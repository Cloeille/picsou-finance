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
  502 UPSTREAM_UNAVAILABLE    bank-side or transport failure (synthesis or current account)
  502 UPSTREAM_FORMAT_CHANGED a payload (synthesis or current account) that does not match
                              the observed format
  Any other account that cannot be read is absent from `accounts` and listed in
  `unavailable` (`{externalId, kind, code}`). The deadline below still drops the whole
  import (502 UPSTREAM_UNAVAILABLE).
"""

import asyncio

import httpx
from fastapi import HTTPException, Request
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, StrictInt

import fetcher
import login
import per_client
import replay

# httpx logs every request URL at INFO and the authorize URL carries the
# authorize parameters in its query string.
log = configure_logging("caisse-epargne-auth", quiet=("httpx", "httpcore"))

# Test seam: tests inject an httpx.MockTransport here. None means real network.
_TRANSPORT: httpx.AsyncBaseTransport | None = None
_token_cache = replay.TokenCache()

# Overall budget of POST /accounts (token replay + every data call). Kept below the
# backend's 120 s read timeout so the sidecar answers before the caller gives up.
ACCOUNTS_DEADLINE_SECONDS: float = 100


LOGIN_PATHS = {"/initiate", "/keypad", "/complete"}


def _validation_detail(request: Request, _) -> str:
    # Never echo the validation errors: they quote the offending input.
    return "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"


app = create_app(
    request_log=(log, "Caisse d'Epargne"),
    background=login,
    validation_detail=_validation_detail,
)


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


class KeypadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    # The positions the user clicked in the pop-up. The password itself never
    # leaves his browser: it is only ever the page's own script that knows it.
    # StrictInt: a stringified index is a bug on the caller side, not a position.
    positions: list[StrictInt] = Field(min_length=1, max_length=200)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=200)


# --- Routes -------------------------------------------------------------------


@app.post("/initiate")
async def initiate(req: InitiateRequest) -> dict:
    # One attempt: no retry here or anywhere. Only the identifier goes in; the pad
    # images come back for the user to click himself.
    try:
        return await login.initiate(req.customerId)
    except login.LoginError as exc:
        raise HTTPException(status_code=exc.status, detail=exc.code) from None
    except Exception:
        log.error("initiate failed unexpectedly")  # no exception text: it may quote input
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/keypad")
async def keypad(req: KeypadRequest) -> dict:
    # The clicked positions are never logged; a wrong set costs nothing at the bank
    # (the pad typing is refused before any click).
    try:
        return await login.keypad(req.processId, req.positions)
    except login.LoginError as exc:
        raise HTTPException(status_code=exc.status, detail=exc.code) from None
    except Exception:
        log.error("keypad failed unexpectedly")
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


async def _fetch_once(session_state: str) -> dict:
    token = await _token_cache.get(session_state, transport=_TRANSPORT)

    async def loan_token() -> str:
        # Second OAuth chain, minted only when the synthesis holds a loan.
        result = await _token_cache.get(session_state, transport=_TRANSPORT, chain=replay.LOAN_CHAIN)
        return result.access_token

    async def home_loan_token() -> str:
        # Home-loan app chain on the BPCE authorization server, minted only for a home loan.
        result = await _token_cache.get(
            session_state,
            transport=_TRANSPORT,
            authorize_url=replay.HOME_LOAN_AUTHORIZE_URL,
            token_url=replay.HOME_LOAN_TOKEN_URL,
            chain=replay.HOME_LOAN_CHAIN,
        )
        return result.access_token

    async def per_detail(contract_id: str, entity_code: str) -> dict:
        # Third OAuth chain (insurer app), minted only when the synthesis holds a PER.
        # The main token already proved the session alive, so a refusal here is
        # PER-specific: one fresh PER token is tried, then only this PER is left out.
        for attempt in (1, 2):
            try:
                per_token = await _token_cache.get(session_state, transport=_TRANSPORT, chain=replay.PER_CHAIN)
                return await per_client.fetch_per_detail(
                    per_token.access_token,
                    contract_id,
                    entity_code=entity_code,
                    routing_token=token.access_token,
                    transport=_TRANSPORT,
                )
            except replay.SessionExpired:
                _token_cache.evict(session_state, chain=replay.PER_CHAIN)
                if attempt == 2:
                    raise replay.UpstreamError("PER token refused twice") from None
        raise AssertionError("unreachable")

    return await fetcher.fetch_import(
        token.access_token,
        transport=_TRANSPORT,
        loan_token=loan_token,
        per_detail=per_detail,
        home_loan_token=home_loan_token,
    )


async def _fetch_accounts(session_state: str) -> dict:
    try:
        return await _fetch_once(session_state)
    except replay.SessionExpired:
        # A cached token can be revoked before its expiry: drop both and replay once more.
        # A second rejection (or a failed replay) means the session itself is dead.
        _token_cache.evict(session_state)
        _token_cache.evict(session_state, chain=replay.LOAN_CHAIN)
        _token_cache.evict(session_state, chain=replay.HOME_LOAN_CHAIN)
        _token_cache.evict(session_state, chain=replay.PER_CHAIN)
        return await _fetch_once(session_state)


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
