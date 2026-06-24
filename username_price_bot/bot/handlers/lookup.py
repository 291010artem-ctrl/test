"""Username lookup: receive a username, show a card, navigate sections."""
from __future__ import annotations

import logging
import re
from html import escape

from aiogram import F, Router
from aiogram.types import CallbackQuery, Message

from ..aggregator import Aggregator
from ..formatting import (
    card_text,
    estimate_text,
    is_nft,
    last_sale_text,
    price_text,
    quality_text,
    sales_text,
)
from ..assets import detect, display
from ..keyboards import card_kb, est_kb, price_kb, rate_kb, sales_kb, to_menu_kb
from ..middlewares import ThrottlingMiddleware
from .common import edit_or_replace

log = logging.getLogger(__name__)

router = Router(name="lookup")
# Flood control: max 3 lookups per minute per user.
router.message.middleware(ThrottlingMiddleware(limit=3, window=60.0))

MIN_LEN = 4  # usernames shorter than 4 chars don't exist


@router.message(F.text)
async def on_text(message: Message, aggregator: Aggregator) -> None:
    raw = (message.text or "").strip()
    if raw.startswith("/"):
        await message.answer("Неизвестная команда. Используй /start.", reply_markup=to_menu_kb())
        return

    # Several at once → ask for one (don't fail with "invalid").
    tokens = [t for t in re.split(r"[\s,]+", raw) if t]
    if len(tokens) > 1 and sum(bool(detect(t)) for t in tokens) > 1:
        await message.answer(
            "✋ Можно проверять только <b>один</b> актив за раз — пришли один "
            "юзернейм или номер.",
            reply_markup=to_menu_kb(),
        )
        return

    detected = detect(raw)
    if not detected:
        await message.answer(
            "🤔 Это не похоже на юзернейм или номер.\n"
            "Юзернейм: <code>@durov</code> · Номер: <code>+888 8856 4001</code>",
            reply_markup=to_menu_kb(),
        )
        return
    kind, asset_id = detected

    if kind.key == "username" and len(asset_id) < MIN_LEN:
        await message.answer(
            f"🔎 <b>@{escape(asset_id)}</b>\n\n"
            "Юзернеймы короче 4 символов не существуют. Оценивать нечего.",
            reply_markup=to_menu_kb(),
        )
        return

    pretty = display(kind, asset_id)
    status = await message.answer(
        f"🔎 Собираю данные по <b>{escape(pretty)}</b>…", disable_web_page_preview=True
    )
    try:
        report = await aggregator.get_report(raw)
    except Exception:  # noqa: BLE001
        log.exception("aggregation failed for %s", asset_id)
        await status.edit_text("⚠️ Не удалось получить данные. Попробуй позже.",
                               reply_markup=to_menu_kb())
        return

    if report is None:
        await status.edit_text("🤔 Не получилось разобрать.", reply_markup=to_menu_kb())
        return

    text, kb = card_text(report), card_kb(report)
    # Attach the NFT image to the card (caption) only for real NFTs.
    sent = False
    if is_nft(report) and report.image_url:
        try:
            await message.answer_photo(report.image_url, caption=text, reply_markup=kb)
            await status.delete()
            sent = True
        except Exception:  # noqa: BLE001 — no NFT image, use text card
            pass
    if not sent:
        await status.edit_text(text, reply_markup=kb, disable_web_page_preview=True)


async def _report_for(cb: CallbackQuery, aggregator: Aggregator) -> object | None:
    username = cb.data.split(":", 1)[1]
    try:
        return await aggregator.get_report(username)
    except Exception:  # noqa: BLE001
        log.exception("aggregation failed for %s", username)
        await cb.answer("⚠️ Ошибка. Попробуй позже.", show_alert=True)
        return None


async def _render_card(cb: CallbackQuery, report) -> None:
    text, kb = card_text(report), card_kb(report)
    msg = cb.message
    # Restore the photo card when returning from a text section (e.g. history).
    if is_nft(report) and report.image_url and not msg.photo:
        try:
            await msg.delete()
        except Exception:  # noqa: BLE001
            pass
        try:
            await msg.answer_photo(report.image_url, caption=text, reply_markup=kb)
        except Exception:  # noqa: BLE001
            await msg.answer(text, reply_markup=kb, disable_web_page_preview=True)
        await cb.answer()
    else:
        await edit_or_replace(cb, text, kb)


@router.callback_query(F.data.startswith("card:"))
async def cb_card(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await _render_card(cb, report)


@router.callback_query(F.data.startswith("price:"))
async def cb_price(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await edit_or_replace(cb, price_text(report), price_kb(report))


@router.callback_query(F.data.startswith("last:"))
async def cb_last_sale(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await edit_or_replace(cb, last_sale_text(report), sales_kb(report))


@router.callback_query(F.data.startswith("sales:"))
async def cb_sales(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        # Full history can be long → show as a text message (no 1024 caption cap).
        await edit_or_replace(cb, sales_text(report), sales_kb(report), photo_ok=False)


@router.callback_query(F.data.startswith("est:"))
async def cb_est(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await edit_or_replace(cb, estimate_text(report), est_kb(report))


@router.callback_query(F.data.startswith("rate:"))
async def cb_rate(cb: CallbackQuery, aggregator: Aggregator) -> None:
    report = await _report_for(cb, aggregator)
    if report:
        await edit_or_replace(cb, quality_text(report), rate_kb(report))


@router.message()
async def on_other(message: Message) -> None:
    await message.answer("Пришли юзернейм текстом, например <code>@durov</code>.",
                         reply_markup=to_menu_kb())
