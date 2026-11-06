"""Read-only CORUM client-space authentication, SCPI and CORUM Life sidecar.

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

CORUM Life contracts are read from a third payload, one call per contract,
and `life_parser` owns that one. `/portfolio` reads both kinds in a single
browser session; `/positions` is the SCPI-only read the backend used before.

Credentials, session cookies and raw financial responses are never logged.
"""

import asyncio
import json
from decimal import Decimal
from typing import Any
from urllib.parse import quote, urlsplit

from fastapi import HTTPException
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Playwright,
    Page,
    async_playwright,
)
from life_parser import parse_life, parse_operations
from positions_parser import PositionsFormatError, parse_snapshot

log = configure_logging("corum-auth")

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
CONTRACT_PATH = "/api/contract/active"
# NB: no `realEstate` segment on the detail paths. The client space routes the
# contract itself as /contract/realEstate/{code}/... but the per-fund read is
# /contract/{code}/... -- verified live, where adding `realEstate` is a 404 that
# reads like a missing fund.
CONTRACT_DETAIL_PATH = "/api/contract/realEstate/{code}/{investment_type}"
PRODUCT_PATH = "/api/contract/{code}/{investment_type}/product/{product}"
# Verified live: 200, the whole contract (pockets, NAVs, euro fund) in one read.
LIFE_DETAIL_PATH = "/api/contract/life/{code}"
# Verified live: 200, every contract's operations split by year, in one read.
# There is no per-operation detail endpoint.
OPERATIONS_PATH = "/api/operations/"

LOGIN_FORM_TIMEOUT_SECONDS = 25
SUBMIT_TIMEOUT_SECONDS = 30
# No second factor was observed, but the cookie is captured by watching the
# first authenticated call the dashboard makes, so the wait is bounded by how
# long that dashboard takes to fire rather than by any user prompt.
SESSION_CAPTURE_TIMEOUT_SECONDS = 30
# One in-page API read. Below the 30 s navigation bound: a stalled response
# must give its browser slot back rather than hold it until CORUM answers.
READ_TIMEOUT_SECONDS = 20
# The evaluation's own bound, past the in-page abort, for a page that is wedged.
READ_EVALUATE_GRACE_SECONDS = 5

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


app = create_app(request_log=(log, "CORUM"), validation_detail="INVALID_DATA")


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
    # `holdings`, not `positions`: this is the key `CorumAdapter.Snapshot` decodes.
    # `extra="forbid"` on both sides means a mismatch surfaces as a 502 rather
    # than as a snapshot that silently carries no funds.
    holdings: list[HoldingPayload]


class LifeHoldingPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    productCode: str = Field(min_length=1, max_length=40)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    unitPrice: Decimal
    valueEur: Decimal
    netInvestedEur: Decimal | None
    valuationDate: str | None = Field(max_length=10)
    # Invested in the euro fund: 1-euro units, quantity = valueEur.
    euroFund: bool
    # The code's `chartSections` section name (`realEstate`, `bondFund`,
    # `euroLife`, ...), raw; null when the asset-class view does not list it.
    assetClass: str | None = Field(max_length=40)


class ScheduledPaymentPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    amountEur: Decimal
    # CORUM's own word (`monthly`, ...); the backend maps it to a cadence.
    frequency: str = Field(min_length=1, max_length=40)


class LifeOperationPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # CORUM's own id, the string of an integer; the backend's dedup key.
    operationId: str = Field(min_length=1, max_length=40)
    # CORUM's own words (`scheduledPayment`, `couponDetachment`...), raw: the
    # backend maps the ones it knows and keeps the rest as neutral rows.
    type: str = Field(min_length=1, max_length=60)
    category: str | None = Field(max_length=60)
    date: str = Field(min_length=10, max_length=10)
    # Signed: a fee is negative, an arbitrage has one leg each way.
    amountEur: Decimal
    fundCodes: list[str] | None


class LifeSnapshotPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    contractCode: str = Field(min_length=1, max_length=100)
    currency: str = Field(min_length=3, max_length=3)
    totalValuationEur: Decimal
    valuationDate: str | None = Field(max_length=10)
    # The backend re-runs the reconciliation, and its tolerance scales with
    # the raw line count, which the aggregated holdings no longer show.
    valuationLineCount: int = Field(ge=0)
    # Contract-level cost basis: the lines' own costs leave out switched money.
    netInvestedEur: Decimal
    paidInEur: Decimal | None
    openedAt: str | None = Field(max_length=10)
    scheduledPayment: ScheduledPaymentPayload | None
    snapshotComplete: bool
    holdings: list[LifeHoldingPayload]
    # Null when the operation list could not be read: the backend then keeps
    # the rows it already stored, and the valuation above is synced all the same.
    operations: list[LifeOperationPayload] | None


class PortfolioPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    # Null, not absent, when the login holds no SCPI contract: the backend
    # must be told there is nothing there rather than left to guess.
    realEstate: SnapshotPayload | None
    life: list[LifeSnapshotPayload]


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
    """Opens a browser and returns it ready to use, or raises having released.

    A slot and a Chromium process are held from the first line. Both have to
    come back on every failure, including one between the two: a caller that
    never receives the browser cannot clean it up, so a raise in `new_context`
    or `route` would otherwise leave the slot counted and leak the process.
    Four of those and the sidecar answers 503 to everything.
    """
    await _acquire_browser_slot()
    try:
        browser = await pw.chromium.launch(headless=True, args=LAUNCH_ARGS)
    except BaseException:
        # Nothing to close yet: the slot is the only thing held.
        await _release_browser_slot()
        raise
    try:
        context = await browser.new_context(
            locale="fr-FR",
            timezone_id="Europe/Paris",
            user_agent=USER_AGENT,
            storage_state=storage_state,
            service_workers="block",
        )
        # Images are left alone -- the login flow was verified with them loading.
        # Fonts and media are dead weight either way.
        await context.route(
            "**/*",
            lambda route: route.abort()
            if route.request.resource_type in ("media", "font")
            else route.continue_(),
        )
    except BaseException:
        # The caller never receives the browser, so this is the only place the
        # slot and the process can be given back.
        await _close_resources(None, browser, None)
        raise
    return browser, context, SessionCollector()


def _is_portal_url(url: str) -> bool:
    try:
        parsed = urlsplit(url)
        origin = urlsplit(BASE_URL)
        return (parsed.scheme == "https" and parsed.hostname == origin.hostname
                and (443 if parsed.port is None else parsed.port)
                == (443 if origin.port is None else origin.port)
                and not parsed.username and not parsed.password)
    except ValueError:
        return False


async def _new_page(context: BrowserContext) -> Page:
    """Guard every Chromium request, including redirected POSTs, before sending.

    Playwright routes only see the first hop of a redirected request. CDP's
    request-stage interception sees each hop without replacing Chromium's TLS
    fingerprint with an API client's, which Cloudflare would reject.
    """
    page = await context.new_page()
    session = await context.new_cdp_session(page)
    hops: dict[str, int] = {}

    async def guard(event: dict[str, Any]) -> None:
        request_id = event["requestId"]
        previous = event.get("redirectedRequestId")
        count = hops.pop(previous, 0) + 1 if previous else 0
        hops[request_id] = count
        if count > 5 or not _is_portal_url(event["request"]["url"]):
            await session.send("Fetch.failRequest", {
                "requestId": request_id, "errorReason": "BlockedByClient"})
        else:
            await session.send("Fetch.continueRequest", {"requestId": request_id})

    session.on("Fetch.requestPaused", guard)
    await session.send("Fetch.enable", {"patterns": [{"urlPattern": "*", "requestStage": "Request"}]})
    return page


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
        page = await _new_page(live)
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
        log.warning("CORUM authentication failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected CORUM authentication failure")
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

    The fetch is aborted after `READ_TIMEOUT_SECONDS`, body included, and the
    evaluation itself is bounded a little later in case the page is wedged: a
    stalled read raises, so `_read_in_portal` closes and frees its slot.
    """
    try:
        result = await asyncio.wait_for(
            page.evaluate(
                """async ([path, timeoutMs]) => {
                    const controller = new AbortController();
                    const timer = setTimeout(() => controller.abort(), timeoutMs);
                    try {
                        const response = await fetch(path, {credentials: 'include', signal: controller.signal});
                        let body = null;
                        try { body = await response.json(); } catch (e) { body = null; }
                        if (controller.signal.aborted) return {status: null, body: null, timedOut: true};
                        return {status: response.status, body};
                    } catch (e) {
                        if (controller.signal.aborted) return {status: null, body: null, timedOut: true};
                        throw e;
                    } finally {
                        clearTimeout(timer);
                    }
                }""",
                [path, READ_TIMEOUT_SECONDS * 1000],
            ),
            timeout=READ_TIMEOUT_SECONDS + READ_EVALUATE_GRACE_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        log.warning("CORUM request timed out")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    if result.get("timedOut"):
        log.warning("CORUM request timed out")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    status = result.get("status")
    if status in (401, 403):
        # 401 is the API refusing the session. 403 here is Cloudflare rather
        # than CORUM, which the adapter surfaces as an unavailable upstream.
        raise HTTPException(
            status_code=401 if status == 401 else 502,
            detail="SESSION_EXPIRED" if status == 401 else "UPSTREAM_UNAVAILABLE",
        )
    if status is None or not 200 <= status < 300:
        log.warning("CORUM request failed (status=%s)", status)
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


def _select_contracts(active: Any, *, reads_life: bool = False) -> tuple[str | None, list[str]]:
    """Pick the contracts this service reads out of `/contract/active`.

    Returns the real-estate contract code, if any, and every CORUM Life code
    as listed; `/portfolio` refuses a duplicate code, `/positions` never reads them.
    With `reads_life` (`/portfolio`), a Life or real-estate entry without a
    usable code is refused rather than skipped: skipping it would report a
    successful sync that silently left that contract (or the SCPI funds) stale.
    PER and capitalisation are still left out: they are insurance this service
    has no parser for, and folding them in would invent positions.
    """
    entries = active if isinstance(active, list) else [active]
    real_estate: list[str] = []
    life: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        code = entry.get("contractCode")
        contract_type = entry.get("contractType")
        if not isinstance(contract_type, str):
            continue
        if not (isinstance(code, str) and code):
            if reads_life and contract_type.casefold() in ("life", "real_estate"):
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
            continue
        if contract_type.casefold() == "real_estate":
            real_estate.append(code)
        elif contract_type.casefold() == "life":
            life.append(code)

    if len(real_estate) > 1:
        # Ambiguous: a user with two real-estate contracts gets one account per
        # fund, so this service cannot pick a contract for them. Fail loudly
        # rather than syncing the wrong one -- and refuse the Life part too,
        # because the backend commits both or neither.
        raise HTTPException(status_code=409, detail="MULTIPLE_CONTRACTS")
    if not real_estate and not life:
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
    return (real_estate[0] if real_estate else None), life


def _segment(value: str) -> str:
    """One upstream-supplied value as a single URL path segment.

    Contract and fund codes come from CORUM's own payloads and go straight into
    a same-origin fetch. Encoding them keeps a value such as `../profile` from
    reaching another client-space endpoint with the session's cookies. A bare
    `.` or `..` survives encoding and is resolved by the browser as a dot
    segment, so it is refused outright.
    """
    if value in {"", ".", ".."}:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    return quote(value, safe="")


async def _read_real_estate(page: Any, code: str) -> dict[str, Any]:
    """The SCPI snapshot of one real-estate contract, already parsed."""
    _, contract = await _get_json(
        page,
        CONTRACT_DETAIL_PATH.format(code=_segment(code), investment_type="FULL_PROPERTY"),
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
                code=_segment(code), investment_type="FULL_PROPERTY", product=_segment(fund_code)
            ),
        )
        products.append(product)

    return parse_snapshot(contract, products)


async def _read_life(page: Any, code: str) -> dict[str, Any]:
    """The snapshot of one CORUM Life contract, already parsed."""
    _, payload = await _get_json(page, LIFE_DETAIL_PATH.format(code=_segment(code)))
    snapshot = parse_life(payload)
    # The account is keyed by this code. A payload for another contract would
    # write one contract's money under another's name.
    if snapshot["contractCode"] != code:
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    return snapshot


async def _read_operations(page: Any, life_codes: list[str]) -> dict[str, Any]:
    """Every Life contract's operations, or None for all of them on failure.

    The operation list is secondary to the valuation: a read or format failure
    here must not fail a sync whose balances were read fine. It is logged by
    cause only -- never the payload -- and every contract then reports no
    operations, which the backend reads as "keep what is stored".
    """
    try:
        _, payload = await _get_json(page, OPERATIONS_PATH)
        return parse_operations(payload, life_codes)
    except HTTPException as exc:
        log.warning("CORUM Life operations not read (code=%s)", exc.detail)
    except PositionsFormatError as exc:
        log.warning("CORUM Life operations rejected (code=%s)", exc.code)
    except PlaywrightError:
        # The page died mid-fetch. The message may quote the page, so only the cause is logged.
        log.warning("CORUM Life operations not read (code=BROWSER_ERROR)")
    return {code: None for code in life_codes}


def _active_totals(active: Any) -> dict[str, Any]:
    """Valuation per contract code from the active list, which may hold a single entry."""
    entries = active if isinstance(active, list) else [active]
    return {
        entry["contractCode"]: entry.get("valuationAmount")
        for entry in entries
        # A non-string code is no key to look up, and a list is no key at all.
        if isinstance(entry, dict) and isinstance(entry.get("contractCode"), str)
    }


async def _read_portfolio(page: Any) -> dict:
    """Read the real-estate snapshot and every life contract of the login in one pass. A
    repeated life code is refused, since it would write one account from two reads; a
    gap between the listed and detailed total is only logged."""
    _, active = await _get_json(page, CONTRACT_PATH)
    real_estate_code, life_codes = _select_contracts(active, reads_life=True)
    if len(set(life_codes)) != len(life_codes):
        # A Life account is keyed by its contract code. The same code twice
        # would write one account from two reads.
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    real_estate = (
        await _read_real_estate(page, real_estate_code)
        if real_estate_code is not None
        else None
    )
    totals = _active_totals(active)
    life = []
    for code in life_codes:
        snapshot = await _read_life(page, code)
        # The summary list is rounded and cached on CORUM's side, so it is not
        # a gate -- but a drift worth noticing. No figure is logged.
        listed = totals.get(code)
        if (
            isinstance(listed, (int, float))
            and not isinstance(listed, bool)
            and abs(Decimal(str(listed)) - snapshot["totalValuationEur"]) > Decimal("0.05")
        ):
            log.warning("CORUM Life active total differs from the contract detail")
        life.append(snapshot)
    if life:
        operations = await _read_operations(page, life_codes)
        for snapshot in life:
            snapshot["operations"] = operations.get(snapshot["contractCode"])
    return PortfolioPayload.model_validate(
        {"realEstate": real_estate, "life": life}
    ).model_dump()


async def _read_positions(page: Any) -> dict:
    """Read the SCPI snapshot alone. A login with only CORUM Life has none, so it is
    refused as PORTFOLIO_INCOMPLETE."""
    _, active = await _get_json(page, CONTRACT_PATH)
    code, _life = _select_contracts(active)
    if code is None:
        # The SCPI-only read: a login with only CORUM Life has no SCPI snapshot.
        raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
    snapshot = await _read_real_estate(page, code)
    return SnapshotPayload.model_validate(snapshot).model_dump()


async def _read_in_portal(session_state: str, read: Any) -> dict:
    """Open the client space with a stored session, run `read` on it, close.

    The one place the read endpoints open a browser, so the error mapping and
    the cleanup cannot drift between them.
    """
    storage_state, _cookie = _decode_session(session_state)

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser, live, _ = await _new_browser(pw, storage_state=storage_state)
        context = live
        page = await _new_page(live)
        # The reads happen in this page, so it has to be on the client space
        # itself: a same-origin fetch is what Cloudflare accepts.
        await page.goto(f"{BASE_URL}/", wait_until="domcontentloaded", timeout=30_000)
        return await read(page)
    except PositionsFormatError as exc:
        log.warning("CORUM payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except ValidationError as exc:
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("CORUM read browser failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected CORUM read failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)


@app.post("/positions", response_model=SnapshotPayload)
async def positions(req: SessionRequest) -> dict:
    """SCPI only. Kept for one release so a backend not yet on `/portfolio`
    keeps syncing; it answers exactly what it did before CORUM Life."""
    return await _read_in_portal(req.sessionState, _read_positions)


@app.post("/portfolio", response_model=PortfolioPayload)
async def portfolio(req: SessionRequest) -> dict:
    """SCPI and every CORUM Life contract, read in one browser session so both
    parts describe the same moment and the backend can commit them together."""
    return await _read_in_portal(req.sessionState, _read_portfolio)
