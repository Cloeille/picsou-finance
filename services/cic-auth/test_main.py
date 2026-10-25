"""The sidecar's flows end to end, against a fake CIC customer site.

`main._new_client` is patched to an `httpx.MockTransport` that plays the site:
login form, mobile confirmation or SMS code, remembered-device cookie,
"Gestion des pouvoirs" notice and the accounts page. Nothing reaches the bank.
"""

import json
import unittest
from decimal import Decimal
from unittest.mock import patch
from urllib.parse import parse_qs

import httpx
from fastapi.testclient import TestClient

import main
from fixtures import (
    ACCOUNTS_PAGE,
    AUTHORITY_MANAGEMENT_PAGE,
    LOGIN_PAGE,
    LOGIN_PAGE_WRONG_PASSWORD,
    MOBILE_CONFIRMATION_PAGE,
    SMS_CONFIRMATION_PAGE,
    transaction_state,
)

KEY = "cic-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": KEY}
MFA_PATH = "/fr/banque/validation.aspx"
HOME_PATH = "/fr/banque/pageaccueil.html"
AUTHORITY_PATH = "/fr/banque/migr_gestion_pouvoirs.html"
DEVICE_COOKIE = "%5Fwirt%5Fsosd%5FSOSD%5FOTPAUTH%5Fsaguid"
TRANSACTION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0"


class FakeBank:
    def __init__(self, *, second_factor="mobile", states=("PENDING", "VALIDATED"), authority_notice=False):
        self.second_factor = second_factor
        self.states = list(states)
        self.authority_notice = authority_notice
        self.requests: list[tuple[str, str]] = []
        self.final_fields: dict[str, list[str]] = {}
        self.cancelled = False

    def redirect(self, location: str, *cookies: str) -> httpx.Response:
        headers = [("location", location)] + [("set-cookie", cookie) for cookie in cookies]
        return httpx.Response(302, headers=headers)

    def logged_in(self) -> httpx.Response:
        if self.authority_notice:
            return self.redirect(AUTHORITY_PATH, "IdSes=live; Path=/")
        return self.redirect(HOME_PATH, "IdSes=live; Path=/")

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        self.requests.append((request.method, path))
        cookies = request.headers.get("cookie", "")
        logged = "IdSes=live" in cookies

        if path == main.LOGIN_PATH and request.method == "GET":
            return httpx.Response(200, text=LOGIN_PAGE)
        if path == main.LOGIN_PATH:
            fields = parse_qs(request.content.decode(), keep_blank_values=True)
            if fields.get("_cm_pwd") != ["secret"]:
                return httpx.Response(200, text=LOGIN_PAGE_WRONG_PASSWORD)
            # The device is remembered by the trust cookie and the device id together.
            trusted = "auth_client_state=trusted-device" in cookies and f"{DEVICE_COOKIE}=device-1" in cookies
            if self.second_factor and not trusted:
                return self.redirect(MFA_PATH, "IdSes=pending; Path=/")
            return self.logged_in()
        if path == MFA_PATH and request.method == "GET":
            page = {"mobile": MOBILE_CONFIRMATION_PAGE, "sms": SMS_CONFIRMATION_PAGE}[self.second_factor]
            return httpx.Response(200, text=page)
        if path == main.DEFAULT_STATE_PATH:
            return httpx.Response(200, text=transaction_state(self.states.pop(0)))
        if path == main.DEFAULT_CANCEL_PATH:
            self.cancelled = True
            return httpx.Response(200, text="")
        if path == MFA_PATH:
            self.final_fields = parse_qs(request.content.decode(), keep_blank_values=True)
            if self.second_factor == "sms" and (
                self.final_fields.get("otp_password") != ["123456"]
                or "_FID_CheckOrDeliverBackup" not in self.final_fields
            ):
                return httpx.Response(200, text=SMS_CONFIRMATION_PAGE)
            if self.second_factor == "mobile" and "_FID_DoValidate" not in self.final_fields:
                # Approved on the phone but not submitted with the button: still parked.
                return httpx.Response(200, text=MOBILE_CONFIRMATION_PAGE)
            target = AUTHORITY_PATH if self.authority_notice else HOME_PATH
            return self.redirect(
                target,
                "IdSes=live; Path=/",
                "auth_client_state=trusted-device; Path=/; Max-Age=7776000",
                f"{DEVICE_COOKIE}=device-1; Path=/",
            )
        if not logged:
            return self.redirect(main.LOGIN_PATH)
        if path == AUTHORITY_PATH and request.method == "GET":
            return httpx.Response(200, text=AUTHORITY_MANAGEMENT_PAGE)
        if path == AUTHORITY_PATH:
            assert "_FID_DoStopPaciAndRemind" in parse_qs(request.content.decode())
            self.authority_notice = False
            return self.redirect(HOME_PATH)
        if path in (HOME_PATH, main.ACCOUNTS_PATH):
            return httpx.Response(200, text=ACCOUNTS_PAGE)
        return httpx.Response(404)


class FlowTest(unittest.TestCase):
    def setUp(self):
        for name, value in (("SIDECAR_API_KEY", KEY), ("APP_VALIDATION_POLL_SECONDS", 0)):
            patcher = patch.object(main, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.bank = FakeBank()
        patcher = patch.object(main, "_new_client", self.client_factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def client_factory(self):
        return httpx.AsyncClient(base_url=main.BASE_URL, transport=httpx.MockTransport(self.bank), follow_redirects=False)

    def initiate(self, password="secret", session=None):
        body = {"login": "12345678", "password": password}
        if session is not None:
            body["sessionState"] = session
        return self.client.post("/initiate", json=body, headers=HEADERS)

    def confirm(self) -> str:
        started = self.initiate()
        self.assertEqual(started.status_code, 200, started.text)
        self.assertEqual(started.json()["mfaType"], "APP_PUSH")
        done = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)
        self.assertEqual(done.status_code, 200, done.text)
        return done.json()["sessionState"]

    def test_mobile_confirmation_polls_the_documented_path_then_reads_the_accounts(self):
        session = self.confirm()

        # The page script named no URL: the platform's documented one is used.
        self.assertIn(("POST", main.DEFAULT_STATE_PATH), self.bank.requests)
        self.assertEqual(self.bank.final_fields["$CPT"], ["opaque-page-state"])
        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(
            [(a["externalId"], a["type"], Decimal(a["balanceEur"])) for a in response.json()],
            [
                ("cic_1000000012345601", "CHECKING", Decimal("1234.56")),
                ("cic_1000000012345602", "LIVRET_A", Decimal("5000.00")),
                ("cic_1000000012345603", "LDDS", Decimal("800.00")),
                ("cic_1000000012345604", "PEL", Decimal("3100.50")),
            ],
        )

    def test_the_remembered_device_spares_the_confirmation_next_time(self):
        session = self.confirm()
        self.bank.requests.clear()

        response = self.initiate(session=session)

        self.assertFalse(response.json()["mfaRequired"])
        self.assertNotIn(("GET", MFA_PATH), self.bank.requests)

    def test_an_sms_code_is_posted_with_the_check_button(self):
        self.bank.second_factor = "sms"
        started = self.initiate()
        self.assertEqual(started.json()["mfaType"], "SMS")

        wrong = self.client.post("/complete", json={"processId": started.json()["processId"], "code": "000000"}, headers=HEADERS)
        right = self.client.post("/complete", json={"processId": started.json()["processId"], "code": "123456"}, headers=HEADERS)

        self.assertEqual(wrong.json(), {"detail": "INVALID_OTP"})
        self.assertEqual(right.status_code, 200, right.text)
        self.assertEqual(self.bank.final_fields["_FID_CheckOrDeliverBackup"], ["Valider"])

    def test_the_authority_management_notice_is_postponed_like_woob_does(self):
        self.bank.second_factor = None
        self.bank.authority_notice = True

        response = self.initiate()

        self.assertEqual(response.status_code, 200, response.text)
        self.assertFalse(response.json()["mfaRequired"])
        self.assertIn(("POST", AUTHORITY_PATH), self.bank.requests)

    def test_an_unattended_re_login_withdraws_the_confirmation_from_the_phone(self):
        response = self.client.post(
            "/initiate", json={"login": "12345678", "password": "secret", "unattended": True}, headers=HEADERS
        )

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))
        self.assertTrue(self.bank.cancelled)

    def test_a_wrong_password_is_reported_as_such(self):
        response = self.initiate(password="wrong")

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "INVALID_CREDENTIALS"}))

    def test_an_unapproved_confirmation_times_out_and_is_withdrawn(self):
        self.bank.states = ["PENDING"] * 1000
        started = self.initiate()

        with patch.object(main, "APP_VALIDATION_TIMEOUT_SECONDS", 0.05):
            response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual(response.json(), {"detail": "APP_VALIDATION_TIMEOUT"})
        self.assertTrue(self.bank.cancelled)

    def test_an_expired_session_reads_as_session_expired(self):
        dead = json.dumps({"cookies": [{"name": "other", "value": "x", "domain": "", "path": "/"}]})

        response = self.client.post("/accounts", json={"sessionState": dead}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))


if __name__ == "__main__":
    unittest.main()
