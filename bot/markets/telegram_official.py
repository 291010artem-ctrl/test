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

_RARITY_NAMES = {
    "StarGiftAttributeRarityUncommon": "Uncommon",
    "StarGiftAttributeRarityRare": "Rare",
    "StarGiftAttributeRarityEpic": "Epic",
    "StarGiftAttributeRarityLegendary": "Legendary",
}


def _stars_amount_to_float(stars_amount) -> float:
    """StarsAmount(amount, nanos) -> float. Currency (Stars vs TON) is
    determined by the caller from context; this just does the arithmetic.
    """
    return stars_amount.amount + stars_amount.nanos / 1e9


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

    async def lookup_gift(self, number: str, model: str) -> MarketResult:
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
            f"{label}: {attr.name} ({_RARITY_NAMES.get(type(attr.rarity).__name__, '?')})"
            for label, attr in (
                ("Модель", model_attr),
                ("Узор", pattern_attr),
                ("Фон", backdrop_attr),
            )
            if attr is not None
        )

        floor_ton = await self._floor_for_combo(client, gift, model_attr, backdrop_attr, pattern_attr)

        return MarketResult(
            market=self.name,
            available=True,
            current_price_ton=floor_ton,
            sales_history=[],
            url=f"https://t.me/nft/{slug}",
            error=None if floor_ton is not None else "not_for_sale",
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

        prices = []
        for g in resale.gifts:
            for amount in getattr(g, "resell_amount", None) or []:
                prices.append(_stars_amount_to_float(amount))
        return min(prices) if prices else None

    async def lookup_username(self, username: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")

    async def lookup_number(self, number: str) -> MarketResult:
        return MarketResult(market=self.name, available=False, error="not_supported")
