from __future__ import annotations

from datetime import datetime

import aiohttp

from .base import MarketClient, MarketResult, Sale, debug

_TONAPI_BASE = "https://tonapi.io/v2"


class GetgemsClient(MarketClient):
    """Getgems is a standalone TON NFT marketplace, but it has no public
    search-by-name API (their GraphQL backend rejects ad-hoc queries with
    "use the official API", and that official API doesn't actually exist
    with public docs).

    What does work: every NFT traded on Getgems (or any other TON
    marketplace) is a real on-chain item with a TON address, and TonAPI.io
    exposes that on-chain state (including the active marketplace listing)
    for free, no auth needed. So instead of searching Getgems by name, we
    look the item up by its TON address - which only exists if the gift has
    been taken on-chain via Telegram's "Transfer to wallet" action. Most
    gifts haven't been, in which case there's nothing for us (or Getgems) to
    show.
    """

    name = "Getgems"

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        if not address:
            return MarketResult(market=self.name, available=False, error="not_on_chain")

        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_TONAPI_BASE}/nfts/{address}",
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    raw_text = await resp.text()
                    debug(self.name, f"status={resp.status} body={raw_text[:1500]}")
                    resp.raise_for_status()
                    data = await resp.json(content_type=None)
        except Exception as exc:
            debug(self.name, f"request failed: {exc!r}")
            return MarketResult(market=self.name, available=False, error=str(exc))

        sale = data.get("sale")
        current_price_ton = None
        if sale:
            price = sale.get("price") or {}
            if price.get("token_name") == "TON":
                current_price_ton = float(price["value"]) / 1e9

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=current_price_ton,
            sales_history=await self._sales_history(address),
            url=f"https://getgems.io/nft/{address}",
            error=None if current_price_ton is not None else "not_for_sale",
        )

    async def _sales_history(self, address: str) -> list[Sale]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_TONAPI_BASE}/accounts/{address}/nfts/history",
                    params={"limit": 30},
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    raw_text = await resp.text()
                    debug(self.name, f"history status={resp.status} body={raw_text[:1500]}")
                    resp.raise_for_status()
                    data = await resp.json(content_type=None)
        except Exception as exc:
            debug(self.name, f"history request failed: {exc!r}")
            return []

        sales = []
        for event in data.get("events") or []:
            for action in event.get("actions") or []:
                nft_purchase = action.get("NftPurchase")
                if not nft_purchase:
                    continue
                price = nft_purchase.get("price")
                if price is None:
                    continue
                ts = event.get("timestamp")
                if ts is None:
                    continue
                sales.append(
                    Sale(
                        market=self.name,
                        price_ton=float(price) / 1e9,
                        sold_at=datetime.fromtimestamp(ts),
                    )
                )
        return sales

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
