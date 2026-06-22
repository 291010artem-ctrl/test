"""Obtain Mini App auth (initData / bearer tokens) for the marketplaces that
need a Telegram account: Tonnel, Portals and mrkt.

Login is done via QR code (no SMS/login-code needed): it prints a QR code in
the terminal, you scan it with Telegram on your phone (Settings -> Devices ->
Link Desktop Device), and the script logs in under that account. It then
emulates opening each marketplace Mini App (the same RequestWebView call the
official Telegram client makes), pulls the initData out of the result, and
prints the values ready to paste into .env. For mrkt it goes one step further
and exchanges the initData for a bearer token via the market's /auth.

Usage:
    cp .env.example .env          # fill TG_API_ID / TG_API_HASH
    pip install -r requirements-scripts.txt
    python -m scripts.get_tokens  # scan the printed QR code with Telegram

Notes:
  * Scanning the QR with your phone links THIS script to whatever account is
    open in your Telegram app. If you don't want your main account touched,
    log out and log into a spare/technical account in the Telegram app first,
    then scan with that.
  * The bot usernames / app short names below are best-effort. If a market
    fails to resolve, open it once in Telegram, check the t.me/<bot>/<app>
    link, and adjust the MARKETS entries accordingly.
  * Portals tokens rotate every 1-7 days, so re-run this script periodically
    (e.g. from cron / Task Scheduler) to refresh them.
"""

from __future__ import annotations

import asyncio
import os
import sys
from urllib.parse import parse_qs, unquote, urlparse

import aiohttp
import qrcode
from dotenv import load_dotenv
from telethon import TelegramClient
from telethon.errors import SessionPasswordNeededError
from telethon.tl.functions.messages import (
    RequestAppWebViewRequest,
    RequestWebViewRequest,
)
from telethon.tl.types import InputBotAppShortName

load_dotenv()

SESSION_NAME = "tech_account"

# Each market: how to open its Mini App.
#   bot   - the bot username hosting the Mini App
#   app   - app short name for t.me/<bot>/<app> links (None -> menu button)
#   start - start_param to pass, if the app needs one
MARKETS = {
    "TONNEL": {"bot": "tonnel_network_bot", "app": "market", "start": ""},
    "PORTALS": {"bot": "portals", "app": "market", "start": ""},
    "MRKT": {"bot": "mrkt", "app": "app", "start": ""},
}

# mrkt exchanges initData for a bearer token here.
MRKT_AUTH_URL = "https://api.tgmrkt.io/api/v1/auth"


def _print_qr(url: str) -> None:
    qr = qrcode.QRCode(border=1)
    qr.add_data(url)
    qr.make()
    matrix = qr.get_matrix()
    print()
    for row in matrix:
        print("".join("██" if cell else "  " for cell in row))
    print()
    print(f"Если QR не сканируется, открой эту ссылку на телефоне: {url}")
    print()


def _extract_init_data(web_view_url: str) -> str | None:
    """Pull the URL-decoded tgWebAppData (initData) out of a WebView result URL.

    The URL looks like: https://app/#tgWebAppData=<urlencoded>&tgWebAppVersion=...
    """
    fragment = urlparse(web_view_url).fragment or urlparse(web_view_url).query
    params = parse_qs(fragment)
    raw = params.get("tgWebAppData", [None])[0]
    if not raw:
        return None
    return unquote(raw)


async def _open_mini_app(client: TelegramClient, cfg: dict) -> str | None:
    """Return the initData string for one marketplace Mini App, or None."""
    bot_entity = await client.get_input_entity(cfg["bot"])

    if cfg.get("app"):
        try:
            bot_app = InputBotAppShortName(bot_id=bot_entity, short_name=cfg["app"])
            res = await client(
                RequestAppWebViewRequest(
                    peer=bot_entity,
                    app=bot_app,
                    platform="android",
                    write_allowed=True,
                    start_param=cfg.get("start") or None,
                )
            )
            return _extract_init_data(res.url)
        except Exception as exc:  # noqa: BLE001
            print(f"  (app webview failed: {exc}; trying menu webview)")

    try:
        res = await client(
            RequestWebViewRequest(
                peer=bot_entity,
                bot=bot_entity,
                platform="android",
                from_bot_menu=True,
                start_param=cfg.get("start") or None,
            )
        )
        return _extract_init_data(res.url)
    except Exception as exc:  # noqa: BLE001
        print(f"  (menu webview failed: {exc})")
        return None


async def _mrkt_bearer(init_data: str) -> str | None:
    """Exchange mrkt initData for a bearer token."""
    try:
        async with aiohttp.ClientSession() as session:
            async with session.post(
                MRKT_AUTH_URL,
                json={"data": init_data},
                headers={"Referer": "https://cdn.tgmrkt.io/"},
                timeout=aiohttp.ClientTimeout(total=15),
            ) as resp:
                payload = await resp.json()
                return (
                    payload.get("token")
                    or payload.get("accessToken")
                    or payload.get("access_token")
                )
    except Exception as exc:  # noqa: BLE001
        print(f"  (mrkt /auth failed: {exc})")
        return None


async def _login_with_qr(client: TelegramClient) -> None:
    qr_login = await client.qr_login()
    print("Сканируй этот QR-код Telegram-ом на телефоне")
    print("(Настройки -> Устройства -> Привязать устройство / Linked devices -> Scan QR):")
    _print_qr(qr_login.url)
    try:
        await qr_login.wait(timeout=120)
    except asyncio.TimeoutError:
        print("QR-код истёк (120 секунд). Перезапусти скрипт и попробуй снова.")
        sys.exit(1)
    except SessionPasswordNeededError:
        password = ""
        while not password:
            password = input(
                "На аккаунте включена двухфакторная защита. Введи облачный пароль: "
            ).strip()
            if not password:
                print("Пароль не может быть пустым, попробуй ещё раз.")
        await client.sign_in(password=password)


async def main() -> None:
    api_id = os.getenv("TG_API_ID")
    api_hash = os.getenv("TG_API_HASH")
    if not api_id or not api_hash:
        sys.exit("Set TG_API_ID and TG_API_HASH in .env (see https://my.telegram.org)")

    results: dict[str, str] = {}
    client = TelegramClient(SESSION_NAME, int(api_id), api_hash)
    await client.connect()

    if not await client.is_user_authorized():
        await _login_with_qr(client)

    me = await client.get_me()
    print(f"Logged in as @{me.username or me.id}\n")

    for key, cfg in MARKETS.items():
        print(f"Opening {key} Mini App (@{cfg['bot']})...")
        init_data = await _open_mini_app(client, cfg)
        if not init_data:
            print(f"  -> failed, skipping {key}\n")
            continue

        if key == "MRKT":
            bearer = await _mrkt_bearer(init_data)
            if bearer:
                results["MRKT_BEARER_TOKEN"] = bearer
                print("  -> got mrkt bearer token\n")
            else:
                print("  -> got initData but /auth gave no token\n")
        elif key == "PORTALS":
            results["PORTALS_AUTH_TOKEN"] = init_data
            print("  -> got Portals initData\n")
        elif key == "TONNEL":
            results["TONNEL_INIT_DATA"] = init_data
            print("  -> got Tonnel initData\n")

    await client.disconnect()

    if not results:
        sys.exit("No tokens obtained. Check the bot usernames/app names in MARKETS.")

    print("=" * 60)
    print("Paste these into your .env:\n")
    for env_key, value in results.items():
        print(f"{env_key}={value}")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
