"""Read-only American Express France authentication and accounts sidecar.

Camoufox (stealth Firefox) drives login and the SMS/e-mail one-time-code
second factor AMEX's `two-step-verification/verify` page offers (no app
push there -- confirmed live, see `_wait_for_login_outcome`). Once
authenticated, the session's cookies are handed to httpx to call AMEX's
internal servicing APIs directly -- lighter than keeping the browser alive
for every account read. Credentials, one-time codes, cookies and raw
financial responses are never logged.

Why Camoufox (stealth Firefox) and not plain Playwright Chromium:
  Live investigation with fake credentials found the #loginSubmit click
  firing correctly and AMEX's own login POST
  (myca/logon/emea/action/login) leaving a plain headless Playwright
  Chromium browser, but Akamai Bot Manager answering every attempt with an
  unheadered 403 that Chrome's CORS layer surfaces as net::ERR_FAILED --
  the page never sees a response at all, headless or not, UA spoofing and
  navigator.webdriver patches included. Revolut's sidecar hit the same
  class of Akamai/anti-bot block and fixed it by swapping in Camoufox, a
  real Firefox engine with engine-level fingerprint spoofing (see
  services/revolut-auth/main.py); this sidecar follows the same fix rather
  than re-inventing Chromium-specific stealth flags that Akamai already
  defeats.
"""

import asyncio
import hmac
import json
import logging
import os
import re
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal, InvalidOperation
from typing import Any, Literal

import httpx
from camoufox.async_api import AsyncCamoufox
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("amex-auth")

LOGIN_URL = "https://www.americanexpress.com/fr-fr/account/login/"
API_BASE_URL = "https://global.americanexpress.com"
BALANCES_PATH = (
    "/api/servicing/v1/financials/balances"
    "?extended_details=deferred,non_deferred,pay_in_full,pay_over_time,early_pay"
)
TRANSACTIONS_PATH = "/api/servicing/v1/financials/transactions"
TRANSACTION_PAGE_SIZE = 100
MEMBER_PATH = "/api/servicing/v1/member"

# AMEX's servicing APIs (balances/transactions) 400 without this header
# identifying which card the SPA is asking about -- see _account_token below
# for how it's obtained after login.
ACCOUNT_TOKEN_HEADER = "account_token"
# Matches the Camoufox (real Firefox) fingerprint used for login, so the
# httpx follow-up calls look like the same browser session to AMEX.
FIREFOX_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
)
ACCOUNT_TOKEN_CAPTURE_TIMEOUT_SECONDS = 10.0

USERID_FIELD_SELECTOR = "#eliloUserID"
PASSWORD_FIELD_SELECTOR = "#eliloPassword"
SUBMIT_BUTTON_SELECTOR = "#loginSubmit"
LOGIN_FORM_TIMEOUT_MS = 15_000
LOGIN_RESULT_TIMEOUT_SECONDS = 30

# Verify page (two-step-verification/verify): pick the SMS/e-mail option,
# click through any intermediate confirm step, then find the OTP input.
# Each wait stays well under the Java adapter's 45s /initiate budget.
VERIFY_OPTION_TIMEOUT_MS = 15_000
VERIFY_CONTINUE_TIMEOUT_MS = 5_000
VERIFY_OTP_INPUT_TIMEOUT_MS = 15_000
OPTION_BUTTON_SELECTOR = "button[data-testid=option-button]"
OPTION_HEADING_SELECTOR = "[data-testid=select-button-heading]"
OTP_INPUT_SELECTORS = (
    "input[autocomplete='one-time-code']",
    "input[type=tel]",
    "input[inputmode=numeric]",
)
OTP_SUBMIT_TEXT_PATTERN = re.compile(
    r"v[ée]rifier|valider|continuer|confirmer", re.IGNORECASE
)
# Buttons that look like a submit but aren't -- must never be clicked when
# looking for the OTP submit action.
OTP_SUBMIT_EXCLUDE_PATTERN = re.compile(
    r"renvoyer|resend|annuler|cancel|retour|back", re.IGNORECASE
)
# Method -> substring the option heading must contain, matched case-insensitively
# against the confirmed French copy ("Code à usage unique (SMS)" / "... (e-mail)").
METHOD_HEADING_SUBSTRINGS = {"sms": "sms", "email": "e-mail"}

# Post-OTP "Appareil de confiance" (trusted device) interstitial: AMEX shows
# this after accepting the code, asking to remember the device. We never tick
# the checkbox or name the device (browser is ephemeral) -- just click
# through with "Continuer" so the poll loop can reach the dashboard.
TRUST_DEVICE_CHECKBOX_SELECTOR = "[data-testid=trust-device]"
TRUST_DEVICE_CONTINUE_PATTERN = re.compile(r"^\s*continuer\s*$", re.IGNORECASE)

OTP_POLL_SECONDS = 1.0
# Submitting an OTP is a single round-trip, not a human approving a push on
# their phone -- much shorter than the old app-push wait, but still generous
# for a slow page render.
OTP_RESULT_TIMEOUT_SECONDS = 30
REQUEST_TIMEOUT_SECONDS = 30.0

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
# Every pending SafeKey approval pins a whole browser. Cheap next to the
# provider's own throttle, but this still bounds the service in aggregate.
MAX_PENDING = 16

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


# ─── Sidecar shared-secret enforcement ─────────────────────────────────
# The backend sends X-Picsou-Sidecar-Key on every call and maps a 401 whose
# WWW-Authenticate contains "Picsou-Sidecar-Key" back to a SidecarAuthenticationException.
# Each *-auth sidecar enforces that same secret so a container that can reach port 8001
# cannot drive the sidecar or replay a captured sessionState. See
# docs/decisions/2026-09-17-sidecar-shared-secret-channel.md.
SIDECAR_KEY_HEADER = "X-Picsou-Sidecar-Key"
SIDECAR_AUTH_CHALLENGE = "Picsou-Sidecar-Key"
_SIDECAR_KEY: str | None = None  # set in lifespan; None until then


def _get_sidecar_key() -> str:
    """Return the sidecar key, loading it lazily on first use so `python main.py`
    self-check (which never starts the server) still runs without the env var."""
    global _SIDECAR_KEY
    if _SIDECAR_KEY is None:
        key = os.environ.get("APP_SIDECAR_API_KEY")
        if not key or not key.strip():
            raise RuntimeError(
                "APP_SIDECAR_API_KEY is required: it is the shared secret that authenticates Picsou "
                "to its connector sidecars, which carry your bank credentials. Generate one "
                "with: openssl rand -base64 32 -- and set it as APP_SIDECAR_API_KEY in both the "
                "backend and every *-auth service."
            )
        _SIDECAR_KEY = key.strip()
    return _SIDECAR_KEY


# ─── Lifecycle ──────────────────────────────────────────────────────────────


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        try:
            await _cleanup_expired()
        except Exception:
            # One bad sweep must not end the task: without it, expired pending
            # states keep their browser until shutdown and MAX_PENDING
            # eventually rejects every new login.
            log.warning("AMEX pending sweep failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    _get_sidecar_key()  # fail fast at startup when the shared key is missing
    sweeper = asyncio.create_task(_pending_sweeper())
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await _close_all_pending()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            log.info(
                "AMEX request completed (path=%s; duration=%.2fs)",
                request.url.path,
                time.monotonic() - started_at,
            )


@app.middleware("http")
async def enforce_sidecar_key(request: Request, call_next):
    """Reject every request except /health unless it carries the shared sidecar key.

    The backend authenticates to the sidecar with X-Picsou-Sidecar-Key; this sidecar
    answers 401 with a WWW-Authenticate challenge of "Picsou-Sidecar-Key" when the
    key is missing or wrong, so the backend's SidecarWebClientFactory can turn the
    rejection into a SidecarAuthenticationException instead of a bank credential error.
    The header value is never logged.
    """
    if request.url.path == "/health":
        return await call_next(request)
    expected = _get_sidecar_key().encode("utf-8")
    provided = request.headers.get(SIDECAR_KEY_HEADER, "").encode("utf-8")
    if not provided or not hmac.compare_digest(expected, provided):
        return JSONResponse(
            status_code=401,
            content={"detail": "invalid sidecar key"},
            headers={"WWW-Authenticate": SIDECAR_AUTH_CHALLENGE},
        )
    return await call_next(request)


# ─── Contract ───────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    userId: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)
    # AMEX's verify page offers one one-time-code method per channel, no app
    # push (see module docstring) -- the caller picks which channel AMEX
    # should send the code to.
    method: Literal["sms", "email"]


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    otp: str = Field(min_length=4, max_length=10, pattern=r"^\d+$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)
    history: bool = False


class DirectDebitPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    iban: str | None = None
    status: str | None = None
    nextDebitDate: str | None = None


class TransactionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    date: str
    description: str = Field(min_length=1, max_length=255)
    amount: Decimal
    status: str | None = None
    category: str | None = None


class AccountPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["CREDIT_CARD"] = "CREDIT_CARD"
    balanceEur: Decimal
    statementBalance: Decimal | None = None
    amountDue: Decimal | None = None
    dueDate: str | None = None
    rewardPoints: int | None = None
    minimumPayment: Decimal | None = None
    directDebit: DirectDebitPayload | None = None
    transactions: list[TransactionPayload] = Field(default_factory=list)
    # False when the pending feed failed: the backend must then keep its stored pending rows.
    pendingComplete: bool = False
    snapshotComplete: Literal[True]


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": "INVALID_DATA"})


# ─── Pending second factors ─────────────────────────────────────────────────


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    camoufox: AsyncCamoufox | None,
) -> None:
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, close_method)(),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("AMEX browser resource cleanup failed")
    if camoufox is not None:
        try:
            await asyncio.wait_for(
                camoufox.__aexit__(None, None, None),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("AMEX clean-up wait failed")


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    await _close_resources(state.get("context"), state.get("browser"), state.get("camoufox"))


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, state in _pending.items() if state["created_at"] < cutoff]
        states = [_pending.pop(pid) for pid in expired]
    for state in states:
        await _dispose_pending_state(state)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _store_pending(process_id: str, state: dict[str, Any]) -> None:
    async with _pending_lock:
        if len(_pending) >= MAX_PENDING:
            raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE")
        _pending[process_id] = state


# ─── Login / SafeKey ────────────────────────────────────────────────────────


async def _dashboard_reached(page: Page) -> bool:
    return "/myca/" in page.url or "/dashboard" in page.url


# The exact copy AMEX renders on the login page for rejected credentials,
# confirmed live against production with fake credentials (see
# _wait_for_login_outcome's docstring for how it was captured). Matched as
# a substring against visible page text rather than a build-hashed
# selector, which AMEX's own front end doesn't expose reliably either.
INVALID_CREDENTIALS_TEXT = "code utilisateur ou le mot de passe est erron"


async def _invalid_credentials_visible(page: Page) -> bool:
    try:
        alerts = await page.query_selector_all(
            "[role=alert], .alert, [class*=error], [data-testid*=error]"
        )
    except PlaywrightError:
        return False
    for el in alerts:
        try:
            text = (await el.inner_text()).strip().lower()
        except PlaywrightError:
            continue
        if INVALID_CREDENTIALS_TEXT in text:
            return True
    return False


async def _otp_challenge_visible(page: Page) -> bool:
    """True once AMEX has moved off the login form onto the verify page.

    AMEX renders the SMS/e-mail one-time-code challenge without the login
    fields still on screen, so their absence together with a URL that has
    left the login page is the signal used here, rather than a
    build-hashed selector.
    """
    if await _dashboard_reached(page):
        return False
    if LOGIN_URL.rstrip("/") in page.url:
        try:
            return await page.locator(USERID_FIELD_SELECTOR).count() == 0
        except PlaywrightError:
            return False
    return True


async def _wait_for_login_outcome(page: Page, timeout_seconds: int) -> str:
    """Poll for the known post-submit outcomes.

    Returns "success", "verify" (the SMS/e-mail method-selection challenge
    appeared), "invalid" (AMEX's own bad-credentials message rendered on
    the login page), or "timeout" (none of the above within the deadline).

    A "timeout" is NOT proof of bad credentials: live investigation with
    fake credentials found the #loginSubmit click firing correctly and
    AMEX's own login POST (myca/logon/emea/action/login) leaving the
    browser, but Akamai Bot Manager answering it with an unheadered 403
    that Chrome's CORS layer reports to Playwright as net::ERR_FAILED with
    no response ever reaching the page -- the exact same shape a slow or
    blocked network produces. That fingerprint block was fixed by
    switching the browser engine to Camoufox (stealth Firefox, see the
    module docstring); once fixed, the real login POST returns 200 and
    AMEX renders "Le code utilisateur ou le mot de passe est erroné.
    Veuillez essayer de nouveau." on the page -- INVALID_CREDENTIALS_TEXT
    above is that confirmed string. A residual "timeout" (e.g. a genuine
    AMEX outage or a future markup change) is still reported as an
    upstream problem, never as rejected credentials.
    """
    for _ in range(timeout_seconds * 4):
        if await _dashboard_reached(page):
            return "success"
        if await _invalid_credentials_visible(page):
            return "invalid"
        if await _otp_challenge_visible(page):
            return "verify"
        await asyncio.sleep(0.25)
    return "timeout"


def _safe_error_type(exc: BaseException) -> str:
    return type(exc).__name__


def _mask_digits(s: str) -> str:
    return re.sub(r"\d", "#", s or "")


async def _log_verify_page_diagnostics(page: Page) -> None:
    try:
        elements = await page.query_selector_all(
            "button, [role=button], input:not([type=hidden]), h1, h2, h3"
        )
        log.info("AMEX verify page diagnostic elements_present=%s", bool(elements))
    except PlaywrightError as exc:
        log.warning("AMEX verify page diagnostic failed error_type=%s", _safe_error_type(exc))


async def _select_otp_method(page: Page, method: str) -> None:
    """Clicks the SMS/e-mail option on the verify page and, if AMEX shows
    an intermediate confirm step, clicks through it too.

    Raises HTTPException(502, UPSTREAM_FORMAT_CHANGED) with a diagnostic
    dump if the expected option button never appears.
    """
    needle = METHOD_HEADING_SUBSTRINGS[method]
    options = page.locator(OPTION_BUTTON_SELECTOR)
    try:
        await options.first.wait_for(state="visible", timeout=VERIFY_OPTION_TIMEOUT_MS)
    except PlaywrightError:
        await _log_verify_page_diagnostics(page)
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

    target = None
    count = await options.count()
    for index in range(count):
        option = options.nth(index)
        try:
            heading_text = (
                await option.locator(OPTION_HEADING_SELECTOR).first.inner_text()
            ).strip().lower()
        except PlaywrightError:
            continue
        if needle in heading_text:
            target = option
            break
    if target is None:
        await _log_verify_page_diagnostics(page)
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    await target.click()

    # AMEX may show an intermediate confirm/continue step before sending the
    # code; best-effort only -- its absence is not an error.
    try:
        continue_button = page.get_by_role(
            "button", name=OTP_SUBMIT_TEXT_PATTERN
        ).first
        await continue_button.wait_for(state="visible", timeout=VERIFY_CONTINUE_TIMEOUT_MS)
        await continue_button.click()
    except PlaywrightError:
        pass


async def _find_otp_input(page: Page):
    """Locates the OTP input on the verify page, trying selectors in order
    of specificity, then falling back to any visible text input.

    Raises HTTPException(502, UPSTREAM_FORMAT_CHANGED) with a diagnostic
    dump if none is found.
    """
    for selector in OTP_INPUT_SELECTORS:
        locator = page.locator(selector).first
        try:
            await locator.wait_for(state="visible", timeout=VERIFY_OTP_INPUT_TIMEOUT_MS)
            return locator
        except PlaywrightError:
            continue

    fallback = page.locator("input[type=text]:visible, input:not([type]):visible").first
    try:
        await fallback.wait_for(state="visible", timeout=2_000)
        return fallback
    except PlaywrightError:
        pass

    await _log_verify_page_diagnostics(page)
    raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")


def _serialize_cookies(cookies: Any, account_token: str | None = None, enrichment: dict[str, Any] | None = None) -> str:
    state: dict[str, Any] = {"cookies": cookies}
    if account_token:
        state["accountToken"] = account_token
    if enrichment:
        state["enrichment"] = enrichment
    return json.dumps(state, separators=(",", ":"))


def _account_token_from_cookies(cookies: list[dict[str, Any]]) -> str | None:
    for cookie in cookies:
        if cookie.get("name") in ("account_token", "axp_account_token"):
            return cookie.get("value")
    return None


def _account_token_from_member_payload(payload: Any) -> str | None:
    if not isinstance(payload, dict):
        return None
    accounts = payload.get("accounts")
    if not isinstance(accounts, list):
        return None
    for entry in accounts:
        if isinstance(entry, dict) and entry.get("account_token"):
            return str(entry["account_token"])
    return None


async def _account_token_via_member_api(page: Page) -> str | None:
    """Last-resort account token lookup: ask AMEX's own member endpoint from
    inside the authenticated browser context (so it reuses the page's
    cookies/headers exactly as the SPA would)."""
    try:
        response = await page.request.get(f"{API_BASE_URL}{MEMBER_PATH}")
        if response.status != 200:
            return None
        return _account_token_from_member_payload(await response.json())
    except (PlaywrightError, ValueError):
        return None


async def _account_token(page: Page, cookies: list[dict[str, Any]]) -> str | None:
    token = _account_token_from_cookies(cookies)
    if token:
        return token
    try:
        token = await page.evaluate(
            "() => window.localStorage.getItem('account_token') "
            "|| window.localStorage.getItem('axp_account_token')"
        )
    except PlaywrightError:
        token = None
    if token:
        return token
    return await _account_token_via_member_api(page)


def _capture_account_token_header(page: Page) -> tuple[asyncio.Future, Any]:
    """Registers a request listener that resolves the returned future with
    the first `account_token` header seen on an AMEX API call -- must be
    registered before the OTP submit click so dashboard XHRs are caught.
    """
    loop = asyncio.get_event_loop()
    future: asyncio.Future = loop.create_future()

    def _on_request(request: Any) -> None:
        if future.done():
            return
        if "global.americanexpress.com/api/" not in request.url:
            return
        token = request.headers.get(ACCOUNT_TOKEN_HEADER)
        if token:
            future.set_result(token)

    page.on("request", _on_request)
    return future, _on_request


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    camoufox: AsyncCamoufox | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        # headless=False: Akamai Bot Manager blocks the login POST outright from a
        # headless fingerprint (see the module docstring for the confirmed evidence),
        # so this runs a real, on-screen Camoufox Firefox under the container's Xvfb
        # display (entrypoint.sh) rather than a Chromium stealth-flag workaround.
        camoufox = AsyncCamoufox(
            headless=False,
            humanize=True,
            os="linux",
            locale="fr-FR",
        )
        browser = await camoufox.__aenter__()
        context = await browser.new_context(timezone_id="Europe/Paris")
        page = await context.new_page()
        enrichment: dict[str, Any] = {}

        async def _record_login_response(response: Any) -> None:
            try:
                if "americanexpress.com" not in response.url or "json" not in response.headers.get("content-type", "").lower():
                    return
                payload = await response.json()
                path = response.url.split("?", 1)[0]
                keys = sorted(payload) if isinstance(payload, dict) else []
                types = {key: type(payload[key]).__name__ for key in keys}
                log.info("AMEX JSON diagnostic (path=%s; keys=%s; types=%s; shape=%s)", path, keys, types, _shape_summary(payload))
                due_date, points = _parse_dashboard_enrichment(payload)
                enrichment["dueDate"] = enrichment.get("dueDate") or due_date
                enrichment["rewardPoints"] = enrichment.get("rewardPoints") if enrichment.get("rewardPoints") is not None else points
            except Exception:
                pass

        page.on("response", lambda response: asyncio.create_task(_record_login_response(response)))

        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        try:
            await page.locator(
                "#user-consent-management-granular-banner-decline-all-button"
            ).click(timeout=5_000)
        except PlaywrightError:
            pass  # banner not shown (already consented or A/B) -- continue

        user_field = page.locator(USERID_FIELD_SELECTOR).first
        password_field = page.locator(PASSWORD_FIELD_SELECTOR).first
        try:
            await user_field.wait_for(state="visible", timeout=LOGIN_FORM_TIMEOUT_MS)
            await password_field.wait_for(state="visible", timeout=LOGIN_FORM_TIMEOUT_MS)
        except PlaywrightError as exc:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc

        await user_field.press_sequentially(req.userId, delay=40)
        await password_field.press_sequentially(req.password, delay=40)

        user_val = await user_field.input_value()
        pwd_val = await password_field.input_value()
        user_ok = user_val == req.userId
        pwd_ok = pwd_val == req.password
        if not user_ok:
            await user_field.fill(req.userId)
        if not pwd_ok:
            await password_field.fill(req.password)
        log.info(
            "AMEX login fields typed user_ok=%s user_len=%d/%d pwd_ok=%s pwd_len=%d/%d%s%s",
            user_ok, len(user_val), len(req.userId),
            pwd_ok, len(pwd_val), len(req.password),
            " user_refilled=True" if not user_ok else "",
            " pwd_refilled=True" if not pwd_ok else "",
        )

        submit = page.locator(SUBMIT_BUTTON_SELECTOR).first
        try:
            await submit.wait_for(state="visible", timeout=LOGIN_FORM_TIMEOUT_MS)
        except PlaywrightError as exc:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc
        await submit.click()

        outcome = await _wait_for_login_outcome(page, LOGIN_RESULT_TIMEOUT_SECONDS)

        if outcome == "invalid":
            # AMEX's own bad-credentials message rendered on the login page
            # (confirmed live -- see _wait_for_login_outcome). This is the
            # only outcome that maps to a 401: it is an explicit rejection
            # from AMEX, not an inference from a stalled page.
            raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")

        if outcome == "timeout":
            # Neither the dashboard, the verify challenge, nor the known
            # bad-credentials message appeared. This is reported as an
            # upstream problem rather than guessed at as rejected
            # credentials -- a genuine AMEX outage or a markup change would
            # look identical from here.
            try:
                alerts = await page.query_selector_all(
                    "[role=alert], .alert, [class*=error], [data-testid*=error]"
                )
                log.warning("AMEX login outcome=timeout alerts_present=%s", bool(alerts))
            except Exception as exc:
                log.warning(
                    "AMEX login timeout diagnostics failed error_type=%s",
                    _safe_error_type(exc),
                )
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

        if outcome == "verify":
            await _select_otp_method(page, req.method)
            await _find_otp_input(page)  # confirms the input exists before storing pending state

            await _store_pending(
                process_id,
                {
                    "page": page,
                    "context": context,
                    "browser": browser,
                    "camoufox": camoufox,
                    "created_at": time.time(),
                    "enrichment": enrichment,
                },
            )
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "OTP",
                "sessionState": None,
            }

        # "success": no second factor was raised.
        cookies = await context.cookies()
        await _close_resources(context, browser, camoufox)
        return {
            "processId": None,
            "mfaRequired": False,
            "mfaType": None,
            "sessionState": _serialize_cookies(cookies),
        }
    except HTTPException:
        await _close_resources(context, browser, camoufox)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, camoufox)
        log.warning("AMEX authentication initiation failed error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, camoufox)
        log.error("Unexpected AMEX authentication initiation failure error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


# The exact copy AMEX renders when a submitted one-time code is rejected,
# plus the broader substrings ("incorrect", "invalide", "expir...") that also
# show up across AMEX's OTP error variants -- confirm against a live
# rejection before relying on any single one; until then a wrong-code
# submission that leaves the browser on the verify page past the timeout
# still falls back to INVALID_OTP below when the OTP input is still visible.
INVALID_OTP_TEXT = "code invalide"
INVALID_OTP_TEXT_PATTERN = re.compile(
    r"incorrect|invalide|expir", re.IGNORECASE
)


async def _invalid_otp_visible(page: Page) -> bool:
    try:
        alerts = await page.query_selector_all(
            "[role=alert], .alert, [class*=error], [data-testid*=error]"
        )
    except PlaywrightError:
        return False
    for el in alerts:
        try:
            text = (await el.inner_text()).strip().lower()
        except PlaywrightError:
            continue
        if INVALID_OTP_TEXT in text or INVALID_OTP_TEXT_PATTERN.search(text):
            return True
    return False


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    await _cleanup_expired()
    state = await _take_pending(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - PENDING_TTL_SECONDS:
        await _dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    page: Page = state["page"]
    context: BrowserContext = state["context"]
    enrichment: dict[str, Any] = state.get("enrichment") or {}
    keep_pending = False
    try:
        otp_input = await _find_otp_input(page)
        await otp_input.fill(req.otp)
        filled = await otp_input.input_value()
        if len(filled) != len(req.otp):
            await otp_input.fill("")
            await otp_input.press_sequentially(req.otp, delay=80)
            filled = await otp_input.input_value()
        log.info("AMEX otp filled otp_len_ok=%s", len(filled) == len(req.otp))

        # Registered before the submit click below so it catches the
        # dashboard's own XHRs (which carry the account_token header AMEX's
        # servicing APIs require) as soon as the SPA loads post-OTP.
        account_token_future, _account_token_listener = _capture_account_token_header(page)

        submit = None
        candidates = page.locator("button[type=submit]")
        for index in range(await candidates.count()):
            candidate = candidates.nth(index)
            try:
                if not (await candidate.is_visible() and await candidate.is_enabled()):
                    continue
                text = (await candidate.inner_text()).strip()
            except PlaywrightError:
                continue
            if OTP_SUBMIT_EXCLUDE_PATTERN.search(text):
                continue
            submit = candidate
            break

        if submit is None:
            role_candidates = page.get_by_role("button", name=OTP_SUBMIT_TEXT_PATTERN)
            for index in range(await role_candidates.count()):
                candidate = role_candidates.nth(index)
                try:
                    if not (await candidate.is_visible() and await candidate.is_enabled()):
                        continue
                    text = (await candidate.inner_text()).strip()
                except PlaywrightError:
                    continue
                if OTP_SUBMIT_EXCLUDE_PATTERN.search(text):
                    continue
                submit = candidate
                break

        if submit is not None:
            button_text = ""
            try:
                button_text = (await submit.inner_text()).strip()
            except PlaywrightError:
                pass
            log.info("AMEX otp submit button=%s", _mask_digits(button_text))
            await submit.click()
        else:
            log.info("AMEX otp submit: no button found, pressing Enter in OTP input")
            await otp_input.press("Enter")

        deadline = time.monotonic() + OTP_RESULT_TIMEOUT_SECONDS
        approved = False
        trust_device_continued = False
        while time.monotonic() < deadline:
            if await _dashboard_reached(page):
                approved = True
                break
            if not trust_device_continued:
                try:
                    trust_checkbox = page.locator(TRUST_DEVICE_CHECKBOX_SELECTOR)
                    if await trust_checkbox.count() > 0 and await trust_checkbox.first.is_visible():
                        continue_button = page.get_by_role(
                            "button", name=TRUST_DEVICE_CONTINUE_PATTERN
                        ).first
                        await continue_button.click()
                        trust_device_continued = True
                        log.info("AMEX trusted-device step: continued")
                        continue
                except PlaywrightError:
                        log.warning("AMEX trusted-device continue click failed")
            if "two-step-verification" not in page.url and LOGIN_URL.rstrip("/") not in page.url:
                # Left the verify flow for somewhere other than the login
                # page (e.g. global.americanexpress.com/overview) -- treat
                # as success rather than requiring an exact /myca//dashboard
                # match.
                approved = True
                break
            await asyncio.sleep(OTP_POLL_SECONDS)

        if not approved:
            alert_present = False
            try:
                alerts = await page.query_selector_all(
                    "[role=alert], .alert, [class*=error], [data-testid*=error]"
                )
                alert_present = bool(alerts)
            except PlaywrightError:
                pass
            log.warning(
                "AMEX otp not approved url=%s title=%s alerts_present=%s",
                _mask_digits(page.url.split("?")[0]),
                _mask_digits(await page.title()),
                alert_present,
            )
            await _log_verify_page_diagnostics(page)

            if await _invalid_otp_visible(page):
                # Wrong code, not an expired attempt -- keep the pending
                # browser alive so the user can retry without logging in
                # again.
                keep_pending = True
                raise HTTPException(status_code=401, detail="INVALID_OTP")

            try:
                otp_input_still_visible = await otp_input.is_visible()
            except PlaywrightError:
                otp_input_still_visible = False
            if "two-step-verification" in page.url and otp_input_still_visible:
                # Still on the verify page with the code field present:
                # AMEX didn't confirm the code but also didn't kick us back
                # to login -- most likely a wrong code with unrecognized
                # error copy. Keep the pending state so the user can retry
                # without a new SMS, same as the confirmed INVALID_OTP path.
                keep_pending = True
                raise HTTPException(status_code=401, detail="INVALID_OTP")

            raise HTTPException(status_code=401, detail="AUTH_ATTEMPT_EXPIRED")

        account_token: str | None = None
        try:
            account_token = await asyncio.wait_for(
                account_token_future, timeout=ACCOUNT_TOKEN_CAPTURE_TIMEOUT_SECONDS
            )
            token_source = "xhr"
        except asyncio.TimeoutError:
            token_source = None
        page.remove_listener("request", _account_token_listener)

        if not account_token:
            account_token = await _account_token(page, await context.cookies())
            token_source = "helper" if account_token else None

        log.info("AMEX account token capture token_found=%s source=%s", bool(account_token), token_source)

        try:
            async with page.expect_response(
                lambda response: "ReadLoyaltyTransactions" in response.url
                and '"periodIndex":0' in (response.request.post_data or "").replace(" ", ""),
                timeout=20_000,
            ) as loyalty_response_info:
                await page.goto(
                    "https://global.americanexpress.com/rewards/summary",
                    wait_until="domcontentloaded",
                    timeout=20_000,
                )
            loyalty_response = await loyalty_response_info.value
            loyalty_payload = await loyalty_response.json()
            earned_points = _parse_loyalty_earned(loyalty_payload)
            if earned_points is not None:
                enrichment["rewardPoints"] = earned_points
        except Exception:
            log.info("AMEX loyalty summary capture unavailable")

        cookies = await context.cookies()
        return {"sessionState": _serialize_cookies(cookies, account_token, enrichment)}

    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("AMEX authentication completion failed error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected AMEX authentication completion failure error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        if keep_pending:
            state["created_at"] = time.time()
            async with _pending_lock:
                _pending[req.processId] = state
        else:
            await _dispose_pending_state(state)


# ─── Accounts ───────────────────────────────────────────────────────────────


def _restore_cookies(raw: str) -> tuple[list[dict[str, Any]], str | None, dict[str, Any]]:
    try:
        decoded = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(decoded, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    cookies = decoded.get("cookies")
    if not isinstance(cookies, list) or not cookies:
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    for cookie in cookies:
        if not isinstance(cookie, dict) or not isinstance(cookie.get("name"), str):
            raise HTTPException(status_code=400, detail="INVALID_DATA")
    # accountToken is absent from sessionState produced before this field
    # existed -- old, already-stored sessions must keep working.
    account_token = decoded.get("accountToken")
    if account_token is not None and not isinstance(account_token, str):
        account_token = None
    enrichment = decoded.get("enrichment")
    return cookies, account_token, enrichment if isinstance(enrichment, dict) else {}


def _new_client(cookies: list[dict[str, Any]], account_token: str | None = None) -> httpx.AsyncClient:
    headers = {
        "Accept": "application/json",
        "Accept-Language": "fr-FR,fr;q=0.9",
        "User-Agent": FIREFOX_USER_AGENT,
        "Origin": API_BASE_URL,
        "Referer": f"{API_BASE_URL}/",
    }
    if account_token:
        headers[ACCOUNT_TOKEN_HEADER] = account_token
    client = httpx.AsyncClient(
        base_url=API_BASE_URL,
        timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
        headers=headers,
    )
    for cookie in cookies:
        client.cookies.set(
            cookie["name"],
            str(cookie.get("value", "")),
            domain=str(cookie.get("domain") or "").lstrip("."),
            path=str(cookie.get("path") or "/"),
        )
    return client


def _decimal(value: Any, field: str) -> Decimal:
    try:
        return Decimal(str(value))
    except (InvalidOperation, TypeError) as exc:
        raise AmexFormatError(f"{field} was not a parseable number") from exc


class AmexFormatError(Exception):
    """The provider handed back JSON that no longer matches the shapes below."""


def _masked_error_diagnostics(response: httpx.Response) -> str:
    """Best-effort, masked summary of an error body's shape -- key names and
    a digit-masked, truncated error message, never the full body."""
    try:
        body = response.json()
    except ValueError:
        return "body=<non-json>"
    if not isinstance(body, dict):
        return f"body_type={type(body).__name__}"
    keys = sorted(body.keys())[:10]
    message = None
    for field in ("message", "error", "errorCode", "code", "detail"):
        if isinstance(body.get(field), str):
            message = body[field]
            break
    summary = f"keys={keys}"
    if message:
        summary += f" message={_mask_digits(message)[:120]}"
    return summary


async def _get_json(client: httpx.AsyncClient, path: str) -> Any:
    response = await client.get(path)
    if response.status_code in (401, 403):
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if response.status_code != 200:
        log.warning(
            "AMEX request failed (path=%s; status=%s; %s)",
            path.split("?")[0],
            response.status_code,
            _masked_error_diagnostics(response),
        )
        if response.status_code == 429 or response.status_code >= 500:
            raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE")
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    try:
        return response.json()
    except ValueError as exc:
        raise AmexFormatError("response body was not JSON") from exc


def _shape_summary(payload: Any) -> str:
    """Safe, value-free description of an unexpected payload's shape: root
    type, list length if a list, and (for the first dict found) sorted key
    names with value TYPES only -- nested dicts one level deep, key names
    only. Never includes any value, so it's safe to log at WARNING."""
    root_type = type(payload).__name__
    parts = [f"root_type={root_type}"]
    first: Any = payload
    if isinstance(payload, list):
        parts.append(f"list_len={len(payload)}")
        first = payload[0] if payload else None
    if isinstance(first, dict):
        key_types = []
        for key in sorted(first.keys()):
            value = first[key]
            if isinstance(value, dict):
                key_types.append(f"{key}:dict[{','.join(sorted(value.keys()))}]")
            else:
                key_types.append(f"{key}:{type(value).__name__}")
        parts.append("keys=" + ",".join(key_types))
    return " ".join(parts)


def _numeric_flag(value: Any) -> str | None:
    """Value-free flag for a numeric-looking value: 'num:nz' (non-zero) or
    'num:zero'. Returns None for anything that isn't int/float/bool or a
    string that parses as a number -- so a plain label/date/id is left out
    rather than misreported as zero."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return "num:nz" if value != 0 else "num:zero"
    if isinstance(value, str):
        try:
            return "num:nz" if float(value) != 0 else "num:zero"
        except ValueError:
            return None
    return None


def _entry_diagnostic_summary(entry: dict[str, Any]) -> str:
    """Safe, value-free description of a balance entry: for each key, either
    a numeric non-zero/zero flag, a bool value (never a secret), or the
    value's TYPE only. Dict values are recursed one level (key names/flags
    only, same rule) -- covers `extended_details` etc. Never includes an
    actual amount, date, id or string value."""
    parts = []
    for key in sorted(entry.keys()):
        value = entry[key]
        if isinstance(value, dict):
            nested = []
            for nested_key in sorted(value.keys()):
                nested_value = value[nested_key]
                flag = _numeric_flag(nested_value)
                if flag is not None:
                    nested.append(f"{nested_key}:{flag}")
                elif isinstance(nested_value, bool):
                    nested.append(f"{nested_key}:bool:{nested_value}")
                else:
                    nested.append(f"{nested_key}:{type(nested_value).__name__}")
            parts.append(f"{key}:dict[{','.join(nested)}]")
            continue
        flag = _numeric_flag(value)
        if flag is not None:
            parts.append(f"{key}:{flag}")
        elif isinstance(value, bool):
            parts.append(f"{key}:bool:{value}")
        else:
            parts.append(f"{key}:{type(value).__name__}")
    return ",".join(parts)


def _unwrap_amount(value: Any) -> Any:
    """AMEX sometimes nests an amount as {'amount'|'value'|'total': x}
    instead of a bare number -- unwrap one level if so."""
    if isinstance(value, dict):
        for key in ("amount", "value", "total"):
            if key in value:
                return value[key]
        return None
    return value


def _first(entry: dict[str, Any], *keys: str) -> Any:
    for key in keys:
        if entry.get(key) is not None:
            return entry[key]
    return None


def _parse_balance(payload: Any, account_token: str | None = None) -> dict[str, Any]:
    if isinstance(payload, list):
        accounts = payload
    elif isinstance(payload, dict):
        accounts = payload.get("accounts") or payload.get("financialAccountSummaries") or payload.get("balances")
        if not isinstance(accounts, list):
            raise AmexFormatError(f"balances payload had unexpected shape ({_shape_summary(payload)})")
    else:
        raise AmexFormatError(f"balances payload had unexpected shape ({_shape_summary(payload)})")
    if not accounts:
        raise AmexFormatError("balances payload carried no accounts")

    entry = None
    if account_token:
        for candidate in accounts:
            if not isinstance(candidate, dict):
                continue
            candidate_token = _first(candidate, "accountToken", "account_token")
            if candidate_token is not None and str(candidate_token) == str(account_token):
                entry = candidate
                break
    if entry is None:
        entry = accounts[0]
    if not isinstance(entry, dict):
        raise AmexFormatError(f"balances account entry was not an object ({_shape_summary(payload)})")

    statement_balance = _unwrap_amount(
        _first(entry, "statementBalance", "statement_balance_amount", "statement_balance")
    )

    if entry.get("statement_balance_amount") is not None or entry.get("total_debits_balance_amount") is not None:
        statement_amt = statement_balance if statement_balance is not None else 0
        debits_amt = _unwrap_amount(entry.get("total_debits_balance_amount"))
        debits_amt = debits_amt if debits_amt is not None else 0
        credits_amt = _unwrap_amount(entry.get("total_payments_credits_amount"))
        credits_amt = credits_amt if credits_amt is not None else 0
        remaining_amt = _unwrap_amount(entry.get("remaining_statement_balance_amount"))
        if remaining_amt is not None:
            # remaining_statement_balance_amount is the statement balance
            # AFTER payments/credits are netted in, so the current balance
            # is what is still owed on the statement plus the new debits
            # posted since the statement date. Using statement_balance_amount
            # here would double-count a statement that has already been paid.
            current_balance_key = (
                "remaining_statement_balance_amount+total_debits_balance_amount"
            )
            current_balance = (
                _decimal(remaining_amt, "remaining_statement_balance_amount")
                + _decimal(debits_amt, "total_debits_balance_amount")
            )
        else:
            # Legacy fallback for entries without remaining_statement_balance_amount.
            current_balance_key = (
                "statement_balance_amount+total_debits_balance_amount-total_payments_credits_amount"
            )
            current_balance = (
                _decimal(statement_amt, "statement_balance_amount")
                + _decimal(debits_amt, "total_debits_balance_amount")
                - _decimal(credits_amt, "total_payments_credits_amount")
            )
    else:
        current_balance_keys = (
            "totalBalance", "total_balance_amount", "total_balance",
            "currentBalance", "current_balance", "balance",
        )
        current_balance_key = next((k for k in current_balance_keys if entry.get(k) is not None), None)
        current_balance = _unwrap_amount(_first(entry, *current_balance_keys))
        if current_balance is None:
            raise AmexFormatError(f"balances entry carried no balance ({_shape_summary(payload)})")

    log.info(
        "AMEX balance entry diagnostic (current_balance_key=%s; %s)",
        current_balance_key,
        _entry_diagnostic_summary(entry),
    )

    minimum_payment = _unwrap_amount(
        _first(entry, "minimumPaymentDue", "minimum_payment_due", "minimum_payment_due_amount")
    )
    rewards_balance = _unwrap_amount(_first(entry, "rewardsBalance", "rewards_balance"))
    entry_token = _first(entry, "accountToken", "account_token")

    return {
        "externalId": str(entry_token or _first(entry, "accountId", "account_id") or "amex_card"),
        "name": _first(entry, "productDescription", "accountName", "product_description", "product_name")
        or "American Express",
        # AMEX reports what is owed as a positive figure; Picsou's convention
        # for a liability is negative.
        "balanceEur": -_decimal(current_balance, "balance"),
        "statementBalance": (
            _decimal(statement_balance, "statementBalance") if statement_balance is not None else None
        ),
        "amountDue": (
            _decimal(_unwrap_amount(entry.get("remaining_statement_balance_amount")), "amountDue")
            if entry.get("remaining_statement_balance_amount") is not None else None
        ),
        "dueDate": _first(entry, "payment_due_date", "due_date", "paymentDueDate"),
        "minimumPayment": (
            _decimal(minimum_payment, "minimumPayment") if minimum_payment is not None else None
        ),
        "rewardPoints": int(rewards_balance) if rewards_balance is not None else None,
        "accountToken": entry_token,
    }


def _normalize_category(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("category_name", "categoryName", "name", "subcategory_name", "subcategoryName", "category"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate:
                return candidate
    return None


def _parse_transactions(
    payload: Any, log_label: str = "posted", status_default: str | None = None
) -> list[dict[str, Any]]:
    root_type = type(payload).__name__
    if isinstance(payload, dict):
        raw = payload.get("transactions") or []
        root_keys = sorted(payload.keys())
    elif isinstance(payload, list):
        raw = payload
        root_keys = None
    else:
        raise AmexFormatError("transactions payload had an unexpected root type")
    if not isinstance(raw, list):
        raise AmexFormatError("transactions payload's transaction list was not an array")

    transactions: list[dict[str, Any]] = []
    skipped_missing_date = 0
    skipped_missing_description = 0
    skipped_missing_amount = 0
    for index, entry in enumerate(raw):
        if not isinstance(entry, dict):
            raise AmexFormatError(f"transaction entry {index} was not an object")
        extended_details = entry.get("extendedDetails") or entry.get("extended_details")
        extended_details = extended_details if isinstance(extended_details, dict) else {}
        merchant = extended_details.get("merchant")
        merchant_name = merchant.get("name") if isinstance(merchant, dict) else None
        category = _normalize_category(extended_details.get("category"))
        if category is None:
            category = _normalize_category(entry.get("category"))

        date = _first(entry, "chargeDate", "charge_date", "date", "transactionDate")
        description = _first(entry, "description") or merchant_name
        amount = _unwrap_amount(_first(entry, "amount", "billingAmount", "billing_amount"))
        if not date:
            skipped_missing_date += 1
            continue
        if not description:
            skipped_missing_description += 1
            continue
        if amount is None:
            skipped_missing_amount += 1
            continue
        transactions.append(
            {
                "date": str(date)[:10],
                "description": str(description),
                "amount": -_decimal(amount, "transaction amount"),
                # The feed the row came from decides its status: the backend deletes only
                # rows it stored as pending, so AMEX's own field must not relabel them.
                "status": status_default or entry.get("status"),
                "category": category,
                "identifier": entry.get("identifier"),
            }
        )

    first_shape = _shape_summary(raw) if raw else "root_type=NoneType"
    first_category_shape = "none"
    if raw and isinstance(raw[0], dict):
        first_details = raw[0].get("extendedDetails") or raw[0].get("extended_details")
        first_category = first_details.get("category") if isinstance(first_details, dict) else None
        if isinstance(first_category, dict):
            first_category_shape = "dict[" + ",".join(
                f"{key}:{type(value).__name__}" for key, value in sorted(first_category.items())
            ) + "]"
    log.info(
        "AMEX transactions diagnostic (label=%s; root_type=%s; root_keys=%s; raw_len=%s; "
        "parsed_count=%s; first_entry=[%s]; first_category=%s; skipped_missing_date=%s; "
        "skipped_missing_description=%s; skipped_missing_amount=%s)",
        log_label,
        root_type,
        root_keys,
        len(raw),
        len(transactions),
        first_shape,
        first_category_shape,
        skipped_missing_date,
        skipped_missing_description,
        skipped_missing_amount,
    )
    return transactions


def _merge_transactions(
    posted: list[dict[str, Any]], pending: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Concatenates posted and pending transactions, deduping by
    `identifier` when the same transaction appears in both (posted wins,
    since it reflects the settled state). The `identifier` key used only
    for dedup is stripped before returning -- TransactionPayload doesn't
    carry it."""
    seen_identifiers = {t["identifier"] for t in posted if t.get("identifier") is not None}
    merged = list(posted) + [
        t for t in pending if t.get("identifier") is None or t["identifier"] not in seen_identifiers
    ]
    return [{k: v for k, v in t.items() if k != "identifier"} for t in merged]


def _parse_dashboard_enrichment(payload: Any) -> tuple[str | None, int | None]:
    due_date = None
    points = None
    date_keys = {"payment_due_date", "due_date", "paymentduedate", "duedate", "direct_debit_date", "directdebitdate", "nextdebitdate"}
    point_keys = {"points_balance", "reward_points", "rewards_points", "loyalty_balance"}
    date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}")
    def walk(value: Any) -> None:
        nonlocal due_date, points
        if isinstance(value, dict):
            for key, child in value.items():
                normalized = key.lower().replace("-", "_")
                if due_date is None and (normalized in date_keys or normalized.replace("_", "") in date_keys):
                    if isinstance(child, str) and date_pattern.match(child):
                        due_date = child[:10]
                if points is None and normalized in point_keys and isinstance(child, (int, float)):
                    points = int(child)
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)
    walk(payload)
    return due_date, points


def _parse_loyalty_earned(payload: Any) -> int | None:
    if not isinstance(payload, dict) or not isinstance(payload.get("totals"), list):
        return None
    for item in payload["totals"]:
        if not isinstance(item, dict) or item.get("type") != "EARNED":
            continue
        total = item.get("total")
        value = total.get("value") if isinstance(total, dict) else None
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return None
        return int(value)
    return None


def _parse_scheduled_due_date(payload: Any, account_token: str | None = None) -> str | None:
    if not isinstance(payload, list):
        return None
    entries = [entry for entry in payload if isinstance(entry, dict)]
    entry = next((item for item in entries if account_token and str(item.get("account_token")) == str(account_token)), None)
    if entry is None and len(entries) == 1 and len(payload) == 1:
        entry = entries[0]
    if entry is None:
        return None
    for group, key in (("direct_debit_details", "direct_debit_date"), ("payment_due_details", "payment_due_date")):
        details = entry.get(group)
        value = details.get(key) if isinstance(details, dict) else None
        if isinstance(value, str) and re.match(r"^\d{4}-\d{2}-\d{2}", value):
            return value[:10]
    return None


async def _collect_accounts(client: httpx.AsyncClient, account_token: str | None = None, enrichment: dict[str, Any] | None = None, history: bool = False) -> list[AccountPayload]:
    balance_json = await _get_json(client, BALANCES_PATH)
    account = _parse_balance(balance_json, account_token)
    enrichment = enrichment or {}
    account["dueDate"] = account.get("dueDate") or enrichment.get("dueDate")
    account["rewardPoints"] = account.get("rewardPoints") if account.get("rewardPoints") is not None else enrichment.get("rewardPoints")

    try:
        payments = await _get_json(client, "/api/servicing/v1/financials/payments?status=scheduled")
        scheduled_due = _parse_scheduled_due_date(payments, account_token)
        if scheduled_due:
            account["dueDate"] = scheduled_due
    except HTTPException as exc:
        if exc.detail == "SESSION_EXPIRED":
            raise
        log.info("AMEX scheduled-payments fetch failed (status=%s)", exc.status_code)
    except AmexFormatError:
        log.info("AMEX scheduled-payments response rejected")
    except httpx.HTTPError as exc:
        log.info("AMEX scheduled-payments fetch failed (error=%s)", type(exc).__name__)

    async def fetch_transactions(status: str) -> list[dict[str, Any]]:
        # Older AMEX clients may honor only limit; newer ones also honor beforeDate.
        limit = 1000 if history else TRANSACTION_PAGE_SIZE
        params = f"limit={limit}&status={status}&extended_details=merchant,category"
        if history:
            params += "&beforeDate=1900-01-01"
        payload = await _get_json(client, f"{TRANSACTIONS_PATH}?{params}")
        return _parse_transactions(payload, log_label=status, status_default=status)

    posted_transactions = await fetch_transactions("posted")
    pending_transactions: list[dict[str, Any]] = []
    pending_complete = False
    try:
        pending_transactions = await fetch_transactions("pending")
        pending_complete = True
    except HTTPException as exc:
        log.info("AMEX pending-transactions fetch failed (status=%s)", exc.status_code)
    except AmexFormatError:
        log.info("AMEX pending-transactions response rejected")
    except httpx.HTTPError as exc:
        log.info("AMEX pending-transactions fetch failed (error=%s)", type(exc).__name__)

    transactions = _merge_transactions(posted_transactions, pending_transactions)

    account.pop("accountToken", None)
    account["dueDate"] = account.get("dueDate") or None

    account["transactions"] = transactions
    account["pendingComplete"] = pending_complete
    account["snapshotComplete"] = True
    return [AccountPayload.model_validate(account)]


@app.post("/accounts", response_model=list[AccountPayload])
async def accounts(req: AccountsRequest) -> list[AccountPayload]:
    cookies, account_token, enrichment = _restore_cookies(req.sessionState)
    client = _new_client(cookies, account_token)
    try:
        if not account_token:
            # Older sessionState predating token capture in /complete, or a
            # capture that failed at login time -- ask AMEX's member API for
            # it before the (account_token-gated) balances/transactions
            # calls below.
            member_json = await _get_json(client, MEMBER_PATH)
            account_token = _account_token_from_member_payload(member_json)
            log.info("AMEX account token discovered via member API token_found=%s", bool(account_token))
            if account_token:
                client.headers[ACCOUNT_TOKEN_HEADER] = account_token
        return await _collect_accounts(client, account_token, enrichment, req.history)
    except AmexFormatError as exc:
        log.warning("AMEX accounts payload rejected: %s", exc)
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc
    except ValidationError as exc:
        log.warning("AMEX accounts payload failed validation: %s", exc.error_count())
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("AMEX accounts fetch failed error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected AMEX accounts failure error_type=%s", _safe_error_type(exc))
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await client.aclose()


def _self_check() -> None:
    """Pure-function regression check for the sessionState round-trip this
    fix depends on: old sessionState (no accountToken) and new sessionState
    (with accountToken) must both restore correctly."""
    from fastapi.testclient import TestClient  # self-check only, keep it off the serving path

    sample_cookies = [{"name": "session", "value": "abc", "domain": "americanexpress.com", "path": "/"}]

    legacy_state = json.dumps({"cookies": sample_cookies}, separators=(",", ":"))
    cookies, token, enrichment = _restore_cookies(legacy_state)
    assert cookies == sample_cookies
    assert token is None
    assert enrichment == {}

    new_state = _serialize_cookies(sample_cookies, "tok-123", {"dueDate": "2026-03-04", "rewardPoints": 350})
    cookies, token, enrichment = _restore_cookies(new_state)
    assert cookies == sample_cookies
    assert token == "tok-123"
    assert enrichment == {"dueDate": "2026-03-04", "rewardPoints": 350}

    no_token_state = _serialize_cookies(sample_cookies, None)
    assert "accountToken" not in json.loads(no_token_state)

    assert _account_token_from_member_payload(
        {"accounts": [{"account_token": "abc123"}]}
    ) == "abc123"
    assert _account_token_from_member_payload({"accounts": []}) is None
    assert _account_token_from_member_payload({}) is None
    assert _parse_dashboard_enrichment({"nested": {"paymentDueDate": "2026-03-04", "points_balance": 350}}) == ("2026-03-04", 350)
    # Regression: generic "balance" must not be mistaken for reward points
    # (it's a monetary field on countless AMEX JSON payloads).
    assert _parse_dashboard_enrichment({"balance": 123.45}) == (None, None)
    assert _parse_scheduled_due_date([{"account_token": "x", "direct_debit_details": {"direct_debit_date": "2026-04-05T00:00:00Z"}, "payment_due_details": {"payment_due_date": "2026-04-06"}}], "x") == "2026-04-05"
    assert _parse_scheduled_due_date([{"account_token": "x", "payment_due_details": {"payment_due_date": "2026-04-06"}}], "x") == "2026-04-06"
    assert _parse_scheduled_due_date([{"account_token": "x"}, {"account_token": "y"}], "y") is None
    assert _parse_scheduled_due_date({"junk": []}) is None
    assert _parse_loyalty_earned({"totals": [{"type": "EARNED_BASE", "total": {"value": 4}}, {"type": "EARNED", "total": {"value": 13}}]}) == 13
    assert _parse_loyalty_earned({"totals": []}) is None
    assert _parse_loyalty_earned({"totals": [{"type": "EARNED", "total": {"value": "junk"}}]}) is None

    # Legacy-shape regression: totalBalance-only snake_case entry (no
    # statement_balance_amount/total_debits_balance_amount) falls back to
    # the plain current-balance keys.
    list_balance = _parse_balance(
        [
            {
                "account_token": "tok-1",
                "total_balance_amount": "123.45",
                "minimum_payment_due": "25.00",
                "dueDate": "2026-01-15",
                "rewardPoints": 125,
                "product_name": "Gold Card",
            }
        ]
    )
    assert list_balance["balanceEur"] == Decimal("-123.45")
    assert list_balance["accountToken"] == "tok-1"
    assert list_balance["name"] == "Gold Card"

    # A list root with a matching account_token must pick that entry, not
    # just the first one.
    matched_balance = _parse_balance(
        [
            {"account_token": "other", "total_balance_amount": "1.00"},
            {"account_token": "tok-2", "total_balance_amount": "2.00"},
        ],
        account_token="tok-2",
    )
    assert matched_balance["balanceEur"] == Decimal("-2.00")

    # Dict root with camelCase keys (older/alternate shape) must still work.
    dict_balance = _parse_balance(
        {"accounts": [{"accountToken": "tok-3", "totalBalance": 50, "accountName": "Card"}]}
    )
    assert dict_balance["balanceEur"] == Decimal("-50")
    assert dict_balance["name"] == "Card"

    # Snake_case transaction with a nested amount dict.
    transactions = _parse_transactions(
        {
            "transactions": [
                {
                    "charge_date": "2026-01-02T00:00:00Z",
                    "description": "Coffee Shop",
                    "amount": {"amount": "4.50"},
                    "extended_details": {"category": "Dining"},
                }
            ]
        }
    )
    assert len(transactions) == 1
    assert transactions[0]["amount"] == Decimal("-4.50")
    assert transactions[0]["category"] == "Dining"
    assert _normalize_category({"category_name": "Restaurant", "subcategory_name": "x"}) == "Restaurant"
    assert _normalize_category({"unknown": "ignored"}) is None
    category_account = AccountPayload.model_validate({
        "externalId": "card",
        "name": "Card",
        "balanceEur": Decimal("-1"),
        "transactions": _merge_transactions(_parse_transactions({"transactions": [{
            "charge_date": "2026-01-02",
            "description": "Cafe",
            "amount": "1.00",
            "extended_details": {"category": {"category_name": "Restaurant", "subcategory_name": "x"}},
        }]}), []),
        "snapshotComplete": True,
    })
    assert category_account.transactions[0].category == "Restaurant"

    # Real AMEX /financials/balances shape: list root, one entry. The entry
    # carries remaining_statement_balance_amount=63.50 against a statement of
    # 100.00, i.e. 36.50 of the statement has already been paid, so the
    # current balance is what remains on the statement (63.50) plus the new
    # debits since the statement date (0) = 63.50 -- not the full statement.
    real_shape_balance = _parse_balance(
        [
            {
                "account_token": "tok-real",
                "cash_advance_balance_amount": 0.0,
                "charges_amount": 0.0,
                "dispute_details": {"total_amount": 0.0},
                "disputed_amount": 0.0,
                "extended_details": {
                    "deferred": 0.0,
                    "early_pay": 0.0,
                    "non_deferred": 0.0,
                    "pay_in_full": 0.0,
                    "pay_over_time": 0.0,
                },
                "interest_charge_amount": 0.0,
                "interest_saver_amount": 0.0,
                "last_statement_balance_amount": 100.0,
                "pay_flex_disclosure_indicator": False,
                "pay_flex_indicator": False,
                "qualified": True,
                "remaining_statement_balance_amount": 63.50,
                "statement_balance_amount": 100.0,
                "total_debits_balance_amount": 0.0,
                "total_payments_credits_amount": 36.50,
            },
            {"account_token": "tok-other", "total_debits_balance_amount": 999.0},
        ],
        account_token="tok-real",
    )
    assert real_shape_balance["balanceEur"] == Decimal("-63.50")
    assert real_shape_balance["statementBalance"] == Decimal("100.00")
    assert real_shape_balance["amountDue"] == Decimal("63.50")
    assert real_shape_balance["dueDate"] is None
    assert real_shape_balance["rewardPoints"] is None
    assert real_shape_balance["accountToken"] == "tok-real"

    # API discovery helpers tolerate nested names and the final account DTO keeps one contract.
    assert _parse_dashboard_enrichment({"loyalty": {"points_balance": 350}, "payment_due_date": "2026-03-04"}) == ("2026-03-04", 350)
    contract = AccountPayload.model_validate({
        **{key: value for key, value in real_shape_balance.items() if key != "accountToken"},
        "dueDate": "2026-03-04",
        "rewardPoints": 350,
        "transactions": [],
        "snapshotComplete": True,
    }).model_dump()
    assert contract["amountDue"] == Decimal("63.50")
    assert contract["dueDate"] == "2026-03-04"
    assert contract["rewardPoints"] == 350
    assert "paymentDueDate" not in contract and "rewardsPoints" not in contract

    # Same real shape, with a non-zero debits and a payment/credit --
    # statement + debits - credits.
    real_shape_balance_with_activity = _parse_balance(
        [
            {
                "account_token": "tok-real2",
                "statement_balance_amount": 100.0,
                "total_debits_balance_amount": 23.45,
                "total_payments_credits_amount": 10.0,
            },
        ],
        account_token="tok-real2",
    )
    assert real_shape_balance_with_activity["balanceEur"] == Decimal("-113.45")

    # ─── Statement-payment balance semantics (issue #196) ────────────────
    # When remaining_statement_balance_amount is present it already nets the
    # payments/credits against the statement, so the current balance is
    # remaining + new debits -- a paid statement must not be double-counted.

    # No payment yet: remaining == statement, so balance = statement + debits.
    no_payment_balance = _parse_balance(
        [{
            "account_token": "tok-nopay",
            "statement_balance_amount": 500.0,
            "remaining_statement_balance_amount": 500.0,
            "total_debits_balance_amount": 20.0,
            "total_payments_credits_amount": 0.0,
        }],
        account_token="tok-nopay",
    )
    assert no_payment_balance["balanceEur"] == Decimal("-520.00")

    # Full payment: the statement is paid in full (remaining = 0), only the
    # new debits since the statement date remain due. Real case from the
    # issue: statement 2122.00 paid, 133.00 of new purchases -> -133.00.
    full_payment_balance = _parse_balance(
        [{
            "account_token": "tok-fullpay",
            "statement_balance_amount": 2122.0,
            "last_statement_balance_amount": 2122.0,
            "remaining_statement_balance_amount": 0.0,
            "total_debits_balance_amount": 133.0,
            "total_payments_credits_amount": 2122.0,
            "charges_amount": 133.0,
        }],
        account_token="tok-fullpay",
    )
    assert full_payment_balance["balanceEur"] == Decimal("-133.00")
    assert full_payment_balance["amountDue"] == Decimal("0.00")

    # Partial payment: 100.00 statement, 36.50 paid -> 63.50 still owed on
    # the statement, plus 10.00 of new debits -> -73.50.
    partial_payment_balance = _parse_balance(
        [{
            "account_token": "tok-partial",
            "statement_balance_amount": 100.0,
            "remaining_statement_balance_amount": 63.50,
            "total_debits_balance_amount": 10.0,
            "total_payments_credits_amount": 36.50,
        }],
        account_token="tok-partial",
    )
    assert partial_payment_balance["balanceEur"] == Decimal("-73.50")

    # Refund/credit: credits exceed the statement (overpayment/refund), the
    # statement nets to zero, only new debits remain due.
    refund_credit_balance = _parse_balance(
        [{
            "account_token": "tok-refund",
            "statement_balance_amount": 100.0,
            "remaining_statement_balance_amount": 0.0,
            "total_debits_balance_amount": 5.0,
            "total_payments_credits_amount": 120.0,
        }],
        account_token="tok-refund",
    )
    assert refund_credit_balance["balanceEur"] == Decimal("-5.00")

    # The legacy formula still applies when remaining_statement_balance_amount
    # is absent (already covered above by real_shape_balance_with_activity:
    # statement + debits - credits = 100 + 23.45 - 10 = 113.45).

    # Merge posted + pending transactions, deduping by identifier (posted wins).
    posted_for_merge = [
        {"date": "2026-01-01", "description": "A", "amount": Decimal("-1"), "status": "posted",
         "category": None, "identifier": "id-1"},
    ]
    pending_for_merge = [
        {"date": "2026-01-01", "description": "A", "amount": Decimal("-1"), "status": "pending",
         "category": None, "identifier": "id-1"},
        {"date": "2026-01-02", "description": "B", "amount": Decimal("-2"), "status": "pending",
         "category": None, "identifier": "id-2"},
    ]
    merged = _merge_transactions(posted_for_merge, pending_for_merge)
    assert len(merged) == 2
    assert merged[0]["status"] == "posted"  # posted wins over duplicate identifier
    assert merged[1]["description"] == "B"
    assert all("identifier" not in t for t in merged)

    # The backend deletes stored pending rows only when the pending feed answered.
    def collect_with_pending(pending_status: int | type[httpx.HTTPError]) -> AccountPayload:
        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == BALANCES_PATH.split("?")[0]:
                return httpx.Response(200, json=[{"account_token": "tok", "statement_balance_amount": 10.0}])
            if request.url.path == TRANSACTIONS_PATH:
                status = request.url.params["status"]
                if status == "pending" and not isinstance(pending_status, int):
                    raise pending_status("pending feed down", request=request)
                if status == "pending" and pending_status != 200:
                    return httpx.Response(pending_status)
                return httpx.Response(200, json={"transactions": [
                    {"charge_date": "2026-01-02", "description": status, "amount": "1.00", "status": "other"},
                ]})
            return httpx.Response(404)

        async def run() -> AccountPayload:
            async with httpx.AsyncClient(base_url="https://amex.test", transport=httpx.MockTransport(handler)) as fake:
                return (await _collect_accounts(fake, "tok"))[0]

        return asyncio.run(run())

    answered = collect_with_pending(200)
    assert answered.pendingComplete is True
    assert [(t.description, t.status) for t in answered.transactions] == [("posted", "posted"), ("pending", "pending")]
    for failure in (429, 503, 404, httpx.ReadTimeout, httpx.ConnectError):
        failed = collect_with_pending(failure)
        assert failed.pendingComplete is False, failure
        assert [(t.description, t.status) for t in failed.transactions] == [("posted", "posted")]

    # Exception diagnostics retain only the type, never unsafe exception text.
    synthetic_secret = "private-page-text?token=secret-alert"
    error = RuntimeError(synthetic_secret)
    assert _safe_error_type(error) == "RuntimeError"
    assert synthetic_secret not in _safe_error_type(error)

    # Shape-summary diagnostics must never leak a value, only key names/types.
    secret = "super-secret-balance-value-999"
    summary = _shape_summary([{"total_balance_amount": secret, "nested": {"amount": secret}}])
    assert secret not in summary
    assert "total_balance_amount:str" in summary
    assert "nested:dict[amount]" in summary

    # Nonzero-flag diagnostics must never leak a value either -- a secret
    # numeric string is reduced to a nz/zero flag, never echoed back.
    secret_amount = "918273.45"
    entry_summary = _entry_diagnostic_summary(
        {"charges_amount": secret_amount, "extended_details": {"amount": secret_amount, "flag": True}}
    )
    assert secret_amount not in entry_summary
    assert "charges_amount:num:nz" in entry_summary
    assert "extended_details:dict[amount:num:nz,flag:bool:True]" in entry_summary

    # ─── Sidecar shared-secret enforcement ───────────────────────────────
    # /health is open; every other path is gated by X-Picsou-Sidecar-Key, and a
    # bad key answers 401 with the WWW-Authenticate challenge the backend's
    # SidecarWebClientFactory maps to SidecarAuthenticationException.
    global _SIDECAR_KEY
    _SIDECAR_KEY = None  # reset any cached value so the assertions set their own
    os.environ["APP_SIDECAR_API_KEY"] = "self-check-key"
    try:
        client = TestClient(app)
        health = client.get("/health")
        assert health.status_code == 200, health.status_code

        no_key = client.post("/accounts", json={"sessionState": "{}"})
        assert no_key.status_code == 401, no_key.status_code
        assert no_key.json() == {"detail": "invalid sidecar key"}
        assert no_key.headers["www-authenticate"] == "Picsou-Sidecar-Key"

        wrong_key = client.post(
            "/accounts", json={"sessionState": "{}"},
            headers={SIDECAR_KEY_HEADER: "wrong"},
        )
        assert wrong_key.status_code == 401, wrong_key.status_code
        assert wrong_key.headers["www-authenticate"] == "Picsou-Sidecar-Key"

        # /health must stay open even with a bad key -- it is the only hop the
        # backend (and a liveness probe) is allowed to make unauthenticated.
        health_wrong = client.get("/health", headers={SIDECAR_KEY_HEADER: "wrong"})
        assert health_wrong.status_code == 200, health_wrong.status_code

        right_key = client.post(
            "/accounts", json={"sessionState": "{}"},
            headers={SIDECAR_KEY_HEADER: "self-check-key"},
        )
        # A valid key passes the middleware; what reaches the endpoint is the
        # sessionState contract (400 INVALID_DATA), not the 401 auth rejection.
        assert right_key.status_code != 401, right_key.status_code
    finally:
        os.environ.pop("APP_SIDECAR_API_KEY", None)
        _SIDECAR_KEY = None

    # Without the env var at all, the first gated request must fail loudly
    # rather than silently defaulting to open -- same wording spirit as the
    # Java SidecarWebClientFactory's startup check.
    _SIDECAR_KEY = None
    os.environ.pop("APP_SIDECAR_API_KEY", None)
    try:
        _get_sidecar_key()
    except RuntimeError as exc:
        assert "APP_SIDECAR_API_KEY is required" in str(exc), str(exc)
    else:
        raise AssertionError("expected RuntimeError when APP_SIDECAR_API_KEY is unset")

    print("self-check OK")


if __name__ == "__main__":
    _self_check()
