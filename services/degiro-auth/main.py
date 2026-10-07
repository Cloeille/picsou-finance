"""
DEGIRO Auth & Sync Sidecar
--------------------------
Handles DEGIRO authentication (username/password + TOTP) and portfolio
valuation/positions fetching, via DEGIRO's unofficial, reverse-engineered
trading API (no official public API exists — see
docs/features/degiro-sync.md and the design ADR referenced there).

The endpoint shapes below started out reconstructed from public reference
implementations (Chavithra/degiro-connector, bramton/degiro) and have since been
confirmed end-to-end against a live account: /login/secure/login and its /totp
variant, /pa/secure/client, /trading/secure/v5/update (portfolio + cashFunds, in
the name/value-pair shape portfolio_parser handles) and
/product_search/secure/v5/products/info. Two live-only quirks that the reference
implementations do not mention are handled explicitly: the "FLATEX_EUR" cash
pseudo-position mixed in among real security ids, and the literal string "NULL"
standing in for a missing product-info field. Remaining gaps are tracked in
docs/features/degiro-sync.md "Known limitations".

Auth flow:
  POST /initiate  {username, password}
    → no TOTP:  {processId, totpRequired: false, sessionBlob: "..."}
    → TOTP:     {processId, totpRequired: true}

  POST /complete  {processId, code}  (only when totpRequired)
    → {sessionBlob: "..."}

  POST /portfolio  {sessionBlob: "..."}
    → {cashEur: 0.0, positions: [{isin, symbol, name, quantity, buyingPrice, currentPrice}]}

Session state is stored in memory only during the auth flow (keyed by processId).
After auth completes, an opaque {sessionId, intAccount} blob is returned to Java
for encrypted storage. Unlike the other broker sidecars, this session is
short-lived (DEGIRO times out the cookie after ~30 min of inactivity, no refresh
token) — a 401 from DEGIRO always surfaces as HTTP 401 from /portfolio, never
retried here. Picsou never stores the account's TOTP secret, so there is no
unattended re-authentication — see
docs/decisions/2026-08-05-degiro-session-only-no-stored-totp.md.
"""

import json
import logging
import os
import re
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from portfolio_parser import build_positions, build_product_info_map, parse_cash_eur, parse_raw_positions

# C0/C1 controls (CR, LF, ESC, NEL...) and the Unicode line/paragraph separators.
_LOG_UNSAFE = re.compile(r"[\x00-\x1f\x7f-\x9f  ]")


class SafeFormatter(logging.Formatter):
    """Escapes line breaks and control characters in the whole formatted record,
    traceback included, so a logged value cannot forge a log line."""

    def format(self, record: logging.LogRecord) -> str:
        return _LOG_UNSAFE.sub(
            lambda m: m.group().encode("unicode_escape").decode("ascii"),
            super().format(record),
        )


def safe(value: object, limit: int = 500) -> str:
    """An outside-controlled value made safe for a log line: truncated, line
    breaks and control characters replaced with '?'. SafeFormatter already
    covers the handler; this keeps the call site safe on its own."""
    text = str(value)
    if len(text) > limit:
        text = text[:limit] + "..."
    text = text.replace("\r\n", "?").replace("\n", "?")
    return _LOG_UNSAFE.sub("?", text)


_log_handler = logging.StreamHandler()
_log_handler.setFormatter(SafeFormatter(logging.BASIC_FORMAT))
logging.basicConfig(level=logging.INFO, handlers=[_log_handler])
log = logging.getLogger("degiro-auth")
SIDECAR_API_KEY = os.environ.get("APP_SIDECAR_API_KEY", "")


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not SIDECAR_API_KEY.strip():
        raise RuntimeError("APP_SIDECAR_API_KEY must be configured and non-blank")
    yield


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def authenticate_sidecar_request(request: Request, call_next):
    if request.url.path != "/health":
        supplied_key = request.headers.get("X-Picsou-Sidecar-Key", "")
        # Starlette exposes wire header bytes through Latin-1, not UTF-8.
        if (
            not SIDECAR_API_KEY.strip()
            or not secrets.compare_digest(
                supplied_key.encode("latin-1"), SIDECAR_API_KEY.encode("utf-8")
            )
        ):
            return JSONResponse(
                status_code=401,
                content={"detail": "UNAUTHORIZED"},
                headers={"WWW-Authenticate": "Picsou-Sidecar-Key"},
            )
    return await call_next(request)


DEGIRO_BASE = "https://trader.degiro.nl"

# The JSESSIONID cookie value is opaque, so the pattern only keeps characters that stay
# inside a URL path segment: no / ? # % ; @ \, whitespace or controls.
SESSION_ID_PATTERN = r"[A-Za-z0-9._~!*+=,:-]{1,256}"

# In-memory auth state: processId → {username, password, created_at}
# Cleaned up after /complete or after TTL. Credentials are held only long
# enough to retry the login with a TOTP code — never logged, never persisted.
_pending: dict[str, dict] = {}
_PENDING_TTL = 300  # 5 minutes — DEGIRO TOTP codes are valid 30s, generous margin for user entry

_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)


_REDACTED_KEYS = {
    "sessionid", "password", "onetimepassword", "token", "accesstoken", "refreshtoken",
    "email", "emailaddress", "username", "firstcontact", "address", "phonenumber",
    "mobilephonenumber", "cellphonenumber", "bankaccount", "iban",
}


def _redact(value):
    """Strips credentials and PII out of a DEGIRO response before it is logged.

    These bodies are logged to make the unofficial API's real shapes debuggable, but a
    login response carries a live `sessionId` (a usable bearer of the whole session)
    and the client/profile response carries the account holder's name, email, address
    and bank details. Keys are matched case-insensitively and the walk is recursive,
    since DEGIRO nests everything under `data`.
    """
    if isinstance(value, dict):
        return {
            k: "***" if k.lower() in _REDACTED_KEYS else _redact(v)
            for k, v in value.items()
        }
    if isinstance(value, list):
        return [_redact(v) for v in value]
    return value


def _safe_body(payload) -> str:
    return json.dumps(_redact(payload))[:500]


def _clean_pending():
    now = time.time()
    expired = [k for k, v in _pending.items() if now - v.get("created_at", 0) > _PENDING_TTL]
    for k in expired:
        _pending.pop(k, None)


def _client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=DEGIRO_BASE,
        timeout=httpx.Timeout(30.0),
        headers={"User-Agent": _USER_AGENT, "Content-Type": "application/json"},
    )


def _session_id_from_cookies(client: httpx.AsyncClient) -> Optional[str]:
    for name, value in client.cookies.items():
        if name.upper() == "JSESSIONID":
            return value
    return None


_TOTP_NEEDED_STATUS_VALUES = {6}
_TOTP_NEEDED_STATUS_TEXT_VALUES = {"totpNeeded", "totpRequired"}


async def _login(client: httpx.AsyncClient, username: str, password: str, totp: Optional[str]) -> dict:
    """
    POST /login/secure/login (or /login/secure/login/totp when a code is supplied).
    Returns {"needsTotp": bool} or {"sessionId": "..."} on success.

    Confirmed live, including the TOTP variant, but deliberately defensive: real
    logins returned shapes this function did not originally anticipate (an HTTP 200 +
    session cookie with no usable account behind it, and a plain HTTP 202 on the
    initial POST), so the branches below stay explicit rather than assuming one
    canonical success shape. Every response is logged — status, whether a cookie came
    back, and the body via `_safe_body`, which redacts the session id and any PII, and
    never includes the submitted credentials.
    """
    path = "/login/secure/login/totp" if totp else "/login/secure/login"
    body = {"username": username, "password": password, "isPassCodeReset": False, "isRedirectToMobile": False}
    if totp:
        body["oneTimePassword"] = totp

    resp = await client.post(path, json=body)
    session_id = _session_id_from_cookies(client)

    try:
        payload = resp.json()
    except ValueError:
        payload = {}

    log.info(
        "DEGIRO login response: HTTP %s, session_cookie=%s, body=%s",
        resp.status_code, bool(session_id), _safe_body(payload),
    )

    needs_totp = (
        not totp
        and (payload.get("status") in _TOTP_NEEDED_STATUS_VALUES
             or payload.get("statusText") in _TOTP_NEEDED_STATUS_TEXT_VALUES)
    )
    if needs_totp:
        return {"needsTotp": True}

    if session_id:
        # A cookie without a usable account behind it (see _fetch_int_account) is a
        # separate, already-logged failure mode — not re-guessed here.
        return {"sessionId": session_id}

    if resp.status_code in (400, 401):
        raise HTTPException(status_code=401, detail="Invalid DEGIRO credentials or TOTP code")

    raise HTTPException(
        status_code=502,
        detail=f"Unexpected response from DEGIRO login (HTTP {resp.status_code}) — see degiro-auth logs for the response body",
    )


async def _fetch_int_account(client: httpx.AsyncClient, session_id: str) -> int:
    resp = await client.get("/pa/secure/client", params={"sessionId": session_id})
    try:
        payload = resp.json()
    except ValueError:
        payload = {}
    log.info("DEGIRO /pa/secure/client response: HTTP %s, body=%s", resp.status_code, _safe_body(payload))

    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail="Could not resolve DEGIRO account after login")
    int_account = payload.get("data", {}).get("intAccount")
    if int_account is None:
        # Seen live: a session cookie can come back before 2FA is actually satisfied —
        # this is likely that state, not a different account-resolution bug. The logged
        # body above is what tells us for sure.
        raise HTTPException(status_code=502, detail="DEGIRO login succeeded but no account was returned")
    try:
        return int(int_account)
    except (TypeError, ValueError) as ex:
        raise HTTPException(status_code=502, detail="DEGIRO returned an invalid account number") from ex


async def _fetch_portfolio(client: httpx.AsyncClient, session_id: str, int_account: int) -> dict:
    resp = await client.get(
        f"/trading/secure/v5/update/{int_account};jsessionid={session_id}",
        params={"portfolio": 0, "totalPortfolio": 0, "cashFunds": 0},
    )
    if resp.status_code == 401:
        raise HTTPException(status_code=401, detail="DEGIRO session expired")
    if resp.status_code != 200:
        raise HTTPException(status_code=502, detail="Could not fetch DEGIRO portfolio")

    data = resp.json()
    cash_eur = parse_cash_eur(data.get("cashFunds", {}).get("value", []))
    raw_positions = parse_raw_positions(data.get("portfolio", {}).get("value", []))
    product_ids = [p["productId"] for p in raw_positions if p["productId"] is not None]

    products = await _fetch_product_info(client, session_id, int_account, product_ids) if product_ids else {}
    positions = build_positions(raw_positions, products)

    return {"cashEur": cash_eur, "positions": positions}


async def _fetch_product_info(client: httpx.AsyncClient, session_id: str, int_account: int, product_ids: list) -> dict:
    """
    POST /product_search/secure/v5/products/info — resolves productId → ISIN/name.

    Confirmed live. On any failure, positions still come through (see caller) with
    the raw productId standing in for symbol/name rather than failing the whole
    sync — an incomplete label is recoverable, a missing position isn't.
    """
    try:
        resp = await client.post(
            "/product_search/secure/v5/products/info",
            params={"intAccount": int_account, "sessionId": session_id},
            json=product_ids,
        )
        if resp.status_code != 200:
            return {}
        data = resp.json().get("data", {})
        # No truncation here (unlike the login/account logs above): this is DEGIRO's
        # own market/product data, not a credential, and the previous 1000-char cap
        # cut the dump off before reaching later product ids in the batch.
        log.info("DEGIRO product info response for %s: %s", product_ids, json.dumps(data))
        products = build_product_info_map(data)
        return products
    except Exception as ex:
        log.warning("DEGIRO product info lookup failed, falling back to raw productId labels: %s", ex)
        return {}


# ─── Request/response models ───────────────────────────────────────────────

class InitiateRequest(BaseModel):
    username: str
    password: str


class CompleteRequest(BaseModel):
    processId: str
    code: str


class PortfolioRequest(BaseModel):
    sessionBlob: str


# ─── Routes ─────────────────────────────────────────────────────────────────

@app.post("/initiate")
async def initiate(req: InitiateRequest):
    _clean_pending()
    log.info("DEGIRO auth initiate for user %s***", safe(req.username[:2]))

    client = _client()
    try:
        result = await _login(client, req.username, req.password, totp=None)
    except BaseException:
        # Not just HTTPException: _login can also raise httpx.ConnectError/ReadTimeout,
        # and the client would then never be closed — every sidecar outage leaking a
        # connection pool.
        await client.aclose()
        raise

    process_id = str(uuid.uuid4())

    if result.get("needsTotp"):
        await client.aclose()
        _pending[process_id] = {"username": req.username, "password": req.password, "created_at": time.time()}
        return {"processId": process_id, "totpRequired": True}

    try:
        int_account = await _fetch_int_account(client, result["sessionId"])
    finally:
        await client.aclose()

    blob = json.dumps({"sessionId": result["sessionId"], "intAccount": int_account})
    return {"processId": process_id, "totpRequired": False, "sessionBlob": blob}


@app.post("/complete")
async def complete(req: CompleteRequest):
    state = _pending.pop(req.processId, None)
    if not state:
        raise HTTPException(status_code=404, detail="processId not found or expired — please re-authenticate")

    log.info("DEGIRO TOTP complete for processId %s", req.processId)
    client = _client()
    try:
        result = await _login(client, state["username"], state["password"], totp=req.code)
        if result.get("needsTotp"):
            raise HTTPException(status_code=401, detail="TOTP code was rejected")
        int_account = await _fetch_int_account(client, result["sessionId"])
    finally:
        await client.aclose()

    blob = json.dumps({"sessionId": result["sessionId"], "intAccount": int_account})
    return {"sessionBlob": blob}


@app.post("/portfolio")
async def portfolio(req: PortfolioRequest):
    try:
        parsed = json.loads(req.sessionBlob)
        session_id = parsed["sessionId"]
        int_account = int(parsed["intAccount"])
    # TypeError too: json.loads happily returns a scalar or a list for a blob like "5"
    # or "[1]", and subscripting that raises TypeError, which would escape as a 500
    # instead of the 400 this is meant to be. ValueError covers a non-numeric
    # intAccount (and is JSONDecodeError's base class).
    except (ValueError, KeyError, TypeError) as ex:
        raise HTTPException(status_code=400, detail="Invalid sessionBlob format") from ex
    # Both values end up in the portfolio URL path; nothing that can leave the segment.
    if not isinstance(session_id, str):
        raise HTTPException(status_code=400, detail="Invalid sessionBlob format")
    if not re.fullmatch(SESSION_ID_PATTERN, session_id):
        raise HTTPException(status_code=400, detail="Invalid sessionBlob format")

    client = _client()
    client.cookies.set("JSESSIONID", session_id, domain="trader.degiro.nl")
    try:
        return await _fetch_portfolio(client, session_id, int_account)
    finally:
        await client.aclose()


@app.get("/health")
async def health():
    return {"status": "ok"}
