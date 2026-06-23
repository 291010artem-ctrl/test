from bot.models import MarketStatus
from bot.services.fragment import FragmentClient


def test_parse_active_auction():
    html = (
        '<div class="tm-section-header">Highest bid</div>'
        '<div class="tm-value">9 215</div><div>Ends in 5h 30m</div>'
    )
    info = FragmentClient._parse(html)
    assert info.status == MarketStatus.ON_AUCTION
    assert info.active_price_ton == 9215


def test_parse_sold():
    html = '<div>Sold</div><div class="tm-value">900 000</div>'
    info = FragmentClient._parse(html)
    assert info.status == MarketStatus.SOLD
    assert info.last_sale_ton == 900000


def test_parse_available():
    html = "<div>This username is available</div>"
    info = FragmentClient._parse(html)
    assert info.status == MarketStatus.AVAILABLE
    assert info.active_price_ton is None
