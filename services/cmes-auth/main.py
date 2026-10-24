"""Read-only Crédit Mutuel Épargne Salariale authentication and positions sidecar.

The saver's space (www.creditmutuel-ere.fr, also used by CIC savers) has no
public API. Its pages are server-rendered and its login is a plain HTML form
with no captcha, so this service speaks plain HTTPS -- no browser.

Auth flow:
  POST /initiate {login, password, sessionState?}
    → no second factor: {processId: null, mfaRequired: false, sessionState}
    → mobile confirmation: {processId, mfaRequired: true, mfaType: "APP_PUSH"}
    → code by SMS:         {processId, mfaRequired: true, mfaType: "SMS"}
  POST /complete {processId, code?}
    → APP_PUSH: holds open while the user approves in the bank's app
    → SMS: submits the code → {sessionState}
  POST /positions {sessionState}
    → [{externalId, name, planKind, employer, balanceEur, positions[], snapshotComplete}]

Once the second factor is passed, the site sets a remembered-device cookie
(`auth_client_state`) that spares the second factor for a while (90 days on the
banking sites built on the same platform). `/initiate` accepts the previous
session so that cookie is replayed: a stored-credentials re-login after the
session expired then needs no one at the phone.

The mobile-confirmation choreography (`transactionId`, state polling, then the
page form submitted) is the one the platform uses on its banking sites, as
documented by woob's `creditmutuel` module; it is reused here rather than
guessed. Only the session cookies are returned to Java, which encrypts them
before storage. Credentials are held in memory for one request and are never
logged; neither are cookies, field values or financial responses.
"""

import asyncio
import json
import logging
import os
import re
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any, Literal
from urllib.parse import urljoin, urlsplit

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from forms import Form, field_names, find_form, parse_forms
from fund_parser import MY_SAVINGS_PATH_MARKER, my_savings_tab, parse_fund_identity, parse_my_savings
from situation_parser import SituationFormatError, money_close, parse_profile, parse_situation

logging.basicConfig(level=logging.INFO)
logging.getLogger("httpx").setLevel(logging.WARNING)
log = logging.getLogger("cmes-auth")
SIDECAR_API_KEY = os.environ.get("APP_SIDECAR_API_KEY", "")

BASE_URL = os.environ.get("CMES_BASE_URL", "https://www.creditmutuel-ere.fr").rstrip("/")
LOGIN_PATH = "/fr/identification/authentification.html"
SAVER_SPACE = "/epargne-salariale/fr/epargnants"
SITUATION_PATH = f"{SAVER_SPACE}/mon-epargne/situation-financiere-detaillee/index.html"
LOGIN_FORM = "bloc_ident"
LOGGED_IN_MARKER = "deconnexion/deconnexion.html"
TRUST_COOKIE = "auth_client_state"

# Where a login can land before the saver's space. The second factor lives on
# `premiers-pas/authentification-forte` here and on `validation.aspx` on the
# banking sites; both are recognised.
MFA_PATH_MARKERS = ("authentification-forte", "validation.aspx")
# Notices the site parks a login on until the saver acts on the real website.
ACTION_NEEDED_PATH_MARKERS = (
    "premiers-pas/saisir-vos-coordonnees",
    "premiers-pas/vos-services",
    "conditions-generales-d-utilisation",
)

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
# Stay under the Java adapter's validation timeout so the sidecar is always the
# one to give up, and can cancel the request on the phone.
APP_VALIDATION_TIMEOUT_SECONDS = 120
APP_VALIDATION_POLL_SECONDS = 2.0
REQUEST_TIMEOUT_SECONDS = 30.0
MAX_REDIRECTS = 10
MAX_PENDING = 16

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

_BAD_CREDENTIALS_MARKERS = (
    "mot de passe est faux",
    "mot de passe est révoqué",
    "identifiant ou mot de passe est incorrect",
    "votre code d'accès n'est pas reconnu",
    "devez renseigner votre identifiant",
)
_LOCKED_MARKERS = ("bloqué", "pas autorisé à accéder à ce service")
_ERROR_BLOCK_RE = re.compile(r'<div\b[^>]*class="[^"]*\bblocmsg\s+(?:err|alerte)\b[^"]*"[^>]*>(?P<text>.*?)</div>', re.DOTALL)
_TRANSACTION_ID_RE = re.compile(r"transactionId\s*:\s*'(?P<id>[^']+)'")
_STATE_URL_RE = re.compile(r"""['"](?P<url>[^'"\s]*SOSD_OTP_GetTransactionState[^'"\s]*)['"]""")
_CANCEL_URL_RE = re.compile(r"""['"](?P<url>[^'"\s]*SOSD_OTP_CancelTransaction[^'"\s]*)['"]""")
_TRANSACTION_STATE_RE = re.compile(r"<transactionState>\s*(?P<state>\w+)\s*</transactionState>", re.IGNORECASE)
_LINK_RE = re.compile(r"<a\b(?P<attrs>[^>]*)>(?P<text>.*?)</a>", re.DOTALL | re.IGNORECASE)
_HREF_RE = re.compile(r'href="(?P<href>[^"]+)"')
_TAG_RE = re.compile(r"<[^>]+>")

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


class UpstreamError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


# ─── Lifecycle ──────────────────────────────────────────────────────────────


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        try:
            await _cleanup_expired()
        except Exception:
            log.warning("CMES pending sweep failed", exc_info=True)


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not SIDECAR_API_KEY.strip():
        raise RuntimeError("APP_SIDECAR_API_KEY must be configured and non-blank")
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
            log.info(
                "CMES request completed (path=%s; duration=%.2fs)",
                _log_safe(request.url.path),
                time.monotonic() - started_at,
            )


def _log_safe(value: str) -> str:
    return "".join(char for char in value if char.isprintable())[:200]


# ─── Contract ───────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)
    # The previous session, if any: only its remembered-device cookie is reused.
    sessionState: str | None = Field(default=None, max_length=2_000_000)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    # The SMS code; absent for a mobile confirmation, which has nothing to type.
    code: str | None = Field(default=None, pattern=r"^\d{4,8}$")


class PositionsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
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


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    fields = {str(error["loc"][-1]) for error in exc.errors() if error.get("loc")}
    detail = "INVALID_OTP" if "code" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})


# ─── Pending second factors ─────────────────────────────────────────────────


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    client: httpx.AsyncClient | None = state.get("client")
    if client is None:
        return
    try:
        await client.aclose()
    except Exception:
        log.warning("CMES client cleanup failed", exc_info=True)


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, state in _pending.items() if state["created_at"] < cutoff]
        states = [_pending.pop(pid) for pid in expired]
    for state in states:
        await _dispose_pending_state(state)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _store_pending(process_id: str, state: dict[str, Any]) -> None:
    async with _pending_lock:
        if len(_pending) >= MAX_PENDING:
            raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE")
        _pending[process_id] = state


# ─── HTTP plumbing ──────────────────────────────────────────────────────────


def _new_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=BASE_URL,
        timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
        # Redirects are followed by hand: where a login lands is what tells a
        # second factor, a notice and a wrong password apart.
        follow_redirects=False,
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


def _decode_cookies(raw: str) -> list[dict[str, Any]]:
    try:
        decoded = json.loads(raw)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    cookies = decoded.get("cookies") if isinstance(decoded, dict) else None
    if not isinstance(cookies, list) or not cookies:
        raise HTTPException(status_code=400, detail="INVALID_DATA")
    for cookie in cookies:
        if not isinstance(cookie, dict) or not isinstance(cookie.get("name"), str):
            raise HTTPException(status_code=400, detail="INVALID_DATA")
    return cookies


def _set_cookie(client: httpx.AsyncClient, cookie: dict[str, Any]) -> None:
    client.cookies.set(
        cookie["name"],
        str(cookie.get("value", "")),
        domain=str(cookie.get("domain") or ""),
        path=str(cookie.get("path") or "/"),
    )


def restore_cookies(client: httpx.AsyncClient, raw: str) -> None:
    for cookie in _decode_cookies(raw):
        _set_cookie(client, cookie)


def restore_trust_cookie(client: httpx.AsyncClient, raw: str | None) -> bool:
    """Carry only the remembered-device cookie over from a previous session.

    The rest of an old jar is a dead session that would only confuse the login.
    An unreadable previous session is ignored: it must never block a login.
    """
    if not raw:
        return False
    try:
        cookies = _decode_cookies(raw)
    except HTTPException:
        return False
    trusted = [cookie for cookie in cookies if cookie["name"] == TRUST_COOKIE]
    for cookie in trusted:
        _set_cookie(client, cookie)
    return bool(trusted)


def _path(url: str | httpx.URL) -> str:
    return urlsplit(str(url)).path


def _same_origin(url: str) -> str:
    resolved = urljoin(BASE_URL + "/", url)
    if urlsplit(resolved).netloc != urlsplit(BASE_URL).netloc or urlsplit(resolved).scheme != "https":
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "A link left the saver's site")
    return resolved


async def _follow(client: httpx.AsyncClient, response: httpx.Response) -> httpx.Response:
    """Follow redirects by hand, staying on the saver's site."""
    for _ in range(MAX_REDIRECTS):
        if not response.is_redirect:
            return response
        target = _same_origin(urljoin(str(response.url), response.headers.get("location", "")))
        response = await client.get(target)
    raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Too many redirects")


def _text(fragment: str) -> str:
    return " ".join(_TAG_RE.sub(" ", fragment).replace("&nbsp;", " ").split())


def _link_with(page: str, *phrases: str) -> str | None:
    """The href of the first link whose text holds one of `phrases`."""
    for match in _LINK_RE.finditer(page):
        text = _text(match.group("text")).lower()
        if any(phrase in text for phrase in phrases):
            href = _HREF_RE.search(match.group("attrs"))
            if href and not href.group("href").startswith(("#", "javascript:")):
                return href.group("href").replace("&amp;", "&")
    return None


def _diagnose(stage: str, response: httpx.Response) -> None:
    """Log the shape of an unexpected page: its path and field names, never values."""
    log.warning(
        "CMES %s: unexpected page (status=%s; path=%s; forms=%s)",
        stage,
        response.status_code,
        _log_safe(_path(response.url)),
        field_names(response.text)[:40],
    )


# ─── Login ──────────────────────────────────────────────────────────────────


def _login_error(page: str) -> str | None:
    block = _ERROR_BLOCK_RE.search(page)
    if block is None:
        return None
    message = _text(block.group("text")).lower()
    if any(marker in message for marker in _BAD_CREDENTIALS_MARKERS):
        return "INVALID_CREDENTIALS"
    if any(marker in message for marker in _LOCKED_MARKERS):
        # A locked access must not be retried with the stored password either.
        return "INVALID_CREDENTIALS"
    return "UPSTREAM_FORMAT_CHANGED"


async def _login(client: httpx.AsyncClient, login: str, password: str) -> httpx.Response:
    """Submit the login form and return the page the login lands on."""
    login_page = await _follow(client, await client.get(LOGIN_PATH))
    form = find_form(login_page.text, name=LOGIN_FORM)
    if form is None:
        _diagnose("login page", login_page)
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No login form")
    fields = dict(form.fields)
    fields.update({"_cm_user": login, "_cm_pwd": password, "_charset_": "UTF-8"})
    target = _same_origin(urljoin(str(login_page.url), form.action or ""))
    response = await client.post(
        target,
        data=fields,
        headers={"Origin": BASE_URL, "Referer": str(login_page.url)},
    )
    if not response.is_redirect:
        code = _login_error(response.text)
        if code is None:
            _diagnose("login", response)
            code = "UPSTREAM_FORMAT_CHANGED"
        raise UpstreamError(code, "Login refused")
    landing = await _follow(client, response)
    if _path(landing.url) == LOGIN_PATH:
        # Sent back to the login form: the site answers a wrong password this
        # way once the form itself was accepted.
        code = _login_error(landing.text) or "INVALID_CREDENTIALS"
        raise UpstreamError(code, "Login refused")
    return landing


def _is_mfa_page(response: httpx.Response) -> bool:
    return any(marker in _path(response.url) for marker in MFA_PATH_MARKERS)


async def _skip_notices(client: httpx.AsyncClient, response: httpx.Response) -> httpx.Response:
    """Step over the skippable notices the site parks a login on."""
    for _ in range(3):
        if not any(marker in _path(response.url) for marker in ACTION_NEEDED_PATH_MARKERS):
            return response
        skip = _link_with(response.text, "passer cette etape", "passer cette étape")
        if skip is None:
            log.info("CMES: login parked on a notice that needs the saver (path=%s)", _log_safe(_path(response.url)))
            raise UpstreamError("ACTION_REQUIRED", "The site needs the saver to act on its website")
        response = await _follow(client, await client.get(_same_origin(skip)))
    raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Too many notices")


async def _bypass_second_factor(client: httpx.AsyncClient, page: httpx.Response) -> httpx.Response | None:
    """Follow the link the confirmation page offers when it can be skipped.

    Shown while the remembered-device cookie is still valid ("confirmer votre
    identité plus tard") or when the site does not require the confirmation.
    """
    link = _link_with(page.text, "sans confirmation mobile", "accéder à votre espace client")
    if link is None and "confirmer votre identité plus tard" in page.text:
        # "... plus tard : [cliquez ici]": the phrase sits outside the link,
        # whose only stable trait is its Bypass target.
        link = next(
            (
                href.group("href").replace("&amp;", "&")
                for match in _LINK_RE.finditer(page.text)
                if (href := _HREF_RE.search(match.group("attrs"))) and "bypass" in href.group("href").lower()
            ),
            None,
        )
    if link is None:
        return None
    log.info("CMES: second factor skipped by the site's own link")
    return await _follow(client, await client.get(_same_origin(link)))


OTP_FIELD = "otp_password"
_OTP_ERROR_RE = re.compile(r'<div\b[^>]*class="[^"]*\bbloctxt\s+err\b[^"]*"[^>]*>(?P<text>.*?)</div>', re.DOTALL)


def _otp_form(page: str) -> Form | None:
    """The form the SMS code is typed into, recognised by its code field."""
    for form in parse_forms(page):
        if OTP_FIELD in form.fields:
            return form
    return None


def _start_sms(page: httpx.Response, form: Form) -> dict[str, Any]:
    page_url = str(page.url)
    fields = dict(form.fields)
    fields.pop(OTP_FIELD, None)
    log.info("CMES: code sent by SMS (form buttons=%s)", form.buttons[:10])
    return {
        "kind": "SMS",
        "finalUrl": _same_origin(urljoin(page_url, form.action or page_url)),
        "finalFields": fields,
        "pageUrl": page_url,
    }


async def _submit_sms_code(client: httpx.AsyncClient, state: dict[str, Any], code: str) -> httpx.Response:
    """Post the code with the page state, the way the bank's own form does.

    Like woob's creditmutuel module, no button is pressed: the hidden fields
    and the code are the whole submission.
    """
    response = await client.post(
        state["finalUrl"],
        data={**state["finalFields"], OTP_FIELD: code},
        headers={"Origin": BASE_URL, "Referer": state["pageUrl"]},
    )
    landing = await _follow(client, response)
    if LOGIN_PATH in _path(landing.url):
        raise UpstreamError("AUTH_ATTEMPT_EXPIRED", "The SMS code is no longer valid")
    retry = _otp_form(landing.text)
    if retry is not None:
        error = _OTP_ERROR_RE.search(landing.text)
        message = _text(error.group("text")).lower() if error else ""
        if "bloqué" in message or "erreurs de saisie" in message:
            raise UpstreamError("ACTION_REQUIRED", "Too many wrong codes, access locked")
        # Same page again: the code was wrong. The saver may try once more on
        # this same request, with the page's refreshed state.
        state["finalFields"] = {name: value for name, value in retry.fields.items() if name != OTP_FIELD}
        state["pageUrl"] = str(landing.url)
        raise UpstreamError("INVALID_OTP", "Wrong SMS code")
    if TRUST_COOKIE not in {cookie.name for cookie in client.cookies.jar}:
        log.info("CMES: no remembered-device cookie after the SMS code; every login will ask again")
    return await _skip_notices(client, landing)


def _unsupported_factor(page: str) -> str | None:
    lowered = page.lower()
    if "lecteur digipass" in lowered:
        return "DIGIPASS"
    if "safetrans" in lowered:
        return "SAFETRANS"
    if "aucun moyen pour confirmer" in lowered:
        return "NONE_ENROLLED"
    return None


def _confirmation_form(page: str) -> Form | None:
    """The form to submit once the confirmation is approved: the page's POST form."""
    for form in parse_forms(page):
        if form.method == "post" and form.name != LOGIN_FORM and "search" not in form.action:
            return form
    return None


def _start_mobile_confirmation(page: httpx.Response) -> dict[str, Any]:
    body = page.text
    transaction = _TRANSACTION_ID_RE.search(body)
    form = _confirmation_form(body)
    state_url = _STATE_URL_RE.search(body)
    if transaction is None or form is None or state_url is None:
        _diagnose("mobile confirmation", page)
        log.warning(
            "CMES mobile confirmation markers (transactionId=%s; form=%s; stateUrl=%s)",
            transaction is not None,
            form is not None,
            state_url is not None,
        )
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Unknown confirmation page")
    cancel_url = _CANCEL_URL_RE.search(body)
    page_url = str(page.url)
    return {
        "transactionId": transaction.group("id"),
        "stateUrl": _same_origin(urljoin(page_url, state_url.group("url"))),
        "cancelUrl": _same_origin(urljoin(page_url, cancel_url.group("url"))) if cancel_url else None,
        "finalUrl": _same_origin(urljoin(page_url, form.action or page_url)),
        "finalFields": dict(form.fields),
        "pageUrl": page_url,
    }


async def _await_confirmation(client: httpx.AsyncClient, state: dict[str, Any]) -> None:
    deadline = time.monotonic() + APP_VALIDATION_TIMEOUT_SECONDS
    data = {"transactionId": state["transactionId"]}
    while time.monotonic() < deadline:
        response = await client.post(state["stateUrl"], data=data)
        match = _TRANSACTION_STATE_RE.search(response.text) if response.status_code == 200 else None
        if match is None:
            # Sent elsewhere (typically the login page): the request is gone.
            log.info("CMES confirmation polling answered without a state (status=%s)", response.status_code)
            raise UpstreamError("AUTH_ATTEMPT_EXPIRED", "Confirmation request no longer exists")
        current = match.group("state").upper()
        if current == "VALIDATED":
            return
        if current in ("CANCELLED", "NONE"):
            raise UpstreamError("APP_VALIDATION_CANCELLED", "Confirmation refused on the phone")
        if current != "PENDING":
            log.warning("CMES confirmation polling returned an unknown state")
            raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Unknown confirmation state")
        await asyncio.sleep(APP_VALIDATION_POLL_SECONDS)
    if state.get("cancelUrl"):
        try:
            # Withdraw the request from the phone rather than leave it pending.
            await client.post(state["cancelUrl"], data=data)
        except httpx.HTTPError:
            log.info("CMES could not cancel the expired confirmation request")
    raise UpstreamError("APP_VALIDATION_TIMEOUT", "Confirmation not approved in time")


async def _finish_confirmation(client: httpx.AsyncClient, state: dict[str, Any]) -> httpx.Response:
    response = await client.post(
        state["finalUrl"],
        data=state["finalFields"],
        headers={"Origin": BASE_URL, "Referer": state["pageUrl"]},
    )
    if TRUST_COOKIE not in {cookie.name for cookie in client.cookies.jar}:
        log.info("CMES: no remembered-device cookie after confirmation; every login will ask again")
    return await _skip_notices(client, await _follow(client, response))


def _require_logged_in(stage: str, response: httpx.Response) -> None:
    if LOGGED_IN_MARKER not in response.text:
        _diagnose(stage, response)
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Did not reach the saver's space")


# ─── Positions ──────────────────────────────────────────────────────────────


def plan_external_id(number: str, plan: dict) -> str:
    slug = plan["planKind"] or re.sub(r"[^a-z0-9]+", "-", plan["name"].lower()).strip("-")
    return f"cmes_{number}_{slug}"[:100]


async def _situation(client: httpx.AsyncClient) -> str:
    response = await client.get(SITUATION_PATH)
    if response.is_redirect:
        target = urljoin(str(response.url), response.headers.get("location", ""))
        if LOGIN_PATH in target or "identification" in target:
            raise UpstreamError("SESSION_EXPIRED", "Session expired")
        response = await _follow(client, response)
    if response.status_code in (401, 403) or LOGIN_PATH in _path(response.url):
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    if response.status_code != 200:
        code = "UPSTREAM_UNAVAILABLE" if response.status_code == 429 or response.status_code >= 500 else "UPSTREAM_FORMAT_CHANGED"
        raise UpstreamError(code, f"Situation page answered HTTP {response.status_code}")
    if LOGGED_IN_MARKER not in response.text:
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    return response.text


async def _collect_plans(client: httpx.AsyncClient) -> list[PlanPayload]:
    page = await _situation(client)
    employer, number = parse_profile(page)
    plans = parse_situation(page)
    payloads = []
    for plan in plans:
        positions = [await fetch_fund(client, fund) for fund in plan["funds"]]
        payloads.append({
            "externalId": plan_external_id(number, plan),
            "name": plan["name"],
            "planKind": plan["planKind"],
            "employer": employer,
            "balanceEur": plan["balanceEur"],
            "positions": positions,
            "snapshotComplete": True,
        })
    return [PlanPayload.model_validate(payload) for payload in payloads]


async def fetch_fund(client: httpx.AsyncClient, fund: dict) -> dict:
    """Read one fund's units, unit value and code from its own pages.

    The fund button posts the situation page's form, which lands on the fund's
    "Synthèse" tab; its "Mon épargne" tab holds the units. Every fund starts
    from a fresh situation page: the form carries the page state, and one
    posted from a page the server has since moved away from is refused.
    """
    page = await _situation(client)
    form = find_form(page, button=fund["button"])
    if form is None:
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No form holds the fund button")
    fields = dict(form.fields)
    fields[fund["button"]] = fund["label"]
    target = _same_origin(urljoin(BASE_URL + SITUATION_PATH, form.action or SITUATION_PATH))
    summary = await _follow(client, await client.post(target, data=fields, headers={"Origin": BASE_URL}))
    if LOGIN_PATH in _path(summary.url):
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    isin, fund_code = parse_fund_identity(summary.text)

    savings = summary
    if MY_SAVINGS_PATH_MARKER not in _path(summary.url):
        tab = my_savings_tab(summary.text)
        if tab is None:
            _diagnose("fund summary", summary)
            raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No 'Mon épargne' tab on the fund page")
        savings = await _follow(client, await client.get(_same_origin(urljoin(str(summary.url), tab))))
    details = parse_my_savings(savings.text)
    if not money_close(details["valueEur"], fund["valueEur"]):
        # The fund page belongs to another fund, or the two pages were read on
        # either side of a valuation: either way the snapshot is not coherent.
        raise UpstreamError("PORTFOLIO_INCOMPLETE", "Fund page and situation page disagree")
    return {
        "isin": isin,
        "fundCode": fund_code,
        "label": fund["label"],
        "quantity": details["quantity"],
        "unitValue": details["unitValue"],
        "valueEur": fund["valueEur"],
        "pnlEur": details["pnlEur"],
    }


# ─── Routes ─────────────────────────────────────────────────────────────────


def _http_error(exc: Exception) -> HTTPException:
    code = getattr(exc, "code", "INTERNAL_ERROR")
    status = {
        "INVALID_CREDENTIALS": 401,
        "ACTION_REQUIRED": 401,
        "SESSION_EXPIRED": 401,
        "MFA_TYPE_UNSUPPORTED": 501,
        "APP_VALIDATION_TIMEOUT": 408,
        "APP_VALIDATION_CANCELLED": 401,
        "INVALID_OTP": 400,
        "AUTH_ATTEMPT_EXPIRED": 410,
    }.get(code, 502)
    return HTTPException(status_code=status, detail=code)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    client = _new_client()
    keep_client = False
    try:
        trusted = restore_trust_cookie(client, req.sessionState)
        landing = await _skip_notices(client, await _login(client, req.login, req.password))

        if _is_mfa_page(landing):
            bypassed = await _bypass_second_factor(client, landing)
            if bypassed is not None:
                landing = await _skip_notices(client, bypassed)

        if not _is_mfa_page(landing):
            _require_logged_in("login landing", landing)
            return {
                "processId": None,
                "mfaRequired": False,
                "mfaType": None,
                "sessionState": serialize_cookies(client),
            }

        sms_form = _otp_form(landing.text)
        if sms_form is not None:
            state = _start_sms(landing, sms_form)
            state["client"] = client
            state["created_at"] = time.time()
            await _store_pending(process_id, state)
            keep_client = True
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "SMS",
                "sessionState": None,
            }

        unsupported = _unsupported_factor(landing.text)
        if unsupported:
            log.info("CMES asked for an unsupported second factor: %s", unsupported)
            raise UpstreamError("MFA_TYPE_UNSUPPORTED", "Unsupported second factor")
        if trusted:
            log.info("CMES: remembered-device cookie no longer spares the confirmation")

        state = _start_mobile_confirmation(landing)
        state["kind"] = "APP_PUSH"
        state["client"] = client
        state["created_at"] = time.time()
        await _store_pending(process_id, state)
        keep_client = True
        return {
            "processId": process_id,
            "mfaRequired": True,
            "mfaType": "APP_PUSH",
            "sessionState": None,
        }
    except (UpstreamError, SituationFormatError) as exc:
        log.warning("CMES login rejected (code=%s): %s", exc.code, exc)
        raise _http_error(exc) from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("CMES authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected CMES authentication initiation failure")
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
    keep_state = False
    try:
        if state["kind"] == "SMS":
            if req.code is None:
                # Not a failure of the attempt: the code can still be sent.
                keep_state = True
                raise UpstreamError("INVALID_OTP", "An SMS code is required")
            landing = await _submit_sms_code(client, state, req.code)
        else:
            if req.code is not None:
                raise UpstreamError("MFA_TYPE_UNSUPPORTED", "A mobile confirmation takes no code")
            await _await_confirmation(client, state)
            landing = await _finish_confirmation(client, state)
        _require_logged_in("after second factor", landing)
        return {"sessionState": serialize_cookies(client)}
    except UpstreamError as exc:
        if exc.code == "INVALID_OTP" and state["kind"] == "SMS":
            # A mistyped code leaves the request open on the site: keep it so
            # the saver can type the code again.
            keep_state = True
            await _store_pending(req.processId, state)
        log.warning("CMES second factor rejected (code=%s): %s", exc.code, exc)
        raise _http_error(exc) from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("CMES authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected CMES authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        if not keep_state:
            await _dispose_pending_state(state)


@app.post("/positions", response_model=list[PlanPayload])
async def positions(req: PositionsRequest) -> list[PlanPayload]:
    client = _new_client()
    try:
        restore_cookies(client, req.sessionState)
        return await _collect_plans(client)
    except (UpstreamError, SituationFormatError) as exc:
        log.warning("CMES positions rejected (code=%s): %s", exc.code, exc)
        raise _http_error(exc) from exc
    except ValidationError as exc:
        log.warning("CMES positions payload failed validation: %s", exc.error_count())
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("CMES positions fetch failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected CMES positions failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await client.aclose()
