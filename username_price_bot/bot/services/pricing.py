"""Two-tier 'real price' estimation.

Tier 1 — real market data dominates (never diluted by the formula):
  • active auction      → current bid × market-expectation coefficient;
  • active fixed sale   → asking price (≈ what you pay now);
  • past sale on record → last sale price, lightly time-adjusted.
Tier 2 — internal formula (fallback only) for unminted / "junk" names:
  • length base × quality multiplier (semantics/theme/brand/pattern).

The `basis` field records which tier produced the number so the UI can show an
honest confidence label. Not financial advice.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import TYPE_CHECKING

from ..market import MarketModel, class_label
from ..models import Listing, MarketStatus, PriceEstimate, SaleEvent

if TYPE_CHECKING:
    from ..scoring import Score

_MIDTERM_DAYS = 365
# Auctions usually close a bit above the current bid.
_AUCTION_COEFF = 1.10
# A fixed-price ask is roughly the price; fair value sits a touch below.
_FIXED_COEFF = 0.97


def estimate_price(
    *,
    username: str,
    listing: Listing | None,
    sales: list[SaleEvent],
    ton_usd: float | None,
    market: MarketModel | None = None,
    score: "Score | None" = None,
    now: datetime | None = None,
) -> PriceEstimate:
    market = market or MarketModel()
    now = now or datetime.now(timezone.utc)
    signals: list[str] = []

    heuristic = (
        market.category_base(username) * score.multiplier if score
        else market.category_typical(username)
    )
    last_sale = next((s for s in sales if s.price_ton), None)
    comp_value, comp_n = market.comparable_estimate(username, now)
    listed = bool(
        listing and listing.price_ton
        and listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )

    # ── TIER 1: active market ────────────────────────────────────────────
    if listed and listing.status == MarketStatus.ON_AUCTION:
        point = listing.price_ton * _AUCTION_COEFF
        basis, confidence, (lo, hi) = "active_auction", "high", (0.95, 1.40)
        src = f" ({listing.source})" if listing.source else ""
        signals.append(
            f"Активный аукцион{src}: ставка {listing.price_ton:g} TON "
            f"(+ожидание рынка ×{_AUCTION_COEFF})"
        )
    elif listed:
        point = listing.price_ton * _FIXED_COEFF
        basis, confidence, (lo, hi) = "listing", "high", (0.92, 1.00)
        src = f" ({listing.source})" if listing.source else ""
        signals.append(f"Сейчас продаётся{src}: {listing.price_ton:g} TON — можно купить")

    # ── TIER 1b: past sale on record — take the last sale price as the base ──
    elif last_sale and last_sale.price_ton:
        age_days = (now - last_sale.timestamp).days if last_sale.timestamp else None
        point = last_sale.price_ton          # the sale price IS the basis
        basis = "last_sale"
        confidence = "high" if (age_days is not None and age_days <= _MIDTERM_DAYS) else "medium"
        lo, hi = (0.80, 1.25) if confidence == "high" else (0.60, 1.45)
        months = max(1, age_days // 30) if age_days is not None else None
        when = f" (~{months} мес. назад)" if months is not None else ""
        signals.append(f"Последняя продажа: {last_sale.price_ton:g} TON{when}")
        # Only an OLD (medium-confidence) cheap sale gets lifted to the current
        # category level — a fresh sale is trusted as-is.
        if confidence != "high":
            floor = heuristic * 0.7
            if point < floor:
                point = floor
                signals.append("Поднято до уровня категории (прошлая цена устарела)")

    # ── TIER 2a: comparable recent sales ─────────────────────────────────
    elif comp_value:
        point = comp_value
        basis, confidence, (lo, hi) = "comparables", "medium", (0.70, 1.40)
        signals.append(
            f"Похожие {len(username)}-симв. ({class_label(username)}) "
            f"продаются ~{comp_value:.0f} TON (по {comp_n} продажам)"
        )

    # ── TIER 2b: internal formula (fallback) ─────────────────────────────
    else:
        point = heuristic
        basis, confidence = "heuristic", "low"
        strong = bool(score and (score.semantic >= 8 or score.thematic >= 8 or score.rarity >= 7))
        lo, hi = (0.55, 1.60) if strong else (0.45, 2.00)
        signals.append(
            f"Нет продаж и листинга — расчёт по виду имени (длина + качество): "
            f"~{heuristic:.0f} TON"
        )
        if score and (score.theme or score.patterns):
            bits = []
            if score.theme:
                bits.append(f"тема: {score.theme}")
            if score.patterns:
                bits.append("паттерн: " + ", ".join(score.patterns[:2]))
            signals.append("🏷 " + " · ".join(bits))
        signals.append("⚠️ Мало данных — это грубый ориентир, а не точная цена")

    low, high = point * lo, point * hi
    high = max(high, point)
    low = max(min(low, point), 0.0)
    usd_point = point * ton_usd if (point and ton_usd) else None
    return PriceEstimate(
        low_ton=low, high_ton=high, point_ton=point, usd_point=usd_point,
        confidence=confidence, basis=basis, signals=signals,
    )
