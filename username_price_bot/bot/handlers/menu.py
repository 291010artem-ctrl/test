"""Main-menu navigation callbacks."""
from __future__ import annotations

from aiogram import F, Router
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
from .common import edit_or_replace

router = Router(name="menu")


@router.callback_query(F.data == CB_MAIN)
async def open_main(cb: CallbackQuery) -> None:
    # Leaving the username context → replace the photo card with a text menu.
    await edit_or_replace(cb, MAIN_MENU, main_menu_kb(), photo_ok=False)


@router.callback_query(F.data == CB_VALUATION)
async def open_valuation(cb: CallbackQuery) -> None:
    await edit_or_replace(cb, VALUATION_PROMPT, valuation_prompt_kb(), photo_ok=False)


@router.callback_query(F.data == CB_HELP)
async def open_help(cb: CallbackQuery) -> None:
    await edit_or_replace(cb, HELP, to_menu_kb(), photo_ok=False)


@router.callback_query(F.data == CB_SOON)
async def open_soon(cb: CallbackQuery) -> None:
    await cb.answer("🔜 Эта функция ещё в разработке. Скоро!", show_alert=True)
