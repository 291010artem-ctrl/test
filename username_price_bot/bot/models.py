"""Domain models shared across services."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


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
    signals: list[str] = field(default_factory=list)


@dataclass
class UsernameReport:
    username: str
    nft_address: str | None = None
    found: bool = False
    current_owner: str | None = None
    listing: Listing | None = None
    estimate: PriceEstimate | None = None
    sales: list[SaleEvent] = field(default_factory=list)
    owners: list[OwnerPeriod] = field(default_factory=list)
    ton_usd_rate: float | None = None
    sources_used: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    fragment_url: str | None = None
    getgems_url: str | None = None
