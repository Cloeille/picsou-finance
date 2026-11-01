"""The shared-key contract every sidecar app must keep, as one reusable test.

Each sidecar binds it to its own app in test_sidecar_auth.py:

    class SidecarAuthTest(SidecarAuthContract):
        app = main.app

Only tests import this module; it needs fastapi.testclient (httpx).
"""

import os
import re
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient

from .app import CHALLENGE, HEALTH_PATH, KEY_ENV, KEY_HEADER

TEST_KEY = "sidecar-contract-test-key"


class SidecarAuthContract(unittest.TestCase):
    app: FastAPI

    @classmethod
    def setUpClass(cls):
        if not hasattr(cls, "app"):
            raise unittest.SkipTest("abstract contract")

    def setUp(self):
        key = patch.dict(os.environ, {KEY_ENV: TEST_KEY})
        key.start()
        self.addCleanup(key.stop)

    def _client(self):
        return TestClient(self.app)

    def _routes(self):
        for route in self.app.routes:
            if isinstance(route, APIRoute) and route.path != HEALTH_PATH:
                path = re.sub(r"\{[^}]+\}", "x", route.path)
                for method in sorted(route.methods - {"HEAD"}):
                    yield method, path

    def assert_challenged(self, response):
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
        self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)

    def test_every_route_challenges_a_missing_wrong_or_empty_key_before_routing(self):
        requests = [*self._routes(), ("GET", "/docs"), ("GET", "/openapi.json"), ("GET", "/not-a-route"),
                    ("GET", HEALTH_PATH + "/")]
        with self._client() as client:
            for method, path in requests:
                for headers in ({}, {KEY_HEADER: "wrong-key"}, {KEY_HEADER: ""}):
                    with self.subTest(method=method, path=path, headers=headers):
                        # A malformed body must not get past the key check either.
                        self.assert_challenged(client.request(method, path, headers=headers, content=b"{"))

    def test_health_is_public_and_reports_the_shared_base_when_set(self):
        with self._client() as client:
            with patch.dict(os.environ):
                os.environ.pop("PICSOU_SIDECAR_BASE", None)
                self.assertEqual(client.get(HEALTH_PATH).json(), {"status": "ok"})
            with patch.dict(os.environ, {"PICSOU_SIDECAR_BASE": "sidecar-python"}):
                response = client.get(HEALTH_PATH)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok", "base": "sidecar-python"})

    def test_the_right_key_reaches_the_app(self):
        with self._client() as client:
            response = client.get("/openapi.json", headers={KEY_HEADER: TEST_KEY})
        self.assertEqual(response.status_code, 200)
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_a_non_ascii_key_authenticates_as_utf8_wire_bytes(self):
        with patch.dict(os.environ, {KEY_ENV: "clé"}), self._client() as client:
            response = client.get("/openapi.json", headers=[(KEY_HEADER.encode(), "clé".encode("utf-8"))])
        self.assertEqual(response.status_code, 200)

    def test_startup_refuses_a_missing_or_blank_key(self):
        for key in ("", "   "):
            with self.subTest(key=repr(key)), patch.dict(os.environ, {KEY_ENV: key}):
                with self.assertRaisesRegex(RuntimeError, KEY_ENV):
                    with self._client():
                        self.fail("startup should refuse a missing key")
