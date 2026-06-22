"""GetGems GraphQL client — secondary marketplace data (best-effort).

GetGems exposes a GraphQL API at https://api.getgems.io/graphql. The exact
schema is not officially documented and may change, so this client is fully
defensive: any error returns None and the report is built from TonAPI/Fragment.
Treat the result as a *hint* (e.g. an extra current listing), never as required.
"""
from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from typing import Any

from ..http_client import HttpClient
from ..models import Listing, MarketStatus

_NAME_CLEAN = re.compile(r"[^a-z0-9_]")

log = logging.getLogger(__name__)

_QUERY = """
query NftItem($address: String!) {
  nftItemByAddress(address: $address) {
    address
    name
    sale {
      __typename
      ... on NftSaleFixPrice { fullPrice }
      ... on NftSaleAuction { minBid lastBidAmount }
    }
  }
}
""".strip()

# Experimental: recent sold items of a collection, used to price "similar"
# usernames. Schema is unofficial — failures return [] and the bot falls back
# to the built-in category model.
_COLLECTION_SALES_QUERY = """
query CollectionSales($address: String!, $first: Int!) {
  historyByCollectionAddress(address: $address, kinds: ["Sold"], first: $first) {
    items {
      time
      nft { name }
      typeData { __typename ... on HistoryTypeSold { amount } }
    }
  }
}
""".strip()


def _clean_name(raw: str | None) -> str:
    if not raw:
        return ""
    name = raw.strip().lower().lstrip("@").split(".", 1)[0]
    return _NAME_CLEAN.sub("", name)


class GetGemsClient:
    def __init__(self, http: HttpClient, endpoint: str, api_key: str | None):
        self.http = http
        self.endpoint = endpoint
        self._headers = {"Content-Type": "application/json"}
        if api_key:
            self._headers["Authorization"] = api_key

    async def get_listing(self, nft_address: str) -> Listing | None:
        try:
            data = await self.http.post_json(
                self.endpoint,
                json={"query": _QUERY, "variables": {"address": nft_address}},
                headers=self._headers,
            )
        except Exception as exc:  # noqa: BLE001 — best-effort source
            log.info("GetGems query failed: %s", exc)
            return None
        return self._parse(data)

    async def get_recent_collection_sales(
        self, collection: str, first: int = 200
    ) -> list[tuple[str, float, datetime | None]]:
        """Recent sold usernames in the collection: (name, price_ton, time).

        Best-effort — returns [] on any error or schema mismatch.
        """
        try:
            data = await self.http.post_json(
                self.endpoint,
                json={
                    "query": _COLLECTION_SALES_QUERY,
                    "variables": {"address": collection, "first": first},
                },
                headers=self._headers,
            )
        except Exception as exc:  # noqa: BLE001 — best-effort source
            log.info("GetGems collection-sales query failed: %s", exc)
            return []
        return self._parse_sales(data)

    @staticmethod
    def _parse_sales(data: Any) -> list[tuple[str, float, datetime | None]]:
        try:
            items = data["data"]["historyByCollectionAddress"]["items"]
        except (KeyError, TypeError):
            return []
        out: list[tuple[str, float, datetime | None]] = []
        for it in items or []:
            try:
                name = _clean_name((it.get("nft") or {}).get("name"))
                amount = (it.get("typeData") or {}).get("amount")
                price = int(amount) / 1_000_000_000 if amount else None
                ts = it.get("time")
                dt = datetime.fromtimestamp(int(ts), tz=timezone.utc) if ts else None
            except (TypeError, ValueError, OSError):
                continue
            if name and price:
                out.append((name, price, dt))
        return out

    @staticmethod
    def _parse(data: Any) -> Listing | None:
        try:
            item = data["data"]["nftItemByAddress"]
        except (KeyError, TypeError):
            return None
        if not item:
            return None
        sale = item.get("sale") or {}
        typename = sale.get("__typename", "")
        raw = sale.get("fullPrice") or sale.get("minBid") or sale.get("lastBidAmount")
        price = None
        try:
            if raw is not None:
                price = int(raw) / 1_000_000_000
        except (TypeError, ValueError):
            price = None
        if price is None:
            return None
        status = (
            MarketStatus.ON_AUCTION
            if "Auction" in typename
            else MarketStatus.ON_SALE
        )
        return Listing(status=status, price_ton=price, source="getgems")
