"""Inline keyboards — menu-driven navigation so the user never gets stuck."""
from __future__ import annotations

from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from .formatting import _on_sale, is_nft
from .models import UsernameReport

# Callback data (usernames contain only [a-z0-9_], so ':' is a safe separator).
CB_MAIN = "menu:main"
CB_VALUATION = "menu:valuation"
CB_NUMBERS = "menu:numbers"
CB_SOON = "menu:soon"
CB_HELP = "menu:help"


def card_cb(u: str) -> str: return f"card:{u}"
def price_cb(u: str) -> str: return f"price:{u}"
def lastsale_cb(u: str) -> str: return f"last:{u}"
def sales_cb(u: str) -> str: return f"sales:{u}"
def est_cb(u: str) -> str: return f"est:{u}"
def rate_cb(u: str) -> str: return f"rate:{u}"


def main_menu_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="💎 Оценка юзернеймов", callback_data=CB_VALUATION)],
        [InlineKeyboardButton(text="📱 Оценка номеров +888", callback_data=CB_NUMBERS)],
        [InlineKeyboardButton(text="🔜 Другие функции (скоро)", callback_data=CB_SOON)],
        [InlineKeyboardButton(text="ℹ️ Помощь", callback_data=CB_HELP)],
    ])


def valuation_prompt_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⬅️ В меню", callback_data=CB_MAIN)],
    ])


def to_menu_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⬅️ В меню", callback_data=CB_MAIN)],
    ])


def card_kb(r: UsernameReport) -> InlineKeyboardMarkup:
    u = r.username
    rows: list[list[InlineKeyboardButton]] = [
        [InlineKeyboardButton(text="💰 Актуальная цена", callback_data=price_cb(u))],
        [InlineKeyboardButton(text="🧾 Последняя продажа", callback_data=lastsale_cb(u))],
        [InlineKeyboardButton(text="📜 История продаж", callback_data=sales_cb(u))],
        [InlineKeyboardButton(text="📊 Примерная стоимость", callback_data=est_cb(u))],
        [InlineKeyboardButton(text="🏆 Рейтинг и разбор", callback_data=rate_cb(u))],
    ]
    if r.tonviewer_url:
        rows.append([InlineKeyboardButton(text="👛 Кошельки (TonViewer)", url=r.tonviewer_url)])
    rows.append([
        InlineKeyboardButton(text="🔄 Другой", callback_data=CB_VALUATION),
        InlineKeyboardButton(text="⬅️ Меню", callback_data=CB_MAIN),
    ])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def _back_to_card(u: str) -> InlineKeyboardButton:
    return InlineKeyboardButton(text="⬅️ Назад", callback_data=card_cb(u))


def price_kb(r: UsernameReport) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    if r.fragment_url:
        text = "🛒 Купить на Fragment" if _on_sale(r) else "🔎 Цена на Fragment"
        rows.append([InlineKeyboardButton(text=text, url=r.fragment_url)])
    rows.append([_back_to_card(r.username)])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def sales_kb(r: UsernameReport) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    if r.fragment_url:
        rows.append([InlineKeyboardButton(text="📜 История с ценами (Fragment)", url=r.fragment_url)])
    if r.tonviewer_url:
        rows.append([InlineKeyboardButton(text="👛 Кошельки на TonViewer", url=r.tonviewer_url)])
    rows.append([_back_to_card(r.username)])
    return InlineKeyboardMarkup(inline_keyboard=rows)


def est_kb(r: UsernameReport) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[_back_to_card(r.username)]])


def rate_kb(r: UsernameReport) -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[[_back_to_card(r.username)]])
