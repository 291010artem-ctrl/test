"""Offline test for Portals sales-history pagination/filtering.

Mocks the HTTP layer so we can verify the logic without the live API:
- pages through the global /market/actions/ feed
- keeps only completed purchases ("type": "purchase")
- matches the gift name case-insensitively
- ignores price_update entries
- dedups and stops when pagination stalls (offset ignored by server)

Run: python -m scripts.test_portals_history
"""
from __future__ import annotations

import asyncio
import json
from unittest import mock

from bot.markets import portals
from bot.markets.portals import PortalsClient


class FakeResp:
    status = 200

    def __init__(self, actions):
        self._payload = {"actions": actions}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def text(self):
        return json.dumps(self._payload)

    def raise_for_status(self):
        pass

    async def json(self, content_type=None):
        return self._payload


class FakeSession:
    """Paginates correctly: respects offset/limit."""

    def __init__(self, feed):
        self.feed = feed

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def get(self, url, params=None, headers=None, timeout=None):
        off, lim = params["offset"], params["limit"]
        return FakeResp(self.feed[off:off + lim])


class StuckSession(FakeSession):
    """Simulates a server that ignores offset (always returns page 0)."""

    def get(self, url, params=None, headers=None, timeout=None):
        return FakeResp(self.feed[: params["limit"]])


def build_feed():
    feed = []
    # Page 0 (offset 0): 50 purchases of an unrelated model.
    for i in range(50):
        feed.append({
            "nft": {"id": f"x{i}", "name": "Lol Pop", "external_collection_number": i},
            "type": "purchase", "amount": "1.0", "created_at": "2026-06-22T10:00:00Z",
        })
    # Page 1 (offset 50): 5 Nail Bracelet purchases, some price_updates, rest noise.
    for i in range(50):
        if i % 10 == 0:
            feed.append({
                "nft": {"id": f"nb{i}", "name": "Nail Bracelet", "external_collection_number": 4000 + i},
                "type": "purchase", "amount": str(5.0 + i / 10), "created_at": f"2026-06-21T0{i % 10}:00:00Z",
            })
        elif i % 10 == 1:
            feed.append({  # price_update must be ignored
                "nft": {"id": f"nbp{i}", "name": "Nail Bracelet", "external_collection_number": 4000 + i},
                "type": "price_update", "amount": "9.99", "created_at": "2026-06-21T05:00:00Z",
            })
        else:
            feed.append({
                "nft": {"id": f"y{i}", "name": "Snoop Dogg", "external_collection_number": i},
                "type": "purchase", "amount": "2.0", "created_at": "2026-06-21T11:00:00Z",
            })
    # Page 2 (offset 100): 20 lowercase "nail bracelet" purchases (case-insensitivity).
    for i in range(20):
        feed.append({
            "nft": {"id": f"nb2{i}", "name": "nail bracelet", "external_collection_number": 4200 + i},
            "type": "purchase", "amount": "6.5", "created_at": "2026-06-20T08:00:00Z",
        })
    return feed


def main():
    feed = build_feed()

    # --- Test 1: correct pagination finds matches spread across pages ---
    with mock.patch.object(portals.aiohttp, "ClientSession", lambda *a, **k: FakeSession(feed)):
        sales = asyncio.run(PortalsClient()._sales_history("Nail Bracelet"))
    assert len(sales) == 25, f"expected 25 sales (5 on page1 + 20 on page2), got {len(sales)}"
    assert all(s.market == "Portals" for s in sales)
    assert all(s.price_ton > 0 for s in sales)
    print(f"Test 1 OK: found {len(sales)} Nail Bracelet sales across 3 pages (price_updates ignored)")

    # --- Test 2: a model with no sales returns empty, doesn't crash ---
    with mock.patch.object(portals.aiohttp, "ClientSession", lambda *a, **k: FakeSession(feed)):
        none = asyncio.run(PortalsClient()._sales_history("Plush Pepe"))
    assert none == [], f"expected no sales, got {len(none)}"
    print("Test 2 OK: unknown model returns empty history")

    # --- Test 3: server ignores offset -> must not loop forever, no double counts ---
    with mock.patch.object(portals.aiohttp, "ClientSession", lambda *a, **k: StuckSession(feed)):
        stuck = asyncio.run(PortalsClient()._sales_history("Lol Pop"))
    assert len(stuck) == 50, f"expected 50 page-0 sales with no duplicates, got {len(stuck)}"
    print(f"Test 3 OK: stalled pagination handled (got {len(stuck)} unique sales, no infinite loop)")

    print("\nALL TESTS PASSED")


if __name__ == "__main__":
    main()
