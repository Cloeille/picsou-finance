"""The bank origin is pinned: no navigation or request ever leaves it.

Covers the SSRF repro: a bank-supplied URL pointing at the instance-metadata
address (or any off-origin host) is refused before anything is sent, on every
navigation, every poll and every redirect hop. A full pending store rejects a
new login before any browser is opened. Synthetic fixtures only.
"""

import time
import unittest
from urllib.parse import quote

import httpx
from fastapi import HTTPException
from unittest.mock import patch

import bank
import fixtures
import test_bank

CUSTOMER = "1234567890"
POSITIONS = [1, 0, 2, 3, 4, 5]
EVIL_METADATA = "http://169.254.169.254/latest/meta-data/iam/security-credentials/"
EVIL_HOSTS = (
    "http://169.254.169.254/latest/meta-data/",
    "https://attacker.example/steal",
    "http://voscomptesenligne.labanquepostale.fr/steal",
    "http://127.0.0.1/steal",
    "https://192.168.1.1/steal",
    "https://[::1]/steal",
    "https://voscomptesenligne.labanquepostale.fr:444/steal",
    "https://user:secret@voscomptesenligne.labanquepostale.fr/steal",
    "//attacker.example/steal",
)

EVIL_CHALLENGE_HTML = """
<html><head><title>fixture</title></head><body>
<script>
var PPH = "http://169.254.169.254";
var URL_AF_POLLING = "/latest/meta-data/iam/security-credentials/";
</script>
</body></html>
"""


def notice_url(retour: str) -> str:
    return (
        "https://voscomptesenligne.labanquepostale.fr/wsost/OstBrokerWeb/message.html"
        "?param=AuthentInfoAF&url_retour=" + quote(retour, safe="")
    )


class FakePage:
    """A page whose navigations are recorded, never performed."""

    def __init__(self):
        self.gotos: list[str] = []

    async def wait_for_selector(self, selector, timeout=None):
        raise Exception("no Continuer button")

    async def click(self, selector, timeout=None):  # pragma: no cover
        raise AssertionError("must not click when the button is missing")

    async def goto(self, url, **kwargs):
        self.gotos.append(url)


class FakeBrowserRequest:
    def __init__(self, url):
        self.url = url


class FakeRoute:
    def __init__(self, url):
        self.request = FakeBrowserRequest(url)
        self.action = None

    async def continue_(self):
        self.action = "continue"

    async def abort(self):
        self.action = "abort"


class FakeSocket:
    def __init__(self, url):
        self.url = url
        self.action = None

    def connect_to_server(self):
        self.action = "connect"

    async def close(self):
        self.action = "close"


class AllowedOriginTest(unittest.TestCase):
    def test_the_bank_origin_is_allowed(self):
        self.assertTrue(bank._is_allowed_url(bank.BASE + "/wsost/OstBrokerWeb/auth"))
        self.assertTrue(bank._is_allowed_url("https://voscomptesenligne.labanquepostale.fr:443/safe"))

    def test_anything_off_origin_is_refused(self):
        for target in EVIL_HOSTS:
            with self.subTest(target=target):
                self.assertFalse(bank._is_allowed_url(target))
        for target in ("", "/relative/path", "https://user@voscomptesenligne.labanquepostale.fr/"):
            with self.subTest(target=target):
                self.assertFalse(bank._is_allowed_url(target))

    def test_require_resolves_relative_links_and_refuses_off_origin(self):
        self.assertEqual(
            bank._require_allowed_url("/wsost/OstBrokerWeb/loginform?cu=attenteAF"),
            "https://voscomptesenligne.labanquepostale.fr/wsost/OstBrokerWeb/loginform?cu=attenteAF",
        )
        for target in EVIL_HOSTS:
            with self.subTest(target=target):
                with self.assertRaises(HTTPException) as raised:
                    bank._require_allowed_url(target)
                self.assertEqual(raised.exception.status_code, 502)
                self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")


class NavigationGuardTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_bank_supplied_retour_off_origin_is_never_opened(self):
        for retour in (EVIL_METADATA, "https://attacker.example/steal"):
            with self.subTest(retour=retour):
                page = FakePage()
                with self.assertRaises(HTTPException) as raised:
                    await bank.BrowserBankClient._follow_continue(page, notice_url(retour))
                self.assertEqual(raised.exception.status_code, 502)
                self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")
                self.assertEqual(page.gotos, [])

    async def test_a_same_origin_retour_still_opens(self):
        page = FakePage()
        await bank.BrowserBankClient._follow_continue(
            page, notice_url("/wsost/OstBrokerWeb/loginform?TAM_OP=login&cu=attenteAF")
        )
        self.assertEqual(
            page.gotos,
            [
                "https://voscomptesenligne.labanquepostale.fr"
                "/wsost/OstBrokerWeb/loginform?TAM_OP=login&cu=attenteAF"
            ],
        )

    async def test_goto_refuses_an_off_origin_url(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as raised:
            await bank.BrowserBankClient._goto(page, EVIL_METADATA)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(page.gotos, [])


class EvilPollBank(test_bank.FakeBank):
    """The challenge page carries a metadata-address polling host."""

    def __call__(self, request: httpx.Request) -> httpx.Response:
        params = request.url.params
        if (
            request.url.path == "/wsost/OstBrokerWeb/loginform"
            and "cu" in params
            and "origin" in params
        ):
            return httpx.Response(200, text=EVIL_CHALLENGE_HTML)
        return super().__call__(request)


class EvilRedirectBank(test_bank.FakeBank):
    """Every statement read bounces to the metadata address."""

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path in (bank.RELEVE_CCP, bank.RELEVE_CNE, bank.FRAME_CNE):
            return httpx.Response(302, headers={"location": EVIL_METADATA})
        return super().__call__(request)


class LoopBank(test_bank.FakeBank):
    """Every statement read redirects to itself: the chain must be capped."""
    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path in (bank.RELEVE_CCP, bank.RELEVE_CNE, bank.FRAME_CNE):
            return httpx.Response(302, headers={"location": str(request.url)})
        return super().__call__(request)


class EvilAuthBank(test_bank.FakeBank):
    """The login POST itself redirects to the metadata address."""

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/wsost/OstBrokerWeb/auth":
            return httpx.Response(302, headers={"location": EVIL_METADATA})
        return super().__call__(request)


def recording_client(fake) -> tuple[bank.HttpxBankClient, list[str]]:
    sent: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        sent.append(str(request.url))
        return fake(request)

    return bank.HttpxBankClient(transport=httpx.MockTransport(handler)), sent


def assert_nothing_off_origin(sent: list[str]) -> None:
    for url in sent:
        parsed = httpx.URL(url)
        assert parsed.scheme == "https", url
        assert parsed.host == bank.ALLOWED_HOST, url
        assert "169.254" not in url, url


class PollGuardTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_metadata_poll_url_is_never_requested(self):
        client, sent = recording_client(EvilPollBank(mode="two_factor"))
        initiated = await client.initiate(CUSTOMER)
        await client.keypad(initiated["processId"], POSITIONS)
        with self.assertRaises(HTTPException) as raised:
            await client.complete(initiated["processId"])
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")
        assert_nothing_off_origin(sent)


class RedirectGuardTest(unittest.IsolatedAsyncioTestCase):
    async def login(self, client):
        initiated = await client.initiate(CUSTOMER)
        await client.keypad(initiated["processId"], POSITIONS)
        return await client.complete(initiated["processId"])

    async def test_a_metadata_redirect_hop_is_never_followed(self):
        client, sent = recording_client(EvilRedirectBank())
        state = await self.login(client)
        with self.assertRaises(HTTPException) as raised:
            await client.accounts(state)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")
        assert_nothing_off_origin(sent)

    async def test_a_login_redirect_off_origin_fails_closed_without_a_fetch(self):
        client, sent = recording_client(EvilAuthBank())
        initiated = await client.initiate(CUSTOMER)
        with self.assertRaises(HTTPException) as raised:
            await client.keypad(initiated["processId"], POSITIONS)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        assert_nothing_off_origin(sent)

    async def test_a_redirect_loop_is_bounded(self):
        client, sent = recording_client(LoopBank())
        state = await self.login(client)
        before = len(sent)
        with self.assertRaises(HTTPException) as raised:
            await client.accounts(state)
        self.assertEqual(raised.exception.status_code, 502)
        assert_nothing_off_origin(sent)
        # Two tries (the read is retried once) of two syntheses plus an
        # initial request with at most MAX_REDIRECTS followed hops each.
        self.assertLessEqual(len(sent) - before, 2 * (2 + 1 + bank.MAX_REDIRECTS))


class RouteGuardTest(unittest.IsolatedAsyncioTestCase):
    async def test_requests_and_sockets_stay_on_the_bank_origin(self):
        guard = bank._route_guard()
        allowed = FakeRoute(bank.BASE + "/voscomptes/canalXHTML/identif.ea?origin=particuliers")
        await guard(allowed)
        self.assertEqual(allowed.action, "continue")
        for target in EVIL_HOSTS:
            with self.subTest(target=target):
                route = FakeRoute(target)
                await guard(route)
                self.assertEqual(route.action, "abort")
        data = FakeRoute("data:image/png;base64,fixture")
        await guard(data)
        self.assertEqual(data.action, "continue")

        ws_guard = bank._websocket_guard()
        socket = FakeSocket("wss://voscomptesenligne.labanquepostale.fr/socket")
        await ws_guard(socket)
        self.assertEqual(socket.action, "connect")
        for target in ("wss://attacker.example/socket", "ws://voscomptesenligne.labanquepostale.fr/socket"):
            with self.subTest(target=target):
                refused = FakeSocket(target)
                await ws_guard(refused)
                self.assertEqual(refused.action, "close")


class BrowserCapacityTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_full_store_rejects_without_launching_a_browser(self):
        client = bank.BrowserBankClient()
        for index in range(bank.MAX_PENDING):
            await client.pending.put_or_raise(f"proc-{index}", {"created_at": time.time()})
        with patch.object(bank, "async_playwright") as factory:
            with self.assertRaises(HTTPException) as raised:
                await client.initiate(CUSTOMER)
        self.assertEqual(raised.exception.status_code, 503)
        self.assertEqual(raised.exception.detail, "TOO_MANY_PENDING")
        factory.assert_not_called()


if __name__ == "__main__":
    unittest.main()
