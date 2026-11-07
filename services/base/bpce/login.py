"""Browser login for the BPCE sidecars (Caisse d'Epargne, Banque Populaire).

The login page computes the `password` it posts from the keypad clicks with its
own script, so this module drives the real page. It is split in two calls:

  * `/initiate` picks the establishment when the site has a picker (Banque
    Populaire), types the identifier, waits for the 10-key pad and returns the key
    images to the UI. It never clicks the pad.
  * `/keypad` clicks the positions the USER chose in the pop-up, then Valider, then
    waits for the Sécur'Pass approval page (which the human approves on the phone,
    `/complete`).

The password never exists outside the user's browser: only key positions travel.

Rules (see the README):
  * every credential, position, image and cookie stays out of logs, errors and
    traces; the cookie jar of a finished login is the only thing returned;
  * one attempt, no retry on any path: a wrong password costs a bank attempt;
  * the pad is pinned: the 10 digests are re-read and compared before EVERY click,
    and any difference stops the typing at once (`KEYPAD_CHANGED`);
  * Sécur'Pass is human only: `complete` waits and never clicks anything;
  * errors carry a code only, logs carry fixed text, counts and exception TYPES.

The hosts, the region picker and the cookie domain come from the `Site` handed to
`initiate`, pinned on the pending login for `/keypad` and `/complete`.
"""

import asyncio
import json
import logging
import time
import unicodedata
import urllib.parse
import uuid
from dataclasses import dataclass, field
from typing import Any, cast

from playwright.async_api import Error as PlaywrightError
from playwright.async_api import TimeoutError as PlaywrightTimeoutError
from playwright.async_api import async_playwright

from . import keypad_table, replay
from .site import Site

log = logging.getLogger("bpce.login")

KEYS_PATH = "/se-connecter/icg/mot-de-passe"
SECURPASS_URL_MARKER = "(modal:icg/cloudcard)"
ERROR_PATH_PREFIX = "/erreur"
AUTHORIZE_PATH = "/api/oauth/v2/authorize"
DROPPED_AUTHORIZE_PARAMS = ("code_challenge", "code_challenge_method", "nonce")

# The identifier field's id depends on the instance the bank serves: observed
# live as `#neo-input-1` (2026-10-07) and as `#neo-input-2` (2026-10-31, same
# region, another deployment). Match the prefix, never one numbered id.
IDENTIFIER_INPUT = 'input[type="text"][id^="neo-input-"]'
# Observed live 2026-10-07: the identifier page also has a carousel button
# labelled `Suivant` (type=button) placed before the form's submit button.
# Only the form's `Valider` (type=submit) moves on to the keypad.
NEXT_BUTTON = f'form:has({IDENTIFIER_INPUT}) button[type="submit"]:has-text("Valider")'
SUBMIT_BUTTON = 'button[type="submit"]:has-text("Valider")'
KEY_SELECTOR = ".keyboard-button"
# Not observed live yet (no failed attempt was ever made on purpose): to confirm.
# Deliberately NOT the generic `[role="alert"]`: it also matches informational
# banners, and a false INVALID_CREDENTIALS makes the user retype (bank lock risk).
# It is read on the identifier page (refusal of the identifier, nothing sent yet)
# and on the password page right after Valider, never while waiting for Sécur'Pass.
# Observed live 2026-11-05 on Banque Populaire: a wrong identifier or password opens a
# modal titled "Identifiant ou mot de passe erroné" (Réessayer / Annuler) over the
# keypad page. Matched on that wording only, never a generic dialog.
ERROR_SELECTOR = '.alert-danger, .error-message, :text-matches("mot de passe erron", "i")'
SECURPASS_SELECTOR = 'text=/S[ée]cur.?Pass/i'
# Observed live on Banque Populaire 2026-11-05, before the identifier field: a
# `neo-select-simple` "Sélectionnez votre établissement bancaire" whose trigger is a
# combobox button (`#neo-input-2` that day: never matched by its numbered id) and
# whose options carry the establishment names as their exact text.
REGION_TRIGGER = 'neo-select-simple button[role="combobox"]'
REGION_OPTION = 'neo-select-simple-option[role="option"]'
_BACKGROUND_JS = "element => getComputedStyle(element).backgroundImage"

# How long a click waits before checking whether the consent banner covers its target.
BANNER_CLICK_ATTEMPT_SECONDS = 3
MAX_CONCURRENT_BROWSERS = 2
PENDING_TTL_SECONDS = 300
PENDING_SWEEP_SECONDS = 30
# A session waiting for the keypad click ages out fast: nobody can use it later.
KEYPAD_TTL_SECONDS = 90
# The backend gives /initiate 90 s, /keypad 70 s and /complete 170 s: each overall
# deadline is shorter, so the sidecar answers (and cleans up) before the backend
# gives up.
INITIATE_DEADLINE_SECONDS = 80
KEYPAD_DEADLINE_SECONDS = 60
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
POSITIONS_MIN_LENGTH = 6
POSITIONS_MAX_LENGTH = 12

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
    site: Site = field(repr=False)
    playwright: Any = None
    browser: Any = None
    context: Any = None
    page: Any = None
    digests: list[str] = field(default_factory=list, repr=False)
    keypad_done: bool = False
    authorize_requests: list[str] = field(default_factory=list, repr=False)
    slot_held: bool = True
    shutdown_requested: bool = False


_pending: dict[str, _Session] = {}
_keypad_inflight: dict[str, _Session] = {}
_keypad_tasks: dict[str, asyncio.Task[Any]] = {}
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
    session.digests = []
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


def _ttl_of(session: _Session) -> int:
    """A session waiting for the keypad click ages out faster than a Sécur'Pass wait."""
    return PENDING_TTL_SECONDS if session.keypad_done else KEYPAD_TTL_SECONDS


async def cleanup_expired() -> None:
    now = time.monotonic()
    expired = [
        pid for pid, session in _pending.items() if session.created_at < now - _ttl_of(session)
    ]
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
    sessions = list({
        id(session): session
        for session in (*_pending.values(), *_keypad_inflight.values())
    }.values())
    for session in sessions:
        session.shutdown_requested = True
    _pending.clear()

    # The keypad task owns its browser while it is interacting with the page.
    # Wait for it to finish before disposal; on success it must not republish.
    tasks = [
        task
        for process_id in _keypad_inflight
        if (task := _keypad_tasks.get(process_id)) is not None
    ]
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=True)
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


def _is_identifier(site: Site, value: object) -> bool:
    """1 to 20 ASCII digits, or ASCII letters and digits on a site whose identifiers have letters."""
    if not isinstance(value, str) or not (1 <= len(value) <= CUSTOMER_ID_MAX_LENGTH):
        return False
    if not site.alphanumeric_identifier:
        return _is_digits(value, 1, CUSTOMER_ID_MAX_LENGTH)
    return all(char.isascii() and char.isalnum() for char in value)


def valid_positions(value: object) -> bool:
    """`[int]` of 6..12 key positions in 0..9. `bool` and `float` are not ints here."""
    if not isinstance(value, list):
        return False
    if not POSITIONS_MIN_LENGTH <= len(value) <= POSITIONS_MAX_LENGTH:
        return False
    return all(type(position) is int and 0 <= position < keypad_table.KEY_COUNT for position in value)


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


def _route_guard(site: Site):
    async def guard(route: Any) -> None:
        url = route.request.url
        if replay.is_allowed_url(site, url) or url.startswith("data:"):
            await route.continue_()
        else:
            await route.abort()

    return guard


def _ws_as_https(url: str) -> str:
    """`wss://host/...` -> `https://host/...` so the HTTPS allow-list applies; else unchanged."""
    return "https://" + url[len("wss://"):] if url.startswith("wss://") else url


def _websocket_guard(site: Site):
    async def guard(ws: Any) -> None:
        """Connect a WebSocket to the real server only if its host is allow-listed."""
        if replay.is_allowed_url(site, _ws_as_https(ws.url)):
            ws.connect_to_server()
        else:
            await ws.close()

    return guard


async def _visible(page: Any, selector: str) -> bool:
    return await page.locator(selector).first.is_visible()


def _url_parts(url: str) -> tuple[str, str]:
    parts = urllib.parse.urlsplit(url)
    return (parts.hostname or "").lower(), parts.path


def _on_client_space(site: Site, url: str) -> bool:
    host, path = _url_parts(url)
    return host == site.client_host and path.startswith(site.client_path_prefix)


def _watch_authorize(session: _Session, page: Any) -> None:
    """Record authorize requests made while the page is in the client space.

    The login pages call authorize too (another client, observed on both banks):
    those are made from the login host, never from the client space, and are ignored.
    """
    site = session.site

    def on_request(request: Any) -> None:
        url = request.url
        if (
            replay.is_allowed_url(site, url)
            and urllib.parse.urlsplit(url).path == AUTHORIZE_PATH
            and _on_client_space(site, page.url)
        ):
            session.authorize_requests.append(url)

    page.on("request", on_request)


# --- /initiate -----------------------------------------------------------------


async def initiate(site: Site, customer_id: str, *, region: str | None = None) -> dict[str, Any]:
    """`region` is one of `site.regions`' codes on a site with a picker, else None."""
    # A region the site does not list (or one for a site without a picker) is a
    # caller bug: refused before anything else, like an invalid body.
    region_label = site.region_label(region)
    if (region_label is None) if site.regions else (region is not None):
        raise LoginError(400, "INVALID_REQUEST")
    # A shape the bank would refuse is refused here, before any browser work.
    if not _is_identifier(site, customer_id):
        raise LoginError(401, "INVALID_CREDENTIALS")

    await cleanup_expired()
    _acquire_slot()
    session = _Session(created_at=time.monotonic(), site=site)
    try:
        # Overall deadline (backend gives 90 s): on expiry the browser is closed and
        # the slot released below, so no half-open login is left behind.
        async with asyncio.timeout(INITIATE_DEADLINE_SECONDS):
            session.playwright = await _playwright_factory().start()
            session.browser = await session.playwright.chromium.launch(
                headless=True, args=LAUNCH_ARGS
            )
            # No service worker (it could answer requests behind the route guard).
            session.context = await session.browser.new_context(
                locale="fr-FR", service_workers="block"
            )
            await session.context.route("**/*", _route_guard(site))
            await session.context.route_web_socket("**/*", _websocket_guard(site))
            session.page = await session.context.new_page()
            _watch_authorize(session, session.page)

            await _open_identifier_page(site, session.page)
            if region_label is not None:
                await _select_region(site, session.page, region_label)
            await _submit_identifier(session.page, customer_id)
            images, digests = await _read_pad(session.page)

        session.digests = digests
        process_id = uuid.uuid4().hex + uuid.uuid4().hex
        session.created_at = time.monotonic()
        _pending[process_id] = session
        return {
            "processId": process_id,
            "keypad": {"images": images, "columns": keypad_table.KEY_COLUMNS},
            "expiresInSeconds": KEYPAD_TTL_SECONDS,
        }
    except BaseException as exc:
        await _dispose(session)
        if not isinstance(exc, Exception):
            raise  # cancellation: the slot and the browser are already released
        raise _to_login_error(exc) from None


async def _open_identifier_page(site: Site, page: Any) -> None:
    try:
        await page.goto(
            site.login_url, wait_until="domcontentloaded", timeout=STEP_TIMEOUT_SECONDS * 1000
        )
    except PlaywrightTimeoutError:
        raise LoginError(502, "UPSTREAM_UNAVAILABLE") from None


async def _wait_for_count(locator: Any) -> int:
    """The locator's match count once it is non-zero, or 0 after the step timeout."""
    deadline = time.monotonic() + STEP_TIMEOUT_SECONDS
    while True:
        count = await locator.count()
        if count or time.monotonic() >= deadline:
            return count
        await asyncio.sleep(POLL_INTERVAL_SECONDS)


async def _dismiss_cookie_banner(site: Site, page: Any) -> None:
    """Refuse the consent banner if it covers the page; its absence is fine."""
    if site.cookie_banner_button is not None and await _visible(page, site.cookie_banner_button):
        await page.click(site.cookie_banner_button, timeout=STEP_TIMEOUT_SECONDS * 1000)


async def _click_past_banner(site: Site, page: Any, target: Any, timeout_ms: float) -> None:
    """Click `target`; if the consent banner showed up since it was checked and blocks
    the click, refuse the banner and click once more. No other retry: this only runs
    before the identifier is typed, so nothing reaches the bank twice.
    """
    if site.cookie_banner_button is None:
        await target.click(timeout=timeout_ms)
        return
    # A short first attempt, so a banner covering the target is seen in seconds rather
    # than after the whole step timeout; the deadline of /initiate is 80 s in all.
    first_ms = min(timeout_ms, BANNER_CLICK_ATTEMPT_SECONDS * 1000)
    try:
        await target.click(timeout=first_ms)
        return
    except PlaywrightTimeoutError:
        if await _visible(page, site.cookie_banner_button):
            await _dismiss_cookie_banner(site, page)
            await target.click(timeout=timeout_ms)
        elif first_ms < timeout_ms:
            # No banner: the target is just slow. Wait out the rest of the step.
            await target.click(timeout=timeout_ms - first_ms)
        else:
            raise


def _region_text(text: str) -> str:
    """Option text with its Unicode form and spacing made canonical, nothing else.

    `BOURGOGNE FRANCHE-COMTÉ` can come with a decomposed accent or a non-breaking
    space depending on how the page is rendered: neither names another bank. Case
    and accents still count.
    """
    return " ".join(unicodedata.normalize("NFC", text).split())


def _shows(text: object, label: str) -> bool:
    wanted = _region_text(label)
    return isinstance(text, str) and wanted in (_region_text(line) for line in text.splitlines())


async def _matching_options(options: Any, label: str, timeout_ms: float) -> tuple[int, list[int]]:
    """Re-read the option list until exactly one option names `label` or the step times out.

    A list that renders progressively is read again rather than judged on its first
    option; it never settles for a near match.
    """
    wanted = _region_text(label)
    deadline = time.monotonic() + STEP_TIMEOUT_SECONDS
    while True:
        count = await options.count()
        matches = [
            index
            for index in range(count)
            if _region_text(await options.nth(index).inner_text(timeout=timeout_ms)) == wanted
        ]
        if len(matches) == 1 or time.monotonic() >= deadline:
            return count, matches
        await asyncio.sleep(POLL_INTERVAL_SECONDS)


async def _select_region(site: Site, page: Any, label: str) -> None:
    """Pick the establishment whose option text is `label` (Unicode form and spacing aside), then check it took.

    A missing picker, no option or several with that exact text, or a picker that
    does not show the choice afterwards is a page this module does not know: it stops
    with UPSTREAM_FORMAT_CHANGED and never picks another establishment.
    """
    timeout_ms = STEP_TIMEOUT_SECONDS * 1000
    trigger = page.locator(REGION_TRIGGER)
    if await _wait_for_count(trigger) != 1:
        log.warning("Region picker not found")
        raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
    await _dismiss_cookie_banner(site, page)
    await _click_past_banner(site, page, trigger.first, timeout_ms)

    options = page.locator(REGION_OPTION)
    count, matches = await _matching_options(options, label, timeout_ms)
    if len(matches) != 1:
        log.warning("Region option not found (options=%d, matching=%d)", count, len(matches))
        raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
    await _click_past_banner(site, page, options.nth(matches[0]), timeout_ms)

    deadline = time.monotonic() + STEP_TIMEOUT_SECONDS
    while not _shows(await trigger.first.inner_text(timeout=timeout_ms), label):
        if time.monotonic() >= deadline:
            log.warning("Region choice not shown by the picker")
            raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")
        await asyncio.sleep(POLL_INTERVAL_SECONDS)


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
        _log_keypad_refusal(count, 0, [])
        raise keypad_table.KeypadChanged()  # a pad that is not 10 keys
    raise LoginError(502, "UPSTREAM_FORMAT_CHANGED")


async def _read_pad(page: Any) -> tuple[list[str], list[str]]:
    """`(the 10 key images in DOM order, their digests)`, or `KeypadChanged`."""
    keys = page.locator(KEY_SELECTOR)
    count = await keys.count()
    if count != keypad_table.KEY_COUNT:
        _log_keypad_refusal(count, 0, [])
        raise keypad_table.KeypadChanged()
    images: list[str] = []
    digests: list[str] = []
    for index in range(keypad_table.KEY_COUNT):
        css = await keys.nth(index).evaluate(_BACKGROUND_JS)
        parsed = keypad_table.parse_key_css(css)
        if parsed is None:
            _log_keypad_refusal(index + 1, len(digests), digests)
            raise keypad_table.KeypadChanged()
        digest, uri = parsed
        digests.append(digest)
        images.append(uri)
    if len(set(digests)) != keypad_table.KEY_COUNT:
        _log_keypad_refusal(keypad_table.KEY_COUNT, keypad_table.KEY_COUNT, digests)
        raise keypad_table.KeypadChanged()
    return images, digests


def _log_keypad_refusal(
    keys_read: int, with_image: int, digests: list[str], expected: list[str] | None = None
) -> None:
    """Why the pad was refused, as counts only. A digest or a CSS value never goes in a log."""
    matching = (
        sum(1 for current, pinned in zip(digests, expected) if current == pinned)
        if expected is not None
        else None
    )
    log.warning(
        "Keypad refused (keys=%d, with_image=%d, distinct=%d%s)",
        keys_read,
        with_image,
        len(set(digests)),
        f", matching={matching}" if matching is not None else "",
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


# --- /keypad -------------------------------------------------------------------


async def keypad(process_id: str, positions: object) -> dict[str, Any]:
    if not valid_positions(positions):
        # Refused before anything is looked at, and the pending login is dropped:
        # a set of positions the UI would never produce means a broken caller.
        session = _pending.pop(process_id, None)
        if session is not None:
            await _dispose(session)
        raise LoginError(422, "INVALID_POSITIONS")

    session = _pending.get(process_id)
    if session is None:
        if process_id in _keypad_inflight:
            raise LoginError(409, "KEYPAD_CHANGED")
        raise LoginError(410, "AUTH_ATTEMPT_EXPIRED")
    if session.keypad_done:
        # Single use, set before the first await: a second call never clicks again.
        raise LoginError(409, "KEYPAD_CHANGED")
    if session.created_at < time.monotonic() - KEYPAD_TTL_SECONDS:
        _pending.pop(process_id, None)
        await _dispose(session)
        raise LoginError(408, "KEYPAD_EXPIRED")
    # Remove it before the first await: complete and the sweeper may only claim
    # sessions from _pending, and a second keypad call is refused via inflight.
    _pending.pop(process_id, None)
    session.keypad_done = True
    _keypad_inflight[process_id] = session
    task = asyncio.current_task()
    if task is not None:
        _keypad_tasks[process_id] = task

    try:
        # Overall deadline (backend gives 70 s): on expiry the browser is closed and
        # the slot released below.
        async with asyncio.timeout(KEYPAD_DEADLINE_SECONDS):
            await _click_positions(session.page, cast("list[int]", positions), session.digests)
            await _click_valider(session.page)
            await _expect_securpass(session.page)
        # The human wait starts now, not at /initiate: a fresh TTL for /complete.
        session.created_at = time.monotonic()
        _keypad_inflight.pop(process_id, None)
        if not session.shutdown_requested:
            _pending[process_id] = session
        return {"processId": process_id, "status": "SECURPASS_PENDING"}
    except BaseException as exc:
        _keypad_inflight.pop(process_id, None)
        await _dispose(session)
        if not isinstance(exc, Exception):
            raise
        raise _to_login_error(exc) from None
    finally:
        _keypad_tasks.pop(process_id, None)


async def _click_positions(page: Any, positions: list[int], pinned: list[str]) -> None:
    keys = page.locator(KEY_SELECTOR)
    for position in positions:
        # The pad is re-read in full before EVERY click: any change (reshuffle, new
        # image, one key losing its image) stops here, with no further click.
        _, digests = await _read_pad(page)
        if digests != pinned:
            _log_keypad_refusal(
                keypad_table.KEY_COUNT, keypad_table.KEY_COUNT, digests, pinned
            )
            raise keypad_table.KeypadChanged()
        await keys.nth(position).click(timeout=STEP_TIMEOUT_SECONDS * 1000)


# --- /complete -----------------------------------------------------------------


async def complete(process_id: str) -> str:
    session = _pending.pop(process_id, None)  # single-use, atomic: no await before it
    if session is None:
        raise LoginError(410, "AUTH_ATTEMPT_EXPIRED")
    started = time.monotonic()
    human_wait_over = False
    try:
        if not session.keypad_done or session.created_at < started - PENDING_TTL_SECONDS:
            raise LoginError(410, "AUTH_ATTEMPT_EXPIRED")
        # Overall deadline (backend gives 170 s) around the human wait AND the
        # authorize capture/reload that follows it.
        async with asyncio.timeout(COMPLETE_DEADLINE_SECONDS) as deadline:
            await _wait_for_client_space(session.site, session.page)
            human_wait_over = True
            return await _collect_session_state(session, started + COMPLETE_DEADLINE_SECONDS)
    except Exception as exc:
        if isinstance(exc, TimeoutError) and deadline.expired() and not human_wait_over:
            raise LoginError(408, "APP_VALIDATION_TIMEOUT") from None
        raise _to_login_error(exc) from None
    finally:
        await _dispose(session)


async def _wait_for_client_space(site: Site, page: Any) -> None:
    deadline = time.monotonic() + COMPLETE_WAIT_SECONDS
    while True:
        url = page.url
        host, path = _url_parts(url)
        if _on_client_space(site, url):
            return
        if host == site.client_host and path.startswith(ERROR_PATH_PREFIX):
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
        if _is_site_domain(session.site, cookie.get("domain", ""))
    ]
    state = json.dumps({"cookies": cookies, "authorizeParams": params})
    try:
        replay.parse_session_state(state)
    except replay.InvalidSessionState:
        raise LoginError(502, "UPSTREAM_FORMAT_CHANGED") from None
    return state


def _is_site_domain(site: Site, domain: str) -> bool:
    bare = domain.lstrip(".").lower()
    return bare == site.domain or bare.endswith("." + site.domain)