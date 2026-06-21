"""Obtain Mini App auth (initData / bearer tokens) for the marketplaces that
need a Telegram account: Tonnel, Portals and mrkt.

It logs in under a TECHNICAL Telegram account via Pyrogram, emulates opening
each marketplace Mini App (the same RequestWebView call the official Telegram
client makes), pulls the `tgWebAppData` (a.k.a. initData) out of the returned
URL, and prints the values ready to paste into .env. For mrkt it goes one step
further and exchanges the initData for a bearer token via the market's /auth.

Usage:
    cp .env.example .env          # fill TG_API_ID / TG_API_HASH
    python -m scripts.get_tokens  # first run asks for phone + login code

Notes:
  * Use a DEDICATED account/number, not your main one. Heavy automated polling
    through a user account can get it limited or banned by Telegram.
  * The bot usernames / app short names below are best-effort. If a market
    fails to resolve, open it once in Telegram, check the t.me/<bot>/<app>
    link, and adjust the MARKETS entries accordingly.
  * Portals tokens rotate every 1-7 days, so re-run this script periodically
    (e.g. from cron) to refresh them.
"""

from __future__ import annotations

import asyncio
import os
import sys
from urllib.parse import parse_qs, unquote, urlparse

import aiohttp
from dotenv import load_dotenv
from pyrogram import Client
from pyrogram.raw.functions.messages import RequestAppWebView, RequestWebView
from pyrogram.raw.types import InputBotAppShortName

load_dotenv()

SESSION_NAME = "tech_account"

# Each market: how to open its Mini App.
#   bot       - the bot username hosting the Mini App
#   app       - app short name for t.me/<bot>/<app> links (None -> menu button)
#   start     - start_param to pass, if the app needs one
MARKETS = {
    "TONNEL": {"bot": "tonnel_network_bot", "app": "market", "start": ""},
    "PORTALS": {"bot": "portals", "app": "market", "start": ""},
    "MRKT": {"bot": "mrkt", "app": "app", "start": ""},
}

# mrkt exchanges initData for a bearer token here.
MRKT_AUTH_URL = "https://api.tgmrkt.io/api/v1/auth"


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


async def _open_mini_app(app: Client, cfg: dict) -> str | None:
    """Return the initData string for one marketplace Mini App, or None."""
    bot_peer = await app.resolve_peer(cfg["bot"])

    # Preferred path: named app (t.me/<bot>/<app>).
    if cfg.get("app"):
        try:
            bot_app = InputBotAppShortName(
                bot_id=bot_peer, short_name=cfg["app"]
            )
            res = await app.invoke(
                RequestAppWebView(
                    peer=bot_peer,
                    app=bot_app,
                    platform="android",
                    write_allowed=True,
                    start_param=cfg.get("start") or None,
                )
            )
            return _extract_init_data(res.url)
        except Exception as exc:  # noqa: BLE001
            print(f"  (app webview failed: {exc}; trying menu webview)")

    # Fallback path: menu-button web app.
    try:
        res = await app.invoke(
            RequestWebView(
                peer=bot_peer,
                bot=bot_peer,
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
                # Field name varies; try the common ones.
                return (
                    payload.get("token")
                    or payload.get("accessToken")
                    or payload.get("access_token")
                )
    except Exception as exc:  # noqa: BLE001
        print(f"  (mrkt /auth failed: {exc})")
        return None


async def main() -> None:
    api_id = os.getenv("TG_API_ID")
    api_hash = os.getenv("TG_API_HASH")
    if not api_id or not api_hash:
        sys.exit("Set TG_API_ID and TG_API_HASH in .env (see https://my.telegram.org)")

    results: dict[str, str] = {}
    async with Client(SESSION_NAME, api_id=int(api_id), api_hash=api_hash) as app:
        me = await app.get_me()
        print(f"Logged in as @{me.username or me.id}\n")

        for key, cfg in MARKETS.items():
            print(f"Opening {key} Mini App (@{cfg['bot']})...")
            init_data = await _open_mini_app(app, cfg)
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
                # portals.py builds the header as "tma {token}", so store raw initData.
                results["PORTALS_AUTH_TOKEN"] = init_data
                print("  -> got Portals initData\n")
            elif key == "TONNEL":
                results["TONNEL_INIT_DATA"] = init_data
                print("  -> got Tonnel initData\n")

    if not results:
        sys.exit("No tokens obtained. Check the bot usernames/app names in MARKETS.")

    print("=" * 60)
    print("Paste these into your .env:\n")
    for env_key, value in results.items():
        print(f"{env_key}={value}")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
