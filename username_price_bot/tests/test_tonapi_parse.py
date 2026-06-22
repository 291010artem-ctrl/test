"""Tests for TonAPI history parsing.

The SAMPLE_EVENTS payload below documents the JSON shape this code expects from
``GET /v2/nfts/{address}/history`` (events are returned newest-first).
"""
from bot.services.tonapi import TonApi

# Newest first, as TonAPI returns them.
SAMPLE_EVENTS = [
    {
        "timestamp": 1704067200,  # 2024-01-01 — sale 850 TON
        "actions": [
            {
                "type": "NftPurchase",
                "NftPurchase": {
                    "amount": {"token_name": "TON", "value": "850000000000"},
                    "buyer": {"address": "0:bbb"},
                    "seller": {"address": "0:aaa2"},
                },
            }
        ],
    },
    {
        "timestamp": 1688256000,  # 2023-07-02 — free transfer (no price)
        "actions": [
            {
                "type": "NftItemTransfer",
                "NftItemTransfer": {
                    "sender": {"address": "0:aaa"},
                    "recipient": {"address": "0:aaa2"},
                },
            }
        ],
    },
    {
        "timestamp": 1656720000,  # 2022-07-02 — mint (no sender)
        "actions": [
            {
                "type": "NftItemTransfer",
                "NftItemTransfer": {
                    "sender": None,
                    "recipient": {"address": "0:aaa"},
                },
            }
        ],
    },
]


def test_sales_parsing():
    sales, _ = TonApi.parse_history(SAMPLE_EVENTS, current_owner="0:bbb")
    priced = [s for s in sales if s.price_ton]
    assert len(priced) == 1
    assert priced[0].price_ton == 850.0
    assert priced[0].buyer == "0:bbb"
    assert priced[0].seller == "0:aaa2"


def test_owner_timeline():
    _, owners = TonApi.parse_history(SAMPLE_EVENTS, current_owner="0:bbb")
    addrs = [o.address for o in owners]
    assert addrs == ["0:aaa", "0:aaa2", "0:bbb"]
    # chronological boundaries line up
    assert owners[0].until == owners[1].since
    assert owners[1].until == owners[2].since
    assert owners[-1].is_current is True
    assert owners[0].is_current is False


def test_owner_reconciled_with_authoritative_owner():
    # On-chain current owner differs from last parsed transition -> append it.
    _, owners = TonApi.parse_history(SAMPLE_EVENTS, current_owner="0:ccc")
    assert owners[-1].address == "0:ccc"
    assert owners[-1].is_current is True
    assert sum(o.is_current for o in owners) == 1


def test_empty_history():
    sales, owners = TonApi.parse_history([], current_owner="0:only")
    assert sales == []
    assert len(owners) == 1
    assert owners[0].address == "0:only"
    assert owners[0].is_current is True


def test_parse_listing():
    from bot.models import MarketStatus

    nft = {"sale": {"price": {"token_name": "TON", "value": "1200000000000"},
                    "market": {"name": "getgems"}}}
    listing = TonApi.parse_listing(nft)
    assert listing is not None
    assert listing.price_ton == 1200.0
    assert listing.status == MarketStatus.ON_SALE
    assert TonApi.parse_listing({"sale": None}) is None
