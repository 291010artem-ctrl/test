"""/start and /help command handlers."""
from __future__ import annotations

from aiogram import Router
from aiogram.filters import Command, CommandStart
from aiogram.types import Message

router = Router(name="commands")

_WELCOME = (
    "👋 Привет! Я помогу узнать <b>реальную цену NFT-юзернейма</b> Telegram.\n\n"
    "Просто пришли мне юзернейм — например:\n"
    "<code>@durov</code>, <code>durov</code> или <code>t.me/durov</code>\n\n"
    "Что я покажу:\n"
    "• 📊 оценку справедливой цены (в TON и $)\n"
    "• 💰 текущую цену/статус на Fragment\n"
    "• 📜 историю продаж (если была)\n"
    "• 👛 на каких кошельках лежал юзернейм\n\n"
    "Данные собираю из <b>TON-блокчейна (TonAPI)</b>, <b>Fragment</b> и <b>GetGems</b>."
)

_HELP = (
    "ℹ️ <b>Как пользоваться</b>\n\n"
    "Отправь юзернейм в любом виде:\n"
    "• <code>@name</code>\n"
    "• <code>name</code>\n"
    "• <code>t.me/name</code>\n"
    "• <code>fragment.com/username/name</code>\n\n"
    "Команды:\n"
    "/start — приветствие\n"
    "/help — эта справка\n\n"
    "⚠️ Оценка цены приблизительная и не является финансовой рекомендацией."
)


@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    await message.answer(_WELCOME, disable_web_page_preview=True)


@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(_HELP, disable_web_page_preview=True)
