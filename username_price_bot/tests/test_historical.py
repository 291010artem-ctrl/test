from datetime import datetime, timezone

from bot.historical import ton_rub_at, ton_usd_at, usd_rub_at


def _d(y, m):
    return datetime(y, m, 1, tzinfo=timezone.utc)


def test_ton_usd_interpolation():
    # Nov 2022 (@bank sale) ~ $1.8 anchor
    assert 1.5 < ton_usd_at(_d(2022, 11)) < 2.2
    # mid-2024 peak is higher than the 2026 low
    assert ton_usd_at(_d(2024, 6)) > ton_usd_at(_d(2026, 6))


def test_out_of_range_clamps():
    assert ton_usd_at(_d(2019, 1)) > 0     # before first anchor
    assert ton_usd_at(_d(2030, 1)) > 0     # after last anchor


def test_usd_rub_and_ton_rub():
    assert usd_rub_at(_d(2022, 6)) == 68
    assert ton_rub_at(_d(2022, 11)) == ton_usd_at(_d(2022, 11)) * 68
