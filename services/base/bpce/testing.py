"""Test double for the Playwright objects `bpce.login` drives. Test-only module.

Plays the BPCE login page: an optional establishment picker and cookie banner
(Banque Populaire), the identifier step, a 10-key image keypad (read by
`/initiate`, clicked by `/keypad`), the Sécur'Pass wait and the client space.
No browser, no network, no real data. The base image does not ship it; CI mounts
it read-only for the sidecar tests.
"""

import asyncio
import base64
import hashlib
import struct
import zlib

from playwright.async_api import Error as PlaywrightError

from . import login

DIGITS = "0123456789"
# Banque Populaire's consent banner refusal ("Continuer sans accepter").
COOKIE_BANNER_BUTTON = "#no_consent_btn"

IDENTIFIER_URL = "https://www.icgauth.caisse-epargne.fr/se-connecter/identifier"
KEYS_URL = "https://www.icgauth.caisse-epargne.fr/se-connecter/icg/mot-de-passe"
SECURPASS_URL = KEYS_URL + "/(modal:icg/cloudcard)"
CLIENT_URL = "https://www.caisse-epargne.fr/espace-client/synthese"
ERROR_URL = "https://www.caisse-epargne.fr/erreur"
AUTHORIZE_URL = (
    "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/authorize"
    "?client_id=test-client-id"
    "&redirect_uri=https%3A%2F%2Fwww.example-app.caisse-epargne.fr%2Fcallback"
    "&scope=openid&response_type=code"
    "&code_challenge=stale-challenge&code_challenge_method=S256&nonce=stale-nonce"
    "&login_hint=SECRET-LOGIN-HINT"
)
# The login page itself calls authorize (other parameters) BEFORE the client space.
DECOY_AUTHORIZE_URL = (
    "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/authorize"
    "?client_id=decoy-login-client&redirect_uri=https%3A%2F%2Fdecoy.caisse-epargne.fr%2Fcb"
)


def make_png(digit: str) -> bytes:
    """A real, tiny grayscale PNG whose pixels depend on the digit."""

    def chunk(kind: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(kind + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", crc)

    width = height = 8
    rows = b"".join(
        b"\x00" + bytes((int(digit) * 23 + x * 5 + y * 3) % 256 for x in range(width))
        for y in range(height)
    )
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


def png_digest(png: bytes) -> str:
    return hashlib.sha256(png).hexdigest()


def data_uri(png: bytes, mime: str = "image/png") -> str:
    return f"data:{mime};base64,{base64.b64encode(png).decode()}"


_ref = make_png("0")
UNKNOWN_PNG = _ref[:-12] + b"unknown-key" + _ref[-12:]
# Decoded size just above the 16 KB cap the sidecar accepts per key image.
OVERSIZE_PNG = _ref[:-12] + b"x" * (16 * 1024) + _ref[-12:]


def default_cookies() -> list[dict]:
    return [
        {"name": "SSO", "value": "CE-COOKIE-SECRET-1", "domain": ".caisse-epargne.fr",
         "path": "/", "httpOnly": True, "secure": True, "expires": -1},
        {"name": "SESSION", "value": "CE-COOKIE-SECRET-2", "domain": "www.caisse-epargne.fr",
         "path": "/espace-client", "httpOnly": True, "secure": True, "expires": -1},
        {"name": "tracker", "value": "other-site", "domain": ".example.com", "path": "/"},
        {"name": "lookalike", "value": "evil", "domain": "evilcaisse-epargne.fr", "path": "/"},
    ]


REGION_PLACEHOLDER = "Sélectionnez votre établissement bancaire"


class World:
    """Scenario knobs plus everything the tests assert on.

    The URLs default to the Caisse d'Epargne ones; another bank passes its own.
    """

    def __init__(
        self,
        layout: str = "3817250649",
        *,
        identifier_url: str = IDENTIFIER_URL,
        keys_url: str = KEYS_URL,
        client_url: str = CLIENT_URL,
        error_url: str = ERROR_URL,
        authorize_url: str = AUTHORIZE_URL,
        decoy_authorize_url: str = DECOY_AUTHORIZE_URL,
    ):
        assert sorted(layout) == list(DIGITS)
        self.layout = layout
        self.identifier_url = identifier_url
        self.keys_url = keys_url
        self.securpass_url = keys_url + "/(modal:icg/cloudcard)"
        self.client_url = client_url
        self.error_url = error_url
        self.decoy_authorize_url = decoy_authorize_url
        # scenario
        self.key_count = 10
        self.unknown_key: int | None = None  # key i shows a PNG that is not one of the pad's
        self.oversize_key: int | None = None  # key i shows a PNG above the 16 KB cap
        self.css_by_key: dict[int, str] = {}  # per-key CSS value (e.g. no data URI)
        self.same_image: tuple[int, int] | None = None  # key i shows key j's image
        self.css_override: str | None = None  # every key shows this CSS value
        self.identifier_ok = True
        self.identifier_error = True  # a refused identifier shows an error banner
        self.after_valider = "securpass"  # securpass | securpass_text | error | nothing
        self.after_push = "approve"  # approve | refuse | never | erreur
        self.approve_after_reads = 6
        self.authorize_url = authorize_url
        self.authorize_on = "reach"  # reach | reload | never
        self.early_authorize = False  # an authorize request BEFORE the client space
        self.cookies = default_cookies()
        self.launch_error: Exception | None = None
        self.fail_on: dict[str, BaseException] = {}
        self.hang_on: set[str] = set()  # these calls never return (a stuck page)
        self.info_alert = False  # an informational `role=alert` is visible on every page
        self.reshuffle_after: int | None = None  # keypad reshuffles after this many key clicks
        self.reshuffled_layout = "0123456789"
        # Establishment picker: None means the page has none (Caisse d'Epargne).
        self.region_options: list[str] | None = None
        self.region_triggers = 1  # how many combobox triggers the page shows
        self.region_select_works = True  # False: a click on an option changes nothing
        self.cookie_banner = False  # a consent banner covers the page until refused
        # records
        self.events: list[str] = []
        self.launches = 0
        self.launch_kwargs: list[dict] = []
        self.context_kwargs: list[dict] = []
        self.contexts: list[FakeContext] = []
        self.pages: list[FakePage] = []
        self.opened: list[str] = []
        self.closed: list[str] = []
        self.gotos: list[str] = []
        self.fills: list[tuple[str, str]] = []
        self.key_clicks: list[int] = []
        self.typed: list[str] = []
        self.page_clicks: list[str] = []
        self.visible_queries: list[str] = []
        self.selected_region: str | None = None
        self.region_option_clicks: list[str] = []
        self.cookie_banner_dismissed = False

    def key_png(self, index: int) -> bytes:
        if index == self.unknown_key:
            return UNKNOWN_PNG
        if index == self.oversize_key:
            return OVERSIZE_PNG
        if self.same_image and index == self.same_image[0]:
            index = self.same_image[1]
        return make_png(self.layout[index % 10])

    def key_css(self, index: int) -> str:
        if index in self.css_by_key:
            return self.css_by_key[index]
        if self.css_override is not None:
            return self.css_override
        return f'url("{data_uri(self.key_png(index))}")'

    def all_digests_hex(self) -> list[str]:
        return [png_digest(self.key_png(i)) for i in range(10)]

    def all_data_uris(self) -> list[str]:
        return [data_uri(self.key_png(i)) for i in range(10)]

    def maybe_fail(self, name: str) -> None:
        if name in self.fail_on:
            raise self.fail_on[name]

    async def maybe_hang(self, name: str) -> None:
        if name in self.hang_on:
            await asyncio.Event().wait()

    def still_open(self) -> list[str]:
        return [r for r in self.opened if r not in self.closed]

    # --- what login._playwright_factory returns ---
    def factory(self):
        return FakePlaywrightManager(self)


class FakePlaywrightManager:
    def __init__(self, world: World):
        self.world = world

    async def start(self):
        return FakePlaywright(self.world)


class FakePlaywright:
    def __init__(self, world: World):
        self.world = world
        self.name = f"playwright#{sum(1 for o in world.opened if o.startswith('playwright'))}"
        world.opened.append(self.name)
        self.chromium = FakeChromium(world)

    async def stop(self):
        self.world.closed.append(self.name)


class FakeChromium:
    def __init__(self, world: World):
        self.world = world

    async def launch(self, **kwargs):
        await asyncio.sleep(0)  # let concurrent initiates interleave
        self.world.launches += 1
        self.world.launch_kwargs.append(kwargs)
        if self.world.launch_error:
            raise self.world.launch_error
        browser = FakeBrowser(self.world, f"browser#{self.world.launches}")
        self.world.opened.append(browser.name)
        return browser


class FakeBrowser:
    def __init__(self, world: World, name: str):
        self.world = world
        self.name = name

    async def new_context(self, **kwargs):
        self.world.maybe_fail("new_context")
        self.world.context_kwargs.append(kwargs)
        ctx = FakeContext(self.world, f"context#{len(self.world.contexts) + 1}")
        self.world.contexts.append(ctx)
        self.world.opened.append(ctx.name)
        return ctx

    async def close(self):
        self.world.closed.append(self.name)


class FakeRoute:
    def __init__(self, url: str):
        self.request = type("Req", (), {"url": url, "resource_type": "document"})()
        self.aborted = False
        self.continued = False

    async def abort(self, *_a, **_k):
        self.aborted = True

    async def continue_(self, *_a, **_k):
        self.continued = True


class FakeWebSocketRoute:
    def __init__(self, url: str):
        self.url = url
        self.connected = False
        self.closed = False

    def connect_to_server(self):
        self.connected = True
        return self

    async def close(self, *_a, **_k):
        self.closed = True


class FakeContext:
    def __init__(self, world: World, name: str):
        self.world = world
        self.name = name
        self.route_pattern = None
        self.route_handler = None
        self.ws_pattern = None
        self.ws_handler = None

    async def route(self, pattern, handler):
        self.world.events.append("route")
        self.route_pattern = pattern
        self.route_handler = handler

    async def route_web_socket(self, pattern, handler):
        self.world.events.append("route_web_socket")
        self.ws_pattern = pattern
        self.ws_handler = handler

    async def open_web_socket(self, url: str) -> "FakeWebSocketRoute":
        """What the browser does for `new WebSocket(url)`: the handler decides."""
        assert self.ws_handler is not None
        ws = FakeWebSocketRoute(url)
        result = self.ws_handler(ws)
        if hasattr(result, "__await__"):
            await result
        return ws

    async def new_page(self):
        page = FakePage(self.world)
        self.world.pages.append(page)
        return page

    async def cookies(self, *_a):
        return [dict(c) for c in self.world.cookies]

    async def close(self):
        self.world.closed.append(self.name)

    async def request_through_route(self, url: str) -> FakeRoute:
        """What the browser does for a request: the route handler decides."""
        assert self.route_handler is not None
        route = FakeRoute(url)
        result = self.route_handler(route)
        if hasattr(result, "__await__"):
            await result
        return route


class FakeRequest:
    def __init__(self, url: str):
        self.url = url


def playwright_error(message: str = "boom") -> PlaywrightError:
    return PlaywrightError(message)


class FakePage:
    def __init__(self, world: World):
        self.world = world
        self._url = "about:blank"
        self.stage = "blank"
        self.listeners: dict[str, list] = {}
        self.error_visible = False
        self.reads_in_securpass = 0
        self.reloads = 0
        self.reload_kwargs: list[dict] = []
        self.picker_open = False

    # Forbidden surfaces: the login must never capture the page.
    async def screenshot(self, *_a, **_k):
        raise AssertionError("login must never take a screenshot")

    @property
    def video(self):
        raise AssertionError("login must never record video")

    @property
    def url(self) -> str:
        if self.stage == "securpass":
            self.reads_in_securpass += 1
            if self.reads_in_securpass >= self.world.approve_after_reads:
                self._resolve_push()
        return self._url

    def _emit_authorize(self, url: str | None = None):
        for handler in self.listeners.get("request", []):
            handler(FakeRequest(url or self.world.authorize_url))

    def _resolve_push(self):
        outcome = self.world.after_push
        if outcome == "never":
            self.reads_in_securpass = -(10**9)
            return
        self.stage = "resolved"
        if outcome == "approve":
            self._url = self.world.client_url
            if self.world.authorize_on == "reach":
                self._emit_authorize()
        elif outcome == "erreur":
            self._url = self.world.error_url
        elif outcome == "refuse":
            self.error_visible = True

    def on(self, event, handler):
        self.listeners.setdefault(event, []).append(handler)

    async def goto(self, url, **_kw):
        self.world.maybe_fail("goto")
        await self.world.maybe_hang("goto")
        self.world.gotos.append(url)
        self.world.events.append("goto")
        self.stage, self._url = "identifier", self.world.identifier_url

    async def reload(self, **_kw):
        self.reloads += 1
        self.reload_kwargs.append(_kw)
        await self.world.maybe_hang("reload")
        if self.world.authorize_on == "reload":
            self._emit_authorize()

    async def fill(self, selector, value, **_kw):
        self.world.maybe_fail("fill")
        self.world.fills.append((selector, value))

    async def click(self, selector, **_kw):
        self.world.maybe_fail("click")
        self.world.page_clicks.append(selector)
        self.world.events.append(f"click:{selector}")
        if selector == COOKIE_BANNER_BUTTON and self.world.cookie_banner:
            self.world.cookie_banner_dismissed = True
        elif selector == login.NEXT_BUTTON:
            if self.world.region_options is not None and self.world.selected_region is None:
                raise AssertionError("identifier submitted before an establishment was picked")
            if self.world.identifier_ok:
                self.stage, self._url = "password", self.world.keys_url
            else:
                self.stage = "identifier_refused"
                self.error_visible = self.world.identifier_error
        elif selector == login.SUBMIT_BUTTON:
            outcome = self.world.after_valider
            if self.world.early_authorize:
                self._emit_authorize(self.world.decoy_authorize_url)
            if outcome == "securpass":
                self.stage, self._url = "securpass", self.world.securpass_url
            elif outcome == "securpass_text":
                self.stage = "securpass"
            elif outcome == "error":
                self.stage, self.error_visible = "refused", True
            else:
                self.stage = "stuck"
        else:
            raise AssertionError(f"unexpected click on {selector!r}")

    def locator(self, selector):
        return FakeLocator(self, selector)


class FakeLocator:
    def __init__(self, page: FakePage, selector: str):
        self.page = page
        self.selector = selector

    @property
    def first(self):
        return self

    async def count(self) -> int:
        world = self.page.world
        if self.selector == login.KEY_SELECTOR:
            return world.key_count if self.page.stage == "password" else 0
        if self.selector == login.REGION_TRIGGER:
            on_identifier = self.page.stage == "identifier"
            return world.region_triggers if on_identifier and world.region_options is not None else 0
        if self.selector == login.REGION_OPTION:
            return len(world.region_options or []) if self.page.picker_open else 0
        return 1 if await self.is_visible() else 0

    def nth(self, index: int):
        if self.selector == login.REGION_OPTION:
            return FakeRegionOption(self.page, index)
        assert self.selector == login.KEY_SELECTOR
        return FakeKey(self.page, index)

    async def inner_text(self, **_kw) -> str:
        assert self.selector == login.REGION_TRIGGER
        world = self.page.world
        return f"{world.selected_region or REGION_PLACEHOLDER}\n"

    async def click(self, **_kw):
        assert self.selector == login.REGION_TRIGGER, self.selector
        world = self.page.world
        if world.cookie_banner and not world.cookie_banner_dismissed:
            raise AssertionError("the cookie banner covers the picker")
        world.events.append("click:region-trigger")
        self.page.picker_open = True

    async def is_visible(self, **_kw) -> bool:
        page = self.page
        page.world.visible_queries.append(self.selector)
        if 'role="alert"' in self.selector and page.world.info_alert:
            return True  # a generic selector also matches informational alerts
        if self.selector == login.ERROR_SELECTOR:
            return page.error_visible
        if self.selector == login.SECURPASS_SELECTOR:
            return page.stage == "securpass" and page.world.after_valider == "securpass_text"
        if self.selector == COOKIE_BANNER_BUTTON:
            return page.world.cookie_banner and not page.world.cookie_banner_dismissed
        return False


class FakeRegionOption:
    def __init__(self, page: FakePage, index: int):
        self.page = page
        self.index = index

    async def inner_text(self, **_kw) -> str:
        return f"  {self.page.world.region_options[self.index]} "

    async def click(self, **_kw):
        world = self.page.world
        label = world.region_options[self.index]
        world.region_option_clicks.append(label)
        world.events.append(f"click:region:{label}")
        self.page.picker_open = False
        if world.region_select_works:
            world.selected_region = label


class FakeKey:
    def __init__(self, page: FakePage, index: int):
        self.page = page
        self.index = index

    async def evaluate(self, _expression):
        self.page.world.maybe_fail("evaluate")
        return self.page.world.key_css(self.index)

    async def click(self, **_kw):
        world = self.page.world
        world.key_clicks.append(self.index)  # the attempt counts, even if it then fails
        world.maybe_fail("key_click")
        await world.maybe_hang("key_click")
        world.typed.append(world.layout[self.index % 10])
        world.events.append(f"key:{self.index}")
        if world.reshuffle_after is not None and len(world.key_clicks) == world.reshuffle_after:
            world.layout = world.reshuffled_layout
