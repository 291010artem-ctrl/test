from __future__ import annotations

import re

import aiohttp
from bs4 import BeautifulSoup

from .base import MarketClient, MarketResult

_BASE_URL = "https://fragment.com"


class FragmentClient(MarketClient):
    """fragment.com has no public API - this scrapes the public auction
    pages for usernames and anonymous numbers. Selectors are based on the
    page structure at the time of writing and may break if Fragment changes
    its markup; treat this as best-effort.
    """

    name = "Fragment"

    async def _fetch(self, path: str) -> str | None:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_BASE_URL}{path}",
                    headers={"User-Agent": "Mozilla/5.0"},
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    if resp.status != 200:
                        return None
                    return await resp.text()
        except Exception:
            return None

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_username(self, username: str) -> MarketResult:
        username = username.lstrip("@")
        html = await self._fetch(f"/username/{username}")
        if not html:
            return MarketResult(market=self.name, available=False, error="not_found")
        return self._parse_listing_page(html, f"/username/{username}")

    async def lookup_number(self, number: str) -> MarketResult:
        digits = re.sub(r"\D", "", number)
        html = await self._fetch(f"/number/{digits}")
        if not html:
            return MarketResult(market=self.name, available=False, error="not_found")
        return self._parse_listing_page(html, f"/number/{digits}")

    def _parse_listing_page(self, html: str, path: str) -> MarketResult:
        soup = BeautifulSoup(html, "html.parser")
        price_el = soup.select_one(".tm-section-amount, .table-cell-value.tm-value")
        current_price_ton = None
        if price_el:
            text = price_el.get_text(strip=True).replace(",", "")
            match = re.search(r"[\d.]+", text)
            if match:
                current_price_ton = float(match.group())

        return MarketResult(
            market=self.name,
            available=current_price_ton is not None,
            current_price_ton=current_price_ton,
            sales_history=[],
            url=f"{_BASE_URL}{path}",
            error=None if current_price_ton is not None else "not_for_sale",
        )
