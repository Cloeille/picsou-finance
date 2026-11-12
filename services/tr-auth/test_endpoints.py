"""Trade Republic sidecar endpoints against a fake TR API (httpx mocked, no network)."""

import asyncio
import base64
import json
import os
import time
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main
from tr_fakes import (CLAIMS, DEVICE, EXPORT_DOWNLOAD, EXPORT_REQUEST, EXPORT_STATUS, JOB_ID, PIN, PROCESS_ID, REFRESH,
                      SESSION, SESSION_ROUTE, SUB, V1_CODE, V1_LOGIN, V2_AUTHENTICATOR, V2_LOGIN, V2_POLL, FakeTR,
                      authenticator_pending, confirmed, csv_reply, encode_claims, export_job, pending, reply)

TEST_KEY = "tr-sidecar-test-key"
AUTH = {"X-Picsou-Sidecar-Key": TEST_KEY}
PASSWORD = "Tr0ub4dor&3 horse"
PHONE = "+33612345678"
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
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID, "claims": None}), V2_POLL: pending()})

        body = self.initiate()

        self.assertEqual(body["flow"], "confirmInApp")
        self.assertEqual(body["countdownInSeconds"], main.APP_VALIDATION_TIMEOUT_SECONDS)
        self.assertNotEqual(body["processId"], PROCESS_ID)
        sent = tr.calls(*V2_LOGIN)[0]
        self.assertEqual(json.loads(sent.content), {"phoneNumber": PHONE, "pin": PIN})
        self.assertEqual(sent.headers["x-aws-waf-token"], "waf")
        self.assertIn("x-tr-device-info", sent.headers)
        self.assertEqual(tr.calls(*V1_LOGIN), [])
        self.assertEqual(len(tr.calls(*V2_POLL)), 1)

    def test_device_info_is_a_base64_json_with_a_random_128_hex_device_id(self):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), V2_POLL: pending()})
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

    def test_a_password_is_sent_verbatim_and_never_logged(self):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), V2_POLL: pending()})
        with self.assertLogs(level="DEBUG") as logs:
            response = self.client.post("/initiate", json={**CREDENTIALS, "pin": PASSWORD})

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(json.loads(tr.calls(*V2_LOGIN)[0].content)["pin"], PASSWORD)
        self.assertNotIn(PASSWORD, "\n".join(logs.output))

    def test_an_empty_or_over_long_secret_is_invalid_data(self):
        tr = self.run_tr({})
        for secret in ("", "x" * 129):
            with self.subTest(length=len(secret)):
                self.assertDetail(self.client.post("/initiate", json={**CREDENTIALS, "pin": secret}),
                                  422, "INVALID_DATA")
        self.assertEqual(tr.requests, [])

    def test_the_first_poll_asking_for_the_authenticator_returns_that_flow(self):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), V2_POLL: authenticator_pending()})

        body = self.initiate()

        self.assertEqual(body["flow"], "authenticator")
        self.assertEqual(body["countdownInSeconds"], 120)
        self.assertEqual(len(tr.calls(*V2_POLL)), 1)
        self.assertEqual(tr.calls(*V2_POLL)[0].headers["x-tr-device-info"],
                         tr.calls(*V2_LOGIN)[0].headers["x-tr-device-info"])

    def test_a_failing_first_poll_leaves_confirm_in_app(self):
        self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), V2_POLL: reply(500)})

        self.assertEqual(self.initiate()["flow"], "confirmInApp")

    def test_the_pin_is_never_logged(self):
        self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}), V2_POLL: pending()})
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
                         {"refresh": REFRESH, "device": DEVICE, "sub": SUB, "phone": PHONE})
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

    def test_a_slow_waf_refresh_at_the_deadline_still_ends_as_a_timeout_within_the_budget(self):
        # The poll answers with the WAF's empty 403, and the token refresh then hangs: the one
        # deadline cancels it, so the member gets the timeout code, on time.
        _, process_id = self.start({V2_POLL: reply(403)})

        async def hanging_token():
            await asyncio.sleep(5)
            return "waf"

        with patch.object(main, "get_waf_token", hanging_token):
            started = time.monotonic()
            response = self.client.post("/complete", json={"processId": process_id})

        self.assertDetail(response, 408, "APP_VALIDATION_TIMEOUT")
        self.assertLess(time.monotonic() - started, 1)

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
                         {"refresh": REFRESH, "device": DEVICE, "sub": SUB, "phone": PHONE})
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


class CompleteAuthenticatorTest(EndpointCase):
    def start(self, routes):
        tr = self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}),
                          V2_POLL: [authenticator_pending(), confirmed()], **routes})
        self.assertEqual(self.initiate()["flow"], "authenticator")
        return tr, next(iter(main._pending._states))

    def complete(self, process_id, tan="123456"):
        return self.client.post("/complete", json={"processId": process_id, "tan": tan})

    def test_the_right_code_returns_a_session_state_with_the_phone(self):
        tr, process_id = self.start({V2_AUTHENTICATOR: reply(200, {})})

        response = self.complete(process_id)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(json.loads(response.json()["sessionState"]),
                         {"refresh": REFRESH, "device": DEVICE, "sub": SUB, "phone": PHONE})
        self.assertNotIn(SESSION, response.text)
        sent = tr.calls(*V2_AUTHENTICATOR)[0]
        self.assertEqual(json.loads(sent.content), {"code": "123456"})
        self.assertEqual(sent.headers["x-aws-waf-token"], "waf")
        infos = {r.headers["x-tr-device-info"] for r in [*tr.calls(*V2_LOGIN), *tr.calls(*V2_POLL), sent]}
        self.assertEqual(len(infos), 1)

    def test_cookies_set_by_the_verification_are_kept(self):
        self.run_tr({V2_LOGIN: reply(200, {"processId": PROCESS_ID}),
                     V2_POLL: [authenticator_pending(), reply(200, {"status": "COMPLETED"})],
                     V2_AUTHENTICATOR: reply(200, {}, cookies={
                         "tr_refresh": REFRESH, "tr_device": DEVICE, "tr_claims": encode_claims(CLAIMS)})})
        self.assertEqual(self.initiate()["flow"], "authenticator")
        process_id = next(iter(main._pending._states))

        response = self.complete(process_id)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(json.loads(response.json()["sessionState"])["refresh"], REFRESH)

    def test_a_wrong_code_is_401_and_the_same_attempt_accepts_a_retry(self):
        for code_error in ("VALIDATION_CODE_INVALID", "VALIDATION_CODE_ALREADY_USED"):
            with self.subTest(code_error=code_error):
                tr, process_id = self.start({V2_AUTHENTICATOR: [
                    reply(400, {"errors": [{"errorCode": code_error}]}), reply(200, {})]})

                self.assertDetail(self.complete(process_id), 401, "INVALID_OTP")
                self.assertEqual(self.complete(process_id, "654321").status_code, 200)
                codes = [json.loads(r.content)["code"] for r in tr.calls(*V2_AUTHENTICATOR)]
                self.assertEqual(codes, ["123456", "654321"])

    def test_process_gone_or_not_found_is_an_expired_attempt(self):
        for code_error in ("PROCESS_GONE", "NOT_FOUND"):
            with self.subTest(code_error=code_error):
                _, process_id = self.start({V2_AUTHENTICATOR: reply(
                    410 if code_error == "PROCESS_GONE" else 404, {"errors": [{"errorCode": code_error}]})})

                self.assertDetail(self.complete(process_id), 410, "AUTH_ATTEMPT_EXPIRED")
                self.assertDetail(self.complete(process_id), 410, "AUTH_ATTEMPT_EXPIRED")

    def test_already_processed_is_cancelled(self):
        _, process_id = self.start({V2_AUTHENTICATOR: reply(409, {"errors": [{"errorCode": "ALREADY_PROCESSED"}]})})

        self.assertDetail(self.complete(process_id), 401, "APP_VALIDATION_CANCELLED")

    def test_too_many_requests_is_rate_limited(self):
        for response in (reply(429, {"errors": [{"errorCode": "TOO_MANY_REQUESTS",
                                                  "meta": {"nextAttemptInSeconds": 30}}]}),
                         reply(400, {"errors": [{"errorCode": "TOO_MANY_REQUESTS"}]})):
            with self.subTest(status=response.status_code):
                _, process_id = self.start({V2_AUTHENTICATOR: response})

                self.assertDetail(self.complete(process_id), 429, "RATE_LIMITED")

    def test_5xx_and_network_errors_are_upstream_unavailable(self):
        def unreachable(request):
            raise main.httpx.ConnectError("refused", request=request)

        for route in (reply(500, {"errors": []}), unreachable):
            with self.subTest(route=route):
                _, process_id = self.start({V2_AUTHENTICATOR: route})

                self.assertDetail(self.complete(process_id), 502, "UPSTREAM_UNAVAILABLE")

    def test_a_bad_code_format_is_422_and_never_reaches_tr(self):
        tr, process_id = self.start({V2_AUTHENTICATOR: reply(200, {})})
        before = len(tr.requests)

        for body in ({"processId": process_id}, {"processId": process_id, "tan": "1234"},
                     {"processId": process_id, "tan": "12345"}, {"processId": process_id, "tan": "12345a"},
                     {"processId": process_id, "tan": "1234567"}):
            with self.subTest(body=body):
                self.assertDetail(self.client.post("/complete", json=body), 422, "INVALID_OTP")
        self.assertEqual(len(tr.requests), before)
        self.assertEqual(self.complete(process_id).status_code, 200)

    def test_the_token_fetch_and_the_confirmation_wait_share_one_budget(self):
        # A slow WAF token leaves less time for the in-app confirmation, not a fresh full wait,
        # so the authenticator flow also answers before the backend's /complete timeout.
        tr, process_id = self.start({V2_AUTHENTICATOR: reply(200, {})})
        tr.routes[V2_POLL] = pending()

        async def slow_token():
            await asyncio.sleep(0.5)
            return "waf"

        with patch.object(main, "get_waf_token", slow_token):
            started = time.monotonic()
            response = self.complete(process_id)

        self.assertDetail(response, 408, "APP_VALIDATION_TIMEOUT")
        self.assertLess(time.monotonic() - started, 0.65)

    def test_the_code_is_never_logged(self):
        _, process_id = self.start({V2_AUTHENTICATOR: reply(200, {})})
        with self.assertLogs(level="DEBUG") as logs:
            self.complete(process_id, "987654")

        self.assertNotIn("987654", "\n".join(logs.output))


class CompleteOtpFourDigitsTest(EndpointCase):
    def test_a_six_digit_code_is_refused_by_the_v1_flow(self):
        self.run_tr({V2_LOGIN: reply(503), V1_LOGIN: reply(200, {"processId": PROCESS_ID})})
        self.assertEqual(self.initiate()["flow"], "otp")
        process_id = next(iter(main._pending._states))

        self.assertDetail(self.client.post("/complete", json={"processId": process_id, "tan": "123456"}),
                          422, "INVALID_OTP")


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

    def test_a_state_without_phone_is_still_accepted(self):
        self.run_tr({SESSION_ROUTE: self.session_ok()})

        for state in (STATE, json.dumps({"refresh": REFRESH, "device": DEVICE, "sub": SUB, "phone": PHONE})):
            with self.subTest(state=state):
                self.assertEqual(self.client.post("/session", json={"sessionState": state}).status_code, 200)

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


class TransactionsTest(EndpointCase):
    CSV = "datetime;type;amount\r\n2026-01-02T10:00:00Z;BUY;-12,50\r\ncafé;é;ü\r\n".encode()
    BODY = {"sessionState": STATE, "from": "2026-01-01", "to": "2026-11-04"}

    def setUp(self):
        super().setUp()
        sleep = AsyncMock()
        patcher = patch.object(main, "pause", sleep)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.sleep = sleep

    def refreshed(self):
        return reply(200, {}, cookies={"tr_session": SESSION, "tr_claims": encode_claims(CLAIMS),
                                       "JSESSIONID": "jsid", "tr_external_id": "ext"})

    def routes(self, overrides=None):
        return {SESSION_ROUTE: self.refreshed(), EXPORT_REQUEST: export_job("PENDING", 202),
                EXPORT_STATUS: export_job("COMPLETED"), EXPORT_DOWNLOAD: csv_reply(self.CSV), **(overrides or {})}

    def post(self, **body):
        return self.client.post("/transactions", json={**self.BODY, **body})

    def test_ok_returns_the_csv_verbatim(self):
        tr = self.run_tr(self.routes())

        response = self.post()

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.content, self.CSV)
        self.assertEqual(response.headers["content-type"], "text/csv; charset=utf-8")
        request = tr.calls(*EXPORT_REQUEST)[0]
        self.assertEqual(json.loads(request.content), {"from": "2026-01-01", "to": "2026-11-04"})
        for call in (request, tr.calls(*EXPORT_STATUS)[0], tr.calls(*EXPORT_DOWNLOAD)[0]):
            cookie = call.headers["cookie"]
            for expected in (f"tr_session={SESSION}", "JSESSIONID=jsid", "tr_external_id=ext", "tr_claims="):
                self.assertIn(expected, cookie)
            self.assertNotIn("tr_refresh", cookie)
            self.assertEqual(call.headers["origin"], main.TR_APP)
            self.assertEqual(call.headers["user-agent"], main.USER_AGENT)
        self.assertEqual(tr.calls(*EXPORT_DOWNLOAD)[0].headers["accept"], "*/*")
        self.assertEqual(tr.calls(*EXPORT_STATUS)[0].url.params["jobId"], JOB_ID)
        self.assertEqual(tr.calls(*EXPORT_DOWNLOAD)[0].url.params["jobId"], JOB_ID)

    def test_pending_is_polled_until_completed(self):
        tr = self.run_tr(self.routes({EXPORT_STATUS: [export_job("PENDING", seconds=1),
                                                          export_job("PENDING", seconds=7),
                                                          export_job("COMPLETED")]}))

        response = self.post()

        self.assertEqual(response.content, self.CSV)
        self.assertEqual(len(tr.calls(*EXPORT_STATUS)), 3)
        self.assertEqual([c.args[0] for c in self.sleep.await_args_list], [2, 2, 7])

    def test_a_job_that_does_not_complete_is_upstream_unavailable(self):
        tr = self.run_tr(self.routes({EXPORT_STATUS: export_job("FAILED")}))

        self.assertDetail(self.post(), 502, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(tr.calls(*EXPORT_DOWNLOAD), [])

    def test_a_job_pending_past_the_budget_is_upstream_unavailable(self):
        self.run_tr(self.routes({EXPORT_STATUS: export_job("PENDING")}))
        with patch.object(main, "EXPORT_BUDGET_S", 0.2), \
                patch.object(main, "pause", lambda seconds: asyncio.sleep(0.05)):
            self.assertDetail(self.post(), 502, "UPSTREAM_UNAVAILABLE")

    def test_refresh_rejection_is_session_expired(self):
        for status in (401, 403):
            with self.subTest(status=status):
                tr = self.run_tr(self.routes({SESSION_ROUTE: reply(status, {"errors": [{"errorCode": "UNAUTHORIZED"}]})}))
                self.assertDetail(self.post(), 401, "SESSION_EXPIRED")
                self.assertEqual(tr.calls(*EXPORT_REQUEST), [])

    def test_bad_state_is_422_and_never_reaches_tr(self):
        tr = self.run_tr(self.routes())

        self.assertDetail(self.post(sessionState="not json"), 422, "INVALID_SESSION_STATE")
        self.assertDetail(self.client.post("/transactions", json={"from": "2026-01-01", "to": "2026-01-02"}),
                          422, "INVALID_SESSION_STATE")
        self.assertEqual(tr.requests, [])

    def test_bad_dates_are_422_and_never_reach_tr(self):
        tr = self.run_tr(self.routes())
        bad = [{"from": "01/01/2026"}, {"from": "2026-13-01"}, {"to": "2026-02-30"}, {"to": "soon"}, {"from": ""},
               {"from": "2026-12-01"}, {"from": "2020-01-01", "to": "2026-11-04"}, {"from": "2026-1-1"}]

        for override in bad:
            with self.subTest(override=override):
                self.assertDetail(self.post(**override), 422, "INVALID_DATA")
        self.assertDetail(self.client.post("/transactions", json={"sessionState": STATE, "to": "2026-01-02"}),
                          422, "INVALID_DATA")
        self.assertEqual(tr.requests, [])

    def test_the_longest_range_is_accepted(self):
        self.run_tr(self.routes())

        # 1830 days, main.EXPORT_MAX_DAYS: the backend clamps its window to exactly this.
        self.assertEqual(self.post(**{"from": "2021-10-31", "to": "2026-11-04"}).status_code, 200)
        self.assertEqual(self.post(**{"from": "2026-11-04", "to": "2026-11-04"}).status_code, 200)
        self.assertDetail(self.post(**{"from": "2021-10-30", "to": "2026-11-04"}), 422, "INVALID_DATA")

    def test_429_anywhere_is_rate_limited(self):
        for step in (SESSION_ROUTE, EXPORT_REQUEST, EXPORT_STATUS, EXPORT_DOWNLOAD):
            with self.subTest(step=step):
                self.run_tr(self.routes({step: reply(429, {"errors": []})}))
                self.assertDetail(self.post(), 429, "RATE_LIMITED")

    def test_unexpected_upstream_answers_are_upstream_unavailable(self):
        def unreachable(request):
            raise main.httpx.ConnectError("refused", request=request)

        cases = {"refresh 5xx": {SESSION_ROUTE: reply(500, {"errors": []})},
                 "request 200": {EXPORT_REQUEST: export_job("PENDING", 200)},
                 "request 5xx": {EXPORT_REQUEST: reply(503, {})},
                 "status 5xx": {EXPORT_STATUS: reply(500, {})},
                 "download 404": {EXPORT_DOWNLOAD: reply(404, {})},
                 "download 406": {EXPORT_DOWNLOAD: reply(406, {})},
                 "network": {EXPORT_REQUEST: unreachable}}
        for name, override in cases.items():
            with self.subTest(name):
                self.run_tr(self.routes(override))
                self.assertDetail(self.post(), 502, "UPSTREAM_UNAVAILABLE")

    def test_a_refresh_without_a_session_cookie_is_a_format_change(self):
        self.run_tr(self.routes({SESSION_ROUTE: reply(200, {}, cookies={"tr_claims": encode_claims(CLAIMS)})}))

        self.assertDetail(self.post(), 502, "UPSTREAM_FORMAT_CHANGED")

    def test_neither_secrets_nor_the_csv_are_logged(self):
        self.run_tr(self.routes())
        with self.assertLogs(level="DEBUG") as logs:
            self.post()

        text = "\n".join(logs.output)
        for secret in (REFRESH, DEVICE, SESSION, SUB, JOB_ID, "jsid", "BUY", "café", encode_claims(CLAIMS)):
            self.assertNotIn(secret, text)
        self.assertIn(f"{len(self.CSV)} bytes, 3 lines", text)


class SurfaceTest(EndpointCase):
    def test_refresh_is_gone(self):
        self.assertEqual(self.client.post("/refresh", json={"refreshToken": "x"}).status_code, 404)

    def test_the_routes_are_exactly_the_contract(self):
        paths = {route.path for route in main.app.routes if getattr(route, "methods", None)}
        self.assertTrue({"/initiate", "/complete", "/session", "/transactions", "/health"} <= paths)
        self.assertNotIn("/refresh", paths)

    def test_every_endpoint_requires_the_sidecar_key(self):
        bodies = {"/initiate": CREDENTIALS, "/complete": {"processId": PROCESS_ID},
                  "/session": {"sessionState": STATE},
                  "/transactions": {"sessionState": STATE, "from": "2026-01-01", "to": "2026-01-02"}}
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
