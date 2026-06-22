"""Shared MTProto session for talking to Telegram's own gift marketplace.

The bot needs a real user session (not just a Bot API token) because the
official gift-resale endpoints (payments.GetUniqueStarGift,
payments.GetResaleStarGifts, ...) are only reachable via MTProto user auth.
Reuses the same session file produced by scripts/get_tokens.py.
"""

from __future__ import annotations

import os

from telethon import TelegramClient

SESSION_NAME = "tech_account"

_client: TelegramClient | None = None


async def get_client() -> TelegramClient | None:
    """Return a connected, authorized Telethon client, or None if unavailable."""
    global _client

    api_id = os.getenv("TG_API_ID")
    api_hash = os.getenv("TG_API_HASH")
    if not api_id or not api_hash:
        return None

    if _client is None:
        _client = TelegramClient(SESSION_NAME, int(api_id), api_hash)

    if not _client.is_connected():
        await _client.connect()

    if not await _client.is_user_authorized():
        return None

    return _client
