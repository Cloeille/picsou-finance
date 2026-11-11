"""BNP Paribas / Hello bank! web client, driven in Camoufox (Firefox).

The sidecar drives the bank's own login page like a person would: it types the customer number,
reads the ten digits written on the shuffled keypad buttons, hands those ten labels to the
member, and CLICKS the buttons the member picked (by DOM position). The page's own JavaScript
fills the hidden code field, the Akamai sensor and the ThreatMetrix id; this process never
builds or posts the login request itself. The password digits never reach this process: only
button positions do. No password or one-time code is stored; an SMS code is typed into the page
and dropped.

Verification status (docs/features/bnp-paribas.md, "Verification boundary"):
  * VERIFIED on the public pages with a real browser, nothing typed: the login hosts
    (PingFederate on `connexion.<brand domain>`), `form#identForm`, `pf.username`,
    `pf.pass.positions`, the 5x2 keypad of 10 text buttons, the one-POST submit, the
    `error_code` query parameter, the `"-10"` string answer of InfosClient when not signed in.
  * VERIFIED in one owner-assisted run (served by mabanqueprivee.bnpparibas, no second factor asked): the
    redirect chain after the submit, the session probe, the account, RIB and operation endpoints,
    a read in a new browser from the restored cookies.
  * UNVERIFIED: the second factor (Cle Digitale approval vs SMS code) and the page and host it
    happens on, the securities endpoints, Hello bank!, `codeRetour` -30.
Whenever the page does not look as expected the sidecar stops and says so (KEYPAD_CHANGED,
MFA_TYPE_UNSUPPORTED, UPSTREAM_FORMAT_CHANGED); it never guesses, and any request to a host outside
the brand's allow-list stops the login and logs the host name (host only), except the other hosts of
BNP Paribas' own dotBrand TLD (`*.bnpparibas`, see `is_brand_tld_host`), which load, the sub-resources
the bank's own page loads from its known fraud vendor (ThreatMetrix, `*.online-metrix.net`), which load,
the calls of its cookie-consent vendor (OneTrust), which are dropped silently, and passive third-party
sub-resources (analytics), which are dropped silently too (see `VENDORS` and `verdict`). Every stop logs
a fixed stage name (`stage=...`), never page content.
"""

import asyncio
import base64
import hashlib
import json
import logging
import re
import time
import uuid
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any
from urllib.parse import parse_qs, parse_qsl, urlsplit

from fastapi import HTTPException
from picsou_sidecar import PendingStore

import parsers

log = logging.getLogger("bnp-auth")


@dataclass(frozen=True)
class Brand:
    name: str
    site: str  # the bank's site host: its post-login API and the OIDC return URL live here
    login: str  # the PingFederate login host
    entry: str  # where the member would click "Se connecter"
    hosts: frozenset[str]
    cookie_domains: frozenset[str]

    @property
    def base(self) -> str:
        return f"https://{self.site}"


def _brand(name: str, site: str, login: str, entry: str, parent: str) -> Brand:
    hosts = frozenset({site, login, f"content.{login}"})
    return Brand(name, site, login, entry, hosts, hosts | {parent})


BRANDS = {
    "bnp": _brand("bnp", "mabanque.bnpparibas", "connexion.mabanque.bnpparibas",
                  "https://mabanque.bnpparibas/auth/login", "mabanque.bnpparibas"),
    "hellobank": _brand("hellobank", "www.hellobank.fr", "connexion.hellobank.fr",
                        "https://www.hellobank.fr/auth/login?requiredDAC=3", "hellobank.fr"),
}
# Host policy (docs/features/bnp-paribas.md, "Host policy"). Exact hosts, https, default port, no
# credentials: the brand's own three hosts. They are the only destination of a main-frame navigation
# (the login form post included) and of any request that carries a body, unless the request is a
# sub-resource of a known vendor below. Known vendors are matched by a dot-bounded suffix and only
# for the sub-resources the bank's own page requests from them. Every other third-party sub-resource
# without a body is aborted silently; anything else off-list stops the attempt.
ALL_HOSTS = frozenset().union(*(brand.hosts for brand in BRANDS.values()))
ALLOW, IGNORE, STOP = "allow", "ignore", "stop"
# Known third parties of the bank's own pages and what their sub-resources get. A vendor never
# receives the main-frame navigation, whatever its entry says. A key that starts with a dot is a
# dot-bounded suffix; any other key is one exact host.
VENDORS = {
    # ThreatMetrix (LexisNexis), `<n>.online-metrix.net` and `<id>.e.aa.online-metrix.net` (random
    # labels, hence a suffix): the login page fills `pf.idTMX` from it, so it loads.
    ".online-metrix.net": ALLOW,
    # OneTrust cookie consent, on every landing with a new profile: geolocation, a session POST,
    # consent receipts. The login does not need it: dropped, with or without a body, never a stop.
    # Exact hosts only: `my.onetrust.com` hosts every OneTrust customer, so the bank's own tenant
    # is named and any other one keeps the fail-closed rule.
    "geolocation.onetrust.com": IGNORE,
    "cookies-data.onetrust.io": IGNORE,
    "bnp-privacy.my.onetrust.com": IGNORE,
}
BRAND_TLD = "bnpparibas"  # a dotBrand gTLD: only BNP Paribas can register a name under it (IANA root zone db)
_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
SAFE_METHODS = frozenset({"GET", "HEAD", "OPTIONS"})
MAX_LOGGED_HOSTS = 10

PENDING_TTL_SECONDS = 300
MAX_PENDING = 4
STEP_SECONDS = 20
SETTLE_SECONDS = 30
SETTLE_POLL_SECONDS = 0.5
STRUCTURE_RETRY_SECONDS = 1.0
STRUCTURE_TIMEOUT_SECONDS = 5
STABLE_SECONDS = 3.0
APP_APPROVAL_SECONDS = 180
SMS_SETTLE_SECONDS = 45
MAX_REDIRECTS = 20
MAX_SESSION_STATE = 150_000
MAX_COOKIES = 200
SNAPSHOT_CACHE_SECONDS = 120
FIRST_SNAPSHOT_SECONDS = 45
# All of /complete: the second-factor wait, then the first snapshot. The backend stops waiting at 200 s
# (BnpAdapter.COMPLETE_TIMEOUT); the 10 s between the two are for reading the cookies and closing the browser.
COMPLETE_SECONDS = 190
MAX_LOGGED_COOKIES = 30
MIN_LOGIN_GAP = 2.0
POLL_SECONDS = 1.5
MAX_UNKNOWN_HOSTS = 5

LOGIN_PATH = "/auth/login"
AUTHORIZE_PATH = "/as/authorization.oauth2"
INFOS_CLIENT = "/serviceinfosclient-wspl/rpc/InfosClient?modeAppel=0"
# From woob modules/bnp. Seen live: accounts, ibans, history. UNVERIFIED: synthesis, portfolio.
ENDPOINTS = {
    "accounts": ("GET", "/udc-wspl/rest/getlstcpt"),
    "ibans": ("GET", "/rib-wspl/rpc/comptes"),
    "history": ("POST", "/rop2-wspl/rest/releveOp"),
    "synthesis": ("POST", "/pe-war/rpc/synthesis/get"),
    "portfolio": ("POST", "/pe-war/rpc/portfolioDetails/get"),
}

# Verified on the public login page.
FORM = "form#identForm"
USER_INPUT = 'input[name="pf.username"]'
GRID_INPUT = 'input[name="pf.pass.positions"]'
STEP_PASSWORD = "fieldset#stepPassword"
KEY_BUTTONS = "button.cas__btn--key"
SUBMIT_BUTTON = "#validLastStep"
CONTINUE_BUTTON_NAME = re.compile(r"^\s*Continuer\s*$", re.I)
KEY_COUNT = 10
COLUMNS = 5
POSITIONS = 6
# UNVERIFIED: how the second factor looks.
CODE_INPUT = (
    'input[autocomplete="one-time-code"], input[inputmode="numeric"], input[type="tel"], '
    'input[name*="otp" i], input[name*="code" i]'
)

_INSPECT_JS = """([form, user, grid, step, keys, submit]) => {
  const f = document.querySelector(form);
  const pad = document.querySelector(step);
  const buttons = pad ? [...pad.querySelectorAll(keys)] : [];
  const container = buttons.length ? buttons[0].parentElement : null;
  const columns = container ? getComputedStyle(container).gridTemplateColumns.split(' ').filter(Boolean).length : 0;
  return {
    form: !!f,
    action: f ? f.action : null,
    method: f ? (f.method || '').toLowerCase() : null,
    user: !!document.querySelector(user),
    grid: !!document.querySelector(grid),
    submit: !!document.querySelector(submit),
    pad: !!pad,
    keys: buttons.map(b => (b.textContent || '').trim()),
    columns,
  };
}"""

_PRESSED_JS = """([grid, submit]) => {
  const input = document.querySelector(grid);
  const button = document.querySelector(submit);
  return {value: input ? input.value : null, enabled: !!button && !button.disabled};
}"""

# Structure only: counts, ids, field names and types. The title leaves the page as a length, no value
# (input value, text, attribute value, URL) is read.
_STRUCTURE_JS = """() => {
  const forms = [...document.forms].slice(0, 6).map(f => ({
    id: f.id || '',
    fields: [...f.elements].slice(0, 24).map(e => ({n: e.name || e.id || '', t: e.type || e.tagName.toLowerCase()})),
  }));
  const types = {};
  for (const input of document.querySelectorAll('input')) { const t = input.type || 'text'; types[t] = (types[t] || 0) + 1; }
  const landmarks = [...document.querySelectorAll('main,[role=main],[role=dialog],dialog,h1,h2,h3,form,fieldset,section,button,iframe')]
    .filter(e => e.id).slice(0, 16).map(e => e.tagName.toLowerCase() + '#' + e.id);
  return {title: (document.title || '').length, ready: document.readyState, frames: window.frames.length, forms, types, landmarks};
}"""

_FETCH_JS = """async ([method, path, body]) => {
  const init = {method, credentials: 'same-origin', redirect: 'error', headers: {'Accept': 'application/json'}};
  if (body !== null) { init.body = JSON.stringify(body); init.headers['Content-Type'] = 'application/json'; }
  const response = await fetch(path, init);
  return {status: response.status, text: await response.text()};
}"""


def fail(status: int, detail: str, stage: str | None = None) -> HTTPException:
    """`stage` is a fixed step name (never page content): it is logged so a failure is diagnosable."""
    exc = HTTPException(status_code=status, detail=detail)
    if stage is not None:
        log.warning("BNP attempt stopped: %s (stage=%s)", detail, stage)
        exc.stage = stage  # type: ignore[attr-defined]
    return exc


# ─── allow-list ──────────────────────────────────────────────────────────────


def host_of(url: Any) -> str | None:
    try:
        return urlsplit(url).hostname if isinstance(url, str) else None
    except ValueError:
        return None


def is_brand_tld_host(host: str) -> bool:
    """A name under `.bnpparibas`, at a dot boundary (at least one valid label before the TLD)."""
    host = host.lower()
    if not host.endswith("." + BRAND_TLD):
        return False
    labels = host[: -len(BRAND_TLD) - 1].split(".")
    return all(_LABEL.fullmatch(label) for label in labels)


def is_allowed_url(url: Any, hosts: frozenset[str] = ALL_HOSTS) -> bool:
    """https only, an exact brand host or any host of the `.bnpparibas` dotBrand TLD, the default
    port, no credentials in the URL."""
    if not isinstance(url, str):
        return False
    if url.startswith("data:") or url == "about:blank":
        return True
    try:
        parts = urlsplit(url)
        return (
            parts.scheme == "https"
            and ((parts.hostname or "") in hosts or is_brand_tld_host(parts.hostname or ""))
            and parts.username is None
            and parts.password is None
            and parts.port in (None, 443)
        )
    except ValueError:
        return False


def vendor_of(url: Any) -> str | None:
    """The `VENDORS` key a URL falls under (https, default port, no credentials): one exact host, or a
    strict sub-domain of a suffix key (`evil-online-metrix.net` and the bare apex do not match)."""
    if not isinstance(url, str):
        return None
    try:
        parts = urlsplit(url)
        if parts.scheme != "https" or parts.username is not None or parts.password is not None or parts.port not in (None, 443):
            return None
        host = parts.hostname or ""
    except ValueError:
        return None
    return next((key for key in VENDORS if (host.endswith(key) if key.startswith(".") else host == key)), None)


def _is_main_frame_navigation(request: Any) -> bool:
    """Unknown means yes: an unreadable request is judged by the strictest rule."""
    try:
        return bool(request.is_navigation_request()) and request.frame.parent_frame is None
    except Exception:
        return True


def _is_navigation(request: Any) -> bool:
    try:
        return bool(request.is_navigation_request())
    except Exception:
        return True


def _carries_data(request: Any) -> bool:
    try:
        return str(request.method).upper() not in SAFE_METHODS or request.post_data is not None
    except Exception:
        return True


def verdict(request: Any, hosts: frozenset[str]) -> str:
    """ALLOW (load it), IGNORE (abort silently) or STOP (abort and end the attempt) for one request."""
    url = request.url
    if is_allowed_url(url, hosts):
        return ALLOW
    # The login form post and every redirect of a page load are main-frame navigations: exact hosts only.
    if _is_main_frame_navigation(request):
        return STOP
    # What the bank's own page requests from a known vendor (scripts, pixels, beacons, an iframe).
    vendor = vendor_of(url)
    if vendor is not None:
        return VENDORS[vendor]
    # A foreign frame or anything that sends a body or a write verb could carry the identifier or the code.
    if _is_navigation(request) or _carries_data(request):
        return STOP
    # A passive third-party sub-resource (analytics script, pixel, font): dropped without a trace on the login.
    return IGNORE


class Guard:
    """Request filter for one browser context (`verdict` holds the policy).

    - ALLOW goes through;
    - IGNORE is aborted and counted (`blocked`), its host name is logged once;
    - STOP is aborted AND taints the attempt (`tainted`), its host name is remembered and logged, so
      an unexpected redirect (the second-factor page is on an unobserved host) is reported instead
      of silently followed.
    Playwright's Firefox routing may not report every redirect hop to a route handler, so every
    finished request's redirect chain is checked again (`observe`); one STOP hop taints the attempt
    and the caller drops the whole browser.
    """

    def __init__(self, hosts: frozenset[str]) -> None:
        self.hosts = hosts
        self.tainted = False
        self.blocked = 0
        self.unknown_hosts: list[str] = []
        self.ignored_hosts: list[str] = []
        self.vendors_seen: list[str] = []
        self.brand_hosts_seen: list[str] = []

    def _note(self, request: Any) -> None:
        """Host names only, once each (a vendor once per domain: its sub-domains are random)."""
        vendor = vendor_of(request.url)
        if is_allowed_url(request.url, self.hosts):
            host = host_of(request.url) or ""
            if host not in self.hosts and host not in self.brand_hosts_seen and len(self.brand_hosts_seen) < MAX_LOGGED_HOSTS:
                self.brand_hosts_seen.append(host)
                log.info("BNP page uses another host of the bank's own domain: %s", host[:100])
            return
        if VENDORS.get(vendor) == ALLOW:
            if vendor not in self.vendors_seen and len(self.vendors_seen) < MAX_LOGGED_HOSTS:
                self.vendors_seen.append(vendor)
                log.info("BNP page loads sub-resources from the vendor %s", vendor.lstrip("."))
            return
        host = (host_of(request.url) or "unparsable")[:100]
        if host not in self.ignored_hosts and len(self.ignored_hosts) < MAX_LOGGED_HOSTS:
            self.ignored_hosts.append(host)
            log.info("BNP page sub-resource from a third-party host was dropped: %s", host)

    def _unknown(self, url: Any) -> None:
        self.tainted = True
        host = host_of(url) or "unparsable"
        if host not in self.unknown_hosts and len(self.unknown_hosts) < MAX_UNKNOWN_HOSTS:
            self.unknown_hosts.append(host)
            # Host name only: never the URL, its query or any page content.
            log.warning("BNP browser requested a host outside the allow-list: %s", host[:100])

    async def route(self, route: Any) -> None:
        request = route.request
        decision = verdict(request, self.hosts)
        if decision == ALLOW:
            self._note(request)
            await route.continue_()
            return
        self.blocked += 1
        if decision == STOP:
            self._unknown(request.url)
        else:
            self._note(request)
        await route.abort()

    def observe(self, request: Any) -> None:
        hop = request
        hops = 0
        while hop is not None:
            hops += 1
            if hops > MAX_REDIRECTS:
                self.tainted = True
                return
            if verdict(hop, self.hosts) == STOP:
                self._unknown(hop.url)
                return
            hop = hop.redirected_from


# ─── browser plumbing ────────────────────────────────────────────────────────


async def _close(state: dict[str, Any]) -> None:
    for key in ("context", "browser"):
        resource = state.get(key)
        if resource is not None:
            try:
                await asyncio.wait_for(resource.close(), timeout=10)
            except Exception:
                log.warning("BNP browser resource cleanup failed")
    camoufox = state.get("camoufox")
    if camoufox is not None:
        try:
            await asyncio.wait_for(camoufox.__aexit__(None, None, None), timeout=10)
        except Exception:
            log.warning("BNP browser shutdown failed")


async def launch(brand: Brand) -> dict[str, Any]:
    """A guarded Camoufox page on the brand's hosts. Imported late: tests never need the browser."""
    from camoufox.async_api import AsyncCamoufox

    state: dict[str, Any] = {"brand": brand, "created_at": time.time()}
    try:
        # Headful under the container's Xvfb: the edge (Akamai, ThreatMetrix) profiles the browser.
        # WebRTC stays off so the ThreatMetrix TURN beacon (aa.online-metrix.net) cannot go around
        # the host policy. UNVERIFIED: whether the bank accepts a login without that beacon.
        camoufox = AsyncCamoufox(headless=False, humanize=True, os="linux", locale="fr-FR", block_webrtc=True)
        state["camoufox"] = camoufox
        state["browser"] = await camoufox.__aenter__()
        context = await state["browser"].new_context(timezone_id="Europe/Paris", service_workers="block")
        state["context"] = context
        guard = Guard(brand.hosts)
        state["guard"] = guard
        await context.route("**/*", guard.route)
        await context.route_web_socket("**/*", lambda ws: asyncio.ensure_future(ws.close()))
        context.on("requestfinished", guard.observe)
        context.on("requestfailed", guard.observe)
        state["page"] = await context.new_page()
        return state
    except BaseException:
        await _close(state)
        raise


def require_clean(state: dict[str, Any], stage: str) -> None:
    """The attempt stops as soon as the browser asked for a host outside the allow-list."""
    if state["guard"].tainted:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED", stage=stage)


def on_site(url: str, brand: Brand) -> bool:
    """On the bank's site, past the login: its own host or any other host of its domain (the private
    banking space is on another `.bnpparibas` host), never a PingFederate login host."""
    parts = urlsplit(url)
    host = parts.hostname or ""
    if host != brand.site and not (is_allowed_url(url, brand.hosts) and is_brand_tld_host(host)):
        return False
    return not parts.path.startswith(LOGIN_PATH) and not _is_login_host(host)


def _is_login_host(host: str) -> bool:
    return host.startswith("connexion.") or host.startswith("content.connexion.")


def on_login(url: str, brand: Brand) -> bool:
    parts = urlsplit(url)
    return (parts.hostname or "") == brand.login and parts.path == AUTHORIZE_PATH


def landing_host(url: Any, brand: Brand) -> str | None:
    """The host of the bank's site the signed-in browser is on (host only: never a path or a query), None
    when the page is not on the site. The private banking space is on another `.bnpparibas` host than
    `brand.site`, and host-only cookies only travel to the host that set them."""
    if not is_allowed_url(url, brand.hosts):
        return None
    try:
        host = (urlsplit(url).hostname or "").lower()
        return host if on_site(url, brand) and valid_landing(host, brand) else None
    except ValueError:
        return None


def valid_landing(host: Any, brand: Brand) -> bool:
    if not isinstance(host, str) or not host or host != host.lower():
        return False
    url = f"https://{host}/"
    return host_of(url) == host and is_allowed_url(url, brand.hosts) and on_site(url, brand)


# Verified live: the login page reads `error_code` from its own URL and shows the catalogue message
# `app.identification.erreur.<id>`. Meanings below come from the ids' public catalogue titles; the
# counter behaviour of a lock is UNVERIFIED.
ERROR_IDS = {
    **dict.fromkeys(("3", "4", "202"), (429, "RATE_LIMITED")),
    **dict.fromkeys(("6", "201", "203", "210", "211", "212"), (401, "INVALID_CREDENTIALS")),
    # A customer number of the other brand (BNP number on Hello bank! and the reverse).
    **dict.fromkeys(("21501", "21521", "21531", "21510", "21512", "21513"), (401, "INVALID_CREDENTIALS")),
    **dict.fromkeys(("7", "50", "213"), (409, "ACTION_REQUIRED")),
    "1002": (410, "AUTH_ATTEMPT_EXPIRED"),
    **dict.fromkeys(("207", "300", "1001", "1003"), (502, "UPSTREAM_UNAVAILABLE")),
}
ERROR_NAMES = (
    (("Delay", "Locked"), (429, "RATE_LIMITED")),
    (("ErrorSecret", "SecretError", "ClientNotFound", "FailedLogin", "InvalidGrid", "SdoInvalidPwd",
      "ZosConnectGetIKPI", "CasInvalidCredential"), (401, "INVALID_CREDENTIALS")),
    (("ExpiredTmpPwd", "ExpiredClientPwd", "ChangePwd", "MailPwdReset", "AccountClosed"), (409, "ACTION_REQUIRED")),
    (("TechnicalException", "CasTechnical"), (502, "UPSTREAM_UNAVAILABLE")),
)


ERROR_PARAMS = ("error_code", "errorCode")  # the login page / the site's redirect (woob reads `errorCode`)
SECOND_FACTOR_PATH = re.compile(r"authentification-forte|authentforte", re.I)
PASSWORD_PATH = re.compile(r"mot-de-passe-expire|changer-son-mot-de-passe|mdp-expire|/100-connexion", re.I)


def classify_url(url: str, brand: Brand) -> str:
    """What the page the browser is on stands for after the login submit, from its URL alone:
    error | second_factor | password | login | callback | site | other.
    Paths from the open-source woob module (modules/bnp/pp/browser.py: `otp`, `con_threshold`)."""
    if not is_allowed_url(url, brand.hosts):
        return "other"
    parts = urlsplit(url)
    query = parse_qs(parts.query)
    if any(query.get(name) for name in ERROR_PARAMS):
        return "error"
    if SECOND_FACTOR_PATH.search(parts.path):
        return "second_factor"
    if PASSWORD_PATH.search(parts.path):
        return "password"
    host = parts.hostname or ""
    if _is_login_host(host) or parts.path.startswith(LOGIN_PATH):
        return "login"
    if ("code" in query and "state" in query) or "callback" in parts.path.lower():
        return "callback"
    return "site"


def login_failure(url: str) -> HTTPException | None:
    """The failure a login error redirect (`error_code=<id>`) stands for, None when the URL is not one."""
    query = parse_qs(urlsplit(url).query)
    values = next((query[name] for name in ERROR_PARAMS if query.get(name)), [])
    if not values:
        return None
    value = values[0].strip()
    if re.fullmatch(r"-?\d{1,6}", value):
        status, code = ERROR_IDS.get(value, (502, "UPSTREAM_FORMAT_CHANGED"))
    else:
        status, code = next(
            (answer for needles, answer in ERROR_NAMES if any(needle in value for needle in needles)),
            (502, "UPSTREAM_FORMAT_CHANGED"),
        )
    return fail(status, code, stage="login_error")


# ─── structure-only diagnostics ──────────────────────────────────────────────
# One line per stop after the login submit. Only shapes: the host and the path with every digit run
# replaced, the query KEYS (never a value, never the fragment), the title as a length, the ids and
# names of forms and fields, a few landmark ids. Anything that looks like an opaque token is masked.


def _word_like(text: str, *, max_uppercase: int = 1, max_digit_runs: int = 1, max_length: int = 40) -> bool:
    return (
        len(text) <= max_length
        and bool(re.fullmatch(r"[A-Za-z][A-Za-z0-9_.:\-#]*", text) or re.fullmatch(r"[0-9]+", text))
        and sum(c.isupper() for c in text) <= max_uppercase
        and len(re.findall(r"[0-9]+", text)) <= max_digit_runs
    )


def _mask_digits(text: str) -> str:
    return re.sub(r"[0-9]+", "<N>", text)


def describe_url(url: Any) -> str:
    """`host=<h> path=<p> query=[keys]` with digit runs masked and opaque segments and keys masked."""
    try:
        parts = urlsplit(url) if isinstance(url, str) else None
        host = parts.hostname if parts else None
        port = parts.port if parts else None
    except ValueError:
        parts, host, port = None, None, None
    if parts is None or not host:
        return "host=<none> path=<none> query=[]"
    segments = [(_mask_digits(seg) if not seg or _word_like(seg) else "<ID>") for seg in parts.path.split("/")]
    keys = [
        _mask_digits(key) if _word_like(key, max_uppercase=4, max_digit_runs=2) else "<K>"
        for key, _ in parse_qsl(parts.query, keep_blank_values=True)[:15]
    ]
    shown_host = _mask_digits(host.lower())[:100] + ("" if port in (None, 443) else ":<port>")
    return f"host={shown_host} path={'/'.join(segments) or '/'} query=[{','.join(keys)}]"


def _name(text: Any) -> str:
    text = text if isinstance(text, str) else ""
    if not text:
        return "-"
    # Names and ids are code identifiers; a long token or one with many digit runs is data.
    if len(text) > 40 or (len(text) >= 16 and re.search(r"[0-9]", text)) or len(re.findall(r"[0-9]+", text)) > 2:
        return "<X>"
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.:\-#]*", text):
        return "<X>"
    return _mask_digits(text)


def describe_structure(raw: Any) -> str:
    """The page-shape part of the diagnostic line, from what `_STRUCTURE_JS` returned. Never raises."""
    if not isinstance(raw, dict):
        return "unreadable=1"
    title = raw.get("title")
    title_len = title if isinstance(title, int) and not isinstance(title, bool) else len(title) if isinstance(title, str) else -1
    forms = []
    for entry in (raw.get("forms") if isinstance(raw.get("forms"), list) else [])[:6]:
        if not isinstance(entry, dict):
            continue
        fields = entry.get("fields") if isinstance(entry.get("fields"), list) else []
        shown = ",".join(
            f"{_name(f.get('n'))}:{_name(f.get('t'))}" for f in fields[:24] if isinstance(f, dict)
        )
        forms.append(f"{_name(entry.get('id'))}({shown})")
    types = raw.get("types") if isinstance(raw.get("types"), dict) else {}
    kinds = ",".join(f"{_name(k)}={v}" for k, v in list(types.items())[:12] if isinstance(v, int))
    landmarks = [
        _mask_digits(mark) for mark in (raw.get("landmarks") if isinstance(raw.get("landmarks"), list) else [])[:16]
        if isinstance(mark, str) and _word_like(mark, max_uppercase=3, max_digit_runs=2)
    ]
    ready = raw.get("ready") if raw.get("ready") in ("loading", "interactive", "complete") else "?"
    frames = raw.get("frames") if isinstance(raw.get("frames"), int) else -1
    return (
        f"title_len={title_len} ready={ready} frames={frames} forms=[{';'.join(forms)}] "
        f"inputs=[{kinds}] landmarks=[{','.join(landmarks)}]"
    )


async def log_page_structure(page: Any, brand: Brand, stage: str) -> None:
    """ONE structure-only line for a stop after the login submit. A page that cannot be read says so."""
    raw: Any = None
    for attempt in range(2):
        try:
            raw = await asyncio.wait_for(page.evaluate(_STRUCTURE_JS), timeout=STRUCTURE_TIMEOUT_SECONDS)
            break
        except Exception:
            if attempt == 0:
                await asyncio.sleep(STRUCTURE_RETRY_SECONDS)  # often a navigation still going on
    try:
        url = page.url
        where = f"{shallow_url(url)} kind={classify_url(url, brand)}"
    except Exception:
        where = "host=<none> path=<none> query=[]"
    if raw is None:
        log.warning("BNP page structure unavailable (stage=%s) %s", stage, where)
    else:
        log.warning("BNP page structure (stage=%s) %s %s", stage, where, describe_structure(raw))


def shallow_url(url: Any) -> str:
    """`describe_url` with a path cut to its first two segments (`/a/b/...`): a deeper segment can hold a
    member's name or an id, and the host plus the first two segments tell the page apart."""
    described = describe_url(url)
    head, sep, rest = described.partition(" path=")
    path, sep2, query = rest.partition(" query=")
    parts = path.split("/")
    if len(parts) > 3:
        path = "/".join(parts[:3]) + "/..."
    return f"{head} path={path} query={query}" if sep and sep2 else described


def cookie_sent_to(cookie: dict, host: str) -> bool:
    """RFC 6265 domain match: a host-only cookie travels to its own host, a `.domain` cookie to its
    sub-domains as well. Path, scheme and expiry are not looked at."""
    raw = str(cookie.get("domain") or "").lower()
    domain = raw.lstrip(".")
    return bool(domain) and (host == domain or (raw.startswith(".") and host.endswith("." + domain)))


def describe_cookies(cookies: Any, landing: str | None) -> str:
    """Structure of a cookie jar: how many, how many travel to the landing host, and for each the
    NAME, the DOMAIN (marked `*` for a domain cookie, bare for a host-only one) and the flags. Never a
    value, never an expiry. Names that look like data are masked."""
    if not isinstance(cookies, list):
        return "cookies=unreadable"
    jar = [c for c in cookies if isinstance(c, dict)]
    sent = sum(cookie_sent_to(c, landing) for c in jar) if landing else 0
    shown = []
    for cookie in jar[:MAX_LOGGED_COOKIES]:
        raw = str(cookie.get("domain") or "")
        domain = _mask_digits(raw.lstrip(".").lower())[:60] or "-"
        flags = (
            ("S" if cookie.get("secure") else "")
            + ("H" if cookie.get("httpOnly") else "")
            + ("T" if cookie.get("expires") in (None, -1) else "")  # a session cookie: no expiry
        )
        shown.append(f"{_name(cookie.get('name'))}@{'*' if raw.startswith('.') else ''}{domain}[{flags}]")
    more = f"+{len(jar) - MAX_LOGGED_COOKIES}" if len(jar) > MAX_LOGGED_COOKIES else ""
    return f"cookies={len(jar)} sent_to_landing={sent} jar=[{','.join(shown)}{more}]"


async def log_session_stop(state: dict[str, Any], stage: str, phase: str, landing: str | None) -> None:
    """ONE structure-only line for a session check that stopped: where the page ended up (host, path
    shape, query keys), which host the check meant to use, and the shape of the cookie jar."""
    brand: Brand = state["brand"]
    page = state.get("page")
    try:
        url = page.url
        where = f"{shallow_url(url)} kind={classify_url(url, brand)}"
    except Exception:
        where = "host=<none> path=<none> query=[]"
    try:
        jar = describe_cookies(await asyncio.wait_for(state["context"].cookies(), timeout=STRUCTURE_TIMEOUT_SECONDS), landing)
    except Exception:
        jar = "cookies=unreadable"
    log.warning(
        "BNP session check stopped (stage=%s phase=%s) landing=%s %s %s",
        stage, phase, _mask_digits(landing)[:100] if landing else "none", where, jar,
    )


async def fetch_json(page: Any, key: str, body: dict | None = None) -> Any:
    """One of the fixed read endpoints, requested by the page itself (same origin, its cookies)."""
    method, path = ENDPOINTS[key] if key in ENDPOINTS else ("GET", key)
    result = await page.evaluate(_FETCH_JS, [method, path, body])
    if not isinstance(result, dict) or not isinstance(result.get("text"), str) or len(result["text"]) > 8_000_000:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED", stage="snapshot_fetch")
    if result.get("status") in (401, 403):
        raise fail(401, "SESSION_EXPIRED")
    if result.get("status") != 200:
        raise fail(502, "UPSTREAM_UNAVAILABLE")
    return parsers.loads(result["text"])


def session_alive(info: Any) -> bool:
    """Signed in: InfosClient answers `codeRetour` 0 AND carries `data`.

    The code alone is not enough. Signed out, the public site answers `"-10"` ("user not connected", a
    string). The signed-in site whose session idled out still answers `"0"` and "OK", with `data: null`
    (seen live), and its data endpoints then answer a generic error (`codeRetour` 99)."""
    if not isinstance(info, dict):
        return False
    try:
        code = int(str(info.get("codeRetour")).strip())
    except ValueError:
        return False
    return code == 0 and bool(info.get("data"))


async def probe_session(page: Any, brand: Brand) -> bool | None:
    """True/False once the site answers, None when it cannot be asked yet (mid-redirect)."""
    if not on_site(page.url, brand):
        return None
    try:
        return session_alive(await fetch_json(page, INFOS_CLIENT))
    except HTTPException:
        return False
    except Exception:
        return None


def classify_second_factor(code_inputs: int, page_text: str) -> str | None:
    """UNVERIFIED heuristic. 'sms' when the page asks for a code, 'app' when it only waits for an
    approval in the bank's app, None (stop) when neither is recognisable."""
    if code_inputs == 1:
        return "sms"
    if code_inputs == 0 and re.search(r"cl[ée] digitale|application|smartphone|mobile", page_text, re.I):
        return "app"
    return None


# ─── the client ──────────────────────────────────────────────────────────────


class BrowserBankClient:
    def __init__(self) -> None:
        self.pending = PendingStore(dispose=_close, log=log, ttl_seconds=PENDING_TTL_SECONDS, max_size=MAX_PENDING)
        self._last_login = 0.0
        self._first: dict[str, tuple[float, tuple[list[dict], list[dict]]]] = {}

    # -- login ---------------------------------------------------------------

    async def initiate(self, customer_id: str, brand_name: str) -> dict[str, Any]:
        brand = BRANDS.get(brand_name)
        if brand is None:
            raise fail(400, "INVALID_REQUEST")
        await self.pending.expire()
        if len(self.pending) >= MAX_PENDING:
            raise fail(503, "UPSTREAM_UNAVAILABLE")
        if time.monotonic() - self._last_login < MIN_LOGIN_GAP:
            raise fail(429, "RATE_LIMITED")
        self._last_login = time.monotonic()
        state = await launch(brand)
        parked = False
        try:
            page = state["page"]
            try:
                await page.goto(brand.entry, wait_until="domcontentloaded", timeout=45_000)
            except Exception:
                require_clean(state, "page_load")
                raise fail(502, "UPSTREAM_UNAVAILABLE", stage="page_load") from None
            require_clean(state, "page_load")
            if not on_login(page.url, brand):
                raise fail(409, "KEYPAD_CHANGED", stage="login_page")
            keys = await self._read_keypad(page, brand, customer_id, state)
            process_id = uuid.uuid4().hex
            state.update(stage="keypad", process_id=process_id, submit_url=page.url)
            await self.pending.put_or_raise(process_id, state)
            parked = True
            return {
                "processId": process_id,
                "keypad": {"keys": keys, "columns": COLUMNS},
                "expiresInSeconds": PENDING_TTL_SECONDS,
            }
        finally:
            if not parked:
                await _close(state)

    async def _read_keypad(self, page: Any, brand: Brand, customer_id: str, state: dict[str, Any]) -> list[str]:
        """Type the customer number, open the keypad step, and read the ten digits on the buttons."""
        found = await page.evaluate(_INSPECT_JS, [FORM, USER_INPUT, GRID_INPUT, STEP_PASSWORD, KEY_BUTTONS, SUBMIT_BUTTON])
        action = urlsplit(str(found.get("action") or ""))
        if not (
            found.get("form") and found.get("user") and found.get("grid") and found.get("submit") and found.get("pad")
            and found.get("method") == "post"
            # The form posts back to the PingFederate login host, never elsewhere.
            and (action.hostname or "") == brand.login and action.scheme == "https"
            and action.path.startswith("/as/") and action.path.endswith("/authorization.ping")
        ):
            raise fail(409, "KEYPAD_CHANGED", stage="login_form")
        try:
            await page.fill(USER_INPUT, customer_id, timeout=10_000)
            await page.get_by_role("button", name=CONTINUE_BUTTON_NAME).click(timeout=10_000)
            await page.wait_for_selector(f"{STEP_PASSWORD}:not(.hidden) {KEY_BUTTONS}", state="visible", timeout=STEP_SECONDS * 1000)
        except Exception:
            require_clean(state, "customer_number")
            raise fail(409, "KEYPAD_CHANGED", stage="customer_number") from None
        require_clean(state, "keypad_read")
        found = await page.evaluate(_INSPECT_JS, [FORM, USER_INPUT, GRID_INPUT, STEP_PASSWORD, KEY_BUTTONS, SUBMIT_BUTTON])
        keys = found.get("keys")
        if (
            not isinstance(keys, list)
            or len(keys) != KEY_COUNT
            or found.get("columns") != COLUMNS
            or any(not isinstance(key, str) or not re.fullmatch(r"[0-9]", key) for key in keys)
            or len(set(keys)) != KEY_COUNT
        ):
            raise fail(409, "KEYPAD_CHANGED", stage="keypad_read")
        return keys

    async def keypad(self, process_id: str, positions: list[int]) -> str:
        state = await self.pending.take(process_id)
        if state is None or state.get("stage") != "keypad":
            if state is not None:
                await _close(state)
            raise fail(410, "AUTH_ATTEMPT_EXPIRED")
        brand: Brand = state["brand"]
        page = state["page"]
        keep = False
        submitted = False
        try:
            require_clean(state, "keypad_press")
            if len(positions) != POSITIONS or any(not 0 <= position < KEY_COUNT for position in positions):
                raise fail(400, "INVALID_REQUEST")
            # The member's click order, replayed as real clicks on the real buttons.
            keys = page.locator(KEY_BUTTONS)
            try:
                for position in positions:
                    await keys.nth(position).click(timeout=10_000)
                pressed = await page.evaluate(_PRESSED_JS, [GRID_INPUT, SUBMIT_BUTTON])
            except Exception:
                require_clean(state, "keypad_press")
                raise fail(409, "KEYPAD_CHANGED", stage="keypad_press") from None
            # The page's own field must hold exactly the clicked positions (verified live: one
            # character per press, the 0-based DOM index) and the submit must be enabled.
            if pressed.get("value") != "".join(str(position) for position in positions) or not pressed.get("enabled"):
                raise fail(409, "KEYPAD_CHANGED", stage="keypad_press")
            # One submission, never retried: a wrong keypad counts against the bank's lockout.
            try:
                submitted = True
                await page.click(SUBMIT_BUTTON, timeout=10_000)
            except Exception:
                require_clean(state, "submit")
                raise fail(409, "KEYPAD_CHANGED", stage="submit") from None
            outcome = await self._settle(page, brand, state)
            if isinstance(outcome, HTTPException):
                raise outcome
            if outcome == "logged_in":
                state.update(stage="mfa", kind="none")
                status = "APPROVAL_PENDING"
            else:
                kind = await self._second_factor_kind(page)
                if kind is None:
                    raise fail(422, "MFA_TYPE_UNSUPPORTED", stage="second_factor")
                state.update(stage="mfa", kind=kind)
                status = "CODE_REQUIRED" if kind == "sms" else "APPROVAL_PENDING"
            state["created_at"] = time.time()
            await self.pending.repark(process_id, state)
            keep = True
            return status
        except HTTPException as exc:
            if submitted:
                await self._diagnose(page, brand, exc)
            raise
        finally:
            if not keep:
                await _close(state)

    @staticmethod
    async def _diagnose(page: Any, brand: Brand, exc: HTTPException) -> None:
        """Every stop with a stage after the login submit leaves one structure-only line."""
        stage = getattr(exc, "stage", None)
        if stage:
            await log_page_structure(page, brand, stage)

    async def _settle(self, page: Any, brand: Brand, state: dict[str, Any]) -> Any:
        """After the single submit: 'logged_in', 'second_factor', or an HTTPException to raise.

        The bank answers with a redirect chain (PingFederate resume, an OIDC callback with `code` and
        `state`, then the site, possibly on another `.bnpparibas` host). The URL says what each page
        is (`classify_url`); a callback is a hop in flight and never a verdict."""
        deadline = time.monotonic() + SETTLE_SECONDS
        last_url, changed_at = page.url, time.monotonic()
        while time.monotonic() < deadline:
            await asyncio.sleep(SETTLE_POLL_SECONDS)
            if state["guard"].tainted:
                return fail(502, "UPSTREAM_FORMAT_CHANGED", stage="after_submit")
            url = page.url
            if not is_allowed_url(url, brand.hosts):
                return fail(502, "UPSTREAM_FORMAT_CHANGED", stage="after_submit_redirect")
            kind = classify_url(url, brand)
            if kind == "error":
                failure = login_failure(url)
                if failure is not None:
                    return failure
            if kind == "password":
                return fail(409, "ACTION_REQUIRED", stage="password_expired")
            if url != last_url:
                last_url, changed_at = url, time.monotonic()
            if kind == "callback":
                continue
            if await probe_session(page, brand):
                return "logged_in"
            # Still on the page the form posted from: nothing happened yet.
            if url == state.get("submit_url"):
                continue
            # A different page that stayed put without a session: the second-factor step. The page's
            # own shape decides which one (`_second_factor_kind`), and an unknown one stops the login.
            if time.monotonic() - changed_at >= STABLE_SECONDS:
                return "second_factor"
        return fail(409, "KEYPAD_CHANGED", stage="after_submit_timeout")

    async def _second_factor_kind(self, page: Any) -> str | None:
        try:
            visible = 0
            for handle in await page.query_selector_all(CODE_INPUT):
                if await handle.is_visible():
                    visible += 1
            text = await page.inner_text("body", timeout=5_000)
        except Exception:
            return None
        return classify_second_factor(visible, text)

    async def _wait_logged_in(self, page: Any, brand: Brand, state: dict[str, Any], seconds: float) -> bool:
        deadline = time.monotonic() + seconds
        while True:
            if state["guard"].tainted:
                raise fail(502, "UPSTREAM_FORMAT_CHANGED", stage="second_factor_wait")
            failure = login_failure(page.url)
            if failure is not None:
                raise failure
            if await probe_session(page, brand):
                return True
            if time.monotonic() >= deadline:
                return False
            await asyncio.sleep(POLL_SECONDS)

    async def complete(self, process_id: str, code: str | None) -> str:
        deadline = time.monotonic() + COMPLETE_SECONDS
        state = await self.pending.take(process_id)
        if state is None or state.get("stage") != "mfa":
            if state is not None:
                await _close(state)
            raise fail(410, "AUTH_ATTEMPT_EXPIRED")
        brand: Brand = state["brand"]
        page = state["page"]
        keep = False
        try:
            kind = state["kind"]
            if kind == "sms":
                if not code:
                    raise fail(400, "INVALID_OTP")
                try:
                    field = page.locator(CODE_INPUT).first
                    await field.fill(code, timeout=10_000)
                    await field.press("Enter")
                except Exception:
                    require_clean(state, "sms_code")
                    raise fail(409, "MFA_TYPE_UNSUPPORTED", stage="sms_code") from None
                if not await self._wait_logged_in(page, brand, state, SMS_SETTLE_SECONDS):
                    # Still on the code page: the member may retype once; the backend counts tries.
                    state["created_at"] = time.time()
                    await self.pending.repark(process_id, state)
                    keep = True
                    raise fail(401, "INVALID_OTP")
            else:
                if code:
                    raise fail(400, "INVALID_REQUEST")
                wait = 0 if kind == "none" else APP_APPROVAL_SECONDS
                if not await self._wait_logged_in(page, brand, state, wait):
                    raise fail(408, "APP_VALIDATION_TIMEOUT") if kind == "app" else fail(502, "UPSTREAM_FORMAT_CHANGED", stage="approval_wait")
            require_clean(state, "session_cookies")
            # The browser that logged in is the one the session is known to work in: read the first
            # snapshot here, before it closes, and keep it for the /accounts call that follows.
            landing = landing_host(page.url, brand)
            snapshot = await self._first_snapshot(state, brand, landing, deadline - time.monotonic())
            session = encode_session(brand, await state["context"].cookies(), landing)
            if snapshot is not None and not state["guard"].tainted:
                self._remember(session, snapshot)
            return session
        except HTTPException as exc:
            await self._diagnose(page, brand, exc)
            raise
        finally:
            if not keep:
                await _close(state)

    # -- snapshot --------------------------------------------------------------

    async def accounts(self, session_state: str) -> dict[str, Any]:
        brand, cookies, landing = decode_session_state(session_state)
        cached = self._take(session_state)
        if cached is not None:
            # Read in the browser that logged in, a moment ago: the session is the same one.
            return {"brand": brand.name, "accounts": cached[0], "unsupported": cached[1], "sessionState": session_state}
        state = await launch(brand)
        try:
            page = state["page"]
            await state["context"].add_cookies(cookies)
            # Where the signed-in browser was (the private banking space is not `brand.site`), else the site's home:
            # any page of the host gives the API its origin and cookies.
            home = f"https://{landing}/" if landing else brand.base + "/"
            try:
                await page.goto(home, wait_until="domcontentloaded", timeout=45_000)
            except Exception:
                require_clean(state, "snapshot_load")
                raise fail(502, "UPSTREAM_UNAVAILABLE", stage="snapshot_load") from None
            require_clean(state, "snapshot_load")
            snapshot = await self._read_snapshot(state, brand, "fresh_browser", landing)
            require_clean(state, "snapshot")
            return {
                "brand": brand.name,
                "accounts": snapshot[0],
                "unsupported": snapshot[1],
                "sessionState": encode_session(brand, await state["context"].cookies(), landing_host(page.url, brand) or landing),
            }
        finally:
            await _close(state)

    async def _read_snapshot(self, state: dict[str, Any], brand: Brand, phase: str, landing: str | None) -> tuple[list[dict], list[dict]]:
        """Check the session, then read the accounts, on the page the state holds. Every way the session
        can be found dead has its own stage, and leaves one structure-only line (`log_session_stop`):
          snapshot_off_site    the page is not on the bank's site (bounced to a login page or off it);
          snapshot_info_dead   the bank says nobody is signed in (InfosClient answers `codeRetour` -10 / -30, or 0
                               without data; or a data answer says -10 / -30);
          snapshot_http_401    the bank refused a request with 401 / 403.
        `phase` says which browser asked: `same_browser` (the one that logged in) or `fresh_browser`."""
        page = state["page"]

        async def stop(stage: str) -> HTTPException:
            exc = fail(401, "SESSION_EXPIRED", stage=stage)
            await log_session_stop(state, stage, phase, landing)
            return exc

        if not on_site(page.url, brand):
            raise await stop("snapshot_off_site")
        try:
            if not session_alive(await fetch_json(page, INFOS_CLIENT)):
                raise await stop("snapshot_info_dead")
            return await self._snapshot(page)
        except parsers.SessionExpired:
            raise await stop("snapshot_info_dead") from None
        except parsers.ParseError as exc:
            raise fail(502, exc.code, stage="snapshot_parse") from None
        except HTTPException as exc:
            if exc.detail == "SESSION_EXPIRED" and getattr(exc, "stage", None) is None:
                raise await stop("snapshot_http_401") from None
            raise

    async def _first_snapshot(self, state: dict[str, Any], brand: Brand, landing: str | None, seconds: float) -> tuple[list[dict], list[dict]] | None:
        """The first snapshot, in the browser that just logged in. Never fails the login: when it cannot
        be read the stage is logged and `/accounts` reads it again the usual way. `seconds` is what `/complete`
        has left: after a late approval the snapshot gets less than FIRST_SNAPSHOT_SECONDS, or no time at all."""
        try:
            return await asyncio.wait_for(self._read_snapshot(state, brand, "same_browser", landing), min(FIRST_SNAPSHOT_SECONDS, seconds))
        except HTTPException:
            return None  # staged and logged where it stopped
        except TimeoutError:
            log.warning("BNP first snapshot ran out of time (stage=snapshot_same_browser_timeout)")
            return None
        except Exception:
            log.warning("BNP first snapshot failed unexpectedly (stage=snapshot_same_browser)")
            return None

    # The first snapshot waits (briefly, in memory only) for the `/accounts` call of the same session.

    @staticmethod
    def _digest(session_state: str) -> str:
        return hashlib.sha256(session_state.encode()).hexdigest()

    def _remember(self, session_state: str, snapshot: tuple[list[dict], list[dict]]) -> None:
        now = time.monotonic()
        self._first = {key: item for key, item in self._first.items() if item[0] > now}
        while len(self._first) >= MAX_PENDING:
            self._first.pop(next(iter(self._first)))
        self._first[self._digest(session_state)] = (now + SNAPSHOT_CACHE_SECONDS, snapshot)

    def _take(self, session_state: str) -> tuple[list[dict], list[dict]] | None:
        item = self._first.pop(self._digest(session_state), None)
        return item[1] if item is not None and item[0] > time.monotonic() else None

    async def _snapshot(self, page: Any) -> tuple[list[dict], list[dict]]:
        ibans = parsers.parse_ibans(parsers.envelope(await fetch_json(page, "ibans")))
        rows, unsupported = parsers.parse_accounts(parsers.envelope(await fetch_json(page, "accounts")), ibans)
        end = date.today()
        window = {"startDate": (end - timedelta(days=365)).strftime("%d%m%Y"), "endDate": end.strftime("%d%m%Y")}
        histories: dict[str, Any] = {}
        portfolios: dict[str, Any] = {}
        synthesis: list[dict] = []
        securities = [row for row in rows if row["kind"] in ("PEA", "COMPTE_TITRES") and not row["cash"]]
        if securities:
            synthesis = parsers.parse_synthesis(parsers.envelope(await fetch_json(page, "synthesis", {})))
        for row in rows:
            if row["cash"]:
                continue
            if row["kind"] in ("PEA", "COMPTE_TITRES"):
                match = parsers.match_securities(row, synthesis)
                portfolios[row["externalId"]] = None
                if match is not None:
                    portfolios[row["externalId"]] = await self._optional(
                        page, "portfolio", {"securityAccountNumber": match["number"]}, parsers.parse_positions
                    )
            elif row["kind"] not in ("ASSURANCE_VIE", "PER", "LOAN"):
                body = {"ibanCrypte": row["key"], "pastOrPending": 1, "triAV": 0, **window}
                histories[row["externalId"]] = await self._optional(page, "history", body, parsers.parse_operations)
        return parsers.build_accounts(rows, histories, synthesis, portfolios), unsupported

    async def _optional(self, page: Any, key: str, body: dict, parse) -> Any:
        """Parsed data, or None when the bank answered something unusable (the account turns incomplete).
        A dead session still ends the whole snapshot."""
        try:
            return parse(parsers.envelope(await fetch_json(page, key, body)))
        except parsers.SessionExpired:
            raise
        except (parsers.ParseError, HTTPException) as exc:
            if isinstance(exc, HTTPException) and exc.detail == "SESSION_EXPIRED":
                raise
            return None


# ─── session state ───────────────────────────────────────────────────────────

COOKIE_KEYS = ("name", "value", "domain", "path", "expires", "httpOnly", "secure", "sameSite")


def cookie_in_scope(cookie: dict, brand: Brand) -> bool:
    """Every cookie of the bank's domains: the brand's hosts and their sub-domains, and every host of the
    `.bnpparibas` dotBrand TLD (never the bare TLD)."""
    domain = str(cookie.get("domain") or "").lstrip(".").lower()
    return (
        any(domain == known or domain.endswith("." + known) for known in brand.cookie_domains)
        or is_brand_tld_host(domain)
    )


def encode_session(brand: Brand, cookies: list[dict], landing: str | None = None) -> str:
    """Only the cookies of the brand's own domains, with the few fields needed to restore them, and the
    host of the site the browser was on (`landing`, a host name only)."""
    kept = [{key: c[key] for key in COOKIE_KEYS if key in c} for c in cookies if cookie_in_scope(c, brand)]
    data: dict[str, Any] = {"v": 2, "brand": brand.name, "cookies": kept}
    if landing is not None and valid_landing(landing, brand):
        data["landing"] = landing
    text = json.dumps(data, separators=(",", ":"))
    if not kept or len(kept) > MAX_COOKIES or len(text) > MAX_SESSION_STATE:
        raise fail(502, "UPSTREAM_FORMAT_CHANGED", stage="session_cookies")
    return base64.b64encode(text.encode()).decode("ascii")


def decode_session_state(session_state: str) -> tuple[Brand, list[dict], str | None]:
    try:
        data = json.loads(base64.b64decode(session_state, validate=True))
        brand = BRANDS[data["brand"]]
        cookies = data["cookies"]
        if data["v"] != 2 or not isinstance(cookies, list) or not 0 < len(cookies) <= MAX_COOKIES:
            raise ValueError
        landing = data.get("landing")  # absent in a state saved before it was recorded
        if landing is not None and not valid_landing(landing, brand):
            raise ValueError
        out = []
        for cookie in cookies:
            if not isinstance(cookie.get("name"), str) or not isinstance(cookie.get("value"), str):
                raise ValueError
            if not cookie_in_scope(cookie, brand):
                raise ValueError
            out.append({key: cookie[key] for key in COOKIE_KEYS if key in cookie})
        return brand, out, landing
    except (ValueError, KeyError, TypeError, AttributeError):
        raise fail(400, "INVALID_SESSION_STATE") from None


def decode_session(session_state: str) -> tuple[Brand, list[dict]]:
    brand, cookies, _ = decode_session_state(session_state)
    return brand, cookies
