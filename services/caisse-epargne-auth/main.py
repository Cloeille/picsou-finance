"""Caisse d'Epargne sidecar: session-replay transport.

Scope: log in through a real browser (`POST /initiate` + `POST /complete`, see
login.py), prove that a captured SSO session can still mint a Bearer token
(`POST /token-check`) and read the accounts with it (`POST /accounts`). Logout
is NOT implemented.

Safety rules, same as the BoursoBank sidecar:
  * every request but /health needs the shared key (`APP_SIDECAR_API_KEY`);
    the service refuses to start without one;
  * cookies, SAML payloads, authorization codes, tokens and authorize
    parameters are never logged and never returned; the token stays in memory.

Error codes (JSON `{"detail": CODE}`):
  400 INVALID_SESSION_STATE   unusable request body or session state
  401 SESSION_EXPIRED         the bank no longer accepts the SSO session
  502 UPSTREAM_ERROR          any other bank-side or transport failure
  500 INTERNAL_ERROR          unexpected bug (no detail leaked)
Login (`/initiate`, `/complete`): 400 INVALID_REQUEST, 401 INVALID_CREDENTIALS,
  408 APP_VALIDATION_TIMEOUT, 409 KEYPAD_CHANGED, 410 AUTH_ATTEMPT_EXPIRED,
  429 TOO_MANY_PENDING, 502 UPSTREAM_UNAVAILABLE / UPSTREAM_FORMAT_CHANGED.
`POST /accounts` uses the bourso-style codes instead of UPSTREAM_ERROR:
  502 UPSTREAM_UNAVAILABLE    bank-side or transport failure
  502 UPSTREAM_FORMAT_CHANGED a payload that does not match the observed format
                              (all-or-nothing: never a partial account list)
"""

import asyncio
import logging
import os
import secrets
import time
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

import fetcher
import login
import replay

logging.basicConfig(level=logging.INFO)
# httpx logs every request URL at INFO and the authorize URL carries the
# authorize parameters in its query string.
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
log = logging.getLogger("caisse-epargne-auth")
SIDECAR_API_KEY = os.environ.get("APP_SIDECAR_API_KEY", "")

# Test seam: tests inject an httpx.MockTransport here. None means real network.
_TRANSPORT: httpx.AsyncBaseTransport | None = None
_token_cache = replay.TokenCache()

# Overall budget of POST /accounts (token replay + every data call). Kept below the
# backend's 120 s read timeout so the sidecar answers before the caller gives up.
ACCOUNTS_DEADLINE_SECONDS: float = 100


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not SIDECAR_API_KEY.strip():
        raise RuntimeError("APP_SIDECAR_API_KEY must be configured and non-blank")
    sweeper = asyncio.create_task(login.sweeper())
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await login.close_all()


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


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            # Uvicorn percent-decodes the path, so a caller can plant CR/LF in it
            # and forge log lines. Strip controls and bound the length.
            log.info(
                "Caisse d'Epargne request completed (path=%s; duration=%.2fs)",
                _log_safe(request.url.path),
                time.monotonic() - started_at,
            )


def _log_safe(value: str) -> str:
    return "".join(char for char in value if char.isprintable())[:200]


# --- Contract ---------------------------------------------------------------


class TokenCheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=200_000)


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=200_000)


class TokenCheckResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ok: bool
    expiresIn: int


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    customerId: str = Field(min_length=1, max_length=200)
    # Shape (digits, 4-20) is checked in login.initiate -> 401 before any browser work.
    password: str = Field(max_length=200, repr=False)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=200)


LOGIN_PATHS = {"/initiate", "/complete"}


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, __: RequestValidationError) -> JSONResponse:
    # Never echo the validation errors: they quote the offending input.
    detail = "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"
    return JSONResponse(status_code=400, content={"detail": detail})


# --- Routes -------------------------------------------------------------------


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate")
async def initiate(req: InitiateRequest) -> dict:
    # One attempt: no retry here or anywhere. The password goes straight to the
    # keypad and is never logged or returned.
    try:
        return await login.initiate(req.customerId, req.password)
    except login.LoginError as exc:
        raise HTTPException(status_code=exc.status, detail=exc.code) from None
    except Exception:
        log.error("initiate failed unexpectedly")  # no exception text: it may quote input
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/complete")
async def complete(req: CompleteRequest) -> dict:
    try:
        return {"sessionState": await login.complete(req.processId)}
    except login.LoginError as exc:
        raise HTTPException(status_code=exc.status, detail=exc.code) from None
    except Exception:
        log.error("complete failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/token-check", response_model=TokenCheckResponse)
async def token_check(req: TokenCheckRequest) -> dict:
    try:
        result = await _token_cache.get(req.sessionState, transport=_TRANSPORT)
    except replay.InvalidSessionState as exc:
        raise HTTPException(status_code=400, detail=exc.code) from None
    except replay.SessionExpired as exc:
        raise HTTPException(status_code=401, detail=exc.code) from None
    except replay.ReplayError as exc:
        raise HTTPException(status_code=502, detail="UPSTREAM_ERROR") from None
    except Exception:
        # Type only: the message may embed session data.
        log.error("token-check failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None
    # The token itself stays in memory; callers only learn that one can be minted.
    return {"ok": True, "expiresIn": int(result.expires_in)}



async def _fetch_accounts(session_state: str) -> dict:
    token = await _token_cache.get(session_state, transport=_TRANSPORT)
    try:
        return await fetcher.fetch_import(token.access_token, transport=_TRANSPORT)
    except replay.SessionExpired:
        # A cached token can be revoked before its expiry: drop it and replay once more.
        # A second rejection (or a failed replay) means the session itself is dead.
        _token_cache.evict(session_state)
        token = await _token_cache.get(session_state, transport=_TRANSPORT)
        return await fetcher.fetch_import(token.access_token, transport=_TRANSPORT)


@app.post("/accounts")
async def accounts(req: AccountsRequest) -> dict:
    try:
        async with asyncio.timeout(ACCOUNTS_DEADLINE_SECONDS):
            return await _fetch_accounts(req.sessionState)
    except TimeoutError:
        # Nothing partial is ever returned: the whole import is dropped.
        log.warning("accounts deadline exceeded")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
    except replay.InvalidSessionState as exc:
        raise HTTPException(status_code=400, detail=exc.code) from None
    except replay.SessionExpired as exc:
        raise HTTPException(status_code=401, detail=exc.code) from None
    except fetcher.FetchError as exc:
        raise HTTPException(status_code=502, detail=exc.code) from None
    except replay.ReplayError:
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
    except Exception:
        # Type only: the message may embed session data or balances.
        log.error("accounts failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None
