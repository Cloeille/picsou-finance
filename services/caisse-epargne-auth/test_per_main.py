"""Offline checks for PER transport wiring into the production accounts entry point."""

import copy
import unittest
import urllib.parse
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, call, patch

import httpx
from fastapi.testclient import TestClient

import main
import per_client
import replay
import test_fetcher
from test_fetcher import FIXTURES, FakeCE
from test_per_client import API_HOST, INSURER_HOST, JWT, routing_body
from test_replay import make_state


CONTRACT = "00000     PERIC12345678"
DETAIL = {"detailContratVie": {"valorisation": {}}}


class PerMainWiringTest(unittest.IsolatedAsyncioTestCase):
    async def test_per_reader_uses_the_per_chain_token_not_the_main_one(self):
        state = "synthetic-session-state"
        result = {"accounts": [], "unsupported": []}

        async def tokens(_state, *, transport, chain=replay.MAIN_CHAIN):
            return SimpleNamespace(access_token=f"synthetic-{chain.name}-token")

        async def fetch(access_token, **kwargs):
            self.assertEqual(access_token, "synthetic-main-token")
            self.assertIs(await kwargs["per_detail"](CONTRACT, "00000"), DETAIL)
            return result

        with patch.object(main._token_cache, "get", AsyncMock(side_effect=tokens)) as get, \
             patch.object(main.fetcher, "fetch_import", AsyncMock(side_effect=fetch)), \
             patch.object(per_client, "fetch_per_detail", AsyncMock(return_value=DETAIL)) as insurer:
            self.assertIs(await main._fetch_once(state), result)
        insurer.assert_awaited_once_with(
            "synthetic-per-token", CONTRACT, entity_code="00000",
            routing_token="synthetic-main-token", transport=main._TRANSPORT,
        )
        self.assertIn(call(state, transport=main._TRANSPORT, chain=replay.PER_CHAIN), get.await_args_list)

    async def test_no_per_account_does_not_mint_a_per_token_or_contact_the_insurer(self):
        get = AsyncMock(return_value=SimpleNamespace(access_token="synthetic"))
        with patch.object(main._token_cache, "get", get), \
             patch.object(main.fetcher, "fetch_import", AsyncMock(return_value={"accounts": []})), \
             patch.object(per_client, "fetch_per_detail", AsyncMock()) as insurer:
            await main._fetch_once("synthetic-state")
        insurer.assert_not_awaited()
        self.assertTrue(all(c.kwargs.get("chain") is not replay.PER_CHAIN for c in get.await_args_list))

    async def run_per_detail(self, insurer_side_effect):
        captured = {}

        async def fetch(_access_token, **kwargs):
            captured["per_detail"] = kwargs["per_detail"]
            return {"accounts": []}

        cache = SimpleNamespace(
            get=AsyncMock(return_value=SimpleNamespace(access_token="synthetic-token")),
            evict=Mock(),
        )
        insurer = AsyncMock(side_effect=insurer_side_effect)
        with patch.object(main, "_token_cache", cache), \
             patch.object(main.fetcher, "fetch_import", AsyncMock(side_effect=fetch)), \
             patch.object(per_client, "fetch_per_detail", insurer):
            await main._fetch_once("synthetic-state")
            try:
                return await captured["per_detail"](CONTRACT, "00000"), insurer, cache
            except replay.ReplayError as exc:
                return exc, insurer, cache

    async def test_an_expired_per_token_is_evicted_and_retried_once(self):
        outcome, insurer, cache = await self.run_per_detail([replay.SessionExpired("x"), DETAIL])

        self.assertIs(outcome, DETAIL)
        self.assertEqual(insurer.await_count, 2)
        cache.evict.assert_called_once_with("synthetic-state", chain=replay.PER_CHAIN)

    async def test_a_second_refusal_leaves_only_the_per_out(self):
        outcome, insurer, _ = await self.run_per_detail([replay.SessionExpired("x")] * 2)

        # An UpstreamError, not SessionExpired: the fetcher isolates it to the PER.
        self.assertIsInstance(outcome, replay.UpstreamError)
        self.assertNotIsInstance(outcome, replay.SessionExpired)
        self.assertEqual(insurer.await_count, 2)

    async def test_other_per_errors_are_not_retried(self):
        outcome, insurer, cache = await self.run_per_detail([replay.UpstreamError("x")])

        self.assertIsInstance(outcome, replay.UpstreamError)
        self.assertEqual(insurer.await_count, 1)
        cache.evict.assert_not_called()


class PerAccountsHttpTest(unittest.TestCase):
    def test_full_accounts_flow_mints_the_per_token_then_imports_owned_uc_and_euro_pocket(self):
        source = copy.deepcopy(FIXTURES["synthesis_products"])
        per_contract = next(item for item in source["items"]
                            if item["identity"]["productFamilyPFM"]["code"] == "4")
        per_contract["identification"]["contractId"]["id"] = CONTRACT
        ce = FakeCE(synth=source)
        insurer_requests = []
        token_forms = []

        def handle(request):
            if request.url.host == API_HOST and request.url.path.endswith("/prepareRouting"):
                insurer_requests.append(request)
                return httpx.Response(201, json=routing_body(
                    producer="PERIC876543", cdetab="00000", entity="00000"))
            if request.url.host == API_HOST and "/clientToken/" in request.url.path:
                insurer_requests.append(request)
                self.assertTrue(request.url.path.endswith("/clientToken/PERIC876543"))
                return httpx.Response(200, headers={"iw4-jwt-token": JWT})
            if request.url.host == INSURER_HOST:
                insurer_requests.append(request)
                self.assertEqual(request.url.path, "/etna-web/rest/v2/contratVie/load/PER/IC/876543")
                return httpx.Response(200, json=test_fetcher.PerHoldingsImportTest.payload())
            if request.url.path.endswith("/api/oauth/v2/token"):
                token_forms.append(dict(urllib.parse.parse_qsl(request.content.decode())))
            return ce.handle(request)

        with patch.object(main, "SIDECAR_API_KEY", "synthetic-test-key"), \
             patch.object(main, "_token_cache", replay.TokenCache()), \
             patch.object(main, "_TRANSPORT", httpx.MockTransport(handle)), \
             TestClient(main.app) as client:
            response = client.post("/accounts", json={"sessionState": make_state()},
                                   headers={"X-Picsou-Sidecar-Key": "synthetic-test-key"})

        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        per = next(account for account in body["accounts"] if account["kind"] == "PER")
        self.assertEqual(per["balance"], "1700")
        self.assertEqual(per["per"]["euroFundBalance"], "1000")
        self.assertEqual(per["per"]["positions"][0]["quantity"], "10")
        self.assertEqual(per["per"]["positions"][0]["isin"], "XX0000000001")
        self.assertFalse(any(item["kind"] == "PER" for item in body["unavailable"]))
        self.assertEqual([r.url.host for r in insurer_requests], [API_HOST, API_HOST, INSURER_HOST])
        self.assertTrue(all("cookie" not in r.headers for r in insurer_requests))
        per_forms = [f for f in token_forms if f.get("client_id") == replay.PER_CLIENT_ID]
        self.assertEqual(len(per_forms), 1)
        self.assertEqual(per_forms[0]["redirect_uri"], replay.PER_REDIRECT_URI)

    def test_an_insurer_failure_leaves_only_the_per_unavailable(self):
        source = copy.deepcopy(FIXTURES["synthesis_products"])
        per_contract = next(item for item in source["items"]
                            if item["identity"]["productFamilyPFM"]["code"] == "4")
        per_contract["identification"]["contractId"]["id"] = CONTRACT
        ce = FakeCE(synth=source)

        def handle(request):
            if request.url.host == API_HOST and request.url.path.endswith("/prepareRouting"):
                return httpx.Response(201, json=routing_body(
                    producer="PERIC876543", cdetab="00000", entity="00000"))
            if request.url.host == API_HOST and "/clientToken/" in request.url.path:
                return httpx.Response(461)
            return ce.handle(request)

        with patch.object(main, "SIDECAR_API_KEY", "synthetic-test-key"), \
             patch.object(main, "_token_cache", replay.TokenCache()), \
             patch.object(main, "_TRANSPORT", httpx.MockTransport(handle)), \
             TestClient(main.app) as client:
            response = client.post("/accounts", json={"sessionState": make_state()},
                                   headers={"X-Picsou-Sidecar-Key": "synthetic-test-key"})

        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual([item["kind"] for item in body["unavailable"]], ["PER"])
        self.assertFalse(any(account["kind"] == "PER" for account in body["accounts"]))
        self.assertTrue(any(account["kind"] == "CURRENT_ACCOUNT" for account in body["accounts"]))


if __name__ == "__main__":
    unittest.main()
