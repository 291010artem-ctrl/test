from __future__ import annotations

from .markets.base import MarketResult


def format_report(item_label: str, results: list[MarketResult], current_ton_usd: float | None) -> str:
    lines = [f"<b>{item_label}</b>", ""]

    available = [r for r in results if r.available]
    if not available:
        lines.append("Не найдено ни на одной площадке (или нет доступа к API).")
        return "\n".join(lines)

    telegram_official = next((r for r in available if r.market == "Telegram" and r.attributes), None)
    if telegram_official:
        lines.append(f"<b>Атрибуты (по данным Telegram):</b> {telegram_official.attributes}")
        if telegram_official.current_price_ton is not None:
            usd = (
                f" (~${telegram_official.current_price_ton * current_ton_usd:.2f})"
                if current_ton_usd
                else ""
            )
            lines.append(
                f"<b>Объективный floor по этой комбинации атрибутов:</b> "
                f"{telegram_official.current_price_ton:.2f} TON{usd}"
            )
        lines.append("")

    lines.append("<b>Актуальные цены:</b>")
    for r in available:
        if r.current_price_ton is None:
            continue
        usd = f" (~${r.current_price_ton * current_ton_usd:.2f})" if current_ton_usd else ""
        link = f' — <a href="{r.url}">ссылка</a>' if r.url else ""
        lines.append(f"• {r.market}: {r.current_price_ton:.2f} TON{usd}{link}")

    any_history = any(r.sales_history for r in available)
    if any_history:
        lines.append("")
        lines.append("<b>История продаж:</b>")
        all_sales = sorted(
            (sale for r in available for sale in (r.sales_history or [])),
            key=lambda s: s.sold_at,
            reverse=True,
        )
        for sale in all_sales[:20]:
            date_str = sale.sold_at.strftime("%d.%m.%Y")
            usd_at_sale = (
                f", курс TON ${sale.ton_usd_at_sale:.2f} → ${sale.price_usd_at_sale:.2f}"
                if sale.ton_usd_at_sale
                else ""
            )
            lines.append(f"• {date_str} | {sale.market} | {sale.price_ton:.2f} TON{usd_at_sale}")

    not_available = [r for r in results if not r.available]
    if not_available:
        lines.append("")
        lines.append("<b>Недоступно:</b>")
        for r in not_available:
            reason = {
                "missing_auth": "нужна авторизация",
                "not_found": "не найдено",
                "not_supported": "не поддерживается этой площадкой",
                "not_for_sale": "не продаётся сейчас",
                "no_response": "площадка не ответила",
            }.get(r.error, r.error or "ошибка")
            lines.append(f"• {r.market}: {reason}")

    return "\n".join(lines)
