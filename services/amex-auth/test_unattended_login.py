"""Unattended re-login: previous-session replay, no code request, trusted device."""

import asyncio
import json
import logging
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException
from picsou_sidecar import PendingStore

import main

PREVIOUS_COOKIES = [
    {
        "name": "sid", "value": "synthetic-value", "domain": ".example.test", "path": "/",
        "expires": 1_900_000_000.5, "httpOnly": True, "secure": True, "sameSite": "Lax",
        "partitionKey": "synthetic", "extra": 1,
    },
    {"name": "session-only", "value": "synthetic", "domain": "example.test", "path": "/", "expires": -1},
]


def previous_state(**extra) -> str:
    return json.dumps({"cookies": PREVIOUS_COOKIES, **extra})


class InitiateHarness(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.calls: list[str] = []
        self.context = MagicMock(close=AsyncMock())
        self.browser = MagicMock(close=AsyncMock())
        self.camoufox = MagicMock(__aexit__=AsyncMock())
        self.page = MagicMock()
        self.camoufox.__aenter__ = AsyncMock(return_value=self.browser)
        self.browser.new_context = AsyncMock(return_value=self.context)
        self.context.new_page = AsyncMock(return_value=self.page)
        self.context.add_cookies = AsyncMock(side_effect=lambda cookies: self.calls.append("add_cookies"))
        self.context.cookies = AsyncMock(return_value=[{"name": "fresh", "value": "synthetic"}])
        self.page.goto = AsyncMock(side_effect=lambda *a, **k: self.calls.append("goto"))
        field = self.page.locator.return_value.first
        field.wait_for = AsyncMock()
        field.press_sequentially = AsyncMock()
        field.input_value = AsyncMock(return_value="synthetic")
        field.fill = AsyncMock()
        field.click = AsyncMock()
        self.page.locator.return_value.click = AsyncMock()
        self.select_otp = AsyncMock()
        self.store = PendingStore(
            dispose=main._dispose_pending_state,
            log=logging.getLogger("test"),
            max_size=main.MAX_PENDING,
        )
        self.addAsyncCleanup(self.store.close_all)

    async def open_login_form(self, page):
        await page.goto(main.LOGIN_URL)

    def run_initiate(self, outcome, **fields):
        patches = (
            patch.object(main, "_pending", self.store),
            patch.object(main, "AsyncCamoufox", return_value=self.camoufox),
            # The form wait has its own tests (test_login_form.py); here it only opens the page.
            patch.object(main, "_open_login_form", AsyncMock(side_effect=self.open_login_form)),
            patch.object(main, "_wait_for_login_outcome", AsyncMock(return_value=outcome)),
            patch.object(main, "_select_otp_method", self.select_otp),
            patch.object(main, "_find_otp_input", AsyncMock()),
        )
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        return main.initiate(main.InitiateRequest(
            userId="synthetic", password="synthetic", method="sms", **fields,
        ))

    def assert_resources_closed(self):
        self.context.close.assert_awaited_once()
        self.browser.close.assert_awaited_once()
        self.camoufox.__aexit__.assert_awaited_once_with(None, None, None)


class UnattendedVerifyTest(InitiateHarness):
    async def test_unattended_verify_answers_session_expired_without_selecting_a_channel(self):
        with self.assertLogs(main.log, level="INFO") as logs:
            with self.assertRaises(HTTPException) as caught:
                await self.run_initiate("verify", unattended=True)
        self.assertEqual(caught.exception.status_code, 401)
        self.assertEqual(caught.exception.detail, "SESSION_EXPIRED")
        self.select_otp.assert_not_awaited()
        self.assertEqual(len(self.store), 0)
        self.assert_resources_closed()
        self.assertTrue(any("AMEX unattended re-login stopped at the verify page" in m for m in logs.output))

    async def test_attended_verify_still_selects_the_channel_and_parks_the_browser(self):
        result = await self.run_initiate("verify")
        self.select_otp.assert_awaited_once_with(self.page, "sms")
        self.assertTrue(result["mfaRequired"])
        self.assertIn(result["processId"], self.store)
        self.context.close.assert_not_awaited()


class PreviousSessionTest(InitiateHarness):
    async def test_previous_cookies_are_added_before_the_login_page_opens(self):
        with self.assertRaises(HTTPException):
            await self.run_initiate("verify", sessionState=previous_state(accountToken="t"), unattended=True)
        self.assertEqual(self.calls[:2], ["add_cookies", "goto"])
        added = self.context.add_cookies.await_args.args[0]
        self.assertEqual(len(added), 2)
        self.assertEqual(
            added[0],
            {
                "name": "sid", "value": "synthetic-value", "domain": ".example.test", "path": "/",
                "expires": 1_900_000_000.5, "httpOnly": True, "secure": True, "sameSite": "Lax",
            },
        )
        self.assertNotIn("expires", added[1])

    async def test_unreadable_session_state_is_ignored_and_the_login_continues(self):
        with self.assertLogs(main.log, level="WARNING") as logs:
            result = await self.run_initiate("verify", sessionState="not-json")
        self.context.add_cookies.assert_not_awaited()
        self.assertTrue(any("AMEX previous session not reused error_type=" in m for m in logs.output))
        self.assertTrue(result["mfaRequired"])
        self.assertIn("goto", self.calls)

    async def test_cookies_rejected_by_the_browser_do_not_fail_the_login(self):
        self.context.add_cookies = AsyncMock(side_effect=main.PlaywrightError("synthetic rejection"))
        with self.assertLogs(main.log, level="WARNING") as logs:
            result = await self.run_initiate("verify", sessionState=previous_state())
        self.assertTrue(any("AMEX previous session not reused error_type=Error" in m for m in logs.output))
        self.assertTrue(result["mfaRequired"])
        self.assertNotIn("synthetic-value", "".join(logs.output))


class SuccessPathTest(InitiateHarness):
    async def test_success_returns_a_complete_session_with_the_account_token(self):
        token_future = asyncio.get_running_loop().create_future()
        token_future.set_result("synthetic-token")
        with patch.object(
            main, "_capture_account_token_header", return_value=(token_future, MagicMock()),
        ):
            result = await self.run_initiate("success")
        state = json.loads(result["sessionState"])
        self.assertFalse(result["mfaRequired"])
        self.assertEqual(state["accountToken"], "synthetic-token")
        self.assertEqual(state["cookies"], [{"name": "fresh", "value": "synthetic"}])
        self.select_otp.assert_not_awaited()
        self.assert_resources_closed()


class TrustDeviceTest(unittest.IsolatedAsyncioTestCase):
    async def test_checkbox_is_ticked_before_continuer(self):
        calls: list[str] = []
        store = PendingStore(
            dispose=main._dispose_pending_state, log=logging.getLogger("test"), max_size=main.MAX_PENDING,
        )
        self.addAsyncCleanup(store.close_all)
        page = MagicMock(url="https://synthetic.example/two-step-verification")
        context = MagicMock(close=AsyncMock())
        browser = MagicMock(close=AsyncMock())
        camoufox = MagicMock(__aexit__=AsyncMock())

        inner = MagicMock(count=AsyncMock(return_value=1))
        inner.first.check = AsyncMock(side_effect=lambda **k: calls.append("tick"))
        trust = MagicMock(count=AsyncMock(return_value=1))
        trust.first.is_visible = AsyncMock(return_value=True)
        trust.first.locator.return_value = inner
        submit_candidates = MagicMock(count=AsyncMock(return_value=0))
        page.locator.side_effect = lambda selector: trust if selector == main.TRUST_DEVICE_CHECKBOX_SELECTOR else submit_candidates
        role = MagicMock(count=AsyncMock(return_value=0))
        role.first.click = AsyncMock(side_effect=lambda: calls.append("continue"))
        page.get_by_role.return_value = role

        otp_input = MagicMock(
            fill=AsyncMock(), input_value=AsyncMock(return_value="1234"), press=AsyncMock(),
        )
        await store.put("attempt", {
            "created_at": time.time(), "page": page, "context": context,
            "browser": browser, "camoufox": camoufox,
        })
        reached = iter([False, True])
        with (
            patch.object(main, "_pending", store),
            patch.object(main, "_find_otp_input", AsyncMock(return_value=otp_input)),
            patch.object(main, "_dashboard_reached", AsyncMock(side_effect=lambda _p: next(reached))),
            patch.object(main, "_capture_post_login_session", AsyncMock(return_value="{}")),
        ):
            result = await main.complete(main.CompleteRequest(processId="attempt", otp="1234"))
        self.assertEqual(result, {"sessionState": "{}"})
        self.assertEqual(calls, ["tick", "continue"])


if __name__ == "__main__":
    unittest.main()
