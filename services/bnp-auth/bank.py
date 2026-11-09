"""BNP Paribas / Hello bank! web client, driven in Camoufox (Firefox).

The sidecar drives the bank's own login page like a person would: it types the customer number,
reads the ten digits written on the shuffled keypad buttons, hands those ten labels to the
member, and CLICKS the buttons the member picked (by DOM position). The page's own JavaScript
fills the hidden code field, the Akamai sensor and the ThreatMetrix id; this process never
builds or posts the login request itself. The password digits never reach this process: only
button positions do. No password or one-time code is stored; an SMS code is typed into the page
and dropped.

Verification status (docs/features/bnp-paribas.md, "Verification boundary"):
  * VERIFIED on the public pages with a real browser, nothing typed: the login hosts
    (PingFederate on `connexion.<brand domain>`), `form#identForm`, `pf.username`,
    `pf.pass.positions`, the 5x2 keypad of 10 text buttons, the one-POST submit, the
    `error_code` query parameter, the `"-10"` string answer of InfosClient when not signed in.
  * UNVERIFIED (only visible after a login): the second factor (Cle Digitale approval vs SMS
    code), the page and host it happens on, the session cookies after login, every JSON
    endpoint, path, verb and schema below, `codeRetour` -30, session lifetime.
Whenever the page does not look as expected the sidecar stops and says so (KEYPAD_CHANGED,
MFA_TYPE_UNSUPPORTED, UPSTREAM_FORMAT_CHANGED); it never guesses, and any request to a host outside
the brand's allow-list stops the login and logs the host name (host only).
"""

import asyncio
import base64
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any
from urllib.parse import parse_qs, urlsplit

from fastapi import HTTPException
from picsou_sidecar import PendingStore

import parsers

log = logging.getLogger("bnp-auth")


@dataclass(frozen=True)
class Brand:
    name: str
    site: str  # the bank's site host: its post-login API and the OIDC return URL live here
    login: str  # the PingFederate login host
    entry: str  # where the member would click "Se connecter"
    hosts: frozenset[str]
    cookie_domains: frozenset[str]

    @property
    def base(self) -> str:
        return f"https://{self.site}"


def _brand(name: str, site: str, login: str, entry: str, parent: str) -> Brand:
    hosts = frozenset({site, login, f"content.{login}"})
    return Brand(name, site, login, entry, hosts, hosts | {parent})


BRANDS = {
    "bnp": _brand("bnp", "mabanque.bnpparibas", "connexion.mabanque.bnpparibas",
                  "https://mabanque.bnpparibas/auth/login", "mabanque.bnpparibas"),
    "hellobank": _brand("hellobank", "www.hellobank.fr", "connexion.hellobank.fr",
                        "https://www.hellobank.fr/auth/login?requiredDAC=3", "hellobank.fr"),
}
# Exact hosts, no suffix rule. Everything the browser requests, document or not, must be one of the
# brand's three hosts, except the analytics hosts below, which are refused without stopping the login.
ALL_HOSTS = frozenset().union(*(brand.hosts for brand in BRANDS.values()))
BLOCKED_HOSTS = frozenset({"assets.adobedtm.com", "aa.online-metrix.net"})

PENDING_TTL_SECONDS = 300
MAX_PENDING = 4
STEP_SECONDS = 20
SETTLE_SECONDS = 30
STABLE_SECONDS = 3.0
APP_APPROVAL_SECONDS = 180
SMS_SETTLE_SECONDS = 45
MAX_REDIRECTS = 20
MAX_SESSION_STATE = 150_000
MIN_LOGIN_GAP = 2.0
POLL_SECONDS = 1.5
MAX_UNKNOWN_HOSTS = 5

LOGIN_PATH = "/auth/login"
AUTHORIZE_PATH = "/as/authorization.oauth2"
INFOS_CLIENT = "/serviceinfosclient-wspl/rpc/InfosClient?modeAppel=0"
# UNVERIFIED post-login endpoints (woob modules/bnp); see the module docstring.
ENDPOINTS = {
    "accounts": ("GET", "/udc-wspl/rest/getlstcpt"),
    "ibans": ("GET", "/rib-wspl/rpc/comptes"),
    "history": ("POST", "/rop2-wspl/rest/releveOp"),
    "synthesis": ("POST", "/pe-war/rpc/synthesis/get"),
    "portfolio": ("POST", "/pe-war/rpc/portfolioDetails/get"),
}

# Verified on the public login page.
FORM = "form#identForm"
USER_INPUT = 'input[name="pf.username"]'
GRID_INPUT = 'input[name="pf.pass.positions"]'
STEP_PASSWORD = "fieldset#stepPassword"
KEY_BUTTONS = "button.cas__btn--key"
SUBMIT_BUTTON = "#validLastStep"
CONTINUE_BUTTON_NAME = re.compile(r"^\s*Continuer\s*$", re.I)
KEY_COUNT = 10
COLUMNS = 5
POSITIONS = 6
# UNVERIFIED: how the second factor looks.
CODE_INPUT = (
    'input[autocomplete="one-time-code"], input[inputmode="numeric"], input[type="tel"], '
    'input[name*="otp" i], input[name*="code" i]'
)

_INSPECT_JS = """([form, user, grid, step, keys, submit]) => {
  const f = document.querySelector(form);
  const pad = document.querySelector(step);
  const buttons = pad ? [...pad.querySelectorAll(keys)] : [];
  const container = buttons.length ? buttons[0].parentElement : null;
  const columns = container ? getComputedStyle(container).gridTemplateColumns.split(' ').filter(Boolean).length : 0;
  return {
    form: !!f,
    action: f ? f.action : null,
    method: f ? (f.method || '').toLowerCase() : null,
    user: !!document.querySelector(user),
    grid: !!document.querySelector(grid),
    submit: !!document.querySelector(submit),
    pad: !!pad,
    keys: buttons.map(b => (b.textContent || '').trim()),
    columns,
  };
}"""

_PRESSED_JS = """([grid, submit]) => {
  const input = document.querySelector(grid);
  const button = document.querySelector(submit);
  return {value: input ? input.value : null, enabled: !!button && !button.disabled};
}"""

_FETCH_JS = """async ([method, path, body]) => {
  const init = {method, credentials: 'same-origin', redirect: 'error', headers: {'Accept': 'application/json'}};
  if (body !== null) { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  const response = await fetch(path, init);
  return {status: response.status, text: await response.text()};
}"""


def fail(status: int, detail: str) -> HTTPException:
    return HTTPException(status_code=status, detail=detail)


# ─── allow-list ──────────────────────────────────────────────────────────────


def host_of(url: Any) -> str | None:
    try:
        return urlsplit(url).hostname if isinstance(url, str) else None
    except ValueError:
        return None


def is_allowed_url(url: Any, hosts: frozenset[str] = ALL_HOSTS) -> bool:
    """https only, an exact brand host, the default port, no credentials in the URL."""
    if not isinstance(url, str):
        return False
    if url.startswith("data:") or url == "about:blank":
        return True
    try:
        parts = urlsplit(url)
        return (
            parts.scheme == "https"
            and (parts.hostname or "") in hosts
            and parts.username is None
            and parts.password is None
            and parts.port in (None, 443)
        )
    except ValueError:
        return False


class Guard:
    """Request filter for one browser context.

    - an allowed URL goes through;
    - a known analytics host (BLOCKED_HOSTS) is refused and ignored;
    - any other host is refused AND stops the attempt (`tainted`), its host name is remembered and
      logged, so an unexpected redirect (the second-factor page is on an unobserved host) is reported
      instead of silently followed.
    Playwright's Firefox routing may not report every redirect hop to a route handler, so every
    finished request's redirect chain is checked again (`observe`); one off-list hop taints the
    attempt and the caller drops the whole browser.
    """

    def __init__(self, hosts: frozenset[str]) -> None:
        self.hosts = hosts
        self.tainted = False
        self.blocked = 0
        self.unknown_hosts: list[str] = []

    def _unknown(self, url: Any) -> None:
        self.tainted = True
        host = host_of(url) or "unparsable"
        if host not in self.unknown_hosts and len(self.unknown_hosts) < MAX_UNKNOWN_HOSTS:
            self.unknown_hosts.append(host)
            # Host name only: never the URL, its query or any page content.
            log.warning("BNP browser requested a host outside the allow-list: %s", host[:100])

    async def route(self, route: Any) -> None:
        url = route.request.url
        if is_allowed_url(url, self.hosts):
            await route.continue_()
            return
        self.blocked += 1
        if host_of(url) not in BLOCKED_HOSTS:
            self._unknown(url)
        await route.abort()

    def observe(self, request: Any) -> None:
        hop = request
        hops = 0
        while hop is not None:
            hops += 1
            if hops > MAX_REDIRECTS:
                self.tainted = True
                return
            if not is_allowed_url(hop.url, self.hosts) and host_of(hop.url) not in BLOCKED_HOSTS:
                self._unknown(hop.url)
                return
            hop = hop.redirected_from


# ─── browser plumbing ────────────────────────────────────────────────────────


async def _close(state: dict[str, Any]) -> None:
    for key in ("context", "browser"):
        resource = state.get(key)
        if resource is not None:
            try:
                await asyncio.wait_for(resource.close(), timeout=10)
            except Exception:
                log.warning("BNP browser resource cleanup failed")
    camoufox = state.get("camoufox")
    if camoufox is not None:
        try:
            await asyncio.wait_for(camoufox.__aexit__(None, None, None), timeout=10)
        except Exception:
            log.warning("BNP browser shutdown failed")


async def launch(brand: Brand) -> dict[str, Any]:
    """A guarded Camoufox page on the brand's hosts. Imported late: tests never need the browser."""
    from camoufox.async_api import AsyncCamoufox

    state: dict[str, Any] = {"brand": brand, "created_at": time.time()}
    try:
        # Headful under the container's Xvfb: the edge (Akamai, ThreatMetrix) profiles the browser.
        # WebRTC stays off so the ThreatMetrix TURN beacon (aa.online-metrix.net) cannot go around
        # the host allow-list. UNVERIFIED: whether the bank accepts a login without that beacon.
        camoufox = AsyncCamoufox(headless=False, humanize=True, os="linux", locale="fr-FR", block_webrtc=True)
        state["camoufox"] = camoufox
        state["browser"] = await camoufox.__aenter__()
        context = await state["browser"].new_context(timezone_id="Europe/Paris", service_workers="block")
        state["context"] = context
        guard = Guard(brand.hosts)
        state["guard"] = guard
        await context.route("**/*", guard.route)
        await context.route_web_socket("**/*", lambda ws: asyncio.ensure_future(ws.close()))
        context.on("requestfinished", guard.observe)
        context.on("requestfailed", guard.observe)
        state["page"] = await context.new_page()
        return state
    except BaseException:
        await _close(state)
        raise


def require_clean(state: dict[str, Any]) -> None:
    """The attempt stops as soon as the browser asked for a host outside the allow-list."""
    if state["guard"].tainted:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED")


def on_site(url: str, brand: Brand) -> bool:
    """On the bank's site host, past the login redirect."""
    parts = urlsplit(url)
    return (parts.hostname or "") == brand.site and not parts.path.startswith(LOGIN_PATH)


def on_login(url: str, brand: Brand) -> bool:
    parts = urlsplit(url)
    return (parts.hostname or "") == brand.login and parts.path == AUTHORIZE_PATH


# Verified live: the login page reads `error_code` from its own URL and shows the catalogue message
# `app.identification.erreur.<id>`. Meanings below come from the ids' public catalogue titles; the
# counter behaviour of a lock is UNVERIFIED.
ERROR_IDS = {
    **dict.fromkeys(("3", "4", "202"), (429, "RATE_LIMITED")),
    **dict.fromkeys(("6", "201", "203", "210", "211", "212"), (401, "INVALID_CREDENTIALS")),
    # A customer number of the other brand (BNP number on Hello bank! and the reverse).
    **dict.fromkeys(("21501", "21521", "21531", "21510", "21512", "21513"), (401, "INVALID_CREDENTIALS")),
    **dict.fromkeys(("7", "50", "213"), (409, "ACTION_REQUIRED")),
    "1002": (410, "AUTH_ATTEMPT_EXPIRED"),
    **dict.fromkeys(("207", "300", "1001", "1003"), (502, "UPSTREAM_UNAVAILABLE")),
}
ERROR_NAMES = (
    (("Delay", "Locked"), (429, "RATE_LIMITED")),
    (("ErrorSecret", "SecretError", "ClientNotFound", "FailedLogin", "InvalidGrid", "SdoInvalidPwd",
      "ZosConnectGetIKPI", "CasInvalidCredential"), (401, "INVALID_CREDENTIALS")),
    (("ExpiredTmpPwd", "ExpiredClientPwd", "ChangePwd", "MailPwdReset", "AccountClosed"), (409, "ACTION_REQUIRED")),
    (("TechnicalException", "CasTechnical"), (502, "UPSTREAM_UNAVAILABLE")),
)


def login_failure(url: str) -> HTTPException | None:
    """The failure a login error redirect (`error_code=<id>`) stands for, None when the URL is not one."""
    values = parse_qs(urlsplit(url).query).get("error_code", [])
    if not values:
        return None
    value = values[0].strip()
    if re.fullmatch(r"-?\d{1,6}", value):
        status, code = ERROR_IDS.get(value, (502, "UPSTREAM_FORMAT_CHANGED"))
    else:
        status, code = next(
            (answer for needles, answer in ERROR_NAMES if any(needle in value for needle in needles)),
            (502, "UPSTREAM_FORMAT_CHANGED"),
        )
    return fail(status, code)


async def fetch_json(page: Any, key: str, body: dict | None = None) -> Any:
    """One of the fixed read endpoints, requested by the page itself (same origin, its cookies)."""
    method, path = ENDPOINTS[key] if key in ENDPOINTS else ("GET", key)
    result = await page.evaluate(_FETCH_JS, [method, path, body])
    if not isinstance(result, dict) or not isinstance(result.get("text"), str) or len(result["text"]) > 8_000_000:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED")
    if result.get("status") in (401, 403):
        raise fail(401, "SESSION_EXPIRED")
    if result.get("status") != 200:
        raise fail(502, "UPSTREAM_UNAVAILABLE")
    return parsers.loads(result["text"])


def session_alive(info: Any) -> bool:
    """InfosClient answers `codeRetour "-10"` ("user not connected") when signed out, as a string."""
    if not isinstance(info, dict):
        return False
    try:
        code = int(str(info.get("codeRetour")).strip())
    except ValueError:
        code = None
    if code in parsers.SESSION_EXPIRED_CODES:
        return False
    return code == 0 or str(info.get("message")) == "OK"


async def probe_session(page: Any, brand: Brand) -> bool | None:
    """True/False once the site answers, None when it cannot be asked yet (mid-redirect)."""
    if not on_site(page.url, brand):
        return None
    try:
        return session_alive(await fetch_json(page, INFOS_CLIENT))
    except HTTPException:
        return False
    except Exception:
        return None


def classify_second_factor(code_inputs: int, page_text: str) -> str | None:
    """UNVERIFIED heuristic. 'sms' when the page asks for a code, 'app' when it only waits for an
    approval in the bank's app, None (stop) when neither is recognisable."""
    if code_inputs == 1:
        return "sms"
    if code_inputs == 0 and re.search(r"cl[ée] digitale|application|smartphone|mobile", page_text, re.I):
        return "app"
    return None


# ─── the client ──────────────────────────────────────────────────────────────


class BrowserBankClient:
    def __init__(self) -> None:
        self.pending = PendingStore(dispose=_close, log=log, ttl_seconds=PENDING_TTL_SECONDS, max_size=MAX_PENDING)
        self._last_login = 0.0

    # -- login ---------------------------------------------------------------

    async def initiate(self, customer_id: str, brand_name: str) -> dict[str, Any]:
        brand = BRANDS.get(brand_name)
        if brand is None:
            raise fail(400, "INVALID_REQUEST")
        await self.pending.expire()
        if len(self.pending) >= MAX_PENDING:
            raise fail(503, "UPSTREAM_UNAVAILABLE")
        if time.monotonic() - self._last_login < MIN_LOGIN_GAP:
            raise fail(429, "RATE_LIMITED")
        self._last_login = time.monotonic()
        state = await launch(brand)
        parked = False
        try:
            page = state["page"]
            try:
                await page.goto(brand.entry, wait_until="domcontentloaded", timeout=45_000)
            except Exception:
                require_clean(state)
                raise fail(502, "UPSTREAM_UNAVAILABLE") from None
            require_clean(state)
            if not on_login(page.url, brand):
                raise fail(409, "KEYPAD_CHANGED")
            keys = await self._read_keypad(page, brand, customer_id, state)
            process_id = uuid.uuid4().hex
            state.update(stage="keypad", process_id=process_id, submit_url=page.url)
            await self.pending.put_or_raise(process_id, state)
            parked = True
            return {
                "processId": process_id,
                "keypad": {"keys": keys, "columns": COLUMNS},
                "expiresInSeconds": PENDING_TTL_SECONDS,
            }
        finally:
            if not parked:
                await _close(state)

    async def _read_keypad(self, page: Any, brand: Brand, customer_id: str, state: dict[str, Any]) -> list[str]:
        """Type the customer number, open the keypad step, and read the ten digits on the buttons."""
        found = await page.evaluate(_INSPECT_JS, [FORM, USER_INPUT, GRID_INPUT, STEP_PASSWORD, KEY_BUTTONS, SUBMIT_BUTTON])
        action = urlsplit(str(found.get("action") or ""))
        if not (
            found.get("form") and found.get("user") and found.get("grid") and found.get("submit") and found.get("pad")
            and found.get("method") == "post"
            # The form posts back to the PingFederate login host, never elsewhere.
            and (action.hostname or "") == brand.login and action.scheme == "https"
            and action.path.startswith("/as/") and action.path.endswith("/authorization.ping")
        ):
            raise fail(409, "KEYPAD_CHANGED")
        try:
            await page.fill(USER_INPUT, customer_id, timeout=10_000)
            await page.get_by_role("button", name=CONTINUE_BUTTON_NAME).click(timeout=10_000)
            await page.wait_for_selector(f"{STEP_PASSWORD}:not(.hidden) {KEY_BUTTONS}", state="visible", timeout=STEP_SECONDS * 1000)
        except Exception:
            require_clean(state)
            raise fail(409, "KEYPAD_CHANGED") from None
        require_clean(state)
        found = await page.evaluate(_INSPECT_JS, [FORM, USER_INPUT, GRID_INPUT, STEP_PASSWORD, KEY_BUTTONS, SUBMIT_BUTTON])
        keys = found.get("keys")
        if (
            not isinstance(keys, list)
            or len(keys) != KEY_COUNT
            or found.get("columns") != COLUMNS
            or any(not isinstance(key, str) or not re.fullmatch(r"[0-9]", key) for key in keys)
            or len(set(keys)) != KEY_COUNT
        ):
            raise fail(409, "KEYPAD_CHANGED")
        return keys

    async def keypad(self, process_id: str, positions: list[int]) -> str:
        state = await self.pending.take(process_id)
        if state is None or state.get("stage") != "keypad":
            if state is not None:
                await _close(state)
            raise fail(410, "AUTH_ATTEMPT_EXPIRED")
        brand: Brand = state["brand"]
        page = state["page"]
        keep = False
        try:
            require_clean(state)
            if len(positions) != POSITIONS or any(not 0 <= position < KEY_COUNT for position in positions):
                raise fail(400, "INVALID_REQUEST")
            # The member's click order, replayed as real clicks on the real buttons.
            keys = page.locator(KEY_BUTTONS)
            try:
                for position in positions:
                    await keys.nth(position).click(timeout=10_000)
                pressed = await page.evaluate(_PRESSED_JS, [GRID_INPUT, SUBMIT_BUTTON])
            except Exception:
                require_clean(state)
                raise fail(409, "KEYPAD_CHANGED") from None
            # The page's own field must hold exactly the clicked positions (verified live: one
            # character per press, the 0-based DOM index) and the submit must be enabled.
            if pressed.get("value") != "".join(str(position) for position in positions) or not pressed.get("enabled"):
                raise fail(409, "KEYPAD_CHANGED")
            # One submission, never retried: a wrong keypad counts against the bank's lockout.
            try:
                await page.click(SUBMIT_BUTTON, timeout=10_000)
            except Exception:
                require_clean(state)
                raise fail(409, "KEYPAD_CHANGED") from None
            outcome = await self._settle(page, brand, state)
            if isinstance(outcome, HTTPException):
                raise outcome
            if outcome == "logged_in":
                state.update(stage="mfa", kind="none")
                status = "APPROVAL_PENDING"
            else:
                kind = await self._second_factor_kind(page)
                if kind is None:
                    raise fail(422, "MFA_TYPE_UNSUPPORTED")
                state.update(stage="mfa", kind=kind)
                status = "CODE_REQUIRED" if kind == "sms" else "APPROVAL_PENDING"
            state["created_at"] = time.time()
            await self.pending.repark(process_id, state)
            keep = True
            return status
        finally:
            if not keep:
                await _close(state)

    async def _settle(self, page: Any, brand: Brand, state: dict[str, Any]) -> Any:
        """After the single submit: 'logged_in', 'second_factor', or an HTTPException to raise."""
        deadline = time.monotonic() + SETTLE_SECONDS
        last_url, changed_at = page.url, time.monotonic()
        while time.monotonic() < deadline:
            await asyncio.sleep(0.5)
            if state["guard"].tainted:
                return fail(502, "UPSTREAM_FORMAT_CHANGED")
            url = page.url
            if not is_allowed_url(url, brand.hosts):
                return fail(502, "UPSTREAM_FORMAT_CHANGED")
            failure = login_failure(url)
            if failure is not None:
                return failure
            if url != last_url:
                last_url, changed_at = url, time.monotonic()
            if await probe_session(page, brand):
                return "logged_in"
            # Still on the page the form posted from: nothing happened yet.
            if url == state.get("submit_url"):
                continue
            # A different page that stayed put without a session: the second-factor step (UNVERIFIED).
            if time.monotonic() - changed_at >= STABLE_SECONDS:
                return "second_factor"
        return fail(409, "KEYPAD_CHANGED")

    async def _second_factor_kind(self, page: Any) -> str | None:
        try:
            visible = 0
            for handle in await page.query_selector_all(CODE_INPUT):
                if await handle.is_visible():
                    visible += 1
            text = await page.inner_text("body", timeout=5_000)
        except Exception:
            return None
        return classify_second_factor(visible, text)

    async def _wait_logged_in(self, page: Any, brand: Brand, state: dict[str, Any], seconds: float) -> bool:
        deadline = time.monotonic() + seconds
        while True:
            if state["guard"].tainted:
                raise fail(502, "UPSTREAM_FORMAT_CHANGED")
            failure = login_failure(page.url)
            if failure is not None:
                raise failure
            if await probe_session(page, brand):
                return True
            if time.monotonic() >= deadline:
                return False
            await asyncio.sleep(POLL_SECONDS)

    async def complete(self, process_id: str, code: str | None) -> str:
        state = await self.pending.take(process_id)
        if state is None or state.get("stage") != "mfa":
            if state is not None:
                await _close(state)
            raise fail(410, "AUTH_ATTEMPT_EXPIRED")
        brand: Brand = state["brand"]
        page = state["page"]
        keep = False
        try:
            kind = state["kind"]
            if kind == "sms":
                if not code:
                    raise fail(400, "INVALID_OTP")
                try:
                    field = page.locator(CODE_INPUT).first
                    await field.fill(code, timeout=10_000)
                    await field.press("Enter")
                except Exception:
                    require_clean(state)
                    raise fail(409, "MFA_TYPE_UNSUPPORTED") from None
                if not await self._wait_logged_in(page, brand, state, SMS_SETTLE_SECONDS):
                    # Still on the code page: the member may retype once; the backend counts tries.
                    state["created_at"] = time.time()
                    await self.pending.repark(process_id, state)
                    keep = True
                    raise fail(401, "INVALID_OTP")
            else:
                if code:
                    raise fail(400, "INVALID_REQUEST")
                wait = 0 if kind == "none" else APP_APPROVAL_SECONDS
                if not await self._wait_logged_in(page, brand, state, wait):
                    raise fail(408, "APP_VALIDATION_TIMEOUT") if kind == "app" else fail(502, "UPSTREAM_FORMAT_CHANGED")
            require_clean(state)
            return encode_session(brand, await state["context"].cookies())
        finally:
            if not keep:
                await _close(state)

    # -- snapshot --------------------------------------------------------------

    async def accounts(self, session_state: str) -> dict[str, Any]:
        brand, cookies = decode_session(session_state)
        state = await launch(brand)
        try:
            page = state["page"]
            await state["context"].add_cookies(cookies)
            try:
                # The site's home: any page of the site host gives the API its origin and cookies.
                await page.goto(brand.base + "/", wait_until="domcontentloaded", timeout=45_000)
            except Exception:
                require_clean(state)
                raise fail(502, "UPSTREAM_UNAVAILABLE") from None
            require_clean(state)
            try:
                if not on_site(page.url, brand) or not session_alive(await fetch_json(page, INFOS_CLIENT)):
                    raise fail(401, "SESSION_EXPIRED")
                snapshot = await self._snapshot(page)
            except parsers.SessionExpired:
                raise fail(401, "SESSION_EXPIRED") from None
            except parsers.ParseError as exc:
                raise fail(502, exc.code) from None
            require_clean(state)
            return {
                "brand": brand.name,
                "accounts": snapshot[0],
                "unsupported": snapshot[1],
                "sessionState": encode_session(brand, await state["context"].cookies()),
            }
        finally:
            await _close(state)

    async def _snapshot(self, page: Any) -> tuple[list[dict], list[dict]]:
        ibans = parsers.parse_ibans(parsers.envelope(await fetch_json(page, "ibans")))
        rows, unsupported = parsers.parse_accounts(parsers.envelope(await fetch_json(page, "accounts")), ibans)
        end = date.today()
        window = {"startDate": (end - timedelta(days=365)).strftime("%d%m%Y"), "endDate": end.strftime("%d%m%Y")}
        histories: dict[str, Any] = {}
        portfolios: dict[str, Any] = {}
        synthesis: list[dict] = []
        securities = [row for row in rows if row["kind"] in ("PEA", "COMPTE_TITRES") and not row["cash"]]
        if securities:
            synthesis = parsers.parse_synthesis(parsers.envelope(await fetch_json(page, "synthesis", {})))
        for row in rows:
            if row["cash"]:
                continue
            if row["kind"] in ("PEA", "COMPTE_TITRES"):
                match = parsers.match_securities(row, synthesis)
                portfolios[row["externalId"]] = None
                if match is not None:
                    portfolios[row["externalId"]] = await self._optional(
                        page, "portfolio", {"securityAccountNumber": match["number"]}, parsers.parse_positions
                    )
            elif row["kind"] not in ("ASSURANCE_VIE", "PER", "LOAN"):
                body = {"ibanCrypte": row["key"], "pastOrPending": 1, "triAV": 0, **window}
                histories[row["externalId"]] = await self._optional(page, "history", body, parsers.parse_operations)
        return parsers.build_accounts(rows, histories, synthesis, portfolios), unsupported

    async def _optional(self, page: Any, key: str, body: dict, parse) -> Any:
        """Parsed data, or None when the bank answered something unusable (the account turns incomplete).
        A dead session still ends the whole snapshot."""
        try:
            return parse(parsers.envelope(await fetch_json(page, key, body)))
        except parsers.SessionExpired:
            raise
        except (parsers.ParseError, HTTPException) as exc:
            if isinstance(exc, HTTPException) and exc.detail == "SESSION_EXPIRED":
                raise
            return None


# ─── session state ───────────────────────────────────────────────────────────

COOKIE_KEYS = ("name", "value", "domain", "path", "expires", "httpOnly", "secure", "sameSite")


def cookie_in_scope(cookie: dict, brand: Brand) -> bool:
    return str(cookie.get("domain") or "").lstrip(".").lower() in brand.cookie_domains


def encode_session(brand: Brand, cookies: list[dict]) -> str:
    """Only the cookies of the brand's own domains, with the few fields needed to restore them."""
    kept = [{key: c[key] for key in COOKIE_KEYS if key in c} for c in cookies if cookie_in_scope(c, brand)]
    text = json.dumps({"v": 2, "brand": brand.name, "cookies": kept}, separators=(",", ":"))
    if not kept or len(text) > MAX_SESSION_STATE:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED")
    return base64.b64encode(text.encode()).decode("ascii")


def decode_session(session_state: str) -> tuple[Brand, list[dict]]:
    try:
        data = json.loads(base64.b64decode(session_state, validate=True))
        brand = BRANDS[data["brand"]]
        cookies = data["cookies"]
        if data["v"] != 2 or not isinstance(cookies, list) or not 0 < len(cookies) <= 100:
            raise ValueError
        out = []
        for cookie in cookies:
            if not isinstance(cookie.get("name"), str) or not isinstance(cookie.get("value"), str):
                raise ValueError
            if not cookie_in_scope(cookie, brand):
                raise ValueError
            out.append({key: cookie[key] for key in COOKIE_KEYS if key in cookie})
        return brand, out
    except (ValueError, KeyError, TypeError, AttributeError):
        raise fail(400, "INVALID_SESSION_STATE") from None
