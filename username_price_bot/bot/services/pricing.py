"""Heuristic 'real price' estimation.

This is deliberately transparent, not a black box: the estimate is anchored to
hard signals when available (current listing, recent sale) and falls back to a
rough length/pattern heuristic otherwise. Every estimate ships with the signals
it used and an explicit confidence level. It is NOT financial advice.
"""
from __future__ import annotations

from datetime import datetime, timezone

from ..models import Listing, MarketStatus, PriceEstimate, SaleEvent

# Rough base value (TON) by username length when nothing else is known.
_LENGTH_BASE = {1: 20000, 2: 8000, 3: 2500, 4: 700, 5: 200, 6: 90, 7: 45, 8: 25}
_LONG_BASE = 12.0  # 9+ chars

_COMMON_WORDS = {
    "crypto", "money", "wallet", "bank", "game", "games", "music", "news",
    "shop", "store", "love", "king", "queen", "boss", "gold", "vip", "pro",
    "art", "nft", "ton", "bot", "ai", "dev", "code", "web", "app", "cat", "dog",
}


def _quality_multiplier(username: str, signals: list[str]) -> float:
    n = len(username)
    m = 1.0
    if n <= 4:
        m *= 3.0
        signals.append("Короткий (≤4 символов) — премиальный сегмент")
    elif n == 5:
        m *= 1.8
        signals.append("5 символов — повышенный спрос")
    elif n <= 8:
        m *= 1.0
    else:
        m *= 0.6
        signals.append("Длинный юзернейм — спрос ниже")

    if username.isdigit():
        m *= 0.8
        signals.append("Только цифры — обычно дешевле")
    if "_" in username:
        m *= 0.85
        signals.append("Содержит '_' — небольшой дисконт")
    if username.isalpha() and (username in _COMMON_WORDS or _looks_like_word(username)):
        m *= 1.3
        signals.append("Похоже на осмысленное слово — выше спрос")
    return m


def _looks_like_word(username: str) -> bool:
    if not username.isalpha():
        return False
    vowels = sum(c in "aeiou" for c in username)
    return 0 < vowels < len(username)  # has both vowels and consonants


def _heuristic_base(username: str) -> float:
    return float(_LENGTH_BASE.get(len(username), _LONG_BASE))


def estimate_price(
    *,
    username: str,
    listing: Listing | None,
    sales: list[SaleEvent],
    ton_usd: float | None,
    now: datetime | None = None,
) -> PriceEstimate:
    now = now or datetime.now(timezone.utc)
    signals: list[str] = []
    quality = _quality_multiplier(username, signals)

    last_sale = next((s for s in sales if s.price_ton), None)
    point = low = high = None
    confidence = "low"

    listed = listing and listing.price_ton and listing.status in (
        MarketStatus.ON_SALE, MarketStatus.ON_AUCTION,
    )

    if listed:
        ask = listing.price_ton
        signals.insert(0, f"Активный листинг: {ask:g} TON ({listing.source})")
        # Asking price is typically a ceiling; fair value sits a bit below.
        point = ask * 0.92
        low, high = ask * 0.8, ask * 1.0
        confidence = "high"
    elif last_sale and last_sale.price_ton:
        base = last_sale.price_ton
        age_days = (now - last_sale.timestamp).days if last_sale.timestamp else None
        when = f"{age_days} дн. назад" if age_days is not None else "ранее"
        signals.insert(0, f"Последняя продажа: {base:g} TON ({when})")
        point = base
        low, high = base * 0.7, base * 1.3
        if age_days is None:
            confidence = "medium"
        elif age_days < 90:
            confidence = "high"
        elif age_days < 365:
            confidence = "medium"
        else:
            confidence = "low"
            signals.append("Продажа давно — цена могла измениться")
    else:
        base = _heuristic_base(username) * quality
        signals.insert(0, "Нет данных о продажах/листинге — оценка по эвристике")
        point = base
        low, high = base * 0.4, base * 2.0
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
