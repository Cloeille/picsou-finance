"""A cold container can miss the AWS WAF token. TR then answers the login with an
empty 403/405, which must reach the backend as a WAF block, not a credential error."""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException

import main


def run_initiate(waf_token, tr_response=None):
    """Runs /initiate with the given WAF token and TR answer. Returns the
    HTTPException (or None) and the requests sent to TR."""
    sent = []

    def handler(request):
        sent.append(request)
        return tr_response

    real_client = httpx.AsyncClient

    def client_with_tr(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    request = main.InitiateRequest(phoneNumber="+33600000000", pin="1234")
    with patch.object(main, "get_waf_token", AsyncMock(return_value=waf_token)), \
            patch.object(main.httpx, "AsyncClient", client_with_tr):
        try:
            asyncio.run(main.initiate(request))
        except HTTPException as error:
            return error, sent
    return None, sent


class InitiateWafRejectionTest(unittest.TestCase):
    def test_no_waf_token_is_a_waf_block_and_never_calls_tr(self):
        error, sent = run_initiate(None)

        self.assertEqual(error.status_code, 503)
        self.assertEqual(error.detail, main.WAF_BLOCKED)
        self.assertEqual(sent, [])

    def test_empty_403_or_405_from_tr_is_a_waf_block(self):
        for status in (403, 405):
            with self.subTest(status=status):
                error, sent = run_initiate("token", httpx.Response(status, content=b""))

                self.assertEqual(error.status_code, 503)
                self.assertEqual(error.detail, main.WAF_BLOCKED)
                self.assertEqual(sent[0].headers["x-aws-waf-token"], "token")

    def test_tr_credential_error_is_passed_through(self):
        error, _ = run_initiate(
            "token",
            httpx.Response(400, json={"errors": [{"errorCode": "PIN_INVALID"}]}),
        )

        self.assertEqual(error.status_code, 400)
        self.assertIn("PIN_INVALID", error.detail)
        self.assertNotIn(main.WAF_BLOCKED, error.detail)


class WafTokenRetryTest(unittest.TestCase):
    def test_a_second_browser_attempt_runs_when_the_first_gets_no_token(self):
        load = AsyncMock(side_effect=[None, "token"])
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertEqual(token, "token")
        self.assertEqual(load.await_count, 2)

    def test_gives_up_after_the_last_attempt(self):
        load = AsyncMock(return_value=None)
        with patch.object(main, "load_waf_token", load):
            token = asyncio.run(main.get_waf_token())

        self.assertIsNone(token)
        self.assertEqual(load.await_count, main.WAF_TOKEN_ATTEMPTS)


if __name__ == "__main__":
    unittest.main()
