"""Inline keyboards for navigation so the user never gets stuck."""
from __future__ import annotations

from aiogram.types import InlineKeyboardButton, InlineKeyboardMarkup

from .models import UsernameReport

NAV_START = "nav:start"
NAV_HELP = "nav:help"


def start_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="ℹ️ Как пользоваться", callback_data=NAV_HELP)],
    ])


def help_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⬅️ Назад", callback_data=NAV_START)],
    ])


def back_kb() -> InlineKeyboardMarkup:
    return InlineKeyboardMarkup(inline_keyboard=[
        [InlineKeyboardButton(text="⬅️ В начало", callback_data=NAV_START)],
    ])


def result_kb(report: UsernameReport) -> InlineKeyboardMarkup:
    rows: list[list[InlineKeyboardButton]] = []
    links: list[InlineKeyboardButton] = []
    if report.fragment_url:
        links.append(InlineKeyboardButton(text="🔗 Fragment", url=report.fragment_url))
    if report.getgems_url:
        links.append(InlineKeyboardButton(text="🔗 GetGems", url=report.getgems_url))
    if links:
        rows.append(links)
    rows.append([InlineKeyboardButton(text="🔄 Проверить другой", callback_data=NAV_START)])
    return InlineKeyboardMarkup(inline_keyboard=rows)
