"""Render UsernameReport sections into Telegram HTML messages.

The bot is menu-driven: a short "card" with buttons, and one message per
section (current price / sales history / estimate).
"""
from __future__ import annotations

from datetime import datetime, timezone
from html import escape

from .models import MarketStatus, UsernameReport
from .utils import fmt_ton, short_addr


def _timeleft(dt: datetime) -> str:
    secs = (dt - datetime.now(timezone.utc)).total_seconds()
    if secs <= 0:
        return "завершается"
    days, rem = divmod(int(secs), 86400)
    hours, rem = divmod(rem, 3600)
    mins = rem // 60
    if days:
        return f"{days}д {hours}ч"
    if hours:
        return f"{hours}ч {mins}м"
    return f"{mins}м"

_CONFIDENCE_LABEL = {"high": "высокая", "medium": "средняя", "low": "низкая"}
_CONF_WORD = {"high": "ВЫСОКАЯ", "medium": "СРЕДНЯЯ", "low": "НИЗКАЯ"}
_BASIS_DESC = {
    "active_auction": "активные торги",
    "listing": "активная продажа",
    "last_sale": "история продаж",
    "comparables": "похожие продажи",
    "heuristic": "расчётная формула",
}


def _conf_line(est) -> str:
    word = _CONF_WORD.get(est.confidence, est.confidence)
    desc = _BASIS_DESC.get(est.basis, "")
    return f"{word} (на основе: {desc})" if desc else word

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
    if r.theoretical:
        status = "❗️ Невозможный юзернейм (теоретическая оценка по виду)."
    elif is_nft(r):
        status = "💎 Это NFT-юзернейм."
    else:
        status = "❗️ Не является NFT (свободен или не выпущен как NFT)."
    tier = f" · тир {r.score.tier}" if r.score else ""
    return f"🔎 <b>@{name}</b>{tier}\n{status}\n\nЧто показать? 👇"


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
        if listing.status == MarketStatus.ON_AUCTION:
            line = f"🔨 Идёт аукцион. Текущая ставка: <b>{_prices(listing.price_ton, r.rates)}</b>."
            if r.auction_ends_at:
                line += f"\n⏳ До конца: {_timeleft(r.auction_ends_at)}"
            return head + line + "\n🛒 Сделать ставку — на Fragment (кнопка ниже)."
        return (
            head + f"🟢 Продаётся за <b>{_prices(listing.price_ton, r.rates)}</b>.\n"
            "🛒 Купить — на Fragment (кнопка ниже)."
        )

    # Not actively listed → show the last sale, if any.
    priced = [s for s in r.sales if s.price_ton]
    if priced:
        s = priced[0]
        when = s.timestamp.strftime("%d.%m.%Y") if s.timestamp else "—"
        return (
            head + "🔴 Сейчас не продаётся.\n"
            f"Последняя продажа: <b>{_prices(s.price_ton, r.rates)}</b> ({when})."
        )
    return head + "⚪️ Сейчас не продаётся, продаж не найдено."


# ── section: last sale ───────────────────────────────────────────────────────
def last_sale_text(r: UsernameReport) -> str:
    name = escape(r.username)
    head = f"🔎 <b>@{name}</b>\n\n"
    if not is_nft(r):
        return head + "❗️ Это не NFT — продаж не было."
    priced = [s for s in r.sales if s.price_ton]
    if not priced:
        return (
            head
            + "🧾 Цену последней продажи не удалось получить из он-чейна.\n"
            "Первичный аукцион Fragment не пишет цену в блокчейн — "
            "смотри её на Fragment (кнопка ниже)."
        )
    s = priced[0]
    when = s.timestamp.strftime("%Y-%m-%d") if s.timestamp else "—"
    lines = [
        head.rstrip("\n"), "",
        "🧾 <b>Последняя продажа:</b>",
        f"   {when} — <b>{_prices(s.price_ton, r.rates)}</b>",
    ]
    if s.seller:
        lines.append(f"   продавец: <code>{escape(short_addr(s.seller))}</code>")
    if s.buyer:
        lines.append(f"   покупатель: <code>{escape(short_addr(s.buyer))}</code>")
    if len(priced) > 1:
        lines.append(f"\nВсего продаж: {len(priced)} — см. «📜 История продаж».")
    if r.tonviewer_url:
        lines.append("\n👛 Детали сделки — на TonViewer (кнопка ниже).")
    return "\n".join(lines)


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
        lines.append("   Цен продаж в он-чейне (TonAPI) не найдено.")
        lines.append("   Первичный аукцион Fragment цену в блокчейне не пишет —")
        lines.append("   полная история с ценами есть на Fragment 👇")

    if r.current_owner:
        lines += ["", f"👛 Текущий владелец: <code>{escape(short_addr(r.current_owner))}</code>"]
    if r.tonviewer_url:
        lines += ["", "👛 Полная история передач по кошелькам — на TonViewer (кнопка ниже)."]
    return "\n".join(lines)


# ── section: estimate ────────────────────────────────────────────────────────
def estimate_text(r: UsernameReport) -> str:
    name = escape(r.username)
    lines = [f"🔎 <b>@{name}</b>", ""]
    if r.theoretical:
        lines += [
            "❗️ Такой юзернейм невозможен в Telegram (начинается с цифры или "
            "состоит только из цифр).",
            "Это лишь <b>теоретическая</b> оценка его вида:",
            "",
        ]
    elif not is_nft(r):
        lines += [
            "❗️ Это не NFT (свободен/не выпущен).",
            "Оценка — <b>по виду</b> юзернейма (длина, паттерн), без истории продаж:",
            "",
        ]

    est = r.estimate
    # An NFT always has at least its first-auction price, so a pure-formula
    # result for a confirmed NFT means the market data didn't load (a glitch),
    # NOT that there is no market price.
    nft_no_data = is_nft(r) and est is not None and est.basis == "heuristic"
    if nft_no_data:
        lines += [
            "⚠️ Это NFT — у него точно есть цена первого аукциона, но получить "
            "рыночные данные сейчас не удалось (похоже на сбой источника — проверь /diag).",
            "Ниже — только ориентировочный расчёт по виду имени:",
            "",
        ]

    if not est or not est.point_ton:
        lines.append("Недостаточно данных для оценки.")
        lines.append(_DISCLAIMER)
        return "\n".join(lines)

    if r.theoretical:
        title = "Теоретическая оценка паттерна"
    elif nft_no_data:
        title = "Ориентировочно (данные не загрузились)"
    elif est.confidence == "low":
        title = "Грубая оценка"
    else:
        title = "Оценка цены"
    lines.append(f"📊 <b>{title}: ~{_prices(est.point_ton, r.rates)}</b>")
    margin = _margin_pct(est.point_ton, est.low_ton, est.high_ton)
    lines.append(f"достоверность: {_conf_line(est)}")
    if margin is not None:
        lines.append(f"погрешность: ± ~{margin}%")
    if est.low_ton and est.high_ton:
        lines.append(f"диапазон: {fmt_ton(est.low_ton)}–{fmt_ton(est.high_ton)} TON")

    sc = r.score
    if sc and (sc.theme or sc.patterns):
        bits = []
        if sc.theme:
            bits.append(f"тема: {escape(sc.theme)}")
        if sc.patterns:
            bits.append("паттерн: " + escape(", ".join(sc.patterns[:2])))
        lines.append("🏷 " + " · ".join(bits))

    for sig in est.signals[:5]:
        lines.append(f"• {escape(sig)}")
    lines.append("👉 Подробный разбор и рейтинг — кнопка «🏆 Рейтинг».")
    lines.append(_DISCLAIMER)
    return "\n".join(lines)


# ── section: quality / breakdown ─────────────────────────────────────────────
def _bar(value: int) -> str:
    full = max(0, min(10, value))
    return "▰" * full + "▱" * (10 - full)


def quality_text(r: UsernameReport) -> str:
    name = escape(r.username)
    sc = r.score
    lines = [f"🔎 <b>@{name}</b>", ""]
    if not sc:
        lines.append("Нет данных для рейтинга.")
        return "\n".join(lines)

    lines.append(f"💯 <b>Рейтинг ценности: {sc.rating100}/100</b>")
    lines.append(
        f"🏆 Тир: <b>{sc.tier}</b>  ·  лучше ~{sc.percentile}% "
        f"{len(r.username)}-символьных"
    )
    if r.theoretical:
        lines.append("<i>(теоретически — такой юзернейм нельзя создать)</i>")

    lines.append("")
    lines.append("<b>Рейтинг качества (1–10):</b>")
    for label, val in sc.ratings.items():
        lines.append(f"{label:<20} <code>{_bar(val)}</code> {val}")

    if sc.theme:
        lines += ["", f"🏷 Тема: <b>{escape(sc.theme)}</b>"]
    if sc.patterns:
        lines.append("🧩 Паттерны: " + escape(", ".join(sc.patterns)))

    lines += ["", "<b>Из чего складывается цена</b> (множитель к базе по длине):"]
    for label, frac in sc.breakdown:
        if abs(frac) < 0.005:
            continue
        sign = "+" if frac >= 0 else "−"
        lines.append(f"   {escape(label)}: {sign}{abs(frac) * 100:.0f}%")
    lines.append(f"   = итоговый множитель ×{sc.multiplier:.2f}")

    lines.append(_DISCLAIMER)
    return "\n".join(lines)
