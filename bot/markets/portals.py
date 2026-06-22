from __future__ import annotations

import os

import aiohttp

from .base import MarketClient, MarketResult, debug

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
            sales_history=[],
            url="https://t.me/portals",
        )

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
