"""GetGems GraphQL client — secondary marketplace data (best-effort).

GetGems exposes a GraphQL API at https://api.getgems.io/graphql. The exact
schema is not officially documented and may change, so this client is fully
defensive: any error returns None and the report is built from TonAPI/Fragment.
Treat the result as a *hint* (e.g. an extra current listing), never as required.
"""
from __future__ import annotations

import logging
from typing import Any

from ..http_client import HttpClient
from ..models import Listing, MarketStatus

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
