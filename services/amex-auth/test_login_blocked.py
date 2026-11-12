"""Akamai refusing AMEX's login request is told apart from a silent timeout."""

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException

import main

LOGIN_POST = "https://global.americanexpress.com/myca/logon/emea/action/login"
CHALLENGE = "https://global.americanexpress.com/api/CreateDeviceIdentityVerificationChallenge.v1"


def _response(url, status, server="AkamaiGHost"):
    return SimpleNamespace(url=url, status=status, headers={"server": server})


class LoginPostWatchTest(unittest.TestCase):
    def test_two_403s_in_a_row_on_the_login_request_is_a_block(self):
        watch = main._LoginPostWatch()
        watch.on_response(_response(LOGIN_POST, 403))
        self.assertFalse(watch.blocked)
        watch.on_response(_response(LOGIN_POST + "?x=1", 403))
        self.assertTrue(watch.blocked)
        self.assertTrue(watch.akamai_edge)

    def test_a_403_on_another_request_does_not_count(self):
        watch = main._LoginPostWatch()
        watch.on_response(_response(CHALLENGE, 403))
        watch.on_response(_response(CHALLENGE, 403))
        self.assertFalse(watch.blocked)
        self.assertEqual(watch.responses, 0)

    def test_an_answered_login_request_resets_the_run(self):
        watch = main._LoginPostWatch()
        watch.on_response(_response(LOGIN_POST, 403, server="nginx"))
        watch.on_response(_response(LOGIN_POST, 200))
        watch.on_response(_response(LOGIN_POST, 403, server="nginx"))
        self.assertFalse(watch.blocked)
        self.assertFalse(watch.akamai_edge)


class BlockedInitiateTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.context = MagicMock(close=AsyncMock())
        self.browser = MagicMock(close=AsyncMock(), new_context=AsyncMock(return_value=self.context))
        self.camoufox = MagicMock(__aenter__=AsyncMock(return_value=self.browser), __aexit__=AsyncMock())
        self.page = MagicMock(url=main.LOGIN_URL, goto=AsyncMock(), add_locator_handler=AsyncMock())
        self.handlers = []
        self.page.on = lambda event, handler: self.handlers.append(handler)
        self.context.new_page = AsyncMock(return_value=self.page)
        field = self.page.locator.return_value.first
        field.press_sequentially = AsyncMock()
        field.input_value = AsyncMock(return_value="synthetic")
        field.click = AsyncMock(side_effect=self.akamai_refuses_the_login)

    async def akamai_refuses_the_login(self, *_, **__):
        # What the page does on submit when Akamai scores the browser as a bot.
        for _ in range(6):
            for handler in self.handlers:
                handler(_response(LOGIN_POST, 403))

    async def initiate(self):
        with (
            patch.object(main, "AsyncCamoufox", return_value=self.camoufox),
            patch.object(main, "_open_login_form", AsyncMock()),
            patch.object(main, "_dashboard_reached", AsyncMock(return_value=False)),
            patch.object(main, "_invalid_credentials_visible", AsyncMock(return_value=False)),
            patch.object(main, "_otp_challenge_visible", AsyncMock(return_value=False)),
        ):
            return await asyncio.wait_for(
                main.initiate(main.InitiateRequest(userId="synthetic", password="synthetic", method="sms")),
                timeout=5,
            )

    async def test_blocked_login_fails_at_once_and_says_why_in_the_log(self):
        with self.assertLogs("amex-auth", "WARNING") as logs, self.assertRaises(HTTPException) as caught:
            await self.initiate()
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (503, "UPSTREAM_UNAVAILABLE"))
        self.assertIn("AMEX login blocked by anti-bot login_post_403=6 akamai_edge=True", "\n".join(logs.output))
        self.assertNotIn("synthetic", "\n".join(logs.output))
        self.context.close.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
