"""
Trade Republic Auth Sidecar
---------------------------
Logs a member in against api.traderepublic.com (which sits behind an AWS WAF
browser challenge) and turns the login cookies into short-lived session tokens.
The Spring backend keeps the long-lived state, encrypted; the data path stays in Java.

Flow:
  POST /initiate {phoneNumber, pin}   → {processId, flow, countdownInSeconds}
  POST /complete {processId, tan?}    → {sessionState}
  POST /session  {sessionState}       → {sessionToken, claims}
  POST /transactions {sessionState, from, to} → TR's official CSV transaction export

`pin` is the member's secret: the 4-digit PIN or, when the account uses one, a
real password. TR takes both in the same field.

flow "confirmInApp": v2 login, the member confirms in the Trade Republic app and
/complete polls until TR confirms. flow "authenticator": v2 login where TR asks
for the code of the member's TOTP app (requiredAction AUTHENTICATOR_VERIFICATION);
/complete posts the 6-digit code in tan. flow "otp": v1 login with a 4-digit
code, the fallback when v2 answers 5xx or DEVICE_NOT_FOUND.

sessionState is the JSON {"refresh": tr_refresh, "device": tr_device, "sub": sub,
"phone": normalised phone}; states without phone are still accepted. It never
carries tr_session. Cookie values, the PIN or password, claims contents and the
full phone number are never logged.
"""

import asyncio
import base64
import binascii
import json
import re
import secrets
import time
import uuid
from datetime import date, datetime, timezone
from typing import Any, Optional

import httpx
from fastapi import HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from picsou_sidecar import PendingStore, configure_logging, create_app, safe
from playwright.async_api import async_playwright
from pydantic import BaseModel, Field

log = configure_logging("tr-auth", quiet=("httpx",))

TR_API = "https://api.traderepublic.com"
TR_APP = "https://app.traderepublic.com"

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)
TR_APP_VERSION = "13.40.5"

FLOW_CONFIRM_IN_APP = "confirmInApp"
FLOW_OTP = "otp"
FLOW_AUTHENTICATOR = "authenticator"

PENDING_TTL_SECONDS = 600
MAX_PENDING = 16

# A confirmation is a human opening an app. The whole wait, polls and WAF token
# refreshes included, stays under the backend's 150 s /complete timeout, so the
# sidecar is always the one to give up.
APP_VALIDATION_TIMEOUT_SECONDS = 120
APP_VALIDATION_POLL_SECONDS = 2.0
AUTHENTICATOR_COUNTDOWN_SECONDS = 120
# v1 says nothing about the code's lifetime; the countdown only paces the UI.
OTP_COUNTDOWN_SECONDS = 30

# A cold container often settles the page before the WAF challenge has set its
# token, so a second browser attempt runs when the first gets none or fails.
WAF_TOKEN_ATTEMPTS = 2
WAF_PAGE_LOAD_TIMEOUT_MS = 15000
WAF_CHALLENGE_WAIT_MS = 8000
WAF_POLL_INTERVAL_MS = 500

# The backend gives /initiate 45 s. Every token attempt, browser startup included,
# shares one budget that leaves the TR call its whole timeout, plus a margin,
# inside that wait.
CALLER_TIMEOUT_S = 45
TR_REQUEST_TIMEOUT_S = 8
WAF_TOKEN_BUDGET_S = CALLER_TIMEOUT_S - TR_REQUEST_TIMEOUT_S - 5

# The CSV export is three TR calls (request, polls, download) behind one refresh:
# one budget covers them all, and a job that stays pending ends as a 502.
EXPORT_BUDGET_S = 100
EXPORT_MIN_POLL_SECONDS = 2
EXPORT_MAX_DAYS = 1830
EXPORT_DATE_PATTERN = r"[0-9]{4}-[0-9]{2}-[0-9]{2}"
EXPORT_PATH = "/api/v1/portfolio-analytics/transactions/export"

# Both values become URL path segments on api.traderepublic.com. TR's processId is a
# UUID and the TAN is the 4-digit code; the patterns exclude every character that
# could leave the segment (/ ? # . @ % ...). The authenticator code (6 digits) goes
# in a JSON body, never in a URL.
PROCESS_ID_PATTERN = r"[A-Za-z0-9-]{1,64}"
TAN_PATTERN = r"[0-9]{4}"
AUTHENTICATOR_CODE_PATTERN = r"[0-9]{6}"
# Which flow takes which code; the request model only bounds the length.
CODE_PATTERNS = {FLOW_OTP: TAN_PATTERN, FLOW_AUTHENTICATOR: AUTHENTICATOR_CODE_PATTERN}


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    # A pending state holds plain strings only: nothing to release.
    return None


_pending = PendingStore(
    dispose=_dispose_pending_state, log=log, ttl_seconds=PENDING_TTL_SECONDS, max_size=MAX_PENDING
)
app = create_app(request_log=(log, "Trade Republic"), background=_pending)


@app.exception_handler(RequestValidationError)
async def invalid_body(_: Request, exc: RequestValidationError) -> JSONResponse:
    # Never echoes the offending input: it may be a PIN or a session state.
    fields = {str(error["loc"][-1]) for error in exc.errors() if error.get("loc")}
    if "tan" in fields:
        detail = "INVALID_OTP"
    elif "sessionState" in fields:
        detail = "INVALID_SESSION_STATE"
    else:
        detail = "INVALID_DATA"
    return JSONResponse(status_code=422, content={"detail": detail})


# ─── Errors ───────────────────────────────────────────────────────────────────

def upstream_unavailable(status_code: int = 502) -> HTTPException:
    return HTTPException(status_code=status_code, detail="UPSTREAM_UNAVAILABLE")


def format_changed() -> HTTPException:
    return HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")


def rate_limited() -> HTTPException:
    return HTTPException(status_code=429, detail="RATE_LIMITED")


def waf_blocked() -> HTTPException:
    log.warning("AWS WAF refused the request or gave no token")
    return upstream_unavailable(503)


def tr_unreachable(status_code: int = 504) -> HTTPException:
    return upstream_unavailable(status_code)


def browser_failed() -> HTTPException:
    return upstream_unavailable(503)


def is_waf_rejection(resp: httpx.Response) -> bool:
    # TR's own errors carry a JSON body; AWS WAF answers with an empty one.
    return resp.status_code in (403, 405) and not resp.text.strip()


# ─── AWS WAF token ────────────────────────────────────────────────────────────

async def get_waf_token() -> Optional[str]:
    """Retrieves the AWS WAF token, with a fresh browser when an attempt gets none
    or fails, and gives up once WAF_TOKEN_BUDGET_S has passed. A failure on the
    last attempt is a browser_failed() error."""
    try:
        async with asyncio.timeout(WAF_TOKEN_BUDGET_S):
            for attempt in range(1, WAF_TOKEN_ATTEMPTS + 1):
                try:
                    waf_token = await load_waf_token()
                except Exception as error:
                    if attempt == WAF_TOKEN_ATTEMPTS:
                        log.error("AWS WAF token attempt %d/%d failed",
                                  attempt, WAF_TOKEN_ATTEMPTS, exc_info=True)
                        raise browser_failed() from error
                    log.warning("AWS WAF token attempt %d/%d failed",
                                attempt, WAF_TOKEN_ATTEMPTS, exc_info=True)
                    continue
                if waf_token:
                    return waf_token
                log.warning("No AWS WAF token on attempt %d/%d", attempt, WAF_TOKEN_ATTEMPTS)
    except TimeoutError:
        log.warning("No AWS WAF token within %d s", WAF_TOKEN_BUDGET_S)
    return None


async def load_waf_token() -> Optional[str]:
    """Loads app.traderepublic.com in headless Chromium and waits for the AWS WAF token."""
    log.info("Launching headless browser to obtain AWS WAF token...")
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = await browser.new_context(user_agent=USER_AGENT)
        page = await context.new_page()
        await page.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined })"
        )

        try:
            await page.goto(TR_APP, wait_until="networkidle", timeout=WAF_PAGE_LOAD_TIMEOUT_MS)
        except Exception:
            pass

        waf_token = await read_waf_token(page, context)
        for _ in range(WAF_CHALLENGE_WAIT_MS // WAF_POLL_INTERVAL_MS):
            if waf_token:
                break
            await page.wait_for_timeout(WAF_POLL_INTERVAL_MS)
            waf_token = await read_waf_token(page, context)

        await browser.close()
        return waf_token


async def read_waf_token(page, context) -> Optional[str]:
    # Try JS API first
    try:
        waf_token = await page.evaluate(
            "window.AWSWafIntegration ? window.AWSWafIntegration.getToken() : null"
        )
        if waf_token:
            log.info("Got WAF token via AWSWafIntegration.getToken()")
            return waf_token
    except Exception:
        pass

    # Fallback: cookies
    for cookie in await context.cookies():
        if "aws-waf-token" in cookie.get("name", "").lower():
            log.info("Got WAF token from cookie")
            return cookie["value"]
    return None


# ─── Request helpers ──────────────────────────────────────────────────────────

def build_device_info() -> str:
    """The X-TR-Device-Info value of one login attempt. TR ties the login POST and
    every poll to this exact string: build it once and keep it in the pending state."""
    # Same fields as the web app's own device info (analytics chunk, C_e/d$).
    return base64.b64encode(json.dumps({
        "stableDeviceId": secrets.token_hex(64),
        "browser": "Chrome",
        "browserVersion": "153.0.0.0",
        "os": "Linux",
        "timezone": "Europe/Paris",
        "timezoneOffset": -60,
        "screen": "1440x900x24",
        "preferredLanguages": ["fr-FR", "fr"],
        "numberOfCores": 8,
        "deviceMemory": 8,
    }, separators=(",", ":")).encode()).decode()


def login_cookie_header(cookies: dict[str, str]) -> dict:
    """The cookies TR set on the login POST (JSESSIONID, load-balancer affinity), sent
    back on every poll as the browser does. Raw header: httpx's jar drops path=/api."""
    pairs = [f"{name}={value}" for name, value in cookies.items()
             if not re.search(r"[;,\s\x00-\x1f\x7f]", name + value)]
    return {"Cookie": "; ".join(pairs)} if pairs else {}


def tr_headers(waf_token: Optional[str], device_info: str) -> dict:
    headers = {
        "Accept": "*/*",
        "Accept-Language": "fr",
        "Cache-Control": "no-cache",
        "Content-Type": "application/json",
        "Pragma": "no-cache",
        "User-Agent": USER_AGENT,
        "x-tr-app-version": TR_APP_VERSION,
        "x-tr-device-info": device_info,
        "x-tr-platform": "web",
        "Origin": TR_APP,
        "Referer": TR_APP + "/",
    }
    if waf_token:
        headers["x-aws-waf-token"] = waf_token
    return headers


def normalise_phone(phone: str) -> str:
    # Separators are dropped so the stored phone is +<digits>, which the backend can mask.
    phone = re.sub(r"[\s.\-()]", "", phone)
    if phone.startswith("0"):
        phone = "+33" + phone[1:]
    return phone


def mask_phone(phone: str) -> str:
    # Only the last two digits, whatever the input format (+33, leading 0, spaces).
    # They are rebuilt from an int, so the logged text carries nothing else typed.
    digits = "".join(c for c in phone if c in "0123456789")
    if len(digits) < 2:
        return "****"
    return f"****{int(digits[-2:]):02d}"


def login_url(version: str) -> str:
    return f"{TR_API}/api/{version}/auth/web/login"


def poll_url(process_id: str) -> str:
    if not re.fullmatch(PROCESS_ID_PATTERN, process_id):
        raise ValueError("processId is not a valid path segment")
    return f"{TR_API}/api/v2/auth/web/login/processes/{process_id}"


def authenticator_url(process_id: str) -> str:
    if not re.fullmatch(PROCESS_ID_PATTERN, process_id):
        raise ValueError("processId is not a valid path segment")
    return f"{poll_url(process_id)}/authenticator-verification"


def login_complete_url(process_id: str, tan: str) -> str:
    # The TR processId comes from TR itself and the tan from a validated body;
    # checked again so the URL builder is safe on its own.
    if not re.fullmatch(PROCESS_ID_PATTERN, process_id):
        raise ValueError("processId is not a valid path segment")
    if not re.fullmatch(TAN_PATTERN, tan):
        raise ValueError("tan is not a valid path segment")
    return f"{TR_API}/api/v1/auth/web/login/{process_id}/{tan}"


def new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(timeout=TR_REQUEST_TIMEOUT_S)


async def send(client: httpx.AsyncClient, method: str, url: str, **kwargs) -> httpx.Response:
    # asyncio.timeout raises the builtin TimeoutError; httpx's own timeouts and
    # connection failures are TransportErrors. Callers map both.
    async with asyncio.timeout(TR_REQUEST_TIMEOUT_S):
        return await client.request(method, url, **kwargs)


def error_codes(resp: httpx.Response) -> list[str]:
    """TR's errorCode values from a JSON error body, upper-cased; [] when there are none."""
    try:
        body = resp.json()
    except ValueError:
        return []
    items = body.get("errors") if isinstance(body, dict) else None
    if not isinstance(items, list):
        items = [body] if isinstance(body, dict) else []
    return [str(item["errorCode"]).upper() for item in items
            if isinstance(item, dict) and item.get("errorCode")]


def parse_set_cookies(headers: httpx.Headers) -> dict[str, str]:
    """name → value of every non-empty Set-Cookie. httpx's jar would drop TR's
    cookies (path=/api), so the headers are read directly."""
    cookies: dict[str, str] = {}
    for cookie_str in headers.get_list("set-cookie"):
        first = cookie_str.split(";", 1)[0].strip()
        if "=" not in first:
            continue
        name, value = first.split("=", 1)
        if value.strip():
            cookies[name.strip()] = value.strip()
    return cookies


def cookie_names(headers: httpx.Headers) -> list[str]:
    return list(parse_set_cookies(headers))


def decode_claims(value: Any) -> dict:
    """tr_claims: base64url JSON, or a JWT-shaped value whose payload is the middle part.
    A dict is returned as is. Raises ValueError for anything else."""
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value:
        raise ValueError("no claims")
    segment = value
    if "." in value:
        parts = value.split(".")
        if len(parts) < 2:
            raise ValueError("claims are not decodable")
        segment = parts[1]
    try:
        decoded = json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
    except (binascii.Error, ValueError) as error:
        raise ValueError("claims are not decodable") from error
    if not isinstance(decoded, dict):
        raise ValueError("claims are not an object")
    return decoded


def build_session_state(cookies: dict[str, str], body_claims: Any = None,
                        phone: Optional[str] = None) -> str:
    refresh, device = cookies.get("tr_refresh"), cookies.get("tr_device")
    if not refresh or not device:
        log.warning("TR login gave no refresh or device cookie (names: %s)", sorted(cookies))
        raise format_changed()
    sub = None
    for source in (cookies.get("tr_claims"), body_claims):
        try:
            sub = decode_claims(source).get("sub")
        except ValueError:
            continue
        if sub:
            break
    if not isinstance(sub, str) or not sub:
        log.warning("TR login gave no subject in its claims")
        raise format_changed()
    state = {"refresh": refresh, "device": device, "sub": sub}
    if phone:
        state["phone"] = phone
    return json.dumps(state, separators=(",", ":"))


def parse_session_state(raw: str) -> dict[str, str]:
    try:
        state = json.loads(raw)
    except ValueError:
        state = None
    keys = ("refresh", "device", "sub")
    if not isinstance(state, dict) or not all(
            isinstance(state.get(key), str) and state[key] for key in keys):
        raise HTTPException(status_code=422, detail="INVALID_SESSION_STATE")
    values = {key: state[key] for key in keys}
    # Optional: states issued before the phone was kept do not have it.
    if isinstance(state.get("phone"), str) and state["phone"]:
        values["phone"] = state["phone"]
    # Both go into a raw Cookie header: refuse anything that could add a cookie or a header.
    if any(re.search(r"[;,\s\x00-\x1f\x7f]", values[key]) for key in ("refresh", "device")):
        raise HTTPException(status_code=422, detail="INVALID_SESSION_STATE")
    return values


# ─── Requests ─────────────────────────────────────────────────────────────────

class InitiateRequest(BaseModel):
    phoneNumber: str = Field(min_length=1, max_length=64)
    pin: str = Field(min_length=1, max_length=128)


class CompleteRequest(BaseModel):
    processId: str = Field(pattern=f"^{PROCESS_ID_PATTERN}$")
    # 4 digits for flow otp, 6 for flow authenticator: complete() checks the flow's own.
    tan: Optional[str] = Field(default=None, pattern=r"^[0-9]{4,6}$")


class SessionRequest(BaseModel):
    sessionState: str = Field(min_length=2, max_length=20_000)


class TransactionsRequest(BaseModel):
    sessionState: str = Field(min_length=2, max_length=20_000)
    # Not parsed here: a bad date is INVALID_DATA however it is wrong.
    from_: str = Field(alias="from", min_length=1, max_length=32)
    to: str = Field(min_length=1, max_length=32)


# ─── Login: initiate ──────────────────────────────────────────────────────────

def fails_over_to_v1(resp: httpx.Response) -> bool:
    """The web falls back to v1 when v2 answers 5xx or an error outside its known list."""
    return resp.status_code >= 500 or any("DEVICE" in code for code in error_codes(resp))


def raise_for_login_status(resp: httpx.Response) -> None:
    if is_waf_rejection(resp):
        raise waf_blocked()
    if resp.status_code == 429:
        raise rate_limited()
    if resp.status_code >= 500:
        raise upstream_unavailable()
    raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")


def tr_process_id(resp: httpx.Response) -> str:
    try:
        process_id = resp.json().get("processId")
    except (ValueError, AttributeError):
        process_id = None
    if not isinstance(process_id, str) or not re.fullmatch(PROCESS_ID_PATTERN, process_id):
        raise format_changed()
    return process_id


def requires_authenticator(resp: httpx.Response) -> bool:
    try:
        body = resp.json()
    except ValueError:
        return False
    return isinstance(body, dict) and body.get("requiredAction") == "AUTHENTICATOR_VERIFICATION"


async def read_login_flow(client: httpx.AsyncClient, process_id: str, headers: dict,
                          cookies: dict) -> tuple[str, dict]:
    """(flow, cookies the poll set): the web reads the new v2 process once to learn
    whether TR wants the authenticator code. A poll that fails leaves confirmInApp,
    the default: /complete polls again and reports the error itself."""
    try:
        resp = await send(client, "GET", poll_url(process_id),
                          headers={**headers, **login_cookie_header(cookies)})
    except (TimeoutError, httpx.TransportError) as error:
        log.warning("TR first poll did not answer error_type=%s", type(error).__name__)
        return FLOW_CONFIRM_IN_APP, {}
    log.info("TR first poll → %d", resp.status_code)
    if not resp.is_success:
        return FLOW_CONFIRM_IN_APP, {}
    flow = FLOW_AUTHENTICATOR if requires_authenticator(resp) else FLOW_CONFIRM_IN_APP
    return flow, parse_set_cookies(resp.headers)


async def start_login(client: httpx.AsyncClient, credentials: dict, headers: dict) -> tuple[str, str, dict]:
    """(flow, TR processId, cookies set by the login): v2, then v1 when v2 fails over."""
    resp = await send(client, "POST", login_url("v2"), json=credentials, headers=headers)
    log.info("TR v2 login → %d", resp.status_code)
    if resp.is_success:
        process_id = tr_process_id(resp)
        cookies = parse_set_cookies(resp.headers)
        flow, poll_cookies = await read_login_flow(client, process_id, headers, cookies)
        return flow, process_id, {**cookies, **poll_cookies}
    if not fails_over_to_v1(resp):
        raise_for_login_status(resp)

    log.info("TR v2 login failed over to v1 (status %d)", resp.status_code)
    resp = await send(client, "POST", login_url("v1"), json=credentials, headers=headers)
    log.info("TR v1 login → %d", resp.status_code)
    if not resp.is_success:
        raise_for_login_status(resp)
    return FLOW_OTP, tr_process_id(resp), parse_set_cookies(resp.headers)


@app.post("/initiate")
async def initiate(req: InitiateRequest) -> dict:
    await _pending.expire()
    waf_token = await get_waf_token()
    if not waf_token:
        raise waf_blocked()
    phone = normalise_phone(req.phoneNumber)
    log.info("Initiating TR auth for %s", safe(mask_phone(phone)))

    device_info = build_device_info()
    try:
        async with new_client() as client:
            flow, tr_id, cookies = await start_login(
                client, {"phoneNumber": phone, "pin": req.pin}, tr_headers(waf_token, device_info))
    except HTTPException:
        raise
    except (TimeoutError, httpx.TransportError) as error:
        log.warning("TR login did not answer error_type=%s", type(error).__name__)
        raise tr_unreachable() from error
    except Exception as error:
        log.exception("Unexpected TR initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from error

    process_id = str(uuid.uuid4())
    await _pending.put_or_raise(process_id, {
        "flow": flow,
        "trProcessId": tr_id,
        "deviceInfo": device_info,
        "wafToken": waf_token,
        "cookies": cookies,
        "phone": phone,
        "created_at": time.time(),
    })
    countdown = {FLOW_CONFIRM_IN_APP: APP_VALIDATION_TIMEOUT_SECONDS,
                 FLOW_AUTHENTICATOR: AUTHENTICATOR_COUNTDOWN_SECONDS}.get(flow, OTP_COUNTDOWN_SECONDS)
    return {"processId": process_id, "flow": flow, "countdownInSeconds": countdown}


# ─── Login: complete ──────────────────────────────────────────────────────────

def poll_is_expired(body: dict) -> bool:
    expires_at = body.get("expiresAt")
    if not isinstance(expires_at, str):
        return False
    try:
        expiry = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
    except ValueError:
        return False
    if expiry.tzinfo is None:
        expiry = expiry.replace(tzinfo=timezone.utc)
    return expiry < datetime.now(timezone.utc)


def confirmation_outcome(resp: httpx.Response) -> Optional[tuple[dict, Any]]:
    """None while TR waits for the member; the cookies and claims once confirmed."""
    try:
        body = resp.json()
    except ValueError:
        raise format_changed()
    if not isinstance(body, dict) or not isinstance(body.get("status"), str):
        raise format_changed()
    status = body["status"]
    log.info("TR poll → status %s", safe(status, 40))
    if status in ("CONFIRMED", "COMPLETED"):
        return parse_set_cookies(resp.headers), body.get("claims")
    if status != "PENDING":
        raise HTTPException(status_code=401, detail="APP_VALIDATION_CANCELLED")
    if poll_is_expired(body):
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    return None


async def await_app_confirmation(state: dict, timeout: Optional[float] = None) -> tuple[dict, Any]:
    """Polls the v2 process until TR confirms; returns (cookies, claims from the body).

    One deadline bounds the whole wait: a poll or a WAF token fetch still running
    when it passes is cancelled, so the call never outlasts its timeout
    (APP_VALIDATION_TIMEOUT_SECONDS unless the caller passes a shorter one).
    """
    url = poll_url(state["trProcessId"])
    try:
        async with asyncio.timeout(
                APP_VALIDATION_TIMEOUT_SECONDS if timeout is None else timeout) as deadline:
            async with new_client() as client:
                while True:
                    try:
                        resp = await send(client, "GET", url, headers={
                            **tr_headers(state["wafToken"], state["deviceInfo"]),
                            **login_cookie_header(state["cookies"])})
                    except (TimeoutError, httpx.TransportError) as error:
                        log.warning("TR poll did not answer error_type=%s", type(error).__name__)
                        resp = None

                    if resp is not None:
                        if is_waf_rejection(resp):
                            # The token can expire while the member looks for their phone.
                            log.info("TR poll refused by the WAF, fetching a new token")
                            state["wafToken"] = await get_waf_token()
                            if not state["wafToken"]:
                                raise waf_blocked()
                            continue
                        if resp.status_code == 429:
                            raise rate_limited()
                        if resp.status_code == 404:
                            raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
                        if 400 <= resp.status_code < 500:
                            raise HTTPException(status_code=401, detail="APP_VALIDATION_CANCELLED")
                        if resp.is_success:
                            outcome = confirmation_outcome(resp)
                            if outcome is not None:
                                return outcome
                        else:
                            log.warning("TR poll → %d", resp.status_code)
                    await asyncio.sleep(APP_VALIDATION_POLL_SECONDS)
    except TimeoutError:
        if not deadline.expired():
            raise
    raise HTTPException(status_code=408, detail="APP_VALIDATION_TIMEOUT")


async def submit_otp(state: dict, tan: str) -> tuple[dict, Any]:
    """v1: posts the 4-digit code; returns (cookies, None)."""
    # The WAF token from /initiate may be stale by the time the member read their code.
    waf_token = await get_waf_token()
    if not waf_token:
        raise waf_blocked()
    async with new_client() as client:
        resp = await send(client, "POST", login_complete_url(state["trProcessId"], tan),
                          headers={**tr_headers(waf_token, state["deviceInfo"]),
                                   **login_cookie_header(state["cookies"])})
    log.info("TR v1 code check → %d  set-cookie names: %s", resp.status_code, cookie_names(resp.headers))
    if is_waf_rejection(resp):
        raise waf_blocked()
    if resp.status_code == 429:
        raise rate_limited()
    if resp.status_code >= 500:
        raise upstream_unavailable()
    if resp.status_code >= 400:
        raise HTTPException(status_code=401, detail="INVALID_OTP")
    return parse_set_cookies(resp.headers), None


def raise_for_authenticator_status(resp: httpx.Response) -> None:
    if is_waf_rejection(resp):
        raise waf_blocked()
    if resp.status_code == 429:
        raise rate_limited()
    if resp.status_code >= 500:
        raise upstream_unavailable()
    if resp.is_success:
        return
    codes = error_codes(resp)
    if "TOO_MANY_REQUESTS" in codes:
        raise rate_limited()
    if "PROCESS_GONE" in codes or "NOT_FOUND" in codes or resp.status_code == 404:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if "ALREADY_PROCESSED" in codes:
        raise HTTPException(status_code=401, detail="APP_VALIDATION_CANCELLED")
    # VALIDATION_CODE_INVALID, VALIDATION_CODE_ALREADY_USED and any other refusal.
    raise HTTPException(status_code=401, detail="INVALID_OTP")


async def submit_authenticator_code(state: dict, code: str) -> tuple[dict, Any]:
    """v2 authenticator: posts the 6-digit code, then reads the process until TR
    has finished it; returns (cookies of the verification and of the polls, claims).

    TR still asks for the in-app confirmation after the code (measured live). The token
    fetch, the code check and that wait share one APP_VALIDATION_TIMEOUT_SECONDS budget,
    so this flow also answers before the backend's 150 s /complete timeout."""
    deadline = time.monotonic() + APP_VALIDATION_TIMEOUT_SECONDS
    waf_token = await get_waf_token()
    if not waf_token:
        raise waf_blocked()
    async with new_client() as client:
        resp = await send(client, "POST", authenticator_url(state["trProcessId"]), json={"code": code},
                          headers={**tr_headers(waf_token, state["deviceInfo"]),
                                   **login_cookie_header(state["cookies"])})
    log.info("TR authenticator check → %d  set-cookie names: %s", resp.status_code, cookie_names(resp.headers))
    raise_for_authenticator_status(resp)
    verified = parse_set_cookies(resp.headers)
    cookies, body_claims = await await_app_confirmation(
        {**state, "wafToken": waf_token, "cookies": {**state["cookies"], **verified}},
        max(0.0, deadline - time.monotonic()))
    return {**verified, **cookies}, body_claims


@app.post("/complete")
async def complete(req: CompleteRequest) -> dict:
    state = await _pending.take(req.processId)
    if state is None:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    flow = state["flow"]
    try:
        if flow == FLOW_CONFIRM_IN_APP:
            cookies, body_claims = await await_app_confirmation(state)
        else:
            if not req.tan or not re.fullmatch(CODE_PATTERNS[flow], req.tan):
                await _pending.repark(req.processId, state)
                raise HTTPException(status_code=422, detail="INVALID_OTP")
            if flow == FLOW_AUTHENTICATOR:
                cookies, body_claims = await submit_authenticator_code(state, req.tan)
            else:
                cookies, body_claims = await submit_otp(state, req.tan)
        return {"sessionState": build_session_state(
            {**state["cookies"], **cookies}, body_claims, state.get("phone"))}
    except HTTPException as error:
        # A mistyped code does not burn the attempt: the member can type it again.
        if flow in CODE_PATTERNS and error.detail == "INVALID_OTP" and req.processId not in _pending:
            await _pending.repark(req.processId, state)
        raise
    except (TimeoutError, httpx.TransportError) as error:
        log.warning("TR completion did not answer error_type=%s", type(error).__name__)
        raise tr_unreachable(502 if flow == FLOW_AUTHENTICATOR else 504) from error
    except Exception as error:
        log.exception("Unexpected TR completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from error


# ─── Session ──────────────────────────────────────────────────────────────────

def refresh_headers(state: dict[str, str]) -> dict:
    return {
        "Accept": "*/*",
        "Content-Type": "application/json",
        "Origin": TR_APP,
        "Referer": TR_APP + "/",
        "User-Agent": USER_AGENT,
        # httpx's cookies= would drop them: TR sets both with path=/api.
        "Cookie": f"tr_refresh={state['refresh']}; tr_device={state['device']}",
    }


async def refresh_session(client: httpx.AsyncClient, state: dict[str, str]) -> tuple[httpx.Response, dict]:
    """TR's session refresh: (response, cookies it set). Raises the HTTPException of a
    rejected, throttled or failed refresh; transport errors are the caller's."""
    resp = await send(client, "POST", f"{TR_API}/api/v2/auth/web/session",
                      json={"subjectId": state["sub"]}, headers=refresh_headers(state))
    cookies = parse_set_cookies(resp.headers)
    log.info("TR session → %d  set-cookie names: %s", resp.status_code, sorted(cookies))
    if resp.status_code in (401, 403) and not is_waf_rejection(resp):
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if resp.status_code == 429:
        raise rate_limited()
    if not resp.is_success:
        raise upstream_unavailable()
    return resp, cookies


@app.post("/session")
async def session(req: SessionRequest) -> dict:
    state = parse_session_state(req.sessionState)
    try:
        async with new_client() as client:
            _, cookies = await refresh_session(client, state)
    except HTTPException:
        raise
    except (TimeoutError, httpx.TransportError) as error:
        log.warning("TR session call did not answer error_type=%s", type(error).__name__)
        raise tr_unreachable(502) from error
    except Exception as error:
        log.exception("Unexpected TR session failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from error

    token = cookies.get("tr_session")
    if not token:
        raise format_changed()
    try:
        claims = decode_claims(cookies.get("tr_claims"))
    except ValueError as error:
        log.warning("TR session claims could not be decoded")
        raise format_changed() from error
    return {"sessionToken": token, "claims": claims}


# ─── Transactions export ──────────────────────────────────────────────────────

def export_range(raw_from: str, raw_to: str) -> tuple[str, str]:
    """The validated (from, to) ISO dates of an export, or INVALID_DATA."""
    try:
        if not (re.fullmatch(EXPORT_DATE_PATTERN, raw_from) and re.fullmatch(EXPORT_DATE_PATTERN, raw_to)):
            raise ValueError("not an ISO date")
        start, end = date.fromisoformat(raw_from), date.fromisoformat(raw_to)
    except ValueError as error:
        raise HTTPException(status_code=422, detail="INVALID_DATA") from error
    if start > end or (end - start).days > EXPORT_MAX_DAYS:
        raise HTTPException(status_code=422, detail="INVALID_DATA")
    return start.isoformat(), end.isoformat()


async def pause(seconds: float) -> None:
    await asyncio.sleep(seconds)


def export_headers(cookie_header: dict, **extra: str) -> dict:
    return {"Origin": TR_APP, "User-Agent": USER_AGENT, **extra, **cookie_header}


def raise_for_export_status(resp: httpx.Response, expected: int, step: str) -> None:
    log.info("TR export %s → %d", step, resp.status_code)
    if resp.status_code == 429:
        raise rate_limited()
    if resp.status_code != expected:
        raise upstream_unavailable()


def export_job(resp: httpx.Response) -> tuple[str, str, float]:
    """(jobId, status, estimated seconds remaining) of a request or status reply."""
    try:
        body = resp.json()
    except ValueError:
        raise format_changed()
    job_id = body.get("jobId") if isinstance(body, dict) else None
    status = body.get("status") if isinstance(body, dict) else None
    if not isinstance(job_id, str) or not job_id or not isinstance(status, str):
        raise format_changed()
    estimate = body.get("estimatedSecondsRemaining")
    if isinstance(estimate, bool) or not isinstance(estimate, (int, float)):
        estimate = 0
    return job_id, status, estimate


async def download_export(client: httpx.AsyncClient, cookie_header: dict, start: str, end: str) -> bytes:
    resp = await send(client, "POST", TR_API + EXPORT_PATH + "/request", json={"from": start, "to": end},
                      headers=export_headers(cookie_header, **{"Accept": "*/*", "Content-Type": "application/json"}))
    raise_for_export_status(resp, 202, "request")
    job_id, status, estimate = export_job(resp)
    params = {"jobId": job_id}

    while status == "PENDING":
        await pause(max(EXPORT_MIN_POLL_SECONDS, estimate))
        resp = await send(client, "GET", TR_API + EXPORT_PATH + "/status", params=params,
                          headers=export_headers(cookie_header, Accept="*/*"))
        raise_for_export_status(resp, 200, "status")
        _, status, estimate = export_job(resp)
        log.info("TR export job status %s", safe(status, 40))
    if status != "COMPLETED":
        raise upstream_unavailable()

    # Accept: application/json is a 406; the export is a CSV.
    resp = await send(client, "GET", TR_API + EXPORT_PATH + "/download", params=params,
                      headers=export_headers(cookie_header, Accept="*/*"))
    raise_for_export_status(resp, 200, "download")
    return resp.content


@app.post("/transactions")
async def transactions(req: TransactionsRequest) -> Response:
    state = parse_session_state(req.sessionState)
    start, end = export_range(req.from_, req.to)
    try:
        async with asyncio.timeout(EXPORT_BUDGET_S):
            async with new_client() as client:
                _, cookies = await refresh_session(client, state)
                if not cookies.get("tr_session"):
                    raise format_changed()
                csv = await download_export(client, login_cookie_header(cookies), start, end)
    except HTTPException:
        raise
    except (TimeoutError, httpx.TransportError) as error:
        log.warning("TR export did not answer error_type=%s", type(error).__name__)
        raise tr_unreachable(502) from error
    except Exception as error:
        log.exception("Unexpected TR export failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from error

    log.info("TR export → %d bytes, %d lines", len(csv), csv.count(b"\n"))
    return Response(content=csv, media_type="text/csv; charset=utf-8")
