from __future__ import annotations

import os
from datetime import datetime

import aiohttp

from .base import MarketClient, MarketResult, Sale, debug

_BASE_URL = "https://portals-market.com/api"


class PortalsClient(MarketClient):
    """Portals Market. Unofficial API, auth via Telegram WebApp token
    (header "Authorization: tma {token}"). The token rotates every 1-7 days
    and must be refreshed by re-opening the Mini App (e.g. via Pyrogram).
    Set PORTALS_AUTH_TOKEN in the environment.

    Endpoint shapes are based on the community wrapper `portalsmp` and may
    need adjusting if Portals changes its backend.
    """

    name = "Portals"

    def __init__(self) -> None:
        self.token = os.getenv("PORTALS_AUTH_TOKEN", "")

    def _headers(self) -> dict:
        return {"Authorization": f"tma {self.token}"}

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        if not self.token:
            return MarketResult(market=self.name, available=False, error="missing_auth")

        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_BASE_URL}/nfts/search",
                    params={"name": model, "gift_num": number},
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
        if not results:
            return MarketResult(market=self.name, available=False, error="not_found")

        item = results[0]
        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=item.get("price"),
            sales_history=await self._sales_history(model),
            url="https://t.me/portals",
        )

    async def _sales_history(self, model: str) -> list[Sale]:
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"{_BASE_URL}/market/actions/",
                    params={
                        "offset": 0,
                        "limit": 30,
                        "sort_by": "listed_at desc",
                        "action_types": "buy",
                        "model": model,
                    },
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

        actions = data if isinstance(data, list) else data.get("actions") or []
        sales = []
        for action in actions or []:
            price = action.get("amount") or action.get("price")
            ts = action.get("created_at") or action.get("listed_at") or action.get("date")
            if price is None or ts is None:
                continue
            try:
                sold_at = (
                    datetime.fromtimestamp(ts / 1000) if isinstance(ts, (int, float)) else datetime.fromisoformat(ts.replace("Z", "+00:00"))
                )
            except (ValueError, TypeError):
                continue
            sales.append(Sale(market=self.name, price_ton=float(price), sold_at=sold_at))
        return sales

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
