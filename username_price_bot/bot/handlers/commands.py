"""/start and /help commands → main menu."""
from __future__ import annotations

from aiogram import Router
from aiogram.filters import Command, CommandStart
from aiogram.types import Message

from ..aggregator import Aggregator
from ..keyboards import main_menu_kb, to_menu_kb
from ..texts import HELP, MAIN_MENU

router = Router(name="commands")


@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    await message.answer(MAIN_MENU, reply_markup=main_menu_kb(), disable_web_page_preview=True)


@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(HELP, reply_markup=to_menu_kb(), disable_web_page_preview=True)


@router.message(Command("diag"))
async def cmd_diag(message: Message, aggregator: Aggregator) -> None:
    msg = await message.answer("🩺 Проверяю источники данных…")
    results = await aggregator.diagnose()
    text = "🩺 <b>Диагностика источников</b>\n\n" + "\n".join(
        f"• {k}: {v}" for k, v in results.items()
    )
    await msg.edit_text(text, reply_markup=to_menu_kb(), disable_web_page_preview=True)
