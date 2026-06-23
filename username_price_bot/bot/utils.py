"""Small pure helpers (no I/O) — easy to unit-test."""
from __future__ import annotations

import re

_USERNAME_RE = re.compile(r"^[a-z0-9_]{1,32}$")
# A username that can actually exist on Telegram: starts with a letter, then
# letters/digits/underscore. (All-digit or digit-leading names are impossible.)
_VALID_TG_RE = re.compile(r"^[a-z][a-z0-9_]{3,31}$")


def is_valid_telegram_username(username: str) -> bool:
    """True if such a username can really exist on Telegram (4+ chars here)."""
    return bool(_VALID_TG_RE.match(username)) and not username.endswith("_")


def normalize_username(raw: str | None) -> str | None:
    """Accept @name, name, t.me/name, fragment.com/username/name, name.t.me …

    Returns a clean lowercase username or None if it doesn't look valid.
    """
    if not raw:
        return None
    s = raw.strip().lower()
    if s.startswith("http") or "t.me/" in s or "fragment.com" in s or "getgems.io" in s:
        s = s.split("?", 1)[0].rstrip("/")
        s = s.rsplit("/", 1)[-1]
    s = s.replace("@", "")
    if s.endswith(".t.me"):
        s = s[: -len(".t.me")]
    if not _USERNAME_RE.match(s):
        return None
    return s


def short_addr(addr: str | None, head: int = 4, tail: int = 4) -> str:
    if not addr:
        return "—"
    if len(addr) <= head + tail + 1:
        return addr
    return f"{addr[:head]}…{addr[-tail:]}"


def fmt_ton(value: float | None) -> str:
    if value is None:
        return "—"
    if value >= 100:
        return f"{value:,.0f}".replace(",", " ")
    if value >= 1:
        return f"{value:,.1f}".replace(",", " ")
    return f"{value:.2f}"


def fmt_usd(value: float | None) -> str:
    if value is None:
        return "—"
    if value >= 1000:
        return "$" + f"{value:,.0f}"
    return "$" + f"{value:,.2f}"
