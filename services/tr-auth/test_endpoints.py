"""Trade Republic sidecar endpoints against a fake TR API (httpx mocked, no network)."""

import asyncio
import base64
import json
import os
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main
from tr_fakes import (CLAIMS, DEVICE, PIN, PROCESS_ID, REFRESH, SESSION, SESSION_ROUTE, SUB, V1_CODE, V1_LOGIN,
                      V2_LOGIN, V2_POLL, FakeTR, confirmed, encode_claims, pending, reply)

TEST_KEY = "tr-sidecar-test-key"
AUTH = {"X-Picsou-Sidecar-Key": TEST_KEY}
CREDENTIALS = {"phoneNumber": "0612345678", "pin": PIN}
STATE = json.dumps({"refresh": REFRESH, "device": DEVICE, "sub": SUB})


class EndpointCase(unittest.TestCase):
    def setUp(self):
        for p in (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY}),
            patch.object(main, "get_waf_token", AsyncMock(return_value="waf")),
            patch.object(main, "APP_VALIDATION_POLL_SECONDS", 0.01),
            patch.object(main, "APP_VALIDATION_TIMEOUT_SECONDS", 0.2),
        ):
            p.start()
            self.addCleanup(p.stop)
        self.addCleanup(lambda: asyncio.run(main._pending.close_all()))
        self.client = TestClient(main.app, headers=AUTH)

    def run_tr(self, routes):
        tr = FakeTR(routes)
        patcher = tr.patcher()
        patcher.start()
        self.addCleanup(patcher.stop)
        return tr

    def initiate(self):
        response = self.client.post("/initiate", json=CREDENTIALS)
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def assertDetail(self, response, status, detail):
        self.assertEqual((response.status_code, response.json()), (status, {"detail": detail}))


class InitiateTest(EndpointCase):
    def test_v2_ok_returns_a_confirm_in_app_flow(self):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID, "claims": None})})

        body = self.initiate()

        self.assertEqual(body["flow"], "confirmInApp")
        self.assertEqual(body["countdownInSeconds"], main.APP_VALIDATION_TIMEOUT_SECONDS)
        self.assertNotEqual(body["processId"], PROCESS_ID)
        sent = tr.calls(*V2_LOGIN)[0]
        self.assertEqual(json.loads(sent.content), {"phoneNumber": "+33612345678", "pin": PIN})
        self.assertEqual(sent.headers["x-aws-waf-token"], "waf")
        self.assertIn("x-tr-device-info", sent.headers)
        self.assertEqual(tr.calls(*V1_LOGIN), [])

    def test_device_info_is_a_base64_json_with_a_random_128_hex_device_id(self):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID})})
        self.initiate()
        self.initiate()

        infos = [r.headers["x-tr-device-info"] for r in tr.calls(*V2_LOGIN)]
        ids = [json.loads(base64.b64decode(i))["stableDeviceId"] for i in infos]

        self.assertEqual(len(set(ids)), 2)
        for device_id in ids:
            self.assertRegex(device_id, r"^[0-9a-f]{128}$")

    def test_v2_5xx_falls_back_to_v1_otp(self):
        tr = self.run_tr({V2_LOGIN: reply(503, {"errors": []}), V1_LOGIN: reply(200, {"processId": PROCESS_ID})})

        body = self.initiate()

        self.assertEqual(body["flow"], "otp")
        self.assertEqual(len(tr.calls(*V1_LOGIN)), 1)
        self.assertEqual(tr.calls(*V1_LOGIN)[0].headers["x-tr-device-info"],
                         tr.calls(*V2_LOGIN)[0].headers["x-tr-device-info"])

    def test_v2_device_not_found_falls_back_to_v1_otp(self):
        self.run_tr({V2_LOGIN: reply(400, {"errors": [{"errorCode": "DEVICE_NOT_FOUND"}]}),
                     V1_LOGIN: reply(200, {"processId": PROCESS_ID})})

        self.assertEqual(self.initiate()["flow"], "otp")

    def test_bad_credentials_are_401_and_do_not_fall_back(self):
        tr = self.run_tr({V2_LOGIN: reply(400, {"errors": [{"errorCode": "PIN_INVALID"}]})})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 401, "INVALID_CREDENTIALS")
        self.assertEqual(tr.calls(*V1_LOGIN), [])

    def test_429_is_rate_limited(self):
        self.run_tr({V2_LOGIN: reply(429, {"errors": []})})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 429, "RATE_LIMITED")

    def test_no_waf_token_is_upstream_unavailable_and_never_calls_tr(self):
        tr = self.run_tr({})
        with patch.object(main, "get_waf_token", AsyncMock(return_value=None)):
            response = self.client.post("/initiate", json=CREDENTIALS)

        self.assertDetail(response, 503, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(tr.requests, [])

    def test_empty_403_from_tr_is_a_waf_block(self):
        self.run_tr({V2_LOGIN: reply(403)})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 503, "UPSTREAM_UNAVAILABLE")

    def test_transport_error_is_upstream_unavailable(self):
        def unreachable(request):
            raise main.httpx.ConnectError("refused", request=request)

        self.run_tr({V2_LOGIN: unreachable})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 504, "UPSTREAM_UNAVAILABLE")

    def test_reply_without_a_process_id_is_a_format_change(self):
        self.run_tr({V2_LOGIN: reply(200, {"claims": None})})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 502, "UPSTREAM_FORMAT_CHANGED")

    def test_unexpected_failure_is_internal_error(self):
        self.run_tr({V2_LOGIN: lambda request: 1 / 0})

        self.assertDetail(self.client.post("/initiate", json=CREDENTIALS), 500, "INTERNAL_ERROR")

    def test_the_pin_is_never_logged(self):
        self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID})})
        with self.assertLogs(level="DEBUG") as logs:
            self.initiate()

        text = "\n".join(logs.output)
        self.assertNotIn(PIN, text)
        self.assertNotIn("0612345678", text)
        self.assertIn("****78", text)


class CompleteConfirmInAppTest(EndpointCase):
    def start(self, routes):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), **routes})
        return tr, self.initiate()["processId"]

    def test_pending_then_confirmed_returns_the_session_state(self):
        tr, process_id = self.start({V2_POLL: [pending(), pending(), confirmed()]})

        response = self.client.post("/complete", json={"processId": process_id})

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(json.loads(response.json()["sessionState"]),
                         {"refresh": REFRESH, "device": DEVICE, "sub": SUB})
        self.assertNotIn(SESSION, response.text)
        self.assertEqual(len(tr.calls(*V2_POLL)), 3)

    def test_completed_status_is_accepted_too(self):
        self.start({V2_POLL: confirmed("COMPLETED")})

        pid = next(iter(main._pending._states))
        self.assertEqual(self.client.post("/complete", json={"processId": pid}).status_code, 200)

    def test_jwt_shaped_claims_are_decoded_from_the_middle_part(self):
        _, process_id = self.start({V2_POLL: reply(200, {"status": "CONFIRMED"}, cookies={
            "tr_refresh": REFRESH, "tr_device": DEVICE, "tr_claims": encode_claims(CLAIMS, jwt_shaped=True)})})

        response = self.client.post("/complete", json={"processId": process_id})

        self.assertEqual(json.loads(response.json()["sessionState"])["sub"], SUB)

    def test_device_info_is_identical_across_the_post_and_every_poll(self):
        tr, process_id = self.start({V2_POLL: [pending(), pending(), confirmed()]})
        self.client.post("/complete", json={"processId": process_id})

        infos = {r.headers["x-tr-device-info"] for r in [*tr.calls(*V2_LOGIN), *tr.calls(*V2_POLL)]}

        self.assertEqual(len(tr.calls(*V2_POLL)), 3)
        self.assertEqual(len(infos), 1)

    def test_never_confirmed_is_a_408_timeout(self):
        self.start({V2_POLL: pending()})
        pid = next(iter(main._pending._states))

        self.assertDetail(self.client.post("/complete", json={"processId": pid}), 408, "APP_VALIDATION_TIMEOUT")

    def test_a_rejected_process_is_cancelled(self):
        for status in ("REJECTED", "EXPIRED", "CANCELLED"):
            with self.subTest(status=status):
                _, process_id = self.start({V2_POLL: reply(200, {"status": status})})
                self.assertDetail(self.client.post("/complete", json={"processId": process_id}),
                                  401, "APP_VALIDATION_CANCELLED")

    def test_a_pending_process_past_its_expiry_is_an_expired_attempt(self):
        _, process_id = self.start({V2_POLL: reply(200, {"status": "PENDING", "expiresAt": "2020-01-01T00:00:00Z"})})

        self.assertDetail(self.client.post("/complete", json={"processId": process_id}), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_poll_4xx_is_cancelled_and_429_is_rate_limited(self):
        _, process_id = self.start({V2_POLL: reply(400, {"errors": []})})
        self.assertDetail(self.client.post("/complete", json={"processId": process_id}),
                          401, "APP_VALIDATION_CANCELLED")
        _, process_id = self.start({V2_POLL: reply(429, {"errors": []})})
        self.assertDetail(self.client.post("/complete", json={"processId": process_id}), 429, "RATE_LIMITED")

    def test_unknown_process_id_is_410(self):
        self.assertDetail(self.client.post("/complete", json={"processId": "never-issued"}),
                          410, "AUTH_ATTEMPT_EXPIRED")

    def test_a_process_id_is_single_use(self):
        _, process_id = self.start({V2_POLL: confirmed()})
        self.assertEqual(self.client.post("/complete", json={"processId": process_id}).status_code, 200)

        self.assertDetail(self.client.post("/complete", json={"processId": process_id}), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_confirmation_without_a_refresh_cookie_is_a_format_change(self):
        _, process_id = self.start({V2_POLL: reply(200, {"status": "CONFIRMED"}, cookies={
            "tr_session": SESSION, "tr_device": DEVICE, "tr_claims": encode_claims(CLAIMS)})})

        self.assertDetail(self.client.post("/complete", json={"processId": process_id}),
                          502, "UPSTREAM_FORMAT_CHANGED")

    def test_confirmation_without_a_subject_is_a_format_change(self):
        _, process_id = self.start({V2_POLL: reply(200, {"status": "CONFIRMED"}, cookies={
            "tr_refresh": REFRESH, "tr_device": DEVICE, "tr_claims": encode_claims({"exp": 1})})})

        self.assertDetail(self.client.post("/complete", json={"processId": process_id}),
                          502, "UPSTREAM_FORMAT_CHANGED")

    def test_cookie_values_and_claims_are_never_logged(self):
        _, process_id = self.start({V2_POLL: confirmed()})
        with self.assertLogs(level="DEBUG") as logs:
            self.client.post("/complete", json={"processId": process_id})

        text = "\n".join(logs.output)
        for secret in (REFRESH, DEVICE, SESSION, SUB, encode_claims(CLAIMS)):
            self.assertNotIn(secret, text)


class CompleteOtpTest(EndpointCase):
    def start(self, routes):
        tr = self.run_tr({V2_LOGIN: reply(503), V1_LOGIN: reply(200, {"processId": PROCESS_ID}), **routes})
        self.assertEqual(self.initiate_flow(), "otp")
        return tr, next(iter(main._pending._states))

    def initiate_flow(self):
        return self.initiate()["flow"]

    def test_the_right_code_returns_the_session_state(self):
        tr, process_id = self.start({V1_CODE: confirmed()})

        response = self.client.post("/complete", json={"processId": process_id, "tan": "1234"})

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(json.loads(response.json()["sessionState"]),
                         {"refresh": REFRESH, "device": DEVICE, "sub": SUB})
        self.assertEqual(tr.calls(*V1_CODE)[0].headers["x-tr-device-info"],
                         tr.calls(*V1_LOGIN)[0].headers["x-tr-device-info"])

    def test_a_wrong_code_is_401_and_the_attempt_stays_open(self):
        _, process_id = self.start({V1_CODE: [reply(400, {"errors": [{"errorCode": "VERIFICATION_CODE_INVALID"}]}),
                                              confirmed()]})

        self.assertDetail(self.client.post("/complete", json={"processId": process_id, "tan": "1234"}),
                          401, "INVALID_OTP")
        self.assertEqual(
            self.client.post("/complete", json={"processId": process_id, "tan": "1234"}).status_code, 200)

    def test_a_missing_or_malformed_code_is_422(self):
        tr, process_id = self.start({V1_CODE: confirmed()})

        for body in ({"processId": process_id}, {"processId": process_id, "tan": "12"},
                     {"processId": process_id, "tan": "12/4"}, {"processId": process_id, "tan": "abcd"}):
            with self.subTest(body=body):
                self.assertDetail(self.client.post("/complete", json=body), 422, "INVALID_OTP")
        self.assertEqual(tr.calls(*V1_CODE), [])
        self.assertEqual(
            self.client.post("/complete", json={"processId": process_id, "tan": "1234"}).status_code, 200)

    def test_a_malformed_process_id_is_422_and_never_reaches_tr(self):
        tr, _ = self.start({})
        before = len(tr.requests)

        for process_id in ("../../api/v1/account", "abc/def", "abc?x=1", "abc%2Fdef", "", "a" * 65):
            with self.subTest(processId=process_id):
                response = self.client.post("/complete", json={"processId": process_id, "tan": "1234"})
                self.assertEqual(response.status_code, 422)
        self.assertEqual(len(tr.requests), before)

    def test_429_from_tr_is_rate_limited(self):
        _, process_id = self.start({V1_CODE: reply(429, {"errors": []})})

        self.assertDetail(self.client.post("/complete", json={"processId": process_id, "tan": "1234"}),
                          429, "RATE_LIMITED")

    def test_no_waf_token_is_upstream_unavailable(self):
        _, process_id = self.start({V1_CODE: confirmed()})
        with patch.object(main, "get_waf_token", AsyncMock(return_value=None)):
            response = self.client.post("/complete", json={"processId": process_id, "tan": "1234"})

        self.assertDetail(response, 503, "UPSTREAM_UNAVAILABLE")


class SessionTest(EndpointCase):
    def session_ok(self, **cookies):
        return reply(200, {}, cookies={"tr_session": SESSION, "tr_claims": encode_claims(CLAIMS),
                                       "JSESSIONID": "j", **cookies})

    def test_ok_returns_the_token_and_decoded_claims(self):
        tr = self.run_tr({SESSION_ROUTE: self.session_ok()})

        response = self.client.post("/session", json={"sessionState": STATE})

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {"sessionToken": SESSION, "claims": CLAIMS})
        sent = tr.calls(*SESSION_ROUTE)[0]
        self.assertEqual(sent.headers["cookie"], f"tr_refresh={REFRESH}; tr_device={DEVICE}")
        self.assertEqual(json.loads(sent.content), {"subjectId": SUB})
        self.assertNotIn("x-aws-waf-token", sent.headers)
        self.assertNotIn("x-tr-device-info", sent.headers)

    def test_session_needs_no_waf_token(self):
        self.run_tr({SESSION_ROUTE: self.session_ok()})
        waf = AsyncMock(return_value=None)
        with patch.object(main, "get_waf_token", waf):
            response = self.client.post("/session", json={"sessionState": STATE})

        self.assertEqual(response.status_code, 200)
        waf.assert_not_awaited()

    def test_rejection_is_session_expired(self):
        for status in (401, 403):
            with self.subTest(status=status):
                self.run_tr({SESSION_ROUTE: reply(status, {"errors": [{"errorCode": "UNAUTHORIZED"}]})})
                self.assertDetail(self.client.post("/session", json={"sessionState": STATE}),
                                  401, "SESSION_EXPIRED")

    def test_429_is_rate_limited(self):
        self.run_tr({SESSION_ROUTE: reply(429, {"errors": []})})

        self.assertDetail(self.client.post("/session", json={"sessionState": STATE}), 429, "RATE_LIMITED")

    def test_5xx_and_network_errors_are_upstream_unavailable(self):
        def unreachable(request):
            raise main.httpx.ConnectError("refused", request=request)

        for route in (reply(500, {"errors": []}), reply(502), reply(403), unreachable):
            with self.subTest(route=route):
                self.run_tr({SESSION_ROUTE: route})
                self.assertDetail(self.client.post("/session", json={"sessionState": STATE}),
                                  502, "UPSTREAM_UNAVAILABLE")

    def test_bad_state_is_422_and_never_reaches_tr(self):
        tr = self.run_tr({SESSION_ROUTE: self.session_ok()})
        bad = ["not json", "[]", "{}", json.dumps({"refresh": REFRESH, "device": DEVICE}),
               json.dumps({"refresh": "", "device": DEVICE, "sub": SUB}),
               json.dumps({"refresh": 1, "device": DEVICE, "sub": SUB}),
               json.dumps({"refresh": "a; tr_session=x", "device": DEVICE, "sub": SUB}),
               json.dumps({"refresh": "a\r\nX: y", "device": DEVICE, "sub": SUB}), "x"]

        for state in bad:
            with self.subTest(state=state):
                self.assertDetail(self.client.post("/session", json={"sessionState": state}),
                                  422, "INVALID_SESSION_STATE")
        self.assertEqual(tr.requests, [])

    def test_missing_session_state_is_422_invalid_session_state(self):
        self.assertDetail(self.client.post("/session", json={}), 422, "INVALID_SESSION_STATE")

    def test_a_reply_without_a_session_cookie_is_a_format_change(self):
        self.run_tr({SESSION_ROUTE: reply(200, {}, cookies={"tr_claims": encode_claims(CLAIMS)})})

        self.assertDetail(self.client.post("/session", json={"sessionState": STATE}),
                          502, "UPSTREAM_FORMAT_CHANGED")

    def test_undecodable_claims_are_a_format_change(self):
        self.run_tr({SESSION_ROUTE: reply(200, {}, cookies={"tr_session": SESSION, "tr_claims": "!!!"})})

        self.assertDetail(self.client.post("/session", json={"sessionState": STATE}),
                          502, "UPSTREAM_FORMAT_CHANGED")

    def test_tokens_are_never_logged(self):
        self.run_tr({SESSION_ROUTE: self.session_ok()})
        with self.assertLogs(level="DEBUG") as logs:
            self.client.post("/session", json={"sessionState": STATE})

        text = "\n".join(logs.output)
        for secret in (REFRESH, DEVICE, SESSION, SUB, encode_claims(CLAIMS)):
            self.assertNotIn(secret, text)


class SurfaceTest(EndpointCase):
    def test_refresh_is_gone(self):
        self.assertEqual(self.client.post("/refresh", json={"refreshToken": "x"}).status_code, 404)

    def test_the_routes_are_exactly_the_contract(self):
        paths = {route.path for route in main.app.routes if getattr(route, "methods", None)}
        self.assertTrue({"/initiate", "/complete", "/session", "/health"} <= paths)
        self.assertNotIn("/refresh", paths)

    def test_every_endpoint_requires_the_sidecar_key(self):
        bodies = {"/initiate": CREDENTIALS, "/complete": {"processId": PROCESS_ID},
                  "/session": {"sessionState": STATE}}
        tr = self.run_tr({})
        anonymous = TestClient(main.app)
        for path, body in bodies.items():
            for headers in ({}, {"X-Picsou-Sidecar-Key": "wrong"}):
                with self.subTest(path=path, headers=headers):
                    response = anonymous.post(path, json=body, headers=headers)
                    self.assertEqual((response.status_code, response.json()), (401, {"detail": "UNAUTHORIZED"}))
        self.assertEqual(tr.requests, [])

    def test_health(self):
        with patch.dict(os.environ):
            os.environ.pop("PICSOU_SIDECAR_BASE", None)
            self.assertEqual(TestClient(main.app).get("/health").json(), {"status": "ok"})


if __name__ == "__main__":
    unittest.main()
