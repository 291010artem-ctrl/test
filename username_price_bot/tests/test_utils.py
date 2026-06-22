from bot.utils import fmt_ton, fmt_usd, normalize_username, short_addr


def test_normalize_variants():
    assert normalize_username("@durov") == "durov"
    assert normalize_username("durov") == "durov"
    assert normalize_username("  DUROV ") == "durov"
    assert normalize_username("t.me/durov") == "durov"
    assert normalize_username("https://t.me/durov") == "durov"
    assert normalize_username("https://fragment.com/username/durov") == "durov"
    assert normalize_username("durov.t.me") == "durov"
    assert normalize_username("https://getgems.io/nft/cool?x=1") == "cool"


def test_normalize_rejects_junk():
    assert normalize_username("") is None
    assert normalize_username(None) is None
    assert normalize_username("has space") is None
    assert normalize_username("with-dash") is None
    assert normalize_username("a" * 33) is None


def test_short_addr():
    assert short_addr(None) == "—"
    assert short_addr("EQ1234567890") == "EQ12…7890"
    assert short_addr("short") == "short"


def test_formatting_numbers():
    assert fmt_ton(None) == "—"
    assert fmt_ton(1500) == "1 500"
    assert fmt_ton(2.5) == "2.5"
    assert fmt_usd(5000) == "$5,000"
    assert fmt_usd(12.5) == "$12.50"
