"""Trade Republic sidecar: helpers and the endpoint contract the backend relies on, network mocked."""

import os
import unittest
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

import main

TR_REQUEST = httpx.Request("POST", "https://api.traderepublic.com/")
TEST_KEY = "tr-sidecar-test-key"
AUTH = {"X-Picsou-Sidecar-Key": TEST_KEY}
PROCESS_ID = "3f2b8c1e-9d4a-4b7e-8f60-2a1c5d9e7b03"


def tr_response(status, json=None, cookies=()):
    headers = [("set-cookie", f"{name}={value}; Path=/; Secure; HttpOnly") for name, value in cookies]
    return httpx.Response(status, json=json, headers=headers, request=TR_REQUEST)


class HelperTest(unittest.TestCase):
    def test_french_local_number_gets_the_country_code(self):
        self.assertEqual(main.normalise_phone(" 0612345678 "), "+33612345678")

    def test_international_number_is_kept(self):
        self.assertEqual(main.normalise_phone("+4915123456789"), "+4915123456789")

    def test_mask_phone_keeps_only_the_last_two_digits(self):
        self.assertEqual(main.mask_phone("+33612345678"), "****78")

    def test_mask_phone_hides_short_values_entirely(self):
        self.assertEqual(main.mask_phone("+3"), "****")

    def test_cookie_names_never_include_values(self):
        headers = httpx.Headers([("set-cookie", "tr_session=secret; Path=/"), ("set-cookie", "tr_refresh=other")])
        self.assertEqual(main.cookie_names(headers), ["tr_session", "tr_refresh"])

    def test_tr_headers_carry_the_waf_token_only_when_present(self):
        self.assertEqual(main.tr_headers("waf")["x-aws-waf-token"], "waf")
        self.assertNotIn("x-aws-waf-token", main.tr_headers(None))


class EndpointTest(unittest.TestCase):
    def setUp(self):
        self.post = AsyncMock()
        self.get = AsyncMock()
        for p in (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY}),
            patch.object(main, "get_waf_token", AsyncMock(return_value="waf")),
            patch.object(httpx.AsyncClient, "post", self.post),
            patch.object(httpx.AsyncClient, "get", self.get),
        ):
            p.start()
            self.addCleanup(p.stop)
        self.client = TestClient(main.app, headers=AUTH)

    def test_health(self):
        with patch.dict(os.environ):
            os.environ.pop("PICSOU_SIDECAR_BASE", None)
            self.assertEqual(self.client.get("/health").json(), {"status": "ok"})

    def test_initiate_returns_the_process_id(self):
        self.post.return_value = tr_response(200, json={"processId": "p-1"})

        resp = self.client.post("/initiate", json={"phoneNumber": "0612345678", "pin": "1234"})

        self.assertEqual(resp.json(), {"processId": "p-1"})
        self.assertEqual(self.post.call_args.kwargs["json"], {"phoneNumber": "+33612345678", "pin": "1234"})

    def test_initiate_passes_tr_rejections_through(self):
        self.post.return_value = tr_response(401, json={"error": "bad pin"})

        resp = self.client.post("/initiate", json={"phoneNumber": "0612345678", "pin": "0000"})

        self.assertEqual(resp.status_code, 401)

    def test_complete_returns_session_and_refresh_tokens(self):
        self.post.return_value = tr_response(200, cookies=[("tr_session", "s-1"), ("tr_refresh", "r-1")])

        resp = self.client.post("/complete", json={"processId": PROCESS_ID, "tan": "1234"})

        self.assertEqual(resp.json(), {"sessionToken": "s-1", "refreshToken": "r-1"})

    def test_complete_without_a_session_cookie_is_a_bad_gateway(self):
        self.post.return_value = tr_response(200)

        resp = self.client.post("/complete", json={"processId": PROCESS_ID, "tan": "1234"})

        self.assertEqual(resp.status_code, 502)

    def test_refresh_keeps_the_old_refresh_token_when_tr_sends_none(self):
        self.get.return_value = tr_response(200, cookies=[("tr_session", "s-2")])

        resp = self.client.post("/refresh", json={"refreshToken": "r-1"})

        self.assertEqual(resp.json(), {"sessionToken": "s-2", "refreshToken": "r-1"})


if __name__ == "__main__":
    unittest.main()
