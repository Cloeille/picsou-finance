"""/portfolio builds a DEGIRO path from the session blob: only well-formed values reach it."""

import json
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi import HTTPException
from fastapi.testclient import TestClient

import main

TEST_KEY = "degiro-sidecar-test-key"
SESSION_ID = "8F1A2B3C4D5E6F708192A3B4C5D6E7F8.prod_b_112_1"


def _blob(session_id, int_account):
    return json.dumps({"sessionId": session_id, "intAccount": int_account})


class PortfolioEndpointTest(unittest.TestCase):
    def setUp(self):
        self.get = AsyncMock(return_value=httpx.Response(
            401, request=httpx.Request("GET", "https://trader.degiro.nl/")))
        for p in (
            patch.object(main, "SIDECAR_API_KEY", TEST_KEY),
            patch.object(httpx.AsyncClient, "get", self.get),
        ):
            p.start()
            self.addCleanup(p.stop)

    def _portfolio(self, blob):
        with TestClient(main.app) as client:
            return client.post("/portfolio", json={"sessionBlob": blob},
                               headers={"X-Picsou-Sidecar-Key": TEST_KEY})

    def test_a_valid_blob_calls_the_expected_path(self):
        response = self._portfolio(_blob(SESSION_ID, 31415926))

        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.get.call_args.args[0],
                         f"/trading/secure/v5/update/31415926;jsessionid={SESSION_ID}")

    def test_a_numeric_string_account_is_coerced_to_an_int(self):
        self._portfolio(_blob(SESSION_ID, "31415926"))

        self.assertEqual(self.get.call_args.args[0],
                         f"/trading/secure/v5/update/31415926;jsessionid={SESSION_ID}")

    def test_malicious_int_accounts_get_400_and_never_reach_degiro(self):
        for value in ("1/../../pa/secure/client", "1?x=1", "1#f", "abc", None, [1], {"a": 1}):
            with self.subTest(intAccount=value):
                self.assertEqual(self._portfolio(_blob(SESSION_ID, value)).status_code, 400)
        self.get.assert_not_called()

    def test_malicious_session_ids_get_400_and_never_reach_degiro(self):
        for value in ("a/b", "a?b=1", "a#b", "a%2Fb", "a;b", "a@b", "a b", "a\nb", "", 123, None, "x" * 257):
            with self.subTest(sessionId=value):
                self.assertEqual(self._portfolio(_blob(value, 31415926)).status_code, 400)
        self.get.assert_not_called()


class FetchIntAccountTest(unittest.IsolatedAsyncioTestCase):
    async def _fetch(self, int_account):
        client = AsyncMock()
        client.get.return_value = httpx.Response(
            200, json={"data": {"intAccount": int_account}},
            request=httpx.Request("GET", "https://trader.degiro.nl/pa/secure/client"))
        return await main._fetch_int_account(client, SESSION_ID)

    async def test_returns_an_int(self):
        self.assertEqual(await self._fetch("31415926"), 31415926)

    async def test_a_non_numeric_account_is_a_502(self):
        with self.assertRaises(HTTPException) as caught:
            await self._fetch("1/../x")
        self.assertEqual(caught.exception.status_code, 502)


if __name__ == "__main__":
    unittest.main()
