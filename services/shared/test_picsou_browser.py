"""Shared browser client. One opt-in end-to-end test speaks to a real browser-service."""

import os
import socket
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import AsyncMock, MagicMock, patch

import httpx

from picsou_browser import (
    BrowserCapacityError,
    BrowserLease,
    BrowserUnavailableError,
    launch_chromium,
    websocket_url,
)


class WebsocketUrlTest(unittest.TestCase):
    def test_http_and_https_map_to_ws_and_wss(self):
        self.assertEqual(
            websocket_url("http://browser-service:8001", "/sessions/abc/cdp"),
            "ws://browser-service:8001/sessions/abc/cdp",
        )
        self.assertEqual(
            websocket_url("https://browser.example:8443/", "/sessions/abc/cdp"),
            "wss://browser.example:8443/sessions/abc/cdp",
        )


class _Response:
    def __init__(self, status: int, body: dict | None = None) -> None:
        self.status_code = status
        self._body = body or {}

    def json(self):
        return self._body


class _Client:
    def __init__(self, factory: "_Factory") -> None:
        self.factory = factory

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    async def post(self, url, json=None, headers=None):
        self.factory.posts.append((url, json, headers))
        if self.factory.post_error is not None:
            raise self.factory.post_error
        return _Response(self.factory.post_status, self.factory.post_body)

    async def delete(self, url, headers=None):
        self.factory.deletes.append((url, headers))
        if self.factory.delete_error is not None:
            raise self.factory.delete_error
        return _Response(204)


class _Factory:
    def __init__(self) -> None:
        self.posts: list = []
        self.deletes: list = []
        self.post_status = 201
        self.post_body = {
            "sessionId": "sess-1",
            "wsPath": "/sessions/sess-1/cdp",
            "token": "token-1",
            "expiresAt": "2026-10-18T00:00:00+00:00",
        }
        self.post_error = None
        self.delete_error = None

    def __call__(self, *_args, **_kwargs):
        return _Client(self)


class LocalLaunchTest(unittest.IsolatedAsyncioTestCase):
    async def test_without_a_service_url_the_local_launch_keeps_the_same_args(self):
        browser = MagicMock()
        pw = MagicMock()
        pw.chromium.launch = AsyncMock(return_value=browser)
        env = os.environ.copy()
        env.pop("BROWSER_SERVICE_URL", None)
        with patch.dict(os.environ, env, clear=True):
            lease = await launch_chromium(
                pw, args=["--disable-blink-features=AutomationControlled"], headless=True
            )
        pw.chromium.launch.assert_awaited_once_with(
            headless=True, args=["--disable-blink-features=AutomationControlled"]
        )
        self.assertIs(lease.browser, browser)
        pw.chromium.connect_over_cdp.assert_not_called()


class RemoteLaunchTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.factory = _Factory()
        self.env = patch.dict(
            os.environ,
            {"BROWSER_SERVICE_URL": "http://browser-service:8001", "APP_SIDECAR_API_KEY": "sidecar-key"},
            clear=False,
        )
        self.env.start()
        self.addCleanup(self.env.stop)
        self.http = patch("picsou_browser.httpx.AsyncClient", self.factory)
        self.http.start()
        self.addCleanup(self.http.stop)

    async def test_posts_then_connects_with_both_headers(self):
        browser = MagicMock()
        pw = MagicMock()
        pw.chromium.connect_over_cdp = AsyncMock(return_value=browser)
        lease = await launch_chromium(pw, args=["--disable-blink-features=AutomationControlled"])
        self.assertEqual(
            self.factory.posts,
            [(
                "http://browser-service:8001/sessions",
                {"args": ["--disable-blink-features=AutomationControlled"]},
                {"X-Picsou-Sidecar-Key": b"sidecar-key"},
            )],
        )
        pw.chromium.connect_over_cdp.assert_awaited_once_with(
            "ws://browser-service:8001/sessions/sess-1/cdp",
            headers={
                "X-Picsou-Browser-Token": "token-1",
                "X-Picsou-Sidecar-Key": "sidecar-key",
            },
            timeout=30_000,
        )
        self.assertIs(lease.browser, browser)

    async def test_https_service_url_uses_wss(self):
        with patch.dict(os.environ, {"BROWSER_SERVICE_URL": "https://browser.example:8443"}):
            pw = MagicMock()
            pw.chromium.connect_over_cdp = AsyncMock(return_value=MagicMock())
            await launch_chromium(pw, args=[])
        self.assertEqual(
            pw.chromium.connect_over_cdp.await_args.args[0],
            "wss://browser.example:8443/sessions/sess-1/cdp",
        )

    async def test_503_is_capacity_and_does_not_connect(self):
        self.factory.post_status = 503
        pw = MagicMock()
        pw.chromium.connect_over_cdp = AsyncMock()
        with self.assertRaises(BrowserCapacityError):
            await launch_chromium(pw, args=[])
        pw.chromium.connect_over_cdp.assert_not_called()

    async def test_connection_failure_is_unavailable(self):
        self.factory.post_error = httpx.ConnectError("down")
        pw = MagicMock()
        with self.assertRaises(BrowserUnavailableError):
            await launch_chromium(pw, args=[])
        pw.chromium.connect_over_cdp.assert_not_called()

    async def test_connect_over_cdp_failure_deletes_the_session(self):
        pw = MagicMock()
        pw.chromium.connect_over_cdp = AsyncMock(side_effect=RuntimeError("ws down"))
        with self.assertRaises(BrowserUnavailableError):
            await launch_chromium(pw, args=[])
        self.assertEqual(self.factory.deletes[0][0], "http://browser-service:8001/sessions/sess-1")
        self.assertEqual(self.factory.deletes[0][1], {"X-Picsou-Sidecar-Key": b"sidecar-key"})

    async def test_unusable_201_still_deletes_the_session(self):
        self.factory.post_body = {"sessionId": "sess-1", "token": 7}
        pw = MagicMock()
        pw.chromium.connect_over_cdp = AsyncMock()
        with self.assertRaises(BrowserUnavailableError):
            await launch_chromium(pw, args=[])
        pw.chromium.connect_over_cdp.assert_not_called()
        self.assertEqual(self.factory.deletes[0][0], "http://browser-service:8001/sessions/sess-1")


class NonAsciiSidecarKeyTest(unittest.IsolatedAsyncioTestCase):
    async def test_utf8_key_goes_out_as_utf8_bytes_on_post_websocket_and_delete(self):
        sent = []

        def service(request: httpx.Request) -> httpx.Response:
            sent.append((request.method, request.headers.raw))
            if request.method == "POST":
                return httpx.Response(201, json=_Factory().post_body)
            return httpx.Response(204)

        real_client = httpx.AsyncClient

        def client(**kwargs):
            return real_client(transport=httpx.MockTransport(service), **kwargs)

        pw = MagicMock()
        pw.chromium.connect_over_cdp = AsyncMock(return_value=MagicMock(close=AsyncMock()))
        env = {"BROWSER_SERVICE_URL": "http://browser-service:8001", "APP_SIDECAR_API_KEY": "clé"}
        with patch.dict(os.environ, env), patch("picsou_browser.httpx.AsyncClient", client):
            lease = await launch_chromium(pw, args=[])
            await lease.close()

        wire = "clé".encode("utf-8")
        self.assertEqual(
            [(method, [value for name, value in raw if name.lower() == b"x-picsou-sidecar-key"])
             for method, raw in sent],
            [("POST", [wire]), ("DELETE", [wire])],
        )
        ws_headers = pw.chromium.connect_over_cdp.await_args.kwargs["headers"]
        # Playwright writes WebSocket header strings as latin-1.
        self.assertEqual(ws_headers["X-Picsou-Sidecar-Key"].encode("latin-1"), wire)


class LeaseCloseTest(unittest.IsolatedAsyncioTestCase):
    async def test_close_is_idempotent_never_raises_and_deletes_once(self):
        factory = _Factory()
        factory.delete_error = httpx.ConnectError("delete down")
        browser = MagicMock()
        browser.close = AsyncMock(side_effect=RuntimeError("already closing"))
        lease = BrowserLease(
            browser,
            session_id="sess-1",
            base_url="http://browser-service:8001",
            sidecar_key=b"sidecar-key",
        )
        with patch("picsou_browser.httpx.AsyncClient", factory):
            await lease.close()
            await lease.close()
        browser.close.assert_awaited_once()
        self.assertEqual(len(factory.deletes), 1)


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@unittest.skipUnless(os.environ.get("BROWSER_SERVICE_E2E") == "1", "requires a real browser-service")
class RemoteFetchGuardTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.port = _free_port()
        env = os.environ.copy()
        env["APP_SIDECAR_API_KEY"] = "e2e-clé"
        env["BROWSER_MAX_SESSIONS"] = "2"
        env["BROWSER_CLAIM_TIMEOUT_SECONDS"] = "30"
        env["BROWSER_SESSION_TTL_SECONDS"] = "120"
        env.pop("BROWSER_SERVICE_URL", None)
        cls.proc = subprocess.Popen(
            [
                sys.executable,
                "-m",
                "uvicorn",
                "main:app",
                "--host",
                "127.0.0.1",
                "--port",
                str(cls.port),
                "--no-access-log",
                "--log-level",
                "warning",
            ],
            cwd="/app",
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        deadline = time.monotonic() + 20
        ready = False
        while time.monotonic() < deadline:
            if cls.proc.poll() is not None:
                output = cls.proc.stdout.read().decode(errors="replace") if cls.proc.stdout else ""
                raise RuntimeError("browser-service exited early: " + output[-500:])
            try:
                with socket.create_connection(("127.0.0.1", cls.port), 0.2):
                    ready = True
                    break
            except OSError:
                time.sleep(0.1)
        if not ready:
            cls.proc.kill()
            raise RuntimeError("browser-service did not listen")

        cls.seen: list[str] = []

        class Fixture(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                return

            def do_GET(self):
                type(self).server_seen.append(self.path)  # type: ignore[attr-defined]
                body = b"<html><body>fixture</body></html>"
                self.send_response(200)
                self.send_header("Content-Type", "text/html")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        Fixture.server_seen = cls.seen  # type: ignore[attr-defined]
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        cls.http_thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.http_thread.start()
        cls.fixture = "http://127.0.0.1:" + str(cls.http.server_port)

        cls.stolen: list[str] = []

        class Steal(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                return

            def do_GET(self):
                type(self).server_seen.append(self.path)  # type: ignore[attr-defined]
                self.send_response(200)
                self.end_headers()

        Steal.server_seen = cls.stolen  # type: ignore[attr-defined]
        cls.steal = ThreadingHTTPServer(("127.0.0.1", 0), Steal)
        cls.steal_thread = threading.Thread(target=cls.steal.serve_forever, daemon=True)
        cls.steal_thread.start()
        cls.steal_url = "http://127.0.0.1:" + str(cls.steal.server_port) + "/steal"

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.steal.shutdown()
        cls.http.server_close()
        cls.steal.server_close()
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
            cls.proc.wait(timeout=5)

    async def test_remote_page_loads_and_corum_fetch_guard_blocks_off_origin(self):
        import importlib.util

        from playwright.async_api import Error as PlaywrightError
        from playwright.async_api import async_playwright

        corum_dir = "/src/corum-auth"
        if corum_dir not in sys.path:
            sys.path.insert(0, corum_dir)
        spec = importlib.util.spec_from_file_location("corum_main", corum_dir + "/main.py")
        assert spec and spec.loader
        corum = importlib.util.module_from_spec(spec)
        sys.modules["corum_main"] = corum
        spec.loader.exec_module(corum)

        env = os.environ.copy()
        env["BROWSER_SERVICE_URL"] = f"http://127.0.0.1:{self.port}"
        env["APP_SIDECAR_API_KEY"] = "e2e-clé"
        with patch.dict(os.environ, env, clear=False):
            playwright = await async_playwright().start()
            lease = None
            try:
                lease = await launch_chromium(
                    playwright, args=["--disable-blink-features=AutomationControlled"]
                )
                context = await lease.browser.new_context()
                page = await context.new_page()
                page.set_default_timeout(15000)
                await page.goto(self.fixture + "/")
                self.assertIn("fixture", await page.content())
                self.assertTrue(any(path == "/" for path in self.seen))

                guarded = await corum._new_page(context)
                guarded.set_default_timeout(8000)
                with self.assertRaises(PlaywrightError):
                    await guarded.goto(self.steal_url)
                await page.wait_for_timeout(300)
                self.assertEqual(self.stolen, [])

                # Another session's profile is on the same filesystem: a CDP
                # client must not be able to load it through file://.
                with self.assertRaises(PlaywrightError):
                    await page.goto("file:///etc/hostname")
                self.assertNotIn("file://", page.url)
            finally:
                if lease is not None:
                    await lease.close()
                    await lease.close()
                await playwright.stop()


if __name__ == "__main__":
    unittest.main()
