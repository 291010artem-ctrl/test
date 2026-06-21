"""Network-free smoke test of the bot pipeline.

It swaps the real marketplace clients and the TON/USD rate calls for fakes,
then runs the full aggregator + formatter exactly as the bot's handler does,
and prints the report a user would receive. Proves the internal logic works
without touching Telegram or any marketplace.

    python -m scripts.smoke_test
"""

from __future__ import annotations

import asyncio
from datetime import datetime

from bot import aggregator, ton_price
from bot.formatting import format_report
from bot.markets.base import ItemKind, MarketClient, MarketResult, Sale


class FakeMarket(MarketClient):
    def __init__(self, name: str, price: float | None, sales: list[Sale]):
        self.name = name
        self._price = price
        self._sales = sales

    async def lookup_gift(self, number: str, model: str) -> MarketResult:
        if self._price is None:
            return MarketResult(market=self.name, available=False, error="not_found")
        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=self._price,
            sales_history=self._sales,
            url=f"https://example/{self.name.lower()}",
        )

    async def lookup_username(self, username: str) -> MarketResult:
        return await self.lookup_gift("", "")

    async def lookup_number(self, number: str) -> MarketResult:
        return await self.lookup_gift("", "")


# Fixed historical TON/USD rates so the test is deterministic and offline.
_FAKE_RATES = {
    datetime(2024, 1, 10).date(): 2.10,
    datetime(2025, 3, 5).date(): 5.40,
}


async def _fake_historical(at: datetime):
    return _FAKE_RATES.get(at.date())


async def _fake_current():
    return 6.00


async def main() -> None:
    # Patch the rate helpers (used by aggregator + handler) to avoid network.
    ton_price.get_historical_ton_usd = _fake_historical
    ton_price.get_current_ton_usd = _fake_current

    # Patch the marketplace roster with deterministic fakes.
    aggregator.ALL_CLIENTS = [
        FakeMarket(
            "Getgems",
            price=12.5,
            sales=[
                Sale("Getgems", 9.0, datetime(2024, 1, 10)),
                Sale("Getgems", 11.0, datetime(2025, 3, 5)),
            ],
        ),
        FakeMarket("Tonnel", price=12.9, sales=[]),
        FakeMarket("Portals", price=13.2, sales=[Sale("Portals", 10.5, datetime(2025, 3, 5))]),
        FakeMarket("mrkt", price=None, sales=[]),   # not found -> goes to "unavailable"
        FakeMarket("Fragment", price=None, sales=[]),
    ]

    results = await aggregator.lookup(ItemKind.GIFT, number="1234", model="Plush Pepe")
    current = await ton_price.get_current_ton_usd()
    report = format_report("🎁 Plush Pepe #1234", results, current)

    print("\n" + "=" * 60)
    print("RENDERED REPORT (what the user receives):")
    print("=" * 60)
    print(report)
    print("=" * 60)

    # Basic assertions so the script fails loudly if the pipeline breaks.
    assert "12.50 TON" in report, "current price missing"
    assert "Getgems" in report and "Portals" in report, "markets missing"
    assert "$" in report, "USD conversion missing"
    assert "История продаж" in report, "sales history missing"
    assert "mrkt" in report, "unavailable section missing"
    print("\nAll pipeline assertions passed ✅")


if __name__ == "__main__":
    asyncio.run(main())
