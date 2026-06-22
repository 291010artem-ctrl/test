from __future__ import annotations

import asyncio

from . import ton_price
from .markets.base import ItemKind, MarketClient, MarketResult
from .markets.fragment import FragmentClient
from .markets.getgems import GetgemsClient
from .markets.mrkt import MrktClient
from .markets.portals import PortalsClient
from .markets.telegram_official import TelegramOfficialClient
from .markets.tonnel import TonnelClient

ALL_CLIENTS: list[MarketClient] = [
    TelegramOfficialClient(),
    GetgemsClient(),
    TonnelClient(),
    PortalsClient(),
    MrktClient(),
    FragmentClient(),
]


async def lookup(kind: ItemKind, *, number: str = "", model: str = "", username: str = "") -> list[MarketResult]:
    telegram_client = next((c for c in ALL_CLIENTS if isinstance(c, TelegramOfficialClient)), None)

    # Telegram's official market tells us whether the gift has a TON-chain
    # address; other on-chain markets (Getgems) need that address to look
    # the item up, so resolve it first instead of running everyone blind.
    telegram_result: MarketResult | None = None
    gift_address: str | None = None
    if kind is ItemKind.GIFT and telegram_client is not None:
        try:
            telegram_result = await telegram_client.lookup_gift(number, model)
            gift_address = telegram_result.gift_address
        except Exception as exc:
            telegram_result = MarketResult(market=telegram_client.name, available=False, error=str(exc))

    async def _run(client: MarketClient) -> MarketResult:
        if client is telegram_client and telegram_result is not None:
            return telegram_result
        try:
            if kind is ItemKind.GIFT:
                return await client.lookup_gift(number, model, address=gift_address)
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
