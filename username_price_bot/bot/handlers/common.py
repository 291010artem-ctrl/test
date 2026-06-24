"""Shared callback helper: edit a message that may be a photo or plain text."""
from __future__ import annotations

from aiogram.exceptions import TelegramBadRequest
from aiogram.types import CallbackQuery, InlineKeyboardMarkup

_CAPTION_LIMIT = 1024


def _fit_caption(text: str) -> str:
    if len(text) <= _CAPTION_LIMIT:
        return text
    # Cut on a line boundary so HTML tags stay balanced.
    return text[: _CAPTION_LIMIT - 2].rsplit("\n", 1)[0] + "\n…"


async def edit_or_replace(
    cb: CallbackQuery, text: str, kb: InlineKeyboardMarkup, photo_ok: bool = True
) -> None:
    """Update the message behind a callback.

    - photo message + photo_ok → edit the caption (keep the image);
    - photo message + not photo_ok → drop the image, send fresh text (leaving the
      NFT context, e.g. back to the menu);
    - text message → edit the text.
    """
    msg = cb.message
    try:
        if msg.photo and photo_ok:
            await msg.edit_caption(caption=_fit_caption(text), reply_markup=kb)
        elif msg.photo and not photo_ok:
            await msg.delete()
            await msg.answer(text, reply_markup=kb, disable_web_page_preview=True)
        else:
            await msg.edit_text(text, reply_markup=kb, disable_web_page_preview=True)
    except TelegramBadRequest:
        pass
    await cb.answer()
