"""Shared-key authentication for every non-health HTTP path."""

import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import main as service

TEST_KEY = "test-key"
AUTH_HEADER = "X-Picsou-Sidecar-Key"
CHALLENGE = "Picsou-Sidecar-Key"


class SidecarAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.key = getattr(service, "SIDECAR_API_KEY", None)
        service.SIDECAR_API_KEY = TEST_KEY
        self.client = TestClient(service.app, follow_redirects=False)

    def tearDown(self):
        self.client.close()
        service.SIDECAR_API_KEY = self.key

    def assert_unauthorized(self, response):
        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.headers.get("www-authenticate"), CHALLENGE)

    def test_each_business_route_rejects_missing_wrong_and_empty_keys(self):
        requests = (
            ("/initiate", {"associateCode": "123456", "password": "pw"}),
            ("/complete", {"processId": "id", "code": "1234"}),
            ("/positions", {"sessionState": "{}"}),
        )
        for path, body in requests:
            for headers in ({}, {AUTH_HEADER: "wrong"}, {AUTH_HEADER: ""}):
                with self.subTest(path=path, headers=headers):
                    self.assert_unauthorized(self.client.post(path, json=body, headers=headers))

    def test_malformed_json_is_rejected_by_auth_before_body_parsing(self):
        for path in ("/initiate", "/complete", "/positions"):
            with self.subTest(path=path):
                response = self.client.post(path, content=b"not-json")
                self.assert_unauthorized(response)

    def test_documentation_openapi_and_unknown_paths_require_the_key(self):
        for path in ("/docs", "/openapi.json", "/not-a-route"):
            with self.subTest(path=path):
                self.assert_unauthorized(self.client.get(path))

    def test_only_exact_health_path_is_public(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})
        self.assert_unauthorized(self.client.get("/health/"))

    def test_configured_non_ascii_key_authenticates_as_utf8(self):
        service.SIDECAR_API_KEY = "clé"
        response = self.client.post(
            "/initiate",
            content=b"not-json",
            headers=[(AUTH_HEADER.encode(), "clé".encode("utf-8"))],
        )
        self.assertEqual(response.status_code, 400)
        self.assertNotEqual(response.headers.get("www-authenticate"), CHALLENGE)

    def test_missing_key_refuses_application_startup(self):
        service.SIDECAR_API_KEY = "  "
        with self.assertRaisesRegex(RuntimeError, "APP_SIDECAR_API_KEY"):
            with TestClient(service.app):
                pass


if __name__ == "__main__":
    unittest.main()
