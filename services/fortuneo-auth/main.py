"""Read-only Fortuneo authentication and portfolio sidecar.

Playwright drives login and MFA, runs authenticated API requests in the
provider page and crosses the provider's legacy-session bridge for securities
positions and history. Credentials, one-time codes, browser state and raw
financial responses are never logged.
"""

import asyncio
import json
import logging
import os
import re
import time
import uuid
from urllib.parse import urlencode
from contextlib import asynccontextmanager
from datetime import date, timedelta
from decimal import Decimal
from typing import Any
from typing import Literal
from urllib.parse import parse_qs

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
    Playwright,
    async_playwright,
)

from fortuneo_parser import (
    PortfolioFormatError,
    PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT,
    decimal_value,
    extract_accounts_from_equipment,
    fold_cash_pockets_into_securities_accounts,
    parse_equipment_multipart,
    attach_securities_isins,
    classify_securities_operation,
    fingerprint_securities_history,
    is_ignored_securities_operation,
    parse_securities_history,
    parse_securities_referential,
    parse_legacy_account_ids,
    parse_portfolio_positions,
    parse_portfolio_summary,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("fortuneo-auth")

# Provider endpoints used by the authenticated frontend.
BASE_URL = "https://mabanque.fortuneo.fr"
LOGIN_URL = f"{BASE_URL}/"
API_BASE_URL = "https://api.fortuneo.fr"
EQUIPMENT_GRAPHQL_URL = f"{API_BASE_URL}/account-items-bff/graphql"
# The list behind "Voir toutes les transactions". That button fires no request
# of its own -- the page already holds the whole list and renders only six of
# it -- so this single call is what decides how much history is reachable.
TRANSACTIONS_URL_TEMPLATE = f"{API_BASE_URL}/fto-transaction-api/v1/accounts/{{web_id}}/transactions"
# Fortuneo's cash account screen requests both booked (`CAV`) and pending
# entries. Picsou requests only CAV because its anchor balance is booked too;
# replaying pending entries against it would shift the reconstructed history.
# Anonymized response-shape checks confirmed that CAV returns entries beyond
# Picsou's former rolling window. It is a filter keyed on the product,
# so it is deliberately NOT sent for PEA/CTO: those have their own ledger and
# asking a securities account for its "compte a vue" entries is meaningless.
CASH_TRANSACTION_TYPES = "CAV"
CASH_ACCOUNT_TYPES = ("CHECKING", "SAVINGS")
# Fortuneo runs TWO frontends: the modern SPA (`/mon-espace`, OAuth/PKCE
# token auth, used for the api.fortuneo.fr calls above) and a legacy
# JSP site (`/fr/prive/...`, classic cookie-session auth) that users can
# still opt into. `ANav=1` ("ancienne navigation") is the legacy site's
# entry point. Per-position holdings only exist on the legacy side; the
# SPA's Equipment GraphQL has no position field at all.
#
# Landing here first is what puts the browser on the legacy frontend --
# every earlier attempt requested the situation URL while the browser was
# still on the SPA, and got the SPA's own shell (or its login form) back
# instead of legacy HTML. See docs/features/fortuneo.md "Verification
# boundaries" bugs 8-15.
#
# Crossing between them is an explicit handshake, not automatic: the SPA
# submits an *empty* form POST to /ssoacces, which 302-redirects to the
# legacy home page and mints the legacy session cookie on the way. Logging
# in via the SPA alone does not establish a legacy session.
SSO_ACCESS_PATH = "/ssoacces"
LEGACY_HOME_URL = f"{BASE_URL}/fr/prive/default.jsp?ANav=1"
LEGACY_PAGE_TIMEOUT_MS = 30_000
# `{segment}` comes from PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT; `ca` is the
# account's *legacy* id, scraped from the legacy home page's own links --
# NOT the Equipment API's id, which is a different identifier space
# entirely (see parse_legacy_account_ids). `iframe=true` asks for
# the bare embeddable fragment. Requested from the legacy frontend (after
# the /ssoacces handshake), this returns the complete server-rendered HTML
# containing every holding as a `<tr name="...princip">` row.
PORTFOLIO_URL_TEMPLATE = f"{BASE_URL}/fr/prive/mes-comptes/{{segment}}/situation/?ca={{ca}}&iframe=true"
# The legacy home page links to this route for securities accounts. The
# transaction API does not provide their ledger, so this page
# is the only source for their history, their dividends and their brokerage fees.
# The sibling "operations-sur-titres" page has neither the required form nor
# the history table; it is not used.
SECURITIES_HISTORY_URL_TEMPLATE = (
    f"{BASE_URL}/fr/prive/mes-comptes/{{segment}}/historique/historique-titres.jsp?ca={{ca}}"
)
# The legacy page accepts an arbitrary range. Starting before Fortuneo existed
# avoids imposing a client-side cutoff. The declared row count bounds normal
# pagination; an implausibly large value fails closed instead of causing an
# effectively unbounded request loop.
SECURITIES_HISTORY_START_DATE = date(1990, 1, 1)
HISTORY_PAGE_SIZE = 100
HISTORY_MAX_DECLARED_ROWS = 100_000

# Provider login form attributes are more stable than build-hashed CSS-module
# class names
# and rotate on every deploy, so they are deliberately not used here).
# The TrustCommander cookie-consent overlay intercepts clicks until dismissed.
COOKIE_CONSENT_ACCEPT_SELECTOR = "#popin_tc_privacy_button"
# After the /ssoacces handshake the legacy site can gate pages behind a
# MiFID "Créer votre profil investisseur" interstitial
# (a `front-authz` micro-frontend rendered into a <shell-event-bridge>
# element) offering a safe deferral action such as "Plus tard" next to the
# profile-creation action. Until the prompt is deferred, any
# legacy URL -- including the portfolio page -- returns the interstitial
# instead of its own content. Match accessible labels rather than tags or
# classnames: the micro-frontend has used buttons, links and nested frames,
# while its CSS-module classnames are build-hashed (`css-1d850bn`). The
# pattern contains deferral-only wording and must never match "Créer" or
# another action that submits profile information.
INVESTOR_PROFILE_DEFER_LABEL_PATTERN = re.compile(
    r"(?:plus\s+tard|pas\s+maintenant|ult(?:e|é)rieurement|me\s+le\s+rappeler)",
    re.IGNORECASE,
)
INVESTOR_PROFILE_MARKER_PATTERN = re.compile(
    r"profil(?:\s|&nbsp;|&#160;|<[^>]+>)+investisseur",
    re.IGNORECASE,
)
# The gate renders with its page, so a short wait is enough. It was raised to 8s
# while diagnosing why the click never fired (bug 20) -- back then "absent" and
# "present but unclickable" were indistinguishable. That is resolved, and the
# gate is absent on most loads, so a long wait is pure dead time every sync.
INVESTOR_PROFILE_TIMEOUT_MS = 2_000
# The valuation summary block -- present on every portfolio page including
# cash-only ones, absent from the login form / interstitial / SPA shell.
PORTFOLIO_SUMMARY_SELECTOR = "#valorisation_compte"
PORTFOLIO_RENDER_TIMEOUT_MS = 10_000
# The parsed holdings must add up to the page's own "Évaluation Titres"
# total. A euro of slack absorbs rounding across many rows while still
# catching the failure that matters: a table that never rendered, which
# reads as zero holdings against a non-zero total.
PORTFOLIO_RECONCILE_TOLERANCE_EUR = Decimal("1.00")
LOGIN_FIELD_SELECTORS = ['#LOGIN', 'input[name="LOGIN"]']
PASSWORD_FIELD_SELECTORS = ['#PASSWD', 'input[name="PASSWD"]']
SUBMIT_BUTTON_SELECTORS = [
    'button[type="submit"]:has-text("Connexion")',
    'button:has-text("Connexion")',
    'button[type="submit"]',
]
# The 2FA screen uses six individual single-digit inputs (no id/name, but a
# stable inputmode/pattern combination) and
# a "Suivant" submit button -- same one-input-per-digit shape as Bourse
# Direct's OTP screen (see services/bourse-direct-auth/main.py `complete()`).
OTP_DIGIT_INPUT_SELECTOR = 'input[inputmode="numeric"][pattern="[0-9]*"]'
OTP_SUBMIT_BUTTON_SELECTORS = [
    'button[type="submit"]:has-text("Suivant")',
    'button:has-text("Suivant")',
    'button[type="submit"]',
]
LOGIN_FORM_TIMEOUT_MS = 15_000
LOGIN_RESULT_TIMEOUT_SECONDS = 15
OTP_RESULT_TIMEOUT_SECONDS = 15

# `@defer` fragments arrive as a chunked multipart/mixed response,
# see fortuneo_parser.parse_equipment_multipart.
EQUIPMENT_GRAPHQL_QUERY = """
query Equipment {
  equipment {
    financialPortfolio {
      lifeInsurance {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      retirementSavingsPlan {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      shareSavingsPlan {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      ordinarySecurities {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      cash {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        __typename
      }
      mortgages {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { remainingCapital { amount currency __typename } __typename }
          __typename
        }
        __typename
      }
      external {
        label
        grouped
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        __typename
      }
      __typename
    }
    banking {
      current {
        label
        accounts {
          id
          label
          accountNumber
          ... @defer { overdraft __typename }
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      savings {
        label
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      external {
        label
        accounts {
          id
          label
          accountNumber
          type { label value __typename }
          ... @defer { associatedUsers { firstName lastName __typename } __typename }
          ... @defer { balance { amount currency __typename } __typename }
          pendingForFirstCredit
          __typename
        }
        grouped
        __typename
      }
      __typename
    }
    __typename
  }
}
"""

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
MFA_RESULT_TIMEOUT_SECONDS = 12
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
DATA_REQUEST_TIMEOUT_MS = 30_000

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        await _cleanup_expired()


@asynccontextmanager
async def lifespan(_: FastAPI):
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
                "Fortuneo request completed (path=%s; duration=%.2fs)",
                request.url.path,
                time.monotonic() - started_at,
            )


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    code: str = Field(pattern=r"^\d{6}$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
    symbol: str = Field(min_length=1, max_length=100)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    buyingPriceEur: Decimal | None = None
    currentPrice: Decimal | None = None
    quoteCurrency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    currentValueEur: Decimal
    pnlEur: Decimal | None = None


class TransactionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # Fortuneo's own stable per-transaction identifier. Optional because a
    # response shape without it must degrade to the legacy rolling-window
    # import rather than fail the whole snapshot; the backend only performs
    # a full-history idempotent import when every entry carries one.
    externalId: str | None = Field(default=None, min_length=1, max_length=100)
    date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    label: str = Field(min_length=1, max_length=255)
    amount: Decimal
    category: str | None = Field(default=None, max_length=100)
    # Fortuneo's own structured operation type. Enrichment, not identity: a
    # missing or oddly-shaped value degrades to None instead of failing a sync.
    type: str | None = Field(default=None, min_length=1, max_length=100)
    # Set only for securities-ledger rows, and only when the provider's own
    # operation label maps onto a Picsou type unambiguously. An unrecognised
    # operation is imported untyped rather than guessed at.
    txType: Literal["BUY", "SELL", "DIVIDEND", "FEE"] | None = None
    quantity: Decimal | None = None
    unitPrice: Decimal | None = None
    fees: Decimal | None = None
    # Only ever set from the provider's own label -> ISIN table, never inferred.
    isin: str | None = Field(default=None, min_length=12, max_length=12)

    @field_validator("date")
    @classmethod
    def date_must_be_a_real_iso_day(cls, value: str) -> str:
        try:
            date.fromisoformat(value)
        except ValueError as exc:
            raise ValueError("date must be a valid ISO calendar day") from exc
        return value


class AccountPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["PEA", "COMPTE_TITRES", "CHECKING", "SAVINGS"]
    balanceEur: Decimal
    cashBalance: Decimal
    positions: list[PositionPayload]
    transactions: list[TransactionPayload] = Field(default_factory=list)
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
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    fields = {
        str(error["loc"][-1])
        for error in exc.errors()
        if error.get("loc")
    }
    detail = "INVALID_OTP" if "code" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    playwright: Playwright | None,
) -> None:
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
        (playwright, "stop"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, close_method)(),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("Fortuneo browser resource cleanup failed", exc_info=True)


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    await _close_resources(
        state.get("context"),
        state.get("browser"),
        state.get("playwright"),
    )


async def _close_pending(process_id: str) -> None:
    async with _pending_lock:
        state = _pending.pop(process_id, None)
    if state:
        await _dispose_pending_state(state)


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, state in _pending.items() if state["created_at"] < cutoff]
    for pid in expired:
        await _close_pending(pid)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _first_visible(page: Page, selectors: list[str], timeout_ms: int = 800):
    """Return the first selector to become visible, actually waiting/polling
    up to `timeout_ms` per selector -- NOT `Locator.is_visible(timeout=...)`,
    which despite the parameter name does a single immediate check and
    never waits for an element that doesn't exist yet (confirmed against
    the real, client-side-rendered Fortuneo login page: the naive
    `is_visible(timeout=...)` version returned False instantly regardless
    of how large `timeout_ms` was).
    """
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            await locator.wait_for(state="visible", timeout=timeout_ms)
            return locator
        except Exception:
            continue
    return None


async def _dismiss_cookie_consent(page: Page) -> None:
    """Accept the TrustCommander cookie-consent overlay if it appears. A
    no-op (short timeout, swallowed) when it doesn't -- this runs on every
    page load and must never block a session where it's already dismissed.
    """
    try:
        button = page.locator(COOKIE_CONSENT_ACCEPT_SELECTOR).first
        await button.wait_for(state="visible", timeout=4_000)
        await button.click()
    except Exception:
        pass


async def _defer_investor_profile_gate(page: Page) -> bool:
    """Take a safe deferral action on the MiFID investor-profile gate.

    Deliberately declines rather than completes the profile: this connector
    is read-only, so it must never submit anything on the user's behalf --
    "Plus tard" only defers a prompt the user can still action themselves
    in the provider UI.

    Returns whether it clicked. Never raises: the interstitial is absent on
    most loads and its absence is the normal case, not an error.

    Fortuneo has rendered this micro-frontend with different element types
    and, in some sessions, inside a child frame. Search every current frame
    by accessible name and only accept wording that unambiguously postpones
    the profile. "Créer" and every other mutating action are intentionally
    excluded.

    A trusted click is preferred. `dispatch_event` remains the fallback for
    the React variant whose actionability checks reject a normal click.
    """
    deadline = time.monotonic() + INVESTOR_PROFILE_TIMEOUT_MS / 1_000
    action = None
    while action is None and time.monotonic() < deadline:
        for frame in page.frames:
            candidates = (
                frame.get_by_role("button", name=INVESTOR_PROFILE_DEFER_LABEL_PATTERN),
                frame.get_by_role("link", name=INVESTOR_PROFILE_DEFER_LABEL_PATTERN),
                frame.locator('[role="button"], [onclick]').filter(
                    has_text=INVESTOR_PROFILE_DEFER_LABEL_PATTERN
                ),
            )
            for candidate in candidates:
                try:
                    if await candidate.count() > 0:
                        action = candidate.first
                        break
                except PlaywrightError:
                    # A frame can disappear while the gate redirects itself.
                    continue
            if action is not None:
                break
        if action is None:
            await asyncio.sleep(0.1)

    if action is None:
        if await _investor_profile_gate_present(page):
            log.warning(
                "Fortuneo investor-profile gate found without a safe deferral action; "
                "manual profile handling is required"
            )
        return False

    # Present. From here on, failing to dismiss it is worth shouting about:
    # it is the difference between a complete portfolio and an unusable snapshot.
    try:
        await action.click(timeout=5_000)
        return True
    except PlaywrightError:
        log.warning(
            "Fortuneo investor-profile button present but not clickable; "
            "dispatching a click event directly"
        )
    try:
        await action.dispatch_event("click")
        return True
    except PlaywrightError:
        log.warning("Fortuneo investor-profile dismissal failed outright", exc_info=True)
        return False


def _investor_profile_gate_in_html(html: str) -> bool:
    """Detect the gate without depending on its generated DOM classes."""
    return bool(INVESTOR_PROFILE_MARKER_PATTERN.search(html))


async def _investor_profile_gate_present(page: Page) -> bool:
    for frame in page.frames:
        try:
            if _investor_profile_gate_in_html(await frame.content()):
                return True
        except PlaywrightError:
            continue
    return False


def _attach_client_id_capture(context: BrowserContext) -> dict[str, str | None]:
    """Capture the `client_id` field from the `/oauth-pkce/token` request the
    page's own JS makes on login success -- this is the same value later
    required as the `apikey` header on every api.fortuneo.fr data request
    (see docs/features/fortuneo.md "Discovery status"). Returns a holder
    dict the caller polls after the login navigation settles.
    """
    holder: dict[str, str | None] = {"value": None}

    def on_request(request) -> None:
        if request.method != "POST" or "/oauth-pkce/token" not in request.url:
            return
        post_data = request.post_data
        if not post_data:
            return
        try:
            client_id = parse_qs(post_data).get("client_id", [None])[0]
        except Exception:
            log.debug("Could not parse Fortuneo token request body", exc_info=True)
            return
        if client_id:
            holder["value"] = client_id

    context.on("request", on_request)
    return holder


_READ_SESSION_STORAGE_SCRIPT = """
() => {
    const out = {};
    for (let i = 0; i < sessionStorage.length; i++) {
        const key = sessionStorage.key(i);
        out[key] = sessionStorage.getItem(key);
    }
    return out;
}
"""

_WRITE_SESSION_STORAGE_SCRIPT = """
(data) => {
    for (const [key, value] of Object.entries(data)) {
        sessionStorage.setItem(key, value);
    }
}
"""


async def _read_session_storage(page: Page) -> dict[str, str]:
    """`context.storage_state()` captures cookies and localStorage only.

    The provider session also requires sessionStorage, which Playwright's
    storage_state never captures. SPAs commonly keep an API auth token there
    specifically because it's per-tab/session rather than persisted to
    disk -- read and carry it manually alongside storageState.
    """
    try:
        return await page.evaluate(_READ_SESSION_STORAGE_SCRIPT)
    except PlaywrightError:
        return {}


async def _restore_session_storage(page: Page, session_storage: dict[str, str]) -> None:
    if session_storage:
        await page.evaluate(_WRITE_SESSION_STORAGE_SCRIPT, session_storage)


def _session_state_json(
    storage_state: dict[str, Any],
    api_key: str,
    session_storage: dict[str, str],
) -> str:
    return json.dumps(
        {"storageState": storage_state, "apiKey": api_key, "sessionStorage": session_storage},
        separators=(",", ":"),
    )


DEBUG_ARTIFACT_DIR = "/tmp/fortuneo-debug"
DEBUG_ARTIFACTS_ENABLED = os.getenv("FORTUNEO_DEBUG_ARTIFACTS", "false").lower() == "true"


async def _capture_failure_diagnostics(page: Page, label: str) -> None:
    """Best-effort diagnostic capture on an unexpected outcome -- a
    screenshot and the page's visible text (UI copy only, never the code or
    credentials) saved to DEBUG_ARTIFACT_DIR for a maintainer to inspect via
    `docker cp`. Never raises -- this must not turn a real error into a
    different one.
    """
    if not DEBUG_ARTIFACTS_ENABLED:
        return
    try:
        os.makedirs(DEBUG_ARTIFACT_DIR, exist_ok=True)
        stamp = int(time.time())
        await page.screenshot(path=f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.png", full_page=True)
        text = await page.locator("body").inner_text()
        with open(f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.txt", "w") as f:
            f.write(f"url: {page.url}\n\n{text}")
        log.info("Fortuneo diagnostic capture saved: %s_%s.{png,txt}", label, stamp)
    except Exception:
        log.warning("Fortuneo diagnostic capture failed", exc_info=True)


def _save_debug_text(label: str, text: str) -> None:
    """Best-effort raw-text diagnostic dump for a page.evaluate()-fetched
    resource. There's no live page to screenshot for these -- the browser
    never navigates, only fetch()es -- so this is the fetch-based
    counterpart to `_capture_failure_diagnostics`'s DOM/screenshot capture.
    Never raises.
    """
    if not DEBUG_ARTIFACTS_ENABLED:
        return
    try:
        os.makedirs(DEBUG_ARTIFACT_DIR, exist_ok=True)
        stamp = int(time.time())
        with open(f"{DEBUG_ARTIFACT_DIR}/{label}_{stamp}.html", "w") as f:
            f.write(text)
        log.info("Fortuneo diagnostic capture saved: %s_%s.html", label, stamp)
    except Exception:
        log.warning("Fortuneo diagnostic capture failed", exc_info=True)


async def _otp_visible(page: Page) -> bool:
    try:
        return await page.locator(OTP_DIGIT_INPUT_SELECTOR).count() >= 6
    except PlaywrightError:
        return False


async def _wait_for_login_outcome(
    page: Page,
    client_id_holder: dict[str, str | None],
    timeout_seconds: int,
) -> str:
    """Poll for the two known post-submit outcomes. Returns "success",
    "otp" (the 2FA digit inputs appeared), or "timeout" (neither within the
    deadline -- most likely rejected credentials, but this sidecar has no
    confirmed selector for Fortuneo's error message to distinguish that
    from a slow page).

    Success is keyed off `client_id_holder` (populated by
    `_attach_client_id_capture` when the `/oauth-pkce/token` exchange
    fires) rather than the page URL: confirmed empirically that
    `/mon-espace` is the SPA shell's URL both *before* and *after* login
    (the login form itself renders at that same route), so URL alone can't
    distinguish the two.
    """
    for _ in range(timeout_seconds * 4):
        if client_id_holder["value"]:
            return "success"
        if await _otp_visible(page):
            return "otp"
        await asyncio.sleep(0.25)
    return "timeout"


async def _wait_for_token_exchange(
    client_id_holder: dict[str, str | None],
    timeout_seconds: int,
) -> bool:
    """Poll for the token exchange alone -- used after OTP submission.

    `_wait_for_login_outcome`'s "otp" check is wrong here because the provider
    keeps the six digit inputs (now filled, with
    the submit button showing a loading spinner) on screen while it
    verifies the code -- they don't disappear or get replaced until the
    request resolves. Reusing that check would make `/complete` give up after a
    single instant poll instead of waiting for the outcome, misreporting a request that
    was still in flight as INVALID_OTP.
    """
    for _ in range(timeout_seconds * 4):
        if client_id_holder["value"]:
            return True
        await asyncio.sleep(0.25)
    return False


async def _capture_api_key(
    client_id_holder: dict[str, str | None],
    timeout_seconds: float = 5,
) -> str:
    deadline = time.monotonic() + timeout_seconds
    while client_id_holder["value"] is None and time.monotonic() < deadline:
        await asyncio.sleep(0.1)
    if not client_id_holder["value"]:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    return client_id_holder["value"]


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(locale="fr-FR")
        client_id_holder = _attach_client_id_capture(context)
        page = await context.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        # The SPA renders the login form client-side after redirecting through
        # /mon-espace -- domcontentloaded fires well before it exists, so the
        # first lookup needs a much longer timeout than the short polling
        # checks used elsewhere (confirmed empirically: a real page needed
        # several seconds past domcontentloaded, not the default 800ms).
        login_field = await _first_visible(page, LOGIN_FIELD_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        password_field = await _first_visible(page, PASSWORD_FIELD_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        if login_field is None or password_field is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await _dismiss_cookie_consent(page)
        await login_field.fill(req.login)
        await password_field.fill(req.password)

        submit = await _first_visible(page, SUBMIT_BUTTON_SELECTORS, LOGIN_FORM_TIMEOUT_MS)
        if submit is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await submit.click()

        outcome = await _wait_for_login_outcome(page, client_id_holder, LOGIN_RESULT_TIMEOUT_SECONDS)

        if outcome == "otp":
            async with _pending_lock:
                _pending[process_id] = {
                    "page": page,
                    "context": context,
                    "browser": browser,
                    "playwright": pw,
                    "client_id_holder": client_id_holder,
                    "created_at": time.time(),
                }
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "OTP",
                "sessionState": None,
            }

        if outcome == "success":
            api_key = await _capture_api_key(client_id_holder)
            session_storage = await _read_session_storage(page)
            storage_state = await context.storage_state()
            session_state = _session_state_json(storage_state, api_key, session_storage)
            await _close_resources(context, browser, pw)
            return {
                "processId": None,
                "mfaRequired": False,
                "mfaType": None,
                "sessionState": session_state,
            }

        # "timeout": neither the dashboard nor the OTP screen appeared. That
        # is not proof that Fortuneo rejected valid credentials, so report an
        # upstream availability failure and let the user retry honestly.
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    except HTTPException:
        # Both success paths above return before this point, so a stored
        # pending context never reaches this handler.
        await _close_resources(context, browser, pw)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, pw)
        log.warning("Fortuneo authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, pw)
        log.exception("Unexpected Fortuneo authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


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
    client_id_holder: dict[str, str | None] = state["client_id_holder"]
    try:
        inputs = page.locator(OTP_DIGIT_INPUT_SELECTOR)
        if await inputs.count() < 6:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        # `.fill()` sets the value directly and dispatches a single input
        # event -- many auto-advancing digit-box widgets key their state off
        # real keydown/keyup events instead, so `.fill()` can silently leave
        # the component's internal state empty even though the DOM value
        # looks filled. Explicitly click each box (avoids relying on
        # auto-advance targeting the right field) then send a real keypress.
        for index, digit in enumerate(req.code):
            await inputs.nth(index).click()
            await page.keyboard.press(digit)
            await asyncio.sleep(0.05)

        submit = await _first_visible(page, OTP_SUBMIT_BUTTON_SELECTORS, 5_000)
        if submit is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await submit.click()

        succeeded = await _wait_for_token_exchange(client_id_holder, OTP_RESULT_TIMEOUT_SECONDS)
        if not succeeded:
            await _capture_failure_diagnostics(page, "otp_failure")
            raise HTTPException(status_code=401, detail="INVALID_OTP")

        api_key = await _capture_api_key(client_id_holder)
        # The SPA may keep client-side-redirecting after the token exchange
        # fires (e.g. login page -> dashboard). Capturing storage_state()/
        # sessionStorage immediately on client_id risks a mid-transition
        # snapshot missing a cookie or key the dashboard only sets once
        # that redirect settles. Give it the same grace `/accounts` does.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        session_storage = await _read_session_storage(page)
        storage_state = await context.storage_state()
        return {"sessionState": _session_state_json(storage_state, api_key, session_storage)}
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Fortuneo authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Fortuneo authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _dispose_pending_state(state)


def _parse_session_state(raw: str) -> tuple[dict[str, Any], str, dict[str, str]]:
    """Split the sessionState envelope into Playwright storage state, apikey,
    and sessionStorage.

    The envelope is `{"storageState": {...}, "apiKey": "...",
    "sessionStorage": {...}}` -- see `_read_session_storage` for why
    sessionStorage travels separately from storageState.
    """
    try:
        envelope = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(envelope, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    storage_state = envelope.get("storageState")
    api_key = envelope.get("apiKey")
    session_storage = envelope.get("sessionStorage") or {}
    if (
        not isinstance(storage_state, dict)
        or not isinstance(api_key, str) or not api_key
        or not isinstance(session_storage, dict)
    ):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return storage_state, api_key, session_storage


def _api_headers(api_key: str, accept: str = "application/json") -> dict[str, str]:
    return {"apikey": api_key, "accept": accept, "content-type": "application/json"}


# Calling account-items-bff/graphql via Playwright's `context.request` (a
# Node-side HTTP client that shares cookies but never runs page JS) does not
# carry the SPA's complete authentication context. The provider cookies (`EncFF`,
# `FORTUNEO_HTTP`) are scoped to `mabanque.fortuneo.fr` specifically and
# never reach a different subdomain -- only tracking/consent cookies are
# shared at the `.fortuneo.fr` parent-domain level. The SPA must be
# attaching the credential api.fortuneo.fr needs (most likely
# read from the `in-session` localStorage entry captured alongside the
# cookies) via its own JS, either a global fetch/XHR wrapper or a service
# worker -- neither of which `context.request` goes through. Running the
# fetch from inside the page's own JS context via `page.evaluate()` instead
# picks up whatever that mechanism is automatically, without needing to
# reverse-engineer it.
# Decodes the body itself instead of trusting `response.text()`. The legacy
# JSP pages serve ISO-8859-1 bytes while declaring nothing (or UTF-8), so
# `text()` turns every accented character -- and the U+00A0 that groups
# thousands -- into U+FFFD. That corrupts the operation labels the securities
# history is classified on, and makes its amounts unparseable. Valid UTF-8 is
# decoded as UTF-8 and comes back byte-identical, so the JSON API calls that
# share this helper are unaffected; only a body that is *not* valid UTF-8 falls
# back to windows-1252 (a superset of ISO-8859-1).
_PAGE_FETCH_SCRIPT = """
async ([url, method, headers, body, timeoutMs]) => {
    try {
        const response = await fetch(url, {
            method,
            headers,
            body: body ?? undefined,
            credentials: 'include',
            signal: AbortSignal.timeout(timeoutMs),
        });
        const buffer = await response.arrayBuffer();
        let text;
        try {
            text = new TextDecoder('utf-8', { fatal: true }).decode(buffer);
        } catch (_) {
            text = new TextDecoder('windows-1252').decode(buffer);
        }
        return { status: response.status, text };
    } catch (e) {
        return { status: 0, text: String(e) };
    }
}
"""


async def _page_fetch(
    page: Page,
    url: str,
    method: str,
    headers: dict[str, str],
    body: str | None,
) -> dict[str, Any]:
    args = [url, method, headers, body, DATA_REQUEST_TIMEOUT_MS]
    try:
        return await page.evaluate(_PAGE_FETCH_SCRIPT, args)
    except PlaywrightError as exc:
        # A stray client-side navigation can destroy the JS execution
        # context out from under an in-flight evaluate(); one retry after a short
        # settle is cheap insurance against that specific race.
        if "Execution context was destroyed" not in str(exc):
            raise
        log.warning("Fortuneo page.evaluate lost its context; retrying once")
        await asyncio.sleep(1)
        return await page.evaluate(_PAGE_FETCH_SCRIPT, args)


# The legacy page expects an iframe request with the legacy home page as its
# referrer. This is kept as a fallback for plain navigation. It deliberately
# does no gate handling:
# dismissing the interstitial navigates the top-level page and destroys the
# iframe mid-call (bug 21), so the caller clears the gate first.
_INJECT_IFRAME_SCRIPT = """
([url, name]) => {
    const iframe = document.createElement('iframe');
    iframe.name = name;
    iframe.style.position = 'fixed';
    iframe.style.left = '-10000px';
    iframe.style.top = '0';
    iframe.style.width = '1280px';
    iframe.style.height = '900px';
    iframe.style.border = '0';
    iframe.src = url;
    document.body.appendChild(iframe);
}
"""


async def _fetch_via_iframe(page: Page, url: str, timeout_ms: int = 20_000) -> str:
    """Load `url` in a same-origin iframe and return its settled HTML.
    """
    frame_name = f"picsou-fetch-{uuid.uuid4().hex}"
    await page.evaluate(_INJECT_IFRAME_SCRIPT, [url, frame_name])

    frame = None
    deadline = time.monotonic() + (timeout_ms / 1000)
    while time.monotonic() < deadline:
        frame = next((f for f in page.frames if f.name == frame_name), None)
        if frame is not None:
            break
        await asyncio.sleep(0.1)
    if frame is None:
        log.warning("Fortuneo portfolio iframe never attached")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

    try:
        await frame.wait_for_load_state("networkidle", timeout=timeout_ms)
    except PlaywrightError:
        pass
    # Wait for the valuation summary, NOT for a holding row. A cash-only
    # securities account has no rows, so a row-specific wait would run to its
    # timeout. The summary block is on every portfolio page, populated or not,
    # and is what the snapshot is actually validated against.
    try:
        await frame.locator(PORTFOLIO_SUMMARY_SELECTOR).first.wait_for(
            state="attached", timeout=PORTFOLIO_RENDER_TIMEOUT_MS
        )
    except PlaywrightError:
        pass
    return await frame.content()


async def _load_legacy_page(page: Page, url: str) -> str:
    """Navigate `page` (already on the legacy frontend) to `url` and return
    the settled HTML, clearing the investor-profile gate if it appears.

    Deferring that gate navigates the page itself, so the requested URL has
    to be asked for again afterwards -- hence the single retry. This is
    also why the fetch is a plain navigation instead of an <iframe>: the
    same navigation destroys an iframe and its execution context mid-call.
    """
    for attempt in range(2):
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=LEGACY_PAGE_TIMEOUT_MS)
        except PlaywrightError as exc:
            log.warning("Fortuneo legacy page navigation failed", exc_info=True)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass

        if attempt == 0 and await _defer_investor_profile_gate(page):
            log.info("Fortuneo investor-profile interstitial deferred; re-requesting the page")
            try:
                await page.wait_for_load_state("networkidle", timeout=15_000)
            except PlaywrightError:
                pass
            continue

        try:
            return await page.content()
        except PlaywrightError as exc:
            if "navigating and changing the content" not in str(exc):
                raise
            log.warning("Fortuneo legacy page still navigating; retrying content() once")
            await asyncio.sleep(1)
            return await page.content()
    # Unreachable: the gate can only be deferred once, so the second pass
    # always falls through to the return above.
    raise AssertionError("legacy page load fell out of its retry loop")


async def _fetch_equipment(page: Page, api_key: str) -> dict[str, Any]:
    result = await _page_fetch(
        page,
        EQUIPMENT_GRAPHQL_URL,
        "POST",
        _api_headers(api_key, "multipart/mixed;deferSpec=20220824,application/json"),
        json.dumps({"query": EQUIPMENT_GRAPHQL_QUERY}),
    )
    status = result["status"]
    if status in (401, 403):
        log.warning("Fortuneo Equipment query rejected the session (status=%s)", status)
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if status == 0 or status >= 400:
        log.warning("Fortuneo Equipment query failed (status=%s)", status)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    return parse_equipment_multipart(result["text"])


def _transaction_type(raw: Any) -> str | None:
    """Normalizes Fortuneo's structured operation type, or None when unusable.

    Deliberately lenient where `_transaction_external_id` is strict: the type is
    enrichment used to classify an entry, not the key the whole import is keyed
    on, so an unexpected shape must not cost the user a sync.
    """
    if raw is None or isinstance(raw, bool) or not isinstance(raw, (str, int)):
        return None
    operation_type = str(raw).strip()
    if not operation_type or len(operation_type) > 100:
        return None
    return operation_type


def _transaction_external_id(raw: Any, index: int) -> str | None:
    """Normalizes Fortuneo's per-transaction identifier, or None when absent.

    Only scalar identifiers are accepted: a nested object here would mean the
    payload changed shape, and silently dropping it would downgrade the backend
    to the rolling-window import without anyone noticing.
    """
    if raw is None:
        return None
    if isinstance(raw, bool) or not isinstance(raw, (str, int)):
        log.warning(
            "Fortuneo transaction id had type=%s instead of a scalar (index=%s)",
            type(raw).__name__,
            index,
        )
        raise HTTPException(status_code=502, detail="INVALID_DATA")
    external_id = str(raw).strip()
    if not external_id or len(external_id) > 100:
        log.warning(
            "Fortuneo transaction id was blank or oversized (index=%s; length=%s)",
            index,
            len(external_id),
        )
        raise HTTPException(status_code=502, detail="INVALID_DATA")
    return external_id


def _transactions_url(web_id: str, account_type: str) -> str:
    """Builds the transaction request for one account.

    `metadata=true` is what populates the category block, and is harmless on any
    product. The product filter is only sent where it means something -- see
    CASH_TRANSACTION_TYPES.
    """
    url = f"{TRANSACTIONS_URL_TEMPLATE.format(web_id=web_id)}?metadata=true"
    if account_type in CASH_ACCOUNT_TYPES:
        url = f"{url}&transactionType={CASH_TRANSACTION_TYPES}"
    return url


async def _fetch_transactions(
    page: Page,
    api_key: str,
    web_id: str,
    account_type: str,
) -> list[dict[str, Any]]:
    result = await _page_fetch(
        page,
        _transactions_url(web_id, account_type),
        "GET",
        _api_headers(api_key),
        None,
    )
    status = result["status"]
    if status in (401, 403):
        log.warning("Fortuneo transactions request rejected the session (status=%s)", status)
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if status == 0 or status >= 400:
        log.warning("Fortuneo transactions request failed (status=%s)", status)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    try:
        raw = json.loads(result["text"])
    except json.JSONDecodeError as exc:
        log.warning("Fortuneo transactions payload was not valid JSON")
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    if not isinstance(raw, list):
        log.warning(
            "Fortuneo transactions payload root had type=%s instead of list",
            type(raw).__name__,
        )
        raise HTTPException(status_code=502, detail="INVALID_DATA")

    transactions: list[dict[str, Any]] = []
    for index, entry in enumerate(raw):
        if not isinstance(entry, dict):
            log.warning(
                "Fortuneo transaction entry had type=%s instead of object (index=%s)",
                type(entry).__name__,
                index,
            )
            raise HTTPException(status_code=502, detail="INVALID_DATA")
        amount_data = entry.get("amount") or {}
        label_data = entry.get("label") or {}
        metadata = entry.get("metadata") or {}
        if not all(isinstance(value, dict) for value in (amount_data, label_data, metadata)):
            log.warning(
                "Fortuneo transaction nested fields changed shape "
                "(index=%s; amount=%s; label=%s; metadata=%s)",
                index,
                type(amount_data).__name__,
                type(label_data).__name__,
                type(metadata).__name__,
            )
            raise HTTPException(status_code=502, detail="INVALID_DATA")
        category = metadata.get("category") or {}
        if not isinstance(category, dict):
            log.warning(
                "Fortuneo transaction category had type=%s instead of object (index=%s)",
                type(category).__name__,
                index,
            )
            raise HTTPException(status_code=502, detail="INVALID_DATA")
        # Settled transactions expose `bookingDate`; alternate responses may
        # use `transactionDate`. Prefer the accounting date when both exist.
        booking_date = entry.get("bookingDate") or entry.get("transactionDate")
        external_id = _transaction_external_id(entry.get("id"), index)
        operation_type = _transaction_type(entry.get("type"))
        amount = amount_data.get("value")
        label = label_data.get("simplifiedLabel") or label_data.get("originalLabel")
        if not booking_date or amount is None or not label:
            log.warning(
                "Fortuneo transaction omitted required fields "
                "(index=%s; date=%s; amount=%s; label=%s)",
                index,
                bool(booking_date),
                amount is not None,
                bool(label),
            )
            raise HTTPException(status_code=502, detail="INVALID_DATA")
        transactions.append({
            "externalId": external_id,
            "date": str(booking_date)[:10],
            "label": label,
            "amount": decimal_value(amount, "transaction amount"),
            "category": category.get("label"),
            "type": operation_type,
        })
    return transactions


# Submitted from a same-origin SPA document as a form POST (not fetch()), so
# the request is a top-level
# navigation carrying the SPA origin/referer, with an empty body -- the
# handshake authenticates purely on the cookies already in the jar.
# `form.submit()` is deferred a tick so this evaluate() can return before
# the navigation it triggers tears the execution context down -- submitting
# inline can race its own result and surface as "Execution context was
# destroyed", the same failure mode `_page_fetch` guards
# against.
_SSO_SUBMIT_SCRIPT = f"""
() => {{
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '{SSO_ACCESS_PATH}';
    document.body.appendChild(form);
    setTimeout(() => form.submit(), 0);
}}
"""


async def _open_legacy_frontend(context: BrowserContext) -> tuple[Page, dict[str, str]]:
    """Open a page sitting on Fortuneo's legacy JSP frontend.

    Positions live only on the legacy site, and reaching it requires the
    /ssoacces handshake -- an SPA login alone leaves the legacy session
    unestablished. So: land on the
    SPA origin, submit the empty form POST, follow its 302 to the legacy
    home page.

    Uses its own page rather than reusing the SPA one, so this navigation
    can't disturb the JS context the Equipment/transaction fetches run in.

    Returns the page together with the legacy `ca` id of every account,
    read from the home page's own links -- the legacy site keys accounts
    by an identifier the modern Equipment API never exposes (see
    `parse_legacy_account_ids`).
    """
    page = await context.new_page()
    try:
        await page.goto(
            f"{BASE_URL}/mon-espace", wait_until="domcontentloaded", timeout=LEGACY_PAGE_TIMEOUT_MS
        )
        # The SPA client-side-redirects shortly after domcontentloaded,
        # which destroys an evaluate() raced against it -- the same settle
        # the main /accounts page does before running any JS.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        await page.evaluate(_SSO_SUBMIT_SCRIPT)
        # The POST 302s to LEGACY_HOME_URL; wait for that landing rather
        # than a fixed sleep.
        await page.wait_for_url("**/fr/prive/**", timeout=LEGACY_PAGE_TIMEOUT_MS)
    except PlaywrightError as exc:
        log.warning("Fortuneo legacy frontend handshake failed", exc_info=True)
        await _capture_failure_diagnostics(page, "legacy_sso_failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    try:
        await page.wait_for_load_state("networkidle", timeout=15_000)
    except PlaywrightError:
        pass
    if await _first_visible(page, LOGIN_FIELD_SELECTORS, 800) is not None:
        await _capture_failure_diagnostics(page, "legacy_home_login_bounce")
        log.warning("Fortuneo legacy frontend bounced to the login form")
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    # Clear the investor-profile gate once, here, rather than per account
    # page: it blocks every legacy URL until deferred, so dismissing it on
    # the way in means the portfolio fetches that follow get real content.
    if await _defer_investor_profile_gate(page):
        log.info("Fortuneo investor-profile interstitial deferred")
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass

    legacy_ids = parse_legacy_account_ids(await page.content())
    if not legacy_ids:
        await _capture_failure_diagnostics(page, "legacy_home_no_account_links")
        log.warning("Fortuneo legacy home page exposed no account links")
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    log.info("Fortuneo legacy account mapping discovered")
    return page, legacy_ids


async def _fetch_securities_history(
    page: Page,
    segment: str,
    legacy_ca: str,
) -> list[dict[str, Any]]:
    """Read a securities account's whole cash ledger from the legacy site.

    The transaction API answers PEA and CTO with an empty list whatever
    parameters it is given, so this page is the only source for their ledger --
    and the only one carrying brokerage fees or reaching past the API's ~13-month
    retention.

    Pagination stops on the provider's own `nbResultatsTotal`, so a short read is
    detectable rather than silently accepted: a page count that never reaches the
    declared total returns nothing at all, leaving the previous import in place,
    rather than handing the backend a partial ledger to reconcile against.
    """
    url = SECURITIES_HISTORY_URL_TEMPLATE.format(segment=segment, ca=legacy_ca)
    today = date.today()
    since = SECURITIES_HISTORY_START_DATE.strftime("%d/%m/%Y")
    until = today.strftime("%d/%m/%Y")
    operations: list[dict[str, Any]] = []
    declared_total: int | None = None
    referential: dict[str, str] = {}
    offset = 0

    while True:
        result = await _page_fetch(
            page,
            url,
            "POST",
            {"content-type": "application/x-www-form-urlencoded"},
            urlencode({
                "dateDebut": since,
                "dateFin": until,
                "offset": str(offset),
                "nbResultats": str(HISTORY_PAGE_SIZE),
                "typeOperation": "",
                "codeReferentiel": "",
            }),
        )
        status = result["status"]
        if status in (401, 403):
            log.warning("Fortuneo history request rejected the session")
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
        if status == 0 or status >= 400:
            log.warning("Fortuneo history request failed (status=%s)", status)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        html = result["text"]
        if _is_login_page(html):
            log.warning("Fortuneo history page bounced to the login form")
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
        total_match = re.search(
            r'name="nbResultatsTotal"[^>]*value="(\d{1,9})"', html, flags=re.IGNORECASE
        )
        if not total_match:
            log.warning("Fortuneo history page declared no total")
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        page_total = int(total_match.group(1))
        if page_total > HISTORY_MAX_DECLARED_ROWS:
            log.warning("Fortuneo history declared an implausibly large row count")
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        if declared_total is None:
            declared_total = page_total
        elif page_total != declared_total:
            log.warning("Fortuneo history total changed during pagination")
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        if not referential:
            referential = parse_securities_referential(html)
        try:
            page_rows = parse_securities_history(html)
        except PortfolioFormatError as exc:
            log.warning("Fortuneo history page did not parse")
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc
        if not page_rows:
            if offset < declared_total:
                log.warning("Fortuneo history pagination stopped before the declared total")
                raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
            break
        operations.extend(page_rows)
        offset += len(page_rows)
        if offset >= declared_total:
            break

    if len(operations) != declared_total:
        log.warning("Fortuneo history row count did not match the declared total")
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")

    identified, _ = attach_securities_isins(operations, referential)
    transactions = _securities_history_transactions(identified)
    log.info("Fortuneo securities ledger imported")
    return transactions


def _securities_history_transactions(
    operations: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Turn parsed history rows into transaction payloads.

    The amount is the provider's net figure -- what actually moved on the cash
    account, fees already applied -- falling back to the gross one when a row
    carries no net. Its sign is the provider's; nothing here re-signs an amount,
    because a guessed direction would be indistinguishable from provider data.

    The ISIN comes from the page's own `codeReferentiel` filter, which lists one
    option per security the account has traded with the ISIN in its value and the
    history rows' own label as its text. Matching is exact on that label: the
    mapping is the provider's, not a similarity guess. A row the referential does
    not know keeps no instrument at all, which merely leaves it out of the
    realized-P&L stream -- where a wrong instrument would corrupt it.
    """
    transactions: list[dict[str, Any]] = []
    for operation in fingerprint_securities_history(operations):
        if is_ignored_securities_operation(operation["operation"]):
            continue
        amount = operation["netAmount"]
        if amount is None:
            amount = operation["grossAmount"]
        if amount is None:
            continue
        transactions.append({
            "externalId": operation["externalId"],
            "date": operation["date"],
            "label": operation["label"] or operation["operation"],
            "amount": amount,
            "category": None,
            "type": operation["operation"],
            "txType": classify_securities_operation(operation["operation"]),
            "quantity": operation["quantity"],
            "unitPrice": operation["unitPrice"],
            "fees": operation["fees"],
            "isin": operation.get("isin"),
        })
    return transactions


async def _transactions_for_account(
    page: Page,
    api_key: str,
    account: dict[str, Any],
    securities_ledger: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """Select the only transaction source that can answer for this product."""
    if account["type"] in ("PEA", "COMPTE_TITRES"):
        return securities_ledger
    return await _fetch_transactions(page, api_key, account["webId"], account["type"])


def _is_login_page(html: str) -> bool:
    return 'name="LOGIN"' in html or 'id="LOGIN"' in html


def _portfolio_snapshot(html: str) -> dict[str, Any] | None:
    """Parse a portfolio page into one internally consistent snapshot.

    The page's summary table is the authority here, not the row list. It
    settles the one question the rows alone cannot answer: an account that
    genuinely holds nothing and a page whose table failed to render both
    parse to zero rows, and reporting the latter as "100% cash" is exactly
    the silent-wrong-data failure this connector exists to refuse. So the
    parsed holdings must reconcile against the page's own "Évaluation
    Titres" total. The summary also yields "Solde espèces EUR", so a PEA's
    cash is read rather than derived by subtraction.

    Parses each document once even when callers try several fetch strategies.
    """
    summary = parse_portfolio_summary(html)
    securities, cash, account_total = (
        summary["securitiesEur"],
        summary["cashEur"],
        summary["totalEur"],
    )
    if securities is None or cash is None or account_total is None:
        return None
    positions = parse_portfolio_positions(html)
    total = sum((p["currentValueEur"] for p in positions), start=Decimal("0"))
    if abs(total - securities) > PORTFOLIO_RECONCILE_TOLERANCE_EUR:
        return None
    if abs(account_total - securities - cash) > PORTFOLIO_RECONCILE_TOLERANCE_EUR:
        return None
    return {
        "positions": positions,
        "cashEur": cash,
        "securitiesEur": securities,
        "totalEur": account_total,
    }


async def _fetch_positions(page: Page, segment: str, legacy_ca: str) -> dict[str, Any]:
    """Fetch a securities account's holdings and its own valuation summary.

    `page` must already be on the legacy frontend, past the /ssoacces
    handshake -- see `_open_legacy_frontend`.

    The iframe is tried first: it is the shape Fortuneo's own UI uses for
    this page and the reliable path for the complete document. The
    navigation is kept solely as a second attempt, because it is the one
    thing that *can* clear the investor-profile interstitial: dismissing
    that gate navigates the top-level page, which destroys an iframe
    mid-call. So on failure: navigate (clearing the gate if it was up),
    take that result if it is usable, otherwise return to the legacy home
    and retry the iframe now that nothing is blocking it.
    """
    url = PORTFOLIO_URL_TEMPLATE.format(segment=segment, ca=legacy_ca)

    html = await _fetch_via_iframe(page, url)
    snapshot = _portfolio_snapshot(html)

    if snapshot is None and not _is_login_page(html):
        log.info(
            "Fortuneo portfolio iframe gave no usable snapshot; retrying via navigation"
        )
        html = await _load_legacy_page(page, url)
        snapshot = _portfolio_snapshot(html)

        if snapshot is None and not _is_login_page(html):
            log.info("Fortuneo retrying the portfolio iframe after navigation")
            try:
                await page.goto(LEGACY_HOME_URL, wait_until="domcontentloaded",
                                timeout=LEGACY_PAGE_TIMEOUT_MS)
                await page.wait_for_load_state("networkidle", timeout=15_000)
            except PlaywrightError:
                pass
            html = await _fetch_via_iframe(page, url)
            snapshot = _portfolio_snapshot(html)

    if _is_login_page(html):
        _save_debug_text(f"positions_login_bounce_{segment}", html)
        log.warning("Fortuneo portfolio page was bounced to the login form")
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

    if snapshot is None:
        _save_debug_text(f"positions_unusable_{segment}", html)
        if _investor_profile_gate_in_html(html):
            log.warning(
                "Fortuneo investor-profile interstitial still blocks the portfolio"
            )
            raise HTTPException(status_code=409, detail="INVESTOR_PROFILE_REQUIRED")
        if parse_portfolio_summary(html)["securitiesEur"] is None:
            raise PortfolioFormatError("the portfolio page has no valuation summary")
        raise PortfolioFormatError(
            "parsed holdings do not match the page's own securities total"
        )

    return snapshot


def _account_name(account: dict[str, Any]) -> str:
    type_label = account.get("typeLabel")
    account_number = account.get("accountNumber")
    if type_label and account_number:
        return f"{type_label} {account_number}"
    return type_label or account.get("label") or "Compte Fortuneo"


@app.post("/accounts", response_model=list[AccountPayload])
async def accounts(req: AccountsRequest) -> list[AccountPayload]:
    storage_state, api_key, session_storage = _parse_session_state(req.sessionState)

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    page: Page | None = None
    try:
        pw = await async_playwright().start()
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(storage_state=storage_state, locale="fr-FR")
        page = await context.new_page()
        # A same-origin page context is required before evaluate()-driven
        # fetch() calls to api.fortuneo.fr can pick up whatever the SPA
        # attaches for cross-subdomain auth (see _page_fetch docstring) --
        # an unnavigated page has no origin to run that JS from.
        await page.goto(f"{BASE_URL}/", wait_until="domcontentloaded", timeout=30_000)
        # The SPA may redirect again shortly after domcontentloaded, destroying
        # an in-flight evaluate() call raced against it ("Execution context
        # was destroyed"). Let that settle before running any JS.
        try:
            await page.wait_for_load_state("networkidle", timeout=15_000)
        except PlaywrightError:
            pass
        # Cookies and localStorage alone are insufficient. sessionStorage is the
        # piece storage_state() never captures; restore it before
        # making any API call.
        await _restore_session_storage(page, session_storage)

        equipment = await _fetch_equipment(page, api_key)
        raw_accounts = extract_accounts_from_equipment(equipment)
        if not raw_accounts:
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        folded_accounts = fold_cash_pockets_into_securities_accounts(raw_accounts)

        payloads: list[AccountPayload] = []
        # Opened lazily on the first securities account -- a cash-only
        # customer never needs the legacy frontend at all.
        legacy_page: Page | None = None
        legacy_ids: dict[str, str] = {}
        for account in folded_accounts:
            if account["balanceEur"] is None:
                raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
            if not account["webId"]:
                raise HTTPException(status_code=502, detail="INVALID_DATA")

            positions: list[dict[str, Any]] = []
            securities_ledger: list[dict[str, Any]] = []
            if account["type"] in ("PEA", "COMPTE_TITRES"):
                segment = PRODUCT_TYPE_TO_PORTFOLIO_SEGMENT.get(account["productType"])
                if segment is None:
                    raise HTTPException(status_code=502, detail="INVALID_DATA")
                if legacy_page is None:
                    legacy_page, legacy_ids = await _open_legacy_frontend(context)
                legacy_ca = legacy_ids.get(segment)
                if legacy_ca is None:
                    log.warning("Fortuneo legacy home page lacked a required account link")
                    raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
                snapshot = await _fetch_positions(legacy_page, segment, legacy_ca)
                securities_ledger = await _fetch_securities_history(
                    legacy_page, segment, legacy_ca
                )
                positions = snapshot["positions"]
                # The portfolio page reports its own cash ("Solde espèces
                # EUR"), from the same snapshot as the holdings -- so it is
                # both authoritative and internally consistent with them.
                # Preferred over the Equipment cash pocket (CTO) or
                # subtracting holdings from the total (PEA), which were
                # only ever approximations of this number.
                equipment_balance = account["balanceEur"]
                pocket_cash = account["cashBalance"]
                account["balanceEur"] = snapshot["totalEur"]
                account["cashBalance"] = snapshot["cashEur"]
                if (
                    abs(equipment_balance - snapshot["totalEur"])
                    > PORTFOLIO_RECONCILE_TOLERANCE_EUR
                ):
                    log.warning(
                        "Fortuneo securities total moved between Equipment and the "
                        "portfolio page; using the internally consistent portfolio snapshot"
                    )
                if (
                    pocket_cash is not None
                    and abs(pocket_cash - snapshot["cashEur"]) > PORTFOLIO_RECONCILE_TOLERANCE_EUR
                ):
                    log.warning(
                        "Fortuneo cash disagrees between the Equipment pocket and the "
                        "portfolio page; using the portfolio page"
                    )

            if account["cashBalance"] is None:
                raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")

            # Securities accounts use the legacy ledger because the transaction
            # API does not provide one for those products. An empty legacy
            # ledger must not fall through to an API that cannot answer for them.
            transactions = await _transactions_for_account(
                page, api_key, account, securities_ledger
            )
            payload = AccountPayload.model_validate({
                "externalId": account["webId"],
                "name": _account_name(account),
                "type": account["type"],
                "balanceEur": account["balanceEur"],
                "cashBalance": account["cashBalance"],
                "positions": positions,
                "transactions": transactions,
                "snapshotComplete": True,
            })
            transaction_cutoff = date.today() - timedelta(days=90)
            older_than_window = sum(
                1
                for transaction in payload.transactions
                if date.fromisoformat(transaction.date) < transaction_cutoff
            )
            dividend_like = sum(
                1
                for transaction in payload.transactions
                if re.match(
                    r"^(?:TNC\s+)?Div\b",
                    transaction.label.strip(),
                    flags=re.IGNORECASE,
                )
            )
            log.info(
                "Fortuneo transaction feed summary "
                "(account_type=%s; count=%s; older_than_90_days=%s; dividend_like=%s)",
                account["type"],
                len(payload.transactions),
                older_than_window,
                dividend_like,
            )
            payloads.append(payload)

        return payloads
    except HTTPException as exc:
        # SESSION_EXPIRED here means the restored session was rejected --
        # capture what the page actually shows (still a login form? an
        # interstitial?) since that's otherwise invisible once the browser
        # closes in `finally`.
        if exc.status_code == 401 and page is not None:
            await _capture_failure_diagnostics(page, "accounts_session_expired")
        raise
    except (PortfolioFormatError, ValidationError) as exc:
        log.warning("Fortuneo Equipment/transactions payload malformed")
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except PlaywrightError as exc:
        log.warning("Fortuneo portfolio browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Fortuneo portfolio failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)
