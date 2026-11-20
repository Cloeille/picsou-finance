"""Banque Populaire sidecar: browser login, session replay and accounts import.

The bank runs the BPCE platform Caisse d'Epargne runs (observed live 2026-11-05):
the same login pages, keypad and Sécur'Pass, the same OAuth2 PKCE + SAML chain and
the same data API, on `*.banquepopulaire.fr`. Everything but this file's routes and
its `SITE` is the shared `bpce` package (services/base/bpce). Two differences:

  * the identifier page first asks for the establishment (a regional bank or the
    BRED): `POST /initiate` takes `{customerId, region}`, `region` one of
    `REGION_CODES`, and the login picks the option with that exact text;
  * the personal-loan (6/12) and home-loan (6/13) apps use their own public OAuth
    clients, read from the bank's public web app configuration and not yet observed in
    a live sync (`LOAN` is a provisional kind: a loan app refusal never ends the
    session, and a loan whose detail cannot be read is imported without it). The
    revolving credit (2/11) is listed in `unsupported`, never read. The PER (4/05)
    is read from the insurer app, like on Caisse d'Epargne (see `SITE.per`).

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
from bpce import fetcher, login, per_client, replay
from bpce.site import ChainProfile, HomeLoanApp, PerApp, Site
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
    # Seen in one live run (2026-11-08): Compte sur Livret 3/02, PEL 3/04,
    # term deposit 4/09 and cooperative shares 4/10.
    # `LOAN`: 6/12 and 6/13, read from the bank's public web app configuration
    # (2026-11-14), not yet observed in a live sync.
    provisional_kinds=frozenset({"SAVINGS", "PEL", "TERM_DEPOSIT", "COOPERATIVE_SHARES", "PER", "LOAN"}),
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
    # The home-loan app is served by the BPCE group hosts, shared with Caisse d'Epargne:
    # these two exactly, never a wildcard on bpce.fr.
    extra_hosts=frozenset({"www.as-ext-bad-me.bpce.fr", "www.rs-ext-bad-me.bpce.fr"}),
    # The personal-loan app (espace-gestion/pret-personnel) mints its own token, on the
    # main authorization server: public client, scope and redirect of that app, no
    # `claims`. Read from the bank's public web app configuration (2026-11-14), not yet
    # observed in a live sync; every other authorize parameter is the one the main chain sends.
    personal_loan=ChainProfile(
        name="personal-loan",
        overrides=(
            ("client_id", "4107d671-4e78-4f18-8842-83d7ca6735a9"),
            ("scope", "managePersonalLoanPrd readPersonalLoanPrd readUser"),
            ("redirect_uri", "https://www.banquepopulaire.fr/espace-gestion/pret-personnel/postMessage/index.html"),
        ),
        drop=("claims",),
    ),
    # The home-loan app (gestion-client/credit-immobilier) mints its own token on the BPCE
    # authorization server: same public client as Caisse d'Epargne, the redirect of that
    # app, an empty `scope`, no `claims`. Read from the public web app configuration
    # (2026-11-14), not yet observed in a live sync.
    home_loan=HomeLoanApp(
        chain=ChainProfile(
            name="home-loan",
            overrides=(
                ("client_id", "873a10ce-67a7-4917-ab31-e5a482617303"),
                ("scope", ""),
                ("redirect_uri", "https://www.banquepopulaire.fr/gestion-client/credit-immobilier/postMessage/index.html"),
            ),
            drop=("claims",),
        ),
        authorize_url="https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/authorize",
        token_url="https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/token",
        base_url="https://www.rs-ext-bad-me.bpce.fr",
    ),
    # The PER insurer app (espace-assurances-de-personnes) mints its own token: public
    # client and redirect of that app, an empty `scope` (the bank grants
    # `assuranceVieAuth_get_<id> readUser`, as on Caisse d'Epargne), no `claims`. Every
    # other authorize parameter is the one the main chain sends. The OAuth exchange,
    # `prepareRouting` (HTTP 201), `clientToken` and `contratVie/load` answered live
    # with this client (issue #524); positions and valuation parse from the insurer.
    per=PerApp(
        chain=ChainProfile(
            name="per",
            overrides=(
                ("client_id", "dcd51401-debb-47e4-ad8d-12a72e529f08"),
                ("scope", ""),
                ("redirect_uri", "https://www.espace-assurances-de-personnes.banquepopulaire.fr/postMessage/index.html"),
            ),
            drop=("claims",),
        ),
        gateway_host="www.rs-ext-bad-ib.banquepopulaire.fr",
        insurer_host="www.espace-assurances-de-personnes.banquepopulaire.fr",
    ),
)

LOAN_CHAIN = SITE.personal_loan
HOME_LOAN_CHAIN = SITE.home_loan.chain
PER_CHAIN = SITE.per.chain

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

    async def loan_token() -> str:
        # Second OAuth chain, minted only when the synthesis holds a loan.
        result = await _token_cache.get(session_state, transport=_TRANSPORT, chain=LOAN_CHAIN)
        return result.access_token

    async def home_loan_token() -> str:
        # Home-loan app chain on the BPCE authorization server, minted only for a home loan.
        result = await _token_cache.get(
            session_state,
            transport=_TRANSPORT,
            authorize_url=SITE.home_loan.authorize_url,
            token_url=SITE.home_loan.token_url,
            chain=HOME_LOAN_CHAIN,
        )
        return result.access_token

    async def per_detail(contract_id: str, entity_code: str) -> dict:
        # Third OAuth chain (insurer app), minted only when the synthesis holds a PER.
        # The main token already proved the session alive, so a refusal here is
        # PER-specific: one fresh PER token is tried, then only this PER is left out.
        for attempt in (1, 2):
            try:
                per_token = await _token_cache.get(session_state, transport=_TRANSPORT, chain=PER_CHAIN)
                return await per_client.fetch_per_detail(
                    SITE,
                    per_token.access_token,
                    contract_id,
                    entity_code=entity_code,
                    routing_token=token.access_token,
                    transport=_TRANSPORT,
                )
            except replay.SessionExpired:
                _token_cache.evict(session_state, chain=PER_CHAIN)
                if attempt == 2:
                    raise replay.UpstreamError("PER token refused twice") from None
        raise AssertionError("unreachable")

    return await fetcher.fetch_import(
        SITE,
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
        _token_cache.evict(session_state, chain=LOAN_CHAIN)
        _token_cache.evict(session_state, chain=HOME_LOAN_CHAIN)
        _token_cache.evict(session_state, chain=PER_CHAIN)
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
