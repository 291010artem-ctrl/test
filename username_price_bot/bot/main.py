"""Application entry point: wires services together and starts long-polling."""
from __future__ import annotations

import asyncio
import logging

from aiogram import Bot, Dispatcher
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode

from .aggregator import Aggregator
from .config import load_config
from .handlers import commands, lookup, menu
from .http_client import HttpClient
from .services.fragment import FragmentClient
from .services.getgems import GetGemsClient
from .services.tonapi import TonApi

log = logging.getLogger(__name__)


async def main() -> None:
    config = load_config()
    logging.basicConfig(
        level=getattr(logging, config.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)-8s %(name)s: %(message)s",
    )

    http = HttpClient(timeout=config.request_timeout)
    aggregator = Aggregator(
        config=config,
        http=http,
        tonapi=TonApi(http, config.tonapi_base, config.tonapi_key, config.usernames_collection),
        fragment=FragmentClient(http, config.fragment_base),
        getgems=GetGemsClient(http, config.getgems_endpoint, config.getgems_key),
    )

    bot = Bot(
        token=config.bot_token,
        default=DefaultBotProperties(parse_mode=ParseMode.HTML),
    )
    dp = Dispatcher()
    dp["aggregator"] = aggregator
    dp.include_router(commands.router)
    dp.include_router(menu.router)
    dp.include_router(lookup.router)

    log.info("Bot starting (long polling)…")
    try:
        await bot.delete_webhook(drop_pending_updates=True)
        await dp.start_polling(bot)
    finally:
        await http.close()
        await bot.session.close()


if __name__ == "__main__":
    asyncio.run(main())
