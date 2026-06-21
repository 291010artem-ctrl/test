from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum


class ItemKind(str, Enum):
    GIFT = "gift"
    USERNAME = "username"
    NUMBER = "number"


@dataclass
class Sale:
    """A single historical sale of an item on some marketplace."""

    market: str
    price_ton: float
    sold_at: datetime
    ton_usd_at_sale: float | None = None
    buyer: str | None = None
    seller: str | None = None

    @property
    def price_usd_at_sale(self) -> float | None:
        if self.ton_usd_at_sale is None:
            return None
        return self.price_ton * self.ton_usd_at_sale


@dataclass
class MarketResult:
    """What a single marketplace returns for a price lookup."""

    market: str
    available: bool
    current_price_ton: float | None = None
    sales_history: list[Sale] | None = None
    error: str | None = None
    url: str | None = None


class MarketClient:
    """Common interface every marketplace integration implements."""

    name: str = "base"

    async def lookup_gift(self, number: str, model: str) -> MarketResult:
        raise NotImplementedError

    async def lookup_username(self, username: str) -> MarketResult:
        raise NotImplementedError

    async def lookup_number(self, number: str) -> MarketResult:
        raise NotImplementedError
