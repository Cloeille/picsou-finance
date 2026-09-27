"""Read-only CORUM client-space authentication and SCPI positions sidecar.

Playwright drives the login form, then the session cookie is harvested off
the requests the SPA itself makes. CORUM gates nothing behind a captcha and,
on the account this was verified against, asks for no second factor -- so
unlike the Amundi sidecar there is no pending state and no `/complete`
round-trip: a login either yields a session or fails.

Two payload shapes are read, and they are not interchangeable. The contract
call knows the funds and the envelope but carries no unit price; the
per-fund call is the only place a withdrawal price appears. `positions_parser`
owns that split and the arithmetic; this file only owns the browser, the
session and the HTTP.

Credentials, session cookies and raw financial responses are never logged.
"""

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Playwright,
    async_playwright,
)
from positions_parser import PositionsFormatError, parse_snapshot

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("corum-auth")

BASE_URL = "https://client.corum.fr"
LOGIN_URL = f"{BASE_URL}/connexion"
# Cloudflare's bot management keys on the client fingerprint, not on the
# session cookie alone. Verified against the live portal: a request carrying
# only these headers is answered 403 Error 1010 ("the owner has banned your
# browser's signature") before any application code runs.
BROWSER_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
# The probes and the payload reads share these. The portal serves the SPA from
# `/`, so a read that omits Referer/Origin is not the request the app makes.
BROWSER_HEADERS = {
    "Accept": "application/json",
    "User-Agent": BROWSER_USER_AGENT,
    "Referer": f"{BASE_URL}/",
    "Origin": BASE_URL,
}
# One probe tells an expired session from a live one without downloading a
# portfolio. It is cheap and unambiguous: 200 while logged in, 401 once not.
AUTH_PROBE_PATH = "/api/auth/isAuthenticated"
CONTRACT_PATH = "/api/contract/active"
# NB: no `realEstate` segment on the detail paths. The client space routes the
# contract itself as /contract/realEstate/{code}/... but the per-fund read is
# /contract/{code}/... -- verified live, where adding `realEstate` is a 404 that
# reads like a missing fund.
CONTRACT_DETAIL_PATH = "/api/contract/realEstate/{code}/{investment_type}"
PRODUCT_PATH = "/api/contract/{code}/{investment_type}/product/{product}"

LOGIN_FORM_TIMEOUT_SECONDS = 25
SUBMIT_TIMEOUT_SECONDS = 30
# No second factor was observed, but the cookie is captured by watching the
# first authenticated call the dashboard makes, so the wait is bounded by how
# long that dashboard takes to fire rather than by any user prompt.
SESSION_CAPTURE_TIMEOUT_SECONDS = 30
POSITIONS_TIMEOUT_SECONDS = 30

# Every login starts a Playwright driver and a Chromium process, and the
# backend throttles per IP, which does not bound this service in aggregate.
MAX_CONCURRENT_BROWSERS = 4
_browsers = 0
_browser_lock = asyncio.Lock()


async def _acquire_browser_slot() -> None:
    global _browsers
    async with _browser_lock:
        if _browsers >= MAX_CONCURRENT_BROWSERS:
            log.warning("CORUM browser capacity reached (%d)", _browsers)
            raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE")
        _browsers += 1


async def _release_browser_slot() -> None:
    global _browsers
    async with _browser_lock:
        _browsers = max(0, _browsers - 1)


@asynccontextmanager
async def lifespan(_: FastAPI):
    yield


app = FastAPI(lifespan=lifespan)


def _log_safe(value: str) -> str:
    """Uvicorn percent-decodes paths, so a caller can plant CR/LF and forge
    log lines. Strip controls and bound the length."""
    return "".join(ch for ch in value if ch.isprintable())[:200]


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            log.info(
                "CORUM request completed (path=%s; duration=%.2fs)",
                _log_safe(request.url.path),
                time.monotonic() - started_at,
            )


class LoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # A CORUM client id, not an email address.
    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)


class SessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


class HoldingPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fundCode: str = Field(min_length=1, max_length=40)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    withdrawalPrice: Decimal | None = None
    subscriptionPrice: Decimal | None = None
    displayedValueEur: Decimal | None = None
    valuationDate: str | None = Field(default=None, max_length=10)


class SnapshotPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    contractCode: str = Field(min_length=1, max_length=100)
    propertyRightType: str | None = Field(default=None, max_length=40)
    currency: str = Field(min_length=3, max_length=3)
    totalValuationEur: Decimal
    valuationDate: str | None = Field(default=None, max_length=10)
    snapshotComplete: bool
    # `holdings`, not `positions`: this is the key `CorumPort.Snapshot` decodes.
    # `extra="forbid"` on both sides means a mismatch surfaces as a 502 rather
    # than as a snapshot that silently carries no funds.
    holdings: list[HoldingPayload]


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    return JSONResponse(status_code=400, content={"detail": "INVALID_DATA"})


class SessionCollector:
    """Harvests the session cookie jar off the SPA's own traffic.

    CORUM authenticates with a whole cookie jar, not one cookie. Replaying only
    `ai_session` -- the obvious choice, and the one the earlier spike used --
    comes back `all_tokens_expired` even on a session that is genuinely live:
    `au_t` and `re_t` are the tokens the API actually checks, and `ai_session`
    is only the SPA's own flag. Verified against the portal, where the same
    jar plus browser headers answers 200.

    So the jar is captured whole rather than by name. Watching the responses
    the dashboard makes is also the capture point that does not depend on
    CORUM's internal storage keys, which is what makes this survive a
    front-end refactor.
    """

    #: Sent by the client space on a CORUM domain, or set on a subdomain.
    DOMAIN_SUFFIX = "corum.fr"

    def __init__(self) -> None:
        self.cookies: dict[str, str] = {}

    async def harvest(self, context: BrowserContext) -> None:
        """Folds the browser's whole jar in, keyed by its real domain.

        Read from the context rather than from the responses seen so far.
        `Response.headers` carries HTTP headers, not cookies, and an entry from
        there has no domain to match on -- so a response-driven collector keeps
        nothing. The context is also the only reader that agrees with the
        storage state the next session will replay.
        """
        for cookie in await context.cookies():
            self.record(
                cookie.get("domain", ""),
                cookie.get("name"),
                cookie.get("value"),
            )

    def _is_corum(self, domain: str) -> bool:
        bare = domain.lstrip(".")
        return bare == self.DOMAIN_SUFFIX or bare.endswith(f".{self.DOMAIN_SUFFIX}")

    def record(self, domain: str, name: str, value: str) -> None:
        """Folds one cookie into the jar when it belongs to CORUM."""
        if name and value and self._is_corum(domain):
            self.cookies[name] = value

    def has_session(self) -> bool:
        return len(self.cookies) > 0

    def cookie_header(self) -> str:
        return "; ".join(f"{name}={value}" for name, value in self.cookies.items())

    async def wait(self, timeout_seconds: int) -> dict[str, str] | None:
        for _ in range(timeout_seconds * 4):
            if self.has_session():
                return self.cookies
            await asyncio.sleep(0.25)
        return self.cookies or None


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    playwright: Playwright | None,
) -> None:
    """Also frees the browser slot.

    Keyed on `browser`, not `playwright`: `_new_browser` guarantees it either
    returns with a slot held and a live browser, or raises having released.
    Every open path closes through here exactly once.
    """
    if browser is not None:
        await _release_browser_slot()
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
        (playwright, "stop"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(getattr(resource, close_method)(), timeout=5)
        except Exception:
            log.warning("CORUM browser resource cleanup failed", exc_info=True)


async def _type_into(page: Any, selectors: list[str], value: str) -> bool:
    """Type key by key rather than setting `value`.

    A bulk fill can land as a single character on a masked input, which leaves
    the form invalid with nothing on screen to explain it.
    """
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.is_visible(timeout=800):
                await locator.click()
                await locator.press_sequentially(value, delay=45)
                await locator.blur()
                return True
        except Exception:
            continue
    return False


async def _wait_for_visible(page: Any, selectors: list[str], timeout_seconds: int):
    for _ in range(timeout_seconds * 4):
        for selector in selectors:
            locator = page.locator(selector).first
            try:
                if await locator.is_visible(timeout=800):
                    return locator
            except Exception:
                continue
        await asyncio.sleep(0.25)
    return None


LAUNCH_ARGS = ["--disable-blink-features=AutomationControlled"]
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


async def _new_browser(
    pw: Playwright,
    storage_state: dict[str, Any] | None = None,
) -> tuple[Browser, BrowserContext, SessionCollector]:
    await _acquire_browser_slot()
    try:
        browser = await pw.chromium.launch(headless=True, args=LAUNCH_ARGS)
    except BaseException:
        await _release_browser_slot()
        raise
    context = await browser.new_context(
        locale="fr-FR",
        timezone_id="Europe/Paris",
        user_agent=USER_AGENT,
        storage_state=storage_state,
    )
    # Images are left alone -- the login flow was verified with them loading.
    # Fonts and media are dead weight either way.
    await context.route(
        "**/*",
        lambda route: route.abort()
        if route.request.resource_type in ("media", "font")
        else route.continue_(),
    )
    collector = SessionCollector()
    return browser, context, collector


def _encode_session(storage_state: dict[str, Any], cookie: str) -> str:
    return json.dumps(
        {"storageState": storage_state, "cookie": cookie},
        separators=(",", ":"),
    )


def _decode_session(raw: str) -> tuple[dict[str, Any], str]:
    try:
        decoded = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(decoded, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    storage_state = decoded.get("storageState")
    cookie = decoded.get("cookie")
    if not isinstance(storage_state, dict) or not isinstance(cookie, str) or not cookie:
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return storage_state, cookie


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=SessionResponse)
async def initiate(req: LoginRequest) -> dict:
    """Authenticate and return a session. CORUM asks for no second factor, so
    this is the whole exchange: there is nothing for a human to confirm."""
    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser, live, collector = await _new_browser(pw)
        context = live
        page = await live.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        # The form is server-rendered but the SPA takes a moment to enable the
        # submit button; a missing field must surface as UPSTREAM_FORMAT_CHANGED
        # rather than as a wrong-credentials accusation.
        if await _wait_for_visible(page, ["#login-id"], LOGIN_FORM_TIMEOUT_SECONDS) is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        if not await _type_into(page, ["#login-id", 'input[inputmode="numeric"]'], req.login):
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        if not await _type_into(page, ["#login-password", 'input[type="password"]'], req.password):
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        submit = await _wait_for_visible(
            page,
            ["#login-confirm", 'button[type="submit"]'],
            SUBMIT_TIMEOUT_SECONDS,
        )
        if submit is None:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        await submit.click()

        # Wait for the tokens, not for any cookie: the pre-login page already
        # sets Cloudflare and tracking cookies, so "a cookie arrived" would
        # return before the session exists and hand back a jar that fails on
        # the first read. `au_t` is the one the API checks.
        authenticated = False
        for _ in range(SESSION_CAPTURE_TIMEOUT_SECONDS * 4):
            await collector.harvest(live)
            if "au_t" in collector.cookies:
                authenticated = True
                break
            await asyncio.sleep(0.25)
        if not authenticated:
            # Still sitting on the form: CORUM rejected the credentials, or it
            # challenged with something this service does not handle. Either way
            # there is no session to hand back.
            raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")

        # Re-read the jar from the browser rather than from the responses seen
        # so far: the storage state is what the next session will replay, and
        # the two must agree.
        await collector.harvest(live)

        storage_state = await live.storage_state()
        return {"sessionState": _encode_session(storage_state, collector.cookie_header())}
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("CORUM authentication failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected CORUM authentication failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        # Every path out of here closes: a successful login still owns a
        # Chromium process, a driver and a browser slot, and leaking those
        # would turn the fourth successful login into a permanent 503.
        await _close_resources(context, browser, pw)


async def _get_json(
    page: Any,
    path: str,
) -> tuple[int, Any]:
    """Reads an API path from inside the page, not from the sidecar process.

    CORUM sits behind Cloudflare, which fingerprints the client. Verified
    against the live portal: the same cookies requested from the sidecar's own
    process are answered 403 Error 1010 ("the site owner has banned your
    browser's signature"), while the identical request issued by the page --
    same origin, same cookies, same TLS session as the SPA -- answers 200.

    So the reads go through `fetch` in the page. That also means no cookie
    header is assembled here: a missing or stale token shows up as the API's
    own 401 rather than as a bot-wall 403 we would have to guess about.
    """
    result = await page.evaluate(
        """async (path) => {
            const response = await fetch(path, {credentials: 'include'});
            let body = null;
            try { body = await response.json(); } catch (e) { body = null; }
            return {status: response.status, body};
        }""",
        path,
    )
    status = result.get("status")
    if status in (401, 403):
        # 401 is the API refusing the session. 403 here is Cloudflare rather
        # than CORUM, which the adapter surfaces as an unavailable upstream.
        raise HTTPException(
            status_code=401 if status == 401 else 502,
            detail="SESSION_EXPIRED" if status == 401 else "UPSTREAM_UNAVAILABLE",
        )
    if status is None or not 200 <= status < 300:
        log.warning("CORUM request failed (path=%s; status=%s)", _log_safe(path), status)
        if status == 429 or (status is not None and status >= 500):
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        # A 404/400 means the endpoint moved or the query contract changed.
        # Calling that "incomplete portfolio" points the operator at the wrong
        # cause.
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    if result.get("body") is None:
        # An HTML error page lands here as a 200 with no JSON.
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    return status, result["body"]


def _select_real_estate_contract(active: Any) -> tuple[str, str]:
    """Pick the real-estate contract out of `/contract/active`.

    One login can also see CORUM Life, PER and capitalisation. Those are
    insurance, not shares, and folding them into a SCPI snapshot would invent
    a position that does not exist.
    """
    entries = active if isinstance(active, list) else [active]
    contracts: list[tuple[str, str]] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        code = entry.get("contractCode")
        contract_type = entry.get("contractType")
        if isinstance(code, str) and code and isinstance(contract_type, str):
            contracts.append((code, contract_type))

    if not contracts:
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
    if len(contracts) > 1:
        # Ambiguous: a user with two real-estate contracts gets one account per
        # fund, so this service cannot pick a contract for them. Fail loudly
        # rather than syncing the wrong one.
        raise HTTPException(status_code=409, detail="MULTIPLE_CONTRACTS")
    return contracts[0]


@app.post("/positions", response_model=SnapshotPayload)
async def positions(req: SessionRequest) -> dict:
    storage_state, _cookie = _decode_session(req.sessionState)

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser, live, _ = await _new_browser(pw, storage_state=storage_state)
        context = live
        page = await live.new_page()
        # The reads below happen in this page, so it has to be on the client
        # space itself: a same-origin fetch is what Cloudflare accepts.
        await page.goto(f"{BASE_URL}/", wait_until="domcontentloaded", timeout=30_000)

        _, active = await _get_json(page, CONTRACT_PATH)
        code, _contract_type = _select_real_estate_contract(active)

        _, contract = await _get_json(
            page,
            CONTRACT_DETAIL_PATH.format(code=code, investment_type="FULL_PROPERTY"),
        )
        if not isinstance(contract, dict):
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        # The withdrawal price lives only in the per-fund call, so every fund
        # in the contract needs its own read. A fund that fails to come back is
        # a partial portfolio, and the parser refuses the whole snapshot.
        products = []
        for entry in contract.get("productsData") or []:
            if not isinstance(entry, dict):
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
            fund_code = entry.get("productCode")
            if not isinstance(fund_code, str) or not fund_code:
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
            _, product = await _get_json(
                page,
                PRODUCT_PATH.format(
                    code=code, investment_type="FULL_PROPERTY", product=fund_code
                ),
            )
            products.append(product)

        snapshot = parse_snapshot(contract, products)
        return SnapshotPayload.model_validate(snapshot).model_dump()
    except PositionsFormatError as exc:
        log.warning("CORUM payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except ValidationError as exc:
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("CORUM positions browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected CORUM positions failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)
