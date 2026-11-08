"""Banque Populaire sidecar: browser login, session replay and accounts import.

The bank runs the BPCE platform Caisse d'Epargne runs (observed live 2026-11-05):
the same login pages, keypad and Sécur'Pass, the same OAuth2 PKCE + SAML chain and
the same data API, on `*.banquepopulaire.fr`. Everything but this file's routes and
its `SITE` is the shared `bpce` package (services/base/bpce). Two differences:

  * the identifier page first asks for the establishment (a regional bank or the
    BRED): `POST /initiate` takes `{customerId, region}`, `region` one of
    `REGION_CODES`, and the login picks the option with that exact text;
  * no personal-loan, home-loan or PER app was observed: those contracts (6/12, 6/13,
    4/05) and the revolving credit (2/11) are listed in `unsupported`, never read.

Safety rules, same as every sidecar:
  * every request but /health needs the shared key (`APP_SIDECAR_API_KEY`);
    the service refuses to start without one;
  * cookies, SAML payloads, authorization codes, tokens and authorize
    parameters are never logged. Only `/complete` returns cookies and authorize
    parameters, inside `sessionState`, for the backend to encrypt; the token
    stays in memory.

Error codes (JSON `{"detail": CODE}`), those of caisse-epargne-auth:
  400 INVALID_SESSION_STATE   unusable request body or session state
  401 SESSION_EXPIRED         the bank no longer accepts the SSO session
  502 UPSTREAM_ERROR          any other bank-side or transport failure
  500 INTERNAL_ERROR          unexpected bug (no detail leaked)
Login (`/initiate`, `/keypad`, `/complete`): 400 INVALID_REQUEST (an unknown region
  too), 401 INVALID_CREDENTIALS, 408 APP_VALIDATION_TIMEOUT, 409 KEYPAD_CHANGED,
  410 AUTH_ATTEMPT_EXPIRED, 429 TOO_MANY_PENDING, 502 UPSTREAM_UNAVAILABLE /
  UPSTREAM_FORMAT_CHANGED (a missing establishment picker or option included).
`POST /accounts`: 502 UPSTREAM_UNAVAILABLE / UPSTREAM_FORMAT_CHANGED for the synthesis
  or the current account; any other account that cannot be read is listed in
  `unavailable` (`{externalId, kind, code}`).
"""

import asyncio
from typing import Literal

import httpx
from bpce import fetcher, login, replay
from bpce.site import Site
from fastapi import HTTPException, Request
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, StrictInt

# httpx logs every request URL at INFO and the authorize URL carries the
# authorize parameters in its query string.
log = configure_logging("banque-populaire-auth", quiet=("httpx", "httpcore"))

# (request code, exact option text of the establishment picker), observed live
# 2026-11-05 in this order.
REGIONS = (
    ("ALSACE_LORRAINE_CHAMPAGNE", "ALSACE LORRAINE CHAMPAGNE"),
    ("AQUITAINE_CENTRE_ATLANTIQUE", "AQUITAINE CENTRE ATLANTIQUE"),
    ("AUVERGNE_RHONE_ALPES", "AUVERGNE RHONE ALPES"),
    ("BOURGOGNE_FRANCHE_COMTE", "BOURGOGNE FRANCHE-COMTÉ"),
    ("BRED", "BRED"),
    ("GRAND_OUEST", "GRAND OUEST"),
    ("MEDITERRANEE", "MEDITERRANEE"),
    ("NORD", "NORD"),
    ("OCCITANE", "OCCITANE"),
    ("RIVES_DE_PARIS", "RIVES DE PARIS"),
    ("SUD", "SUD"),
    ("VAL_DE_FRANCE", "VAL DE FRANCE"),
)
REGION_CODES = tuple(code for code, _ in REGIONS)

# Hosts observed on the real bank, 2026-11-05. `service=dei` (Caisse d'Epargne's
# entry) lands on `/se-connecter/erreur` here: the web front enters with `cyber`.
SITE = Site(
    domain="banquepopulaire.fr",
    login_url="https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber",
    client_host="www.banquepopulaire.fr",
    authorize_url="https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/authorize",
    token_url="https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/token",
    data_base_url="https://www.rs-ext-bad-ib.banquepopulaire.fr",
    regions=REGIONS,
    # "Continuer sans accepter" of the consent banner.
    cookie_banner_button="#no_consent_btn",
    # A letter then seven digits, like `A1234567` (shape observed live 2026-11-05).
    alphanumeric_identifier=True,
    # Cards are not imported for Banque Populaire.
    cards=False,
)

# Test seam: tests inject an httpx.MockTransport here. None means real network.
_TRANSPORT: httpx.AsyncBaseTransport | None = None
_token_cache = replay.TokenCache(SITE)

# Overall budget of POST /accounts (token replay + every data call). Kept below the
# backend's 120 s read timeout so the sidecar answers before the caller gives up.
ACCOUNTS_DEADLINE_SECONDS: float = 100


LOGIN_PATHS = {"/initiate", "/keypad", "/complete"}


def _validation_detail(request: Request, _) -> str:
    # Never echo the validation errors: they quote the offending input.
    return "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"


app = create_app(
    request_log=(log, "Banque Populaire"),
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
    model_config = ConfigDict(extra="forbid", repr=False, strict=True)

    customerId: str = Field(min_length=1, max_length=200)
    region: Literal[REGION_CODES]  # type: ignore[valid-type]


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
    # One attempt: no retry here or anywhere. Only the identifier and the
    # establishment go in; the pad images come back for the user to click himself.
    try:
        return await login.initiate(SITE, req.customerId, region=req.region)
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
    except replay.ReplayError:
        raise HTTPException(status_code=502, detail="UPSTREAM_ERROR") from None
    except Exception:
        # Type only: the message may embed session data.
        log.error("token-check failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None
    # The token itself stays in memory; callers only learn that one can be minted.
    return {"ok": True, "expiresIn": int(result.expires_in)}


async def _fetch_once(session_state: str) -> dict:
    token = await _token_cache.get(session_state, transport=_TRANSPORT)
    # The main token only: the site has no loan or PER app, so those contracts are
    # unsupported and no second chain is ever minted.
    return await fetcher.fetch_import(SITE, token.access_token, transport=_TRANSPORT)


async def _fetch_accounts(session_state: str) -> dict:
    try:
        return await _fetch_once(session_state)
    except replay.SessionExpired:
        # A cached token can be revoked before its expiry: drop it and replay once more.
        # A second rejection (or a failed replay) means the session itself is dead.
        _token_cache.evict(session_state)
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
