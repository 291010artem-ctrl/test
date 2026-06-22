from __future__ import annotations

from .markets.base import MarketResult

_REASONS = {
    "missing_auth": "нужна авторизация",
    "not_found": "не найдено",
    "not_supported": "не поддерживается этой площадкой",
    "not_for_sale": "не продаётся сейчас",
    "no_response": "площадка не ответила",
    "not_on_chain": "подарок не выведен в TON-блокчейн",
}


def _reason(r: MarketResult) -> str:
    return _REASONS.get(r.error, r.error or "ошибка")


def format_item_info(item_label: str, results: list[MarketResult]) -> str:
    """Just confirms what was found, plus its objective attributes if known.
    No prices here - those live behind the "Актуальная цена" button.
    """
    lines = [f"<b>{item_label}</b>", ""]

    available = [r for r in results if r.available]
    if not available:
        lines.append("Не найдено ни на одной площадке (или нет доступа к API).")
        return "\n".join(lines)

    telegram_official = next((r for r in available if r.market == "Telegram" and r.attributes), None)
    if telegram_official:
        lines.append(f"<b>Атрибуты (по данным Telegram):</b> {telegram_official.attributes}")
    else:
        lines.append("Найдено.")

    return "\n".join(lines)


def format_current_prices(item_label: str, results: list[MarketResult], current_ton_usd: float | None) -> str:
    lines = [f"<b>{item_label}</b>", "", "<b>Актуальные цены:</b>"]

    priced = [r for r in results if r.available and r.current_price_ton is not None]
    if not priced:
        lines.append("Нигде не продаётся сейчас.")
    else:
        for r in priced:
            usd = f" (~${r.current_price_ton * current_ton_usd:.2f})" if current_ton_usd else ""
            link = f' — <a href="{r.url}">ссылка</a>' if r.url else ""
            lines.append(f"• {r.market}: {r.current_price_ton:.2f} TON{usd}{link}")

    not_available = [r for r in results if not r.available]
    if not_available:
        lines.append("")
        lines.append("<b>Недоступно:</b>")
        for r in not_available:
            lines.append(f"• {r.market}: {_reason(r)}")

    return "\n".join(lines)


def format_sales_history(item_label: str, results: list[MarketResult]) -> str:
    lines = [f"<b>{item_label}</b>", "", "<b>История продаж:</b>"]

    available = [r for r in results if r.available]
    all_sales = sorted(
        (sale for r in available for sale in (r.sales_history or [])),
        key=lambda s: s.sold_at,
        reverse=True,
    )
    if not all_sales:
        lines.append("История продаж не найдена ни на одной площадке.")
        return "\n".join(lines)

    for sale in all_sales[:30]:
        date_str = sale.sold_at.strftime("%d.%m.%Y %H:%M")
        rate = (
            f" | курс TON в момент продажи: ${sale.ton_usd_at_sale:.2f} → ${sale.price_usd_at_sale:.2f}"
            if sale.ton_usd_at_sale
            else ""
        )
        lines.append(f"• {sale.market} — {sale.price_ton:.2f} TON{rate} | {date_str}")

    return "\n".join(lines)


def format_estimate(item_label: str, results: list[MarketResult], current_ton_usd: float | None) -> str:
    lines = [f"<b>{item_label}</b>", "", "<b>Оценка стоимости:</b>"]

    available = [r for r in results if r.available]
    telegram = next(
        (r for r in available if r.market == "Telegram" and r.current_price_ton is not None), None
    )
    other_prices = [
        r.current_price_ton for r in available if r.market != "Telegram" and r.current_price_ton is not None
    ]

    if telegram is not None:
        estimate = telegram.current_price_ton
        basis = "официальный floor Telegram по точной комбинации атрибутов (модель/узор/фон)"
    elif other_prices:
        estimate = sum(other_prices) / len(other_prices)
        basis = f"среднее по {len(other_prices)} площадк{'е' if len(other_prices) == 1 else 'ам'}"
    else:
        all_sales = sorted(
            (sale for r in available for sale in (r.sales_history or [])),
            key=lambda s: s.sold_at,
            reverse=True,
        )
        if not all_sales:
            lines.append("Недостаточно данных для оценки.")
            return "\n".join(lines)
        last = all_sales[0]
        estimate = last.price_ton
        basis = f"последняя известная продажа ({last.sold_at.strftime('%d.%m.%Y')}, {last.market})"

    usd = f" (~${estimate * current_ton_usd:.2f})" if current_ton_usd else ""
    lines.append(f"≈ {estimate:.2f} TON{usd}")
    lines.append(f"<i>Основание: {basis}</i>")

    if telegram is not None and other_prices:
        lines.append(f"Для сравнения, среднее по остальным площадкам: {sum(other_prices) / len(other_prices):.2f} TON")

    return "\n".join(lines)
