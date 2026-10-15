"""Offline checks for PER transport wiring into the production accounts entry point."""

import copy
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

import main
import per_client
import replay
import test_fetcher
from test_fetcher import FIXTURES, FakeCE
from test_per_client import API_HOST, INSURER_HOST, routing_response
from test_replay import make_state


class PerMainWiringTest(unittest.IsolatedAsyncioTestCase):
    async def test_accounts_supplies_insurer_reader_using_the_current_token_and_same_session(self):
        state = "synthetic-session-state"
        token = "synthetic-token"
        contract = "00000     PERIC12345678"
        detail = {"detailContratVie": {"valorisation": {}}}
        result = {"accounts": [], "unsupported": []}

        async def fetch(access_token, **kwargs):
            self.assertEqual(access_token, token)
            self.assertIs(await kwargs["per_detail"](contract, "00000"), detail)
            return result

        with patch.object(main._token_cache, "get", AsyncMock(return_value=SimpleNamespace(access_token=token))), \
             patch.object(main.fetcher, "fetch_import", AsyncMock(side_effect=fetch)), \
             patch.object(per_client, "fetch_per_detail", AsyncMock(return_value=detail)) as insurer:
            self.assertIs(await main._fetch_once(state), result)
            insurer.assert_awaited_once_with(
                token, state, contract, entity_code="00000", transport=main._TRANSPORT
            )

    async def test_no_per_account_does_not_contact_the_insurer(self):
        with patch.object(main._token_cache, "get", AsyncMock(return_value=SimpleNamespace(access_token="synthetic"))), \
             patch.object(main.fetcher, "fetch_import", AsyncMock(return_value={"accounts": []})), \
             patch.object(per_client, "fetch_per_detail", AsyncMock()) as insurer:
            await main._fetch_once("synthetic-state")
            insurer.assert_not_awaited()


class PerAccountsHttpTest(unittest.TestCase):
    def test_full_accounts_flow_routes_then_imports_owned_uc_and_euro_pocket(self):
        source = copy.deepcopy(FIXTURES["synthesis_products"])
        per_contract = next(item for item in source["items"]
                            if item["identity"]["productFamilyPFM"]["code"] == "4")
        per_contract["identification"]["contractId"]["id"] = "00000     PERIC12345678"
        ce = FakeCE(synth=source)
        insurer_requests = []
        routing_requests = []

        def handle(request):
            if request.url.host == API_HOST and request.url.path.endswith("/prepareRouting"):
                routing_requests.append(request)
                return httpx.Response(200, json=routing_response())
            if request.url.host == INSURER_HOST:
                insurer_requests.append(request)
                if request.url.path == "/bootstrap":
                    return httpx.Response(200, headers={"Set-Cookie": "insurer=synthetic; Path=/"})
                self.assertEqual(request.url.path, "/espaceinternet-ce/rest/v2/contratVie/load/PER/IC/12345678")
                self.assertIn("insurer=synthetic", request.headers.get("cookie", ""))
                return httpx.Response(200, json=test_fetcher.PerHoldingsImportTest.payload())
            return ce.handle(request)

        with patch.object(main, "SIDECAR_API_KEY", "synthetic-test-key"), \
             patch.object(main, "_token_cache", replay.TokenCache()), \
             patch.object(main, "_TRANSPORT", httpx.MockTransport(handle)), \
             TestClient(main.app) as client:
            response = client.post("/accounts", json={"sessionState": make_state()},
                                   headers={"X-Picsou-Sidecar-Key": "synthetic-test-key"})

        self.assertEqual(response.status_code, 200, response.text)
        per = next(account for account in response.json()["accounts"] if account["kind"] == "PER")
        self.assertEqual(per["balance"], "1700")
        self.assertEqual(per["per"]["euroFundBalance"], "1000")
        self.assertEqual(per["per"]["positions"][0]["quantity"], "10")
        self.assertEqual(per["per"]["positions"][0]["isin"], "XX0000000001")
        self.assertEqual(len(routing_requests), 1)
        self.assertEqual(len(insurer_requests), 2)
        self.assertTrue(all("authorization" not in request.headers for request in insurer_requests))


if __name__ == "__main__":
    unittest.main()
