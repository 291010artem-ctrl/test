"""TonAPI client — the reliable on-chain backbone.

Docs: https://tonapi.io/api-v2  (REST, v2)

Endpoints used:
  GET /v2/dns/{name}.t.me          -> resolve username to its NFT item
  GET /v2/nfts/{address}           -> current owner + active sale
  GET /v2/nfts/{address}/history   -> transfer / purchase events (AccountEvents)
  GET /v2/rates?tokens=ton...      -> TON/USD rate

The exact JSON shapes for the NFT *history* are parsed defensively in
``parse_history`` — see ``tests/test_tonapi_parse.py`` for a sample payload
documenting the structure this code expects.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import TYPE_CHECKING, Any

from ..models import Listing, MarketStatus, OwnerPeriod, SaleEvent

if TYPE_CHECKING:  # avoid importing aiohttp just to parse data (keeps tests light)
    from ..http_client import HttpClient

log = logging.getLogger(__name__)

NANO = 1_000_000_000


def _ton(value: Any) -> float | None:
    """Convert a nanoton string/int to TON."""
    try:
        return int(value) / NANO
    except (TypeError, ValueError):
        return None


def _dt(ts: Any) -> datetime | None:
    try:
        return datetime.fromtimestamp(int(ts), tz=timezone.utc)
    except (TypeError, ValueError, OSError):
        return None


class TonApi:
    def __init__(self, http: HttpClient, base: str, api_key: str | None, collection: str):
        self.http = http
        self.base = base.rstrip("/")
        self.collection = collection
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}

    # ── network ──────────────────────────────────────────────────────────────
    async def resolve_username_nft(self, username: str) -> str | None:
        """Resolve a Telegram username to its NFT item address via TON DNS."""
        domain = f"{username}.t.me"
        data = await self.http.get_json(
            f"{self.base}/v2/dns/{domain}", headers=self._headers
        )
        if not isinstance(data, dict):
            return None
        # A username backed by an NFT is returned under "item".
        item = data.get("item")
        if isinstance(item, dict) and item.get("address"):
            return item["address"]
        return None

    async def get_nft(self, address: str) -> dict[str, Any] | None:
        data = await self.http.get_json(
            f"{self.base}/v2/nfts/{address}", headers=self._headers
        )
        return data if isinstance(data, dict) else None

    async def get_history(self, address: str, limit: int = 100) -> list[dict[str, Any]]:
        data = await self.http.get_json(
            f"{self.base}/v2/nfts/{address}/history",
            params={"limit": limit},
            headers=self._headers,
        )
        if isinstance(data, dict) and isinstance(data.get("events"), list):
            return data["events"]
        return []

    async def get_rates(self, currencies: list[str] | None = None) -> dict[str, float]:
        """TON price in the requested fiat/crypto currencies, e.g. {"USD":5.2,"RUB":470}."""
        currencies = currencies or ["usd", "rub"]
        data = await self.http.get_json(
            f"{self.base}/v2/rates",
            params={"tokens": "ton", "currencies": ",".join(currencies)},
            headers=self._headers,
        )
        out: dict[str, float] = {}
        try:
            prices = data["rates"]["TON"]["prices"]
        except (KeyError, TypeError):
            return out
        for code, value in (prices or {}).items():
            try:
                out[code.upper()] = float(value)
            except (TypeError, ValueError):
                continue
        return out

    # ── parsing (pure) ───────────────────────────────────────────────────────
    @staticmethod
    def parse_listing(nft: dict[str, Any]) -> Listing | None:
        """Build a Listing from the ``sale`` block of an NFT item, if present."""
        sale = nft.get("sale")
        if not isinstance(sale, dict):
            return None
        price = _ton((sale.get("price") or {}).get("value"))
        market = (sale.get("market") or {}).get("name", "")
        return Listing(
            status=MarketStatus.ON_SALE,
            price_ton=price,
            source=f"tonapi/{market}".rstrip("/"),
        )

    @staticmethod
    def parse_history(
        events: list[dict[str, Any]], current_owner: str | None
    ) -> tuple[list[SaleEvent], list[OwnerPeriod]]:
        """Turn raw TonAPI events into sales (newest first) + owner timeline."""
        sales: list[SaleEvent] = []
        # (timestamp, new_owner_address, kind)
        transitions: list[tuple[datetime | None, str, str]] = []

        for ev in events:
            dt = _dt(ev.get("timestamp"))
            for action in ev.get("actions", []):
                a_type = action.get("type")
                body = action.get(a_type, {}) if a_type else {}
                if a_type == "NftPurchase":
                    amount = body.get("amount", {})
                    buyer = (body.get("buyer") or {}).get("address")
                    seller = (body.get("seller") or {}).get("address")
                    sales.append(
                        SaleEvent(
                            price_ton=_ton(amount.get("value")),
                            timestamp=dt,
                            kind="sale",
                            buyer=buyer,
                            seller=seller,
                        )
                    )
                    if buyer:
                        transitions.append((dt, buyer, "buy"))
                elif a_type == "NftItemTransfer":
                    recipient = (body.get("recipient") or {}).get("address")
                    sender = (body.get("sender") or {}).get("address")
                    if recipient:
                        kind = "transfer" if sender else "mint"
                        transitions.append((dt, recipient, kind))

        owners = TonApi._build_owner_timeline(transitions, current_owner)
        sales.sort(key=lambda s: s.timestamp or datetime.min.replace(tzinfo=timezone.utc),
                   reverse=True)
        return sales, owners

    @staticmethod
    def _build_owner_timeline(
        transitions: list[tuple[datetime | None, str, str]],
        current_owner: str | None,
    ) -> list[OwnerPeriod]:
        epoch = datetime.min.replace(tzinfo=timezone.utc)
        transitions = sorted(transitions, key=lambda x: x[0] or epoch)
        owners: list[OwnerPeriod] = []
        for dt, addr, _kind in transitions:
            if owners and owners[-1].address == addr:
                continue  # consecutive duplicate, ignore
            if owners:
                owners[-1].until = dt
            owners.append(OwnerPeriod(address=addr, since=dt))

        if owners:
            owners[-1].is_current = True
            # Reconcile with the authoritative current owner from /v2/nfts.
            if current_owner and owners[-1].address != current_owner:
                owners[-1].is_current = False
                owners[-1].until = owners[-1].until
                owners.append(OwnerPeriod(address=current_owner, is_current=True))
        elif current_owner:
            owners.append(OwnerPeriod(address=current_owner, is_current=True))
        return owners
