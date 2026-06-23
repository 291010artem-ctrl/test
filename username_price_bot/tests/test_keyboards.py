from bot.keyboards import (
    CB_HELP,
    CB_MAIN,
    CB_SOON,
    CB_VALUATION,
    card_kb,
    est_kb,
    main_menu_kb,
    price_kb,
    sales_kb,
)
from bot.models import Listing, MarketStatus, UsernameReport


def _callbacks(kb):
    return [b.callback_data for row in kb.inline_keyboard for b in row if b.callback_data]


def _urls(kb):
    return [b.url for row in kb.inline_keyboard for b in row if b.url]


def _report(on_sale=False, tonviewer=True):
    status = MarketStatus.ON_SALE if on_sale else MarketStatus.NOT_LISTED
    price = 1000 if on_sale else None
    return UsernameReport(
        username="durov",
        found=True,
        listing=Listing(status=status, price_ton=price),
        fragment_url="https://fragment.com/username/durov",
        tonviewer_url="https://tonviewer.com/0:nft" if tonviewer else None,
    )


def test_main_menu_has_functions():
    cbs = _callbacks(main_menu_kb())
    assert CB_VALUATION in cbs
    assert CB_SOON in cbs
    assert CB_HELP in cbs


def test_card_kb_has_sections_and_tonviewer():
    kb = card_kb(_report(tonviewer=True))
    cbs = _callbacks(kb)
    assert "price:durov" in cbs
    assert "last:durov" in cbs
    assert "sales:durov" in cbs
    assert "est:durov" in cbs
    assert "rate:durov" in cbs
    assert CB_MAIN in cbs
    assert "https://tonviewer.com/0:nft" in _urls(kb)


def test_card_kb_without_tonviewer():
    kb = card_kb(_report(tonviewer=False))
    assert _urls(kb) == []


def test_price_kb_has_fragment_link_and_back():
    # Fragment link is always offered (buy when on sale, "see price" otherwise).
    assert _urls(price_kb(_report(on_sale=True)))
    assert _urls(price_kb(_report(on_sale=False)))
    assert "card:durov" in _callbacks(price_kb(_report(on_sale=True)))


def test_sales_and_est_back_to_card():
    assert "card:durov" in _callbacks(sales_kb(_report()))
    assert "card:durov" in _callbacks(est_kb(_report()))
    assert "https://tonviewer.com/0:nft" in _urls(sales_kb(_report()))
