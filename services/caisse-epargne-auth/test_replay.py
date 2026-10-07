"""Offline tests for the Caisse d'Epargne session-replay transport.

Every HTTP exchange goes through `httpx.MockTransport`: no network, no real
credentials, no bank data. All values below are invented.
"""

import asyncio
import base64
import hashlib
import json
import logging
import unittest
import urllib.parse

import httpx

import replay

AUTHORIZE_HOST = "www.as-ext-bad-ce.caisse-epargne.fr"
AUTHN_HOST = "www.icgauth.caisse-epargne.fr"
AUTHN_URL = f"https://{AUTHN_HOST}/dacswebssoissuer/AuthnRequestServlet"
CONSUME_URL = f"https://{AUTHORIZE_HOST}/api/oauth/v2/consume"

# Invented marker values: each one must never surface in a log or an error body.
SECRET_COOKIE = "cookie-secret-AAAA"
SECRET_SAML_REQUEST = "saml-request-secret-BBBB"
SECRET_SAML_RESPONSE = "saml-response-secret-CCCC"
SECRET_CODE = "auth-code-secret-DDDD"
SECRET_TOKEN = "access-token-secret-EEEE"
SECRET_PARAM = "authorize-param-secret-FFFF"
ALL_SECRETS = (
    SECRET_COOKIE,
    SECRET_SAML_REQUEST,
    SECRET_SAML_RESPONSE,
    SECRET_CODE,
    SECRET_TOKEN,
    SECRET_PARAM,
)


def make_params() -> dict:
    return {
        "client_id": "test-client-id",
        "redirect_uri": "https://www.example-app.caisse-epargne.fr/callback",
        "scope": "openid test.read",
        "response_type": "code",
        "response_mode": "form_post",
        "login_hint": SECRET_PARAM,
        "code_challenge": "stale-challenge-from-login",
        "code_challenge_method": "S256",
        "nonce": "stale-nonce-from-login",
    }


def make_cookies() -> list[dict]:
    return [
        {"name": "SSO", "value": SECRET_COOKIE, "domain": ".caisse-epargne.fr", "path": "/"},
        {
            "name": "AUTHN",
            "value": SECRET_COOKIE,
            "domain": AUTHN_HOST,
            "path": "/dacswebssoissuer",
        },
    ]


def make_state(cookies=None, params=None) -> str:
    return json.dumps(
        {
            "cookies": make_cookies() if cookies is None else cookies,
            "authorizeParams": make_params() if params is None else params,
        }
    )


class AllowListTest(unittest.TestCase):
    def test_https_caisse_epargne_subdomains_are_allowed(self):
        for url in (
            f"https://{AUTHORIZE_HOST}/api/oauth/v2/authorize",
            AUTHN_URL,
            "https://WWW.ICGAUTH.CAISSE-EPARGNE.FR/x",
            "https://a.b.caisse-epargne.fr:443/x",
        ):
            with self.subTest(url=url):
                self.assertTrue(replay.is_allowed_url(url))

    def test_everything_else_is_refused(self):
        for url in (
            "http://www.icgauth.caisse-epargne.fr/x",
            "https://evil.example.com/x",
            "https://caisse-epargne.fr/x",  # apex is not a *.caisse-epargne.fr host
            "https://caisse-epargne.fr.evil.com/x",
            "https://evilcaisse-epargne.fr/x",
            "https://www.caisse-epargne.fr@evil.com/x",
            "https://user@www.caisse-epargne.fr/x",
            "https://evil.com\\@www.caisse-epargne.fr/x",
            "https://evil.com/?h=www.caisse-epargne.fr",
            "https://www.caisse-epargne.fr:8443/x",
            "https://www.caisse-epargne.fr./x",
            "https://127.0.0.1/x",
            "//www.caisse-epargne.fr/x",
            "ftp://www.caisse-epargne.fr/x",
            "javascript:alert(1)",
            "",
            "/relative/path",
            None,
            42,
            {"a": 1},
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(url))


class SessionStateTest(unittest.TestCase):
    def test_valid_state_is_parsed(self):
        state = replay.parse_session_state(make_state())

        self.assertEqual(state.cookies, make_cookies())
        self.assertEqual(state.authorize_params, make_params())

    def test_malformed_states_are_refused_without_echoing_the_input(self):
        good = json.loads(make_state())
        bad_states = {
            "not json": "{" + SECRET_COOKIE,
            "not an object": json.dumps([SECRET_COOKIE]),
            "no cookies": json.dumps({"authorizeParams": make_params()}),
            "empty cookies": make_state(cookies=[]),
            "cookie not an object": make_state(cookies=[SECRET_COOKIE]),
            "cookie without name": make_state(cookies=[{"value": SECRET_COOKIE}]),
            "cookie value not a string": make_state(
                cookies=[{"name": "a", "value": 1, "domain": "x.caisse-epargne.fr", "path": "/"}]
            ),
            "no params": json.dumps({"cookies": good["cookies"]}),
            "empty params": make_state(params={}),
            "params not a dict": json.dumps(
                {"cookies": good["cookies"], "authorizeParams": [SECRET_PARAM]}
            ),
            "param value not a string": make_state(params={**make_params(), "scope": 3}),
            "no client_id": make_state(
                params={k: v for k, v in make_params().items() if k != "client_id"}
            ),
            "no redirect_uri": make_state(
                params={k: v for k, v in make_params().items() if k != "redirect_uri"}
            ),
        }
        for label, raw in bad_states.items():
            with self.subTest(label=label):
                with self.assertRaises(replay.InvalidSessionState) as ctx:
                    replay.parse_session_state(raw)
                for secret in ALL_SECRETS:
                    self.assertNotIn(secret, str(ctx.exception))

    def test_non_string_input_is_refused(self):
        for raw in (None, 12, b"{}"):
            with self.subTest(raw=raw):
                with self.assertRaises(replay.InvalidSessionState):
                    replay.parse_session_state(raw)


class CookieRoundTripTest(unittest.IsolatedAsyncioTestCase):
    async def test_round_trip_keeps_each_cookies_own_domain_and_path(self):
        cookies = make_cookies() + [
            # Same name on another domain/path must not be merged or re-homed.
            {"name": "SSO", "value": "other", "domain": AUTHN_HOST, "path": "/gsu"},
        ]
        client = replay.new_client()
        try:
            replay.restore_cookies(client, cookies)
            restored = replay.serialize_cookies(client)
        finally:
            await client.aclose()

        key = lambda c: (c["name"], c["domain"], c["path"], c["value"])
        self.assertEqual(sorted(map(key, restored)), sorted(map(key, cookies)))

    async def test_cookies_are_only_sent_to_matching_hosts_and_paths(self):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen[str(request.url)] = request.headers.get("cookie", "")
            return httpx.Response(200, json={})

        client = replay.new_client(transport=httpx.MockTransport(handler))
        try:
            replay.restore_cookies(client, make_cookies())
            await client.get(AUTHN_URL)
            await client.get(f"https://{AUTHORIZE_HOST}/x")
        finally:
            await client.aclose()

        self.assertIn("AUTHN=", seen[AUTHN_URL])
        self.assertIn("SSO=", seen[AUTHN_URL])
        self.assertNotIn("AUTHN=", seen[f"https://{AUTHORIZE_HOST}/x"])
        self.assertIn("SSO=", seen[f"https://{AUTHORIZE_HOST}/x"])

    async def test_client_has_finite_timeouts_and_no_redirect_following(self):
        client = replay.new_client()
        try:
            self.assertFalse(client.follow_redirects)
            for value in (
                client.timeout.connect,
                client.timeout.read,
                client.timeout.write,
                client.timeout.pool,
            ):
                self.assertIsNotNone(value)
                self.assertLessEqual(value, 60)
        finally:
            await client.aclose()


def form_of(request: httpx.Request) -> dict:
    return {k: v[0] for k, v in urllib.parse.parse_qs(request.content.decode()).items()}


class FakeBank:
    """A MockTransport handler that plays the observed chain and records calls."""

    def __init__(
        self,
        *,
        authn_status="AUTHENTICATION_SUCCESS",
        authn_body=None,
        authn_http=200,
        action=AUTHN_URL,
        consume_action=CONSUME_URL,
        expires_in=267,
        token_body=None,
        token_http=200,
        authorize_http=200,
        consume_http=200,
        consume_body=None,
        authorize_body=None,
        authn_redirect=None,
        transaction_body=None,
    ):
        self.requests: list[httpx.Request] = []
        self.authn_status = authn_status
        self.authn_body = authn_body
        self.authn_http = authn_http
        self.action = action
        self.consume_action = consume_action
        self.expires_in = expires_in
        self.token_body = token_body
        self.token_http = token_http
        self.authorize_http = authorize_http
        self.consume_http = consume_http
        self.consume_body = consume_body
        self.authorize_body = authorize_body
        # Observed live 2026-10-07: AuthnRequestServlet answers 303 to
        # /dacsrest/api/v1u0/transaction/<ctx>; the GET there carries the JSON.
        self.authn_redirect = authn_redirect
        self.transaction_body = transaction_body

    def _authn_json(self) -> dict:
        return {
            "id": "x",
            "locale": "fr",
            "response": {
                "status": self.authn_status,
                "saml2_post": {
                    "samlResponse": SECRET_SAML_RESPONSE,
                    "action": self.consume_action,
                    "method": "POST",
                },
            },
        }

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def paths(self) -> list[str]:
        return [request.url.path for request in self.requests]

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path
        if path == "/api/oauth/v2/authorize":
            if self.authorize_body is not None:
                return httpx.Response(self.authorize_http, content=self.authorize_body)
            return httpx.Response(
                self.authorize_http,
                json={
                    "method": "POST",
                    "enctype": "application/x-www-form-urlencoded",
                    "action": self.action,
                    "parameters": {"SAMLRequest": SECRET_SAML_REQUEST},
                },
            )
        if path == "/dacswebssoissuer/AuthnRequestServlet":
            if self.authn_redirect is not None:
                return httpx.Response(303, headers={"Location": self.authn_redirect})
            if self.authn_body is not None:
                return httpx.Response(self.authn_http, content=self.authn_body)
            return httpx.Response(self.authn_http, json=self._authn_json())
        if path.startswith("/dacsrest/api/v1u0/transaction/"):
            if self.transaction_body is not None:
                return httpx.Response(200, json=self.transaction_body)
            return httpx.Response(200, json=self._authn_json())
        if path == "/api/oauth/v2/consume":
            if self.consume_body is not None:
                return httpx.Response(self.consume_http, content=self.consume_body)
            return httpx.Response(
                self.consume_http,
                json={"method": "POST", "parameters": {"code": SECRET_CODE, "idpid": "i"}},
            )
        if path == "/api/oauth/v2/token":
            if self.token_body is not None:
                return httpx.Response(self.token_http, content=self.token_body)
            return httpx.Response(
                self.token_http,
                json={
                    "access_token": SECRET_TOKEN,
                    "token_type": "Bearer",
                    "expires_in": self.expires_in,
                    "scope": "openid",
                    "id_token": "id-token-secret-GGGG",
                },
            )
        return httpx.Response(404)


class ReplayChainTest(unittest.IsolatedAsyncioTestCase):
    async def run_chain(self, bank: FakeBank, **kwargs) -> replay.TokenResult:
        state = replay.parse_session_state(make_state())
        return await replay.replay_chain(state, transport=bank.transport(), **kwargs)

    async def test_happy_chain_returns_the_token_and_its_lifetime(self):
        bank = FakeBank()

        result = await self.run_chain(bank)

        self.assertEqual(result.access_token, SECRET_TOKEN)
        self.assertEqual(result.expires_in, 267)
        self.assertEqual(
            bank.paths(),
            [
                "/api/oauth/v2/authorize",
                "/dacswebssoissuer/AuthnRequestServlet",
                "/api/oauth/v2/consume",
                "/api/oauth/v2/token",
            ],
        )
        authorize, authn, consume, token = bank.requests
        self.assertEqual([r.method for r in bank.requests], ["POST"] * 4)
        self.assertEqual(str(authn.url), AUTHN_URL)
        self.assertEqual(form_of(authn), {"SAMLRequest": SECRET_SAML_REQUEST})
        self.assertEqual(form_of(consume), {"SAMLResponse": SECRET_SAML_RESPONSE})
        for request in (authn, consume, token):
            self.assertEqual(
                request.headers["content-type"], "application/x-www-form-urlencoded"
            )
        self.assertEqual(authorize.content, b"")
        self.assertEqual(authorize.url.host, AUTHORIZE_HOST)
        self.assertEqual(token.url.host, AUTHORIZE_HOST)

    async def test_authn_303_to_the_transaction_context_is_followed_once_with_get(self):
        bank = FakeBank(authn_redirect="/dacsrest/api/v1u0/transaction/CtxTEST")

        result = await self.run_chain(bank)

        self.assertEqual(result.access_token, SECRET_TOKEN)
        self.assertEqual(
            [(r.method, r.url.path) for r in bank.requests],
            [
                ("POST", "/api/oauth/v2/authorize"),
                ("POST", "/dacswebssoissuer/AuthnRequestServlet"),
                ("GET", "/dacsrest/api/v1u0/transaction/CtxTEST"),
                ("POST", "/api/oauth/v2/consume"),
                ("POST", "/api/oauth/v2/token"),
            ],
        )
        self.assertEqual(bank.requests[2].url.host, AUTHN_HOST)

    async def test_authn_303_to_a_new_validation_step_means_session_expired(self):
        # Observed live: once the SSO session is gone the context asks for a new
        # authentication (`step.validationUnits`) instead of a SAML response.
        bank = FakeBank(
            authn_redirect="/dacsrest/api/v1u0/transaction/CtxTEST",
            transaction_body={"id": "x", "locale": "fr", "context": {}, "step": {"phase": {}, "validationUnits": []}},
        )

        with self.assertRaises(replay.SessionExpired):
            await self.run_chain(bank)
        self.assertNotIn("/api/oauth/v2/consume", bank.paths())

    async def test_authn_303_to_another_host_is_refused_without_following(self):
        for location in (
            "https://evil.example.com/dacsrest/api/v1u0/transaction/CtxTEST",
            f"https://{AUTHORIZE_HOST}/dacsrest/api/v1u0/transaction/CtxTEST",
            f"http://{AUTHN_HOST}/dacsrest/api/v1u0/transaction/CtxTEST",
        ):
            with self.subTest(location=location):
                bank = FakeBank(authn_redirect=location)
                with self.assertRaises(replay.UpstreamError):
                    await self.run_chain(bank)
                self.assertEqual(len(bank.requests), 2)

    async def test_a_second_redirect_is_not_followed(self):
        bank = FakeBank(authn_redirect="/dacsrest/api/v1u0/transaction/CtxTEST")
        original = bank.handle

        def handle(request):
            if request.url.path.startswith("/dacsrest/"):
                bank.requests.append(request)
                return httpx.Response(303, headers={"Location": "/dacsrest/api/v1u0/transaction/CtxAGAIN"})
            return original(request)

        bank.handle = handle
        with self.assertRaises(replay.UpstreamError):
            await self.run_chain(bank)
        self.assertEqual(len(bank.requests), 3)

    async def test_authorize_replays_the_captured_params_with_fresh_pkce_and_nonce(self):
        bank = FakeBank()
        await self.run_chain(bank)

        authorize_query = dict(urllib.parse.parse_qsl(bank.requests[0].url.query.decode()))
        token_form = form_of(bank.requests[3])
        params = make_params()

        for key in ("client_id", "redirect_uri", "scope", "response_type", "login_hint"):
            self.assertEqual(authorize_query[key], params[key])
        self.assertEqual(authorize_query["code_challenge_method"], "S256")
        self.assertNotEqual(authorize_query["code_challenge"], params["code_challenge"])
        self.assertNotEqual(authorize_query["nonce"], params["nonce"])

        verifier = token_form["code_verifier"]
        expected = (
            base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest())
            .decode()
            .rstrip("=")
        )
        self.assertEqual(authorize_query["code_challenge"], expected)
        self.assertGreaterEqual(len(verifier), 43)
        self.assertRegex(verifier, r"^[A-Za-z0-9_-]+$")

    async def test_token_request_carries_the_authorization_code_grant(self):
        bank = FakeBank()
        await self.run_chain(bank)

        form = form_of(bank.requests[3])

        self.assertEqual(form["grant_type"], "authorization_code")
        self.assertEqual(form["client_id"], make_params()["client_id"])
        self.assertEqual(form["code"], SECRET_CODE)
        self.assertEqual(form["redirect_uri"], make_params()["redirect_uri"])
        self.assertEqual(set(form), {"grant_type", "client_id", "code", "code_verifier", "redirect_uri"})

    async def test_pkce_and_nonce_are_fresh_on_every_replay(self):
        first, second = FakeBank(), FakeBank()
        await self.run_chain(first)
        await self.run_chain(second)

        query = lambda bank: dict(urllib.parse.parse_qsl(bank.requests[0].url.query.decode()))
        self.assertNotEqual(query(first)["code_challenge"], query(second)["code_challenge"])
        self.assertNotEqual(query(first)["nonce"], query(second)["nonce"])

    async def test_sso_cookies_travel_to_the_authn_host(self):
        bank = FakeBank()
        await self.run_chain(bank)

        self.assertIn(SECRET_COOKIE, bank.requests[1].headers.get("cookie", ""))

    async def test_any_other_saml_status_means_session_expired(self):
        for status in ("AUTHENTICATION_FAILED", "AUTHENTICATION_REQUIRED", "", None, 3):
            with self.subTest(status=status):
                bank = FakeBank(authn_status=status)
                with self.assertRaises(replay.SessionExpired):
                    await self.run_chain(bank)
                self.assertEqual(len(bank.requests), 2)  # stops before consume/token

    async def test_empty_or_unusable_authn_body_means_session_expired(self):
        for body in (b"", b"   ", b"null", b"[]", b"{}", b"<html>login</html>", b'{"response":{}}'):
            with self.subTest(body=body):
                bank = FakeBank(authn_body=body)
                with self.assertRaises(replay.SessionExpired):
                    await self.run_chain(bank)
                self.assertEqual(len(bank.requests), 2)

    async def test_success_status_without_saml2_post_means_session_expired(self):
        body = json.dumps({"response": {"status": "AUTHENTICATION_SUCCESS"}}).encode()
        bank = FakeBank(authn_body=body)

        with self.assertRaises(replay.SessionExpired):
            await self.run_chain(bank)

    async def test_authn_http_errors_are_split_between_expired_and_upstream(self):
        for http_status, expected in (
            (401, replay.SessionExpired),
            (403, replay.SessionExpired),
            (500, replay.UpstreamError),
            (503, replay.UpstreamError),
            (429, replay.UpstreamError),
        ):
            with self.subTest(http_status=http_status):
                bank = FakeBank(authn_http=http_status, authn_body=b"")
                with self.assertRaises(expected):
                    await self.run_chain(bank)

    async def test_non_allow_listed_authn_action_is_rejected_with_no_further_request(self):
        for action in (
            "https://evil.example.com/dacswebssoissuer/AuthnRequestServlet",
            "http://www.icgauth.caisse-epargne.fr/dacswebssoissuer/AuthnRequestServlet",
            "https://www.caisse-epargne.fr.evil.com/x",
            "https://127.0.0.1/x",
            "/relative",
            "",
            None,
            7,
        ):
            with self.subTest(action=action):
                bank = FakeBank(action=action)
                with self.assertRaises(replay.HostNotAllowed):
                    await self.run_chain(bank)
                self.assertEqual(bank.paths(), ["/api/oauth/v2/authorize"])

    async def test_non_allow_listed_saml_post_action_is_rejected_before_posting_the_response(self):
        for action in ("https://evil.example.com/consume", "http://www.as-ext-bad-ce.caisse-epargne.fr/x", None, ""):
            with self.subTest(action=action):
                bank = FakeBank(consume_action=action)
                with self.assertRaises(replay.HostNotAllowed):
                    await self.run_chain(bank)
                self.assertEqual(
                    bank.paths(),
                    ["/api/oauth/v2/authorize", "/dacswebssoissuer/AuthnRequestServlet"],
                )
                self.assertNotIn(SECRET_SAML_RESPONSE.encode(), b"".join(r.content for r in bank.requests))

    async def test_non_allow_listed_authorize_or_token_url_is_rejected_before_any_request(self):
        for kwargs in (
            {"authorize_url": "https://evil.example.com/api/oauth/v2/authorize"},
            {"token_url": "https://evil.example.com/api/oauth/v2/token"},
            {"authorize_url": "http://www.as-ext-bad-ce.caisse-epargne.fr/a"},
        ):
            with self.subTest(kwargs=kwargs):
                bank = FakeBank()
                with self.assertRaises(replay.HostNotAllowed):
                    await self.run_chain(bank, **kwargs)
                self.assertEqual(bank.requests, [])

    async def test_redirects_are_never_followed(self):
        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(str(request.url))
            return httpx.Response(302, headers={"location": "https://evil.example.com/steal"})

        seen: list[str] = []
        state = replay.parse_session_state(make_state())

        with self.assertRaises(replay.UpstreamError):
            await replay.replay_chain(state, transport=httpx.MockTransport(handler))

        self.assertEqual(len(seen), 1)

    async def test_upstream_failures_map_to_upstream_error(self):
        cases = {
            "authorize 500": FakeBank(authorize_http=500, authorize_body=b""),
            "authorize not json": FakeBank(authorize_body=b"<html>"),
            "authorize no SAMLRequest": FakeBank(
                authorize_body=json.dumps({"action": AUTHN_URL, "parameters": {}}).encode()
            ),
            "consume 500": FakeBank(consume_http=500, consume_body=b""),
            "consume no code": FakeBank(consume_body=b'{"parameters":{}}'),
            "token 400": FakeBank(token_http=400, token_body=b'{"error":"invalid_grant"}'),
            "token not json": FakeBank(token_body=b"nope"),
            "token no access_token": FakeBank(token_body=b'{"token_type":"Bearer","expires_in":267}'),
            "token not bearer": FakeBank(
                token_body=b'{"access_token":"a","token_type":"MAC","expires_in":267}'
            ),
            "token bad expires_in": FakeBank(
                token_body=b'{"access_token":"a","token_type":"Bearer","expires_in":"soon"}'
            ),
            "token zero expires_in": FakeBank(
                token_body=b'{"access_token":"a","token_type":"Bearer","expires_in":0}'
            ),
        }
        for label, bank in cases.items():
            with self.subTest(label=label):
                with self.assertRaises(replay.UpstreamError) as ctx:
                    await self.run_chain(bank)
                self.assertNotIsInstance(ctx.exception, replay.SessionExpired)

    async def test_transport_errors_map_to_upstream_error(self):
        def handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectTimeout("boom " + SECRET_COOKIE, request=request)

        state = replay.parse_session_state(make_state())
        with self.assertRaises(replay.UpstreamError) as ctx:
            await replay.replay_chain(state, transport=httpx.MockTransport(handler))

        self.assertNotIn(SECRET_COOKIE, str(ctx.exception))

    async def test_oversized_response_is_refused(self):
        bank = FakeBank(authorize_body=b"x" * (replay.MAX_RESPONSE_BYTES + 1))

        with self.assertRaises(replay.UpstreamError):
            await self.run_chain(bank)

    async def test_client_is_always_closed(self):
        closed = []
        original = httpx.AsyncClient.aclose

        async def spy(self_):
            closed.append(True)
            await original(self_)

        for bank in (FakeBank(), FakeBank(authn_status="NOPE"), FakeBank(token_http=500)):
            closed.clear()
            httpx.AsyncClient.aclose = spy
            try:
                try:
                    await self.run_chain(bank)
                except replay.ReplayError:
                    pass
            finally:
                httpx.AsyncClient.aclose = original
            self.assertEqual(closed, [True])


class TokenCacheTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.now = 1000.0
        self.cache = replay.TokenCache(clock=lambda: self.now)

    async def get(self, bank, raw=None):
        return await self.cache.get(raw or make_state(), transport=bank.transport())

    async def test_token_is_cached_before_expiry_minus_safety_margin(self):
        bank = FakeBank(expires_in=267)
        first = await self.get(bank)
        self.now += 267 - 30 - 1  # still inside the usable window
        second = await self.get(bank)

        self.assertEqual(len(bank.requests), 4)  # one chain only
        self.assertEqual(first.access_token, second.access_token)
        self.assertAlmostEqual(second.expires_in, 267 - 236, delta=0.01)  # real remaining lifetime

    async def test_token_is_not_served_after_expires_in_minus_30_seconds(self):
        bank = FakeBank(expires_in=267)
        await self.get(bank)
        self.now += 267 - 30  # exactly at the margin: no longer usable
        await self.get(bank)

        self.assertEqual(len(bank.requests), 8)  # a second full chain

    async def test_cache_hit_reports_the_remaining_lifetime(self):
        bank = FakeBank(expires_in=267)
        first = await self.get(bank)
        self.now += 100
        second = await self.get(bank)

        self.assertEqual(first.expires_in, 267)
        self.assertAlmostEqual(second.expires_in, 267 - 100, delta=0.01)

    async def test_short_lived_token_is_never_cached(self):
        bank = FakeBank(expires_in=30)
        await self.get(bank)
        await self.get(bank)

        self.assertEqual(len(bank.requests), 8)

    async def test_different_sessions_do_not_share_a_token(self):
        bank = FakeBank()
        await self.get(bank)
        other = make_state(cookies=[{"name": "SSO", "value": "other-cookie", "domain": ".caisse-epargne.fr", "path": "/"}])
        await self.get(bank, other)

        self.assertEqual(len(bank.requests), 8)

    async def test_failures_are_not_cached(self):
        bank = FakeBank(authn_status="AUTHENTICATION_FAILED")
        for _ in range(2):
            with self.assertRaises(replay.SessionExpired):
                await self.get(bank)

        self.assertEqual(len(bank.requests), 4)  # two attempts, two requests each

    async def test_cache_is_bounded(self):
        bank = FakeBank(expires_in=3000)
        for i in range(replay.MAX_CACHED_TOKENS + 5):
            await self.get(
                bank,
                make_state(cookies=[{"name": "SSO", "value": f"c{i}", "domain": ".caisse-epargne.fr", "path": "/"}]),
            )

        self.assertLessEqual(len(self.cache), replay.MAX_CACHED_TOKENS)

    async def test_invalid_state_is_refused_before_any_request(self):
        bank = FakeBank()
        with self.assertRaises(replay.InvalidSessionState):
            await self.get(bank, "{")

        self.assertEqual(bank.requests, [])

    async def test_concurrent_calls_with_the_same_session_run_the_chain_once(self):
        bank = FakeBank()

        async def slow(request: httpx.Request) -> httpx.Response:
            await asyncio.sleep(0.01)  # let the second caller interleave with the first
            return bank.handle(request)

        transport = httpx.MockTransport(slow)
        raw = make_state()
        first, second = await asyncio.gather(
            self.cache.get(raw, transport=transport), self.cache.get(raw, transport=transport)
        )

        authorize = [r for r in bank.requests if r.url.path == "/api/oauth/v2/authorize"]
        self.assertEqual(len(authorize), 1)
        self.assertEqual(len(bank.requests), 4)
        self.assertEqual(first.access_token, second.access_token)

    async def test_concurrent_calls_with_different_sessions_do_not_block_each_other(self):
        bank = FakeBank()

        async def slow(request: httpx.Request) -> httpx.Response:
            await asyncio.sleep(0.01)
            return bank.handle(request)

        transport = httpx.MockTransport(slow)
        other = make_state(
            cookies=[{"name": "SSO", "value": "other-cookie", "domain": ".caisse-epargne.fr", "path": "/"}]
        )
        await asyncio.gather(
            self.cache.get(make_state(), transport=transport),
            self.cache.get(other, transport=transport),
        )

        self.assertEqual(len(bank.requests), 8)

    async def test_evict_drops_only_that_session_token(self):
        bank = FakeBank()
        await self.get(bank)
        self.cache.evict(make_state())
        await self.get(bank)

        self.assertEqual(len(bank.requests), 8)  # a second full chain after the eviction


class LogHygieneTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_secret_reaches_the_logs_or_exception_text(self):
        scenarios = [
            FakeBank(),
            FakeBank(authn_status="AUTHENTICATION_FAILED"),
            FakeBank(authn_body=b""),
            FakeBank(action="https://evil.example.com/x"),
            FakeBank(token_http=500, token_body=SECRET_TOKEN.encode()),
            FakeBank(consume_body=b"<html>" + SECRET_CODE.encode()),
        ]
        records: list[logging.LogRecord] = []

        class Capture(logging.Handler):
            def emit(self, record):
                records.append(record)

        root = logging.getLogger()
        handler = Capture(level=logging.DEBUG)
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        # Third-party loggers are part of what a real deployment would emit.
        texts = []
        try:
            for bank in scenarios:
                try:
                    await replay.replay_chain(
                        replay.parse_session_state(make_state()), transport=bank.transport()
                    )
                except replay.ReplayError as exc:
                    texts.append(str(exc))
                    texts.append(repr(exc))
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)

        rendered = "\n".join(record.getMessage() for record in records) + "\n".join(texts)
        for secret in ALL_SECRETS + ("id-token-secret-GGGG",):
            self.assertNotIn(secret, rendered)
        self.assertNotIn("login_hint", rendered)
        self.assertNotIn("code_verifier", rendered)

    def test_session_state_repr_hides_its_content(self):
        state = replay.parse_session_state(make_state())

        for secret in ALL_SECRETS:
            self.assertNotIn(secret, repr(state))
        result = replay.TokenResult(access_token=SECRET_TOKEN, expires_in=267)
        self.assertNotIn(SECRET_TOKEN, repr(result))

if __name__ == "__main__":
    unittest.main()
