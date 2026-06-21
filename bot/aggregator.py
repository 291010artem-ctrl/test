from __future__ import annotations

import asyncio

from . import ton_price
from .markets.base import ItemKind, MarketClient, MarketResult
from .markets.fragment import FragmentClient
from .markets.getgems import GetgemsClient
from .markets.mrkt import MrktClient
from .markets.portals import PortalsClient
from .markets.tonnel import TonnelClient

ALL_CLIENTS: list[MarketClient] = [
    GetgemsClient(),
    TonnelClient(),
    PortalsClient(),
    MrktClient(),
    FragmentClient(),
]


async def lookup(kind: ItemKind, *, number: str = "", model: str = "", username: str = "") -> list[MarketResult]:
    async def _run(client: MarketClient) -> MarketResult:
        try:
            if kind is ItemKind.GIFT:
                return await client.lookup_gift(number, model)
            if kind is ItemKind.USERNAME:
                return await client.lookup_username(username)
            return await client.lookup_number(number)
        except Exception as exc:
            return MarketResult(market=client.name, available=False, error=str(exc))

    results = await asyncio.gather(*(_run(c) for c in ALL_CLIENTS))

    # Fill in the TON/USD rate for every historical sale across all markets.
    distinct_dates = {
        sale.sold_at.date()
        for r in results
        if r.sales_history
        for sale in r.sales_history
    }
    rates = await asyncio.gather(
        *(ton_price.get_historical_ton_usd(_to_datetime(d)) for d in distinct_dates)
    )
    rate_by_date = dict(zip(distinct_dates, rates))

    for r in results:
        if not r.sales_history:
            continue
        for sale in r.sales_history:
            sale.ton_usd_at_sale = rate_by_date.get(sale.sold_at.date())

    return list(results)


def _to_datetime(d):
    from datetime import datetime, time

    return datetime.combine(d, time())
