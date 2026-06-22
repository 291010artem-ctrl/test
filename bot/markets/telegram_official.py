from __future__ import annotations

import re

from telethon.tl.functions.payments import (
    GetResaleStarGiftsRequest,
    GetUniqueStarGiftRequest,
)
from telethon.tl.types import (
    StarGiftAttributeBackdrop,
    StarGiftAttributeIdBackdrop,
    StarGiftAttributeIdModel,
    StarGiftAttributeIdPattern,
    StarGiftAttributeModel,
    StarGiftAttributePattern,
)

from .. import tg_session
from .base import MarketClient, MarketResult, debug


def _rarity_label(attr) -> str:
    # Attributes don't carry a named tier, just a permille (parts-per-1000)
    # chance of that attribute occurring on the gift.
    permille = getattr(getattr(attr, "rarity", None), "permille", None)
    return f"{permille / 10:.1f}%" if permille is not None else "?"


def _resell_ton_price(gift) -> float | None:
    # resell_amount holds one entry per currency the gift is listed in
    # (StarsAmount = Stars, StarsTonAmount = nanotons). We only want TON.
    for amount in getattr(gift, "resell_amount", None) or []:
        if type(amount).__name__ == "StarsTonAmount":
            return amount.amount / 1e9
    return None


def _slug_for(model: str, number: str) -> str:
    # Telegram unique gift slugs look like "PlushPepe-1234": model name with
    # spaces stripped, a dash, then the gift's mint number.
    clean_model = re.sub(r"\s+", "", model)
    return f"{clean_model}-{number}"


class TelegramOfficialClient(MarketClient):
    """Telegram's own in-app gift resale market (Settings -> My Gifts ->
    resell for Stars/TON). Reached via MTProto (payments.GetUniqueStarGift /
    payments.GetResaleStarGifts), which requires the technical account's
    Telethon session (see scripts/get_tokens.py) to be logged in.

    This is the most authoritative source: it gives the gift's real
    model/backdrop/pattern attributes plus the current resale floor for that
    exact attribute combination, instead of relying on a single old sale.
    """

    name = "Telegram"

    async def lookup_gift(self, number: str, model: str, address: str | None = None) -> MarketResult:
        client = await tg_session.get_client()
        if client is None:
            return MarketResult(market=self.name, available=False, error="missing_auth")

        slug = _slug_for(model, number)
        try:
            result = await client(GetUniqueStarGiftRequest(slug=slug))
        except Exception as exc:
            debug(self.name, f"GetUniqueStarGift({slug}) failed: {exc!r}")
            return MarketResult(market=self.name, available=False, error="not_found")

        gift = result.gift
        debug(self.name, f"gift attributes: {[a.to_dict() for a in gift.attributes]!r}")

        model_attr = next((a for a in gift.attributes if isinstance(a, StarGiftAttributeModel)), None)
        backdrop_attr = next((a for a in gift.attributes if isinstance(a, StarGiftAttributeBackdrop)), None)
        pattern_attr = next((a for a in gift.attributes if isinstance(a, StarGiftAttributePattern)), None)

        attr_summary = " | ".join(
            f"{label}: {attr.name} ({_rarity_label(attr)})"
            for label, attr in (
                ("Модель", model_attr),
                ("Узор", pattern_attr),
                ("Фон", backdrop_attr),
            )
            if attr is not None
        )

        floor_ton = await self._floor_for_combo(client, gift, model_attr, backdrop_attr, pattern_attr)
        gift_address = getattr(gift, "gift_address", None)
        debug(self.name, f"gift_address: {gift_address!r}")

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=floor_ton,
            sales_history=[],
            url=f"https://t.me/nft/{slug}",
            error=None if floor_ton is not None else "not_for_sale",
            gift_address=gift_address,
            attributes=attr_summary or None,
        )

    async def _floor_for_combo(self, client, gift, model_attr, backdrop_attr, pattern_attr) -> float | None:
        attr_ids = []
        if model_attr is not None:
            attr_ids.append(StarGiftAttributeIdModel(document_id=model_attr.document.id))
        if backdrop_attr is not None:
            attr_ids.append(StarGiftAttributeIdBackdrop(backdrop_id=backdrop_attr.backdrop_id))
        if pattern_attr is not None:
            attr_ids.append(StarGiftAttributeIdPattern(document_id=pattern_attr.document.id))

        try:
            resale = await client(
                GetResaleStarGiftsRequest(
                    gift_id=gift.gift_id,
                    offset="",
                    limit=5,
                    sort_by_price=True,
                    attributes=attr_ids or None,
                )
            )
        except Exception as exc:
            debug(self.name, f"GetResaleStarGifts failed: {exc!r}")
            return None

        debug(self.name, f"resale gifts: {[g.to_dict() for g in resale.gifts]!r}")

        prices = [p for p in (_resell_ton_price(g) for g in resale.gifts) if p is not None]
        return min(prices) if prices else None

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
