"""Orchestrates all data sources into a single UsernameReport."""
from __future__ import annotations

import asyncio
import logging
import time
from typing import Awaitable, TypeVar

from .config import Config
from .http_client import HttpClient
from .models import Listing, MarketStatus, UsernameReport
from .services.fragment import FragmentClient
from .services.getgems import GetGemsClient
from .services.pricing import estimate_price
from .services.tonapi import TonApi
from .utils import normalize_username

log = logging.getLogger(__name__)

T = TypeVar("T")


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

        # First wave: independent lookups in parallel.
        rate, nft_addr, frag_listing = await asyncio.gather(
            self._safe(self.tonapi.get_ton_usd(), "tonapi.rate", report),
            self._safe(self.tonapi.resolve_username_nft(username), "tonapi.resolve", report),
            self._safe(self.fragment.get_listing(username), "fragment", report),
        )
        report.ton_usd_rate = rate
        if frag_listing:
            report.sources_used.append("fragment")

        ton_listing = None
        gg_listing = None
        if nft_addr:
            report.found = True
            report.nft_address = nft_addr
            report.sources_used.append("tonapi")
            report.getgems_url = (
                f"https://getgems.io/collection/{self.config.usernames_collection}/{nft_addr}"
            )
            # Second wave: details for the resolved NFT.
            nft, history, gg_listing = await asyncio.gather(
                self._safe(self.tonapi.get_nft(nft_addr), "tonapi.nft", report),
                self._safe(self.tonapi.get_history(nft_addr), "tonapi.history", report),
                self._safe(self.getgems.get_listing(nft_addr), "getgems", report),
            )
            if nft:
                report.current_owner = (nft.get("owner") or {}).get("address")
                ton_listing = TonApi.parse_listing(nft)
            if history:
                report.sales, report.owners = TonApi.parse_history(
                    history, report.current_owner
                )
            if gg_listing:
                report.sources_used.append("getgems")

        # Pick the most authoritative listing: Fragment (live marketplace) first,
        # then on-chain sale, then GetGems.
        report.listing = (
            frag_listing
            or ton_listing
            or gg_listing
            or Listing(status=MarketStatus.NOT_LISTED if report.found else MarketStatus.UNKNOWN)
        )

        report.estimate = estimate_price(
            username=username,
            listing=report.listing,
            sales=report.sales,
            ton_usd=rate,
        )
        return report

    async def _safe(self, awaitable: Awaitable[T], label: str, report: UsernameReport) -> T | None:
        try:
            return await awaitable
        except Exception as exc:  # noqa: BLE001 — every source must fail soft
            log.warning("source %s failed: %s", label, exc)
            report.errors.append(label)
            return None
