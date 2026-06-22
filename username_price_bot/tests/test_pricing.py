from datetime import datetime, timedelta, timezone

from bot.market import MarketModel
from bot.models import Listing, MarketStatus, SaleEvent
from bot.services.pricing import estimate_price

NOW = datetime(2026, 6, 1, tzinfo=timezone.utc)


def _sale(price, days_ago):
    return SaleEvent(price_ton=price, timestamp=NOW - timedelta(days=days_ago), kind="sale")


def _market_with_4letter(price, n=12):
    """Calibrated model: n recent 4-letter ('other' class) sales at `price`."""
    recent = NOW - timedelta(days=30)
    m = MarketModel()
    m.calibrate([(f"zx{i:02d}", float(price), recent) for i in range(n)], now=NOW)
    return m


# ── Regime A: active listing is trusted, never inflated to a table value ──
def test_fixed_price_listing_is_the_price():
    listing = Listing(status=MarketStatus.ON_SALE, price_ton=1000, source="fragment")
    est = estimate_price(username="zxqw", listing=listing, sales=[], ton_usd=5.0, now=NOW)
    assert est.confidence == "high"
    assert est.point_ton == 1000 * 0.95
    assert est.low_ton < est.point_ton <= est.high_ton
    assert est.usd_point == est.point_ton * 5.0


def test_cheap_listing_is_not_inflated_to_category_floor():
    """A 4-letter buyable at 100 TON must NOT be reported as ~315 TON."""
    listing = Listing(status=MarketStatus.ON_SALE, price_ton=100, source="fragment")
    est = estimate_price(username="zxqw", listing=listing, sales=[], ton_usd=None, now=NOW)
    assert est.point_ton < 150  # close to the real ask, not the table floor
    assert est.confidence == "high"


def test_auction_bid_is_a_floor_not_a_ceiling():
    listing = Listing(status=MarketStatus.ON_AUCTION, price_ton=500, source="fragment")
    est = estimate_price(username="zxqw", listing=listing, sales=[], ton_usd=None, now=NOW)
    assert est.point_ton >= 500          # not discounted below the current bid
    assert est.high_ton > est.point_ton  # can be bid higher


def test_listing_far_above_comparables_is_flagged():
    listing = Listing(status=MarketStatus.ON_SALE, price_ton=5000, source="fragment")
    market = _market_with_4letter(500)
    est = estimate_price(username="zxqw", listing=listing, sales=[], ton_usd=None,
                         now=NOW, market=market)
    assert any("завышен" in s for s in est.signals)


# ── Regime B: blend of real signals, floor only lifts stale/cheap values ──
def test_recent_sale_is_trusted_not_inflated():
    """A recent real sale wins over the synthetic category floor."""
    est = estimate_price(username="zxqw", listing=None, sales=[_sale(80, 30)],
                         ton_usd=None, now=NOW)
    assert est.point_ton < 150     # ~80, not lifted to the 4-letter table floor
    assert est.confidence == "high"


def test_old_cheap_4letter_is_lifted_to_market():
    """User's example: 4-letter sold long ago for 50 TON is NOT worth 50 now."""
    old = SaleEvent(price_ton=50, timestamp=datetime(2022, 1, 1, tzinfo=timezone.utc),
                    kind="sale")
    est = estimate_price(username="zxqw", listing=None, sales=[old], ton_usd=None,
                         now=NOW, market=MarketModel())
    assert est.point_ton > 250
    assert est.confidence == "low"
    assert any("категори" in s for s in est.signals)
    assert any("грубый ориентир" in s for s in est.signals)


def test_old_expensive_long_is_not_dragged_down():
    """An old sale ABOVE the generic category typical must not be pulled down."""
    old = SaleEvent(price_ton=100, timestamp=datetime(2022, 6, 1, tzinfo=timezone.utc),
                    kind="sale")
    est = estimate_price(username="averageword", listing=None, sales=[old],
                         ton_usd=None, now=NOW)
    assert est.point_ton > 150   # adjusted upward from 100, NOT down to ~13
    assert any("сегодня" in s for s in est.signals)


def test_comparables_drive_estimate_when_no_own_sale():
    market = _market_with_4letter(600)
    est = estimate_price(username="zxqw", listing=None, sales=[], ton_usd=None,
                         now=NOW, market=market)
    assert est.confidence == "medium"
    assert 450 <= est.point_ton <= 750
    assert any("Похожие" in s for s in est.signals)


def test_no_data_is_low_confidence_rough_guess():
    est = estimate_price(username="zxqw", listing=None, sales=[], ton_usd=None, now=NOW)
    assert est.confidence == "low"
    assert est.point_ton > 0
    assert any("грубый ориентир" in s for s in est.signals)
    # wide range reflects the uncertainty
    assert est.high_ton / est.point_ton >= 1.8


def test_shorter_is_pricier_than_longer_heuristic():
    short = estimate_price(username="zxq", listing=None, sales=[], ton_usd=None, now=NOW)
    long = estimate_price(username="averylongusername", listing=None, sales=[],
                          ton_usd=None, now=NOW)
    assert short.point_ton > long.point_ton > 0
