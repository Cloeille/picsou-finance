"""Real Chromium + local HTTPS server. No real account or portal traffic.

Opt in with CORUM_BROWSER_TESTS=1 and the pinned Playwright Chromium installed.
The TLS fixture uses the system openssl command, not an extra Python dependency.
"""
import os
import ssl
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from playwright.async_api import Error as PlaywrightError, async_playwright

import main


class PortalOriginTest(unittest.TestCase):
    def test_production_origin_is_https_and_exact(self):
        self.assertTrue(main._is_portal_url(main.BASE_URL + "/api/auth"))
        self.assertTrue(main._is_portal_url("https://client.corum.fr:443/safe"))
        for target in ("https://attacker.example/steal", "http://client.corum.fr/steal",
                       "http://127.0.0.1/steal", "https://192.168.1.1/steal",
                       "https://client.corum.fr:444/steal", "https://[::1]/steal",
                       "https://user:secret@client.corum.fr/steal", "https://client.corum.fr:0/steal"):
            with self.subTest(target=target):
                self.assertFalse(main._is_portal_url(target))


@unittest.skipUnless(os.environ.get("CORUM_BROWSER_TESTS") == "1", "requires pinned Chromium")
class BrowserOriginSecurityTest(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = tempfile.TemporaryDirectory()
        cert, key = (str(Path(cls.scratch.name) / name) for name in ("cert.pem", "key.pem"))
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-days", "1", "-subj", "/CN=localhost", "-keyout", key, "-out", cert],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        cls.sent = []
        cls.status, cls.target = 307, "/safe"

        class Fixture(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_GET(self):
                self.answer()

            def do_POST(self):
                self.answer()

            def answer(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                cls.sent.append((self.command, self.path, body))
                if self.path == "/redirect":
                    self.send_response(cls.status)
                    self.send_header("Location", cls.target)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.send_header("Content-Type", "text/html")
                    self.end_headers()
                    self.wfile.write(b"<html><body>fixture</body></html>")

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(cert, key)
        cls.server.socket = tls.wrap_socket(cls.server.socket, server_side=True)
        cls.origin = "https://127.0.0.1:" + str(cls.server.server_port)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.scratch.cleanup()

    async def asyncSetUp(self):
        self.sent.clear()
        self.origin_patch = patch.object(main, "BASE_URL", self.origin)
        self.origin_patch.start()
        self.playwright = await async_playwright().start()
        self.browser = await self.playwright.chromium.launch(headless=True, args=["--no-sandbox"])
        self.context = await self.browser.new_context(ignore_https_errors=True)
        self.page = await main._new_page(self.context)
        self.page.set_default_timeout(5000)

    async def asyncTearDown(self):
        await self.context.close()
        await self.browser.close()
        await self.playwright.stop()
        self.origin_patch.stop()

    async def test_password_and_otp_are_not_forwarded_on_307_or_308(self):
        for status in (307, 308):
            for target in (self.origin.replace("127.0.0.1", "localhost") + "/steal",
                           self.origin.replace("https:", "http:") + "/steal",
                           "http://127.0.0.1/steal", "https://192.168.1.1/steal",
                           "https://127.0.0.1:444/steal"):
                with self.subTest(status=status, target=target):
                    type(self).status, type(self).target = status, target
                    self.sent.clear()
                    await self.page.goto(self.origin + "/")
                    result = await self.page.evaluate("""async () => {
                        try {
                            await fetch('/redirect', {method: 'POST', body: 'password=fixture&otp=123456'});
                            return 'forwarded';
                        } catch (_) { return 'blocked'; }
                    }""")
                    self.assertEqual(result, "blocked")
                    self.assertFalse(any(path == "/steal" for _, path, _ in self.sent))

    async def test_same_origin_https_redirect_preserves_the_post(self):
        for status in (307, 308):
            type(self).status, type(self).target = status, self.origin + "/safe"
            self.sent.clear()
            await self.page.goto(self.origin + "/")
            await self.page.evaluate("fetch('/redirect', {method:'POST', body:'password=fixture'})")
            forwarded = next(r for r in self.sent if r[1] == "/safe")
            self.assertEqual(forwarded, ("POST", "/safe", b"password=fixture"))

    async def test_navigation_redirect_to_another_origin_is_blocked(self):
        type(self).status, type(self).target = 302, self.origin.replace("127.0.0.1", "localhost") + "/login"
        with self.assertRaises(PlaywrightError):
            await self.page.goto(self.origin + "/redirect")
        self.assertFalse(any(path == "/login" for _, path, _ in self.sent))

    async def test_loop_is_stopped_after_five_redirects(self):
        type(self).status, type(self).target = 307, self.origin + "/redirect"
        with self.assertRaises(PlaywrightError):
            await self.page.goto(self.origin + "/redirect")
        self.assertEqual(sum(path == "/redirect" for _, path, _ in self.sent), 6)


if __name__ == "__main__":
    unittest.main()
