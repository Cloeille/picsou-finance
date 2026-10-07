"""HTTP-level tests for the Caisse d'Epargne sidecar: shared-key boundary and /token-check.

The bank is played by `httpx.MockTransport` (see test_replay.FakeBank); no
network, no real credentials, no bank data.
"""

import asyncio
import io
import json
import logging
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

import main
import replay
from test_fetcher import DATA_HOST, FakeCE, default_pages
from test_replay import (
    ALL_SECRETS,
    SECRET_TOKEN,
    FakeBank,
    make_params,
    make_state,
)

TEST_KEY = "caisse-epargne-sidecar-test-key"
CHALLENGE = "Picsou-Sidecar-Key"
HEADERS = {"X-Picsou-Sidecar-Key": TEST_KEY}


class SidecarAuthenticationTest(unittest.TestCase):
    def setUp(self):
        self.key_patch = patch.object(main, "SIDECAR_API_KEY", TEST_KEY)
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.client = TestClient(main.app)

    def test_unauthenticated_and_invalid_key_requests_are_challenged_before_routing(self):
        requests = (
            ("GET", "/docs", None),
            ("GET", "/openapi.json", None),
            ("POST", "/token-check", {"sessionState": make_state()}),
            ("POST", "/token-check", "{"),
        )
        invalid_headers = ({}, {"X-Picsou-Sidecar-Key": "wrong-key"}, {"X-Picsou-Sidecar-Key": ""})

        with self.client as client:
            response = client.get("/not-a-route")
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)

            for method, path, payload in requests:
                for headers in invalid_headers:
                    with self.subTest(method=method, path=path, headers=headers):
                        if isinstance(payload, str):
                            response = client.request(method, path, headers=headers, content=payload)
                        else:
                            response = client.request(method, path, headers=headers, json=payload)
                        self.assertEqual(response.status_code, 401)
                        self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
                        self.assertEqual(response.headers.get("WWW-Authenticate"), CHALLENGE)

    def test_wrong_key_never_reaches_the_bank(self):
        bank = FakeBank()
        with patch.object(main, "_TRANSPORT", bank.transport()), self.client as client:
            client.post(
                "/token-check",
                json={"sessionState": make_state()},
                headers={"X-Picsou-Sidecar-Key": "wrong-key"},
            )

        self.assertEqual(bank.requests, [])

    def test_health_is_accessible_without_a_key(self):
        with self.client as client:
            response = client.get("/health")

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_authorized_request_reaches_validation_without_a_challenge(self):
        with self.client as client:
            response = client.post("/token-check", json={}, headers=HEADERS)

        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_non_ascii_key_authenticates_as_utf8_wire_bytes(self):
        with patch.object(main, "SIDECAR_API_KEY", "clé"):
            with TestClient(main.app) as client:
                response = client.get(
                    "/docs", headers=[(b"X-Picsou-Sidecar-Key", "clé".encode("utf-8"))]
                )

        self.assertEqual(response.status_code, 200)
        self.assertNotIn("WWW-Authenticate", response.headers)

    def test_startup_refuses_a_missing_or_blank_key(self):
        for missing_key in ("", "   "):
            with self.subTest(key=repr(missing_key)), patch.object(
                main, "SIDECAR_API_KEY", missing_key
            ):
                with self.assertRaisesRegex(RuntimeError, "APP_SIDECAR_API_KEY"):
                    with TestClient(main.app):
                        self.fail("startup should refuse a missing key")

    def test_a_blank_key_never_authenticates_even_if_the_header_is_blank(self):
        with patch.object(main, "SIDECAR_API_KEY", ""):
            client = TestClient(main.app)  # no lifespan: only the middleware is exercised
            response = client.get("/docs", headers={"X-Picsou-Sidecar-Key": ""})

        self.assertEqual(response.status_code, 401)


class TokenCheckTest(unittest.TestCase):
    def setUp(self):
        self.key_patch = patch.object(main, "SIDECAR_API_KEY", TEST_KEY)
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.cache_patch = patch.object(main, "_token_cache", replay.TokenCache())
        self.cache_patch.start()
        self.addCleanup(self.cache_patch.stop)

    def check(self, bank: FakeBank, body=None, *, raw: str | None = None):
        payload = {"sessionState": make_state()} if body is None else body
        with patch.object(main, "_TRANSPORT", bank.transport()), TestClient(main.app) as client:
            if raw is not None:
                return client.post("/token-check", content=raw, headers=HEADERS)
            return client.post("/token-check", json=payload, headers=HEADERS)

    def test_happy_path_returns_ok_and_expires_in_only(self):
        bank = FakeBank(expires_in=267)

        response = self.check(bank)

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"ok": True, "expiresIn": 267})
        self.assertEqual(len(bank.requests), 4)

    def test_saml_status_other_than_success_is_session_expired(self):
        response = self.check(FakeBank(authn_status="AUTHENTICATION_FAILED"))

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})

    def test_empty_authn_body_is_session_expired(self):
        response = self.check(FakeBank(authn_body=b""))

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})

    def test_non_allow_listed_action_is_an_upstream_error_with_no_further_request(self):
        bank = FakeBank(action="https://evil.example.com/dacswebssoissuer/AuthnRequestServlet")

        response = self.check(bank)

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "UPSTREAM_ERROR"})
        self.assertEqual(bank.paths(), ["/api/oauth/v2/authorize"])

    def test_upstream_failures_are_upstream_errors(self):
        for bank in (FakeBank(token_http=500, token_body=b""), FakeBank(authorize_body=b"nope")):
            with self.subTest(bank=bank):
                response = self.check(bank)
                self.assertEqual(response.status_code, 502)
                self.assertEqual(response.json(), {"detail": "UPSTREAM_ERROR"})

    def test_invalid_session_states_are_400_without_any_bank_request(self):
        bank = FakeBank()
        good = json.loads(make_state())
        bodies = (
            {},
            {"sessionState": ""},
            {"sessionState": 5},
            {"sessionState": "{"},
            {"sessionState": json.dumps({"cookies": [], "authorizeParams": good["authorizeParams"]})},
            {"sessionState": make_state(), "extra": 1},
        )
        for body in bodies:
            with self.subTest(body=body):
                response = self.check(bank, body)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})
        response = self.check(bank, raw="not json at all")
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})
        self.assertEqual(bank.requests, [])

    def test_token_is_cached_between_calls_but_never_returned(self):
        bank = FakeBank(expires_in=267)

        first = self.check(bank)
        second = self.check(bank)

        self.assertEqual(len(bank.requests), 4)  # second call served from memory
        self.assertEqual(second.status_code, 200)
        self.assertLessEqual(second.json()["expiresIn"], first.json()["expiresIn"])
        self.assertNotIn("access_token", second.text)
        self.assertNotIn("accessToken", second.text)

    def test_unexpected_exceptions_are_a_generic_500_without_detail(self):
        async def boom(*args, **kwargs):
            raise RuntimeError("leak " + ALL_SECRETS[0])

        with patch.object(main._token_cache, "get", boom), TestClient(
            main.app, raise_server_exceptions=False
        ) as client:
            response = client.post(
                "/token-check", json={"sessionState": make_state()}, headers=HEADERS
            )

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json(), {"detail": "INTERNAL_ERROR"})
        for secret in ALL_SECRETS:
            self.assertNotIn(secret, response.text)

    def test_no_secret_in_any_response_body_or_captured_log(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setLevel(logging.DEBUG)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        bodies = []
        try:
            scenarios = (
                FakeBank(),
                FakeBank(),  # served from the cache the second time
                FakeBank(authn_status="AUTHENTICATION_FAILED"),
                FakeBank(authn_body=b""),
                FakeBank(action="https://evil.example.com/x"),
                FakeBank(token_http=500, token_body=b"token=" + b"access-token-secret-EEEE"),
            )
            for index, bank in enumerate(scenarios):
                if index == 1:
                    self.check(scenarios[0])  # warm the cache with the first bank
                    bodies.append(self.check(scenarios[0]).text)
                    continue
                state = make_state() if index == 0 else make_state(
                    cookies=[{"name": "SSO", "value": f"cookie-secret-AAAA-{index}", "domain": ".caisse-epargne.fr", "path": "/"}]
                )
                bodies.append(self.check(bank, {"sessionState": state}).text)
            bodies.append(self.check(FakeBank(), {"sessionState": "{" + ALL_SECRETS[0]}).text)
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)

        rendered = stream.getvalue() + "\n".join(bodies)
        for secret in ALL_SECRETS + ("id-token-secret-GGGG", "cookie-secret-AAAA-"):
            self.assertNotIn(secret, rendered)
        for word in ("login_hint", "SAMLRequest", "SAMLResponse", "code_verifier", "Bearer"):
            self.assertNotIn(word, rendered)

    def test_request_log_line_is_sanitised(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        main.log.addHandler(handler)
        self.addCleanup(main.log.removeHandler, handler)
        self.addCleanup(main.log.setLevel, main.log.level)
        main.log.setLevel(logging.INFO)
        with TestClient(main.app) as client:
            client.get("/x%0Aforged%20line", headers=HEADERS)

        lines = [line for line in stream.getvalue().splitlines() if "forged" in line]
        self.assertEqual(len(lines), 1)
        self.assertTrue(lines[0].startswith("Caisse d'Epargne request completed"))


class AccountsEndpointTest(unittest.TestCase):
    def setUp(self):
        self.key_patch = patch.object(main, "SIDECAR_API_KEY", TEST_KEY)
        self.key_patch.start()
        self.addCleanup(self.key_patch.stop)
        self.cache_patch = patch.object(main, "_token_cache", replay.TokenCache())
        self.cache_patch.start()
        self.addCleanup(self.cache_patch.stop)

    def accounts(self, ce: FakeCE, body=None, *, headers=HEADERS, raise_exc=True):
        payload = {"sessionState": make_state()} if body is None else body
        with patch.object(main, "_TRANSPORT", ce.transport()), TestClient(
            main.app, raise_server_exceptions=raise_exc
        ) as client:
            return client.post("/accounts", json=payload, headers=headers)

    def test_unauthenticated_request_is_challenged_and_never_reaches_the_bank(self):
        ce = FakeCE()

        response = self.accounts(ce, headers={})

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "UNAUTHORIZED"})
        self.assertEqual(ce.bank.requests, [])
        self.assertEqual(ce.data_requests, [])

    def test_happy_path_returns_the_contract_with_decimal_strings(self):
        ce = FakeCE()

        response = self.accounts(ce)

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(set(body), {"accounts", "unsupported"})
        # Synthesis order: a card follows its parent contract.
        self.assertEqual([a["externalId"] for a in body["accounts"]], ["1001", "2001", "1002"])
        self.assertEqual(body["accounts"][0]["balance"], "1234.56")
        self.assertEqual(body["accounts"][1]["kind"], "CARD")
        self.assertEqual(len(body["accounts"][0]["transactions"]), 4)
        self.assertEqual(len(ce.bank.requests), 4)  # the OAuth chain ran once
        for request in ce.data_requests:
            self.assertEqual(request.url.host, DATA_HOST)
        self.assertNotIn(SECRET_TOKEN, response.text)

    def test_the_token_cache_is_shared_with_token_check(self):
        ce = FakeCE()

        self.accounts(ce)
        self.accounts(ce)

        self.assertEqual(len(ce.bank.requests), 4)  # second call served from the cache

    def test_invalid_session_state_is_400_without_any_request(self):
        ce = FakeCE()
        for body in ({}, {"sessionState": ""}, {"sessionState": "{"}, {"sessionState": make_state(), "x": 1}):
            with self.subTest(body=body):
                response = self.accounts(ce, body)
                self.assertEqual(response.status_code, 400)
                self.assertEqual(response.json(), {"detail": "INVALID_SESSION_STATE"})
        self.assertEqual(ce.bank.requests, [])
        self.assertEqual(ce.data_requests, [])

    def test_dead_sso_session_is_session_expired(self):
        response = self.accounts(FakeCE(authn_status="AUTHENTICATION_FAILED"))

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})

    def test_401_on_a_data_call_is_session_expired(self):
        for ce in (FakeCE(synthesis_http=401), FakeCE(tx_http=401)):
            with self.subTest(ce=ce):
                response = self.accounts(ce)
                self.assertEqual(response.status_code, 401)
                self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})

    def flaky_first_data_call(self, ce: FakeCE, after_first_401=None) -> httpx.MockTransport:
        """The first data-host request gets a 401 (stale cached token); the rest are normal."""
        state = {"failed": False}

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.host == DATA_HOST and not state["failed"]:
                state["failed"] = True
                ce.data_requests.append(request)
                if after_first_401 is not None:
                    after_first_401()
                return httpx.Response(401)
            return ce.handle(request)

        return httpx.MockTransport(handler)

    def post_accounts(self, transport: httpx.MockTransport):
        with patch.object(main, "_TRANSPORT", transport), TestClient(main.app) as client:
            return client.post("/accounts", json={"sessionState": make_state()}, headers=HEADERS)

    def test_a_first_401_evicts_the_token_and_the_replay_runs_once_more(self):
        ce = FakeCE()

        response = self.post_accounts(self.flaky_first_data_call(ce))

        self.assertEqual(response.status_code, 200)
        self.assertEqual(len(ce.bank.requests), 8)  # the chain ran twice: initial + after eviction
        self.assertEqual(len(response.json()["accounts"]), 3)

    def test_a_second_401_is_session_expired_after_exactly_one_retry(self):
        ce = FakeCE(synthesis_http=401)

        response = self.accounts(ce)

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})
        self.assertEqual(len(ce.bank.requests), 8)  # one retry, never a loop

    def test_a_401_followed_by_a_dead_sso_session_is_session_expired(self):
        ce = FakeCE()
        transport = self.flaky_first_data_call(
            ce, after_first_401=lambda: setattr(ce.bank, "authn_status", "AUTHENTICATION_FAILED")
        )

        response = self.post_accounts(transport)

        self.assertEqual(response.status_code, 401)
        self.assertEqual(response.json(), {"detail": "SESSION_EXPIRED"})

    def test_a_401_followed_by_an_upstream_outage_is_upstream_unavailable(self):
        ce = FakeCE()
        transport = self.flaky_first_data_call(
            ce, after_first_401=lambda: setattr(ce.bank, "token_http", 500)
        )

        response = self.post_accounts(transport)

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "UPSTREAM_UNAVAILABLE"})

    def test_the_overall_deadline_returns_upstream_unavailable_without_partial_result(self):
        ce = FakeCE()

        async def slow(request: httpx.Request) -> httpx.Response:
            if request.url.host == DATA_HOST:
                await asyncio.sleep(5)
            return ce.handle(request)

        with patch.object(main, "ACCOUNTS_DEADLINE_SECONDS", 0.05):
            response = self.post_accounts(httpx.MockTransport(slow))

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "UPSTREAM_UNAVAILABLE"})
        self.assertNotIn("accounts", response.text)

    def test_the_default_deadline_is_below_the_backend_120_seconds_timeout(self):
        self.assertEqual(main.ACCOUNTS_DEADLINE_SECONDS, 100)

    def test_upstream_failures_are_upstream_unavailable(self):
        for ce in (FakeCE(synthesis_http=503), FakeCE(tx_http=500), FakeCE(token_http=500, token_body=b"")):
            with self.subTest(ce=ce), patch.object(main, "_token_cache", replay.TokenCache()):
                response = self.accounts(ce)
                self.assertEqual(response.status_code, 502)
                self.assertEqual(response.json(), {"detail": "UPSTREAM_UNAVAILABLE"})

    def test_parse_error_is_format_changed_and_never_partial(self):
        pages = default_pages()
        pages["1002"][0]["data"][0]["amount"] = "not-a-number"

        response = self.accounts(FakeCE(pages=pages))

        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json(), {"detail": "UPSTREAM_FORMAT_CHANGED"})
        self.assertNotIn("accounts", response.text)

    def test_unexpected_exceptions_are_a_generic_500(self):
        async def boom(*args, **kwargs):
            raise RuntimeError("leak " + ALL_SECRETS[0])

        with patch.object(main.fetcher, "fetch_import", boom):
            response = self.accounts(FakeCE(), raise_exc=False)

        self.assertEqual(response.status_code, 500)
        self.assertEqual(response.json(), {"detail": "INTERNAL_ERROR"})
        self.assertNotIn(ALL_SECRETS[0], response.text)

    def test_no_value_in_any_response_or_captured_log(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setLevel(logging.DEBUG)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        bad = default_pages()
        bad["1002"][0]["data"][0]["amount"] = "secret-amount-string"
        bodies = []
        try:
            bodies.append(self.accounts(FakeCE()).text.replace("FAKE", "").replace("1234.56", ""))
            for ce in (FakeCE(pages=bad), FakeCE(tx_http=401), FakeCE(synthesis_http=500)):
                bodies.append(self.accounts(ce).text)
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)

        rendered = stream.getvalue() + "\n".join(bodies)
        for value in ALL_SECRETS + ("FAKE ACHAT", "FAKE CPT", "1234.56", "secret-amount-string", "Bearer"):
            self.assertNotIn(value, rendered)


class OutOfScopeEndpointsTest(unittest.TestCase):
    """Logout and any password-taking route other than /initiate are not implemented.

    `/initiate` and `/complete` are the browser login (test_main_login.py).
    """

    def test_these_endpoints_do_not_exist(self):
        with patch.object(main, "SIDECAR_API_KEY", TEST_KEY), TestClient(main.app) as client:
            for path in ("/logout", "/login", "/password"):
                with self.subTest(path=path):
                    response = client.post(path, json={}, headers=HEADERS)
                    self.assertEqual(response.status_code, 404)
        paths = {route.path for route in main.app.routes}
        self.assertTrue({"/health", "/token-check", "/accounts", "/initiate", "/complete"} <= paths)


if __name__ == "__main__":
    unittest.main()
