from __future__ import annotations

from aiogram import F, Router
from aiogram.filters import CommandStart
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message

from . import ton_price
from .aggregator import lookup
from .formatting import format_report
from .markets.base import ItemKind

router = Router()


class LookupStates(StatesGroup):
    choosing_kind = State()
    entering_gift_number = State()
    entering_gift_model = State()
    entering_username = State()
    entering_number = State()


def _kind_keyboard() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(
        inline_keyboard=[
            [InlineKeyboardButton(text="🎁 NFT-подарок", callback_data="kind:gift")],
            [InlineKeyboardButton(text="👤 Юзернейм", callback_data="kind:username")],
            [InlineKeyboardButton(text="📱 Номер", callback_data="kind:number")],
        ]
    )


@router.message(CommandStart())
async def cmd_start(message: Message, state: FSMContext) -> None:
    await state.clear()
    await state.set_state(LookupStates.choosing_kind)
    await message.answer(
        "Что нужно проверить?",
        reply_markup=_kind_keyboard(),
    )


@router.message(F.text == "/price")
async def cmd_price(message: Message, state: FSMContext) -> None:
    await cmd_start(message, state)


@router.callback_query(LookupStates.choosing_kind, F.data.startswith("kind:"))
async def on_kind_chosen(callback: CallbackQuery, state: FSMContext) -> None:
    kind = callback.data.split(":", 1)[1]
    await state.update_data(kind=kind)

    if kind == ItemKind.GIFT.value:
        await state.set_state(LookupStates.entering_gift_number)
        await callback.message.edit_text("Введи номер подарка (например 1234):")
    elif kind == ItemKind.USERNAME.value:
        await state.set_state(LookupStates.entering_username)
        await callback.message.edit_text("Введи юзернейм (например @durov):")
    else:
        await state.set_state(LookupStates.entering_number)
        await callback.message.edit_text("Введи номер телефона (например 88888):")

    await callback.answer()


@router.message(LookupStates.entering_gift_number)
async def on_gift_number(message: Message, state: FSMContext) -> None:
    await state.update_data(number=message.text.strip())
    await state.set_state(LookupStates.entering_gift_model)
    await message.answer("Теперь введи модель/название подарка (например Plush Pepe):")


@router.message(LookupStates.entering_gift_model)
async def on_gift_model(message: Message, state: FSMContext) -> None:
    data = await state.update_data(model=message.text.strip())
    await state.clear()
    number, model = data["number"], data["model"]
    label = f"🎁 {model} #{number}"
    await _run_lookup(message, ItemKind.GIFT, label, number=number, model=model)


@router.message(LookupStates.entering_username)
async def on_username(message: Message, state: FSMContext) -> None:
    username = message.text.strip()
    await state.clear()
    await _run_lookup(message, ItemKind.USERNAME, f"👤 @{username.lstrip('@')}", username=username)


@router.message(LookupStates.entering_number)
async def on_number(message: Message, state: FSMContext) -> None:
    number = message.text.strip()
    await state.clear()
    await _run_lookup(message, ItemKind.NUMBER, f"📱 +{number.lstrip('+')}", number=number)


async def _run_lookup(message: Message, kind: ItemKind, label: str, **kwargs) -> None:
    status = await message.answer("Ищу на mrkt, Portals, Tonnel, Fragment, Getgems…")
    results = await lookup(kind, **kwargs)
    current_rate = await ton_price.get_current_ton_usd()
    text = format_report(label, results, current_rate)
    await status.edit_text(text, disable_web_page_preview=True)
