from __future__ import annotations

from datetime import datetime, timezone

import aiohttp

_COINGECKO_ID = "the-open-network"
_BASE_URL = "https://api.coingecko.com/api/v3"

_cache: dict[str, float] = {}


async def get_current_ton_usd() -> float | None:
    """Current TON/USD rate, cached for the lifetime of the process call."""
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{_BASE_URL}/simple/price",
                params={"ids": _COINGECKO_ID, "vs_currencies": "usd"},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                data = await resp.json()
                return data[_COINGECKO_ID]["usd"]
    except Exception:
        return None


async def get_historical_ton_usd(at: datetime) -> float | None:
    """TON/USD rate on the given date (CoinGecko only has daily granularity)."""
    date_str = at.astimezone(timezone.utc).strftime("%d-%m-%Y")
    cache_key = date_str
    if cache_key in _cache:
        return _cache[cache_key]

    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(
                f"{_BASE_URL}/coins/{_COINGECKO_ID}/history",
                params={"date": date_str, "localization": "false"},
                timeout=aiohttp.ClientTimeout(total=10),
            ) as resp:
                data = await resp.json()
                price = data["market_data"]["current_price"]["usd"]
                _cache[cache_key] = price
                return price
    except Exception:
        return None
