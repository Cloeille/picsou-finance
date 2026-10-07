"""Browser login for the Caisse d'Epargne sidecar (option B).

The login page computes the `password` it posts from the keypad clicks with its
own script, so this module drives the real page: identifier, keypad clicks for
the password digits, Valider, then waits for the human to approve Sécur'Pass.

Rules (see the README):
  * the password is never stored, logged, echoed, put in an error, a screenshot
    or a trace; only the cookie jar of the finished login is returned;
  * one attempt, no retry on any path: a wrong password costs a bank attempt;
  * the keypad fails closed: if the 10 keys cannot ALL be identified, nothing is
    clicked (`KEYPAD_CHANGED`);
  * Sécur'Pass is human only: `complete` waits and never clicks anything;
  * errors carry a code only, logs carry fixed text and exception TYPES only.
"""

import asyncio
import json
import logging
import time
import urllib.parse
import uuid
from dataclasses import dataclass, field
from typing import Any

from playwright.async_api import Error as PlaywrightError
from playwright.async_api import TimeoutError as PlaywrightTimeoutError
from playwright.async_api import async_playwright

import keypad_table
import replay

log = logging.getLogger("caisse-epargne-auth.login")

LOGIN_URL = "https://www.icgauth.caisse-epargne.fr/se-connecter/sso?service=dei"
KEYS_PATH = "/se-connecter/icg/mot-de-passe"
SECURPASS_URL_MARKER = "(modal:icg/cloudcard)"
CLIENT_HOST = "www.caisse-epargne.fr"
CLIENT_PATH_PREFIX = "/espace-client/"
ERROR_PATH_PREFIX = "/erreur"
AUTHORIZE_PATH = "/api/oauth/v2/authorize"
DROPPED_AUTHORIZE_PARAMS = ("code_challenge", "code_challenge_method", "nonce")

IDENTIFIER_INPUT = "#neo-input-1"
# Observed live 2026-10-07: the identifier page also has a carousel button
# labelled `Suivant` (type=button) placed before the form's submit button.
# Only the form's `Valider` (type=submit) moves on to the keypad.
NEXT_BUTTON = 'form:has(#neo-input-1) button[type="submit"]:has-text("Valider")'
SUBMIT_BUTTON = 'button[type="submit"]:has-text("Valider")'
KEY_SELECTOR = ".keyboard-button"
# Not observed live yet (no failed attempt was ever made on purpose): to confirm in L5.
# Deliberately NOT the generic `[role="alert"]`: it also matches informational
# banners, and a false INVALID_CREDENTIALS makes the user retype (bank lock risk).
# It is read only on the identifier page (refusal of the identifier, nothing sent
# yet) and on the password page right after Valider, never while waiting for the
# Sécur'Pass approval.
ERROR_SELECTOR = ".alert-danger, .error-message"
SECURPASS_SELECTOR = 'text=/S[ée]cur.?Pass/i'
_BACKGROUND_JS = "element => getComputedStyle(element).backgroundImage"

MAX_CONCURRENT_BROWSERS = 2
PENDING_TTL_SECONDS = 300
PENDING_SWEEP_SECONDS = 30
# The backend gives /initiate 90 s and /complete 170 s: each overall deadline is
# shorter, so the sidecar answers (and cleans up) before the backend gives up.
INITIATE_DEADLINE_SECONDS = 80
COMPLETE_WAIT_SECONDS = 150  # the human part only
# 10 s after the human wait for the post-approval work (reload + authorize capture).
# Both are clamped to what is left of this deadline, so they fit in those 10 s when
# the human approves at the very end of the wait.
COMPLETE_DEADLINE_SECONDS = 160
STEP_TIMEOUT_SECONDS = 30
AUTHORIZE_WAIT_SECONDS = 15
POLL_INTERVAL_SECONDS = 0.25
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5

CUSTOMER_ID_MAX_LENGTH = 20
PASSWORD_MIN_LENGTH = 4
PASSWORD_MAX_LENGTH = 20

# Headless Chromium only; no stealth or evasion flags on purpose.
LAUNCH_ARGS: list[str] = []


class LoginError(Exception):
    """An HTTP-mappable failure. `str(error)` is the code and nothing else."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


@dataclass
class _Session:
    created_at: float
    playwright: Any = None
    browser: Any = None
    context: Any = None
    page: Any = None
    authorize_requests: list[str] = field(default_factory=list, repr=False)
    slot_held: bool = True


_pending: dict[str, _Session] = {}
_browsers = 0


# --- Browser slots -----------------------------------------------------------
# Plain counter, no await between check and increment: asyncio is single-threaded,
# so the cap cannot be overshot. `initiate` acquires and always either hands the
# slot to a pending session or releases it through `_dispose`.


def browsers_in_use() -> int:
    return _browsers


def _reset_slots_for_tests() -> None:
    global _browsers
    _browsers = 0


def _acquire_slot() -> None:
    global _browsers
    if _browsers >= MAX_CONCURRENT_BROWSERS:
        log.warning("Browser capacity reached")
        raise LoginError(429, "TOO_MANY_PENDING")
    _browsers += 1


def _release_slot() -> None:
    global _browsers
    _browsers = max(0, _browsers - 1)


async def _dispose(session: _Session) -> None:
    """Free the slot first (cannot be skipped), then close what is open, once.

    A cancellation received while closing does not stop the remaining closes (the
    browser process must not outlive its slot); it is re-raised at the end.
    """
    if session.slot_held:
        session.slot_held = False
        _release_slot()
    resources = (
        (session.context, "close"),
        (session.browser, "close"),
        (session.playwright, "stop"),
    )
    session.context = session.browser = session.playwright = session.page = None
    cancelled: asyncio.CancelledError | None = None
    for resource, method in resources:
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, method)(), timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS
            )
        except asyncio.CancelledError as exc:
            cancelled = exc
        except Exception as exc:
            log.warning("Browser resource cleanup failed (%s)", type(exc).__name__)
    if cancelled is not None:
        raise cancelled


# --- Pending store -----------------------------------------------------------


async def cleanup_expired() -> None:
    cutoff = time.monotonic() - PENDING_TTL_SECONDS
    expired = [pid for pid, s in _pending.items() if s.created_at < cutoff]
    for pid in expired:
        # One at a time: a session leaves the store only when it is about to be
        # disposed, so a cancellation never strands several popped sessions (their
        # slots would leak). Not yet popped ones stay reachable by `close_all`.
        session = _pending.pop(pid, None)
        if session is None:
            continue
        try:
            await _dispose(session)
        finally:
            if session.slot_held:
                session.slot_held = False
                _release_slot()


async def close_all() -> None:
    sessions = list(_pending.values())
    _pending.clear()
    for session in sessions:
        await _dispose(session)


async def sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        try:
            await cleanup_expired()
        except Exception as exc:
            log.warning("Pending sweep failed (%s)", type(exc).__name__)


# --- Helpers -------------------------------------------------------------------


def _playwright_factory():
    """Test seam: tests return a fake with the same `.start()`."""
    return async_playwright()


def _is_digits(value: object, min_length: int, max_length: int) -> bool:
    if not isinstance(value, str) or not (min_length <= len(value) <= max_length):
        return False
    return all("0" <= char <= "9" for char in value)


def _to_login_error(exc: Exception) -> LoginError:
    """Map an internal failure to a coded error. Nothing of `exc` is kept."""
    if isinstance(exc, LoginError):
        return exc
    if isinstance(exc, keypad_table.KeypadChanged):
        return LoginError(409, "KEYPAD_CHANGED")
    if isinstance(exc, PlaywrightTimeoutError):
        return LoginError(502, "UPSTREAM_FORMAT_CHANGED")
    if isinstance(exc, TimeoutError):  # an overall deadline (asyncio.timeout)
        log.warning("Login deadline reached")
        return LoginError(502, "UPSTREAM_UNAVAILABLE")
    if isinstance(exc, PlaywrightError):
        log.warning("Browser error during login (%s)", type(exc).__name__)
        return LoginError(502, "UPSTREAM_UNAVAILABLE")
    log.error("Unexpected login failure (%s)", type(exc).__name__)
    return LoginError(500, "INTERNAL_ERROR")


async def _route_guard(route: Any) -> None:
    url = route.request.url
    if replay.is_allowed_url(url) or url.startswith("data:"):
        await route.continue_()
    else:
        await route.abort()


def _ws_as_https(url: str) -> str:
    """`wss://host/...` -> `https://host/...` so the HTTPS allow-list applies; else unchanged."""
    return "https://" + url[len("wss://"):] if url.startswith("wss://") else url


async def _websocket_guard(ws: Any) -> None:
    """Connect a WebSocket to the real server only if its host is allow-listed."""
    if replay.is_allowed_url(_ws_as_https(ws.url)):
        ws.connect_to_server()
    else:
        await ws.close()


async def _visible(page: Any, selector: str) -> bool:
    return await page.locator(selector).first.is_visible()


def _url_parts(url: str) -> tuple[str, str]:
    parts = urllib.parse.urlsplit(url)
    return (parts.hostname or "").lower(), parts.path


def _on_client_space(url: str) -> bool:
    host, path = _url_parts(url)
    return host == CLIENT_HOST and path.startswith(CLIENT_PATH_PREFIX)


def _watch_authorize(session: _Session, page: Any) -> None:
    """Record authorize requests made while the page is in the client space."""

    def on_request(request: Any) -> None:
        url = request.url
        if (
            replay.is_allowed_url(url)
            and urllib.parse.urlsplit(url).path == AUTHORIZE_PATH
            and _on_client_space(page.url)
        ):
            session.authorize_requests.append(url)

    page.on("request", on_request)


# --- /initiate -----------------------------------------------------------------


async def initiate(customer_id: str, password: str) -> dict[str, Any]:
    # A shape the bank would refuse is refused here, before any browser work.
    if not _is_digits(customer_id, 1, CUSTOMER_ID_MAX_LENGTH) or not _is_digits(
        password, PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH
    ):
        raise LoginError(401, "INVALID_CREDENTIALS")

    await cleanup_expired()
    _acquire_slot()
    session = _Session(created_at=time.monotonic())
    try:
        # Overall deadline (backend gives 90 s): on expiry the browser is closed and
        # the slot released below, so no Sécur'Pass push is left pending on a
        # browser nobody will ever complete.
        async with asyncio.timeout(INITIATE_DEADLINE_SECONDS):
            session.playwright = await _playwright_factory().start()
            session.browser = await session.playwright.chromium.launch(
                headless=True, args=LAUNCH_ARGS
            )
            # No service worker (it could answer requests behind the route guard).
            session.context = await session.browser.new_context(
                locale="fr-FR", service_workers="block"
            )
            await session.context.route("**/*", _route_guard)
            await session.context.route_web_socket("**/*", _websocket_guard)
            session.page = await session.context.new_page()
            _watch_authorize(session, session.page)

            await _open_identifier_page(session.page)
            await _submit_identifier(session.page, customer_id)
            await _type_password(session.page, password)
            await _click_valider(session.page)
            await _expect_securpass(session.page)

        process_id = uuid.uuid4().hex + uuid.uuid4().hex
        session.created_at = time.monotonic()
        _pending[process_id] = session
        return {
            "processId": process_id,
            "mfaRequired": True,
            "mfaType": "SECURPASS",
            "expiresInSeconds": PENDING_TTL_SECONDS,
        }
    except BaseException as exc:
        await _dispose(session)
        if not isinstance(exc, Exception):
            raise  # cancellation: the slot and the browser are already released
        raise _to_login_error(exc) from None


async def _open_identifier_page(page: Any) -> None:
    try:
        await page.goto(
            LOGIN_URL, wait_until="domcontentloaded", timeout=STEP_TIMEOUT_SECONDS * 1000
        )
    except PlaywrightTimeoutError:
        raise LoginError(502, "UPSTREAM_UNAVAILABLE") from None


async def _submit_identifier(page: Any, customer_id: str) -> None:
    timeout_ms = STEP_TIMEOUT_SECONDS * 1000
    await page.fill(IDENTIFIER_INPUT, customer_id, timeout=timeout_ms)
    await page.click(NEXT_BUTTON, timeout=timeout_ms)

    keys = page.locator(KEY_SELECTOR)
    count = 0
    deadline = time.monotonic() + STEP_TIMEOUT_SECONDS
    while True:
        url = page.url
        if _url_parts(url)[1].rstrip("/") == KEYS_PATH:
            count = await keys.count()
            if count == keypad_table.KEY_COUNT:
                return
        elif await _visible(page, ERROR_SELECTOR):
            raise LoginError(401, "INVALID_CREDENTIALS")
        if time.monotonic() >= deadline:
            break
        await asyncio.sleep(POLL_INTERVAL_SECONDS)
    if count > 0:
        raise LoginError(409, "KEYPAD_CHANGED")  # a pad that is not 10 keys
    raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")


async def _type_password(page: Any, password: str) -> None:
    keys = page.locator(KEY_SELECTOR)
    # Fail closed: all 10 keys identified, or not a single click.
    digit_of_key = await _read_keypad(keys)
    for digit in password:
        # The pad is read again before EVERY click: any change (reshuffle, new image)
        # stops here, with no further click.
        if await _read_keypad(keys) != digit_of_key:
            raise keypad_table.KeypadChanged()
        await keys.nth(digit_of_key.index(digit)).click(timeout=STEP_TIMEOUT_SECONDS * 1000)


async def _read_keypad(keys: Any) -> list[str]:
    """The digit shown by each of the 10 keys, or `KeypadChanged`."""
    digests: list[str] = []
    for index in range(keypad_table.KEY_COUNT):
        css = await keys.nth(index).evaluate(_BACKGROUND_JS)
        digest = keypad_table.digest_from_css_background(css)
        if digest is None:
            _log_keypad_refusal(len(digests) + 1, len(digests), digests)
            raise keypad_table.KeypadChanged()
        digests.append(digest)
    try:
        return keypad_table.identify_keys(digests)
    except keypad_table.KeypadChanged:
        _log_keypad_refusal(len(digests), len(digests), digests)
        raise


def _log_keypad_refusal(keys_read: int, with_image: int, digests: list[str]) -> None:
    """Why the pad was refused, as counts only. A digest or a CSS value never goes in a log."""
    known = sum(1 for digest in digests if digest in keypad_table.DIGEST_TO_DIGIT)
    log.warning(
        "Keypad refused (keys=%d, with_image=%d, known=%d, distinct=%d)",
        keys_read,
        with_image,
        known,
        len(set(digests)),
    )


async def _click_valider(page: Any) -> None:
    # The single submission. Nothing in this module ever clicks it a second time.
    await page.click(SUBMIT_BUTTON, timeout=STEP_TIMEOUT_SECONDS * 1000)


async def _expect_securpass(page: Any) -> None:
    deadline = time.monotonic() + STEP_TIMEOUT_SECONDS
    while True:
        url = page.url
        if SECURPASS_URL_MARKER in url or await _visible(page, SECURPASS_SELECTOR):
            return
        if await _visible(page, ERROR_SELECTOR):
            raise LoginError(401, "INVALID_CREDENTIALS")
        if time.monotonic() >= deadline:
            raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
        await asyncio.sleep(POLL_INTERVAL_SECONDS)


# --- /complete -----------------------------------------------------------------


async def complete(process_id: str) -> str:
    session = _pending.pop(process_id, None)  # single-use, atomic: no await before it
    if session is None:
        raise LoginError(410, "AUTH_ATTEMPT_EXPIRED")
    started = time.monotonic()
    human_wait_over = False
    try:
        if session.created_at < started - PENDING_TTL_SECONDS:
            raise LoginError(410, "AUTH_ATTEMPT_EXPIRED")
        # Overall deadline (backend gives 170 s) around the human wait AND the
        # authorize capture/reload that follows it.
        async with asyncio.timeout(COMPLETE_DEADLINE_SECONDS) as deadline:
            await _wait_for_client_space(session.page)
            human_wait_over = True
            return await _collect_session_state(session, started + COMPLETE_DEADLINE_SECONDS)
    except Exception as exc:
        if isinstance(exc, TimeoutError) and deadline.expired() and not human_wait_over:
            raise LoginError(408, "APP_VALIDATION_TIMEOUT") from None
        raise _to_login_error(exc) from None
    finally:
        await _dispose(session)


async def _wait_for_client_space(page: Any) -> None:
    deadline = time.monotonic() + COMPLETE_WAIT_SECONDS
    while True:
        url = page.url
        host, path = _url_parts(url)
        if _on_client_space(url):
            return
        if host == CLIENT_HOST and path.startswith(ERROR_PATH_PREFIX):
            raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
        # No error-banner check here, on purpose: only the client-space URL or the
        # timeout decide while the human approves (see ERROR_SELECTOR).
        if time.monotonic() >= deadline:
            raise LoginError(408, "APP_VALIDATION_TIMEOUT")
        await asyncio.sleep(POLL_INTERVAL_SECONDS)


async def _wait_for_authorize(session: _Session, deadline_at: float) -> str:
    """Both the reload and the wait are clamped to what is left before `deadline_at`."""

    def remaining() -> float:
        return max(0.0, deadline_at - time.monotonic())

    async def seen() -> str | None:
        deadline = time.monotonic() + min(AUTHORIZE_WAIT_SECONDS, remaining())
        while True:
            if session.authorize_requests:
                return session.authorize_requests[0]
            if time.monotonic() >= deadline:
                return None
            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    if session.authorize_requests:
        return session.authorize_requests[0]
    await session.page.reload(
        wait_until="domcontentloaded",
        timeout=max(1.0, min(STEP_TIMEOUT_SECONDS, remaining()) * 1000),
    )
    url = await seen()
    if url is None:
        raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
    return url


async def _collect_session_state(session: _Session, deadline_at: float) -> str:
    authorize_url = await _wait_for_authorize(session, deadline_at)
    pairs = urllib.parse.parse_qsl(
        urllib.parse.urlsplit(authorize_url).query, keep_blank_values=True
    )
    params: dict[str, str] = {}
    for key, value in pairs:
        if key not in DROPPED_AUTHORIZE_PARAMS:
            params.setdefault(key, value)

    cookies = [
        {
            "name": cookie["name"],
            "value": cookie["value"],
            "domain": cookie["domain"],
            "path": cookie.get("path") or "/",
        }
        for cookie in await session.context.cookies()
        if _is_caisse_epargne_domain(cookie.get("domain", ""))
    ]
    state = json.dumps({"cookies": cookies, "authorizeParams": params})
    try:
        replay.parse_session_state(state)
    except replay.InvalidSessionState:
        raise LoginError(502, "UPSTREAM_FORMAT_CHANGED") from None
    return state


def _is_caisse_epargne_domain(domain: str) -> bool:
    bare = domain.lstrip(".").lower()
    return bare == "caisse-epargne.fr" or bare.endswith(".caisse-epargne.fr")
