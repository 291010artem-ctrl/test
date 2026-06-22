"""Market model for Telegram username NFTs.

The goal is an *honest* estimate. Two ideas drive it:

1. The username market has appreciated a lot since the early Fragment auctions,
   so an old sale must be **re-priced to today** before it is used as an anchor.
   ``appreciation_factor`` does that via a TON-denominated market index by year.

2. A username's value is mostly defined by its **category** (length + pattern).
   ``category_typical`` gives the current typical price for that category, and
   ``comparable_estimate`` refines it from recent sales of *similar* usernames.

Both the index and the category table are editable defaults; when live
collection sales are available they are **calibrated** from real data
(``calibrate``), so the model tracks the actual market instead of guesses.

⚠️ These defaults are rough, TON-denominated, and meant to be updated. They are
chosen so the estimate is never naively low for a premium category.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from statistics import median

# Relative market index for Telegram usernames by year (TON-denominated).
# Encodes "prices grew over time": index[2024] / index[2022] ≈ how much a 2022
# sale should be scaled up to be comparable to 2024. EDITABLE / calibratable.
DEFAULT_MARKET_INDEX: dict[int, float] = {
    2021: 1.0,
    2022: 1.5,
    2023: 3.0,
    2024: 6.0,
    2025: 8.0,
    2026: 9.0,
}

# Current typical price (TON) by username length, reflecting today's market.
DEFAULT_CATEGORY_TON: dict[int, float] = {
    1: 25000.0,
    2: 7000.0,
    3: 2000.0,
    4: 450.0,
    5: 130.0,
    6: 60.0,
    7: 35.0,
    8: 22.0,
}
DEFAULT_LONG_TON = 10.0          # 9+ characters
_LONG_KEY = 9                    # bucket key for "9 or more"

_COMPARABLE_WINDOW = timedelta(days=365)
_MIN_COMPARABLES = 3


def _length_key(username: str) -> int:
    n = len(username)
    return n if n <= 8 else _LONG_KEY


def looks_like_word(username: str) -> bool:
    if not username.isalpha():
        return False
    vowels = sum(c in "aeiou" for c in username.lower())
    return 0 < vowels < len(username)  # has both vowels and consonants


@dataclass
class MarketModel:
    index: dict[int, float] = field(default_factory=lambda: dict(DEFAULT_MARKET_INDEX))
    category: dict[int, float] = field(default_factory=lambda: dict(DEFAULT_CATEGORY_TON))
    long_ton: float = DEFAULT_LONG_TON
    # (length_key, price_ton, timestamp) of recent collection sales, if calibrated
    sales: list[tuple[int, float, datetime | None]] = field(default_factory=list)
    calibrated: bool = False

    # ── time / appreciation ───────────────────────────────────────────────
    def _index_for(self, dt: datetime | None) -> float | None:
        if dt is None:
            return None
        years = sorted(self.index)
        y = dt.year
        if y <= years[0]:
            return self.index[years[0]]
        if y >= years[-1]:
            return self.index[years[-1]]
        base = self.index[y]
        nxt = self.index.get(y + 1, base)
        frac = (dt.month - 1) / 12.0
        return base + (nxt - base) * frac

    def appreciation_factor(self, from_dt: datetime | None, to_dt: datetime | None) -> float:
        """How much a price from ``from_dt`` should be scaled to equal ``to_dt``."""
        a = self._index_for(from_dt)
        b = self._index_for(to_dt)
        if not a or not b or a <= 0:
            return 1.0
        return max(0.25, min(b / a, 50.0))

    # ── category pricing ──────────────────────────────────────────────────
    def pattern_multiplier(self, username: str) -> float:
        m = 1.0
        if username.isdigit():
            m *= 0.8
        if "_" in username:
            m *= 0.85
        if looks_like_word(username):
            m *= 1.3
        return m

    def category_base(self, username: str) -> float:
        key = _length_key(username)
        if key == _LONG_KEY:
            return self.long_ton
        return self.category.get(key, self.long_ton)

    def category_typical(self, username: str) -> float:
        return self.category_base(username) * self.pattern_multiplier(username)

    # ── comparables ───────────────────────────────────────────────────────
    def comparable_estimate(
        self, username: str, now: datetime | None = None
    ) -> tuple[float | None, int]:
        """Median recent sale price of usernames in the same length bucket."""
        if not self.sales:
            return None, 0
        now = now or datetime.now(timezone.utc)
        key = _length_key(username)
        prices = [
            price
            for (k, price, ts) in self.sales
            if k == key and price and ts and (now - ts) <= _COMPARABLE_WINDOW
        ]
        if len(prices) < _MIN_COMPARABLES:
            return None, len(prices)
        return median(prices), len(prices)

    # ── calibration from live data ────────────────────────────────────────
    def calibrate(
        self, raw_sales: list[tuple[str, float, datetime | None]], now: datetime | None = None
    ) -> "MarketModel":
        """Update category typicals from recent real collection sales.

        ``raw_sales`` is a list of (username, price_ton, timestamp).
        """
        now = now or datetime.now(timezone.utc)
        self.sales = [
            (_length_key(name), price, ts)
            for (name, price, ts) in raw_sales
            if name and price and price > 0
        ]
        buckets: dict[int, list[float]] = {}
        for key, price, ts in self.sales:
            if ts and (now - ts) <= _COMPARABLE_WINDOW:
                buckets.setdefault(key, []).append(price)
        for key, prices in buckets.items():
            if len(prices) >= _MIN_COMPARABLES:
                value = median(prices)
                if key == _LONG_KEY:
                    self.long_ton = value
                else:
                    self.category[key] = value
                self.calibrated = True
        return self
