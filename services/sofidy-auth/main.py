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
    → {currency, totalEur, valuationDate, snapshotComplete,
       holdings: [{fundCode, label, quantity, withdrawalPrice, totalEur}]}

Only the session cookies are returned to Java, which encrypts them before
storage. Credentials are held in memory for the length of one request and are
never logged; neither are cookies or raw financial responses.

The portal is PHP and answers every business page with HTTP 200, whether or not
the session is alive -- the logged-out response is the login form rendered inside
the same page. Liveness is therefore detected by the presence of the login form,
never by a status code: a 200 on the portfolio page is not a portfolio.
"""

import json
import time
import uuid
from decimal import Decimal
from typing import Any, Literal

import httpx
from fastapi import HTTPException
from picsou_sidecar import PendingStore, configure_logging, create_app, safe
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from positions_parser import (
    PositionsFormatError,
    parse_portfolio,
)

# httpx logs complete URLs, including upstream-controlled query strings.
log = configure_logging("sofidy-auth", quiet=("httpx", "httpcore"))

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
    quantity: Decimal = Field(validation_alias="shareCount")
    withdrawalPrice: Decimal | None = Field(validation_alias="withdrawalPriceEur")
    totalEur: Decimal
    snapshotComplete: bool = True


class SnapshotPayload(BaseModel):
    currency: Literal["EUR"]
    totalEur: Decimal
    valuationDate: str | None
    holdings: list[HoldingPayload]
    snapshotComplete: bool = True


# ─── pending second factors ──────────────────────────────────────────────────


async def _check_request_origin(request: httpx.Request) -> None:
    """Runs before every send, including each automatic 307/308 redirect.

    A fixed HTTPS origin also excludes loopback/private-IP redirect targets.
    Never log the rejected URL: it may carry a password, OTP or cookie.
    """
    url = request.url
    if (url.scheme != "https" or url.host != "moncompte.sofidy.com"
            or url.port not in (None, 443) or url.username or url.password):
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")


def _new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=BASE_URL,
        timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
        follow_redirects=True,
        max_redirects=5,
        event_hooks={"request": [_check_request_origin]},
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


_pending = PendingStore(
    dispose=_dispose_pending_state,
    log=log,
    ttl_seconds=PENDING_TTL_SECONDS,
    sweep_seconds=PENDING_SWEEP_SECONDS,
    max_size=MAX_PENDING,
)

# A malformed body must read as a 400, not as an upstream outage: FastAPI's own
# 422 would be mapped by every adapter to INVALID_CREDENTIALS on a login.
app = create_app(
    title="picsou-sofidy-auth",
    request_log=(log, "Sofidy"),
    background=_pending,
    validation_detail="INVALID_REQUEST",
)


# ─── portal calls ────────────────────────────────────────────────────────────


async def _post(client: httpx.AsyncClient, path: str, data: dict[str, str]) -> str:
    response = await client.post(path, data=data)
    if response.status_code >= 500 or response.status_code == 429:
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    if response.status_code != 200:
        # The login endpoint answers 200 with a bare token for every outcome,
        # including a wrong password, so a non-200 here is the portal refusing
        # the request itself rather than rejecting the credentials.
        log.warning("Sofidy request refused (path=%s; status=%s)", safe(path, 120), response.status_code)
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


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _pending.expire()
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
            await _pending.put_or_raise(process_id, state, "TOO_MANY_PENDING")
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
            log.warning("Sofidy login answered without opening a session")
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
        log.warning("Sofidy authentication initiation failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected Sofidy authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        if not keep_client:
            await client.aclose()


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    state = await _pending.take(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    client: httpx.AsyncClient = state["client"]
    retryable = False
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
            retryable = True
            raise HTTPException(status_code=401, detail="MFA_INVALID")

        if not _is_logged_in(await _home(client)):
            log.warning("Sofidy verification succeeded without opening a session")
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        return {"sessionState": serialize_cookies(client)}
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("Sofidy authentication completion failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected Sofidy authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        # A wrong code is retryable: the portal keeps the same attempt open.
        # Disposing it here would turn the next code into an expired attempt.
        if retryable:
            await _pending.repark(req.processId, state, "TOO_MANY_PENDING")
        else:
            await _dispose_pending_state(state)


@app.post("/positions", response_model=SnapshotPayload)
async def positions(req: SessionRequest) -> SnapshotPayload:
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
        return SnapshotPayload.model_validate(snapshot)
    except PositionsFormatError as exc:
        log.warning("Sofidy payload rejected (code=%s)", exc.code)
        raise HTTPException(status_code=502, detail=exc.code) from exc
    except ValidationError as exc:
        log.warning("Sofidy payload failed validation: %s", exc.error_count())
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("Sofidy positions fetch failed")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.error("Unexpected Sofidy positions failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await client.aclose()
