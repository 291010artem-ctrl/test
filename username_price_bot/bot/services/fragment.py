"""Fragment.com scraper — current marketplace price / status.

⚠️  Fragment has NO official public API. We fetch the public username page
(https://fragment.com/username/<name>) and parse it best-effort. The markup can
change at any time, so every failure here is swallowed and the bot falls back to
on-chain data from TonAPI. Keep the heuristics forgiving.
"""
from __future__ import annotations

import logging
import re

from bs4 import BeautifulSoup

from ..http_client import HttpClient
from ..models import Listing, MarketStatus

log = logging.getLogger(__name__)

_AMOUNT_RE = re.compile(r"(\d[\d  ,]*\d|\d)")  # digits with spaces/commas/nbsp


def _parse_amount(text: str) -> float | None:
    if not text:
        return None
    m = _AMOUNT_RE.search(text.replace("\xa0", " "))
    if not m:
        return None
    cleaned = m.group(0).replace(" ", "").replace(",", "")
    try:
        return float(cleaned)
    except ValueError:
        return None


class FragmentClient:
    def __init__(self, http: HttpClient, base: str):
        self.http = http
        self.base = base.rstrip("/")

    def url_for(self, username: str) -> str:
        return f"{self.base}/username/{username}"

    async def get_listing(self, username: str) -> Listing | None:
        url = self.url_for(username)
        html = await self.http.get_text(
            url,
            headers={
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "en-US,en;q=0.9",
            },
        )
        if not html:
            return None
        return self._parse(html, url)

    @staticmethod
    def _parse(html: str, url: str) -> Listing | None:
        soup = BeautifulSoup(html, "html.parser")
        text = soup.get_text(" ", strip=True).lower()

        status = MarketStatus.UNKNOWN
        if "minimum bid" in text or "highest bid" in text or "ends in" in text:
            status = MarketStatus.ON_AUCTION
        elif "buy now" in text or "for sale" in text:
            status = MarketStatus.ON_SALE
        elif "available" in text and "unavailable" not in text:
            status = MarketStatus.AVAILABLE
        elif "sold" in text:
            status = MarketStatus.SOLD
        elif "taken" in text or "unavailable" in text or "not for sale" in text:
            status = MarketStatus.NOT_LISTED

        # Fragment renders TON amounts in elements carrying the `tm-value` class.
        price = None
        for el in soup.select(".tm-value, .table-cell-value, .tm-amount"):
            price = _parse_amount(el.get_text(" ", strip=True))
            if price:
                break

        if status == MarketStatus.UNKNOWN and price is None:
            return None
        return Listing(status=status, price_ton=price, source="fragment", url=url)
