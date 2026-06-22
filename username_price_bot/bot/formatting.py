"""Render a UsernameReport into a Telegram HTML message."""
from __future__ import annotations

from html import escape

from .models import MarketStatus, UsernameReport
from .utils import fmt_ton, short_addr

_STATUS_LABEL = {
    MarketStatus.ON_SALE: "🟢 Продаётся (фикс. цена)",
    MarketStatus.ON_AUCTION: "🔨 На аукционе",
    MarketStatus.SOLD: "🔴 Недавно продан",
    MarketStatus.NOT_LISTED: "⚪️ Не выставлен на продажу",
    MarketStatus.AVAILABLE: "✨ Свободен (можно занять)",
    MarketStatus.UNKNOWN: "❔ Статус неизвестен",
}

_CONFIDENCE_LABEL = {"high": "высокая", "medium": "средняя", "low": "низкая"}

_MAX_SALES = 6
_MAX_OWNERS = 6


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
    usd = rates.get("USD")
    if usd:
        parts.append(f"{_grp(ton * usd)} USDT")
    rub = rates.get("RUB")
    if rub:
        parts.append(f"{_grp(ton * rub)} ₽")
    return " ≈ ".join(parts)


def _margin_pct(point: float, low: float | None, high: float | None) -> int | None:
    if not point or low is None or high is None:
        return None
    dev = ((point - low) / point + (high - point) / point) / 2
    return round(dev * 100)


def _is_nft(r: UsernameReport) -> bool:
    listed = bool(
        r.listing
        and r.listing.price_ton
        and r.listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION)
    )
    return r.found or listed


def render_report(report: UsernameReport) -> str:
    r = report
    name = escape(r.username)
    is_nft = _is_nft(r)
    lines: list[str] = [f"🔎 <b>@{name}</b>"]

    # ── Not an NFT / not found notice (estimate still follows) ──────────────
    if not is_nft:
        lines += [
            "",
            "❗️ Не нашёл этот юзернейм как NFT в TON.",
            "Возможно, он не выпущен как NFT, свободен или пока не существует.",
            "📊 Ниже — <b>примерная</b> оценка, как если бы он был NFT:",
        ]

    # ── Status & current market price ──────────────────────────────────────
    listing = r.listing
    if is_nft and listing:
        lines.append("")
        lines.append(_STATUS_LABEL.get(listing.status, _STATUS_LABEL[MarketStatus.UNKNOWN]))
        if listing.price_ton and listing.status in (MarketStatus.ON_SALE, MarketStatus.ON_AUCTION):
            src = f" · {escape(listing.source)}" if listing.source else ""
            lines.append(f"💰 Цена: <b>{_prices(listing.price_ton, r.rates)}</b>{src}")

    # ── Estimate (always shown) ────────────────────────────────────────────
    est = r.estimate
    if est and est.point_ton:
        lines.append("")
        title = "Грубая оценка" if est.confidence == "low" else "Оценка цены"
        lines.append(f"📊 <b>{title}: ~{_prices(est.point_ton, r.rates)}</b>")
        margin = _margin_pct(est.point_ton, est.low_ton, est.high_ton)
        conf = _CONFIDENCE_LABEL.get(est.confidence, est.confidence)
        meta = f"   достоверность: {conf}"
        if margin is not None:
            meta = f"   погрешность: ± ~{margin}%  ·  достоверность: {conf}"
        lines.append(meta)
        if est.low_ton and est.high_ton:
            lines.append(f"   диапазон: {fmt_ton(est.low_ton)}–{fmt_ton(est.high_ton)} TON")
        for sig in est.signals[:5]:
            lines.append(f"   • {escape(sig)}")

    # ── Sales history (TON only — historical fiat would mislead) ────────────
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

    # ── Owner / wallet history ─────────────────────────────────────────────
    if r.owners:
        lines.append("")
        lines.append("👛 <b>Кошельки-владельцы:</b>")
        for o in list(reversed(r.owners))[:_MAX_OWNERS]:  # newest first
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
        lines.append(f"👛 Текущий владелец: <code>{escape(short_addr(r.current_owner))}</code>")

    # ── Footer ─────────────────────────────────────────────────────────────
    if r.sources_used:
        lines.append("")
        lines.append(f"<i>Источники: {escape(', '.join(sorted(set(r.sources_used))))}</i>")
    lines.append("<i>⚠️ Оценка приблизительная и не является финансовой рекомендацией.</i>")

    return "\n".join(lines)
