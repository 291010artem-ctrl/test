from datetime import datetime, timedelta, timezone

from bot.market import MarketModel
from bot.models import Listing, MarketStatus, SaleEvent
from bot.services.pricing import estimate_price

NOW = datetime(2026, 6, 1, tzinfo=timezone.utc)


def _sale(price, days_ago):
    return SaleEvent(
        price_ton=price,
        timestamp=NOW - timedelta(days=days_ago),
        kind="sale",
    )


def test_active_listing_drives_high_confidence():
    listing = Listing(status=MarketStatus.ON_SALE, price_ton=1000, source="fragment")
    est = estimate_price(username="qwed", listing=listing, sales=[], ton_usd=5.0, now=NOW)
    assert est.confidence == "high"
    # single strong anchor -> the listing (treated as a ceiling)
    assert est.point_ton == 1000 * 0.92
    assert est.usd_point == est.point_ton * 5.0
    assert est.low_ton < est.point_ton < est.high_ton


def test_recent_sale_is_high_confidence_and_not_inflated():
    est = estimate_price(
        username="averageword", listing=None, sales=[_sale(500, 10)], ton_usd=None, now=NOW
    )
    assert est.confidence == "high"
    # very recent sale -> appreciation ~1.0, value close to the sale price
    assert 450 <= est.point_ton <= 560


def test_old_cheap_4letter_is_repriced_to_market():
    """User's example: a 4-letter sold long ago for 50 TON is NOT worth 50 now."""
    old = SaleEvent(price_ton=50, timestamp=datetime(2022, 1, 1, tzinfo=timezone.utc),
                    kind="sale")
    est = estimate_price(
        username="zxqw",  # 4 letters, not a word
        listing=None, sales=[old], ton_usd=None, now=NOW, market=MarketModel(),
    )
    # Must be lifted well above the stale 50 TON toward the current 4-letter market.
    assert est.point_ton > 250
    assert est.confidence in ("medium", "low")
    assert any("рост" in s or "рынк" in s for s in est.signals)


def test_appreciation_reflected_in_signal():
    old = SaleEvent(price_ton=100, timestamp=datetime(2022, 6, 1, tzinfo=timezone.utc),
                    kind="sale")
    est = estimate_price(
        username="averageword", listing=None, sales=[old], ton_usd=None, now=NOW
    )
    # 2022 -> 2026 index growth -> adjusted value clearly above the raw 100 TON
    assert est.point_ton > 150
    assert any("сегодня" in s for s in est.signals)


def test_comparables_anchor_estimate():
    market = MarketModel()
    recent = NOW - timedelta(days=30)
    # 30 recent sales of 4-letter usernames around 600 TON
    market.calibrate([(f"ab{i:02d}", 600.0, recent) for i in range(30)], now=NOW)
    est = estimate_price(
        username="wxyz", listing=None, sales=[], ton_usd=None, now=NOW, market=market
    )
    assert est.confidence == "medium"
    assert 450 <= est.point_ton <= 750
    assert any("Похожие" in s for s in est.signals)


def test_heuristic_only_short_username_is_pricier():
    short = estimate_price(username="zxq", listing=None, sales=[], ton_usd=None, now=NOW)
    long = estimate_price(
        username="averylongusername", listing=None, sales=[], ton_usd=None, now=NOW
    )
    assert short.confidence == "low"
    assert short.point_ton > long.point_ton > 0
    assert short.signals
