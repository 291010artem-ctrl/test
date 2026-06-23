"""Honest 'real price' estimation.

Design goals, in order: **don't mislead**, then be as accurate as the available
data allows, and always expose *why* and *how sure*.

Two regimes:

A. The username is **actively listed** → the market is quoting a price right now.
   We anchor to that quote (fixed price ≈ ceiling, auction bid ≈ floor) and only
   sanity-check it against comparable sales. We never inflate a real buyable
   price up to a synthetic table value.

B. **Not listed** → blend real signals: recent sales of *similar* usernames
   (best), this NFT's own past sales re-priced to today (weighted down the older
   they are), and the category's typical price as a weak prior. A category
   "floor" may only **lift** a stale/cheap estimate — it never drags a fresh real
   data point down.

Confidence drives the width of the range, and a low-confidence estimate is
explicitly labelled a rough guess. Not financial advice.
"""
from __future__ import annotations

from datetime import datetime, timezone
from typing import TYPE_CHECKING

from ..market import MarketModel, class_label
from ..models import Listing, MarketStatus, PriceEstimate, SaleEvent

if TYPE_CHECKING:
    from ..scoring import Score

_RECENT_DAYS = 120
_MIDTERM_DAYS = 365

# (low_mult, high_mult) applied to the point estimate, by confidence.
_SPREAD = {
    "high": (0.85, 1.15),
    "medium": (0.70, 1.40),
    "low": (0.45, 2.00),
    # heuristic-only but with a strong deterministic signal (dictionary word /
    # clear theme / strong pattern) — narrower than a pure random guess.
    "low_strong": (0.55, 1.60),
}


def _weighted(anchors: list[tuple[float, float]]) -> float:
    total_w = sum(w for _, w in anchors)
    return sum(v * w for v, w in anchors) / total_w if total_w else 0.0


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

    # Heuristic "typical" price = length base × quality multiplier, so two
    # same-length names differ by quality (semantics/brand/theme/pattern).
    if score is not None:
        typical = market.category_base(username) * score.multiplier
    else:
        typical = market.category_typical(username)
    comp_value, comp_n = market.comparable_estimate(username, now)

    # This NFT's own most recent priced sale, re-priced to today.
    last_sale = next((s for s in sales if s.price_ton), None)
    age_days: int | None = None
    adjusted: float | None = None
    if last_sale and last_sale.price_ton:
        if last_sale.timestamp:
            age_days = (now - last_sale.timestamp).days
        factor = market.appreciation_factor(last_sale.timestamp, now)
        adjusted = last_sale.price_ton * factor
    has_recent_own = age_days is not None and age_days <= _RECENT_DAYS

    listed = bool(
        listing
        and listing.price_ton
        and listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )

    # ── Regime A: actively listed ─────────────────────────────────────────
    if listed:
        ask = listing.price_ton
        if listing.status == MarketStatus.ON_AUCTION:
            point = ask  # current/min bid is a floor; it can be bid higher
            lo_m, hi_m = 0.95, 1.40
            signals.append(f"Идёт аукцион, ставка {ask:g} TON (может вырасти)")
        else:
            point = ask * 0.95  # buy-now ask is essentially the price
            lo_m, hi_m = 0.90, 1.00
            signals.append(f"Сейчас продаётся за {ask:g} TON — можно купить")
        confidence = "high"
        if comp_value:
            if ask > comp_value * 2.5:
                signals.append(
                    f"⚠️ Заметно дороже похожих (~{comp_value:.0f} TON) — цена может быть завышена"
                )
                lo_m = min(lo_m, 0.6)
            elif ask < comp_value * 0.5:
                signals.append(
                    f"Дешевле похожих (~{comp_value:.0f} TON) — возможно, выгодно"
                )
                hi_m = max(hi_m, 1.6)
        low, high = point * lo_m, point * hi_m

    # ── Regime B: not listed ──────────────────────────────────────────────
    else:
        anchors: list[tuple[float, float]] = []
        if comp_value:
            anchors.append((comp_value, 3.0))
            signals.append(
                f"Похожие {len(username)}-симв. ({class_label(username)}) "
                f"продаются ~{comp_value:.0f} TON (по {comp_n} продажам)"
            )
        if adjusted is not None and last_sale:
            if has_recent_own:
                weight = 3.0
            elif age_days is not None and age_days <= _MIDTERM_DAYS:
                weight = 2.0
            else:
                weight = 1.0
            anchors.append((adjusted, weight))
            months = max(1, age_days // 30) if age_days is not None else None
            if adjusted > last_sale.price_ton * 1.15 and months is not None:
                signals.append(
                    f"Прошлая продажа {last_sale.price_ton:g} TON (~{months} мес. назад) "
                    f"≈ {adjusted:.0f} TON сегодня с поправкой на рост рынка"
                )
            else:
                when = f" (~{months} мес. назад)" if months is not None else ""
                signals.append(f"Последняя продажа: {last_sale.price_ton:g} TON{when}")

        if anchors:
            point = _weighted(anchors)
        else:
            point = typical
            signals.append(
                f"Нет продаж и листинга — оценка по виду имени "
                f"(длина + качество): ~{typical:.0f} TON"
            )

        # Category floor may only LIFT a stale/cheap estimate (never drag fresh
        # real data down). Skip entirely when we have fresh signals.
        fresh = has_recent_own or bool(comp_value)
        if not fresh:
            floor = typical * 0.7
            if point < floor:
                point = floor
                signals.append(
                    "Поднято до текущего уровня категории — прошлая цена устарела/занижена"
                )

        strong_signal = bool(
            score and (score.semantic >= 8 or score.thematic >= 8 or score.rarity >= 7)
        )
        if has_recent_own:
            confidence = "high"
        elif comp_value or (age_days is not None and age_days <= _MIDTERM_DAYS):
            confidence = "medium"
        else:
            confidence = "low"
        spread_key = "low_strong" if (confidence == "low" and strong_signal) else confidence
        lo_m, hi_m = _SPREAD[spread_key]
        low, high = point * lo_m, point * hi_m

    if confidence == "low":
        signals.append("⚠️ Мало данных — это грубый ориентир, а не точная цена")

    # Guards.
    point = max(point, 0.0)
    high = max(high, point)
    low = max(min(low, point), 0.0)

    usd_point = point * ton_usd if (point and ton_usd) else None
    return PriceEstimate(
        low_ton=low,
        high_ton=high,
        point_ton=point,
        usd_point=usd_point,
        confidence=confidence,
        signals=signals,
    )
