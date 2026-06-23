from bot.market import MarketModel
from bot.scoring import analyze, detect_patterns, detect_theme
from bot.services.pricing import estimate_price


def _price(u):
    return estimate_price(username=u, listing=None, sales=[], ton_usd=None,
                          market=MarketModel(), score=analyze(u)).point_ton


# ── audit #1: length must NOT dominate — same-length names differ a lot ──
def test_same_length_names_differ_strongly():
    bank = _price("bank")     # dictionary + finance theme
    qwer = _price("qwer")     # keyboard pattern, not a word
    rand = _price("xzkq")     # random consonants
    assert bank > qwer > rand
    assert bank > rand * 3    # quality swings the price by multiples


# ── audit #2: semantics matter a lot ──
def test_dictionary_word_beats_random():
    assert analyze("money").semantic >= 8
    assert analyze("xkqpt").semantic <= 4
    assert _price("money") > _price("xkqpt") * 2


# ── audit #3: thematic scoring ──
def test_theme_detection():
    assert analyze("crypto").theme == "крипто"
    assert analyze("bank").theme == "финансы"
    assert analyze("xzkq").theme is None
    assert detect_theme("mybank")[1] > 0   # substring root match


# ── audit #4: brandability ──
def test_brandability():
    assert analyze("durov").brandability > analyze("xzkq").brandability
    assert analyze("durov").brandability > 6


# ── audit #6/7: patterns detected + rarity ──
def test_patterns_detected():
    assert any("AAAA" in p or "одинаков" in p for p in analyze("aaaa").patterns)
    assert any("ABAB" in p for p in analyze("abab").patterns)
    assert any("Палиндром" in p for p in analyze("abba").patterns)
    assert analyze("aaaa").rarity >= 9
    labels, rarity = detect_patterns("wxyz")
    assert any("Последовательность" in l for l in labels)


# ── audit #12/13/14: ratings, tier, percentile ──
def test_tier_and_percentile():
    bank = analyze("bank")
    rand = analyze("xzkq")
    assert bank.tier in ("S", "A")
    assert rand.tier in ("C", "D")
    assert bank.percentile > rand.percentile
    assert set(bank.ratings) == {
        "Редкость", "Ликвидность", "Брендовость", "Запоминаемость", "Коммерческая ценность",
    }
    assert all(1 <= v <= 10 for v in bank.ratings.values())


# ── audit #11: explainable breakdown ──
def test_breakdown_present():
    sc = analyze("bank")
    labels = [lbl for lbl, _ in sc.breakdown]
    assert "Тематика" in labels and "Семантика" in labels
    assert sc.multiplier > 1.5


def test_long_random_is_cheap_low_tier():
    sc = analyze("averagelongname")
    assert sc.tier in ("C", "D")
