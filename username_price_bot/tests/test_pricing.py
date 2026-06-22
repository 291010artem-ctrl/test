from datetime import datetime, timedelta, timezone

from bot.models import Listing, MarketStatus, SaleEvent
from bot.services.pricing import estimate_price

NOW = datetime(2024, 6, 1, tzinfo=timezone.utc)


def _sale(price, days_ago):
    return SaleEvent(
        price_ton=price,
        timestamp=NOW - timedelta(days=days_ago),
        kind="sale",
    )


def test_active_listing_drives_high_confidence():
    listing = Listing(status=MarketStatus.ON_SALE, price_ton=1000, source="fragment")
    est = estimate_price(username="cool", listing=listing, sales=[], ton_usd=5.0, now=NOW)
    assert est.confidence == "high"
    assert est.point_ton == 1000 * 0.92
    assert est.usd_point == est.point_ton * 5.0
    assert est.low_ton < est.point_ton < est.high_ton


def test_recent_sale_high_old_sale_low():
    recent = estimate_price(
        username="cool", listing=None, sales=[_sale(500, 10)], ton_usd=None, now=NOW
    )
    assert recent.confidence == "high"
    assert recent.point_ton == 500

    old = estimate_price(
        username="cool", listing=None, sales=[_sale(500, 800)], ton_usd=None, now=NOW
    )
    assert old.confidence == "low"


def test_heuristic_only_short_username_is_pricier():
    short = estimate_price(username="abc", listing=None, sales=[], ton_usd=None, now=NOW)
    long = estimate_price(
        username="averylongusername", listing=None, sales=[], ton_usd=None, now=NOW
    )
    assert short.confidence == "low"
    assert short.point_ton > long.point_ton > 0
    assert short.signals  # explains its reasoning


def test_usd_filled_when_rate_present():
    est = estimate_price(
        username="cool", listing=None, sales=[_sale(100, 5)], ton_usd=6.0, now=NOW
    )
    assert est.usd_point == 100 * 6.0
