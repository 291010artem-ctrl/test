from bot.models import MarketStatus
from bot.services.fragment import FragmentClient

# Trimmed to the structurally-relevant parts of the real @bank page.
SOLD_HTML = """
<span class="tm-section-header-status tm-status-unavail">Sold</span>
<div class="tm-section-box tm-section-bid-info">
  <table class="table">
    <thead><tr><th>Sale Price</th><th>Owner</th></tr></thead>
    <tbody>
      <td><div class="table-cell-value tm-value icon-before icon-ton">850,000</div></td>
      <td><a href="https://tonviewer.com/Ef-exuKIowner" class="tm-wallet">w</a></td>
    </tbody>
  </table>
  <div class="tm-bid-info-text">Purchased on <time datetime="2022-11-02T20:38:12+00:00">3 Nov 2022</time></div>
</div>
<section class="tm-section">
  <div class="tm-section-header"><h3 class="tm-section-header-text">Ownership History</h3></div>
  <table class="table"><tbody>
    <tr>
      <td><div class="table-cell-value tm-value icon-before icon-ton">850,000</div></td>
      <td><time datetime="2022-11-02T20:38:12+00:00">3 Nov 2022</time></td>
      <td><a href="https://tonviewer.com/Ef-exuKIbuyer" class="tm-wallet">w</a></td>
    </tr>
  </tbody></table>
</section>
"""

AUCTION_HTML = """
<span class="tm-section-header-status tm-status-avail">On auction</span>
<div class="tm-section-bid-info">
  <table><thead><tr><th>Minimum Bid</th><th>Bidder</th></tr></thead>
  <tbody><td><div class="tm-value icon-before icon-ton">5,050</div></td><td></td></tbody></table>
  <div class="tm-bid-info-text">Ends <time datetime="2099-01-01T00:00:00+00:00">soon</time></div>
</div>
"""

SALE_HTML = """
<span class="tm-section-header-status tm-status-avail">For sale</span>
<div class="tm-section-bid-info">
  <table><thead><tr><th>Price</th><th>Seller</th></tr></thead>
  <tbody><td><div class="tm-value">30,000</div></td><td></td></tbody></table>
</div>
"""


def test_parse_sold_with_ownership_history():
    info = FragmentClient._parse(SOLD_HTML)
    assert info.status == MarketStatus.SOLD
    assert info.last_sale_ton == 850000
    assert info.last_sale_at and info.last_sale_at.year == 2022
    assert len(info.sales) == 1
    assert info.sales[0].price_ton == 850000
    assert info.sales[0].buyer.endswith("buyer")


def test_parse_active_auction_with_deadline():
    info = FragmentClient._parse(AUCTION_HTML)
    assert info.status == MarketStatus.ON_AUCTION
    assert info.active_price_ton == 5050
    assert info.auction_ends_at is not None  # future deadline parsed


def test_parse_fixed_sale():
    info = FragmentClient._parse(SALE_HTML)
    assert info.status == MarketStatus.ON_SALE
    assert info.active_price_ton == 30000


def test_parse_available():
    info = FragmentClient._parse(
        '<span class="tm-section-header-status tm-status-avail">Available</span>'
    )
    assert info.status == MarketStatus.AVAILABLE
