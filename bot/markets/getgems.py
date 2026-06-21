from __future__ import annotations

from datetime import datetime

import aiohttp

from .base import MarketClient, MarketResult, Sale

_GRAPHQL_URL = "https://api.getgems.io/graphql"


class GetgemsClient(MarketClient):
    """Getgems is a standalone TON NFT marketplace with a public GraphQL API
    (no Telegram WebApp auth required). Gift/username NFTs that have a Getgems
    collection page can be queried by name search.
    """

    name = "Getgems"

    async def _graphql(self, query: str, variables: dict) -> dict | None:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    _GRAPHQL_URL,
                    json={"query": query, "variables": variables},
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    payload = await resp.json()
                    return payload.get("data")
        except Exception:
            return None

    async def lookup_gift(self, number: str, model: str) -> MarketResult:
        # Getgems names TG-gift NFTs like "<Model> #<number>", e.g. "Plush Pepe #1234"
        nft_name = f"{model} #{number}"
        return await self._search_nft(nft_name)

    async def lookup_username(self, username: str) -> MarketResult:
        return await self._search_nft(f"@{username.lstrip('@')}")

    async def lookup_number(self, number: str) -> MarketResult:
        return await self._search_nft(f"+{number.lstrip('+')}")

    async def _search_nft(self, name: str) -> MarketResult:
        query = """
        query SearchNft($name: String!) {
          alphaNftItemSearch(query: $name, first: 1) {
            edges {
              node {
                address
                name
                sale { ... on NftSaleFixPrice { fullPrice currency } }
                history(first: 10) {
                  edges {
                    node {
                      ... on NftHistoryTransfer {
                        time
                        price
                        currency
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """
        data = await self._graphql(query, {"name": name})
        if not data:
            return MarketResult(market=self.name, available=False, error="no_response")

        edges = data.get("alphaNftItemSearch", {}).get("edges", [])
        if not edges:
            return MarketResult(market=self.name, available=False, error="not_found")

        node = edges[0]["node"]
        sale = node.get("sale")
        current_price_ton = None
        if sale and sale.get("currency") == "TON":
            current_price_ton = float(sale["fullPrice"]) / 1e9

        sales: list[Sale] = []
        for edge in node.get("history", {}).get("edges", []):
            h = edge["node"]
            if h.get("currency") != "TON" or not h.get("price"):
                continue
            sales.append(
                Sale(
                    market=self.name,
                    price_ton=float(h["price"]) / 1e9,
                    sold_at=datetime.fromtimestamp(h["time"]),
                )
            )

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=current_price_ton,
            sales_history=sales,
            url=f"https://getgems.io/nft/{node['address']}",
        )
