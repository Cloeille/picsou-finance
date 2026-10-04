"""HTTP-level tests for the DEGIRO sidecar's shared-key boundary."""

import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import main


TEST_KEY = "degiro-sidecar-test-key"
CHALLENGE = "Picsou-Sidecar-Key"


class SidecarAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.key_patch = patch.object(main, "SIDECAR_API_KEY", TEST_KEY)
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.client = TestClient(main.app)

    def test_unauthenticated_and_invalid_key_requests_are_challenged_before_routing(self):
        requests = (
            ("GET", "/docs", None),
            ("GET", "/openapi.json", None),
            ("POST", "/initiate", {"username": "x", "password": "y"}),
            ("POST", "/complete", {"processId": "p", "code": "123456"}),
            ("POST", "/portfolio", {"sessionBlob": "{}"}),
            ("POST", "/initiate", "{"),
        )
        invalid_headers = ({}, {"X-Picsou-Sidecar-Key": "wrong-key"},
                           {"X-Picsou-Sidecar-Key": ""})

        with self.client as client:
            response = client.get("/not-a-route")
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)

            for method, path, payload in requests:
                for headers in invalid_headers:
                    with self.subTest(method=method, path=path, headers=headers):
                        if isinstance(payload, str):
                            response = client.request(
                                method, path, headers=headers, content=payload
                            )
                        else:
                            response = client.request(
                                method, path, headers=headers, json=payload
                            )
                        self.assertEqual(response.status_code, 401)
                        self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
                        self.assertEqual(
                            response.headers.get("WWW-Authenticate"), CHALLENGE
                        )

    def test_health_is_accessible_without_a_key(self):
        with self.client as client:
            response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_authorized_request_reaches_request_validation_without_a_challenge(self):
        with self.client as client:
            response = client.post(
                "/initiate",
                json={},
                headers={"X-Picsou-Sidecar-Key": TEST_KEY},
            )

        self.assertEqual(response.status_code, 422)
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_non_ascii_key_authenticates_as_utf8_wire_bytes(self):
        with patch.object(main, "SIDECAR_API_KEY", "clé"):
            with TestClient(main.app) as client:
                response = client.get(
                    "/docs",
                    headers=[(b"X-Picsou-Sidecar-Key", "clé".encode("utf-8"))],
                )

        self.assertEqual(response.status_code, 200)
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_startup_refuses_a_missing_or_blank_key(self):
        for missing_key in ("", "   "):
            with self.subTest(key=repr(missing_key)), patch.object(
                main, "SIDECAR_API_KEY", missing_key
            ):
                with self.assertRaisesRegex(RuntimeError, "APP_SIDECAR_API_KEY"):
                    with TestClient(main.app):
                        self.fail("startup should refuse a missing key")


if __name__ == "__main__":
    unittest.main()
