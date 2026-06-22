"""Honest 'real price' estimation.

The estimate is a weighted blend of *anchors*, strongest first:

  • active listing price (a ceiling — fair value sits a bit below);
  • this NFT's own past sales, **re-priced to today** via the market index
    (so an old cheap sale doesn't undervalue a now-expensive username);
  • recent sales of **similar** usernames (same length bucket);
  • the current typical price for the username's category (always a backstop).

It never falls misleadingly below the current market floor for the category,
ships with the signals it used and an explicit confidence level, and is NOT
financial advice.
"""
from __future__ import annotations

from datetime import datetime, timezone

from ..market import MarketModel
from ..models import Listing, MarketStatus, PriceEstimate, SaleEvent

_RECENT_OWN_SALE_DAYS = 120


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
    now: datetime | None = None,
) -> PriceEstimate:
    market = market or MarketModel()
    now = now or datetime.now(timezone.utc)
    signals: list[str] = []

    typical = market.category_typical(username)
    anchors: list[tuple[float, float]] = []  # (value_ton, weight)

    # 1) Active listing — strongest, but treated as a ceiling.
    listed = bool(
        listing
        and listing.price_ton
        and listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )
    if listed:
        ask = listing.price_ton
        anchors.append((ask * 0.92, 3.0))
        signals.append(f"Активный листинг: {ask:g} TON (потолок цены)")

    # 2) Comparable recent sales of similar usernames.
    comp_value, comp_n = market.comparable_estimate(username, now)
    if comp_value:
        anchors.append((comp_value, 2.5))
        signals.append(
            f"Похожие {len(username)}-символьные продаются ~{comp_value:.0f} TON "
            f"(по {comp_n} недавним продажам)"
        )

    # 3) This NFT's own last sale, re-priced to today.
    last_sale = next((s for s in sales if s.price_ton), None)
    age_days: int | None = None
    if last_sale and last_sale.price_ton:
        factor = market.appreciation_factor(last_sale.timestamp, now)
        adjusted = last_sale.price_ton * factor
        anchors.append((adjusted, 2.0))
        if last_sale.timestamp:
            age_days = (now - last_sale.timestamp).days
        if factor > 1.15 and age_days is not None:
            months = max(1, age_days // 30)
            signals.append(
                f"Прошлая продажа {last_sale.price_ton:g} TON (~{months} мес. назад) "
                f"≈ {adjusted:.0f} TON сегодня с учётом роста рынка"
            )
        else:
            signals.append(f"Последняя продажа: {last_sale.price_ton:g} TON")

    # 4) Resolve a point estimate.
    if anchors:
        point = _weighted(anchors)
    else:
        point = typical
        signals.append(
            f"Нет данных о продажах/листинге — оценка по текущему рынку категории "
            f"(~{typical:.0f} TON за {len(username)}-символьный)"
        )

    # Floor: never mislead with a stale-low value for a premium category.
    floor = typical * 0.7
    if point < floor:
        point = floor
        signals.append(
            "Оценка поднята до актуального уровня рынка для этой категории "
            "(прошлые цены устарели)"
        )

    # Range from the spread of anchors around the point.
    candidate_vals = [v for v, _ in anchors] + [point]
    low = min(candidate_vals) * 0.85
    high = max(candidate_vals) * 1.15
    low = max(low, typical * 0.5)

    # Confidence.
    has_recent_own = age_days is not None and age_days <= _RECENT_OWN_SALE_DAYS
    if listed or has_recent_own:
        confidence = "high"
    elif comp_value or last_sale:
        confidence = "medium"
    else:
        confidence = "low"

    usd_point = point * ton_usd if (point and ton_usd) else None
    return PriceEstimate(
        low_ton=low,
        high_ton=high,
        point_ton=point,
        usd_point=usd_point,
        confidence=confidence,
        signals=signals,
    )
