"""Read-only Sofidy Espace Associé authentication and SCPI positions sidecar.

Sofidy has no public API. This service speaks the client space over plain HTTPS --
no browser is needed: the pages are server-rendered PHP and the whole portfolio
sits in the initial HTML of `3,clients.html`, with no JavaScript challenge and no
bot wall in front of it. The login choreography is taken from the site's own
`scripts/javascript/general.js`, not guessed.

Auth flow:
  POST /initiate {associateCode, password}
    → second factor by e-mail: {processId, mfaRequired: true, mfaType: "EMAIL_CODE"}
    → already trusted:      {processId: null, mfaRequired: false, sessionState}
  POST /complete {processId, code}
    → {sessionState}
  POST /positions {sessionState}
    → [{fundCode, label, shareCount, withdrawalPriceEur, totalEur, valuationDate,
        snapshotComplete}]

Only the session cookies are returned to Java, which encrypts them before
storage. Credentials are held in memory for the length of one request and are
never logged; neither are cookies or raw financial responses.

The portal is PHP and answers every business page with HTTP 200, whether or not
the session is alive -- the logged-out response is the login form rendered inside
the same page. Liveness is therefore detected by the presence of the login form,
never by a status code: a 200 on the portfolio page is not a portfolio.
"""

import asyncio
import html as html_module
import json
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any, Literal

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from positions_parser import (
    Holding,
    PositionsFormatError,
    parse_portfolio,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("sofidy-auth")

BASE_URL = "https://moncompte.sofidy.com"
# The portal hands out PHPSESSID on the first page view, so the login POST has
# to follow a page fetch or it lands on a session that was never established.
HOME_PATH = "/1,accueil.html"
LOGIN_PATH = "/scripts/ajax/connex.php"
VERIFY_2FA_PATH = "/scripts/ajax/verify2fa.php"
PORTFOLIO_PATH = "/3,clients.html"
LOGIN_FORM_MARKER = "connexion_espace_partenaire"

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/141.0.0.0 Safari/537.36"
)
REQUEST_TIMEOUT_SECONDS = 30.0

# The portal shows a 120 s countdown before it will re-send a verification code,
# and says the code itself expires. Holding the attempt open much longer than
# that only accumulates stale half-authenticated sessions.
PENDING_TTL_SECONDS = 300
PENDING_SWEEP_SECONDS = 30
# One pending attempt pins an httpx client and its cookie jar.
MAX_PENDING = 16

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


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


app = FastAPI(title="picsou-sofidy-auth", lifespan=lifespan)


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    """Times every call and logs the method, path and status only.

    No body, no cookie, no identifier: this log is what an operator reads when a
    login starts failing, and a financial page or a credential must never land in
    it.
    """
    started = time.time()
    response = await call_next(request)
    log.info(
        "%s %s -> %s (%.0fms)",
        request.method,
        _log_safe(request.url.path),
        response.status_code,
        (time.time() - started) * 1000,
    )
    return response


def _log_safe(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9._/-]", "?", value)[:120]


# ─── payloads ────────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    associateCode: str = Field(min_length=6, max_length=6, pattern=r"^[0-9]{6}$")
    password: str = Field(min_length=1, max_length=200)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    processId: str = Field(min_length=1, max_length=64)
    code: str = Field(min_length=4, max_length=12, pattern=r"^[0-9]{4,12}$")


class SessionRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    sessionState: str = Field(min_length=1)


class SessionResponse(BaseModel):
    sessionState: str


class InitiateResponse(BaseModel):
    """A Sofidy login always leaves `processId` or `sessionState` set, never both.

    Modelled separately from `SessionResponse` because `sessionState` is null
    while a verification code is outstanding: reusing the session model here
    would make the second-factor answer fail its own response validation.
    """

    processId: str | None = None
    mfaRequired: bool
    mfaType: str | None = None
    sessionState: str | None = None


class HoldingPayload(BaseModel):
    fundCode: str
    label: str
    shareCount: Decimal
    withdrawalPriceEur: Decimal | None
    totalEur: Decimal
    snapshotComplete: bool = True


class SnapshotPayload(BaseModel):
    valuationDate: str | None
    holdings: list[HoldingPayload]
    snapshotComplete: bool = True


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request, exc: RequestValidationError
) -> JSONResponse:
    """A malformed body must read as a 400, not as an upstream outage.

    FastAPI's own handler answers 422, and every adapter here maps anything
    above 400 on a login to INVALID_CREDENTIALS -- which would send the operator
    looking at the portal instead of at the caller.
    """
    return JSONResponse(status_code=400, content={"detail": "INVALID_REQUEST"})


# ─── pending second factors ──────────────────────────────────────────────────


def _new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=BASE_URL,
        timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
        follow_redirects=True,
        headers={
            "User-Agent": USER_AGENT,
            "Accept-Language": "fr-FR,fr;q=0.9",
        },
    )


def serialize_cookies(client: httpx.AsyncClient) -> str:
    cookies = [
        {
            "name": cookie.name,
            "value": cookie.value or "",
            "domain": cookie.domain or "",
            "path": cookie.path or "/",
        }
        for cookie in client.cookies.jar
    ]
    return json.dumps({"cookies": cookies}, separators=(",", ":"))


def restore_cookies(client: httpx.AsyncClient, raw: str) -> None:
    try:
        payload = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_REQUEST") from exc
    cookies = payload.get("cookies") if isinstance(payload, dict) else None
    if not isinstance(cookies, list) or not cookies:
        raise HTTPException(status_code=400, detail="INVALID_REQUEST")
    for cookie in cookies:
        if not isinstance(cookie, dict):
            raise HTTPException(status_code=400, detail="INVALID_REQUEST")
        client.cookies.set(
            cookie.get("name", ""),
            cookie.get("value", ""),
            domain=cookie.get("domain", ""),
            path=cookie.get("path", "/"),
        )


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    client: httpx.AsyncClient | None = state.get("client")
    if client is not None:
        try:
            await client.aclose()
        except Exception:  # pragma: no cover -- teardown must not raise
            log.debug("Sofidy pending client close failed", exc_info=True)


async def _close_pending(process_id: str) -> None:
    async with _pending_lock:
        state = _pending.pop(process_id, None)
    if state:
        await _dispose_pending_state(state)


async def _store_pending(process_id: str, state: dict[str, Any]) -> bool:
    async with _pending_lock:
        if len(_pending) >= MAX_PENDING:
            return False
        _pending[process_id] = state
        return True


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, s in _pending.items() if s["created_at"] < cutoff]
    for pid in expired:
        await _close_pending(pid)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _pending_sweeper() -> None:
    while True:
        try:
            await asyncio.sleep(PENDING_SWEEP_SECONDS)
            await _cleanup_expired()
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover -- a sweep must never kill the loop
            log.warning("Sofidy pending sweep failed", exc_info=True)


# ─── portal calls ────────────────────────────────────────────────────────────


async def _post(client: httpx.AsyncClient, path: str, data: dict[str, str]) -> str:
    response = await client.post(path, data=data)
    if response.status_code >= 500 or response.status_code == 429:
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    if response.status_code != 200:
        # The login endpoint answers 200 with a bare token for every outcome,
        # including a wrong password, so a non-200 here is the portal refusing
        # the request itself rather than rejecting the credentials.
        log.warning("Sofidy request refused (path=%s; status=%s)", _log_safe(path), response.status_code)
        raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
    return response.text.strip()


def _is_logged_in(page: str) -> bool:
    return LOGIN_FORM_MARKER not in page


async def _home(client: httpx.AsyncClient) -> str:
    response = await client.get(HOME_PATH)
    if response.status_code != 200:
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    return response.text


# Login outcomes, as the portal's own JS distinguishes them. Each one gets its
# own code: a rate-limited portal, a dormant account and a wrong code are three
# different operator actions, and collapsing them into INVALID_CREDENTIALS points
# all three at the wrong place.
_LOGIN_OUTCOMES = {
    "faux": ("INVALID_CREDENTIALS", 401),
    "compteur_4": ("RATE_LIMITED", 429),
    "compteur5": ("RATE_LIMITED", 429),
    "24_mail": ("RATE_LIMITED", 429),
    "inactif": ("ACCOUNT_INACTIVE", 403),
    "mailko": ("EMAIL_UNREACHABLE", 403),
    "firstyes": ("FIRST_VISIT_PENDING", 409),
    # A JSON body means the credentials were accepted and Sofidy is parking the
    # login on its brute-force counter.
    "__json__": ("RATE_LIMITED", 429),
}


def _classify_login(raw: str) -> tuple[str, int] | None:
    """None means the login succeeded (or is waiting on a second factor)."""
    text = raw.strip()
    if text.startswith("2FA|"):
        return None
    if text.startswith("{") or text.startswith("["):
        return _LOGIN_OUTCOMES["__json__"]
    return _LOGIN_OUTCOMES.get(text)


# ─── routes ──────────────────────────────────────────────────────────────────


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    client = _new_client()
    keep_client = False
    try:
        # The portal issues PHPSESSID on the first page view; the login POST
        # reuses it, so a client that never loaded the login form is rejected.
        await _home(client)
        raw = await _post(client, LOGIN_PATH, {"user": req.associateCode, "pass": req.password})

        failure = _classify_login(raw)
        if failure:
            detail, status = failure
            log.warning("Sofidy login rejected (code=%s)", detail)
            raise HTTPException(status_code=status, detail=detail)

        if raw.startswith("2FA|"):
            process_id = str(uuid.uuid4())
            state = {
                "client": client,
                "associateCode": req.associateCode,
                "password": req.password,
                "created_at": time.time(),
            }
            if not await _store_pending(process_id, state):
                raise HTTPException(status_code=503, detail="TOO_MANY_PENDING")
            keep_client = True
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "EMAIL_CODE",
                "sessionState": None,
            }

        if not _is_logged_in(await _home(client)):
            # The portal accepted the POST and answered with no recognisable
            # token, but the session is not usable. Treating this as a login
            # success would store a session that fails on the first sync.
            log.warning("Sofidy login answered %s without opening a session", _log_safe(raw[:24]))
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        return {
            "processId": None,
            "mfaRequired": False,
            "mfaType": None,
            "sessionState": serialize_cookies(client),
        }
    except PositionsFormatError as exc:  # pragma: no cover -- login parses no HTML
        log.warning("Sofidy login payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("Sofidy authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Sofidy authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        if not keep_client:
            await client.aclose()


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    await _cleanup_expired()
    state = await _take_pending(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - PENDING_TTL_SECONDS:
        await _dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    client: httpx.AsyncClient = state["client"]
    try:
        raw = (
            await _post(
                client,
                VERIFY_2FA_PATH,
                {
                    "user": state["associateCode"],
                    "pass": state["password"],
                    "code": req.code,
                },
            )
        ).strip().lower()
        if raw not in ("fauxyes", "firstyes"):
            # The portal's own script treats *every* other answer as "the code
            # you entered is wrong or expired" and offers to re-send one, so that
            # is the only reading its contract supports. 401 here means "try
            # again with another code": not a dead session, and not an outage the
            # operator should go looking for.
            raise HTTPException(status_code=401, detail="MFA_INVALID")

        if not _is_logged_in(await _home(client)):
            log.warning("Sofidy verification succeeded without opening a session")
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        return {"sessionState": serialize_cookies(client)}
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("Sofidy authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Sofidy authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        # The pending client is handed to the caller only as serialised cookies,
        # so it can always be closed here.
        await _dispose_pending_state(state)


@app.post("/positions", response_model=SnapshotPayload)
async def positions(req: SessionRequest) -> dict:
    client = _new_client()
    try:
        restore_cookies(client, req.sessionState)
        page = await _home(client)
        if not _is_logged_in(page):
            # The portal answers 200 with the login form for an expired session.
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        response = await client.get(PORTFOLIO_PATH)
        if response.status_code == 401 or response.status_code == 403:
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
        if response.status_code == 429 or response.status_code >= 500:
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        if response.status_code != 200:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        html = response.text
        if not _is_logged_in(html):
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        snapshot = parse_portfolio(html)
        return SnapshotPayload.model_validate(snapshot).model_dump()
    except PositionsFormatError as exc:
        log.warning("Sofidy payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except ValidationError as exc:
        log.warning("Sofidy payload failed validation: %s", exc.error_count())
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("Sofidy positions fetch failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Sofidy positions failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await client.aclose()
