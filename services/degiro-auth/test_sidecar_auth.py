"""The DEGIRO app keeps the shared sidecar-key contract."""

import os
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from picsou_sidecar import contract

import main


class SidecarAuthTest(contract.SidecarAuthContract):
    app = main.app


class PendingExpiryTest(unittest.TestCase):
    def test_complete_refuses_expired_credentials_before_contacting_degiro(self):
        with (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "test-key"}),
            patch.object(main._pending, "sweep_seconds", 3600),
            patch.object(main, "_client") as upstream,
            TestClient(main.app) as client,
        ):
            state = {"username": "synthetic", "password": "synthetic", "created_at": time.time()}
            client.portal.call(main._pending.put, "expired", state)
            state["created_at"] -= main._pending.ttl_seconds + 1
            response = client.post(
                "/complete", json={"processId": "expired", "code": "123456"},
                headers={"X-Picsou-Sidecar-Key": "test-key"},
            )
            self.assertEqual(response.status_code, 404)
            self.assertEqual(response.json(), {"detail": "processId not found or expired — please re-authenticate"})
            self.assertNotIn("expired", main._pending)
            upstream.assert_not_called()


if __name__ == "__main__":
    unittest.main()
