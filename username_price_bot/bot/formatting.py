"""Render UsernameReport sections into Telegram HTML messages.

The bot is menu-driven: a short "card" with buttons, and one message per
section (current price / sales history / estimate).
"""
from __future__ import annotations

from html import escape

from .models import MarketStatus, UsernameReport
from .utils import fmt_ton, short_addr

_CONFIDENCE_LABEL = {"high": "высокая", "medium": "средняя", "low": "низкая"}

_MAX_SALES = 15
_DISCLAIMER = "<i>⚠️ Оценка приблизительная и не является финансовой рекомендацией.</i>"


# ── helpers ──────────────────────────────────────────────────────────────────
def _grp(value: float) -> str:
    if value >= 100:
        return f"{value:,.0f}".replace(",", " ")
    if value >= 1:
        return f"{value:,.1f}".replace(",", " ")
    return f"{value:.2f}"


def _prices(ton: float | None, rates: dict[str, float]) -> str:
    """'1 200 TON ≈ 6 000 USDT ≈ 540 000 ₽' (skips currencies without a rate)."""
    if ton is None:
        return "—"
    parts = [f"{fmt_ton(ton)} TON"]
    if rates.get("USD"):
        parts.append(f"{_grp(ton * rates['USD'])} USDT")
    if rates.get("RUB"):
        parts.append(f"{_grp(ton * rates['RUB'])} ₽")
    return " ≈ ".join(parts)


def _margin_pct(point: float, low: float | None, high: float | None) -> int | None:
    if not point or low is None or high is None:
        return None
    dev = ((point - low) / point + (high - point) / point) / 2
    return round(dev * 100)


def is_nft(r: UsernameReport) -> bool:
    listed = bool(
        r.listing
        and r.listing.price_ton
        and r.listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )
    return r.found or listed


def _on_sale(r: UsernameReport) -> bool:
    return bool(
        r.listing
        and r.listing.price_ton
        and r.listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )


# ── card (the button menu) ───────────────────────────────────────────────────
def card_text(r: UsernameReport) -> str:
    name = escape(r.username)
    if is_nft(r):
        status = "💎 Это NFT-юзернейм."
    else:
        status = "❗️ Не является NFT (свободен или не выпущен как NFT)."
    return f"🔎 <b>@{name}</b>\n{status}\n\nЧто показать? 👇"


# ── section: current price ───────────────────────────────────────────────────
def price_text(r: UsernameReport) -> str:
    name = escape(r.username)
    head = f"🔎 <b>@{name}</b>\n\n"
    if not is_nft(r):
        return (
            head
            + "❗️ Это не NFT — в продаже быть не может.\n"
            "Нажми «📊 Примерная стоимость», чтобы увидеть оценку по виду юзернейма."
        )
    if _on_sale(r):
        listing = r.listing
        word = (
            "на аукционе, текущая ставка"
            if listing.status == MarketStatus.ON_AUCTION
            else "продаётся за"
        )
        src = f"\n<i>источник: {escape(listing.source)}</i>" if listing.source else ""
        return (
            head
            + f"💰 Сейчас {word} <b>{_prices(listing.price_ton, r.rates)}</b>.\n"
            "🛒 Купить — по кнопке ниже." + src
        )
    return head + "❌ Сейчас нигде не продаётся."


# ── section: sales history ───────────────────────────────────────────────────
def sales_text(r: UsernameReport) -> str:
    name = escape(r.username)
    head = f"🔎 <b>@{name}</b>\n\n"
    if not is_nft(r):
        return head + "❗️ Это не NFT — истории продаж нет."

    priced = [s for s in r.sales if s.price_ton]
    lines = [head.rstrip("\n"), "", "📜 <b>История продаж:</b>"]
    if priced:
        for s in priced[:_MAX_SALES]:
            when = s.timestamp.strftime("%Y-%m-%d") if s.timestamp else "—"
            lines.append(f"   • {when} — {fmt_ton(s.price_ton)} TON")
        if len(priced) > _MAX_SALES:
            lines.append(f"   …ещё {len(priced) - _MAX_SALES}")
    else:
        lines.append("   Продаж не было (с момента выпуска как NFT).")

    if r.current_owner:
        lines += ["", f"👛 Текущий владелец: <code>{escape(short_addr(r.current_owner))}</code>"]
    if r.tonviewer_url:
        lines += ["", "👛 Полная история передач по кошелькам — на TonViewer (кнопка ниже)."]
    return "\n".join(lines)


# ── section: estimate ────────────────────────────────────────────────────────
def estimate_text(r: UsernameReport) -> str:
    name = escape(r.username)
    lines = [f"🔎 <b>@{name}</b>", ""]
    if not is_nft(r):
        lines += [
            "❗️ Это не NFT (свободен/не выпущен).",
            "Оценка — <b>по виду</b> юзернейма (длина, паттерн), без истории продаж:",
            "",
        ]

    est = r.estimate
    if not est or not est.point_ton:
        lines.append("Недостаточно данных для оценки.")
        lines.append(_DISCLAIMER)
        return "\n".join(lines)

    title = "Грубая оценка" if est.confidence == "low" else "Оценка цены"
    lines.append(f"📊 <b>{title}: ~{_prices(est.point_ton, r.rates)}</b>")
    margin = _margin_pct(est.point_ton, est.low_ton, est.high_ton)
    conf = _CONFIDENCE_LABEL.get(est.confidence, est.confidence)
    if margin is not None:
        lines.append(f"погрешность: ± ~{margin}%  ·  достоверность: {conf}")
    else:
        lines.append(f"достоверность: {conf}")
    if est.low_ton and est.high_ton:
        lines.append(f"диапазон: {fmt_ton(est.low_ton)}–{fmt_ton(est.high_ton)} TON")
    for sig in est.signals[:5]:
        lines.append(f"• {escape(sig)}")
    lines.append(_DISCLAIMER)
    return "\n".join(lines)
