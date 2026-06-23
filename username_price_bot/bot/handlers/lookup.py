"""Username lookup: receive a username, show a card, navigate sections."""
from __future__ import annotations

import logging
import re
from html import escape

from aiogram import F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.types import CallbackQuery, Message

from ..aggregator import Aggregator
from ..formatting import (
    card_text,
    estimate_text,
    last_sale_text,
    price_text,
    quality_text,
    sales_text,
)
from ..keyboards import card_kb, est_kb, price_kb, rate_kb, sales_kb, to_menu_kb
from ..utils import normalize_username

log = logging.getLogger(__name__)

router = Router(name="lookup")

# Telegram usernames shorter than 4 chars don't exist / can't be created, so
# valuing them is meaningless.
MIN_LEN = 4


async def _edit(cb: CallbackQuery, text: str, kb) -> None:
    try:
        await cb.message.edit_text(text, reply_markup=kb, disable_web_page_preview=True)
    except TelegramBadRequest:
        pass
    await cb.answer()


@router.message(F.text)
async def on_text(message: Message, aggregator: Aggregator) -> None:
    raw = (message.text or "").strip()
    if raw.startswith("/"):
        await message.answer("Неизвестная команда. Используй /start.", reply_markup=to_menu_kb())
        return

    # Several usernames at once → ask for one (don't fail with "invalid").
    tokens = [t for t in re.split(r"[\s,]+", raw) if t]
    if len(tokens) > 1 and sum(bool(normalize_username(t)) for t in tokens) > 1:
        await message.answer(
            "✋ Можно проверять только <b>один</b> юзернейм за раз. Пришли один.",
            reply_markup=to_menu_kb(),
        )
        return

    username = normalize_username(raw)
    if not username:
        await message.answer(
            "🤔 Это не похоже на юзернейм.\n"
            "Это латинские буквы, цифры и '_'. Пример: <code>@durov</code>",
            reply_markup=to_menu_kb(),
        )
        return

    if len(username) < MIN_LEN:
        await message.answer(
            f"🔎 <b>@{escape(username)}</b>\n\n"
            "Юзернеймы короче 4 символов в Telegram не существуют (обычные — от 5, "
            "премиальные 4-буквенные продавались на Fragment). Оценивать нечего.",
            reply_markup=to_menu_kb(),
        )
        return

    status = await message.answer(
        f"🔎 Собираю данные по <b>@{escape(username)}</b>…", disable_web_page_preview=True
    )
    try:
        report = await aggregator.get_report(username)
    except Exception:  # noqa: BLE001
        log.exception("aggregation failed for %s", username)
        await status.edit_text("⚠️ Не удалось получить данные. Попробуй позже.",
                               reply_markup=to_menu_kb())
        return

    if report is None:
        await status.edit_text("🤔 Не получилось разобрать юзернейм.", reply_markup=to_menu_kb())
        return

    await status.edit_text(card_text(report), reply_markup=card_kb(report),
                           disable_web_page_preview=True)


async def _report_for(cb: CallbackQuery, aggregator: Aggregator) -> object | None:
    username = cb.data.split(":", 1)[1]
    try:
        return await aggregator.get_report(username)
    except Exception:  # noqa: BLE001
        log.exception("aggregation failed for %s", username)
        await cb.answer("⚠️ Ошибка. Попробуй позже.", show_alert=True)
        return None


@router.callback_query(F.data.startswith("card:"))
async def cb_card(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, card_text(report), card_kb(report))


@router.callback_query(F.data.startswith("price:"))
async def cb_price(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, price_text(report), price_kb(report))


@router.callback_query(F.data.startswith("last:"))
async def cb_last_sale(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, last_sale_text(report), sales_kb(report))


@router.callback_query(F.data.startswith("sales:"))
async def cb_sales(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, sales_text(report), sales_kb(report))


@router.callback_query(F.data.startswith("est:"))
async def cb_est(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, estimate_text(report), est_kb(report))


@router.callback_query(F.data.startswith("rate:"))
async def cb_rate(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _edit(cb, quality_text(report), rate_kb(report))


@router.message()
async def on_other(message: Message) -> None:
    await message.answer("Пришли юзернейм текстом, например <code>@durov</code>.",
                         reply_markup=to_menu_kb())
