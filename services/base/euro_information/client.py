"""Login and session client for the Euro-Information banking sites (CIC, Crédit Mutuel).

Both banks run on the same platform: a plain HTML login form with no captcha,
then a second factor (a code by SMS or a confirmation in the bank's mobile app),
then a remembered-device cookie (`auth_client_state`, 90 days on the banking
sites according to woob's `creditmutuel` module) that spares the second factor
for a while. `/initiate` accepts the previous session and replays its cookies,
as woob does. The mobile-confirmation choreography is the one woob documents
for these sites; it is reimplemented, not copied.

What differs per bank is a `Site`; the routes that read the bank's own pages
stay in each sidecar. Credentials are held in memory for one request and are
never logged; neither are cookies or field values.
"""

import asyncio
import json
import logging
import re
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Iterator
from urllib.parse import urljoin, urlsplit

import httpx
from fastapi import APIRouter, HTTPException
from picsou_sidecar import PendingStore, safe
from pydantic import BaseModel, ConfigDict, Field, ValidationError

from .forms import Form, field_names, find_form, html_text, parse_forms

log = logging.getLogger("euro-information")

LOGIN_FORM = "bloc_ident"
TRUST_COOKIE = "auth_client_state"
OTP_FIELD = "otp_password"

# The second factor lives on `validation.aspx` on the banking sites and on
# `premiers-pas/authentification-forte` on the Épargne Salariale one.
MFA_PATH_MARKERS = ("validation.aspx", "authentification-forte")
SKIP_PHRASES = ("passer cette etape", "passer cette étape")

PENDING_TTL_SECONDS = 600
PENDING_SWEEP_SECONDS = 30
MAX_PENDING = 16
# Stay under the Java adapter's validation timeout so the sidecar is always the
# one to give up, and can cancel the request on the phone.
APP_VALIDATION_TIMEOUT_SECONDS = 120
APP_VALIDATION_POLL_SECONDS = 2.0
REQUEST_TIMEOUT_SECONDS = 30.0
MAX_REDIRECTS = 10

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
_OTP_ERROR_RE = re.compile(r'<div\b[^>]*class="[^"]*\bbloctxt\s+err\b[^"]*"[^>]*>(?P<text>.*?)</div>', re.DOTALL)
_TRANSACTION_ID_RE = re.compile(r"transactionId\s*:\s*'(?P<id>[^']+)'")
_STATE_URL_RE = re.compile(r"""['"](?P<url>[^'"\s]*SOSD_OTP_GetTransactionState[^'"\s]*)['"]""")
_CANCEL_URL_RE = re.compile(r"""['"](?P<url>[^'"\s]*SOSD_OTP_CancelTransaction[^'"\s]*)['"]""")
_TRANSACTION_STATE_RE = re.compile(r"<transactionState>\s*(?P<state>\w+)\s*</transactionState>", re.IGNORECASE)
_LINK_RE = re.compile(r"<a\b(?P<attrs>[^>]*)>(?P<text>.*?)</a>", re.DOTALL | re.IGNORECASE)
_HREF_RE = re.compile(r'href="(?P<href>[^"]+)"')


@dataclass(frozen=True)
class Site:
    """What differs between the banks on the platform."""

    # Label of the bank in log lines.
    name: str
    base_url: str
    login_path: str
    # Present on a page only once logged in; the strong-authentication page has it too.
    logged_in_marker: str
    # Notices the site parks a login on until the customer acts on the website.
    action_needed_path_markers: tuple[str, ...]
    skip_phrases: tuple[str, ...] = SKIP_PHRASES
    # A notice with a "later" button that can be pressed instead of a link.
    postpone_button: str | None = None
    # Where the banking sites poll and cancel a mobile confirmation, per woob's
    # creditmutuel module; used when the page script does not name them. A site
    # with no default state path refuses a page whose script does not name it.
    default_state_path: str | None = None
    default_cancel_path: str | None = None
    # The cookies of a previous session that a new login replays; None: all of them.
    replayed_cookies: frozenset[str] | None = None
    # A confirmation poll that answers an error status or no state is read as
    # "the request no longer exists" (410) rather than as a changed format (502),
    # unless it is a 429 or a 5xx.
    unreadable_poll_expires: bool = False


class UpstreamError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)
    # The previous session, if any: its cookies carry the remembered device.
    sessionState: str | None = Field(default=None, max_length=2_000_000)
    # A scheduled re-login with stored credentials: nobody is there to approve,
    # so a mobile confirmation is withdrawn at once rather than left on the phone.
    unattended: bool = False


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    # The SMS code; absent for a mobile confirmation, which has nothing to type.
    code: str | None = Field(default=None, pattern=r"^\d{4,8}$")


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


# ─── Pending second factors ─────────────────────────────────────────────────


async def dispose_pending_state(state: dict[str, Any]) -> None:
    client: httpx.AsyncClient | None = state.get("client")
    if client is None:
        return
    try:
        await client.aclose()
    except Exception:
        log.warning("Euro-Information client cleanup failed", exc_info=True)


def pending_store(sidecar_log: logging.Logger) -> PendingStore:
    return PendingStore(
        dispose=dispose_pending_state,
        log=sidecar_log,
        ttl_seconds=PENDING_TTL_SECONDS,
        sweep_seconds=PENDING_SWEEP_SECONDS,
        max_size=MAX_PENDING,
    )


async def _park(store: PendingStore, process_id: str, client: httpx.AsyncClient, state: dict[str, Any]) -> None:
    state["client"] = client
    state.setdefault("created_at", time.time())
    if not await store.put(process_id, state):
        raise HTTPException(status_code=503, detail="UPSTREAM_UNAVAILABLE")


# ─── HTTP plumbing ──────────────────────────────────────────────────────────


def new_client(site: Site) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=site.base_url,
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


def restore_previous_session(site: Site, client: httpx.AsyncClient, raw: str | None) -> bool:
    """Carry a previous session's cookies over to a new login, as woob does.

    Whatever the second factor left (`auth_client_state`, the OTP-auth device
    id...) travels together, as woob keeps it, unless the site replays only some
    cookies: the rest of an old jar is a dead session that would only confuse
    the login. Returns whether the trust cookie was there. An unreadable
    previous session is ignored: it must never block a login.
    """
    if not raw:
        return False
    try:
        cookies = _decode_cookies(raw)
    except HTTPException:
        return False
    for cookie in cookies:
        if site.replayed_cookies is None or cookie["name"] in site.replayed_cookies:
            _set_cookie(client, cookie)
    return any(cookie["name"] == TRUST_COOKIE for cookie in cookies)


async def with_session(site: Site, raw_session: str, read: Callable[[httpx.AsyncClient], Awaitable[Any]]) -> Any:
    """Run `read` on a client carrying the cookies of `raw_session`, closed afterwards."""
    client = new_client(site)
    try:
        restore_cookies(client, raw_session)
        return await read(client)
    finally:
        await client.aclose()


def path_of(url: str | httpx.URL) -> str:
    return urlsplit(str(url)).path


def same_origin(site: Site, url: str) -> str:
    resolved = urljoin(site.base_url + "/", url)
    if urlsplit(resolved).netloc != urlsplit(site.base_url).netloc or urlsplit(resolved).scheme != "https":
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "A link left the bank's site")
    return resolved


async def follow(site: Site, client: httpx.AsyncClient, response: httpx.Response) -> httpx.Response:
    """Follow redirects by hand, staying on the bank's site."""
    for _ in range(MAX_REDIRECTS):
        if not response.is_redirect:
            return response
        target = same_origin(site, urljoin(str(response.url), response.headers.get("location", "")))
        response = await client.get(target)
    raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Too many redirects")


def link_with(page: str, *phrases: str) -> str | None:
    """The href of the first link whose text holds one of `phrases`."""
    for match in _LINK_RE.finditer(page):
        text = html_text(match.group("text")).lower()
        if any(phrase in text for phrase in phrases):
            href = _HREF_RE.search(match.group("attrs"))
            if href and not href.group("href").startswith(("#", "javascript:")):
                return href.group("href").replace("&amp;", "&")
    return None


def diagnose(site: Site, stage: str, response: httpx.Response) -> None:
    """Log the shape of an unexpected page: its path and field names, never values."""
    log.warning(
        "%s %s: unexpected page (status=%s; path=%s; forms=%s)",
        site.name,
        stage,
        response.status_code,
        safe(path_of(response.url), 200),
        field_names(response.text)[:40],
    )


def is_mfa_page(response: httpx.Response) -> bool:
    return any(marker in path_of(response.url) for marker in MFA_PATH_MARKERS)


def is_logged_in(site: Site, response: httpx.Response) -> bool:
    # The strong-authentication page carries the logout link too: being on it
    # is not being logged in.
    return not is_mfa_page(response) and site.logged_in_marker in response.text


def require_logged_in(site: Site, stage: str, response: httpx.Response) -> None:
    if not is_logged_in(site, response):
        diagnose(site, stage, response)
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Did not reach the customer space")


async def fetch_page(site: Site, client: httpx.AsyncClient, path: str, label: str) -> str:
    """A page of the customer space, or why the session cannot read it."""
    response = await client.get(path)
    if response.is_redirect:
        target = urljoin(str(response.url), response.headers.get("location", ""))
        if site.login_path in target or "identification" in target:
            raise UpstreamError("SESSION_EXPIRED", "Session expired")
        response = await follow(site, client, response)
    if response.status_code in (401, 403) or site.login_path in path_of(response.url):
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    if response.status_code != 200:
        code = "UPSTREAM_UNAVAILABLE" if response.status_code == 429 or response.status_code >= 500 else "UPSTREAM_FORMAT_CHANGED"
        raise UpstreamError(code, f"{label} page answered HTTP {response.status_code}")
    if not is_logged_in(site, response):
        # Parked on the strong-authentication page: the session needs a new
        # second factor, which only the customer can give.
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    return response.text


# ─── Login ──────────────────────────────────────────────────────────────────


def _login_error(page: str) -> str | None:
    block = _ERROR_BLOCK_RE.search(page)
    if block is None:
        return None
    message = html_text(block.group("text")).lower()
    if any(marker in message for marker in _BAD_CREDENTIALS_MARKERS):
        return "INVALID_CREDENTIALS"
    if any(marker in message for marker in _LOCKED_MARKERS):
        # A locked access must not be retried with the stored password either.
        return "INVALID_CREDENTIALS"
    return "UPSTREAM_FORMAT_CHANGED"


async def _login(site: Site, client: httpx.AsyncClient, login: str, password: str) -> httpx.Response:
    """Submit the login form and return the page the login lands on."""
    login_page = await follow(site, client, await client.get(site.login_path))
    form = find_form(login_page.text, name=LOGIN_FORM)
    if form is None:
        diagnose(site, "login page", login_page)
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No login form")
    fields = dict(form.fields)
    fields.update({"_cm_user": login, "_cm_pwd": password, "_charset_": "UTF-8"})
    target = same_origin(site, urljoin(str(login_page.url), form.action or ""))
    response = await client.post(
        target,
        data=fields,
        headers={"Origin": site.base_url, "Referer": str(login_page.url)},
    )
    if not response.is_redirect:
        code = _login_error(response.text)
        if code is None:
            diagnose(site, "login", response)
            code = "UPSTREAM_FORMAT_CHANGED"
        raise UpstreamError(code, "Login refused")
    landing = await follow(site, client, response)
    if path_of(landing.url) == site.login_path:
        # Sent back to the login form: the site answers a wrong password this
        # way once the form itself was accepted.
        code = _login_error(landing.text) or "INVALID_CREDENTIALS"
        raise UpstreamError(code, "Login refused")
    return landing


async def _skip_notices(site: Site, client: httpx.AsyncClient, response: httpx.Response) -> httpx.Response:
    """Step over the skippable notices the site parks a login on."""
    for _ in range(3):
        if not any(marker in path_of(response.url) for marker in site.action_needed_path_markers):
            return response
        button = site.postpone_button
        later = find_form(response.text, button=button) if button else None
        if button and later is not None:
            fields = {**later.fields, button: later.buttons[button]}
            target = same_origin(site, urljoin(str(response.url), later.action or str(response.url)))
            response = await follow(site, client, await client.post(target, data=fields))
            continue
        skip = link_with(response.text, *site.skip_phrases)
        if skip is None:
            log.info(
                "%s: login parked on a notice that needs the customer (path=%s)",
                site.name,
                safe(path_of(response.url), 200),
            )
            raise UpstreamError("ACTION_REQUIRED", "The site needs the customer to act on its website")
        response = await follow(site, client, await client.get(same_origin(site, skip)))
    raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Too many notices")


async def _bypass_second_factor(site: Site, client: httpx.AsyncClient, page: httpx.Response) -> httpx.Response | None:
    """Follow the link the confirmation page offers when it can be skipped.

    Shown while the remembered-device cookie is still valid ("confirmer votre
    identité plus tard") or when the site does not require the confirmation.
    """
    link = link_with(page.text, "sans confirmation mobile", "accéder à votre espace client")
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
    log.info("%s: second factor skipped by the site's own link", site.name)
    return await follow(site, client, await client.get(same_origin(site, link)))


def _otp_form(page: str) -> Form | None:
    """The form the SMS code is typed into, recognised by its code field."""
    for form in parse_forms(page):
        if OTP_FIELD in form.fields:
            return form
    return None


def _sms_fields(form: Form) -> dict[str, str]:
    """The fields the code goes with, the form's submit button pressed.

    The page's one named button, `_FID_CheckOrDeliverBackup`, is what checks
    the code: a post without it is a mere refresh, and the site answers it by
    sending a new SMS. Like woob's forms, every named submit is sent.
    """
    fields = {name: value for name, value in form.fields.items() if name != OTP_FIELD}
    fields.update(form.buttons)
    return fields


def _start_sms(site: Site, page: httpx.Response, form: Form) -> dict[str, Any]:
    page_url = str(page.url)
    fields = _sms_fields(form)
    log.info("%s: code sent by SMS (form buttons=%s)", site.name, list(form.buttons)[:10])
    return {
        "kind": "SMS",
        "finalUrl": same_origin(site, urljoin(page_url, form.action or page_url)),
        "finalFields": fields,
        "pageUrl": page_url,
    }


async def _submit_sms_code(site: Site, client: httpx.AsyncClient, state: dict[str, Any], code: str) -> httpx.Response:
    """Post the code with the page state, the way the bank's own form does.

    The hidden fields, the code and the form's button: see `_sms_fields`.
    """
    response = await client.post(
        state["finalUrl"],
        data={**state["finalFields"], OTP_FIELD: code},
        headers={"Origin": site.base_url, "Referer": state["pageUrl"]},
    )
    landing = await follow(site, client, response)
    if site.login_path in path_of(landing.url):
        raise UpstreamError("AUTH_ATTEMPT_EXPIRED", "The SMS code is no longer valid")
    retry = _otp_form(landing.text)
    if retry is not None:
        error = _OTP_ERROR_RE.search(landing.text)
        message = html_text(error.group("text")).lower() if error else ""
        if "bloqué" in message or "erreurs de saisie" in message:
            raise UpstreamError("ACTION_REQUIRED", "Too many wrong codes, access locked")
        # Same page again: the code was wrong. The customer may try once more on
        # this same request, with the page's refreshed state.
        state["finalFields"] = _sms_fields(retry)
        state["pageUrl"] = str(landing.url)
        raise UpstreamError("INVALID_OTP", "Wrong SMS code")
    if TRUST_COOKIE not in {cookie.name for cookie in client.cookies.jar}:
        log.info("%s: no remembered-device cookie after the SMS code; every login will ask again", site.name)
    return await _skip_notices(site, client, landing)


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


def _start_mobile_confirmation(site: Site, page: httpx.Response) -> dict[str, Any]:
    body = page.text
    transaction = _TRANSACTION_ID_RE.search(body)
    form = _confirmation_form(body)
    state_url = _STATE_URL_RE.search(body)
    state_path = state_url.group("url") if state_url else site.default_state_path
    if transaction is None or form is None or state_path is None:
        diagnose(site, "mobile confirmation", page)
        log.warning(
            "%s mobile confirmation markers (transactionId=%s; form=%s; stateUrl=%s)",
            site.name,
            transaction is not None,
            form is not None,
            state_url is not None,
        )
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Unknown confirmation page")
    cancel_url = _CANCEL_URL_RE.search(body)
    page_url = str(page.url)
    cancel_path = cancel_url.group("url") if cancel_url else site.default_cancel_path
    return {
        "transactionId": transaction.group("id"),
        "stateUrl": same_origin(site, urljoin(page_url, state_path)),
        "cancelUrl": same_origin(site, urljoin(page_url, cancel_path)) if cancel_path else None,
        "finalUrl": same_origin(site, urljoin(page_url, form.action or page_url)),
        # With the form's buttons, as woob's forms send them: without them the
        # site keeps the login on the strong-authentication page even though
        # the phone approved it.
        "finalFields": {**form.fields, **form.buttons},
        "pageUrl": page_url,
    }


async def _withdraw_confirmation(site: Site, client: httpx.AsyncClient, state: dict[str, Any]) -> None:
    """Cancel a confirmation request so the phone does not keep showing it."""
    if not state.get("cancelUrl"):
        return
    try:
        await client.post(state["cancelUrl"], data={"transactionId": state["transactionId"]})
        log.info("%s: withdrew the confirmation request", site.name)
    except httpx.HTTPError:
        log.info("%s could not withdraw the confirmation request", site.name)


def _unreadable_poll(site: Site, response: httpx.Response) -> UpstreamError:
    status = response.status_code
    if status == 429 or status >= 500:
        return UpstreamError("UPSTREAM_UNAVAILABLE", "Confirmation polling is unavailable")
    sent_elsewhere = response.is_redirect or status in (401, 403)
    if status == 200:
        # Sent to the login page: the request is gone.
        sent_elsewhere = site.login_path in path_of(response.url) or LOGIN_FORM in response.text
    if sent_elsewhere or site.unreadable_poll_expires:
        return UpstreamError("AUTH_ATTEMPT_EXPIRED", "Confirmation request no longer exists")
    return UpstreamError("UPSTREAM_FORMAT_CHANGED", "Unreadable confirmation polling response")


async def _await_confirmation(site: Site, client: httpx.AsyncClient, state: dict[str, Any]) -> None:
    deadline = time.monotonic() + APP_VALIDATION_TIMEOUT_SECONDS
    data = {"transactionId": state["transactionId"]}
    while time.monotonic() < deadline:
        try:
            response = await client.post(state["stateUrl"], data=data)
        except httpx.HTTPError:
            # The request may still be live on the phone: withdraw it before
            # reporting the failure.
            await _withdraw_confirmation(site, client, state)
            raise
        match = _TRANSACTION_STATE_RE.search(response.text) if response.status_code == 200 else None
        if match is None:
            # An answer without a state proves nothing about the phone, which may
            # still show the request: withdraw it whatever the cause.
            await _withdraw_confirmation(site, client, state)
            log.info("%s confirmation polling answered without a state (status=%s)", site.name, response.status_code)
            raise _unreadable_poll(site, response)
        current = match.group("state").upper()
        if current == "VALIDATED":
            return
        if current in ("CANCELLED", "NONE"):
            raise UpstreamError("APP_VALIDATION_CANCELLED", "Confirmation refused on the phone")
        if current != "PENDING":
            await _withdraw_confirmation(site, client, state)
            log.warning("%s confirmation polling returned an unknown state", site.name)
            raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Unknown confirmation state")
        await asyncio.sleep(APP_VALIDATION_POLL_SECONDS)
    # Withdraw the request from the phone rather than leave it pending.
    await _withdraw_confirmation(site, client, state)
    raise UpstreamError("APP_VALIDATION_TIMEOUT", "Confirmation not approved in time")


async def _finish_confirmation(site: Site, client: httpx.AsyncClient, state: dict[str, Any]) -> httpx.Response:
    response = await client.post(
        state["finalUrl"],
        data=state["finalFields"],
        headers={"Origin": site.base_url, "Referer": state["pageUrl"]},
    )
    if TRUST_COOKIE not in {cookie.name for cookie in client.cookies.jar}:
        log.info("%s: no remembered-device cookie after confirmation; every login will ask again", site.name)
    return await _skip_notices(site, client, await follow(site, client, response))


# ─── Flow ───────────────────────────────────────────────────────────────────


async def start_login(site: Site, store: PendingStore, req: InitiateRequest) -> dict:
    await store.expire()
    process_id = str(uuid.uuid4())
    client = new_client(site)
    keep_client = False
    try:
        trusted = restore_previous_session(site, client, req.sessionState)
        landing = await _skip_notices(site, client, await _login(site, client, req.login, req.password))

        if is_mfa_page(landing):
            bypassed = await _bypass_second_factor(site, client, landing)
            if bypassed is not None:
                landing = await _skip_notices(site, client, bypassed)

        if not is_mfa_page(landing):
            require_logged_in(site, "login landing", landing)
            return {
                "processId": None,
                "mfaRequired": False,
                "mfaType": None,
                "sessionState": serialize_cookies(client),
            }

        sms_form = _otp_form(landing.text)
        if sms_form is not None and req.unattended:
            # The site sent the SMS on reaching this page; nothing to withdraw.
            log.info("%s: unattended re-login stopped at an SMS code", site.name)
            raise UpstreamError("SESSION_EXPIRED", "Unattended re-login needs an SMS code")
        if sms_form is not None:
            await _park(store, process_id, client, _start_sms(site, landing, sms_form))
            keep_client = True
            return {
                "processId": process_id,
                "mfaRequired": True,
                "mfaType": "SMS",
                "sessionState": None,
            }

        unsupported = _unsupported_factor(landing.text)
        if unsupported:
            log.info("%s asked for an unsupported second factor: %s", site.name, unsupported)
            raise UpstreamError("MFA_TYPE_UNSUPPORTED", "Unsupported second factor")
        if trusted:
            log.info("%s: remembered-device cookie no longer spares the confirmation", site.name)

        state = _start_mobile_confirmation(site, landing)
        if req.unattended:
            await _withdraw_confirmation(site, client, state)
            raise UpstreamError("SESSION_EXPIRED", "Unattended re-login needs a mobile confirmation")
        state["kind"] = "APP_PUSH"
        await _park(store, process_id, client, state)
        keep_client = True
        return {
            "processId": process_id,
            "mfaRequired": True,
            "mfaType": "APP_PUSH",
            "sessionState": None,
        }
    finally:
        if not keep_client:
            await client.aclose()


async def finish_login(site: Site, store: PendingStore, req: CompleteRequest) -> dict:
    await store.expire()
    state = await store.take(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - store.ttl_seconds:
        await dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

    client: httpx.AsyncClient = state["client"]
    keep_state = False
    try:
        if state["kind"] == "SMS":
            if req.code is None:
                # Not a failure of the attempt: the code can still be sent.
                raise UpstreamError("INVALID_OTP", "An SMS code is required")
            landing = await _submit_sms_code(site, client, state, req.code)
        else:
            if req.code is not None:
                raise UpstreamError("MFA_TYPE_UNSUPPORTED", "A mobile confirmation takes no code")
            await _await_confirmation(site, client, state)
            landing = await _finish_confirmation(site, client, state)
        require_logged_in(site, "after second factor", landing)
        return {"sessionState": serialize_cookies(client)}
    except UpstreamError as exc:
        if exc.code == "INVALID_OTP" and state["kind"] == "SMS":
            # A mistyped code leaves the request open on the site: keep it so
            # the customer can type the code again. Only a stored attempt is kept:
            # if the pool refuses it, `finally` still closes its client.
            await _park(store, req.processId, client, state)
            keep_state = True
        raise
    finally:
        if not keep_state:
            await dispose_pending_state(state)


# ─── Routes ─────────────────────────────────────────────────────────────────


def http_error(exc: Exception) -> HTTPException:
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


@contextmanager
def http_errors(site: Site, stage: str, *bank_errors: type[Exception], validated: bool = False) -> Iterator[None]:
    """Answer what a route raises with the sidecar's HTTP errors.

    `bank_errors` are the bank's own parse errors: they carry a `code` like
    `UpstreamError`. With `validated`, a pydantic ValidationError is the
    sidecar's own response model refusing what was read: 502 INVALID_DATA.
    """
    try:
        yield
    except (UpstreamError, *bank_errors) as exc:
        log.warning("%s %s rejected (code=%s): %s", site.name, stage, getattr(exc, "code", None), exc)
        raise http_error(exc) from exc
    except ValidationError as exc:
        if not validated:
            log.exception("Unexpected %s %s failure", site.name, stage)
            raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
        log.warning("%s %s payload failed validation: %s", site.name, stage, exc.error_count())
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except httpx.HTTPError as exc:
        log.warning("%s %s failed", site.name, stage, exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected %s %s failure", site.name, stage)
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


def auth_router(site: Site, store: PendingStore) -> APIRouter:
    """`/initiate` and `/complete`: the login and its second factor."""
    router = APIRouter()

    @router.post("/initiate", response_model=InitiateResponse)
    async def initiate(req: InitiateRequest) -> dict:
        with http_errors(site, "login"):
            return await start_login(site, store, req)

    @router.post("/complete", response_model=SessionResponse)
    async def complete(req: CompleteRequest) -> dict:
        with http_errors(site, "second factor"):
            return await finish_login(site, store, req)

    return router
