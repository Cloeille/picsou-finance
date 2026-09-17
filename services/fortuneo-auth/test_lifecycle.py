import asyncio
import json
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi.testclient import TestClient

from main import (
    PENDING_TTL_SECONDS,
    _cleanup_expired,
    _capture_failure_diagnostics,
    _close_all_pending,
    _close_resources,
    _pending,
    _pending_lock,
    _take_pending,
    app,
)


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
        await _close_all_pending()

    async def asyncTearDown(self):
        await _close_all_pending()

    async def test_expired_authentication_closes_every_browser_resource(self):
        context = FakeResource("close")
        browser = FakeResource("close")
        playwright = FakeResource("stop")
        async with _pending_lock:
            _pending["expired"] = {
                "context": context,
                "browser": browser,
                "playwright": playwright,
                "created_at": time.time() - PENDING_TTL_SECONDS - 1,
            }

        await _cleanup_expired()

        self.assertNotIn("expired", _pending)
        self.assertEqual(context.calls, 1)
        self.assertEqual(browser.calls, 1)
        self.assertEqual(playwright.calls, 1)

    async def test_pending_authentication_can_only_be_claimed_once(self):
        state = {"created_at": time.time()}
        async with _pending_lock:
            _pending["process"] = state

        first, second = await asyncio.gather(
            _take_pending("process"),
            _take_pending("process"),
        )

        self.assertEqual(sum(value is state for value in (first, second)), 1)
        self.assertEqual(sum(value is None for value in (first, second)), 1)

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
            TestClient(app) as client,
        ):
            response = client.post("/accounts", json={"sessionState": session_state})

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_accounts_rejects_a_non_object_storage_state(self):
        with TestClient(app) as client:
            response = client.post("/accounts", json={"sessionState": "[]"})

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_malformed_otp_is_mapped_to_invalid_otp(self):
        with TestClient(app) as client:
            response = client.post(
                "/complete",
                json={"processId": "process", "code": "12ab"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_OTP")

    def test_invalid_credentials_contract_is_mapped_to_invalid_data(self):
        with TestClient(app) as client:
            response = client.post(
                "/initiate",
                json={"login": "", "password": "secret"},
            )

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()["detail"], "INVALID_DATA")

    def test_health_reports_ok(self):
        with TestClient(app) as client:
            response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})


if __name__ == "__main__":
    unittest.main()
