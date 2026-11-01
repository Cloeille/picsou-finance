import json
import os
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient

from main import (
    PENDING_TTL_SECONDS,
    _capture_failure_diagnostics,
    _close_resources,
    _pending,
    app,
)

SIDECAR_HEADERS = {"X-Picsou-Sidecar-Key": "test-key"}


class FakeResource:
    def __init__(self, method: str):
        self.method = method
        self.calls = 0

    async def close(self):
        if self.method != "close":
            raise AssertionError("unexpected close")
        self.calls += 1

    async def stop(self):
        if self.method != "stop":
            raise AssertionError("unexpected stop")
        self.calls += 1


class FailingResource:
    async def close(self):
        raise RuntimeError("close failed")


class PendingAuthenticationLifecycleTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        await _pending.close_all()

    async def asyncTearDown(self):
        await _pending.close_all()

    async def test_expired_authentication_closes_every_browser_resource(self):
        context = FakeResource("close")
        browser = FakeResource("close")
        playwright = FakeResource("stop")
        await _pending.put("expired", {
            "context": context,
            "browser": browser,
            "playwright": playwright,
            "created_at": time.time() - PENDING_TTL_SECONDS - 1,
        })

        await _pending.expire()

        self.assertNotIn("expired", _pending)
        self.assertEqual(context.calls, 1)
        self.assertEqual(browser.calls, 1)
        self.assertEqual(playwright.calls, 1)

    async def test_cleanup_failure_is_logged_at_warning_level(self):
        with self.assertLogs("fortuneo-auth", level="WARNING") as captured:
            await _close_resources(FailingResource(), None, None)

        self.assertTrue(any("resource cleanup failed" in line for line in captured.output))

    async def test_failure_capture_is_disabled_without_explicit_opt_in(self):
        page = MagicMock()
        page.screenshot = AsyncMock()

        with patch("main.DEBUG_ARTIFACTS_ENABLED", False):
            await _capture_failure_diagnostics(page, "anonymous_failure")

        page.screenshot.assert_not_awaited()


class RequestContractTest(unittest.TestCase):
    def setUp(self):
        key_patch = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "test-key"})
        key_patch.start()
        self.addCleanup(key_patch.stop)

    def test_changed_equipment_shape_is_mapped_to_invalid_data(self):
        playwright = MagicMock()
        browser = MagicMock()
        context = MagicMock()
        page = MagicMock()
        manager = MagicMock()
        manager.start = AsyncMock(return_value=playwright)
        playwright.chromium.launch = AsyncMock(return_value=browser)
        browser.new_context = AsyncMock(return_value=context)
        context.new_page = AsyncMock(return_value=page)
        page.goto = AsyncMock()
        page.wait_for_load_state = AsyncMock()
        session_state = json.dumps({
            "storageState": {"cookies": [], "origins": []},
            "apiKey": "test-key",
        })

        with (
            patch("main.async_playwright", return_value=manager),
            patch("main._restore_session_storage", new=AsyncMock()),
            patch("main._fetch_equipment", new=AsyncMock(return_value={"banking": []})),
            patch("main._close_resources", new=AsyncMock()),
            TestClient(app, headers=SIDECAR_HEADERS) as client,
        ):
            response = client.post("/accounts", json={"sessionState": session_state})

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_initiate_disposes_expired_attempt_before_starting_a_browser(self):
        context = FakeResource("close")
        browser = FakeResource("close")
        playwright = FakeResource("stop")
        state = {
            "context": context,
            "browser": browser,
            "playwright": playwright,
            "created_at": time.time(),
        }
        manager = MagicMock()
        observed_at_browser_start = {}

        async def record_pending_state():
            observed_at_browser_start["state_present"] = "expired" in _pending
            observed_at_browser_start["resource_calls"] = (
                context.calls,
                browser.calls,
                playwright.calls,
            )
            raise RuntimeError("browser startup stopped by test")

        manager.start = AsyncMock(side_effect=record_pending_state)
        with (
            patch("main.async_playwright", return_value=manager),
            patch("main.log.exception"),
            patch.object(_pending, "sweep_seconds", 3_600),
            TestClient(app, headers=SIDECAR_HEADERS) as client,
        ):
            client.portal.call(_pending.put, "expired", state)
            state["created_at"] = time.time() - PENDING_TTL_SECONDS - 1
            response = client.post(
                "/initiate",
                json={"login": "synthetic-login", "password": "synthetic-password"},
            )

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json(), {"detail": "INTERNAL_ERROR"})
        manager.start.assert_awaited_once_with()
        self.assertEqual(observed_at_browser_start, {
            "state_present": False,
            "resource_calls": (1, 1, 1),
        })

    def test_accounts_rejects_a_non_object_storage_state(self):
        with TestClient(app, headers=SIDECAR_HEADERS) as client:
            response = client.post("/accounts", json={"sessionState": "[]"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_malformed_otp_is_mapped_to_invalid_otp(self):
        with TestClient(app, headers=SIDECAR_HEADERS) as client:
            response = client.post(
                "/complete",
                json={"processId": "process", "code": "12ab"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_OTP")

    def test_invalid_credentials_contract_is_mapped_to_invalid_data(self):
        with TestClient(app, headers=SIDECAR_HEADERS) as client:
            response = client.post(
                "/initiate",
                json={"login": "", "password": "secret"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")


if __name__ == "__main__":
    unittest.main()
