from datetime import datetime, timezone

from bot.formatting import render_report
from bot.models import (
    Listing,
    MarketStatus,
    OwnerPeriod,
    PriceEstimate,
    SaleEvent,
    UsernameReport,
)


def _full_report():
    return UsernameReport(
        username="durov",
        nft_address="0:nft",
        found=True,
        current_owner="0:bbb",
        listing=Listing(status=MarketStatus.ON_SALE, price_ton=1200, source="fragment"),
        estimate=PriceEstimate(
            low_ton=900, high_ton=1300, point_ton=1100, usd_point=5500,
            confidence="high", signals=["Активный листинг: 1200 TON"],
        ),
        sales=[
            SaleEvent(price_ton=850, timestamp=datetime(2024, 1, 1, tzinfo=timezone.utc), kind="sale"),
            SaleEvent(price_ton=500, timestamp=datetime(2023, 1, 1, tzinfo=timezone.utc), kind="sale"),
        ],
        owners=[
            OwnerPeriod(address="0:aaa", since=datetime(2022, 7, 1, tzinfo=timezone.utc),
                        until=datetime(2024, 1, 1, tzinfo=timezone.utc)),
            OwnerPeriod(address="0:bbb", since=datetime(2024, 1, 1, tzinfo=timezone.utc),
                        is_current=True),
        ],
        ton_usd_rate=5.0,
        sources_used=["tonapi", "fragment"],
        fragment_url="https://fragment.com/username/durov",
        getgems_url="https://getgems.io/collection/X/0:nft",
    )


def test_render_contains_key_sections():
    out = render_report(_full_report())
    assert "@durov" in out
    assert "TON" in out
    assert "История продаж" in out
    assert "Кошельки-владельцы" in out
    assert "Fragment" in out
    assert "финансовой рекомендацией" in out
    assert len(out) < 4096  # Telegram message limit


def test_render_escapes_html():
    report = UsernameReport(username="ev<b>il", found=False)
    out = render_report(report)
    assert "<b>il" not in out
    assert "&lt;b&gt;il" in out


def test_render_minimal_report_not_found():
    report = UsernameReport(username="ghost", found=False,
                            fragment_url="https://fragment.com/username/ghost")
    out = render_report(report)
    assert "@ghost" in out
    assert "не найдены" in out or "свободен" in out
