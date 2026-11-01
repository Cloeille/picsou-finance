"""Pending second factors and the BoursoBank HTTP contract.

A pending app-push validation pins an httpx client and its cookie jar; leaking
those is how a sidecar dies of memory rather than of a bug.
"""

import os
import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from main import MAX_PENDING, PENDING_TTL_SECONDS, _pending, app


class FakeClient:
    def __init__(self):
        self.closed = 0

    async def aclose(self):
        self.closed += 1


class FailingClient:
    async def aclose(self):
        raise RuntimeError("close failed")


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _pending.close_all()

    async def asyncTearDown(self):
        await _pending.close_all()

    async def test_expired_attempts_are_swept_and_their_clients_closed(self):
        client = FakeClient()
        await _pending.put("stale", {"client": client, "created_at": time.time() - PENDING_TTL_SECONDS - 1})

        await _pending.expire()

        self.assertNotIn("stale", _pending)
        self.assertEqual(client.closed, 1)

    async def test_a_live_attempt_survives_the_sweep(self):
        await _pending.put("fresh", {"client": FakeClient(), "created_at": time.time()})

        await _pending.expire()

        self.assertIn("fresh", _pending)

    async def test_shutdown_closes_every_pending_client(self):
        first, second = FakeClient(), FakeClient()
        await _pending.put("a", {"client": first, "created_at": time.time()})
        await _pending.put("b", {"client": second, "created_at": time.time()})

        await _pending.close_all()

        self.assertEqual((len(_pending), first.closed, second.closed), (0, 1, 1))

    async def test_pending_capacity_is_bounded_at_the_configured_limit(self):
        self.assertEqual(_pending.max_size, MAX_PENDING)
        for index in range(MAX_PENDING):
            self.assertTrue(await _pending.put(str(index), {"client": FakeClient(), "created_at": time.time()}))

        self.assertFalse(await _pending.put("overflow", {"client": FakeClient(), "created_at": time.time()}))

    async def test_a_client_that_refuses_to_close_does_not_break_cleanup(self):
        await _pending.put("bad", {"client": FailingClient(), "created_at": time.time() - PENDING_TTL_SECONDS - 1})
        await _pending.expire()
        self.assertNotIn("bad", _pending)


class ContractTest(unittest.TestCase):
    def setUp(self):
        key_patch = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "test-key"})
        key_patch.start()
        self.addCleanup(key_patch.stop)
        self.client = TestClient(app, headers={"X-Picsou-Sidecar-Key": "test-key"})

    def test_an_unknown_attempt_reports_an_expired_authentication(self):
        response = self.client.post("/complete", json={"processId": "gone"})
        self.assertEqual(response.status_code, 410)
        self.assertEqual(response.json()["detail"], "AUTH_ATTEMPT_EXPIRED")

    def test_a_submitted_code_is_refused_because_only_the_app_push_is_supported(self):
        # Refused rather than ignored, so a future SMS path cannot ship
        # half-wired and silently drop the code the user typed.
        response = self.client.post("/complete", json={"processId": "p", "code": "123456"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "MFA_TYPE_UNSUPPORTED")

    def test_unexpected_fields_are_refused(self):
        response = self.client.post(
            "/initiate", json={"customerId": "1", "password": "2", "website": "tc"}
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_a_malformed_session_state_is_refused(self):
        response = self.client.post("/accounts", json={"sessionState": "not-json"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")


if __name__ == "__main__":
    unittest.main()
