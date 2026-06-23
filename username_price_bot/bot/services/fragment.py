"""Fragment.com scraper — active auction / sale / past-sale (best-effort).

⚠️ Fragment has NO official API. We fetch the public username page and parse it
heuristically, so the markup can change at any time. This is a *fallback*: the
reliable signal for an active sale and past sales is on-chain (TonAPI). Every
failure here is swallowed so the bot keeps working on on-chain data.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup

from ..http_client import HttpClient
from ..models import Listing, MarketStatus

log = logging.getLogger(__name__)

_AMOUNT_RE = re.compile(r"(\d[\d  ,]*\d|\d)")


@dataclass
class FragmentInfo:
    status: MarketStatus
    active_price_ton: float | None = None   # current bid / asking price
    last_sale_ton: float | None = None      # "Sold for ..."


def _parse_amount(text: str) -> float | None:
    if not text:
        return None
    m = _AMOUNT_RE.search(text.replace("\xa0", " "))
    if not m:
        return None
    try:
        return float(m.group(0).replace(" ", "").replace(",", ""))
    except ValueError:
        return None


class FragmentClient:
    def __init__(self, http: HttpClient, base: str):
        self.http = http
        self.base = base.rstrip("/")

    def url_for(self, username: str) -> str:
        return f"{self.base}/username/{username}"

    async def get_info(self, username: str) -> FragmentInfo | None:
        html = await self.http.get_text(
            self.url_for(username),
            headers={"Accept": "text/html", "Accept-Language": "en-US,en;q=0.9"},
        )
        if not html:
            return None
        try:
            return self._parse(html)
        except Exception as exc:  # noqa: BLE001 — best-effort
            log.info("Fragment parse failed for %s: %s", username, exc)
            return None

    @staticmethod
    def _parse(html: str) -> FragmentInfo:
        soup = BeautifulSoup(html, "html.parser")
        text = soup.get_text(" ", strip=True).lower()
        amounts = [
            a for a in (
                _parse_amount(el.get_text(" ", strip=True))
                for el in soup.select(".tm-value, .table-cell-value, .tm-amount")
            ) if a
        ]
        first = amounts[0] if amounts else None

        # Active auction: has a bid AND is still running ("ends in" / time left).
        running = any(k in text for k in ("ends in", "time left", "left to bid", "minutes", "seconds"))
        has_bid = any(k in text for k in ("highest bid", "current bid", "minimum bid"))
        if has_bid and (running or "sold" not in text):
            return FragmentInfo(MarketStatus.ON_AUCTION, active_price_ton=first)
        if "buy now" in text or "for sale" in text or "buy for" in text:
            return FragmentInfo(MarketStatus.ON_SALE, active_price_ton=first)
        if "sold" in text:
            return FragmentInfo(MarketStatus.SOLD, last_sale_ton=first)
        if "available" in text and "unavailable" not in text:
            return FragmentInfo(MarketStatus.AVAILABLE)
        if any(k in text for k in ("taken", "unavailable", "not for sale")):
            return FragmentInfo(MarketStatus.NOT_LISTED)
        return FragmentInfo(MarketStatus.UNKNOWN, active_price_ton=first)

    def active_listing(self, info: FragmentInfo, username: str) -> Listing | None:
        if info.active_price_ton and info.status in (
            MarketStatus.ON_SALE, MarketStatus.ON_AUCTION
        ):
            return Listing(
                status=info.status, price_ton=info.active_price_ton,
                source="fragment", url=self.url_for(username),
            )
        return None
