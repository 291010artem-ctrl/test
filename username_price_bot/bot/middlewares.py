"""Simple per-user rate limiting (flood control) for the parser."""
from __future__ import annotations

import time
from typing import Any, Awaitable, Callable

from aiogram import BaseMiddleware
from aiogram.types import Message, TelegramObject


class ThrottlingMiddleware(BaseMiddleware):
    """Allow at most `limit` events per `window` seconds per Telegram user."""

    def __init__(self, limit: int = 3, window: float = 60.0):
        self.limit = limit
        self.window = window
        self._hits: dict[int, list[float]] = {}

    def _allowed(self, user_id: int) -> bool:
        now = time.monotonic()
        hits = [t for t in self._hits.get(user_id, []) if now - t < self.window]
        if len(hits) >= self.limit:
            self._hits[user_id] = hits
            return False
        hits.append(now)
        self._hits[user_id] = hits
        return True

    async def __call__(
        self,
        handler: Callable[[TelegramObject, dict[str, Any]], Awaitable[Any]],
        event: TelegramObject,
        data: dict[str, Any],
    ) -> Any:
        user = data.get("event_from_user")
        if user and not self._allowed(user.id):
            if isinstance(event, Message):
                await event.answer(
                    "⏳ Слишком часто. Не больше 3 запросов в минуту — подожди немного."
                )
            return None
        return await handler(event, data)
