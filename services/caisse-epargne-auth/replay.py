"""Session replay transport for the Caisse d'Epargne web front end.

The bank has no public API. The data calls need a short-lived Bearer token
that the browser obtains with an OAuth2 PKCE + SAML chain; the SSO cookies
captured at login are enough to replay that chain without a browser. Observed
on the real bank (2026-10-06/07, see spike/E2E_FINDINGS.md):

  1. POST {authorize}?<params>  (fresh PKCE challenge + nonce)
       -> {action, parameters{SAMLRequest}}
  2. POST action  form SAMLRequest  (cookies carry the SSO session)
       -> {response{status: "AUTHENTICATION_SUCCESS", saml2_post{action, samlResponse}}}
  3. POST saml2_post.action  form SAMLResponse
       -> {parameters{code}}
  4. POST {token}  form grant_type/client_id/code/code_verifier/redirect_uri
       -> {access_token, token_type: "Bearer", expires_in ~ 267 s}

The AUTHENTICATION_SUCCESS status of step 2 is the session-validity signal.
`/gsu/sessions/me/status` is not: it answers 202 on live sessions too.

Never logged or returned: cookies, SAML payloads, authorization codes, tokens
and the authorize parameters (they carry a login hint).

Deliberately absent: login initiation, password, virtual keypad, Securpass,
logout and accounts reading.
"""

import asyncio
import base64
import hashlib
import json
import logging
import re
import secrets
import time
import urllib.parse
import weakref
from dataclasses import dataclass, field
from typing import Any, Callable

import httpx

log = logging.getLogger("caisse-epargne-auth")
# httpx logs every request URL at INFO and the authorize URL carries the
# authorize parameters (including a login hint) in its query string.
for _noisy in ("httpx", "httpcore"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)

# Hosts observed on the real bank; the chain is replayed against these unless
# the caller passes others. Whatever is used must pass `is_allowed_url`.
AUTHORIZE_URL = "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/authorize"
TOKEN_URL = "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/token"

AUTHN_SUCCESS = "AUTHENTICATION_SUCCESS"

REQUEST_TIMEOUT_SECONDS = 25.0
CONNECT_TIMEOUT_SECONDS = 10.0
MAX_RESPONSE_BYTES = 1_048_576
# The token is dropped this long before the bank says it expires.
TOKEN_SAFETY_MARGIN_SECONDS = 30.0
MAX_TOKEN_LIFETIME_SECONDS = 3600.0
MAX_CACHED_TOKENS = 64

USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)

_HOST_RE = re.compile(r"^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+caisse-epargne\.fr$")


# --- Typed errors ------------------------------------------------------------


class ReplayError(Exception):
    """Base class. `code` is the stable, caller-facing error code.

    Messages are fixed strings: they never embed upstream or caller data.
    """

    code = "UPSTREAM_ERROR"

    def __init__(self, message: str | None = None):
        super().__init__(message or self.code)


class InvalidSessionState(ReplayError):
    code = "INVALID_SESSION_STATE"


class SessionExpired(ReplayError):
    code = "SESSION_EXPIRED"


class UpstreamError(ReplayError):
    code = "UPSTREAM_ERROR"


class HostNotAllowed(UpstreamError):
    """A URL left the https *.caisse-epargne.fr allow-list; nothing was sent."""


# --- SSRF guard --------------------------------------------------------------


def is_allowed_url(url: Any) -> bool:
    """True only for an https URL on a strict `*.caisse-epargne.fr` host."""
    if not isinstance(url, str) or not url or "\\" in url:
        return False
    if any(ord(char) <= 0x20 or ord(char) == 0x7F for char in url):
        return False
    try:
        parsed = urllib.parse.urlsplit(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        return False
    if parsed.scheme != "https" or parsed.username is not None or parsed.password is not None:
        return False
    if port not in (None, 443) or not host:
        return False
    return _HOST_RE.match(host) is not None


def _require_allowed(url: Any) -> str:
    if not is_allowed_url(url):
        raise HostNotAllowed("Refused a URL outside https *.caisse-epargne.fr")
    return url


# --- Session state -----------------------------------------------------------


@dataclass(frozen=True)
class SessionState:
    cookies: list[dict[str, str]] = field(repr=False)
    authorize_params: dict[str, str] = field(repr=False)


def parse_session_state(raw: Any) -> SessionState:
    """Validate `{cookies:[{name,value,domain,path}], authorizeParams:{...}}`.

    `authorizeParams` is the authorize query as captured at login; its content
    is opaque apart from the two fields the token request repeats.
    """
    if not isinstance(raw, str):
        raise InvalidSessionState("Session state is not a string")
    try:
        decoded = json.loads(raw)
    except ValueError as exc:
        raise InvalidSessionState("Session state is not JSON") from exc
    if not isinstance(decoded, dict):
        raise InvalidSessionState("Session state is not an object")

    cookies = decoded.get("cookies")
    if not isinstance(cookies, list) or not cookies:
        raise InvalidSessionState("Session state has no cookies")
    clean_cookies: list[dict[str, str]] = []
    for cookie in cookies:
        if (
            not isinstance(cookie, dict)
            or not isinstance(cookie.get("name"), str)
            or not cookie["name"]
            or not isinstance(cookie.get("value"), str)
            or not isinstance(cookie.get("domain"), str)
            or not cookie["domain"]
            or not isinstance(cookie.get("path", "/"), str)
        ):
            raise InvalidSessionState("Session state has a malformed cookie")
        clean_cookies.append(
            {
                "name": cookie["name"],
                "value": cookie["value"],
                "domain": cookie["domain"],
                "path": cookie.get("path") or "/",
            }
        )

    params = decoded.get("authorizeParams")
    if not isinstance(params, dict) or not params:
        raise InvalidSessionState("Session state has no authorize parameters")
    if not all(isinstance(k, str) and isinstance(v, str) for k, v in params.items()):
        raise InvalidSessionState("Authorize parameters must be strings")
    for required in ("client_id", "redirect_uri"):
        if not params.get(required):
            raise InvalidSessionState("Authorize parameters are incomplete")

    return SessionState(cookies=clean_cookies, authorize_params=dict(params))


# --- HTTP client and cookies ---------------------------------------------------


def new_client(transport: httpx.AsyncBaseTransport | None = None) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS, connect=CONNECT_TIMEOUT_SECONDS),
        # A redirect would hand the cookies to a host this module never vetted.
        follow_redirects=False,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json, text/plain, */*"},
        transport=transport,
    )


def serialize_cookies(client: httpx.AsyncClient) -> list[dict[str, str]]:
    """Dump the jar with each cookie's own domain and path."""
    return [
        {
            "name": cookie.name,
            "value": cookie.value or "",
            "domain": cookie.domain or "",
            "path": cookie.path or "/",
        }
        for cookie in client.cookies.jar
    ]


def restore_cookies(client: httpx.AsyncClient, cookies: list[dict[str, str]]) -> None:
    for cookie in cookies:
        client.cookies.set(
            cookie["name"],
            cookie["value"],
            domain=cookie["domain"],
            path=cookie.get("path") or "/",
        )


# --- Bounded HTTP helpers --------------------------------------------------------


async def _request(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    form: dict[str, str] | None = None,
) -> tuple[int, bytes, str | None]:
    """Send and return (status, body, Location); body capped at MAX_RESPONSE_BYTES."""
    _require_allowed(url)
    headers: dict[str, str] = {}
    if method == "POST" and form is None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    try:
        async with client.stream(method, url, data=form, headers=headers) as response:
            chunks: list[bytes] = []
            size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > MAX_RESPONSE_BYTES:
                    raise UpstreamError("Upstream response too large")
                chunks.append(chunk)
            return response.status_code, b"".join(chunks), response.headers.get("location")
    except httpx.HTTPError:
        # Fixed message, no chained cause: httpx errors can embed URLs.
        raise UpstreamError("Upstream request failed") from None


async def _send(
    client: httpx.AsyncClient,
    url: str,
    *,
    form: dict[str, str] | None = None,
) -> tuple[int, bytes]:
    """POST and return (status, body); the body is capped at MAX_RESPONSE_BYTES."""
    status, body, _ = await _request(client, "POST", url, form=form)
    return status, body


async def _send_authn(client: httpx.AsyncClient, url: str, form: dict[str, str]) -> tuple[int, bytes]:
    """POST the AuthnRequest, following at most ONE 303 on the same https host.

    Observed live 2026-10-07: the servlet answers `303 Location:
    /dacsrest/api/v1u0/transaction/<ctx>` and the JSON comes from a GET there.
    A redirect to any other host, scheme or a second redirect is refused before
    anything is sent, so the SSO cookies never leave the vetted host.
    """
    status, body, location = await _request(client, "POST", url, form=form)
    if status != 303:
        return status, body
    if not location:
        raise UpstreamError("Authentication redirect without a location")
    target = urllib.parse.urljoin(url, location)
    source, dest = urllib.parse.urlsplit(url), urllib.parse.urlsplit(target)
    if dest.scheme != "https" or dest.netloc.lower() != source.netloc.lower():
        raise HostNotAllowed("Refused an authentication redirect to another host")
    status, body, _ = await _request(client, "GET", target)
    if 300 <= status < 400:
        raise UpstreamError("Authentication step redirected twice")
    return status, body


def _json_object(body: bytes) -> dict[str, Any] | None:
    try:
        decoded = json.loads(body)
    except ValueError:
        return None
    return decoded if isinstance(decoded, dict) else None


def _non_empty_str(value: Any) -> bool:
    return isinstance(value, str) and bool(value)


def _s256_challenge(verifier: str) -> str:
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


# --- The replay chain --------------------------------------------------------------


@dataclass(frozen=True)
class TokenResult:
    access_token: str = field(repr=False)
    expires_in: float


async def replay_chain(
    state: SessionState,
    *,
    transport: httpx.AsyncBaseTransport | None = None,
    authorize_url: str = AUTHORIZE_URL,
    token_url: str = TOKEN_URL,
) -> TokenResult:
    """Run authorize -> AuthnRequest -> consume -> token with the session cookies.

    Raises SessionExpired when the bank no longer accepts the SSO session,
    HostNotAllowed (an UpstreamError) when any URL leaves the allow-list, and
    UpstreamError for every other failure. Nothing is sent before the
    authorize/token URLs pass the allow-list.
    """
    _require_allowed(authorize_url)
    _require_allowed(token_url)
    params = state.authorize_params

    verifier = secrets.token_urlsafe(48)
    query = {
        **params,
        "code_challenge": _s256_challenge(verifier),
        "code_challenge_method": "S256",
        "nonce": secrets.token_urlsafe(24),
    }

    client = new_client(transport)
    try:
        restore_cookies(client, state.cookies)

        # 1. authorize
        status, body = await _send(client, f"{authorize_url}?{urllib.parse.urlencode(query)}")
        authorize = _json_object(body)
        if status != 200 or authorize is None:
            raise UpstreamError("Authorize step failed")
        authorize_params = authorize.get("parameters")
        saml_request = authorize_params.get("SAMLRequest") if isinstance(authorize_params, dict) else None
        if not _non_empty_str(saml_request):
            raise UpstreamError("Authorize step returned no SAML request")
        authn_url = _require_allowed(authorize.get("action"))

        # 2. AuthnRequest: the status is the session-validity signal.
        status, body = await _send_authn(client, authn_url, {"SAMLRequest": saml_request})
        if status in (401, 403):
            raise SessionExpired("Session no longer accepted")
        if status != 200:
            raise UpstreamError("Authentication step failed")
        authn = _json_object(body)
        authn_response = authn.get("response") if authn else None
        if not isinstance(authn_response, dict) or authn_response.get("status") != AUTHN_SUCCESS:
            raise SessionExpired("Session no longer accepted")
        saml2_post = authn_response.get("saml2_post")
        if not isinstance(saml2_post, dict):
            raise SessionExpired("Session no longer accepted")
        consume_url = _require_allowed(saml2_post.get("action"))
        saml_response = saml2_post.get("samlResponse")
        if not _non_empty_str(saml_response):
            raise SessionExpired("Session no longer accepted")

        # 3. consume
        status, body = await _send(client, consume_url, form={"SAMLResponse": saml_response})
        consume = _json_object(body)
        consume_params = consume.get("parameters") if consume else None
        code = consume_params.get("code") if isinstance(consume_params, dict) else None
        if status != 200 or not _non_empty_str(code):
            raise UpstreamError("Consume step failed")

        # 4. token
        status, body = await _send(
            client,
            token_url,
            form={
                "grant_type": "authorization_code",
                "client_id": params["client_id"],
                "code": code,
                "code_verifier": verifier,
                "redirect_uri": params["redirect_uri"],
            },
        )
        token = _json_object(body)
        if status != 200 or token is None:
            raise UpstreamError("Token step failed")
        access_token = token.get("access_token")
        expires_in = token.get("expires_in")
        token_type = token.get("token_type")
        if (
            not _non_empty_str(access_token)
            or not isinstance(token_type, str)
            or token_type.lower() != "bearer"
            or isinstance(expires_in, bool)
            or not isinstance(expires_in, (int, float))
            or not 0 < expires_in <= MAX_TOKEN_LIFETIME_SECONDS
        ):
            raise UpstreamError("Token step returned an unusable token")
        return TokenResult(access_token=access_token, expires_in=expires_in)
    except ReplayError as exc:
        log.warning("token replay failed: %s", exc.code)
        raise
    finally:
        await client.aclose()


# --- In-memory token cache -------------------------------------------------------------


class TokenCache:
    """Holds access tokens in memory only, until `expires_in - 30 s`.

    Keyed by a hash of the session state so neither cookies nor tokens are
    used as dictionary keys; never persisted, never logged.
    """

    def __init__(self, clock: Callable[[], float] = time.monotonic):
        self._clock = clock
        self._entries: dict[str, tuple[str, float]] = {}
        # One lock per session, so concurrent callers share a single replay chain.
        # Weak values: a lock disappears once no caller holds or awaits it.
        self._locks: weakref.WeakValueDictionary[str, asyncio.Lock] = weakref.WeakValueDictionary()

    def __len__(self) -> int:
        return len(self._entries)

    def _evict(self, now: float) -> None:
        for key in [k for k, (_, expiry) in self._entries.items() if expiry <= now]:
            del self._entries[key]
        while len(self._entries) >= MAX_CACHED_TOKENS:
            del self._entries[next(iter(self._entries))]

    @staticmethod
    def _key(state: "SessionState") -> str:
        return hashlib.sha256(
            json.dumps([state.cookies, state.authorize_params], sort_keys=True).encode()
        ).hexdigest()

    def evict(self, raw_state: Any) -> None:
        """Forget the cached token of this session (e.g. after the bank rejected it)."""
        self._entries.pop(self._key(parse_session_state(raw_state)), None)

    async def get(
        self,
        raw_state: Any,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        authorize_url: str = AUTHORIZE_URL,
        token_url: str = TOKEN_URL,
    ) -> TokenResult:
        state = parse_session_state(raw_state)
        key = self._key(state)
        lock = self._locks.get(key)
        if lock is None:
            lock = self._locks[key] = asyncio.Lock()

        async with lock:
            now = self._clock()
            cached = self._entries.get(key)
            if cached is not None:
                token, expiry = cached
                if now < expiry - TOKEN_SAFETY_MARGIN_SECONDS:
                    return TokenResult(access_token=token, expires_in=expiry - now)
                del self._entries[key]

            result = await replay_chain(
                state, transport=transport, authorize_url=authorize_url, token_url=token_url
            )
            now = self._clock()
            if result.expires_in > TOKEN_SAFETY_MARGIN_SECONDS:
                self._evict(now)
                self._entries[key] = (result.access_token, now + result.expires_in)
            return result
