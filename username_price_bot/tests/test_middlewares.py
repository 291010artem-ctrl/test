from bot.middlewares import ThrottlingMiddleware


def test_throttle_allows_then_blocks():
    m = ThrottlingMiddleware(limit=3, window=60.0)
    assert m._allowed(1)          # 1
    assert m._allowed(1)          # 2
    assert m._allowed(1)          # 3
    assert not m._allowed(1)      # 4th within the window → blocked


def test_throttle_is_per_user():
    m = ThrottlingMiddleware(limit=3, window=60.0)
    for _ in range(3):
        m._allowed(1)
    assert not m._allowed(1)
    assert m._allowed(2)          # a different user is unaffected
