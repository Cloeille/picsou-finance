import os
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

from main import (
    PENDING_TTL_SECONDS,
    _close_resources,
    _pending,
    _portfolio_http_exception,
    _wait_for_session_state,
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


class FakeAuthenticatedContext:
    async def storage_state(self):
        return {"cookies": [{"name": "CAPITOL", "value": "redacted"}]}


class FakeAuthenticatedPage:
    url = "https://www.boursedirect.fr/fr/actualites"

    async def goto(self, *_args, **_kwargs):
        return None


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
        with self.assertLogs("bourse-direct-auth", level="WARNING") as captured:
            await _close_resources(FailingResource(), None, None)

        self.assertTrue(any("resource cleanup failed" in line for line in captured.output))

    async def test_slow_authenticated_page_is_not_misreported_as_invalid_otp(self):
        with (
            patch("main.MFA_RESULT_TIMEOUT_SECONDS", 1),
            patch("main.SESSION_CHECK_TIMEOUT_SECONDS", 1),
            patch("main._portfolio_link", new=AsyncMock(return_value=(None, None))),
            patch("main._otp_visible", new=AsyncMock(return_value=False)),
            patch("main.asyncio.sleep", new=AsyncMock()),
        ):
            with self.assertRaises(HTTPException) as raised:
                await _wait_for_session_state(
                    FakeAuthenticatedContext(),
                    FakeAuthenticatedPage(),
                )

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_UNAVAILABLE")


class RequestContractTest(unittest.TestCase):
    def setUp(self):
        key_patch = patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "test-key"})
        key_patch.start()
        self.addCleanup(key_patch.stop)

    def test_accounts_rejects_a_non_object_storage_state(self):
        with TestClient(app, headers=SIDECAR_HEADERS) as client:
            response = client.post("/accounts", json={"sessionState": "[]"})

        self.assertEqual(response.status_code, 400)
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

    def test_portfolio_http_statuses_have_distinct_error_codes(self):
        cases = (
            (401, 401, "SESSION_EXPIRED"),
            (403, 401, "SESSION_EXPIRED"),
            (429, 502, "UPSTREAM_UNAVAILABLE"),
            (500, 502, "UPSTREAM_UNAVAILABLE"),
            (503, 502, "UPSTREAM_UNAVAILABLE"),
            (404, 502, "PORTFOLIO_INCOMPLETE"),
        )

        for upstream_status, expected_status, expected_detail in cases:
            with self.subTest(upstream_status=upstream_status):
                error = _portfolio_http_exception(upstream_status)
                self.assertEqual(error.status_code, expected_status)
                self.assertEqual(error.detail, expected_detail)


if __name__ == "__main__":
    unittest.main()
