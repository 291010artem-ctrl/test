from __future__ import annotations

import os
import time
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
        sales_history = await self._sales_history(model)

        if not match:
            return MarketResult(
                market=self.name,
                available=True,
                current_price_ton=None,
                sales_history=sales_history,
                error="not_for_sale",
                url="https://t.me/portals",
            )

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=float(match["price"]) if match.get("price") is not None else None,
            sales_history=sales_history,
            url="https://t.me/portals",
        )

    async def _sales_history(self, model: str) -> list[Sale]:
        """Return completed sales of this model.

        Portals only exposes a single *global* activity feed at
        /market/actions/ — the last N actions across the whole marketplace,
        with no working server-side filter (the filter params the old
        `portalsmp` wrapper documents trip an anti-bot rule and the endpoint
        then lies with 401 "auth sign is invalid"). So a single page almost
        never contains a given model. We page through the feed ourselves and
        keep the purchases whose gift name matches.
        """
        model_norm = model.strip().casefold()
        sales: list[Sale] = []
        seen: set[str] = set()
        limit = 50
        max_pages = 20
        deadline = time.monotonic() + 20
        page = 0

        async with aiohttp.ClientSession() as session:
            for page in range(max_pages):
                if time.monotonic() > deadline:
                    break
                try:
                    async with session.get(
                        f"{_BASE_URL}/market/actions/",
                        params={"offset": page * limit, "limit": limit},
                        headers=self._headers(),
                        timeout=aiohttp.ClientTimeout(total=8),
                    ) as resp:
                        raw_text = await resp.text()
                        if page == 0:
                            debug(self.name, f"history status={resp.status} body={raw_text[:800]}")
                        resp.raise_for_status()
                        data = await resp.json(content_type=None)
                except Exception as exc:
                    debug(self.name, f"history request failed (page {page}): {exc!r}")
                    break

                actions = data.get("actions") or []
                if not actions:
                    break

                page_had_new = False
                for action in actions:
                    nft = action.get("nft") or {}
                    sig = f"{nft.get('id')}|{action.get('type')}|{action.get('created_at')}|{action.get('amount')}"
                    if sig in seen:
                        continue
                    seen.add(sig)
                    page_had_new = True

                    if action.get("type") != "purchase":
                        continue
                    if (nft.get("name") or "").strip().casefold() != model_norm:
                        continue
                    price = action.get("amount")
                    ts = action.get("created_at")
                    if price is None or ts is None:
                        continue
                    try:
                        sold_at = datetime.fromisoformat(str(ts).replace("Z", "+00:00"))
                        price_ton = float(price)
                    except (ValueError, TypeError):
                        continue
                    sales.append(Sale(market=self.name, price_ton=price_ton, sold_at=sold_at))

                # Stop once the feed is exhausted, pagination stalls, or we have plenty.
                if not page_had_new or len(actions) < limit or len(sales) >= 25:
                    break

        debug(self.name, f"history: {len(sales)} sale(s) for {model!r} after {page + 1} page(s)")
        return sales

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
