"""Fragment.com scraper — real parser based on the live page structure.

Parses the public username page:
  • status badge (.tm-section-header-status): Sold / For sale / On auction / Available
  • main box (.tm-section-bid-info): current price/bid + date (label in <th>)
  • "Ownership History" table: actual sales (price + date + buyer)

TonAPI does NOT store Fragment auction/sale prices, so this is the source for
"sold for", last sale and full price history. Best-effort: any failure returns
None and the bot falls back to on-chain data. A browser-like User-Agent is used
because Fragment sits behind Cloudflare.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from ..http_client import HttpClient
from ..models import Listing, MarketStatus

log = logging.getLogger(__name__)

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)
_AMOUNT_RE = re.compile(r"\d[\d  ,]*")


def _amount(text: str | None) -> float | None:
    if not text:
        return None
    m = _AMOUNT_RE.search(text.replace("\xa0", " "))
    if not m:
        return None
    try:
        return float(m.group(0).replace(" ", "").replace(",", ""))
    except ValueError:
        return None


def _dt(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


@dataclass
class FragmentSale:
    price_ton: float
    timestamp: datetime | None
    buyer: str | None = None


@dataclass
class FragmentInfo:
    status: MarketStatus
    active_price_ton: float | None = None   # current bid / asking price
    last_sale_ton: float | None = None
    last_sale_at: datetime | None = None
    auction_ends_at: datetime | None = None
    sales: list[FragmentSale] = field(default_factory=list)  # Ownership History


class FragmentClient:
    def __init__(self, http: HttpClient, base: str):
        self.http = http
        self.base = base.rstrip("/")

    def url_for(self, username: str) -> str:
        return f"{self.base}/username/{username}"

    async def get_info(self, username: str) -> FragmentInfo | None:
        html = await self.http.get_text(
            self.url_for(username),
            headers={"User-Agent": _UA, "Accept": "text/html",
                     "Accept-Language": "en-US,en;q=0.9"},
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
        now = datetime.now(timezone.utc)

        status_el = soup.select_one(".tm-section-header-status")
        status_text = status_el.get_text(" ", strip=True).lower() if status_el else ""

        # Main price box: <th> label, .tm-value amount, <time> date.
        box = soup.select_one(".tm-section-bid-info")
        box_label = box_value = box_dt = None
        if box:
            th = box.select_one("thead th")
            box_label = th.get_text(" ", strip=True).lower() if th else ""
            box_value = _amount(_text(box.select_one(".tm-value")))
            t = box.select_one("time")
            box_dt = _dt(t.get("datetime")) if t else None

        sales = FragmentClient._ownership_history(soup)

        status = MarketStatus.UNKNOWN
        active = last_sale = last_at = ends = None
        if "sold" in status_text:
            status, last_sale, last_at = MarketStatus.SOLD, box_value, box_dt
        elif "available" in status_text:
            status = MarketStatus.AVAILABLE
        elif "bid" in (box_label or "") or "auction" in status_text:
            status, active = MarketStatus.ON_AUCTION, box_value
            if box_dt and box_dt > now:
                ends = box_dt
        elif "price" in (box_label or "") or "sale" in status_text or "buy" in status_text:
            status, active = MarketStatus.ON_SALE, box_value
        elif "taken" in status_text or "unavail" in status_text:
            status = MarketStatus.NOT_LISTED

        if last_sale is None and sales:
            last_sale, last_at = sales[0].price_ton, sales[0].timestamp

        return FragmentInfo(
            status=status, active_price_ton=active, last_sale_ton=last_sale,
            last_sale_at=last_at, auction_ends_at=ends, sales=sales,
        )

    @staticmethod
    def _ownership_history(soup) -> list[FragmentSale]:
        out: list[FragmentSale] = []
        for sec in soup.select("section"):
            head = sec.select_one(".tm-section-header-text")
            if not head or "ownership history" not in head.get_text(" ", strip=True).lower():
                continue
            for tr in sec.select("tbody tr"):
                price = _amount(_text(tr.select_one(".tm-value")))
                if not price:
                    continue
                t = tr.select_one("time")
                a = tr.select_one("a.tm-wallet")
                buyer = a["href"].rsplit("/", 1)[-1] if (a and a.get("href")) else None
                out.append(FragmentSale(price, _dt(t.get("datetime")) if t else None, buyer))
            break
        return out

    def active_listing(self, info: FragmentInfo, username: str) -> Listing | None:
        if info.active_price_ton and info.status in (
            MarketStatus.ON_SALE, MarketStatus.ON_AUCTION
        ):
            return Listing(status=info.status, price_ton=info.active_price_ton,
                           source="fragment", url=self.url_for(username))
        return None


def _text(el) -> str | None:
    return el.get_text(" ", strip=True) if el else None
