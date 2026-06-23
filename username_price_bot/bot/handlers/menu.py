"""Main-menu navigation callbacks."""
from __future__ import annotations

from aiogram import F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.types import CallbackQuery

from ..keyboards import (
    CB_HELP,
    CB_MAIN,
    CB_SOON,
    CB_VALUATION,
    main_menu_kb,
    to_menu_kb,
    valuation_prompt_kb,
)
from ..texts import HELP, MAIN_MENU, VALUATION_PROMPT

router = Router(name="menu")


async def _edit(cb: CallbackQuery, text: str, kb) -> None:
    try:
        await cb.message.edit_text(text, reply_markup=kb, disable_web_page_preview=True)
    except TelegramBadRequest:
        pass
    await cb.answer()


@router.callback_query(F.data == CB_MAIN)
async def open_main(cb: CallbackQuery) -> None:
    await _edit(cb, MAIN_MENU, main_menu_kb())


@router.callback_query(F.data == CB_VALUATION)
async def open_valuation(cb: CallbackQuery) -> None:
    await _edit(cb, VALUATION_PROMPT, valuation_prompt_kb())


@router.callback_query(F.data == CB_HELP)
async def open_help(cb: CallbackQuery) -> None:
    await _edit(cb, HELP, to_menu_kb())


@router.callback_query(F.data == CB_SOON)
async def open_soon(cb: CallbackQuery) -> None:
    await cb.answer("🔜 Эта функция ещё в разработке. Скоро!", show_alert=True)
