"""Main handler: treat any text message as a username to look up."""
from __future__ import annotations

import logging
from html import escape

from aiogram import F, Router
from aiogram.types import Message

from ..aggregator import Aggregator
from ..formatting import render_report
from ..keyboards import back_kb, result_kb
from ..utils import normalize_username

log = logging.getLogger(__name__)

router = Router(name="lookup")


@router.message(F.text)
async def on_text(message: Message, aggregator: Aggregator) -> None:
    raw = (message.text or "").strip()
    if raw.startswith("/"):
        await message.answer(
            "Неизвестная команда. Пришли юзернейм или используй /help.",
            reply_markup=back_kb(),
        )
        return

    username = normalize_username(raw)
    if not username:
        await message.answer(
            "🤔 Это не похоже на корректный юзернейм.\n"
            "Юзернейм — это латинские буквы, цифры и '_' (до 32 символов).\n"
            "Пример: <code>@durov</code>",
            reply_markup=back_kb(),
        )
        return

    status = await message.answer(
        f"🔎 Собираю данные по <b>@{escape(username)}</b>…", disable_web_page_preview=True
    )
    try:
        report = await aggregator.get_report(username)
    except Exception:  # noqa: BLE001 — never crash the handler
        log.exception("aggregation failed for %s", username)
        await status.edit_text(
            "⚠️ Не удалось получить данные. Попробуй ещё раз чуть позже.",
            reply_markup=back_kb(),
        )
        return

    if report is None:
        await status.edit_text(
            "🤔 Не получилось разобрать юзернейм. Попробуй ещё раз.", reply_markup=back_kb()
        )
        return

    await status.edit_text(
        render_report(report), reply_markup=result_kb(report), disable_web_page_preview=True
    )


@router.message()
async def on_other(message: Message) -> None:
    await message.answer(
        "Пришли юзернейм текстом, например <code>@durov</code>.", reply_markup=back_kb()
    )
