from __future__ import annotations

import os

import aiohttp

from .base import MarketClient, MarketResult, debug

_BASE_URL = "https://api.tgmrkt.io/api/v1"


class MrktClient(MarketClient):
    """mrkt (tgmrkt). Unofficial API, auth via bearer token obtained by
    POSTing Telegram WebApp initData to /auth. Set MRKT_BEARER_TOKEN in the
    environment once you have one (see github.com/boostNT/MRKT-API).
    """

    name = "mrkt"

    def __init__(self) -> None:
        self.token = os.getenv("MRKT_BEARER_TOKEN", "")

    def _headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.token}",
            "Referer": "https://cdn.tgmrkt.io/",
        }

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        if not self.token:
            return MarketResult(market=self.name, available=False, error="missing_auth")

        payload = {
            "collectionNames": [model],
            "ordering": "Price",
            "count": 20,
        }
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    f"{_BASE_URL}/gifts/saling",
                    json=payload,
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

        gifts = data.get("gifts") or []
        match = next((g for g in gifts if str(g.get("num")) == str(number)), None)
        if not match:
            return MarketResult(market=self.name, available=False, error="not_found")

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=match.get("price"),
            sales_history=[],
            url="https://t.me/mrkt",
        )

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
