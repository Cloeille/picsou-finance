"""Launch Chromium locally, or lease one process from browser-service.

No FastAPI import. Sidecars translate BrowserCapacityError and
BrowserUnavailableError into their own HTTP errors.
"""

from __future__ import annotations

import asyncio
import os
from typing import Any
from urllib.parse import urlsplit, urlunsplit

import httpx
from playwright.async_api import Playwright


_CONNECT_TIMEOUT_MS = 30_000
_HTTP_TIMEOUT_S = 30.0
_CLOSE_TIMEOUT_S = 5.0


class BrowserServiceError(Exception):
    """Base error for a remote browser lease. Never carries a token or a URL."""


class BrowserCapacityError(BrowserServiceError):
    """browser-service answered 503 BROWSER_CAPACITY."""


class BrowserUnavailableError(BrowserServiceError):
    """The remote browser could not be leased or connected."""


def _service_url() -> str:
    return os.environ.get("BROWSER_SERVICE_URL", "").strip()


def _sidecar_key() -> bytes:
    # browser-service compares the key's UTF-8 bytes; httpx would encode a str header as ASCII.
    return os.environ.get("APP_SIDECAR_API_KEY", "").encode("utf-8")


def websocket_url(base: str, ws_path: str) -> str:
    """http(s) service origin + wsPath → ws(s) URL. Query and fragment are dropped."""
    parts = urlsplit(base.strip())
    scheme = {"http": "ws", "https": "wss"}.get(parts.scheme)
    if scheme is None or not parts.netloc:
        raise BrowserUnavailableError("unsupported browser service url")
    path = ws_path if ws_path.startswith("/") else "/" + ws_path
    return urlunsplit((scheme, parts.netloc, parts.path.rstrip("/") + path, "", ""))


async def _delete_session(base: str, session_id: str, key: bytes) -> None:
    try:
        async with httpx.AsyncClient(timeout=_CLOSE_TIMEOUT_S) as client:
            await client.delete(
                base.rstrip("/") + "/sessions/" + session_id,
                headers={"X-Picsou-Sidecar-Key": key},
            )
    except Exception:
        return


class BrowserLease:
    """A Chromium Playwright is connected to. close() is idempotent and never raises."""

    def __init__(
        self,
        browser: Any,
        *,
        session_id: str | None = None,
        base_url: str | None = None,
        sidecar_key: bytes | None = None,
    ) -> None:
        self.browser = browser
        self._session_id = session_id
        self._base_url = base_url
        self._sidecar_key = sidecar_key or b""
        self._closed = False

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        browser = self.browser
        try:
            if browser is not None:
                await asyncio.wait_for(browser.close(), timeout=_CLOSE_TIMEOUT_S)
        except Exception:
            pass
        session_id = self._session_id
        base_url = self._base_url
        self._session_id = None
        if session_id and base_url:
            await _delete_session(base_url, session_id, self._sidecar_key)
        self._sidecar_key = b""


async def launch_chromium(
    pw: Playwright,
    *,
    args: list[str] | None = None,
    headless: bool = True,
) -> BrowserLease:
    """Local launch when BROWSER_SERVICE_URL is unset, otherwise a remote lease.

    The remote service always starts headless Chromium. headless=False is honored
    only on the local path; the service has no headed mode.
    """
    base = _service_url()
    if not base:
        browser = await pw.chromium.launch(headless=headless, args=args)
        return BrowserLease(browser)

    key = _sidecar_key()
    headers = {"X-Picsou-Sidecar-Key": key}
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_S) as client:
            response = await client.post(
                base.rstrip("/") + "/sessions",
                json={"args": list(args or [])},
                headers=headers,
            )
    except BrowserServiceError:
        raise
    except Exception as exc:
        raise BrowserUnavailableError("browser service unreachable") from exc

    if response.status_code == 503:
        raise BrowserCapacityError("browser service at capacity")
    if response.status_code != 201:
        raise BrowserUnavailableError("browser service rejected the session")

    session_id = None
    try:
        body = response.json()
        session_id = body.get("sessionId") if isinstance(body, dict) else None
        session_id = body["sessionId"]
        token = body["token"]
        ws_path = body["wsPath"]
        if not isinstance(session_id, str) or not isinstance(token, str) or not isinstance(ws_path, str):
            raise TypeError("unusable session")
        ws_url = websocket_url(base, ws_path)
    except Exception as exc:
        if isinstance(session_id, str) and session_id:
            await _delete_session(base, session_id, key)
        raise BrowserUnavailableError("browser service returned an unusable session") from exc

    try:
        browser = await pw.chromium.connect_over_cdp(
            ws_url,
            headers={
                "X-Picsou-Browser-Token": token,
                # Playwright's Node client writes header strings as latin-1 code points.
                "X-Picsou-Sidecar-Key": key.decode("latin-1"),
            },
            timeout=_CONNECT_TIMEOUT_MS,
        )
    except Exception as exc:
        await _delete_session(base, session_id, key)
        raise BrowserUnavailableError("browser service connection failed") from exc
    return BrowserLease(browser, session_id=session_id, base_url=base, sidecar_key=key)
