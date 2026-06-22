"""Thin async HTTP wrapper with timeout + retry, shared by all services."""
from __future__ import annotations

import asyncio
import logging
from typing import Any

import aiohttp

log = logging.getLogger(__name__)

_DEFAULT_UA = "username-price-bot/1.0 (+https://github.com)"


class HttpClient:
    def __init__(self, timeout: float = 15.0, retries: int = 2):
        self._timeout = aiohttp.ClientTimeout(total=timeout)
        self._retries = retries
        self._session: aiohttp.ClientSession | None = None

    async def _session_obj(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(
                timeout=self._timeout,
                headers={"User-Agent": _DEFAULT_UA},
            )
        return self._session

    async def get_json(self, url: str, **kw: Any) -> Any:
        return await self._request("GET", url, expect="json", **kw)

    async def get_text(self, url: str, **kw: Any) -> str | None:
        return await self._request("GET", url, expect="text", **kw)

    async def post_json(self, url: str, **kw: Any) -> Any:
        return await self._request("POST", url, expect="json", **kw)

    async def _request(self, method: str, url: str, expect: str = "json", **kw: Any) -> Any:
        session = await self._session_obj()
        last_exc: Exception | None = None
        for attempt in range(self._retries + 1):
            try:
                async with session.request(method, url, **kw) as resp:
                    if resp.status == 404:
                        return None
                    if resp.status >= 500:
                        raise aiohttp.ClientResponseError(
                            resp.request_info, resp.history,
                            status=resp.status, message="server error",
                        )
                    resp.raise_for_status()
                    if expect == "json":
                        return await resp.json(content_type=None)
                    return await resp.text()
            except (aiohttp.ClientError, asyncio.TimeoutError) as exc:
                last_exc = exc
                if attempt < self._retries:
                    await asyncio.sleep(0.5 * (2 ** attempt))
                    continue
                raise
        assert last_exc is not None  # unreachable
        raise last_exc

    async def close(self) -> None:
        if self._session and not self._session.closed:
            await self._session.close()
