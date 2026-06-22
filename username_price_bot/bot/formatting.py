"""Render a UsernameReport into a Telegram HTML message."""
from __future__ import annotations

from html import escape

from .models import MarketStatus, UsernameReport
from .utils import fmt_ton, fmt_usd, short_addr

_STATUS_LABEL = {
    MarketStatus.ON_SALE: "🟢 Продаётся (фикс. цена)",
    MarketStatus.ON_AUCTION: "🔨 На аукционе",
    MarketStatus.SOLD: "🔴 Недавно продан",
    MarketStatus.NOT_LISTED: "⚪️ Не выставлен на продажу",
    MarketStatus.AVAILABLE: "✨ Свободен (можно занять)",
    MarketStatus.UNKNOWN: "❔ Статус неизвестен",
}

_CONFIDENCE_LABEL = {
    "high": "высокая",
    "medium": "средняя",
    "low": "низкая",
}

_MAX_SALES = 6
_MAX_OWNERS = 6


def _usd_suffix(ton: float | None, rate: float | None) -> str:
    if ton is None or not rate:
        return ""
    return f" (~{fmt_usd(ton * rate)})"


def render_report(report: UsernameReport) -> str:
    r = report
    name = escape(r.username)
    lines: list[str] = [f"🔎 <b>@{name}</b>"]

    # ── Status & current price ────────────────────────────────────────────
    listing = r.listing
    if listing:
        lines.append("")
        lines.append(_STATUS_LABEL.get(listing.status, _STATUS_LABEL[MarketStatus.UNKNOWN]))
        if listing.price_ton:
            src = f" · {escape(listing.source)}" if listing.source else ""
            lines.append(
                f"💰 Цена: <b>{fmt_ton(listing.price_ton)} TON</b>"
                f"{_usd_suffix(listing.price_ton, r.ton_usd_rate)}{src}"
            )

    # ── Estimate ──────────────────────────────────────────────────────────
    est = r.estimate
    if est and est.point_ton:
        lines.append("")
        rng = ""
        if est.low_ton and est.high_ton:
            rng = f" (диапазон {fmt_ton(est.low_ton)}–{fmt_ton(est.high_ton)} TON)"
        conf = _CONFIDENCE_LABEL.get(est.confidence, est.confidence)
        lines.append(
            f"📊 <b>Оценка цены: ~{fmt_ton(est.point_ton)} TON</b>"
            f"{_usd_suffix(est.point_ton, r.ton_usd_rate)}"
        )
        if rng:
            lines.append(f"   {rng.strip()}")
        lines.append(f"   достоверность: {conf}")
        for sig in est.signals[:4]:
            lines.append(f"   • {escape(sig)}")

    # ── Sales history ─────────────────────────────────────────────────────
    priced_sales = [s for s in r.sales if s.price_ton]
    if priced_sales:
        lines.append("")
        lines.append("📜 <b>История продаж:</b>")
        for s in priced_sales[:_MAX_SALES]:
            when = s.timestamp.strftime("%Y-%m-%d") if s.timestamp else "—"
            lines.append(f"   • {when} — {fmt_ton(s.price_ton)} TON")
        if len(priced_sales) > _MAX_SALES:
            lines.append(f"   …ещё {len(priced_sales) - _MAX_SALES}")
    elif r.found:
        lines.append("")
        lines.append("📜 История продаж: продаж не найдено")

    # ── Owner / wallet history ────────────────────────────────────────────
    if r.owners:
        lines.append("")
        lines.append("👛 <b>Кошельки-владельцы:</b>")
        shown = list(reversed(r.owners))[:_MAX_OWNERS]  # newest first
        for o in shown:
            tag = " — текущий" if o.is_current else ""
            period = ""
            if o.since or o.until:
                a = o.since.strftime("%Y-%m") if o.since else "?"
                b = o.until.strftime("%Y-%m") if o.until else "наст. время"
                period = f" ({a} → {b})"
            lines.append(f"   • <code>{escape(short_addr(o.address))}</code>{tag}{period}")
        if len(r.owners) > _MAX_OWNERS:
            lines.append(f"   …всего владельцев: {len(r.owners)}")
    elif r.current_owner:
        lines.append("")
        lines.append(
            f"👛 Текущий владелец: <code>{escape(short_addr(r.current_owner))}</code>"
        )

    # ── Links ─────────────────────────────────────────────────────────────
    links = []
    if r.fragment_url:
        links.append(f'<a href="{escape(r.fragment_url)}">Fragment</a>')
    if r.getgems_url:
        links.append(f'<a href="{escape(r.getgems_url)}">GetGems</a>')
    if links:
        lines.append("")
        lines.append("🔗 " + " · ".join(links))

    if not r.found and not (listing and listing.price_ton):
        lines.append("")
        lines.append(
            "ℹ️ Он-чейн данные не найдены — возможно, юзернейм ещё не выпущен как NFT "
            "или свободен. Проверь ссылку на Fragment выше."
        )

    if r.sources_used:
        lines.append("")
        lines.append(f"<i>Источники: {escape(', '.join(sorted(set(r.sources_used))))}</i>")
    lines.append("<i>⚠️ Оценка приблизительная и не является финансовой рекомендацией.</i>")

    return "\n".join(lines)
