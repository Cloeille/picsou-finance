"""HTTP-level tests for the AMEX sidecar's shared-key boundary."""

import os
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import main


TEST_KEY = "amex-sidecar-test-key"
CHALLENGE = "Picsou-Sidecar-Key"


class SidecarAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.env_patch = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY})
        self.key_patch = patch.object(main, "_SIDECAR_KEY", None)
        self.env_patch.start()
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.addCleanup(self.env_patch.stop)
        self.client = TestClient(main.app)

    def test_health_is_accessible_without_a_key(self):
        with self.client as client:
            response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_unauthenticated_and_invalid_key_requests_are_challenged(self):
        with self.client as client:
            invalid_headers = (
                {},
                {main.SIDECAR_KEY_HEADER: "wrong-key"},
                {main.SIDECAR_KEY_HEADER: ""},
            )
            for headers in invalid_headers:
                with self.subTest(headers=headers):
                    response = client.post(
                        "/accounts", json={"sessionState": "{}"}, headers=headers
                    )
                    self.assertEqual(response.status_code, 401)
                    self.assertEqual(response.json(), {"detail": "invalid sidecar key"})
                    self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)

    def test_authorized_request_reaches_endpoint_validation_without_a_challenge(self):
        with self.client as client:
            response = client.post(
                "/accounts",
                json={"sessionState": "{}"},
                headers={main.SIDECAR_KEY_HEADER: TEST_KEY},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_DATA"})
        self.assertNotIn("WWW-Authenticate", response.headers)


if __name__ == "__main__":
    unittest.main()
