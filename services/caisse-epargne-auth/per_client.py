"""Conservative PER insurer routing and raw-detail HTTP transport.

The routing contract and insurer handshake follow the shapes observed live:
`prepareRouting` answers 201 with form parameters only, the insurer serves one
JSF routing page whose inline script clicks `formRoutageButton`, then a 302
(with a client-side fragment) lands on the insurer single-page app, which has
no form. The raw-detail endpoint after that landing is not yet verified live.
This module does not execute JavaScript or initiate a username, password, or
OTP challenge; every step is fail-closed.
"""

from __future__ import annotations

import json
import re
import urllib.parse
from html.parser import HTMLParser
from typing import Any

import httpx

import replay


ROUTING_API_HOST = "www.rs-ext-bad-ce.caisse-epargne.fr"
ROUTING_API_BASE = f"https://{ROUTING_API_HOST}/bapi/contract/v1"
INSURER_HOST = "www.espace-assurances.caisse-epargne.fr"
INSURER_BASE = f"https://{INSURER_HOST}"
MAX_SSO_HOPS = 5

_ENTITY_CODE_RE = re.compile(r"[0-9]{1,10}\Z")
_CONTRACT_ID_RE = re.compile(r"[0-9]{5} {1,16}PERIC(?P<suffix>[0-9]{1,64})\Z")
_BEARER_RE = re.compile(r"[A-Za-z0-9._~+/-]+={0,}\Z")
_BAD_PERCENT_RE = re.compile(r"%(?![0-9A-Fa-f]{2})")
# Whole name words that denote a credential or challenge field. Matching words
# (not substrings) keeps the live `urlPing` routing parameter from reading as a PIN.
_CHALLENGE_WORDS = frozenset((
    "pwd",
    "login",
    "user",
    "secret",
    "username",
    "userid",
    "password",
    "passwd",
    "passcode",
    "otp",
    "onetime",
    "verificationcode",
    "pin",
    "securpass",
))
# Long, unambiguous parts that are refused even when glued to other words.
_CHALLENGE_SUBSTRINGS = (
    "pwd",
    "login",
    "password",
    "passwd",
    "passcode",
    "username",
    "userid",
    "secret",
    "securpass",
    "verificationcode",
    "onetime",
)
_NAME_WORD_RE = re.compile(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+")
_SUPPORTED_SSO_RELAY_FIELDS = frozenset(("SAMLResponse", "RelayState"))
_ROUTING_SUCCESS_STATUSES = (200, 201)
_JSF_ROUTING_PATH = "/espaceinternet-ce/views/common/routage-itce.xhtml"
_JSF_ROUTING_FORM_ID = "formRoutage"
_JSF_ROUTING_SUBMIT = "formRoutageButton"
_JSF_VIEW_STATE = "javax.faces.ViewState"
_JSF_STATE_FIELDS = frozenset((_JSF_VIEW_STATE, "javax.faces.ClientWindow"))
# Live inline script: `if (false) {...location...href} else {...formRoutageButton...click()}`;
# only punctuation separates the button id from the click call.
_JSF_CLICK_RE = re.compile(re.escape(_JSF_ROUTING_SUBMIT) + r"[^A-Za-z_]{0,20}click\s*\(\s*\)")
_INSURER_LANDING_PATH = "/etna-ihs-ce/"
_INSURER_STATUS_MESSAGES = {
    461: "PER insurer refused the request with insurer status 461",
}


class InvalidPerRequest(replay.ReplayError):
    """The caller did not supply an unambiguous PER request."""

    code = "INVALID_PER_REQUEST"


class _DuplicateKey(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKey
        result[key] = value
    return result


def _reject_json_constant(_: str) -> None:
    raise ValueError


def _parse_state(raw: str) -> replay.SessionState:
    try:
        if not isinstance(raw, str):
            raise ValueError
        # replay.parse_session_state performs the canonical field validation;
        # this first pass additionally rejects ambiguous duplicate JSON keys.
        json.loads(raw, object_pairs_hook=_unique_object, parse_constant=_reject_json_constant)
        return replay.parse_session_state(raw)
    except (replay.InvalidSessionState, ValueError, TypeError, UnicodeError):
        raise replay.InvalidSessionState("Invalid PER session state") from None


def _entity_code(state: replay.SessionState, supplied: str | None = None) -> str:
    value = supplied if supplied is not None else state.authorize_params.get("cdetab")
    if not isinstance(value, str) or not _ENTITY_CODE_RE.fullmatch(value):
        raise replay.InvalidSessionState("Session state has no unambiguous entity code")
    return value


def _contract_suffix(contract_id: str) -> str:
    if not isinstance(contract_id, str) or len(contract_id) > 128:
        raise InvalidPerRequest("Invalid PER contract identifier")
    match = _CONTRACT_ID_RE.fullmatch(contract_id)
    if match is None:
        raise InvalidPerRequest("Invalid PER contract identifier")
    return match.group("suffix")


def _validate_token(access_token: str) -> None:
    if (
        not isinstance(access_token, str)
        or not access_token
        or len(access_token) > 8192
        or _BEARER_RE.fullmatch(access_token) is None
    ):
        raise InvalidPerRequest("Invalid access token")


def _guard_url(url: Any, expected_host: str) -> str:
    if not isinstance(url, str) or not url or "\\" in url:
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    if any(char.isspace() or ord(char) < 0x20 or ord(char) == 0x7F for char in url):
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError:
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host") from None
    if (
        parsed.scheme != "https"
        or parsed.hostname is None
        or parsed.hostname.lower() != expected_host
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or bool(parsed.fragment)
        or _BAD_PERCENT_RE.search(url)
    ):
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    return url


def _is_challenge_name(name: str) -> bool:
    words = [word.casefold() for word in _NAME_WORD_RE.findall(name)]
    if any(word in _CHALLENGE_WORDS for word in words):
        return True
    normalized = re.sub(r"[^a-z0-9]", "", name.casefold())
    return normalized in _CHALLENGE_WORDS or any(
        part in normalized for part in _CHALLENGE_SUBSTRINGS
    )


def _string_parameters(value: Any) -> dict[str, str]:
    if not isinstance(value, dict):
        raise replay.UpstreamError("PER routing parameters are malformed")
    result: dict[str, str] = {}
    for key, item in value.items():
        if (
            not isinstance(key, str)
            or not key
            or not isinstance(item, str)
            or any(ord(char) < 0x20 or ord(char) == 0x7F for char in key)
            or _is_challenge_name(key)
        ):
            raise replay.UpstreamError("PER routing parameters are malformed")
        result[key] = item
    return result


def _json_object(body: bytes) -> dict[str, Any]:
    try:
        decoded = json.loads(
            body,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except (ValueError, TypeError, UnicodeError):
        raise replay.UpstreamError("PER upstream returned invalid JSON") from None
    if not isinstance(decoded, dict):
        raise replay.UpstreamError("PER upstream returned an invalid object")
    return decoded


async def _bounded_request(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    headers: dict[str, str] | None = None,
    json_body: dict[str, Any] | None = None,
    form: dict[str, str] | None = None,
) -> tuple[int, bytes, str | None, str | None]:
    try:
        async with client.stream(
            method,
            url,
            headers=headers,
            json=json_body,
            data=form,
        ) as response:
            location = response.headers.get("location")
            content_type = response.headers.get("content-type")
            if response.status_code in (401, 403):
                return response.status_code, b"", location, content_type
            declared_length = response.headers.get("content-length", "")
            if declared_length.isdecimal() and int(declared_length) > replay.MAX_RESPONSE_BYTES:
                raise replay.UpstreamError("PER upstream response is too large")
            chunks: list[bytes] = []
            size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > replay.MAX_RESPONSE_BYTES:
                    raise replay.UpstreamError("PER upstream response is too large")
                chunks.append(chunk)
            return response.status_code, b"".join(chunks), location, content_type
    except replay.ReplayError:
        raise
    except (httpx.HTTPError, UnicodeError, ValueError):
        # Transport exceptions may include request headers, URLs, or response data.
        raise replay.UpstreamError("PER upstream request failed") from None


def _routing_context(characteristics: dict[str, Any]) -> tuple[dict[str, str], dict[str, str]]:
    context = characteristics.get("routingContext")
    value = context.get("value") if isinstance(context, dict) else None
    if not isinstance(value, dict):
        raise replay.UpstreamError("PER routing context is malformed")
    return (
        _optional_parameters(value, "queryParameters"),
        _optional_parameters(value, "formParameters"),
    )


def _optional_parameters(value: dict[str, Any], key: str) -> dict[str, str]:
    # Live mode-0 routing omits `queryParameters`; absent or null means "none".
    if value.get(key) is None:
        return {}
    return _string_parameters(value[key])


def _authentication_pair(characteristics: dict[str, Any]) -> tuple[str, str] | None:
    name = characteristics.get("authenticationKeyName", "")
    if not isinstance(name, str):
        raise replay.UpstreamError("PER routing authentication parameters are malformed")
    if not name or name == "null":
        return None
    value = characteristics.get("authenticationKeyValue")
    if not isinstance(value, str):
        raise replay.UpstreamError("PER routing authentication parameters are malformed")
    if (
        any(char.isspace() or ord(char) < 0x20 or ord(char) == 0x7F for char in name)
        or _is_challenge_name(name)
    ):
        raise replay.UpstreamError("PER routing authentication parameters are malformed")
    return name, value


def _with_authentication(
    parameters: dict[str, str], authentication: tuple[str, str] | None
) -> dict[str, str]:
    result = dict(parameters)
    if authentication is not None:
        name, value = authentication
        if name in result:
            raise replay.UpstreamError("PER routing parameters conflict")
        result[name] = value
    return result


def _routing_details(root: dict[str, Any]) -> tuple[str, str, dict[str, str], dict[str, str], tuple[str, str] | None]:
    prepared = root.get("prepareRouting")
    if prepared is None:
        prepared = root
    if not isinstance(prepared, dict):
        raise replay.UpstreamError("PER routing response is malformed")
    characteristics = prepared.get("characteristics")
    if not isinstance(characteristics, dict):
        raise replay.UpstreamError("PER routing response is malformed")
    mode_info = characteristics.get("routingMode")
    routing_url = characteristics.get("routingUrl")
    mode = mode_info.get("code") if isinstance(mode_info, dict) else None
    href = routing_url.get("href") if isinstance(routing_url, dict) else None
    if mode not in ("0", "1") or not isinstance(href, str):
        raise replay.UpstreamError("PER routing classification is unsupported")
    query_parameters, form_parameters = _routing_context(characteristics)
    authentication = _authentication_pair(characteristics)
    return mode, href, query_parameters, form_parameters, authentication


def _existing_query(url: str) -> tuple[urllib.parse.SplitResult, list[tuple[str, str]]]:
    _guard_url(url, INSURER_HOST)
    parts = urllib.parse.urlsplit(url)
    try:
        existing = urllib.parse.parse_qsl(
            parts.query,
            keep_blank_values=True,
            strict_parsing=bool(parts.query),
            encoding="utf-8",
            errors="strict",
        )
    except (ValueError, UnicodeError):
        raise replay.UpstreamError("PER routing query is malformed") from None
    names = [name for name, _ in existing]
    if len(names) != len(set(names)):
        raise replay.UpstreamError("PER routing query parameters conflict")
    return parts, existing


def _ensure_url_parameters_unique(url: str, parameters: dict[str, str]) -> None:
    _, existing = _existing_query(url)
    names = {name for name, _ in existing}
    if any(name in names for name in parameters):
        raise replay.UpstreamError("PER routing query parameters conflict")


def _append_query(url: str, parameters: dict[str, str]) -> str:
    parts, existing = _existing_query(url)
    existing_names = {name for name, _ in existing}
    if any(name in existing_names for name in parameters):
        raise replay.UpstreamError("PER routing query parameters conflict")
    try:
        additions = urllib.parse.urlencode(list(parameters.items()), doseq=False)
    except (TypeError, UnicodeError, ValueError):
        raise replay.UpstreamError("PER routing query is malformed") from None
    query = parts.query
    if additions:
        query = f"{query}&{additions}" if query else additions
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, query, ""))


def _simple_submit_script(source: str) -> bool:
    return re.fullmatch(
        r"\s*document\s*\.\s*forms\s*\[\s*0\s*\]\s*\.\s*submit\s*\(\s*\)\s*;?\s*",
        source,
    ) is not None


class _HiddenPostFormParser(HTMLParser):
    """Accept only one hidden-only POST form and a literal auto-submit signal."""

    _allowed_tags = {"html", "head", "title", "body", "meta", "form", "input", "script"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.invalid = False
        self.form_count = 0
        self.action: str | None = None
        self.fields: dict[str, str] = {}
        self.auto_submit = False
        self.in_form = False
        self.in_script = False
        self.in_title = False
        self.script_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes: dict[str, str | None] = {}
        for key, value in attrs:
            lowered = key.lower()
            if lowered in attributes:
                self.invalid = True
            attributes[lowered] = value
        if tag not in self._allowed_tags:
            self.invalid = True
            return
        if any(key.startswith("on") for key in attributes if key != "onload"):
            self.invalid = True
        if tag == "body":
            onload = attributes.get("onload")
            if onload is not None:
                if _simple_submit_script(onload):
                    self.auto_submit = True
                else:
                    self.invalid = True
        elif "onload" in attributes:
            self.invalid = True
        if tag == "meta":
            if (attributes.get("http-equiv") or "").casefold() == "refresh":
                self.invalid = True
        elif tag == "form":
            self.form_count += 1
            if self.form_count != 1 or self.in_form:
                self.invalid = True
            self.in_form = True
            self.action = attributes.get("action")
            if (
                (attributes.get("method") or "").casefold() != "post"
                or not self.action
                or (attributes.get("enctype") or "application/x-www-form-urlencoded").casefold()
                != "application/x-www-form-urlencoded"
                or "target" in attributes
            ):
                self.invalid = True
        elif tag == "input":
            name = attributes.get("name")
            value = attributes.get("value")
            if (
                not self.in_form
                or (attributes.get("type") or "").casefold() != "hidden"
                or not isinstance(name, str)
                or not name
                or not isinstance(value, str)
                or _is_challenge_name(name)
                or name not in _SUPPORTED_SSO_RELAY_FIELDS
                or name in self.fields
            ):
                self.invalid = True
            elif isinstance(name, str) and isinstance(value, str):
                self.fields[name] = value
        elif tag == "script":
            if self.in_script or "src" in attributes:
                self.invalid = True
            self.in_script = True
            self.script_parts = []
        elif tag == "title":
            self.in_title = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "form":
            if not self.in_form:
                self.invalid = True
            self.in_form = False
        elif tag == "script":
            if not self.in_script or not _simple_submit_script("".join(self.script_parts)):
                self.invalid = True
            else:
                self.auto_submit = True
            self.in_script = False
        elif tag == "title":
            self.in_title = False
        elif tag not in self._allowed_tags:
            self.invalid = True

    def handle_data(self, data: str) -> None:
        if self.in_script:
            self.script_parts.append(data)
        elif data.strip() and not self.in_title:
            self.invalid = True

    def handle_comment(self, data: str) -> None:
        if data.strip():
            self.invalid = True

    def handle_decl(self, decl: str) -> None:
        if decl.casefold() != "doctype html":
            self.invalid = True


class _JsfRoutingPageParser(HTMLParser):
    """Read the live insurer JSF routing page without trusting its other markup.

    The page carries external scripts, RichFaces widgets and links, none of which
    are executed. Only its single form and inline scripts are inspected.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.invalid = False
        self.form_count = 0
        self.in_form = False
        self.form_id: str | None = None
        self.action: str | None = None
        self.hidden: dict[str, str] = {}
        self.submit: tuple[str, str] | None = None
        self.in_script = False
        self.script_parts: list[str] = []
        self.scripts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes: dict[str, str | None] = {}
        for key, value in attrs:
            lowered = key.lower()
            if lowered in attributes:
                self.invalid = True
            attributes[lowered] = value
        if tag == "meta" and (attributes.get("http-equiv") or "").casefold() == "refresh":
            self.invalid = True
        elif tag == "form":
            self.form_count += 1
            if self.in_form:
                self.invalid = True
            self.in_form = True
            self.form_id = attributes.get("id")
            self.action = attributes.get("action")
            if (
                (attributes.get("method") or "").casefold() != "post"
                or (attributes.get("enctype") or "application/x-www-form-urlencoded").casefold()
                != "application/x-www-form-urlencoded"
                or "target" in attributes
            ):
                self.invalid = True
        elif tag in ("input", "select", "textarea", "button") and self.in_form:
            self._form_control(tag, attributes)
        elif tag in ("input", "select", "textarea"):
            # A data-entry control anywhere else on the page means a challenge.
            if tag != "input" or (attributes.get("type") or "").casefold() != "hidden":
                self.invalid = True
        elif tag == "script":
            if self.in_script:
                self.invalid = True
            self.in_script = "src" not in attributes
            self.script_parts = []

    def _form_control(self, tag: str, attributes: dict[str, str | None]) -> None:
        name = attributes.get("name")
        value = attributes.get("value")
        kind = (attributes.get("type") or "").casefold()
        if (
            tag != "input"
            or not isinstance(name, str)
            or not isinstance(value, str)
            or _is_challenge_name(name)
            or name in self.hidden
        ):
            self.invalid = True
        elif kind == "hidden" and name in _JSF_STATE_FIELDS | {_JSF_ROUTING_FORM_ID}:
            self.hidden[name] = value
        elif kind == "submit" and name == _JSF_ROUTING_SUBMIT and self.submit is None:
            self.submit = (name, value)
        else:
            self.invalid = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "form":
            if not self.in_form:
                self.invalid = True
            self.in_form = False
        elif tag == "script":
            if self.in_script:
                self.scripts.append("".join(self.script_parts))
            self.in_script = False

    def handle_data(self, data: str) -> None:
        if self.in_script:
            self.script_parts.append(data)


class _LandingPageParser(HTMLParser):
    """The insurer single-page app shell: no form and no data-entry control."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.invalid = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = {key.lower(): value for key, value in attrs}
        if tag in ("form", "input", "select", "textarea"):
            self.invalid = True
        elif tag == "meta" and (attributes.get("http-equiv") or "").casefold() == "refresh":
            self.invalid = True


def _parse_html(parser: HTMLParser, source: str) -> None:
    try:
        parser.feed(source)
        parser.close()
    except (AssertionError, ValueError):
        raise replay.UpstreamError("PER insurer returned unsupported form markup") from None


def _is_jsf_routing_path(path: str) -> bool:
    return path == _JSF_ROUTING_PATH or path.startswith(f"{_JSF_ROUTING_PATH};")


def _jsf_routing_form(url: str, source: str) -> tuple[str, dict[str, str]] | None:
    if not _is_jsf_routing_path(urllib.parse.urlsplit(url).path):
        return None
    parser = _JsfRoutingPageParser()
    _parse_html(parser, source)
    if (
        parser.invalid
        or parser.form_count != 1
        or parser.in_form
        or parser.in_script
        or parser.form_id != _JSF_ROUTING_FORM_ID
        or not parser.action
        or set(parser.hidden) != _JSF_STATE_FIELDS | {_JSF_ROUTING_FORM_ID}
        or parser.submit is None
        or not any(_JSF_CLICK_RE.search(script) for script in parser.scripts)
    ):
        raise replay.UpstreamError("PER insurer returned an unsupported JSF routing page")
    target = urllib.parse.urljoin(url, parser.action)
    _guard_url(target, INSURER_HOST)
    if not _is_jsf_routing_path(urllib.parse.urlsplit(target).path):
        raise replay.UpstreamError("PER insurer returned an unsupported JSF routing page")
    fields = dict(parser.hidden)
    name, value = parser.submit
    fields[name] = value
    return target, fields


def _is_landing_page(url: str, source: str) -> bool:
    if urllib.parse.urlsplit(url).path != _INSURER_LANDING_PATH:
        return False
    parser = _LandingPageParser()
    _parse_html(parser, source)
    if parser.invalid:
        raise replay.UpstreamError("PER insurer landing page contains a form")
    return True


def _insurer_page_step(
    url: str, body: bytes, content_type: str | None
) -> tuple[str, dict[str, str]] | None:
    """Return the next POST for an insurer HTML page, or None when navigation ends."""
    text_start = body.lstrip()[:32].lower()
    looks_html = bool(content_type and content_type.split(";", 1)[0].strip().casefold() == "text/html")
    looks_html = looks_html or text_start.startswith((b"<!doctype html", b"<html", b"<form", b"<body"))
    if not looks_html:
        return None
    try:
        source = body.decode("utf-8")
    except UnicodeError:
        raise replay.UpstreamError("PER insurer returned unsupported form markup") from None
    jsf_form = _jsf_routing_form(url, source)
    if jsf_form is not None:
        return jsf_form
    if _is_landing_page(url, source):
        return None
    action, fields = _hidden_post_form(source)
    target = urllib.parse.urljoin(url, action)
    _guard_url(target, INSURER_HOST)
    return target, fields


def _hidden_post_form(source: str) -> tuple[str, dict[str, str]]:
    parser = _HiddenPostFormParser()
    _parse_html(parser, source)
    if (
        parser.invalid
        or parser.form_count != 1
        or parser.in_form
        or parser.in_script
        or not parser.auto_submit
        or not parser.action
    ):
        raise replay.UpstreamError("PER insurer returned unsupported form markup")
    return parser.action, parser.fields


class _HopBudget:
    def __init__(self) -> None:
        self.used = 0

    def consume(self) -> None:
        if self.used >= MAX_SSO_HOPS:
            raise replay.UpstreamError("PER insurer navigation exceeded its hop limit")
        self.used += 1


async def _navigate_insurer(
    client: httpx.AsyncClient,
    url: str,
    *,
    method: str,
    form: dict[str, str] | None,
    budget: _HopBudget,
) -> bytes:
    _guard_url(url, INSURER_HOST)
    while True:
        _guard_url(url, INSURER_HOST)
        status, body, location, content_type = await _bounded_request(
            client,
            method,
            url,
            form=form,
        )
        if status == 401:
            raise replay.SessionExpired("PER session is no longer accepted")
        if status == 403:
            raise replay.UpstreamError("PER upstream denied access")
        if status in (301, 302, 303, 307, 308):
            if not location:
                raise replay.UpstreamError("PER insurer redirect has no location")
            # The live landing redirect carries a client-side route fragment
            # (`/etna-ihs-ce/#...`); a fragment is never sent over HTTP.
            target = urllib.parse.urldefrag(urllib.parse.urljoin(url, location))[0]
            _guard_url(target, INSURER_HOST)
            if status == 303 or (status in (301, 302) and method == "POST"):
                method, form = "GET", None
            elif status in (301, 302, 307, 308):
                pass
            else:
                raise replay.UpstreamError("PER insurer redirect is unsupported")
            budget.consume()
            url = target
            continue
        if not 200 <= status < 300:
            raise replay.UpstreamError(
                _INSURER_STATUS_MESSAGES.get(status, "PER insurer request failed")
            )
        next_step = _insurer_page_step(url, body, content_type)
        if next_step is not None:
            target, form = next_step
            budget.consume()
            url, method = target, "POST"
            continue
        return body


def _detail_url(suffix: str) -> str:
    encoded = urllib.parse.quote(suffix, safe="")
    return f"{INSURER_BASE}/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/{encoded}"


async def fetch_per_detail(
    access_token: str,
    session_state: str,
    contract_id: str,
    *,
    entity_code: str | None = None,
    transport: httpx.AsyncBaseTransport | None = None,
) -> dict[str, Any]:
    """Return the raw insurer JSON object for one validated PER contract.

    The bearer token is attached only to the exact routing API POST. Every
    insurer request uses the same short-lived client for cookie continuity and
    carries no Authorization header.
    """
    _validate_token(access_token)
    state = _parse_state(session_state)
    entity_code = _entity_code(state, entity_code)
    suffix = _contract_suffix(contract_id)
    route_segment = urllib.parse.quote(f"{entity_code}-{contract_id}", safe="")
    route_url = (
        f"{ROUTING_API_BASE}/contracts/{route_segment}/prepareRouting"
    )
    _guard_url(route_url, ROUTING_API_HOST)

    request_body = {
        "characteristics": {
            "contractActionType": {"code": "", "label": ""},
            "productFamilyPFM": {"code": "", "label": ""},
            "returnUrl": {},
            "routingContext": {
                "suppliedName": "routingContext",
                "value": {"queryParameters": {}, "formParameters": {}},
            },
        },
        "response": {"code": "", "interactionId": "", "label": ""},
    }
    client = replay.new_client(transport)
    try:
        try:
            replay.restore_cookies(client, state.cookies)
        except (httpx.HTTPError, TypeError, UnicodeError, ValueError):
            raise replay.InvalidSessionState("Session state has unusable cookies") from None
        status, body, _, _ = await _bounded_request(
            client,
            "POST",
            route_url,
            headers={"Authorization": f"Bearer {access_token}"},
            json_body=request_body,
        )
        if status == 401:
            raise replay.SessionExpired("PER session is no longer accepted")
        if status == 403:
            raise replay.UpstreamError("PER upstream denied access")
        if status not in _ROUTING_SUCCESS_STATUSES:
            raise replay.UpstreamError("PER routing request failed")
        root = _json_object(body)
        mode, href, query_parameters, form_parameters, authentication = _routing_details(root)
        _guard_url(href, INSURER_HOST)
        budget = _HopBudget()
        if mode == "0":
            target_form = _with_authentication(form_parameters, authentication)
            _ensure_url_parameters_unique(href, target_form)
            await _navigate_insurer(
                client,
                href,
                method="POST",
                form=target_form,
                budget=budget,
            )
        else:
            target_query = _with_authentication(query_parameters, authentication)
            target_url = _append_query(href, target_query)
            await _navigate_insurer(
                client,
                target_url,
                method="GET",
                form=None,
                budget=budget,
            )
        raw_body = await _navigate_insurer(
            client,
            _detail_url(suffix),
            method="GET",
            form=None,
            budget=budget,
        )
        result = _json_object(raw_body)
        if "detailContratVie" not in result or not isinstance(result["detailContratVie"], dict):
            raise replay.UpstreamError("PER insurer detail response is incomplete")
        try:
            serialized = json.dumps(result, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError):
            raise replay.UpstreamError("PER insurer detail response is malformed") from None
        if access_token in serialized:
            raise replay.UpstreamError("PER insurer detail response contained forbidden data")
        return result
    finally:
        await client.aclose()