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
from .models import Listing, MarketStatus, SaleEvent, UsernameReport
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

        # First wave (parallel): rates, on-chain resolve, market, Fragment page.
        rates, nft_addr, market, frag = await asyncio.gather(
            self._safe(self.tonapi.get_rates(), "tonapi.rates", report),
            self._safe(self.tonapi.resolve_username_nft(username), "tonapi.resolve", report),
            self._get_market(),
            self._safe(self.fragment.get_info(username), "fragment", report),
        )
        report.rates = rates or {}

        # On-chain (TonAPI) is the authoritative source for the current sale and
        # for past sales. Fragment is a best-effort fallback (auction/sold) when
        # on-chain data is unavailable.
        onchain_listing = None
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
            nft, history, gg_listing = await asyncio.gather(
                self._safe(self.tonapi.get_nft(nft_addr), "tonapi.nft", report),
                self._safe(self.tonapi.get_history(nft_addr), "tonapi.history", report),
                self._safe(self.getgems.get_listing(nft_addr), "getgems", report),
            )
            if nft:
                # When on sale, nft.owner is the sale contract — the real owner
                # is under sale.owner.
                sale = nft.get("sale")
                if isinstance(sale, dict) and (sale.get("owner") or {}).get("address"):
                    report.current_owner = sale["owner"]["address"]
                else:
                    report.current_owner = (nft.get("owner") or {}).get("address")
                onchain_listing = TonApi.parse_listing(nft)
            if history:
                report.sales, report.owners = TonApi.parse_history(
                    history, report.current_owner
                )

        # Fragment fallback: active listing + past sale, used only to fill gaps.
        frag_listing = self.fragment.active_listing(frag, username) if frag else None
        if frag and frag.status in (MarketStatus.ON_AUCTION, MarketStatus.ON_SALE,
                                    MarketStatus.SOLD):
            report.found = True  # a real (minted) username
            if "fragment" not in report.sources_used:
                report.sources_used.append("fragment")
        if frag and not report.sales and frag.last_sale_ton:
            report.sales = [SaleEvent(price_ton=frag.last_sale_ton, timestamp=None,
                                      kind="sale", source="fragment")]

        report.listing = (
            onchain_listing or frag_listing or gg_listing
            or Listing(status=MarketStatus.NOT_LISTED if report.found else MarketStatus.UNKNOWN)
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
        """Live health-check of the whole pipeline on a real NFT (used by /diag)."""
        out: dict[str, str] = {}
        try:
            rates = await self.tonapi.get_rates()
            out["TonAPI курсы"] = (
                f"✅ TON=${rates.get('USD', 0):.2f}" if rates else "⚠️ пусто"
            )
        except Exception as exc:  # noqa: BLE001
            out["TonAPI курсы"] = f"❌ {type(exc).__name__}"
        try:
            addr = await self.tonapi.resolve_username_nft("bank")  # a real NFT
            if not addr:
                out["TonAPI резолв @bank"] = "❌ не найден (резолв сломан!)"
            else:
                out["TonAPI резолв @bank"] = f"✅ {addr[:12]}…"
                nft = await self.tonapi.get_nft(addr)
                history = await self.tonapi.get_history(addr)
                sale = TonApi.parse_listing(nft) if nft else None
                out["@bank продажа сейчас"] = (
                    f"✅ {sale.price_ton:g} TON" if (sale and sale.price_ton)
                    else "— не продаётся"
                )
                sales, _ = TonApi.parse_history(history or [], None)
                priced = [s for s in sales if s.price_ton]
                out["@bank продаж в истории"] = (
                    f"✅ {len(priced)}" if priced else "⚠️ 0 (проверь parse_history)"
                )
        except Exception as exc:  # noqa: BLE001
            out["TonAPI пайплайн"] = f"❌ {type(exc).__name__}: {exc}"
        try:
            sales = await self.getgems.get_recent_collection_sales(
                self.config.usernames_collection, first=20
            )
            out["GetGems (похожие)"] = (
                f"✅ {len(sales)}" if sales else "⚠️ 0 — не критично, оценка идёт от TonAPI"
            )
        except Exception as exc:  # noqa: BLE001
            out["GetGems (похожие)"] = f"⚠️ {type(exc).__name__} — не критично"
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
