"""Read-only Natixis Interépargne authentication and positions sidecar.

The saver space (epargnants.interepargne.natixis.fr) is an Angular SPA over a
JSON API. Nothing in its login needs a browser: no captcha, no script
challenge, and the on-screen keypad only displays the digits, which the API
receives in clear. So this service speaks plain HTTPS.

Auth flow (an OAuth2 authorization code with PKCE, bridged over SAML):
  POST /initiate {email, password}
    authorize → SAML SSO (its page carries the CSRF token) → login modes
    → password → strong authentication required → code sent by SMS/voice/e-mail
    → {processId, mfaRequired: true, mfaType}
  POST /complete {processId, code}
    → code verified → SAML response posted back → authorization code
    → tokens → {sessionState}
  POST /positions {sessionState}
    → refresh-token grant → savings + products → {plans, sessionState}

The refresh token (`offline_access`) is what lets the backend sync without
anyone at the phone, until Natixis ends the session. It is the only secret
returned to Java, which encrypts it before storage. The password and the code
live in memory for one request; neither they nor any token, cookie or
financial value is ever logged.
"""

import asyncio
import base64
import hashlib
import json
import re
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from html import unescape
from typing import Any, Literal
from urllib.parse import parse_qs, quote, urljoin, urlsplit

import httpx
from fastapi import HTTPException, Response
from picsou_sidecar import PendingStore, configure_logging, create_app, otp_field_detail
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from operations_parser import finished_demands, operation_fund_ids, parse_operations
from savings_parser import SavingsFormatError, fund_code, fund_ids, parse_plans

# httpx logs every URL at INFO, and the redirect after login carries the code.
log = configure_logging("natixis-auth", quiet=("httpx",))

CLIENT_ID = "ad7e6480-cb40-4b44-9149-1738a24460fc"
REDIRECT_URI = "https://epargnants.interepargne.natixis.fr/"
SCOPE = (
    "readActiris readUsers readDistribution readInvestment manageAccount readSaving readCms "
    "readAccount readProfitSharing readTransfer readFunds readSaverGuide readConfidential offline_access"
)
OAUTH = "https://nie-auth.api.natixis.com/api/oauth"
IDP = "https://auth-nie.natixis.com"
API = "https://nie.api.natixis.com"
# Hosts a redirect or a form may send the login to.
TRUSTED_HOSTS = frozenset({"nie-auth.api.natixis.com", "auth-nie.natixis.com", "epargnants.interepargne.natixis.fr"})

# Second factors in order of preference; the saver space offers the ones set up on the account.
OTP_MODES = ("SMS", "EMAIL", "VOICE")
PENDING_TTL_SECONDS = 600
# Wrong codes one login attempt may take before it is dropped; Natixis locks the
# account on its own after a few more, which would lock the saver out.
MAX_CODE_ATTEMPTS = 5
MAX_PENDING = 16
REQUEST_TIMEOUT_SECONDS = 15.0
# Each route gives up before the backend's 45 s adapter timeout, so the backend
# gets an answer rather than a timeout. A route that gives up late in a login may
# still leave a code sent or tokens minted: the saver then needs a new code.
ROUTE_DEADLINE_SECONDS = 40.0
# Fund details only add the AMF code to a line: they get a slice of the route's
# deadline, read a few at a time, and whatever is not back by then goes without.
FUND_DETAIL_BUDGET_SECONDS = 10.0
# /positions answers the backend's 120 s adapter timeout, not the 45 s of the login
# routes; the operations history gets its own slice, and is dropped (not the plans)
# when it overruns.
POSITIONS_DEADLINE_SECONDS = 100.0
OPERATIONS_BUDGET_SECONDS = 30.0
OPERATIONS_HISTORY_YEARS = 5
FUND_DETAIL_CONCURRENCY = 4
SESSION_STATE_VERSION = 1

USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
)

# Every API path the sidecar reads, whole. The saver's token goes to nothing else,
# whatever a session state or an upstream answer holds.
_API_PATH = re.compile(
    r"/auth/v3/users/me"
    r"|/saving/v1/employees/[0-9A-Za-z-]{1,40}/(?:savings|products)"
    r"|/transaction/v1/employees/[0-9A-Za-z-]{1,40}/transactions"
    r"|/transaction/v1/employees/[0-9A-Za-z-]{1,40}/(?:investmentDemands|paymentDemands)/[0-9A-Za-z_-]{1,120}"
    r"|/fund/v1/funds/[0-9]{1,18}"
)

_FORM_ACTION_RE = re.compile(r'<form[^>]*\baction="([^"]+)"', re.I)
_INPUT_RE = re.compile(r'<input[^>]*\bname="([^"]+)"[^>]*\bvalue="([^"]*)"', re.I)
_CSRF_RE = re.compile(r'<meta\s+name="_csrf"\s+content="([^"]+)"', re.I)


class NatixisError(Exception):
    """An outcome with a contract code; the message is for logs and never holds secrets."""

    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code


# ─── Contract ───────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: str = Field(min_length=1, max_length=254)
    password: str = Field(min_length=1, max_length=100)


class InitiateResponse(BaseModel):
    processId: str
    mfaRequired: Literal[True]
    mfaType: Literal["SMS", "EMAIL", "VOICE"]


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    code: str = Field(pattern=r"^[0-9]{4,12}$")


class CancelRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)


class SessionResponse(BaseModel):
    sessionState: str


class PositionsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=10_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: None = None
    fundCode: str | None = Field(default=None, max_length=20)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    unitValue: Decimal | None = None
    valueEur: Decimal
    pnlEur: Decimal | None = None


class PlanPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    planKind: str | None = Field(default=None, max_length=30)
    employer: str | None = Field(default=None, max_length=200)
    balanceEur: Decimal
    positions: list[PositionPayload]
    snapshotComplete: Literal[True]


class OperationPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    planExternalId: str = Field(min_length=1, max_length=100)
    date: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    type: Literal["BUY", "SELL"]
    label: str = Field(min_length=1, max_length=200)
    fundLabel: str = Field(min_length=1, max_length=200)
    fundCode: str | None = Field(default=None, max_length=20)
    quantity: Decimal
    unitValue: Decimal | None = None
    amountEur: Decimal
    feesEur: Decimal | None = None


class PositionsResponse(BaseModel):
    sessionState: str
    plans: list[PlanPayload]
    operationsComplete: bool
    # The first listing date the history was requested from; null when it was not read.
    operationsFrom: str | None = None
    operations: list[OperationPayload]


# ─── Plumbing ───────────────────────────────────────────────────────────────


async def _dispose(state: dict[str, Any]) -> None:
    client = state.get("client")
    if client is not None:
        try:
            await client.aclose()
        except Exception:
            log.warning("Could not close a pending Natixis client")


_pending = PendingStore(dispose=_dispose, log=log, ttl_seconds=PENDING_TTL_SECONDS, max_size=MAX_PENDING)
app = create_app(
    request_log=(log, "Natixis Interépargne"),
    background=_pending,
    validation_detail=otp_field_detail("INVALID_OTP"),
)


def _new_client() -> httpx.AsyncClient:
    """Tests replace this to plug in a mock transport."""
    return httpx.AsyncClient(
        headers={"User-Agent": USER_AGENT, "Accept-Language": "fr-FR,fr;q=0.9"},
        timeout=REQUEST_TIMEOUT_SECONDS,
        follow_redirects=False,
    )


@asynccontextmanager
async def _errors(step: str, deadline: float | None = None):
    """Turn every failure into the `{"detail": CODE}` contract."""
    try:
        async with asyncio.timeout(deadline or ROUTE_DEADLINE_SECONDS):
            yield
    except HTTPException:
        raise
    except NatixisError as exc:
        log.warning("Natixis %s failed code=%s: %s", step, exc.code, exc)
        raise HTTPException(status_code=exc.status, detail=exc.code) from exc
    except SavingsFormatError as exc:
        log.warning("Natixis %s answer rejected code=%s: %s", step, exc.code, exc)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except TimeoutError as exc:
        log.warning("Natixis %s took longer than %ss", step, deadline or ROUTE_DEADLINE_SECONDS)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except (AttributeError, KeyError, TypeError, ValueError, ValidationError) as exc:
        log.warning("Natixis %s answer has an unexpected shape error_type=%s", step, type(exc).__name__)
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED") from exc
    except httpx.HTTPError as exc:
        log.warning("Natixis %s unreachable error_type=%s", step, type(exc).__name__)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected Natixis %s failure error_type=%s", step, type(exc).__name__)
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


def _trusted(url: str) -> str:
    parts = urlsplit(url)
    if parts.scheme != "https" or parts.hostname not in TRUSTED_HOSTS:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "Login sent to an unexpected host")
    return url


def _upstream(response: httpx.Response, what: str) -> None:
    """Fail on what no step expects: a 5xx or a throttle."""
    if response.status_code == 429:
        raise NatixisError(429, "RATE_LIMITED", f"{what} throttled")
    if response.status_code >= 500:
        raise NatixisError(502, "UPSTREAM_UNAVAILABLE", f"{what} answered {response.status_code}")


def _json(response: httpx.Response, what: str) -> Any:
    try:
        return response.json()
    except ValueError as exc:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"{what} is not JSON") from exc


def _error_codes(response: httpx.Response) -> list[str]:
    try:
        errors = response.json().get("errors") or []
    except (ValueError, AttributeError):
        return []
    return [e.get("code") for e in errors if isinstance(e, dict) and isinstance(e.get("code"), str)]


def _pkce() -> tuple[str, str]:
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def _idp_headers(state: dict[str, Any]) -> dict[str, str]:
    return {
        "x-csrf-token": state["csrf"],
        "x-device-id": state["deviceId"],
        "Origin": IDP,
        "Referer": IDP + "/",
        "Accept": "application/json, text/plain, */*",
    }


# ─── Login ──────────────────────────────────────────────────────────────────


async def _open_login(client: httpx.AsyncClient) -> dict[str, Any]:
    """authorize → the SAML request it auto-posts → the IdP page and its CSRF token."""
    verifier, challenge = _pkce()
    oauth_state = secrets.token_urlsafe(24)
    response = await client.get(f"{OAUTH}/authorize", params={
        "response_type": "code",
        "client_id": CLIENT_ID,
        "state": oauth_state,
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPE,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "additionalData": "language=fr",
    })
    _upstream(response, "authorize")
    action = _FORM_ACTION_RE.search(response.text)
    fields = {name: unescape(value) for name, value in _INPUT_RE.findall(response.text)}
    if response.status_code != 200 or action is None or "SAMLRequest" not in fields:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "authorize did not hand out a SAML request")

    target = _trusted(urljoin(str(response.url), unescape(action.group(1))))
    response = await client.post(target, data=fields)
    _upstream(response, "SAML SSO")
    csrf = _CSRF_RE.search(response.text)
    if response.status_code != 200 or csrf is None:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "SAML SSO page carries no CSRF token")
    return {
        "verifier": verifier,
        "oauthState": oauth_state,
        "csrf": csrf.group(1),
        "deviceId": str(uuid.uuid4()),
    }


async def _password_login(client: httpx.AsyncClient, state: dict[str, Any], email: str, password: str) -> None:
    headers = _idp_headers(state)
    response = await client.post(f"{IDP}/auth/v1/authenticationModes/search",
                                 json={"emailOrServerCode": email}, headers=headers)
    _upstream(response, "login modes")
    if 400 <= response.status_code < 500:
        raise NatixisError(401, "INVALID_CREDENTIALS", f"login modes answered {response.status_code}")
    modes = {item.get("mode") for item in _json(response, "login modes").get("items") or [] if isinstance(item, dict)}
    if not modes:
        raise NatixisError(401, "INVALID_CREDENTIALS", "No login mode for this e-mail")
    if "PASSWORD" not in modes:
        raise NatixisError(501, "MFA_TYPE_UNSUPPORTED", "This account has no password login")

    response = await client.post(f"{IDP}/auth/v1/authenticate", json={"password": password}, headers=headers)
    _upstream(response, "password")
    if response.status_code in (401, 403, 400, 404):
        raise NatixisError(401, "INVALID_CREDENTIALS", f"password answered {response.status_code}")
    if response.status_code == 423:
        raise NatixisError(429, "RATE_LIMITED", "account locked")
    if response.status_code not in (200, 201):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"password answered {response.status_code}")


async def _send_code(client: httpx.AsyncClient, state: dict[str, Any]) -> str:
    """Natixis always asks a second factor: start it on the preferred channel."""
    headers = _idp_headers(state)
    response = await client.post(f"{IDP}/auth/v1/multiFactorsAuthenticate", headers=headers)
    _upstream(response, "second factor check")
    if response.status_code != 403 or "strong_authentication_required" not in _error_codes(response):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"second factor check answered {response.status_code}")

    response = await client.get(f"{IDP}/auth/v1/strongAuthenticationModes", headers=headers)
    _upstream(response, "second factor modes")
    if response.status_code != 200:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"second factor modes answered {response.status_code}")
    offered = {
        item.get("id")
        for item in _json(response, "second factor modes").get("items") or []
        if isinstance(item, dict) and item.get("status") == "VALID"
    }
    mode = next((m for m in OTP_MODES if m in offered), None)
    if mode is None:
        raise NatixisError(501, "MFA_TYPE_UNSUPPORTED", "No SMS, e-mail or voice second factor")

    response = await client.post(f"{IDP}/auth/v1/strongAuthentications", json={"mode": mode}, headers=headers)
    _upstream(response, "code request")
    if response.status_code not in (200, 201):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"code request answered {response.status_code}")
    href = _json(response, "code request")["_links"]["verification"]["href"]
    state["verificationUrl"] = _trusted(urljoin(IDP + "/", href))
    return mode


async def _verify_code(client: httpx.AsyncClient, state: dict[str, Any], code: str) -> None:
    headers = _idp_headers(state)
    response = await client.post(state["verificationUrl"], json={"code": code}, headers=headers)
    _upstream(response, "code check")
    if response.status_code == 410:
        raise NatixisError(410, "AUTH_ATTEMPT_EXPIRED", "code expired")
    if response.status_code == 423:
        raise NatixisError(429, "RATE_LIMITED", "too many codes")
    if 400 <= response.status_code < 500:
        raise NatixisError(400, "INVALID_OTP", f"code check answered {response.status_code}")
    if response.status_code not in (200, 201):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"code check answered {response.status_code}")
    strong = _json(response, "code check")["id"]

    response = await client.post(f"{IDP}/auth/v1/multiFactorsAuthenticate",
                                 headers={**headers, "strong-authentication": strong})
    _upstream(response, "second factor")
    if response.status_code not in (200, 201):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"second factor answered {response.status_code}")


async def _finish_login(client: httpx.AsyncClient, state: dict[str, Any]) -> dict[str, Any]:
    """Profile check → SAML response posted back → authorization code → tokens."""
    response = await client.post(f"{IDP}/auth/v1/profiles/me/verifications", headers=_idp_headers(state))
    _upstream(response, "profile check")
    if response.status_code not in (200, 201):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"profile check answered {response.status_code}")
    saml = _json(response, "profile check")["saml"]
    # The SPA posts the RelayState the IdP leaves out as the string "undefined"; the
    # authorization server expects that field, so it is mirrored as such.
    response = await client.post(_trusted(saml["serviceProviderUrl"]), data={
        "SAMLResponse": saml["encodedValue"],
        "RelayState": saml.get("relayState") or "undefined",
    })
    _upstream(response, "SAML response")
    location = response.headers.get("location", "")
    query = parse_qs(urlsplit(location).query)
    if response.status_code not in (302, 303) or not location.startswith(REDIRECT_URI) or "code" not in query:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "SAML response gave no authorization code")
    if query.get("state", [None])[0] != state["oauthState"]:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "Authorization state mismatch")

    tokens = await _token(client, {
        "grant_type": "authorization_code",
        "code": query["code"][0],
        "redirect_uri": REDIRECT_URI,
        "code_verifier": state["verifier"],
    }, refused="AUTH_ATTEMPT_EXPIRED")
    employee_id = await _employee_id(client, tokens["access_token"])
    return {"tokens": tokens, "employeeId": employee_id}


async def _token(client: httpx.AsyncClient, grant: dict[str, str], refused: str = "SESSION_EXPIRED") -> dict[str, Any]:
    response = await client.post(f"{OAUTH}/token", data={**grant, "client_id": CLIENT_ID},
                                 headers={"Accept": "application/json"})
    _upstream(response, "token")
    if response.status_code in (400, 401):
        status = 401 if refused == "SESSION_EXPIRED" else 410
        raise NatixisError(status, refused, f"token grant refused ({response.status_code})")
    if response.status_code != 200:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"token answered {response.status_code}")
    tokens = _json(response, "token")
    if not isinstance(tokens.get("access_token"), str) or not isinstance(tokens.get("refresh_token"), str):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "token answer lacks a token")
    return tokens


async def _api_get(client: httpx.AsyncClient, access_token: str, path: str, params: dict | None = None) -> Any:
    if not _API_PATH.fullmatch(path):
        # Nothing reaches the network: the path came out of a shape Picsou did not expect.
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "API path outside the allowed shapes")
    response = await client.get(f"{API}{path}", params=params,
                                headers={"Authorization": f"Bearer {access_token}", "Accept": "application/json"})
    _upstream(response, "API")
    if response.status_code == 401:
        raise NatixisError(401, "SESSION_EXPIRED", "API answered 401")
    if response.status_code != 200:
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", f"API answered {response.status_code}")
    return _json(response, "API")


async def _employee_id(client: httpx.AsyncClient, access_token: str) -> str:
    employee_id = (await _api_get(client, access_token, "/auth/v3/users/me")).get("id")
    if not isinstance(employee_id, str) or not re.fullmatch(r"[0-9A-Za-z-]{1,40}", employee_id):
        raise NatixisError(502, "UPSTREAM_FORMAT_CHANGED", "users/me carries no usable id")
    return employee_id


# ─── Session state ──────────────────────────────────────────────────────────


def serialize_session(refresh_token: str, device_id: str, employee_id: str) -> str:
    return json.dumps({
        "v": SESSION_STATE_VERSION,
        "refreshToken": refresh_token,
        "deviceId": device_id,
        "employeeId": employee_id,
    }, separators=(",", ":"))


def parse_session(raw: str) -> dict[str, str]:
    try:
        state = json.loads(raw)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if (
        not isinstance(state, dict)
        or state.get("v") != SESSION_STATE_VERSION
        or not all(isinstance(state.get(k), str) and state[k] for k in ("refreshToken", "deviceId", "employeeId"))
        or not re.fullmatch(r"[0-9A-Za-z-]{1,40}", state["employeeId"])
    ):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return state


# ─── Positions ──────────────────────────────────────────────────────────────


def _employee_path(employee_id: str) -> str:
    """The saver's path segment. The id was checked against a strict pattern already
    (users/me, parse_session); it is still percent-encoded so nothing in it can ever
    change the path or the host of an API call."""
    if not re.fullmatch(r"[0-9A-Za-z-]{1,40}", employee_id):
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    return quote(employee_id, safe="")


async def collect_plans(client: httpx.AsyncClient, access_token: str, employee_id: str) -> dict:
    base = f"/saving/v1/employees/{_employee_path(employee_id)}"
    savings = await _api_get(client, access_token, f"{base}/savings", {"gainsOrLosses": "true"})
    products = await _api_get(client, access_token, f"{base}/products",
                              {"includeGainsOrLosses": "true", "includeExternalAccounts": "true"})
    held = fund_ids(savings)
    history = await _operation_details(client, access_token, employee_id)
    sold_out = [f for f in (operation_fund_ids(history[1]) if history else []) if f not in held]
    codes = await _fund_codes(client, access_token, held + sold_out)
    plans = parse_plans(savings, products, employee_id, codes)
    operations = None
    operations_from = None
    if history is not None:
        # Validated here, not in the route: a history Picsou cannot take is dropped
        # whole and the plans still sync, never the other way round.
        try:
            operations = [OperationPayload.model_validate(o)
                          for o in parse_operations(history[0], history[1], employee_id, codes)]
            operations_from = history[2]
        except SavingsFormatError as exc:
            log.warning("Natixis operations not imported code=%s: %s", exc.code, exc)
        except ValidationError as exc:
            log.warning("Natixis operations not imported error_type=ValidationError errors=%d", exc.error_count())
        except Exception as exc:  # noqa: BLE001 -- any other shape the parser did not foresee
            log.warning("Natixis operations not imported error_type=%s", type(exc).__name__)
    return {"plans": plans, "operations": operations,
            "operationsFrom": operations_from if operations is not None else None}


async def _operation_details(client: httpx.AsyncClient, access_token: str,
                             employee_id: str) -> tuple[list[dict], list[dict], str] | None:
    """The finished operations and their details, or None when the history cannot be
    read in full -- a partial history must never pass for the whole one. The history
    is optional, like the fund details: no failure here, a 401 included, ever fails
    the plans or ends a session the savings read just proved alive. Only the route's
    own deadline (a cancellation, not an exception) goes through."""
    today = time.strftime("%Y-%m-%d")
    since = time.strftime("%Y-%m-%d", time.localtime(time.time() - OPERATIONS_HISTORY_YEARS * 365 * 86400 + 86400))
    failure = ""
    try:
        async with asyncio.timeout(OPERATIONS_BUDGET_SECONDS):
            listing = await _api_get(client, access_token, f"/transaction/v1/employees/{_employee_path(employee_id)}/transactions",
                                     {"fromDate": since, "toDate": today, "status": ""})
            demands = finished_demands(listing, employee_id)
            gate = asyncio.Semaphore(FUND_DETAIL_CONCURRENCY)

            async def detail(demand: dict) -> dict:
                async with gate:
                    return await _api_get(client, access_token, demand["path"])

            details = await _all_or_none(detail(d) for d in demands)
            return demands, details, since
    except Exception as exc:  # noqa: BLE001 -- the history is optional, see above
        errors = exc.exceptions if isinstance(exc, ExceptionGroup) else [exc]
        # Codes and error types only: a message may quote what Natixis sent.
        failure = " " + ",".join(sorted({
            f"code={e.code}" if isinstance(e, NatixisError) else f"error_type={type(e).__name__}"
            for e in errors
        }))
    log.warning("Natixis operations not read%s", failure)
    return None


async def _all_or_none(reads) -> list:
    """Every read's result in order, or an ExceptionGroup of the failures. The first
    failure cancels the other reads, so none is left running once the history is given up. Not a
    TaskGroup: on Python 3.12 a failed child leaves the request task cancelling, and
    the route deadline that fires afterwards then escapes as a bare CancelledError."""
    tasks = [asyncio.ensure_future(r) for r in reads]
    if not tasks:
        return []
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_EXCEPTION)
    finally:
        for task in tasks:
            task.cancel()
    failures = [t.exception() for t in done if not t.cancelled() and t.exception() is not None]
    if failures:
        raise ExceptionGroup("operation details", failures)
    return [task.result() for task in tasks]


async def _fund_codes(client: httpx.AsyncClient, access_token: str, ids: list[int]) -> dict[int, str | None]:
    """The AMF code of each fund, best effort. It only labels a line, so no failure
    here -- an error, a timeout, a 401 -- ever blocks a sync or ends a session the
    savings read just proved alive; a fund left without one keeps its Natixis id."""
    gate = asyncio.Semaphore(FUND_DETAIL_CONCURRENCY)

    async def one(fund_id: int) -> tuple[int, str | None]:
        async with gate:
            try:
                return fund_id, fund_code(await _api_get(client, access_token, f"/fund/v1/funds/{fund_id}"))
            except (NatixisError, httpx.HTTPError, AttributeError, TypeError, ValueError):
                return fund_id, None

    if not ids:
        return {}
    tasks = [asyncio.ensure_future(one(fund_id)) for fund_id in ids]
    done, late = await asyncio.wait(tasks, timeout=FUND_DETAIL_BUDGET_SECONDS)
    for task in late:
        task.cancel()
    if late:
        log.warning("Natixis fund details: %d of %d not back in time", len(late), len(ids))
    codes: dict[int, str | None] = {fund_id: None for fund_id in ids}
    codes.update(task.result() for task in done)
    return codes


# ─── Routes ─────────────────────────────────────────────────────────────────


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _pending.expire()
    process_id = str(uuid.uuid4())
    client = _new_client()
    # The slot is reserved before Natixis is asked to send a code: a full store must
    # refuse the login up front, not after the saver received a code nothing can use.
    attempt: dict[str, Any] = {"client": client, "created_at": time.time(), "ready": False}
    try:
        await _pending.put_or_raise(process_id, attempt)
    except BaseException:
        await client.aclose()
        raise
    ready = False
    try:
        async with _errors("login"):
            state = await _open_login(client)
            await _password_login(client, state, req.email, req.password)
            mode = await _send_code(client, state)
            attempt.update(state)
            attempt["ready"] = ready = True
            return {"processId": process_id, "mfaRequired": True, "mfaType": mode}
    finally:
        if not ready:
            await _pending.close(process_id)


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    state = await _pending.take(req.processId)
    if state is None:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if not state.get("ready"):
        # Still initiating: hand the attempt back to /initiate untouched.
        await _pending.repark(req.processId, state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    client: httpx.AsyncClient = state["client"]
    retry = False
    try:
        async with _errors("code check"):
            try:
                await _verify_code(client, state, req.code)
            except NatixisError as exc:
                if exc.code == "INVALID_OTP":
                    state["codeAttempts"] = state.get("codeAttempts", 0) + 1
                    if state["codeAttempts"] >= MAX_CODE_ATTEMPTS:
                        # Saying "wrong code" here would invite a retry the attempt no longer allows.
                        raise NatixisError(410, "AUTH_ATTEMPT_EXPIRED", "too many wrong codes") from exc
                    # A mistyped code keeps the attempt: the saver retries with the same processId.
                    await _pending.repark(req.processId, state)
                    retry = True
                raise
            result = await _finish_login(client, state)
            return {"sessionState": serialize_session(
                result["tokens"]["refresh_token"], state["deviceId"], result["employeeId"])}
    finally:
        if not retry:
            await _dispose(state)


@app.post("/cancel", status_code=204)
async def cancel(req: CancelRequest) -> Response:
    """Drop a login waiting for its code, freeing its slot at once. Idempotent."""
    await _pending.close(req.processId)
    return Response(status_code=204)


@app.post("/positions", response_model=PositionsResponse)
async def positions(req: PositionsRequest) -> dict:
    session = parse_session(req.sessionState)
    async with _new_client() as client:
        async with _errors("positions", POSITIONS_DEADLINE_SECONDS):
            tokens = await _token(client, {"grant_type": "refresh_token", "refresh_token": session["refreshToken"]})
            try:
                read = await collect_plans(client, tokens["access_token"], session["employeeId"])
            except Exception:
                if tokens["refresh_token"] != session["refreshToken"]:
                    # The error contract carries no session state, so a rotated
                    # token is lost here and the next sync will need a new code.
                    log.warning("Natixis rotated the refresh token and the read failed: the session will need a new code")
                raise
            # Natixis hands back the same refresh token today; storing whatever it
            # returns keeps the session alive if it ever starts rotating them.
            state = serialize_session(tokens["refresh_token"], session["deviceId"], session["employeeId"])
            operations = read["operations"]
            return {
                "sessionState": state,
                "plans": [PlanPayload.model_validate(p) for p in read["plans"]],
                "operationsComplete": operations is not None,
                "operationsFrom": read["operationsFrom"],
                "operations": operations or [],
            }
