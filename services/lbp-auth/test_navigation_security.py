"""The bank origin is pinned: no navigation or request ever leaves it.

Covers the SSRF repro: a bank-supplied URL pointing at the instance-metadata
address (or any off-origin host) is refused before anything is sent, on every
navigation, every poll and every redirect hop. A full pending store rejects a
new login before any browser is opened. Synthetic fixtures only.
"""

import os
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import HTTPException
from playwright.async_api import Error as PlaywrightError, async_playwright
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


class FakeFrame:
    def __init__(self, parent=None):
        self.parent_frame = parent


MAIN_FRAME = FakeFrame(parent=None)


def subframe():
    return FakeFrame(parent=object())


class FakeBrowserRequest:
    def __init__(self, url, resource_type="document", frame=None):
        self.url = url
        self.resource_type = resource_type
        self.frame = frame


class FakeRoute:
    def __init__(self, url, resource_type="document", frame=None):
        self.request = FakeBrowserRequest(url, resource_type, frame)
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
        self.assertTrue(bank._is_bank_document_url(bank.BASE + "/wsost/OstBrokerWeb/auth"))
        self.assertTrue(
            bank._is_bank_document_url("https://voscomptesenligne.labanquepostale.fr:443/safe")
        )
        self.assertTrue(bank._is_allowed_url(bank.BASE + "/wsost/OstBrokerWeb/auth"))

    def test_bank_subdomains_are_documents_but_cloudfront_is_not(self):
        # Observed live: the bank serves keypad scripts and anti-fraud
        # payloads from its own subdomains and two pinned CloudFront
        # distributions; blocking the subdomains breaks the login, but a
        # distribution must never be a Document (anyone can register one).
        self.assertTrue(
            bank._is_bank_document_url("https://transverse.labanquepostale.fr/x.js")
        )
        self.assertTrue(bank._is_allowed_url("https://transverse.labanquepostale.fr/x.js"))
        for host in sorted(bank.PINNED_CDN_HOSTS):
            with self.subTest(host=host):
                self.assertFalse(bank._is_bank_document_url(f"https://{host}/app.js"))
                self.assertTrue(bank._is_allowed_url(f"https://{host}/app.js"))
        self.assertTrue(
            bank._is_allowed_url("https://d2ydsn9mah1r4u.cloudfront.net:443/x.css")
        )
        self.assertFalse(
            bank._is_bank_document_url("https://d2ydsn9mah1r4u.cloudfront.net:443/x.css")
        )

    def test_an_attacker_cloudfront_distribution_is_refused_everywhere(self):
        target = "https://attacker.cloudfront.net/steal?x=1"
        self.assertFalse(bank._is_bank_document_url(target))
        self.assertFalse(bank._is_allowed_url(target))
        with self.assertRaises(HTTPException):
            bank._require_allowed_url(target)

    def test_hostname_labels_are_hardened(self):
        # Apex-as-label, empty labels and a trailing dot are refused even on
        # the pinned suffix; uppercase stays allowed via casefold.
        for target in (
            "https://.cloudfront.net/",
            "https://a..cloudfront.net/x.js",
            "https://dqnjn206bwvk2.cloudfront.net./x.js",
            "https://transverse.labanquepostale.fr./x.js",
        ):
            with self.subTest(target=target):
                self.assertFalse(bank._is_bank_document_url(target))
                self.assertFalse(bank._is_allowed_url(target))
        self.assertTrue(
            bank._is_allowed_url("https://DQNJN206BWVK2.CLOUDFRONT.NET/app.js")
        )
        self.assertTrue(
            bank._is_bank_document_url("https://TRANSVERSE.LABANQUEPOSTALE.FR/x.js")
        )

    def test_anything_off_origin_is_refused(self):
        for target in EVIL_HOSTS:
            with self.subTest(target=target):
                self.assertFalse(bank._is_bank_document_url(target))
                self.assertFalse(bank._is_allowed_url(target))
        for target in (
            "",
            "/relative/path",
            "https://user@voscomptesenligne.labanquepostale.fr/",
            # Suffix lookalikes are neither the bank nor a pinned CDN host.
            "https://labanquepostale.fr.evil.example/x.js",
            "https://evillabanquepostale.fr/x.js",
            "https://cloudfront.net.evil.example/x.js",
            "https://evilcloudfront.net/x.js",
            "https://attacker.cloudfront.net/steal",
            "https://user@dqnjn206bwvk2.cloudfront.net/x.js",
            "https://dqnjn206bwvk2.cloudfront.net:8443/x.js",
            "http://dqnjn206bwvk2.cloudfront.net/x.js",
        ):
            with self.subTest(target=target):
                self.assertFalse(bank._is_bank_document_url(target))
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

    async def test_goto_refuses_an_attacker_cloudfront_url(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as raised:
            await bank.BrowserBankClient._goto(page, "https://attacker.cloudfront.net/steal")
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(page.gotos, [])

    async def test_a_cloudfront_retour_is_never_opened(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as raised:
            await bank.BrowserBankClient._follow_continue(
                page, notice_url("https://attacker.cloudfront.net/steal")
            )
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


class EvilCloudfrontBank(test_bank.FakeBank):
    """Every statement read bounces to an attacker CloudFront distribution."""

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path in (bank.RELEVE_CCP, bank.RELEVE_CNE, bank.FRAME_CNE):
            return httpx.Response(
                302, headers={"location": "https://attacker.cloudfront.net/steal"}
            )
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
    # Two-rule model: httpx replays the cookie jar on server-rendered pages,
    # so every request it sends must be a bank-owned Document URL — never a
    # CDN host, never metadata, never anything off-origin.
    for url in sent:
        assert bank._is_bank_document_url(url), url
        assert "169.254" not in url, url
        assert "cloudfront" not in url, url


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

    async def test_a_cloudfront_redirect_hop_is_never_followed(self):
        client, sent = recording_client(EvilCloudfrontBank())
        state = await self.login(client)
        with self.assertRaises(HTTPException) as raised:
            await client.accounts(state)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")
        assert_nothing_off_origin(sent)
        self.assertTrue(all("cloudfront" not in url for url in sent))

    async def test_a_login_redirect_off_origin_fails_closed_without_a_fetch(self):
        client, sent = recording_client(EvilAuthBank())
        initiated = await client.initiate(CUSTOMER)
        with self.assertRaises(HTTPException) as raised:
            await client.keypad(initiated["processId"], POSITIONS)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        assert_nothing_off_origin(sent)

    async def test_httpx_hook_refuses_attacker_cloudfront(self):
        request = httpx.Request("GET", "https://attacker.cloudfront.net/steal")
        with self.assertRaises(HTTPException) as raised:
            await bank._check_request_origin(request)
        self.assertEqual(raised.exception.status_code, 502)

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
        subdomain = FakeRoute("https://transverse.labanquepostale.fr/x.js")
        await guard(subdomain)
        self.assertEqual(subdomain.action, "continue")
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
        for target in (
            "wss://attacker.example/socket",
            "ws://voscomptesenligne.labanquepostale.fr/socket",
            "wss://attacker.cloudfront.net/socket",
            "wss://dqnjn206bwvk2.cloudfront.net/socket",
        ):
            with self.subTest(target=target):
                refused = FakeSocket(target)
                await ws_guard(refused)
                self.assertEqual(refused.action, "close")

    async def test_pinned_cdn_is_subresource_only(self):
        guard = bank._route_guard()
        for host in sorted(bank.PINNED_CDN_HOSTS):
            with self.subTest(host=host):
                for resource_type in ("script", "image", "stylesheet", "xhr", "fetch"):
                    route = FakeRoute(f"https://{host}/app.js", resource_type=resource_type)
                    await guard(route)
                    self.assertEqual(route.action, "continue")
                document = FakeRoute(f"https://{host}/app.js", resource_type="document")
                await guard(document)
                self.assertEqual(document.action, "abort")
        attacker_script = FakeRoute(
            "https://attacker.cloudfront.net/steal.js", resource_type="script"
        )
        # Rotations forced the tradeoff: a shape-valid distribution the bank
        # page itself requests is auto-admitted (it may be the bank's new
        # host) — for scripts and subframes, never for a top-level document
        # navigation. The pure allow-list, the retour/navigation guards and
        # the httpx hook still refuse it — see
        # test_an_attacker_cloudfront_distribution_is_refused_everywhere.
        await guard(attacker_script)
        self.assertEqual(attacker_script.action, "continue")
        attacker_document = FakeRoute(
            "https://attacker.cloudfront.net/steal", resource_type="document"
        )
        await guard(attacker_document)
        self.assertEqual(attacker_document.action, "abort")

    async def test_cdp_resource_type_branches_document_from_subresource(self):
        # Exercise the same branching the CDP Fetch guard uses
        # (`event["resourceType"]`): Document hops use the bank-only rule,
        # subresources additionally allow the pinned CDN hosts.
        cases = [
            ("https://transverse.labanquepostale.fr/x.js", "Document", True),
            ("https://transverse.labanquepostale.fr/x.js", "Script", True),
            (f"https://{sorted(bank.PINNED_CDN_HOSTS)[0]}/app.js", "Script", True),
            (f"https://{sorted(bank.PINNED_CDN_HOSTS)[0]}/app.js", "Image", True),
            (f"https://{sorted(bank.PINNED_CDN_HOSTS)[1]}/x.css", "Stylesheet", True),
            (f"https://{sorted(bank.PINNED_CDN_HOSTS)[0]}/app.js", "Document", False),
            ("https://attacker.cloudfront.net/steal.js", "Script", False),
            ("https://attacker.cloudfront.net/steal", "Document", False),
        ]
        for url, resource_type, expected in cases:
            with self.subTest(url=url, resource_type=resource_type):
                allowed = (
                    bank._is_bank_document_url(url)
                    if resource_type == "Document"
                    else bank._is_allowed_url(url)
                )
                self.assertEqual(allowed, expected)


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


@unittest.skipUnless(os.environ.get("LBP_BROWSER_TESTS") == "1", "requires pinned Chromium")
class BrowserRedirectHopTest(unittest.IsolatedAsyncioTestCase):
    """A bank-origin 302 to the metadata address sends nothing.

    Real Chromium + local HTTPS server, same harness as corum-auth's
    `BrowserOriginSecurityTest`. `context.route` only sees the first hop, so
    without the CDP Fetch guard in `bank._new_guarded_page` Chromium follows
    the redirect and the sink records a hit. With the guard the redirected hop
    fails with ERR_BLOCKED_BY_CLIENT and the sink records zero hits.
    """

    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory()
        cert, key = (str(Path(cls.scratch.name) / name) for name in ("cert.pem", "key.pem"))
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-days", "1", "-subj", "/CN=localhost", "-keyout", key, "-out", cert],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.meta_hits: list = []

        class MetaSink(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def _answer(self):
                cls.meta_hits.append((self.command, self.path))
                body = b"metadata"
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                self._answer()

            def do_POST(self):
                self._answer()

        cls.meta = ThreadingHTTPServer(("127.0.0.1", 0), MetaSink)
        cls.meta_thread = threading.Thread(target=cls.meta.serve_forever, daemon=True)
        cls.meta_thread.start()
        cls.meta_base = "http://127.0.0.1:" + str(cls.meta.server_port)

        cls.bank_hits: list = []

        class BankFixture(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def _answer(self):
                cls.bank_hits.append((self.command, self.path))
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", cls.meta_base + "/latest/meta-data/")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                else:
                    body = b"<html><body>fixture</body></html>"
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

            def do_GET(self):
                self._answer()

            def do_POST(self):
                self._answer()

        cls.bank = ThreadingHTTPServer(("127.0.0.1", 0), BankFixture)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        # Test-only local server: pin the floor so no legacy version is offered.
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(cert, key)
        cls.bank.socket = tls.wrap_socket(cls.bank.socket, server_side=True)
        cls.origin = "https://127.0.0.1:" + str(cls.bank.server_port)
        cls.bank_thread = threading.Thread(target=cls.bank.serve_forever, daemon=True)
        cls.bank_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.bank.shutdown()
        cls.bank.server_close()
        cls.bank_thread.join()
        cls.meta.shutdown()
        cls.meta.server_close()
        cls.meta_thread.join()
        cls.scratch.cleanup()

    async def asyncSetUp(self):
        self.bank_hits.clear()
        self.meta_hits.clear()
        # The guard pins the production host; treat the local TLS fixture as
        # the bank origin so the first hop is allowed and only the redirected
        # metadata hop is under test. Anything else still goes through the
        # real allow-list (both the document and the asset rule).
        real_is_bank_document = bank._is_bank_document_url
        real_is_allowed = bank._is_allowed_url
        origin = self.origin

        def fake_is_bank_document(url):
            if isinstance(url, str) and url.startswith(origin + "/"):
                return True
            return real_is_bank_document(url)

        def fake_is_allowed(url, extra=None):
            if isinstance(url, str) and url.startswith(origin + "/"):
                return True
            return real_is_allowed(url, extra)

        self.document_patch = patch.object(
            bank, "_is_bank_document_url", fake_is_bank_document
        )
        self.document_patch.start()
        self.allow_patch = patch.object(bank, "_is_allowed_url", fake_is_allowed)
        self.allow_patch.start()
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True, args=["--no-sandbox"])
        self.context = await self.browser.new_context(ignore_https_errors=True)
        await self.context.route("**/*", bank._route_guard())
        await self.context.route_web_socket("**/*", bank._websocket_guard())
        self.page = await bank._new_guarded_page(self.context)
        self.page.set_default_timeout(5000)

    async def asyncTearDown(self):
        await self.context.close()
        await self.browser.close()
        await self.playwright.stop()
        self.allow_patch.stop()
        self.document_patch.stop()

    async def test_a_bank_origin_redirect_to_metadata_sends_nothing(self):
        with self.assertRaises(PlaywrightError) as raised:
            await self.page.goto(self.origin + "/redirect")
        self.assertIn("BLOCKED_BY_CLIENT", str(raised.exception))
        self.assertIn(("GET", "/redirect"), self.bank_hits)
        self.assertEqual(self.meta_hits, [])


class CdnLearningTest(unittest.TestCase):
    def test_learns_the_distributions_the_bank_page_references(self):
        html = (
            "<html><head>"
            '<script src="https://d21j9nkdg2p3wo.cloudfront.net/assets/app.js"></script>'
            '<link href="https://DQNJN206BWVK2.CLOUDFRONT.NET/x.css" rel="stylesheet">'
            '<script src="https://transverse.labanquepostale.fr/x.js"></script>'
            '<script src="https://attacker.example/steal.js"></script>'
            '<script>var u="https://a..cloudfront.net/x.js";</script>'
            "</head><body>fixture</body></html>"
        )
        self.assertEqual(
            bank._learn_cdn_hosts(html),
            {"d21j9nkdg2p3wo.cloudfront.net", "dqnjn206bwvk2.cloudfront.net"},
        )

    def test_learning_ignores_non_pages_and_caps_the_set(self):
        self.assertEqual(bank._learn_cdn_hosts(None), set())
        self.assertEqual(bank._learn_cdn_hosts(123), set())
        many = "".join(
            f'<script src="https://host{i:04d}.cloudfront.net/a.js"></script>'
            for i in range(bank.MAX_LEARNED_CDN_HOSTS + 5)
        )
        self.assertEqual(len(bank._learn_cdn_hosts(many)), bank.MAX_LEARNED_CDN_HOSTS)

    def test_learning_catches_every_url_spelling(self):
        html = (
            "<html><head>"
            '<script src="https://d01aaa11111111.cloudfront.net/a.js"></script>'
            '<script>var u="//d02bbb22222222.cloudfront.net/b.js";</script>'
            '<script type="application/json">{"u":"https:\\/\\/d03ccc33333333.cloudfront.net\\/c.js"}</script>'
            "<script>register('d04ddd44444444.cloudfront.net');</script>"
            "</head></html>"
        )
        self.assertEqual(
            bank._learn_cdn_hosts(html),
            {
                "d01aaa11111111.cloudfront.net",
                "d02bbb22222222.cloudfront.net",
                "d03ccc33333333.cloudfront.net",
                "d04ddd44444444.cloudfront.net",
            },
        )

    def test_parse_cdn_hosts_keeps_only_shape_valid_hosts(self):
        self.assertEqual(
            bank._parse_cdn_hosts("d21j9nkdg2p3wo.cloudfront.net, DQNJN206BWVK2.cloudfront.net "),
            {"d21j9nkdg2p3wo.cloudfront.net", "dqnjn206bwvk2.cloudfront.net"},
        )
        self.assertEqual(
            bank._parse_cdn_hosts(
                "attacker.example, https://d1.cloudfront.net/x.js,"
                " a..cloudfront.net, , not a host, https://.cloudfront.net/"
            ),
            set(),
        )
        self.assertEqual(bank._parse_cdn_hosts(""), set())
        self.assertEqual(bank._parse_cdn_hosts(None), set())

    def test_env_hosts_are_subresources_only(self):
        with patch.dict(
            os.environ, {bank.LBP_EXTRA_CDN_HOSTS_ENV: "d21j9nkdg2p3wo.cloudfront.net"}
        ):
            self.assertEqual(
                bank._env_cdn_hosts(), {"d21j9nkdg2p3wo.cloudfront.net"}
            )
            target = "https://d21j9nkdg2p3wo.cloudfront.net/app.js"
            self.assertTrue(bank._is_allowed_url(target))
            self.assertFalse(bank._is_bank_document_url(target))
        self.assertEqual(bank._env_cdn_hosts(), set())

    def test_an_attacker_distribution_is_still_refused_with_env_set(self):
        with patch.dict(
            os.environ, {bank.LBP_EXTRA_CDN_HOSTS_ENV: "d21j9nkdg2p3wo.cloudfront.net"}
        ):
            target = "https://attacker.cloudfront.net/steal.js"
            self.assertFalse(bank._is_allowed_url(target))
            with self.assertRaises(HTTPException):
                bank._require_allowed_url(target)


class LearnedHostGuardTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_learned_host_is_subresource_only(self):
        learned = {"d21j9nkdg2p3wo.cloudfront.net"}
        guard = bank._route_guard(learned)
        script = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/app.js", resource_type="script"
        )
        await guard(script)
        self.assertEqual(script.action, "continue")
        document = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/app.js", resource_type="document"
        )
        await guard(document)
        self.assertEqual(document.action, "abort")
        unknown = FakeRoute("https://unknown.example/a.js", resource_type="script")
        await guard(unknown)
        self.assertEqual(unknown.action, "abort")

    async def test_blocked_requests_log_the_resource_type(self):
        guard = bank._route_guard()
        route = FakeRoute("https://attacker.example/steal.js", resource_type="script")
        with self.assertLogs("lbp-auth", level="INFO") as logs:
            await guard(route)
        self.assertEqual(route.action, "abort")
        self.assertTrue(any("resource=script" in line for line in logs.output))


class LearnFromPageTest(unittest.IsolatedAsyncioTestCase):
    async def test_learn_page_hosts_updates_the_shared_set(self):
        class ContentPage:
            def __init__(self, html):
                self.html = html

            async def content(self):
                return self.html

        learned: set[str] = set()
        html = '<script src="https://d21j9nkdg2p3wo.cloudfront.net/a.js"></script>'
        new = await bank.BrowserBankClient._learn_page_cdn_hosts(ContentPage(html), learned)
        self.assertEqual(new, {"d21j9nkdg2p3wo.cloudfront.net"})
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})
        # A second visit learns nothing new.
        again = await bank.BrowserBankClient._learn_page_cdn_hosts(ContentPage(html), learned)
        self.assertEqual(again, set())

    async def test_unreadable_page_learns_nothing(self):
        class BrokenPage:
            async def content(self):
                raise RuntimeError("gone")

        learned: set[str] = set()
        self.assertEqual(
            await bank.BrowserBankClient._learn_page_cdn_hosts(BrokenPage(), learned),
            set(),
        )
        self.assertEqual(learned, set())


class AutoAdmitGuardTest(unittest.IsolatedAsyncioTestCase):
    async def test_first_unknown_distribution_is_admitted_then_left_through(self):
        learned: set[str] = set()
        guard = bank._route_guard(learned)
        first = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/app.js", resource_type="script"
        )
        with self.assertLogs("lbp-auth", level="INFO") as logs:
            await guard(first)
        self.assertEqual(first.action, "continue")
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})
        self.assertTrue(any("auto-admitted" in line for line in logs.output))
        # The next request to the same host passes without a new admission.
        second = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/other.js", resource_type="script"
        )
        with self.assertNoLogs("lbp-auth", level="INFO"):
            await guard(second)
        self.assertEqual(second.action, "continue")

    async def test_documents_never_auto_admit(self):
        learned: set[str] = set()
        guard = bank._route_guard(learned)
        document = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/app.js", resource_type="document"
        )
        await guard(document)
        self.assertEqual(document.action, "abort")
        self.assertEqual(learned, set())

    async def test_non_cloudfront_hosts_never_auto_admit(self):
        learned: set[str] = set()
        guard = bank._route_guard(learned)
        for target in (
            "https://attacker.example/steal.js",
            "https://attacker.cloudfront.net.evil.example/steal.js",
            "https://a..cloudfront.net/steal.js",
            "http://d21j9nkdg2p3wo.cloudfront.net/steal.js",
        ):
            with self.subTest(target=target):
                route = FakeRoute(target, resource_type="script")
                await guard(route)
                self.assertEqual(route.action, "abort")
        self.assertEqual(learned, set())

    async def test_admissions_are_capped(self):
        learned = {f"host{i:04d}.cloudfront.net" for i in range(bank.MAX_LEARNED_CDN_HOSTS)}
        guard = bank._route_guard(learned)
        route = FakeRoute("https://d21j9nkdg2p3wo.cloudfront.net/a.js", resource_type="script")
        await guard(route)
        self.assertEqual(route.action, "abort")
        self.assertNotIn("d21j9nkdg2p3wo.cloudfront.net", learned)

    async def test_subframe_document_to_an_admitted_host_continues(self):
        learned = {"d21j9nkdg2p3wo.cloudfront.net"}
        guard = bank._route_guard(learned)
        route = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/fraud.html",
            resource_type="document",
            frame=subframe(),
        )
        await guard(route)
        self.assertEqual(route.action, "continue")

    async def test_top_document_to_an_admitted_host_aborts(self):
        learned = {"d21j9nkdg2p3wo.cloudfront.net"}
        guard = bank._route_guard(learned)
        route = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/fraud.html",
            resource_type="document",
            frame=MAIN_FRAME,
        )
        await guard(route)
        self.assertEqual(route.action, "abort")

    async def test_subframe_document_to_a_new_distribution_auto_admits(self):
        learned: set[str] = set()
        guard = bank._route_guard(learned)
        route = FakeRoute(
            "https://d21j9nkdg2p3wo.cloudfront.net/fraud.html",
            resource_type="document",
            frame=subframe(),
        )
        await guard(route)
        self.assertEqual(route.action, "continue")
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})

    async def test_subframe_document_off_cloudfront_aborts(self):
        learned: set[str] = set()
        guard = bank._route_guard(learned)
        route = FakeRoute(
            "https://attacker.example/fraud.html",
            resource_type="document",
            frame=subframe(),
        )
        await guard(route)
        self.assertEqual(route.action, "abort")
        self.assertEqual(learned, set())

    async def test_cdp_hop_to_a_new_distribution_continues(self):
        learned: set[str] = set()
        context = FakeGuardedContext()
        await bank._new_guarded_page(context, learned)
        event = {
            "requestId": "1",
            "frameId": "MAIN",
            "request": {"url": "https://d21j9nkdg2p3wo.cloudfront.net/app.js"},
            "resourceType": "Script",
        }
        await context.session.handlers["Fetch.requestPaused"](event)
        methods = [method for method, _ in context.session.sent if method not in ("Fetch.enable", "Page.getFrameTree")]
        self.assertEqual(methods, ["Fetch.continueRequest"])
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})

    async def test_cdp_document_hop_to_a_new_distribution_fails(self):
        learned: set[str] = set()
        context = FakeGuardedContext()
        await bank._new_guarded_page(context, learned)
        event = {
            "requestId": "1",
            "frameId": "MAIN",
            "request": {"url": "https://d21j9nkdg2p3wo.cloudfront.net/app"},
            "resourceType": "Document",
        }
        await context.session.handlers["Fetch.requestPaused"](event)
        methods = [method for method, _ in context.session.sent if method not in ("Fetch.enable", "Page.getFrameTree")]
        self.assertEqual(methods, ["Fetch.failRequest"])
        self.assertEqual(learned, set())

    async def test_cdp_subframe_document_to_a_new_distribution_continues(self):
        learned: set[str] = set()
        context = FakeGuardedContext()
        await bank._new_guarded_page(context, learned)
        event = {
            "requestId": "7",
            "frameId": "SUBFRAME",
            "request": {"url": "https://d21j9nkdg2p3wo.cloudfront.net/fraud.html"},
            "resourceType": "Document",
        }
        await context.session.handlers["Fetch.requestPaused"](event)
        methods = [method for method, _ in context.session.sent if method not in ("Fetch.enable", "Page.getFrameTree")]
        self.assertEqual(methods, ["Fetch.continueRequest"])
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})

    async def test_cdp_subframe_document_off_cloudfront_fails(self):
        learned: set[str] = set()
        context = FakeGuardedContext()
        await bank._new_guarded_page(context, learned)
        event = {
            "requestId": "7",
            "frameId": "SUBFRAME",
            "request": {"url": "https://attacker.example/fraud.html"},
            "resourceType": "Document",
        }
        await context.session.handlers["Fetch.requestPaused"](event)
        methods = [method for method, _ in context.session.sent if method not in ("Fetch.enable", "Page.getFrameTree")]
        self.assertEqual(methods, ["Fetch.failRequest"])
        self.assertEqual(learned, set())


class AutoAdmitPolicyTest(unittest.TestCase):
    def test_top_level_documents_never_auto_admit(self):
        for resource in ("document", "Document"):
            with self.subTest(resource=resource):
                learned: set[str] = set()
                self.assertFalse(
                    bank._auto_admit_cdn_host(
                        learned, "https://d21j9nkdg2p3wo.cloudfront.net/a", resource, True
                    )
                )
                self.assertEqual(learned, set())

    def test_main_frame_scripts_auto_admit(self):
        learned: set[str] = set()
        self.assertTrue(
            bank._auto_admit_cdn_host(
                learned, "https://d21j9nkdg2p3wo.cloudfront.net/a.js", "Script", True
            )
        )
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})

    def test_subframe_documents_auto_admit(self):
        learned: set[str] = set()
        self.assertTrue(
            bank._auto_admit_cdn_host(
                learned, "https://d21j9nkdg2p3wo.cloudfront.net/a", "Document", False
            )
        )
        self.assertEqual(learned, {"d21j9nkdg2p3wo.cloudfront.net"})


class FakeCDPSession:
    def __init__(self):
        self.handlers: dict[str, Any] = {}
        self.sent: list[tuple[str, dict[str, Any]]] = []

    def on(self, event, handler):
        self.handlers[event] = handler

    async def send(self, method, params):
        self.sent.append((method, params))
        if method == "Page.getFrameTree":
            return {"frameTree": {"frame": {"id": "MAIN"}}}
        return {}


class FakeGuardedContext:
    def __init__(self):
        self.session = FakeCDPSession()

    async def new_page(self):
        return object()

    async def new_cdp_session(self, page):
        return self.session


if __name__ == "__main__":
    unittest.main()
