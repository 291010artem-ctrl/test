"""Domain models shared across services."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .scoring import Score


class MarketStatus(str, Enum):
    ON_SALE = "on_sale"        # fixed-price listing
    ON_AUCTION = "on_auction"  # active auction
    SOLD = "sold"              # recently sold, not currently listed
    NOT_LISTED = "not_listed"  # owned, not for sale
    AVAILABLE = "available"    # never minted / free to claim
    UNKNOWN = "unknown"


@dataclass
class SaleEvent:
    """A single price-bearing on-chain event (sale / auction settle / mint)."""
    price_ton: float | None
    timestamp: datetime | None
    kind: str                  # "sale" | "mint" | "transfer"
    buyer: str | None = None
    seller: str | None = None
    source: str = "tonapi"


@dataclass
class OwnerPeriod:
    """A wallet that held the username for a period of time."""
    address: str
    since: datetime | None = None
    until: datetime | None = None
    is_current: bool = False


@dataclass
class Listing:
    """Current marketplace state."""
    status: MarketStatus
    price_ton: float | None = None
    source: str = ""
    url: str | None = None


@dataclass
class PriceEstimate:
    low_ton: float | None
    high_ton: float | None
    point_ton: float | None
    usd_point: float | None
    confidence: str            # "low" | "medium" | "high"
    # what the estimate is based on: active_auction|listing|last_sale|comparables|heuristic
    basis: str = "heuristic"
    signals: list[str] = field(default_factory=list)


@dataclass
class UsernameReport:
    username: str
    nft_address: str | None = None
    found: bool = False
    current_owner: str | None = None
    listing: Listing | None = None
    estimate: PriceEstimate | None = None
    score: "Score | None" = None
    theoretical: bool = False  # username can't really exist on Telegram (e.g. @8888)
    sales: list[SaleEvent] = field(default_factory=list)
    owners: list[OwnerPeriod] = field(default_factory=list)
    rates: dict[str, float] = field(default_factory=dict)  # per 1 TON, e.g. {"USD":5.2,"RUB":470}
    sources_used: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    fragment_url: str | None = None
    getgems_url: str | None = None
    tonviewer_url: str | None = None
    auction_ends_at: datetime | None = None
