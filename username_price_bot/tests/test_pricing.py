from datetime import datetime, timedelta, timezone

from bot.market import MarketModel
from bot.models import Listing, MarketStatus, SaleEvent
from bot.scoring import analyze
from bot.services.pricing import estimate_price

NOW = datetime(2026, 6, 1, tzinfo=timezone.utc)


def _sale(price, days_ago):
    return SaleEvent(price_ton=price, timestamp=NOW - timedelta(days=days_ago), kind="sale")


def _est(username="zxqw", **kw):
    kw.setdefault("ton_usd", None)
    kw.setdefault("now", NOW)
    kw.setdefault("market", MarketModel())
    kw.setdefault("score", analyze(username))
    return estimate_price(username=username, **kw)


# ── TIER 1: active market dominates ──────────────────────────────────────
def test_active_auction_uses_bid_plus_coefficient():
    e = _est(listing=Listing(status=MarketStatus.ON_AUCTION, price_ton=9215), sales=[])
    assert e.basis == "active_auction"
    assert e.confidence == "high"
    assert e.point_ton == 9215 * 1.10        # bid + market expectation, NOT /100
    assert e.high_ton > e.point_ton


def test_fixed_price_listing_is_the_price():
    e = _est(listing=Listing(status=MarketStatus.ON_SALE, price_ton=1000), sales=[], ton_usd=5.0)
    assert e.basis == "listing"
    assert e.point_ton == 1000 * 0.97
    assert e.usd_point == e.point_ton * 5.0


# ── TIER 1b: past sale dominates the formula (the @bank bug) ──────────────
def test_big_past_sale_dominates_formula():
    e = _est(username="bank", listing=None, sales=[_sale(900_000, 30)])
    assert e.basis == "last_sale"
    assert e.confidence == "high"
    assert e.point_ton > 800_000            # NOT the ~1300 formula value


def test_recent_sale_trusted_not_lifted():
    e = _est(listing=None, sales=[_sale(80, 10)])
    assert e.basis == "last_sale"
    assert e.confidence == "high"
    assert 70 <= e.point_ton <= 95          # trusted as-is, not floored up


def test_old_cheap_sale_is_medium_and_not_face_value():
    old = SaleEvent(price_ton=50, timestamp=datetime(2022, 1, 1, tzinfo=timezone.utc), kind="sale")
    e = _est(listing=None, sales=[old])
    assert e.basis == "last_sale"
    assert e.confidence == "medium"
    assert e.point_ton > 50                 # re-priced toward today's market


# ── TIER 2: comparables, then formula fallback ───────────────────────────
def test_comparables_when_no_sale():
    m = MarketModel()
    m.calibrate([(f"zx{i:02d}", 600.0, NOW - timedelta(days=30)) for i in range(12)], now=NOW)
    e = _est(listing=None, sales=[], market=m)
    assert e.basis == "comparables"
    assert e.confidence == "medium"
    assert 450 <= e.point_ton <= 750


def test_formula_fallback_is_low_confidence():
    e = _est(listing=None, sales=[])
    assert e.basis == "heuristic"
    assert e.confidence == "low"
    assert e.point_ton > 0
    assert any("грубый ориентир" in s for s in e.signals)


def test_real_sale_beats_formula():
    formula = _est(listing=None, sales=[]).point_ton
    withsale = _est(listing=None, sales=[_sale(5000, 20)]).point_ton
    assert withsale > formula * 5           # the real sale, not the formula
