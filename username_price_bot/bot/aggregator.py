"""Orchestrates all data sources into a single UsernameReport."""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone
from typing import Awaitable, TypeVar

from .config import Config
from .http_client import HttpClient
from .market import MarketModel
from .models import Listing, MarketStatus, UsernameReport
from .scoring import analyze
from .services.fragment import FragmentClient
from .services.getgems import GetGemsClient
from .services.pricing import estimate_price
from .services.tonapi import TonApi
from .utils import is_valid_telegram_username, normalize_username

log = logging.getLogger(__name__)

T = TypeVar("T")

# Collection-wide sales change slowly; refresh the market model infrequently.
_MARKET_TTL = 3600.0


class Aggregator:
    def __init__(
        self,
        config: Config,
        http: HttpClient,
        tonapi: TonApi,
        fragment: FragmentClient,
        getgems: GetGemsClient,
    ):
        self.config = config
        self.http = http
        self.tonapi = tonapi
        self.fragment = fragment
        self.getgems = getgems
        self._cache: dict[str, tuple[float, UsernameReport]] = {}
        self._market_cache: tuple[float, MarketModel] | None = None

    async def get_report(self, raw: str) -> UsernameReport | None:
        username = normalize_username(raw)
        if not username:
            return None

        cached = self._cache.get(username)
        if cached and (time.monotonic() - cached[0]) < self.config.cache_ttl:
            return cached[1]

        report = await self._build(username)
        self._cache[username] = (time.monotonic(), report)
        return report

    async def _build(self, username: str) -> UsernameReport:
        report = UsernameReport(username=username)
        report.fragment_url = self.fragment.url_for(username)
        report.theoretical = not is_valid_telegram_username(username)
        report.score = analyze(username)

        # First wave: independent lookups in parallel.
        rates, nft_addr, market = await asyncio.gather(
            self._safe(self.tonapi.get_rates(), "tonapi.rates", report),
            self._safe(self.tonapi.resolve_username_nft(username), "tonapi.resolve", report),
            self._get_market(),
        )
        report.rates = rates or {}

        # Whether it is *currently* for sale is taken from the on-chain sale
        # contract (TonAPI), NOT from scraping Fragment — that is the only
        # reliable signal and avoids reporting long-ended auctions as active.
        listing = None
        nft = None
        gg_listing = None
        if nft_addr:
            report.found = True
            report.nft_address = nft_addr
            report.sources_used.append("tonapi")
            report.getgems_url = (
                f"https://getgems.io/collection/{self.config.usernames_collection}/{nft_addr}"
            )
            report.tonviewer_url = f"https://tonviewer.com/{nft_addr}"
            # Second wave: details for the resolved NFT.
            nft, history, gg_listing = await asyncio.gather(
                self._safe(self.tonapi.get_nft(nft_addr), "tonapi.nft", report),
                self._safe(self.tonapi.get_history(nft_addr), "tonapi.history", report),
                self._safe(self.getgems.get_listing(nft_addr), "getgems", report),
            )
            if nft:
                report.current_owner = (nft.get("owner") or {}).get("address")
                listing = TonApi.parse_listing(nft)  # authoritative; None => not for sale
            elif gg_listing:
                listing = gg_listing  # fallback only when the on-chain fetch failed
            if history:
                report.sales, report.owners = TonApi.parse_history(
                    history, report.current_owner
                )

        report.listing = listing or Listing(
            status=MarketStatus.NOT_LISTED if report.found else MarketStatus.UNKNOWN
        )

        report.estimate = estimate_price(
            username=username,
            listing=report.listing,
            sales=report.sales,
            ton_usd=report.rates.get("USD"),
            market=market,
            score=report.score,
        )
        if market.calibrated and "getgems" not in report.sources_used:
            report.sources_used.append("getgems")
        return report

    async def diagnose(self) -> dict[str, str]:
        """Live health-check of each data source (used by /diag)."""
        out: dict[str, str] = {}
        try:
            rates = await self.tonapi.get_rates()
            out["TonAPI курсы"] = (
                f"✅ TON=${rates.get('USD', '?')}" if rates else "⚠️ пусто"
            )
        except Exception as exc:  # noqa: BLE001
            out["TonAPI курсы"] = f"❌ {type(exc).__name__}"
        try:
            addr = await self.tonapi.resolve_username_nft("durov")
            out["TonAPI резолв @durov"] = f"✅ {addr[:10]}…" if addr else "⚠️ не найден"
        except Exception as exc:  # noqa: BLE001
            out["TonAPI резолв @durov"] = f"❌ {type(exc).__name__}"
        try:
            sales = await self.getgems.get_recent_collection_sales(
                self.config.usernames_collection, first=20
            )
            out["GetGems продажи коллекции"] = (
                f"✅ получено {len(sales)}" if sales
                else "⚠️ 0 (схема не совпала или нет данных)"
            )
        except Exception as exc:  # noqa: BLE001
            out["GetGems продажи коллекции"] = f"❌ {type(exc).__name__}"
        return out

    async def _get_market(self) -> MarketModel:
        """Market model calibrated from recent collection sales (cached)."""
        if self._market_cache and (time.monotonic() - self._market_cache[0]) < _MARKET_TTL:
            return self._market_cache[1]
        model = MarketModel()
        try:
            sales = await self.getgems.get_recent_collection_sales(
                self.config.usernames_collection
            )
            if sales:
                model.calibrate(sales, datetime.now(timezone.utc))
                log.info("market calibrated from %d collection sales", len(sales))
        except Exception as exc:  # noqa: BLE001 — fall back to default model
            log.warning("market calibration failed: %s", exc)
        self._market_cache = (time.monotonic(), model)
        return model

    async def _safe(self, awaitable: Awaitable[T], label: str, report: UsernameReport) -> T | None:
        try:
            return await awaitable
        except Exception as exc:  # noqa: BLE001 — every source must fail soft
            log.warning("source %s failed: %s", label, exc)
            report.errors.append(label)
            return None
