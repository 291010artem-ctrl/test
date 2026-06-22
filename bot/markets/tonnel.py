from __future__ import annotations

import os
from datetime import datetime

import aiohttp

from .base import MarketClient, MarketResult, Sale, debug

_BASE_URL = "https://gifts2.tonnel.network/api"


class TonnelClient(MarketClient):
    """Tonnel Relayer Market. Unofficial API, auth via Telegram WebApp
    initData copied from market.tonnel.network LocalStorage (key
    "web-initData"). Set TONNEL_INIT_DATA in the environment.

    Endpoint shapes are based on the community wrapper `tonnelmp` and may
    need adjusting if Tonnel changes its backend.
    """

    name = "Tonnel"

    def __init__(self) -> None:
        self.init_data = os.getenv("TONNEL_INIT_DATA", "")

    def _headers(self) -> dict:
        return {"Content-Type": "application/json"}

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        if not self.init_data:
            return MarketResult(market=self.name, available=False, error="missing_auth")

        payload = {
            "page": 1,
            "limit": 10,
            "filter": {"gift_name": model},
            "authData": self.init_data,
        }
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{_BASE_URL}/pageGifts",
                    json=payload,
                    headers=self._headers(),
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    items = await resp.json()
        except Exception as exc:
            return MarketResult(market=self.name, available=False, error=str(exc))

        match = next(
            (g for g in items if str(g.get("gift_num")) == str(number)), None
        )
        if not match:
            return MarketResult(market=self.name, available=False, error="not_found")

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=float(match["price"]) if match.get("price") is not None else None,
            sales_history=await self._sales_history(model),
            url="https://t.me/tonnel_network_bot",
        )

    async def _sales_history(self, model: str) -> list[Sale]:
        payload = {
            "page": 1,
            "limit": 30,
            "type": "SALE",
            "filter": {"gift_name": model},
            "sort": "latest",
            "authData": self.init_data,
        }
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{_BASE_URL}/saleHistory",
                    json=payload,
                    headers=self._headers(),
                    timeout=aiohttp.ClientTimeout(total=10),
                ) as resp:
                    raw_text = await resp.text()
                    debug(self.name, f"history status={resp.status} body={raw_text[:1500]}")
                    resp.raise_for_status()
                    items = await resp.json(content_type=None)
        except Exception as exc:
            debug(self.name, f"history request failed: {exc!r}")
            return []

        sales = []
        for item in items or []:
            price = item.get("price")
            ts = item.get("timestamp") or item.get("date")
            if price is None or ts is None:
                continue
            try:
                sold_at = datetime.fromtimestamp(ts / 1000) if isinstance(ts, (int, float)) else datetime.fromisoformat(ts)
            except (ValueError, TypeError):
                continue
            sales.append(Sale(market=self.name, price_ton=float(price), sold_at=sold_at))
        return sales

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
