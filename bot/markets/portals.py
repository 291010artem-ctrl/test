from __future__ import annotations

import os
from datetime import datetime

import aiohttp

from .base import MarketClient, MarketResult, Sale, debug

_BASE_URL = "https://portal-market.com/api"


class PortalsClient(MarketClient):
    """Portals Market. Unofficial API, auth via Telegram WebApp token
    (header "Authorization: tma {token}"). The token rotates every 1-7 days
    and must be refreshed by re-opening the Mini App (e.g. via Pyrogram).
    Set PORTALS_AUTH_TOKEN in the environment.

    Endpoint shapes are based on the community wrapper `portalsmp` and may
    need adjusting if Portals changes its backend. The marketplace migrated
    its domain from portals-market.com to portal-market.com at some point
    after that wrapper was last updated (confirmed via the Mini App's
    Network tab); prices there are now denominated in GRAM, not TON.
    """

    name = "Portals"

    def __init__(self) -> None:
        self.token = os.getenv("PORTALS_AUTH_TOKEN", "")

    def _headers(self) -> dict:
        return {
            "Authorization": f"tma {self.token}",
            "Origin": "https://portal-market.com",
            "Referer": "https://portal-market.com/",
        }

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        if not self.token:
            return MarketResult(market=self.name, available=False, error="missing_auth")

        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_BASE_URL}/nfts/search",
                    params={
                        "offset": 0,
                        "limit": 50,
                        "search": model,
                        "status": "listed",
                        "exclude_bundled": "true",
                        "premarket_status": "all",
                    },
                    headers=self._headers(),
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    raw_text = await resp.text()
                    debug(self.name, f"status={resp.status} body={raw_text[:1500]}")
                    resp.raise_for_status()
                    data = await resp.json(content_type=None)
        except Exception as exc:
            debug(self.name, f"request failed: {exc!r}")
            return MarketResult(market=self.name, available=False, error=str(exc))

        results = data.get("results") or []
        match = next((g for g in results if str(g.get("external_collection_number")) == str(number)), None)
        if not match:
            return MarketResult(market=self.name, available=False, error="not_found")

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=float(match["price"]) if match.get("price") is not None else None,
            sales_history=await self._sales_history(model),
            url="https://t.me/portals",
        )

    async def _sales_history(self, model: str) -> list[Sale]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_BASE_URL}/market/actions/",
                    params={"offset": 0, "limit": 50},
                    headers=self._headers(),
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    raw_text = await resp.text()
                    debug(self.name, f"history status={resp.status} body={raw_text[:1500]}")
                    resp.raise_for_status()
                    data = await resp.json(content_type=None)
        except Exception as exc:
            debug(self.name, f"history request failed: {exc!r}")
            return []

        actions = data.get("actions") or []
        sales = []
        for action in actions:
            if action.get("type") != "purchase":
                continue
            nft = action.get("nft") or {}
            if nft.get("name") != model:
                continue
            price = action.get("amount")
            ts = action.get("created_at")
            if price is None or ts is None:
                continue
            try:
                sold_at = datetime.fromisoformat(ts.replace("Z", "+00:00"))
            except (ValueError, TypeError):
                continue
            sales.append(Sale(market=self.name, price_ton=float(price), sold_at=sold_at))
        return sales

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
