from bot.keyboards import NAV_HELP, NAV_START, back_kb, help_kb, result_kb, start_kb
from bot.models import UsernameReport


def _callbacks(kb):
    return [b.callback_data for row in kb.inline_keyboard for b in row if b.callback_data]


def _urls(kb):
    return [b.url for row in kb.inline_keyboard for b in row if b.url]


def test_start_kb_opens_help():
    assert _callbacks(start_kb()) == [NAV_HELP]


def test_help_and_back_go_to_start():
    assert _callbacks(help_kb()) == [NAV_START]
    assert _callbacks(back_kb()) == [NAV_START]


def test_result_kb_has_links_and_back():
    report = UsernameReport(
        username="durov",
        fragment_url="https://fragment.com/username/durov",
        getgems_url="https://getgems.io/x",
    )
    kb = result_kb(report)
    assert len(_urls(kb)) == 2
    assert NAV_START in _callbacks(kb)


def test_result_kb_without_links():
    kb = result_kb(UsernameReport(username="durov"))
    assert _urls(kb) == []
    assert _callbacks(kb) == [NAV_START]
