"""/start, /help and navigation button callbacks."""
from __future__ import annotations

from aiogram import F, Router
from aiogram.exceptions import TelegramBadRequest
from aiogram.filters import Command, CommandStart
from aiogram.types import CallbackQuery, Message

from ..keyboards import NAV_HELP, NAV_START, help_kb, start_kb

router = Router(name="commands")

_WELCOME = (
    "👋 Привет! Я помогу узнать <b>реальную цену NFT-юзернейма</b> Telegram.\n\n"
    "Просто пришли мне юзернейм — в любом виде:\n"
    "<code>@durov</code>, <code>durov</code> или <code>t.me/durov</code>\n\n"
    "Что покажу:\n"
    "• 📊 оценку цены в <b>TON / USDT / ₽</b> с погрешностью\n"
    "• 💰 текущую цену и статус на Fragment\n"
    "• 📜 историю продаж (если была)\n"
    "• 👛 на каких кошельках лежал юзернейм\n\n"
    "Данные: <b>TON-блокчейн (TonAPI)</b>, <b>Fragment</b>, <b>GetGems</b>."
)

_HELP = (
    "ℹ️ <b>Как пользоваться</b>\n\n"
    "Отправь юзернейм в любом виде:\n"
    "• <code>@name</code>\n"
    "• <code>name</code> (просто символы)\n"
    "• <code>t.me/name</code>\n"
    "• <code>fragment.com/username/name</code>\n\n"
    "Если юзернейм не выпущен как NFT (или не существует), я об этом напишу, "
    "но всё равно дам примерную оценку — как если бы он был NFT.\n\n"
    "Команды: /start и /help.\n\n"
    "⚠️ Оценка приблизительная и не является финансовой рекомендацией."
)


async def _safe_edit(cb: CallbackQuery, text: str, kb) -> None:
    try:
        await cb.message.edit_text(text, reply_markup=kb, disable_web_page_preview=True)
    except TelegramBadRequest:
        pass  # message unchanged / too old to edit — ignore
    await cb.answer()


@router.message(CommandStart())
async def cmd_start(message: Message) -> None:
    await message.answer(_WELCOME, reply_markup=start_kb(), disable_web_page_preview=True)


@router.message(Command("help"))
async def cmd_help(message: Message) -> None:
    await message.answer(_HELP, reply_markup=help_kb(), disable_web_page_preview=True)


@router.callback_query(F.data == NAV_START)
async def nav_start(cb: CallbackQuery) -> None:
    await _safe_edit(cb, _WELCOME, start_kb())


@router.callback_query(F.data == NAV_HELP)
async def nav_help(cb: CallbackQuery) -> None:
    await _safe_edit(cb, _HELP, help_kb())
