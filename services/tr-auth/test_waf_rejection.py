"""A cold container can miss the AWS WAF token. TR then answers the login with an
empty 403/405, which must reach the backend as a WAF block, not a credential error."""

import asyncio
import time
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException

import main

INITIATE = (main.initiate, main.InitiateRequest(phoneNumber="+33600000000", pin="1234"))
COMPLETE = (main.complete, main.CompleteRequest(
    processId="0b6f3c1e-2d4a-4b8e-9f10-123456789abc", tan="1234"))


def run_endpoint(endpoint, waf_token, tr_response=None):
    """Runs /initiate or /complete with the given WAF token and TR answer (a
    response, or an async handler). Returns the HTTPException (or None) and the
    requests sent to TR."""
    handler_fn, request = endpoint
    sent = []

    def handler(tr_request):
        sent.append(tr_request)
        return tr_response(tr_request) if callable(tr_response) else tr_response

    real_client = httpx.AsyncClient

    def client_with_tr(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    with patch.object(main, "get_waf_token", AsyncMock(return_value=waf_token)), \
            patch.object(main.httpx, "AsyncClient", client_with_tr):
        try:
            asyncio.run(handler_fn(request))
        except HTTPException as error:
            return error, sent
    return None, sent


class WafRejectionTest(unittest.TestCase):
    def test_no_waf_token_is_a_waf_block_and_never_calls_tr(self):
        for name, endpoint in (("initiate", INITIATE), ("complete", COMPLETE)):
            with self.subTest(endpoint=name):
                error, sent = run_endpoint(endpoint, None)

                self.assertEqual(error.status_code, 503)
                self.assertEqual(error.detail, main.WAF_BLOCKED)
                self.assertEqual(sent, [])

    def test_empty_403_or_405_from_tr_is_a_waf_block(self):
        for name, endpoint in (("initiate", INITIATE), ("complete", COMPLETE)):
            for status in (403, 405):
                with self.subTest(endpoint=name, status=status):
                    error, sent = run_endpoint(
                        endpoint, "token", httpx.Response(status, content=b""))

                    self.assertEqual(error.status_code, 503)
                    self.assertEqual(error.detail, main.WAF_BLOCKED)
                    self.assertEqual(sent[0].headers["x-aws-waf-token"], "token")

    def test_tr_credential_error_is_passed_through(self):
        error, _ = run_endpoint(
            INITIATE,
            "token",
            httpx.Response(400, json={"errors": [{"errorCode": "PIN_INVALID"}]}),
        )

        self.assertEqual(error.status_code, 400)
        self.assertIn("PIN_INVALID", error.detail)
        self.assertNotIn(main.WAF_BLOCKED, error.detail)

    def test_tr_verification_code_error_is_passed_through(self):
        error, _ = run_endpoint(
            COMPLETE,
            "token",
            httpx.Response(400, json={"errors": [{"errorCode": "VALIDATION_CODE_INVALID"}]}),
        )

        self.assertEqual(error.status_code, 400)
        self.assertIn("VALIDATION_CODE_INVALID", error.detail)
        self.assertNotIn(main.WAF_BLOCKED, error.detail)

    def test_tr_call_stops_at_its_reserved_timeout(self):
        async def slow_tr(_):
            await asyncio.sleep(2)
            return httpx.Response(200, json={"processId": "late"})

        for name, endpoint in (("initiate", INITIATE), ("complete", COMPLETE)):
            with self.subTest(endpoint=name):
                started = time.monotonic()
                with patch.object(main, "TR_REQUEST_TIMEOUT_S", 0.2), \
                        self.assertRaises(TimeoutError):
                    run_endpoint(endpoint, "token", slow_tr)

                self.assertLess(time.monotonic() - started, 1)


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

    def test_a_failure_on_the_last_attempt_is_raised(self):
        load = AsyncMock(side_effect=RuntimeError("browser launch failed"))
        with patch.object(main, "load_waf_token", load), \
                self.assertRaises(RuntimeError):
            asyncio.run(main.get_waf_token())

        self.assertEqual(load.await_count, main.WAF_TOKEN_ATTEMPTS)

    def test_gives_up_after_the_last_attempt(self):
        load = AsyncMock(return_value=None)
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertIsNone(token)
        self.assertEqual(load.await_count, main.WAF_TOKEN_ATTEMPTS)

    def test_attempts_share_one_deadline(self):
        # An empty first attempt eats most of the budget; the second would get its
        # token only after the budget, when the backend may already have given up.
        load = slow_attempts((0.2, None), (5, "late"))
        started = time.monotonic()
        with patch.object(main, "WAF_TOKEN_BUDGET_S", 0.3), \
                patch.object(main, "load_waf_token", load):
            token = asyncio.run(asyncio.wait_for(main.get_waf_token(), 2))

        self.assertIsNone(token)
        self.assertLess(time.monotonic() - started, 1)


if __name__ == "__main__":
    unittest.main()
