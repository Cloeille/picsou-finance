"""The login, the code check and the positions read, end to end against a fake Natixis.

The fake answers with the hosts, paths, status codes and payload shapes the
live saver space used when the fixtures were captured.
"""

import asyncio
import copy
import io
import logging
import os
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlsplit

import httpx
from fastapi.testclient import TestClient
from picsou_sidecar import KEY_HEADER

import main

from fixtures import (
    AUTHENTICATION_MODES,
    FUND_DETAIL,
    PRODUCTS,
    SAVINGS,
    STRONG_AUTHENTICATION_MODES,
    STRONG_AUTHENTICATION_REQUIRED,
    USERS_ME,
)

EMAIL = "saver@example.org"
PASSWORD = "pw-12345678"
CODE = "424242"
CSRF = "csrf-value-0123456789"
SAML_VALUE = "saml-response-value"
AUTH_CODE = "authorization-code-value"
ACCESS_TOKEN = "access-token-value"
REFRESH_TOKEN = "refresh-token-value"
VERIFICATION_ID = "verification-id-value"

AUTHORIZE_HTML = (
    '<html><body><form method="post" '
    'action="https://auth-nie.natixis.com/auth/nie-gateway/saml2/idp/sso">'
    '<input type="hidden" name="SAMLRequest" value="request&#x3d;"/></form></body></html>'
)
SSO_HTML = f'<html><head><meta name="_csrf" content="{CSRF}"/></head><body></body></html>'


class FakeNatixis:
    """A scripted Natixis. Each attribute changes one answer for a test."""

    def __init__(self):
        self.login_modes = copy.deepcopy(AUTHENTICATION_MODES)
        self.strong_modes = copy.deepcopy(STRONG_AUTHENTICATION_MODES)
        self.authorize_html = AUTHORIZE_HTML
        self.password_status = 201
        self.code_status = 201
        self.token_status = 200
        self.api_status = 200
        self.fund_status = 200
        self.service_provider_url = "https://nie-auth.api.natixis.com/api/oauth/authorize"
        self.savings = copy.deepcopy(SAVINGS)
        self.returned_refresh_token = REFRESH_TOKEN
        self.oauth_state = None
        self.requests = []

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        url = urlsplit(str(request.url))
        self.requests.append((request.method, url.hostname, url.path))
        route = (request.method, url.hostname, url.path)

        if route == ("GET", "nie-auth.api.natixis.com", "/api/oauth/authorize"):
            self.oauth_state = parse_qs(url.query)["state"][0]
            return httpx.Response(200, html=self.authorize_html)
        if route == ("POST", "auth-nie.natixis.com", "/auth/nie-gateway/saml2/idp/sso"):
            return httpx.Response(200, html=SSO_HTML)
        if url.hostname == "auth-nie.natixis.com" and url.path.startswith("/auth/v1/"):
            return self.idp(request, url.path)
        if route == ("POST", "nie-auth.api.natixis.com", "/api/oauth/authorize"):
            location = f"https://epargnants.interepargne.natixis.fr/?code={AUTH_CODE}&state={self.oauth_state}"
            return httpx.Response(302, headers={"location": location})
        if route == ("POST", "nie-auth.api.natixis.com", "/api/oauth/token"):
            if self.token_status != 200:
                return httpx.Response(self.token_status, json={"error": "invalid_grant"})
            return httpx.Response(200, json={
                "access_token": ACCESS_TOKEN,
                "refresh_token": self.returned_refresh_token,
                "token_type": "Bearer",
                "expires_in": 1800,
            })
        if url.hostname == "nie.api.natixis.com":
            return self.api(url.path)
        return httpx.Response(404)

    def idp(self, request: httpx.Request, path: str) -> httpx.Response:
        if request.headers.get("x-csrf-token") != CSRF:
            return httpx.Response(403, json={"errors": [{"code": "csrf"}]})
        if path == "/auth/v1/authenticationModes/search":
            return httpx.Response(200, json=self.login_modes)
        if path == "/auth/v1/authenticate":
            return httpx.Response(self.password_status, json={"link": {"href": "/x", "rel": "MFA"}})
        if path == "/auth/v1/multiFactorsAuthenticate":
            if request.headers.get("strong-authentication") == VERIFICATION_ID:
                return httpx.Response(201, json={"link": {"href": "/y", "rel": "PROFILE_VERIFICATION"}})
            return httpx.Response(403, json=STRONG_AUTHENTICATION_REQUIRED)
        if path == "/auth/v1/strongAuthenticationModes":
            return httpx.Response(200, json=self.strong_modes)
        if path == "/auth/v1/strongAuthentications":
            return httpx.Response(201, json={
                "id": "attempt-1",
                "mode": "SMS",
                "expiresIn": 299000,
                "canRetryIn": 239000,
                "_links": {"verification": {"href": "/auth/v1/strongAuthentications/attempt-1/verifications"}},
            })
        if path == "/auth/v1/strongAuthentications/attempt-1/verifications":
            if self.code_status != 201:
                return httpx.Response(self.code_status, json={"errors": [{"code": "invalid_code"}]})
            return httpx.Response(201, json={"id": VERIFICATION_ID})
        if path == "/auth/v1/profiles/me/verifications":
            return httpx.Response(201, json={"saml": {
                "encodedValue": SAML_VALUE,
                "serviceProviderUrl": self.service_provider_url,
            }})
        return httpx.Response(404)

    def api(self, path: str) -> httpx.Response:
        if self.api_status != 200:
            return httpx.Response(self.api_status, json={})
        if path == "/auth/v3/users/me":
            return httpx.Response(200, json=USERS_ME)
        if path.endswith("/savings"):
            return httpx.Response(200, json=self.savings)
        if path.endswith("/products"):
            return httpx.Response(200, json=PRODUCTS)
        if path.startswith("/fund/v1/funds/"):
            if self.fund_status != 200:
                return httpx.Response(self.fund_status, json={})
            return httpx.Response(200, json=FUND_DETAIL)
        return httpx.Response(404)


class SidecarTestCase(unittest.TestCase):
    """Runs the app against the fake, with the shared sidecar key set."""

    def setUp(self):
        self.main = main
        self.fake = FakeNatixis()
        patcher = mock.patch.object(
            main, "_new_client",
            lambda: httpx.AsyncClient(transport=self.fake.transport(), follow_redirects=False),
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)
        self.headers = {KEY_HEADER: os.environ["APP_SIDECAR_API_KEY"]}

    def post(self, path, payload):
        return self.client.post(path, json=payload, headers=self.headers)

    def initiate(self):
        return self.post("/initiate", {"email": EMAIL, "password": PASSWORD})

    def login(self):
        process_id = self.initiate().json()["processId"]
        return self.post("/complete", {"processId": process_id, "code": CODE})


class HappyPathTest(SidecarTestCase):
    def test_initiate_sends_a_code(self):
        response = self.initiate()
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["mfaRequired"])
        self.assertEqual(body["mfaType"], "SMS")
        self.assertTrue(body["processId"])

    def test_complete_returns_the_session(self):
        response = self.login()
        self.assertEqual(response.status_code, 200)
        state = self.main.parse_session(response.json()["sessionState"])
        self.assertEqual(state["refreshToken"], REFRESH_TOKEN)
        self.assertEqual(state["employeeId"], USERS_ME["id"])

    def test_positions_lists_the_plans(self):
        session = self.login().json()["sessionState"]
        response = self.post("/positions", {"sessionState": session})
        self.assertEqual(response.status_code, 200)
        plans = response.json()["plans"]
        self.assertEqual([p["planKind"] for p in plans], ["PEE", "PER"])
        self.assertEqual(len(plans[0]["positions"]), 6)
        self.assertEqual({p["fundCode"] for p in plans[0]["positions"]}, {"QS0000001002"})

    def test_positions_returns_a_rotated_refresh_token(self):
        session = self.login().json()["sessionState"]
        self.fake.returned_refresh_token = "rotated-refresh-token"
        response = self.post("/positions", {"sessionState": session})
        state = self.main.parse_session(response.json()["sessionState"])
        self.assertEqual(state["refreshToken"], "rotated-refresh-token")

    def test_voice_is_used_when_sms_is_not_offered(self):
        self.fake.strong_modes["items"] = [m for m in self.fake.strong_modes["items"] if m["id"] == "VOICE"]
        self.assertEqual(self.initiate().json()["mfaType"], "VOICE")

    def test_missing_fund_code_does_not_block_the_sync(self):
        session = self.login().json()["sessionState"]
        self.fake.fund_status = 404
        response = self.post("/positions", {"sessionState": session})
        self.assertEqual(response.status_code, 200)
        self.assertEqual({p["fundCode"] for p in response.json()["plans"][0]["positions"]}, {None})


class ErrorCodeTest(SidecarTestCase):
    def assertDetail(self, response, status, code):
        self.assertEqual(response.status_code, status, response.text)
        self.assertEqual(response.json(), {"detail": code})

    def test_wrong_password(self):
        self.fake.password_status = 401
        self.assertDetail(self.initiate(), 401, "INVALID_CREDENTIALS")

    def test_unknown_email(self):
        self.fake.login_modes = {"items": []}
        self.assertDetail(self.initiate(), 401, "INVALID_CREDENTIALS")

    def test_passkey_only_account(self):
        self.fake.login_modes = {"items": [{"mode": "WEBAUTHN"}]}
        self.assertDetail(self.initiate(), 501, "MFA_TYPE_UNSUPPORTED")

    def test_no_supported_second_factor(self):
        self.fake.strong_modes["items"] = []
        self.assertDetail(self.initiate(), 501, "MFA_TYPE_UNSUPPORTED")

    def test_throttled_login(self):
        self.fake.password_status = 429
        self.assertDetail(self.initiate(), 429, "RATE_LIMITED")

    def test_wrong_code_can_be_retried(self):
        process_id = self.initiate().json()["processId"]
        self.fake.code_status = 400
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": "111111"}), 400, "INVALID_OTP")
        self.fake.code_status = 201
        response = self.post("/complete", {"processId": process_id, "code": CODE})
        self.assertEqual(response.status_code, 200, response.text)

    def test_too_many_wrong_codes_drop_the_attempt(self):
        process_id = self.initiate().json()["processId"]
        self.fake.code_status = 400
        for _ in range(self.main.MAX_CODE_ATTEMPTS - 1):
            self.assertDetail(self.post("/complete", {"processId": process_id, "code": "111111"}), 400, "INVALID_OTP")
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": "111111"}), 410,
                          "AUTH_ATTEMPT_EXPIRED")
        self.fake.code_status = 201
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": CODE}), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_malformed_code(self):
        process_id = self.initiate().json()["processId"]
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": "abc"}), 400, "INVALID_OTP")

    def test_unknown_attempt(self):
        self.assertDetail(self.post("/complete", {"processId": "nope", "code": CODE}), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_completed_attempt_cannot_be_reused(self):
        process_id = self.initiate().json()["processId"]
        self.post("/complete", {"processId": process_id, "code": CODE})
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": CODE}), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_refused_refresh_token(self):
        session = self.login().json()["sessionState"]
        self.fake.token_status = 400
        self.assertDetail(self.post("/positions", {"sessionState": session}), 401, "SESSION_EXPIRED")

    def test_api_refuses_the_token(self):
        session = self.login().json()["sessionState"]
        self.fake.api_status = 401
        self.assertDetail(self.post("/positions", {"sessionState": session}), 401, "SESSION_EXPIRED")

    def test_incomplete_portfolio(self):
        session = self.login().json()["sessionState"]
        self.fake.savings["items"][0]["compartments"][0]["investmentOptions"].pop()
        self.assertDetail(self.post("/positions", {"sessionState": session}), 502, "PORTFOLIO_INCOMPLETE")

    def test_login_page_changed(self):
        self.fake.authorize_html = "<html><body>maintenance</body></html>"
        self.assertDetail(self.initiate(), 502, "UPSTREAM_FORMAT_CHANGED")

    def test_untrusted_saml_destination(self):
        self.fake.service_provider_url = "https://example.org/collect"
        process_id = self.initiate().json()["processId"]
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": CODE}), 502,
                          "UPSTREAM_FORMAT_CHANGED")

    def test_api_forbidden_is_not_an_expired_session(self):
        session = self.login().json()["sessionState"]
        self.fake.api_status = 403
        self.assertDetail(self.post("/positions", {"sessionState": session}), 502, "UPSTREAM_FORMAT_CHANGED")

    def test_fund_detail_refused_does_not_block_the_sync(self):
        session = self.login().json()["sessionState"]
        self.fake.fund_status = 401
        self.assertEqual(self.post("/positions", {"sessionState": session}).status_code, 200)

    def test_refused_authorization_code_is_an_expired_attempt(self):
        process_id = self.initiate().json()["processId"]
        self.fake.token_status = 400
        self.assertDetail(self.post("/complete", {"processId": process_id, "code": CODE}), 410,
                          "AUTH_ATTEMPT_EXPIRED")

    def test_non_object_json_is_format_changed(self):
        session = self.login().json()["sessionState"]
        self.fake.savings = ["not", "an", "object"]
        self.assertDetail(self.post("/positions", {"sessionState": session}), 502, "UPSTREAM_FORMAT_CHANGED")

    def test_slow_login_gives_up_before_the_backend(self):
        async def slow(*_):
            await asyncio.sleep(1)

        with mock.patch.object(self.main, "ROUTE_DEADLINE_SECONDS", 0.05), \
                mock.patch.object(self.main, "_password_login", slow):
            self.assertDetail(self.initiate(), 502, "UPSTREAM_UNAVAILABLE")

    def test_upstream_server_error(self):
        session = self.login().json()["sessionState"]
        self.fake.api_status = 503
        self.assertDetail(self.post("/positions", {"sessionState": session}), 502, "UPSTREAM_UNAVAILABLE")

    def test_network_error(self):
        def unreachable(request):
            raise httpx.ConnectError("unreachable", request=request)

        self.fake.handle = unreachable
        self.assertDetail(self.initiate(), 502, "UPSTREAM_UNAVAILABLE")

    def test_invalid_session_state(self):
        self.assertDetail(self.post("/positions", {"sessionState": "{not json"}), 400, "INVALID_DATA")
        self.assertDetail(self.post("/positions", {"sessionState": '{"v": 1}'}), 400, "INVALID_DATA")

    def test_unexpected_failure(self):
        session = self.login().json()["sessionState"]
        with mock.patch.object(self.main, "collect_plans", side_effect=RuntimeError("boom")):
            self.assertDetail(self.post("/positions", {"sessionState": session}), 500, "INTERNAL_ERROR")


class LogHygieneTest(SidecarTestCase):
    """Nothing the saver typed and no token Natixis handed out reaches a log line."""

    SENSITIVE = (EMAIL, PASSWORD, CODE, CSRF, SAML_VALUE, AUTH_CODE, ACCESS_TOKEN, REFRESH_TOKEN, VERIFICATION_ID)

    def test_no_sensitive_value_is_logged(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setLevel(logging.DEBUG)
        loggers = [logging.getLogger(), logging.getLogger("natixis-auth"),
                   logging.getLogger("httpx"), logging.getLogger("httpcore")]
        levels = [lg.level for lg in loggers]
        for lg in loggers:
            lg.addHandler(handler)
            lg.setLevel(logging.DEBUG)
        try:
            session = self.login().json()["sessionState"]
            self.post("/positions", {"sessionState": session})
            process_id = self.initiate().json()["processId"]
            self.fake.code_status = 400
            self.post("/complete", {"processId": process_id, "code": CODE})
            self.fake.token_status = 400
            self.post("/positions", {"sessionState": session})
            with mock.patch.object(self.main, "collect_plans", side_effect=RuntimeError(REFRESH_TOKEN)):
                self.fake.token_status = 200
                self.post("/positions", {"sessionState": session})
        finally:
            for lg, level in zip(loggers, levels):
                lg.removeHandler(handler)
                lg.setLevel(level)

        logged = stream.getvalue()
        self.assertTrue(logged, "the test captured no log at all")
        for value in self.SENSITIVE:
            with self.subTest(value=value):
                self.assertNotIn(value, logged)


if __name__ == "__main__":
    unittest.main()
