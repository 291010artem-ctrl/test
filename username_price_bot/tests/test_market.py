from datetime import datetime, timedelta, timezone

from bot.market import MarketModel, looks_like_word

NOW = datetime(2026, 6, 1, tzinfo=timezone.utc)


def test_appreciation_factor_grows_over_time():
    m = MarketModel()
    f = m.appreciation_factor(
        datetime(2022, 1, 1, tzinfo=timezone.utc),
        datetime(2026, 1, 1, tzinfo=timezone.utc),
    )
    assert f > 3.0  # market grew a lot since 2022
    # same instant -> no change
    assert m.appreciation_factor(NOW, NOW) == 1.0
    # None timestamps -> neutral
    assert m.appreciation_factor(None, NOW) == 1.0


def test_category_typical_shorter_is_pricier():
    m = MarketModel()
    assert m.category_typical("ab") > m.category_typical("abcd")
    assert m.category_typical("abcd") > m.category_typical("abcdefghij")


def test_pattern_multiplier_effects():
    m = MarketModel()
    base = m.category_base("aaaa")
    assert m.category_typical("1234") < base          # all digits cheaper
    assert m.category_typical("a_bc") < m.category_typical("abcd")  # underscore discount


def test_looks_like_word():
    assert looks_like_word("cool")
    assert not looks_like_word("xkcd")   # no vowels
    assert not looks_like_word("1234")   # not alpha


def test_calibrate_updates_category_and_comparables():
    m = MarketModel()
    recent = NOW - timedelta(days=20)
    sales = [(f"wo{i:02d}", 800.0, recent) for i in range(10)]  # 4-letter @ 800
    m.calibrate(sales, now=NOW)
    assert m.calibrated is True
    assert abs(m.category[4] - 800.0) < 1e-6
    value, n = m.comparable_estimate("abcd", now=NOW)
    assert value == 800.0
    assert n == 10


def test_comparables_need_minimum_samples():
    m = MarketModel()
    recent = NOW - timedelta(days=20)
    m.calibrate([("ab12", 500.0, recent), ("cd34", 510.0, recent)], now=NOW)
    value, n = m.comparable_estimate("abcd", now=NOW)
    assert value is None  # only 2 samples < minimum
    assert n == 2


def test_old_comparables_ignored():
    m = MarketModel()
    old = NOW - timedelta(days=900)
    m.calibrate([(f"ab{i:02d}", 500.0, old) for i in range(10)], now=NOW)
    value, _ = m.comparable_estimate("abcd", now=NOW)
    assert value is None  # too old to be comparable
