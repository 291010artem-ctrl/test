from datetime import datetime, timezone

from bot.formatting import (
    card_text,
    estimate_text,
    is_nft,
    last_sale_text,
    price_text,
    quality_text,
    sales_text,
)
from bot.scoring import analyze
from bot.models import (
    Listing,
    MarketStatus,
    OwnerPeriod,
    PriceEstimate,
    SaleEvent,
    UsernameReport,
)

RATES = {"USD": 5.0, "RUB": 450.0}


def _nft_on_sale():
    return UsernameReport(
        username="durov",
        nft_address="0:nft",
        found=True,
        current_owner="0:bbb",
        listing=Listing(status=MarketStatus.ON_SALE, price_ton=1200, source="fragment"),
        estimate=PriceEstimate(low_ton=900, high_ton=1300, point_ton=1100, usd_point=5500,
                               confidence="high", signals=["Сейчас продаётся за 1200 TON"]),
        sales=[SaleEvent(price_ton=850, timestamp=datetime(2024, 1, 1, tzinfo=timezone.utc), kind="sale")],
        owners=[OwnerPeriod(address="0:bbb", is_current=True)],
        rates=RATES,
        fragment_url="https://fragment.com/username/durov",
        tonviewer_url="https://tonviewer.com/0:nft",
    )


def _nft_not_for_sale():
    r = _nft_on_sale()
    r.listing = Listing(status=MarketStatus.NOT_LISTED)
    return r


def _not_nft():
    return UsernameReport(
        username="somerandomname",
        found=False,
        listing=Listing(status=MarketStatus.UNKNOWN),
        estimate=PriceEstimate(low_ton=6, high_ton=26, point_ton=13, usd_point=65,
                               confidence="low", signals=["Нет продаж и листинга"]),
        rates=RATES,
        fragment_url="https://fragment.com/username/somerandomname",
    )


def test_is_nft_flag():
    assert is_nft(_nft_on_sale()) is True
    assert is_nft(_not_nft()) is False


def test_price_on_sale_has_currencies():
    out = price_text(_nft_on_sale())
    assert "продаётся" in out
    assert "USDT" in out and "₽" in out


def test_price_not_for_sale():
    assert "нигде не продаётся" in price_text(_nft_not_for_sale())


def test_price_non_nft_says_not_nft():
    out = price_text(_not_nft())
    assert "не NFT" in out or "не является NFT" in out.lower() or "это не nft" in out.lower()


def test_sales_lists_history():
    out = sales_text(_nft_on_sale())
    assert "История продаж" in out
    assert "2024-01-01" in out
    assert "TonViewer" in out


def test_sales_non_nft():
    assert "не" in sales_text(_not_nft()).lower()


def test_estimate_has_margin_and_currencies():
    out = estimate_text(_nft_on_sale())
    assert "погрешность" in out
    assert "USDT" in out and "₽" in out
    assert "финансовой рекомендацией" in out


def test_estimate_non_nft_by_appearance():
    out = estimate_text(_not_nft())
    assert "по виду" in out
    assert "Грубая оценка" in out  # low confidence headline


def test_last_sale_section():
    out = last_sale_text(_nft_on_sale())
    assert "Последняя продажа" in out
    assert "2024-01-01" in out
    assert "USDT" in out


def test_last_sale_none():
    r = _nft_not_for_sale()
    r.sales = []
    assert "ни разу не продавался" in last_sale_text(r)


def test_quality_section():
    r = _nft_on_sale()
    r.score = analyze("durov")
    out = quality_text(r)
    assert "Тир" in out
    assert "Редкость" in out and "Брендовость" in out
    assert "множитель" in out


def test_theoretical_estimate_labelled():
    r = _not_nft()
    r.username = "8888"
    r.theoretical = True
    r.score = analyze("8888")
    out = estimate_text(r)
    assert "Теоретическая" in out
    assert "невозможен" in out


def test_card_and_escaping():
    assert "@durov" in card_text(_nft_on_sale())
    r = UsernameReport(username="ev<b>il", found=False, listing=Listing(status=MarketStatus.UNKNOWN))
    out = card_text(r)
    assert "<b>il" not in out
    assert "&lt;b&gt;il" in out
