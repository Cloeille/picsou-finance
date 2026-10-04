"""Pending second factors, log hygiene, and the HTTP contract itself.

A pending app-push validation pins an httpx client and its cookie jar; leaking
those is how a sidecar dies of memory rather than of a bug.
"""

import time
import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

import main
from main import (
    MAX_PENDING,
    PENDING_TTL_SECONDS,
    _cleanup_expired,
    _close_all_pending,
    _log_safe,
    _pending,
    _pending_lock,
    _store_pending,
    _take_pending,
    app,
)


class FakeClient:
    def __init__(self):
        self.closed = 0

    async def aclose(self):
        self.closed += 1


class FailingClient:
    async def aclose(self):
        raise RuntimeError("close failed")


class LogSafetyTest(unittest.TestCase):
    def test_control_characters_cannot_forge_a_log_line(self):
        forged = _log_safe("/accounts\r\nINFO:bourso-auth:all clear")
        self.assertNotIn("\n", forged)
        self.assertNotIn("\r", forged)
        self.assertTrue(forged.startswith("/accounts"))

    def test_the_logged_path_is_bounded(self):
        self.assertEqual(len(_log_safe("/" + "a" * 5000)), 200)


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _close_all_pending()

    async def asyncTearDown(self):
        await _close_all_pending()

    async def test_expired_attempts_are_swept_and_their_clients_closed(self):
        client = FakeClient()
        async with _pending_lock:
            _pending["stale"] = {
                "client": client,
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }

        await _cleanup_expired()

        self.assertNotIn("stale", _pending)
        self.assertEqual(client.closed, 1)

    async def test_a_live_attempt_survives_the_sweep(self):
        async with _pending_lock:
            _pending["fresh"] = {"client": FakeClient(), "created_at": time.time()}

        await _cleanup_expired()

        self.assertIn("fresh", _pending)

    async def test_an_attempt_can_only_be_consumed_once(self):
        async with _pending_lock:
            _pending["once"] = {"client": FakeClient(), "created_at": time.time()}

        self.assertIsNotNone(await _take_pending("once"))
        self.assertIsNone(await _take_pending("once"))

    async def test_pending_capacity_is_bounded(self):
        for index in range(MAX_PENDING):
            await _store_pending(str(index), {"client": FakeClient(), "created_at": time.time()})

        with self.assertRaises(Exception) as raised:
            await _store_pending("overflow", {"client": FakeClient(), "created_at": time.time()})
        self.assertEqual(getattr(raised.exception, "detail", None), "UPSTREAM_UNAVAILABLE")

    async def test_a_client_that_refuses_to_close_does_not_break_cleanup(self):
        async with _pending_lock:
            _pending["bad"] = {
                "client": FailingClient(),
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }
        await _cleanup_expired()
        self.assertNotIn("bad", _pending)


class ContractTest(unittest.TestCase):
    def setUp(self):
        key_patch = patch.object(main, "SIDECAR_API_KEY", "test-key")
        key_patch.start()
        self.addCleanup(key_patch.stop)
        self.client = TestClient(app, headers={"X-Picsou-Sidecar-Key": "test-key"})

    def test_health_is_unauthenticated(self):
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})

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
