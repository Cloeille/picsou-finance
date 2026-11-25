"""A verify page with no code channel waits for the AMEX app approval."""

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException
from playwright.async_api import Error as PlaywrightError

import main

VERIFY_URL = "https://www.americanexpress.com/fr-fr/account/two-step-verification/verify"
DASHBOARD_URL = "https://global.americanexpress.com/dashboard"
ERROR_URL = "https://www.americanexpress.com/fr-fr/account/error"
SESSION = '{"cookies":[]}'


class AppApprovalInitiateTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.context = MagicMock(close=AsyncMock())
        self.browser = MagicMock(close=AsyncMock(), new_context=AsyncMock(return_value=self.context))
        self.camoufox = MagicMock(__aenter__=AsyncMock(return_value=self.browser), __aexit__=AsyncMock())
        self.page = MagicMock(url=VERIFY_URL, evaluate=AsyncMock(return_value={}))
        self.handlers = []
        self.page.on = lambda event, handler: self.handlers.append((event, handler))
        self.context.new_page = AsyncMock(return_value=self.page)
        field = self.page.locator.return_value.first
        field.press_sequentially = AsyncMock()
        field.input_value = AsyncMock(return_value="synthetic")
        field.click = AsyncMock()
        # No trusted-device interstitial on screen.
        self.page.locator.return_value.count = AsyncMock(return_value=0)
        self.pending = MagicMock(expire=AsyncMock(), put_or_raise=AsyncMock())
        self.finish = AsyncMock(return_value=SESSION)
        self.select = AsyncMock()

    def go_to_after(self, seconds, url):
        asyncio.get_running_loop().call_later(seconds, setattr, self.page, "url", url)

    def account_api_call_after(self, seconds):
        request = SimpleNamespace(
            url="https://global.americanexpress.com/api/servicing/v1/member",
            headers={main.ACCOUNT_TOKEN_HEADER: "synthetic-token"},
        )

        def fire():
            for event, handler in self.handlers:
                if event == "request":
                    handler(request)

        asyncio.get_running_loop().call_later(seconds, fire)

    async def initiate(self, *, channel_offered=False, approval_window=5, verify_timeout_ms=0):
        with (
            patch.object(main, "AsyncCamoufox", return_value=self.camoufox),
            patch.object(main, "_pending", self.pending),
            patch.object(main, "_open_login_form", AsyncMock()),
            patch.object(main, "_wait_for_login_outcome", AsyncMock(return_value="verify")),
            patch.object(main, "_code_channel_offered", AsyncMock(return_value=channel_offered)),
            patch.object(main, "_select_otp_method", self.select),
            patch.object(main, "_find_otp_input", AsyncMock()),
            patch.object(main, "_finish_session", self.finish),
            patch.object(main, "VERIFY_OPTION_TIMEOUT_MS", verify_timeout_ms),
            patch.object(main, "OTP_POLL_SECONDS", 0.01),
            patch.object(main, "INITIATE_BUDGET_SECONDS", approval_window + 1),
            patch.object(main, "APP_APPROVAL_FINISH_RESERVE_SECONDS", 1),
        ):
            return await asyncio.wait_for(
                main.initiate(main.InitiateRequest(userId="synthetic", password="synthetic", method="sms")),
                timeout=5,
            )

    def assert_resources_closed(self):
        self.context.close.assert_awaited_once()
        self.browser.close.assert_awaited_once()
        self.camoufox.__aexit__.assert_awaited_once_with(None, None, None)

    def assert_logged_in_without_otp(self, result):
        self.assertEqual(result, {"processId": None, "mfaRequired": False, "mfaType": None, "sessionState": SESSION})
        self.select.assert_not_awaited()
        self.pending.put_or_raise.assert_not_awaited()
        self.finish.assert_awaited_once()
        self.assert_resources_closed()

    async def test_approval_in_the_app_completes_the_login_in_initiate(self):
        self.go_to_after(0.05, DASHBOARD_URL)
        self.assert_logged_in_without_otp(await self.initiate())
        self.assertIn("deadline", self.finish.await_args.kwargs)

    async def test_an_authenticated_api_call_counts_as_approval(self):
        self.account_api_call_after(0.05)
        self.assert_logged_in_without_otp(await self.initiate())

    async def test_approval_given_while_the_page_is_checked_skips_the_wait(self):
        self.page.url = DASHBOARD_URL
        with self.assertLogs("amex-auth", "INFO") as logs:
            self.assert_logged_in_without_otp(await self.initiate(verify_timeout_ms=5_000))
        self.assertNotIn("waiting for app approval", "\n".join(logs.output))

    async def test_the_trusted_device_step_after_an_approval_is_continued_at_once(self):
        # Shown while the page is still checked for a channel picker.
        self.page.locator.return_value.count = AsyncMock(return_value=1)
        self.page.locator.return_value.first.is_visible = AsyncMock(return_value=True)
        continue_click = AsyncMock(side_effect=lambda **_: setattr(self.page, "url", DASHBOARD_URL))
        self.page.get_by_role.return_value.first.click = continue_click
        self.assert_logged_in_without_otp(await self.initiate(verify_timeout_ms=5_000))
        continue_click.assert_awaited_once()

    async def test_unconfirmed_approval_expires_and_closes_the_browser(self):
        with self.assertLogs("amex-auth", "INFO") as logs, self.assertRaises(HTTPException) as caught:
            await self.initiate(approval_window=0.1)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (408, "AUTH_ATTEMPT_EXPIRED"))
        self.assertIn("AMEX app approval not confirmed in time", "\n".join(logs.output))
        self.assertNotIn("synthetic", "\n".join(logs.output))
        self.finish.assert_not_awaited()
        self.pending.put_or_raise.assert_not_awaited()
        self.assert_resources_closed()

    async def test_an_error_page_after_a_refused_approval_is_not_a_login(self):
        self.go_to_after(0.02, ERROR_URL)
        with self.assertRaises(HTTPException) as caught:
            await self.initiate(approval_window=0.2)
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")
        self.finish.assert_not_awaited()

    async def test_an_approval_too_late_for_the_session_finish_is_not_taken(self):
        self.page.url = DASHBOARD_URL
        with self.assertRaises(HTTPException) as caught:
            # The approval window has closed before the verify page shows up.
            await self.initiate(approval_window=-1)
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")
        self.finish.assert_not_awaited()

    async def test_code_channels_keep_the_one_time_code_flow(self):
        result = await self.initiate(channel_offered=True, verify_timeout_ms=5_000)
        self.assertTrue(result["mfaRequired"])
        self.assertEqual(result["mfaType"], "OTP")
        self.select.assert_awaited_once_with(self.page, "sms")
        self.pending.put_or_raise.assert_awaited_once()
        self.page.remove_listener.assert_called_once()
        self.finish.assert_not_awaited()


class InitiateBudgetTest(unittest.TestCase):
    def test_finish_and_browser_closes_fit_in_the_java_initiate_timeout(self):
        # AmexAdapter.DEFAULT_AUTH_TIMEOUT is 90 s; the three closes run after the finish.
        self.assertLess(main.INITIATE_BUDGET_SECONDS + 3 * main.RESOURCE_CLOSE_TIMEOUT_SECONDS, 90)
        self.assertGreaterEqual(main.APP_APPROVAL_FINISH_RESERVE_SECONDS, main.ACCOUNT_TOKEN_CAPTURE_TIMEOUT_SECONDS)


class FinishSessionDeadlineTest(unittest.IsolatedAsyncioTestCase):
    async def finish(self, deadline):
        page = MagicMock(goto=AsyncMock(), expect_response=MagicMock(side_effect=RuntimeError("no loyalty")))
        context = MagicMock(cookies=AsyncMock(return_value=[{"name": "synthetic", "value": "x"}]))
        future = asyncio.get_running_loop().create_future()
        future.set_result("synthetic-token")
        with self.assertLogs("amex-auth", "INFO") as logs:
            state = await main._finish_session(page, context, {}, future, MagicMock(), deadline=deadline)
        self.assertEqual(main._restore_cookies(state)[1], "synthetic-token")
        return page, "\n".join(logs.output)

    async def test_reward_points_are_skipped_when_the_initiate_budget_is_spent(self):
        page, logs = await self.finish(main.time.monotonic() + 5)
        self.assertIn("AMEX loyalty summary capture skipped", logs)
        page.expect_response.assert_not_called()

    async def test_reward_points_waits_are_cut_to_the_time_left(self):
        page, logs = await self.finish(main.time.monotonic() + 11)
        timeout = page.expect_response.call_args.kwargs["timeout"]
        self.assertLessEqual(timeout, 10_000)
        self.assertGreater(timeout, 8_000)

    async def test_without_a_deadline_the_reward_points_waits_are_unchanged(self):
        page, logs = await self.finish(None)
        self.assertEqual(page.expect_response.call_args.kwargs["timeout"], main.LOYALTY_CAPTURE_TIMEOUT_MS)
        self.assertIn("AMEX loyalty summary capture unavailable", logs)


class TrustedDeviceClickTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_failing_continue_click_is_tried_a_bounded_number_of_times(self):
        page = MagicMock(url=VERIFY_URL)
        page.locator.return_value.count = AsyncMock(return_value=1)
        page.locator.return_value.first.is_visible = AsyncMock(return_value=True)
        page.get_by_role.return_value.first.click = AsyncMock(side_effect=PlaywrightError("detached"))
        with patch.object(main, "OTP_POLL_SECONDS", 0.01), self.assertLogs("amex-auth", "WARNING") as logs:
            approved = await main._wait_for_otp_result(page, main.time.monotonic() + 0.3)
        self.assertFalse(approved)
        self.assertEqual(page.get_by_role.return_value.first.click.await_count, main.TRUST_DEVICE_CLICK_ATTEMPTS)
        self.assertEqual(len(logs.output), main.TRUST_DEVICE_CLICK_ATTEMPTS)
        for call in page.get_by_role.return_value.first.click.await_args_list:
            self.assertLessEqual(call.kwargs["timeout"], main.TRUST_DEVICE_CLICK_TIMEOUT_MS)


class TrustedDeviceOrderTest(unittest.IsolatedAsyncioTestCase):
    def page(self, *, visible=True, count_error=None):
        page = MagicMock(url="https://global.americanexpress.com/account/trust-device")
        page.locator.return_value.count = AsyncMock(return_value=1, side_effect=count_error)
        page.locator.return_value.first.is_visible = AsyncMock(return_value=visible)
        page.get_by_role.return_value.first.click = AsyncMock()
        return page

    async def test_the_interstitial_is_clicked_before_leaving_the_verify_flow_counts(self):
        # The one-time-code path: a URL outside two-step-verification is only
        # trusted once the trusted-device step has been continued.
        page = self.page()
        approved = await main._wait_for_otp_result(page, main.time.monotonic() + 1)
        self.assertTrue(approved)
        page.get_by_role.return_value.first.click.assert_awaited_once()

    async def test_a_navigation_error_while_looking_is_not_reported_as_a_failed_click(self):
        page = self.page(count_error=PlaywrightError("Execution context was destroyed"))
        page.url = VERIFY_URL
        with patch.object(main, "OTP_POLL_SECONDS", 0.01), self.assertNoLogs("amex-auth", "WARNING"):
            approved = await main._wait_for_otp_result(page, main.time.monotonic() + 0.1)
        self.assertFalse(approved)
        page.get_by_role.return_value.first.click.assert_not_awaited()


class AppApprovedTest(unittest.IsolatedAsyncioTestCase):
    async def approved(self, url, token=None):
        future = asyncio.get_running_loop().create_future()
        if token:
            future.set_result(token)
        return await main._app_approved(future)(MagicMock(url=url))

    async def test_only_a_positive_sign_counts_as_approved(self):
        self.assertTrue(await self.approved(DASHBOARD_URL))
        self.assertTrue(await self.approved("https://global.americanexpress.com/overview?x=1"))
        self.assertTrue(await self.approved(VERIFY_URL, token="synthetic-token"))
        self.assertFalse(await self.approved(VERIFY_URL))
        self.assertFalse(await self.approved(ERROR_URL))
        self.assertFalse(await self.approved("https://www.americanexpress.com/fr-fr/account/logout"))
        self.assertFalse(await self.approved("https://example.com/dashboard"))
        self.assertFalse(await self.approved("https://www.americanexpress.com/myca/logon/emea/action/login"))
        self.assertFalse(await self.approved("https://global.americanexpress.com/myca/error/session-expired"))
        self.assertFalse(await self.approved("https://global.americanexpress.com/dashboard/logon.do"))
        self.assertFalse(await self.approved(ERROR_URL + "?DestPage=https://global.americanexpress.com/dashboard"))
        self.assertFalse(await self.approved("https://global.americanexpress.com.example.com/dashboard"))
        self.assertFalse(await self.approved("https://notamericanexpress.com/dashboard"))



class VerifyPageDiagnosticsTest(unittest.IsolatedAsyncioTestCase):
    async def test_only_keywords_are_logged_never_the_page_text(self):
        page = MagicMock(url=VERIFY_URL + "?id=12345", evaluate=AsyncMock(return_value={
            "headings": ["Bonjour Jeanne Martin", "Vérification de sécurité : appareil de confiance"],
            "controls": [{"tag": "button", "type": "submit", "testid": "send-code",
                          "text": "Envoyer le code à b****d@example.com"},
                         {"tag": "input", "type": "radio", "testid": "channel-j***@gmail.com", "text": ""}],
        }))
        with self.assertLogs("amex-auth", "INFO") as logs:
            await main._log_verify_page_diagnostics(page)
        line = "\n".join(logs.output)
        self.assertIn("headings=['-', 'appareil+confiance+vérification+sécurité']", line)
        self.assertIn("button:submit:send-code:code", line)
        self.assertIn("input:radio:<masked>:-", line)
        self.assertIn("path=https://www.americanexpress.com/fr-fr/account/two-step-verification/verify", line)
        for leaked in ("Jeanne", "Martin", "example.com", "gmail", "12345"):
            self.assertNotIn(leaked, line)


if __name__ == "__main__":
    unittest.main()
