"""Asset kinds the bot can appraise (usernames, +888 numbers) and input routing.

Both are Fragment/TON collectibles with the same page structure, so the whole
pipeline is reused; only a few per-kind details differ (URL path, image, DNS
suffix, collection, how the id is displayed and scored).
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .config import DEFAULT_USERNAMES_COLLECTION
from .utils import normalize_username

# Anonymous Telegram Numbers collection on TON (used only for the GetGems link).
DEFAULT_NUMBERS_COLLECTION = "EQAOQdwdw8kGftJCSFgOErM1mBjYPe4DBPq8-AhF6vr9si5N"


@dataclass(frozen=True)
class AssetKind:
    key: str            # "username" | "number"
    noun: str           # "юзернейм" | "номер" (accusative-ish, for messages)
    fragment_path: str  # "username" | "number"
    collection: str
    dns_suffix: str = ".t.me"

    def image_url(self, asset_id: str) -> str:
        return f"https://nft.fragment.com/{self.fragment_path}/{asset_id}.webp"

    def dns_domain(self, asset_id: str) -> str:
        return f"{asset_id}{self.dns_suffix}"


USERNAME = AssetKind("username", "юзернейм", "username", DEFAULT_USERNAMES_COLLECTION)
NUMBER = AssetKind("number", "номер", "number", DEFAULT_NUMBERS_COLLECTION)

# An anonymous number is 888 + 8 digits (11 digits total).
_NUMBER_RE = re.compile(r"^888\d{8}$")


def _strip_url(raw: str) -> str:
    s = raw.strip().lower()
    if s.startswith("http") or "t.me/" in s or "fragment.com" in s:
        s = s.split("?", 1)[0].rstrip("/").rsplit("/", 1)[-1]
    return s.lstrip("@").replace("+", "")


def detect(raw: str) -> tuple[AssetKind, str] | None:
    """Route raw input to (kind, normalized_id), or None if it's neither."""
    if not raw:
        return None
    digits = re.sub(r"\D", "", _strip_url(raw))
    if _NUMBER_RE.match(digits):
        return NUMBER, digits
    username = normalize_username(raw)
    if username:
        return USERNAME, username
    return None


def display(kind: AssetKind, asset_id: str) -> str:
    """Pretty id for messages: @name or +888 8856 4001."""
    if kind.key == "number" and len(asset_id) == 11:
        return f"+{asset_id[:3]} {asset_id[3:7]} {asset_id[7:]}"
    return f"@{asset_id}"
