"""Runtime configuration loaded from environment / .env file."""
from __future__ import annotations

import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()

# Telegram Usernames NFT collection on TON.
DEFAULT_USERNAMES_COLLECTION = "EQCA14o1-VWhS2efqoh_9M1b_A9DtKTuoqfmkn83AbJzwnPi"


def _get(name: str, default: str | None = None) -> str | None:
    val = os.getenv(name)
    return val if val not in (None, "") else default


@dataclass(frozen=True)
class Config:
    bot_token: str
    tonapi_base: str = "https://tonapi.io"
    tonapi_key: str | None = None
    getgems_endpoint: str = "https://api.getgems.io/graphql"
    getgems_key: str | None = None
    fragment_base: str = "https://fragment.com"
    usernames_collection: str = DEFAULT_USERNAMES_COLLECTION
    request_timeout: float = 15.0
    cache_ttl: float = 120.0
    log_level: str = "INFO"


def load_config() -> Config:
    token = _get("BOT_TOKEN")
    if not token:
        raise RuntimeError(
            "BOT_TOKEN is not set. Create a bot via @BotFather and put the token "
            "into a .env file (see .env.example) or the environment."
        )
    return Config(
        bot_token=token,
        tonapi_base=_get("TONAPI_BASE", "https://tonapi.io"),
        tonapi_key=_get("TONAPI_KEY"),
        getgems_endpoint=_get("GETGEMS_ENDPOINT", "https://api.getgems.io/graphql"),
        getgems_key=_get("GETGEMS_API_KEY"),
        fragment_base=_get("FRAGMENT_BASE", "https://fragment.com"),
        usernames_collection=_get("USERNAMES_COLLECTION", DEFAULT_USERNAMES_COLLECTION),
        request_timeout=float(_get("REQUEST_TIMEOUT", "15")),
        cache_ttl=float(_get("CACHE_TTL", "300")),
        log_level=_get("LOG_LEVEL", "INFO"),
    )
