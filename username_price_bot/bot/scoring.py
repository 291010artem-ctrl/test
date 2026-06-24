"""Multi-factor username quality scoring (deterministic, offline, testable).

The price of a username is driven by far more than its length. This module turns
a username into interpretable sub-scores (semantic, thematic, brandability,
pattern/rarity, liquidity), an overall quality, a price *multiplier* applied on
top of the length base, plus a tier, percentile, 1–10 ratings and a breakdown.

Everything here is a transparent heuristic (no trained model / no live data), so
two same-length names now differ a lot: @bank ≫ @qwer ≫ @xkqpt.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

_VOWELS = set("aeiou")

# ── word knowledge ───────────────────────────────────────────────────────────
# Theme roots (substring match) and their RU labels + strength.
_THEMES: dict[str, tuple[str, int, list[str]]] = {
    "finance": ("финансы", 10, [
        "bank", "money", "cash", "pay", "fund", "invest", "trade", "trader",
        "market", "stock", "wealth", "rich", "profit", "gold", "finance",
        "broker", "credit", "loan", "capital", "income", "save", "exchange",
    ]),
    "crypto": ("крипто", 10, [
        "crypto", "coin", "token", "ton", "btc", "eth", "usdt", "usdc", "nft",
        "defi", "dao", "web3", "mint", "stake", "swap", "bitcoin", "ether",
        "chain", "wallet", "satoshi", "hodl", "miner",
    ]),
    "tech": ("технологии/AI", 9, [
        "ai", "app", "web", "dev", "code", "tech", "data", "cloud", "bot",
        "cyber", "digital", "byte", "pixel", "robot", "api", "soft", "net",
    ]),
    "media": ("медиа", 8, [
        "news", "media", "music", "film", "movie", "video", "photo", "play",
        "radio", "stream", "live", "channel", "blog", "vlog", "podcast", "tv",
    ]),
    "gaming": ("гейминг", 8, [
        "game", "gamer", "win", "bet", "poker", "casino", "dice", "slot",
        "quest", "hero", "boss", "clan", "guild", "arena",
    ]),
    "status": ("статус/премиум", 8, [
        "vip", "gold", "king", "queen", "boss", "pro", "lux", "elite", "prime",
        "top", "best", "ace", "star", "royal", "master", "mega", "ultra",
        "super", "alpha", "god", "luxury",
    ]),
}

# Common English words (recognizable) used for the semantic score.
_COMMON_WORDS = {
    "time", "year", "people", "way", "day", "man", "thing", "life", "world",
    "school", "state", "family", "group", "country", "hand", "part", "place",
    "case", "week", "company", "system", "work", "number", "night", "point",
    "home", "water", "room", "area", "money", "story", "fact", "month", "lot",
    "right", "book", "eye", "job", "word", "business", "side", "kind", "head",
    "house", "service", "friend", "power", "hour", "game", "line", "end", "law",
    "car", "city", "name", "team", "idea", "body", "info", "back", "face",
    "level", "office", "door", "health", "art", "war", "history", "party",
    "result", "change", "music", "market", "plan", "death", "course", "air",
    "dog", "cat", "sun", "moon", "sea", "sky", "star", "fire", "ice", "gold",
    "king", "love", "hope", "luck", "soul", "mind", "dream", "light", "dark",
    "fast", "cool", "hot", "red", "blue", "green", "black", "white", "rose",
    "lion", "wolf", "bear", "fox", "hawk", "eagle", "tiger", "shark", "snake",
    "news", "bank", "cash", "trade", "coin", "play", "win", "boss", "pro",
    "vip", "top", "best", "ace", "rich", "deal", "shop", "store", "club",
    "food", "wine", "beer", "milk", "cake", "gift", "toy", "key", "map",
    "code", "data", "web", "app", "bot", "tech", "net", "link", "mail",
    "zone", "spot", "base", "city", "town", "land", "park", "road", "ship",
}
_DICTIONARY = set(_COMMON_WORDS)
for _ru, _str, _roots in _THEMES.values():
    _DICTIONARY.update(_roots)

# Popular first names + notable brands/terms — so e.g. @hayden or @cryptoking
# read as meaningful, not random. Editable / extendable.
_FIRST_NAMES = {
    "alex", "max", "john", "mike", "david", "chris", "james", "robert", "daniel",
    "paul", "mark", "anna", "maria", "kate", "sara", "sarah", "emma", "lucy",
    "nick", "tom", "sam", "ben", "leo", "adam", "ivan", "oleg", "igor", "pavel",
    "hayden", "jessie", "jesse", "walter", "tony", "bruce", "peter", "jack",
    "harry", "george", "henry", "oscar", "victor", "andrew", "kevin", "ryan",
}
_NOTABLE = {
    "google", "apple", "tesla", "amazon", "netflix", "binance", "telegram",
    "durov", "musk", "bitcoin", "ethereum", "openai", "nvidia", "disney",
}
_DICTIONARY.update(_FIRST_NAMES)
_DICTIONARY.update(_NOTABLE)

# Top, instantly recognizable words get the highest semantic score.
_VERY_COMMON = {
    "money", "bank", "cash", "gold", "love", "news", "game", "crypto", "coin",
    "king", "boss", "star", "music", "trade", "market", "win", "vip", "pro",
    "ai", "nft", "ton", "best", "top", "rich", "deal", "shop",
}

_KEYBOARD_ROWS = ("qwertyuiop", "asdfghjkl", "zxcvbnm", "1234567890")


# ── data model ───────────────────────────────────────────────────────────────
@dataclass
class Score:
    semantic: float          # 0..10
    thematic: float          # 0..10
    brandability: float      # 0..10
    rarity: float            # 0..10 (pattern)
    liquidity: float         # 0..10
    multiplier: float        # price multiplier vs length base
    quality: float           # 0..10 overall
    within_quality: float    # 0..10 within its length bucket
    tier: str                # S/A/B/C/D
    percentile: int          # 0..100 within length
    rating100: int = 1       # overall value rating 1..100 (@bank≈100, junk≈1)
    theme: str | None = None        # RU label
    patterns: list[str] = field(default_factory=list)
    breakdown: list[tuple[str, float]] = field(default_factory=list)  # (label, +/- fraction)
    ratings: dict[str, int] = field(default_factory=dict)            # RU rating -> 1..10


# ── helpers ──────────────────────────────────────────────────────────────────
def _clamp(x: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, x))


def _max_consonant_run(s: str) -> int:
    best = run = 0
    for c in s:
        if c.isalpha() and c not in _VOWELS:
            run += 1
            best = max(best, run)
        else:
            run = 0
    return best


def _pronounceability(s: str) -> float:
    letters = [c for c in s if c.isalpha()]
    if not letters:
        return 0.0
    vowels = sum(c in _VOWELS for c in letters)
    ratio = vowels / len(letters)
    ideal = 1.0 - abs(ratio - 0.4) / 0.6           # best around 40% vowels
    cluster_pen = max(0, _max_consonant_run(s) - 2) * 0.2
    specials = sum((not c.isalpha()) for c in s)    # digits / underscore
    special_pen = specials / len(s) * 0.5
    return _clamp(ideal - cluster_pen - special_pen, 0.0, 1.0)


def detect_theme(s: str) -> tuple[str | None, int]:
    best: tuple[str | None, int] = (None, 0)
    for _key, (label, strength, roots) in _THEMES.items():
        if s in roots:
            if strength > best[1]:
                best = (label, strength)
        else:
            for root in roots:
                if len(root) >= 3 and root in s and strength - 3 > best[1]:
                    best = (label, strength - 3)
    return best


def detect_patterns(s: str) -> tuple[list[str], float]:
    labels: list[str] = []
    rarity = 2.0  # baseline "ordinary" name
    n = len(s)
    uniq = len(set(s))

    if uniq == 1:
        labels.append(f"Все символы одинаковые ({s.upper()})")
        rarity = max(rarity, 10.0)
    if n >= 3 and s == s[::-1]:
        labels.append("Палиндром")
        rarity = max(rarity, 8.0)
    if n == 4 and s[0] == s[2] and s[1] == s[3] and s[0] != s[1]:
        labels.append("ABAB")
        rarity = max(rarity, 7.0)
    if n == 4 and s[0] == s[1] and s[2] == s[3] and s[0] != s[2]:
        labels.append("AABB")
        rarity = max(rarity, 6.0)
    if _is_sequential(s):
        labels.append("Последовательность (abcd/1234)")
        rarity = max(rarity, 6.5)
    if _on_keyboard_row(s):
        labels.append("Клавиатурный ряд (qwer)")
        rarity = max(rarity, 6.0)
    if s.isdigit():
        labels.append("Только цифры")
        rarity = max(rarity, 5.0)
    if _has_repeating_unit(s):
        labels.append("Повторяющийся блок")
        rarity = max(rarity, 5.0)
    if uniq <= 2 and uniq != 1:
        rarity = max(rarity, 5.5)
    return labels, rarity


def _is_sequential(s: str) -> bool:
    if len(s) < 3 or not (s.isalpha() or s.isdigit()):
        return False
    diffs = {ord(s[i + 1]) - ord(s[i]) for i in range(len(s) - 1)}
    return diffs in ({1}, {-1})


def _on_keyboard_row(s: str) -> bool:
    if len(s) < 3:
        return False
    return any(s in row or s[::-1] in row for row in _KEYBOARD_ROWS)


def _has_repeating_unit(s: str) -> bool:
    n = len(s)
    for unit in (1, 2, 3):
        if n >= unit * 2 and n % unit == 0 and s == s[:unit] * (n // unit):
            return True
    return False


# ── sub-scores ───────────────────────────────────────────────────────────────
def _is_compound(s: str) -> bool:
    """Two meaningful parts joined, e.g. cryptoking, goldbank, newsbot."""
    for i in range(3, len(s) - 2):
        if s[:i] in _DICTIONARY and s[i:] in _DICTIONARY:
            return True
    return False


def _semantic_score(s: str) -> float:
    if s.isdigit():
        return 1.0
    if s in _VERY_COMMON:
        return 10.0
    if s in _DICTIONARY:
        return 8.0
    if _is_compound(s):
        return 7.5
    return 2.0 + _pronounceability(s) * 4.0  # 2..6 for non-dictionary


def _brandability(s: str) -> float:
    score = _pronounceability(s) * 10.0
    n = len(s)
    if n <= 5:
        score *= 1.1
    elif n >= 10:
        score *= 0.7
    return _clamp(score, 0.0, 10.0)


_LENGTH_LIQ = {4: 9, 5: 8, 6: 6, 7: 5, 8: 4}
_LENGTH_Q = {4: 10, 5: 8, 6: 6, 7: 5, 8: 4}


def _liquidity(length: int, semantic: float, thematic: float) -> float:
    base = _LENGTH_LIQ.get(length, 3 if length >= 9 else 7)
    return _clamp(0.5 * base + 0.3 * semantic + 0.2 * thematic, 0.0, 10.0)


def _tier(overall: float) -> str:
    if overall >= 8.3:
        return "S"
    if overall >= 6.8:
        return "A"
    if overall >= 5.0:
        return "B"
    if overall >= 3.2:
        return "C"
    return "D"


def _r(x: float) -> int:
    return int(max(1, min(10, round(x))))


# ── main ─────────────────────────────────────────────────────────────────────
def analyze(username: str) -> Score:
    s = username.lower()
    n = len(s)

    semantic = _semantic_score(s)
    theme_label, thematic = detect_theme(s)
    brand = _brandability(s)
    patterns, rarity = detect_patterns(s)
    liquidity = _liquidity(n, semantic, float(thematic))

    # Price multiplier (additive, explainable). Centered factors swing both ways;
    # theme & pattern are one-sided bonuses (their absence shouldn't penalise).
    sem_c = (semantic - 5) / 5 * 0.7
    brand_c = (brand - 5) / 5 * 0.4
    # A strongly patterned name (e.g. AAAA) isn't "low-quality random" — don't
    # apply the word/brand penalties on top of its rarity bonus.
    if rarity >= 6:
        sem_c = max(0.0, sem_c)
        brand_c = max(0.0, brand_c)
    contribs = {
        "Семантика": sem_c,
        "Брендовость": brand_c,
        "Тематика": thematic / 10 * 0.8,
        "Паттерн": rarity / 10 * 0.8,
        "Ликвидность": (liquidity - 5) / 5 * 0.2,
    }
    multiplier = _clamp(1.0 + sum(contribs.values()), 0.25, 4.5)

    within = _clamp(
        0.30 * semantic + 0.22 * thematic + 0.20 * brand
        + 0.18 * rarity + 0.10 * liquidity,
        0.0, 10.0,
    )
    length_q = _LENGTH_Q.get(n, 2 if n >= 9 else 9)
    overall = 0.6 * within + 0.4 * length_q
    percentile = int(_clamp(round(100 * (within / 10) ** 0.85), 1, 99))
    rating100 = int(_clamp(round(overall * 12 - 12), 1, 100))  # @bank≈95, junk≈1

    memorability = _clamp(brand - max(0, n - 6) * 0.6 + (2 if patterns else 0), 1, 10)
    ratings = {
        "Редкость": _r(rarity),
        "Ликвидность": _r(liquidity),
        "Брендовость": _r(brand),
        "Запоминаемость": _r(memorability),
        "Коммерческая ценность": _r(0.5 * thematic + 0.5 * semantic),
    }

    return Score(
        semantic=semantic,
        thematic=float(thematic),
        brandability=brand,
        rarity=rarity,
        liquidity=liquidity,
        multiplier=multiplier,
        quality=overall,
        within_quality=within,
        tier=_tier(overall),
        percentile=percentile,
        rating100=rating100,
        theme=theme_label,
        patterns=patterns,
        breakdown=sorted(contribs.items(), key=lambda kv: kv[1], reverse=True),
        ratings=ratings,
    )


# ── numbers (+888) ───────────────────────────────────────────────────────────
NUMBER_BASE_TON = 25.0  # typical anonymous number when nothing else is known


def analyze_number(number: str) -> Score:
    """Quality of an anonymous +888 number from its digit patterns."""
    suffix = number[3:] if number.startswith("888") else number
    n = max(1, len(suffix))
    patterns, rarity = detect_patterns(suffix)
    patterns = [p for p in patterns if "цифры" not in p.lower()]  # noise for numbers

    uniq = len(set(suffix))
    repetition = (n - uniq) / n * 10
    trailing0 = n - len(suffix.rstrip("0"))
    roundness = _clamp(trailing0 * 2.0, 0.0, 10.0)
    memorability = _clamp(10 - (uniq - 1) * 1.3 + (2 if rarity >= 6 else 0), 1.0, 10.0)
    luck = _clamp(suffix.count("8") * 0.8, 0.0, 6.0)
    liquidity = _clamp(0.5 * rarity + 0.3 * memorability + 0.2 * roundness, 0.0, 10.0)

    contribs = {
        "Паттерн/повторы": rarity / 10 * 1.1,
        "Запоминаемость": (memorability - 5) / 5 * 0.5,
        "Круглость (нули)": roundness / 10 * 0.4,
        "Счастливые 8": luck / 10 * 0.3,
    }
    multiplier = _clamp(1.0 + sum(contribs.values()), 0.3, 6.0)

    within = _clamp(0.35 * rarity + 0.30 * memorability + 0.20 * liquidity
                    + 0.15 * roundness, 0.0, 10.0)
    ratings = {
        "Редкость": _r(rarity),
        "Повторы": _r(repetition),
        "Запоминаемость": _r(memorability),
        "Круглость": _r(roundness),
        "Ликвидность": _r(liquidity),
    }
    return Score(
        semantic=memorability, thematic=luck, brandability=memorability,
        rarity=rarity, liquidity=liquidity, multiplier=multiplier,
        quality=within, within_quality=within, tier=_tier(within),
        percentile=int(_clamp(round(100 * (within / 10) ** 0.85), 1, 99)),
        rating100=int(_clamp(round(within * 12 - 12), 1, 100)),
        theme=("счастливые 8" if luck >= 3 else None),
        patterns=patterns,
        breakdown=sorted(contribs.items(), key=lambda kv: kv[1], reverse=True),
        ratings=ratings,
    )
