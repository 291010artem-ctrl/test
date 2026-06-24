"""Approximate historical TON→USD and USD→RUB, for showing the value of a past
sale "at that moment".

TON is volatile and there is no verified free historical endpoint wired in, so
these are rough, editable monthly/yearly anchors with linear interpolation.
Everything is shown with "≈" in the UI. Replace with a real rates-chart source
when available (e.g. TonAPI /v2/rates/chart).
"""
from __future__ import annotations

from datetime import datetime

# Rough TON price in USD by month (YYYY-MM -> USD). Ballpark, editable.
_TON_USD: dict[str, float] = {
    "2021-08": 2.5, "2021-11": 4.5,
    "2022-01": 2.8, "2022-06": 1.2, "2022-09": 1.3, "2022-11": 1.8, "2022-12": 2.0,
    "2023-03": 2.0, "2023-06": 1.6, "2023-09": 2.1, "2023-12": 2.2,
    "2024-03": 4.0, "2024-06": 7.0, "2024-09": 5.5, "2024-12": 6.0,
    "2025-03": 4.5, "2025-06": 3.0, "2025-09": 2.6, "2025-12": 2.3,
    "2026-06": 1.56,
}

# Rough USD/RUB by year.
_USD_RUB: dict[int, float] = {
    2021: 74, 2022: 68, 2023: 85, 2024: 92, 2025: 95, 2026: 90,
}


def _to_num(year: int, month: int) -> float:
    return year + (month - 1) / 12.0


_POINTS = sorted((_to_num(int(k[:4]), int(k[5:7])), v) for k, v in _TON_USD.items())


def ton_usd_at(dt: datetime) -> float:
    """Approximate TON price in USD at the given date (linear interpolation)."""
    x = _to_num(dt.year, dt.month)
    if x <= _POINTS[0][0]:
        return _POINTS[0][1]
    if x >= _POINTS[-1][0]:
        return _POINTS[-1][1]
    for i in range(1, len(_POINTS)):
        x1, v1 = _POINTS[i]
        if x <= x1:
            x0, v0 = _POINTS[i - 1]
            return v0 + (v1 - v0) * (x - x0) / (x1 - x0)
    return _POINTS[-1][1]


def usd_rub_at(dt: datetime) -> float:
    if dt.year in _USD_RUB:
        return _USD_RUB[dt.year]
    return _USD_RUB[max(_USD_RUB)] if dt.year > max(_USD_RUB) else _USD_RUB[min(_USD_RUB)]


def ton_rub_at(dt: datetime) -> float:
    return ton_usd_at(dt) * usd_rub_at(dt)
