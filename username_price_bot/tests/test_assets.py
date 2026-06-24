from bot.assets import NUMBER, USERNAME, detect, display


def test_detect_username():
    assert detect("@Durov") == (USERNAME, "durov")
    assert detect("t.me/bank") == (USERNAME, "bank")
    assert detect("https://fragment.com/username/cool") == (USERNAME, "cool")


def test_detect_number():
    kind, num = detect("+888 8856 4001")
    assert kind is NUMBER and num == "88888564001"
    assert detect("88812345678")[0] is NUMBER
    assert detect("fragment.com/number/88812345678")[0] is NUMBER


def test_detect_junk():
    assert detect("привет мир!") is None
    assert detect("") is None


def test_display():
    assert display(NUMBER, "88888564001") == "+888 8856 4001"
    assert display(USERNAME, "bank") == "@bank"
