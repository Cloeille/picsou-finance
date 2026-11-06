"""The AWS WAF token comes from a headless browser; a cold container can miss it."""

import asyncio
import time
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

import main


def slow_attempts(*attempts):
    """A load_waf_token stand-in: each call sleeps, then returns its token."""
    remaining = list(attempts)

    async def load():
        seconds, token = remaining.pop(0)
        await asyncio.sleep(seconds)
        return token

    return load


class WafTokenRetryTest(unittest.TestCase):
    def test_a_second_browser_attempt_runs_when_the_first_gets_no_token(self):
        load = AsyncMock(side_effect=[None, "token"])
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertEqual(token, "token")
        self.assertEqual(load.await_count, 2)

    def test_a_second_browser_attempt_runs_when_the_first_fails(self):
        load = AsyncMock(side_effect=[RuntimeError("browser launch failed"), "token"])
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertEqual(token, "token")
        self.assertEqual(load.await_count, 2)

    def test_a_failure_on_the_last_attempt_is_upstream_unavailable(self):
        load = AsyncMock(side_effect=RuntimeError("browser launch failed"))
        with patch.object(main, "load_waf_token", load), self.assertRaises(HTTPException) as raised:
            asyncio.run(main.get_waf_token())

        self.assertEqual((raised.exception.status_code, raised.exception.detail), (503, "UPSTREAM_UNAVAILABLE"))
        self.assertEqual(load.await_count, main.WAF_TOKEN_ATTEMPTS)

    def test_gives_up_after_the_last_attempt(self):
        load = AsyncMock(return_value=None)
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertIsNone(token)
        self.assertEqual(load.await_count, main.WAF_TOKEN_ATTEMPTS)

    def test_attempts_share_one_deadline(self):
        load = slow_attempts((0.2, None), (5, "late"))
        started = time.monotonic()
        with patch.object(main, "WAF_TOKEN_BUDGET_S", 0.3), patch.object(main, "load_waf_token", load):
            token = asyncio.run(asyncio.wait_for(main.get_waf_token(), 2))

        self.assertIsNone(token)
        self.assertLess(time.monotonic() - started, 1)

    def test_the_budget_leaves_room_for_the_tr_call_inside_the_backend_timeout(self):
        self.assertLess(main.WAF_TOKEN_BUDGET_S + main.TR_REQUEST_TIMEOUT_S, main.CALLER_TIMEOUT_S)


if __name__ == "__main__":
    unittest.main()
