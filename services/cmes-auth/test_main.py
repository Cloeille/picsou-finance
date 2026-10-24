"""The sidecar's flows end to end, against a fake saver's site.

`main._new_client` is patched to an `httpx.MockTransport` that plays the site:
login form, mobile confirmation, remembered-device cookie, detailed situation
and fund pages. Nothing reaches the real site.
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
    FUND_SAVINGS,
    LOGIN_PAGE,
    LOGIN_PAGE_WRONG_PASSWORD,
    MOBILE_CONFIRMATION_PAGE,
    SITUATION_PAGE,
    SKIP_LATER_PAGE,
    SMS_CONFIRMATION_PAGE,
    fund_summary_page,
    transaction_state,
)

KEY = "cmes-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": KEY}
SPACE = "/epargne-salariale/fr/epargnants"
MFA_PATH = f"{SPACE}/premiers-pas/authentification-forte/index.html"
DASHBOARD_PATH = f"{SPACE}/tableau-de-bord/index.html"
TRANSACTION_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0"


class FakeSite:
    """Just enough of the saver's site to drive every branch of the sidecar."""

    def __init__(self, *, second_factor="mobile", states=("PENDING", "VALIDATED")):
        self.second_factor = second_factor
        self.states = list(states)
        self.requests: list[tuple[str, str]] = []
        self.login_fields: dict[str, list[str]] = {}
        self.final_fields: dict[str, list[str]] = {}
        self.cancelled = False

    def redirect(self, location: str, *cookies: str) -> httpx.Response:
        headers = [("location", location)] + [("set-cookie", cookie) for cookie in cookies]
        return httpx.Response(302, headers=headers)

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        self.requests.append((request.method, path))
        cookies = request.headers.get("cookie", "")
        logged = "IdSes=" in cookies

        if path == main.LOGIN_PATH and request.method == "GET":
            return httpx.Response(200, text=LOGIN_PAGE)
        if path == main.LOGIN_PATH:
            self.login_fields = parse_qs(request.content.decode(), keep_blank_values=True)
            if self.login_fields.get("_cm_pwd") != ["secret"]:
                return httpx.Response(200, text=LOGIN_PAGE_WRONG_PASSWORD)
            trusted = "auth_client_state=trusted-device" in cookies
            if self.second_factor and not trusted:
                return self.redirect(MFA_PATH, "IdSes=pending; Path=/")
            return self.redirect(DASHBOARD_PATH, "IdSes=live; Path=/")
        if path == MFA_PATH and request.method == "GET":
            page = {"mobile": MOBILE_CONFIRMATION_PAGE, "sms": SMS_CONFIRMATION_PAGE, "skip": SKIP_LATER_PAGE}
            return httpx.Response(200, text=page[self.second_factor])
        if path.endswith("SOSD_OTP_GetTransactionState.htm"):
            assert parse_qs(request.content.decode()) == {"transactionId": [TRANSACTION_ID]}
            return httpx.Response(200, text=transaction_state(self.states.pop(0)))
        if path.endswith("SOSD_OTP_CancelTransaction.htm"):
            self.cancelled = True
            return httpx.Response(200, text="")
        if path == MFA_PATH:
            self.final_fields = parse_qs(request.content.decode(), keep_blank_values=True)
            return self.redirect(
                DASHBOARD_PATH,
                "IdSes=live; Path=/",
                "auth_client_state=trusted-device; Path=/; Max-Age=7776000",
            )
        if path.endswith("Bypass.html"):
            return self.redirect(DASHBOARD_PATH, "IdSes=live; Path=/")
        if not logged:
            return self.redirect(main.LOGIN_PATH)
        if path == DASHBOARD_PATH:
            return self.redirect(main.SITUATION_PATH)
        if path == main.SITUATION_PATH and request.method == "GET":
            return httpx.Response(200, text=SITUATION_PAGE)
        if path == main.SITUATION_PATH:
            fields = parse_qs(request.content.decode(), keep_blank_values=True)
            assert fields["$CPT"] == ["opaque-page-state"], "page state must be replayed"
            index = next(
                int(name.rsplit(":", 1)[1]) for name in fields if name.startswith("_FID_GoFundSummary")
            )
            return self.redirect(f"{SPACE}/supports/synthese-du-support.html?k_fundIndex={index}")
        if path.endswith("synthese-du-support.html"):
            index = int(request.url.params["k_fundIndex"])
            if request.url.params.get("_fid") == "GoMyAsset":
                return self.redirect(f"{SPACE}/supports/epargne-sur-le-support.html?k_fundIndex={index}")
            return httpx.Response(200, text=fund_summary_page(index, code=f"16{index}"))
        if path.endswith("epargne-sur-le-support.html"):
            return httpx.Response(200, text=FUND_SAVINGS[int(request.url.params["k_fundIndex"])])
        return httpx.Response(404)


class FlowTest(unittest.TestCase):
    def setUp(self):
        for name, value in (("SIDECAR_API_KEY", KEY), ("APP_VALIDATION_POLL_SECONDS", 0)):
            patcher = patch.object(main, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.site = FakeSite()
        patcher = patch.object(main, "_new_client", self.client_factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def client_factory(self):
        return httpx.AsyncClient(
            base_url=main.BASE_URL,
            transport=httpx.MockTransport(self.site),
            follow_redirects=False,
        )

    def initiate(self, password="secret", session=None):
        body = {"login": "12345678", "password": password}
        if session is not None:
            body["sessionState"] = session
        return self.client.post("/initiate", json=body, headers=HEADERS)

    def confirm(self) -> str:
        started = self.initiate()
        self.assertEqual(started.status_code, 200, started.text)
        self.assertEqual(started.json()["mfaType"], "APP_PUSH")
        completed = self.client.post(
            "/complete", json={"processId": started.json()["processId"], "code": None}, headers=HEADERS
        )
        self.assertEqual(completed.status_code, 200, completed.text)
        return completed.json()["sessionState"]

    def test_mobile_confirmation_then_positions(self):
        session = self.confirm()

        self.assertEqual(self.site.login_fields["_cm_user"], ["12345678"])
        self.assertEqual(self.site.login_fields["flag"], ["password"])
        self.assertEqual(self.site.final_fields["$CPT"], ["opaque-page-state"])
        names = {cookie["name"] for cookie in json.loads(session)["cookies"]}
        self.assertIn("auth_client_state", names)

        response = self.client.post("/positions", json={"sessionState": session}, headers=HEADERS)
        self.assertEqual(response.status_code, 200, response.text)
        [plan] = response.json()
        self.assertEqual(plan["externalId"], "cmes_12345_PEG")
        self.assertEqual(plan["employer"], "ACME INDUSTRIES")
        self.assertEqual(Decimal(plan["balanceEur"]), Decimal("1500.50"))
        self.assertEqual(
            [(p["fundCode"], p["label"], Decimal(p["quantity"]), Decimal(p["pnlEur"])) for p in plan["positions"]],
            [
                ("160", "FONDS EQUILIBRE SOLIDAIRE", Decimal("50.0000"), Decimal("90.91")),
                ("161", "FONDS ACTIONS MONDE", Decimal("25.0250"), Decimal("-12.83")),
            ],
        )

    def test_the_remembered_device_spares_the_confirmation_on_the_next_login(self):
        session = self.confirm()
        self.site.requests.clear()

        response = self.initiate(session=session)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertFalse(response.json()["mfaRequired"])
        self.assertIsNotNone(response.json()["sessionState"])
        self.assertNotIn(("GET", MFA_PATH), self.site.requests)

    def test_a_previous_session_only_lends_its_remembered_device_cookie(self):
        stale = json.dumps({"cookies": [{"name": "IdSes", "value": "dead", "domain": "", "path": "/"}]})

        response = self.initiate(session=stale)

        self.assertTrue(response.json()["mfaRequired"])

    def test_a_wrong_password_is_reported_as_such(self):
        response = self.initiate(password="wrong")

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "INVALID_CREDENTIALS"}))

    def test_no_second_factor_logs_straight_in(self):
        self.site.second_factor = None

        response = self.initiate()

        self.assertFalse(response.json()["mfaRequired"])

    def test_the_site_own_skip_link_is_followed(self):
        self.site.second_factor = "skip"

        response = self.initiate()

        self.assertEqual(response.status_code, 200, response.text)
        self.assertFalse(response.json()["mfaRequired"])

    def test_an_sms_code_is_refused_rather_than_half_driven(self):
        self.site.second_factor = "sms"

        response = self.initiate()

        self.assertEqual((response.status_code, response.json()), (501, {"detail": "MFA_TYPE_UNSUPPORTED"}))

    def test_a_confirmation_refused_on_the_phone(self):
        self.site.states = ["PENDING", "CANCELLED"]
        started = self.initiate()

        response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual(response.json(), {"detail": "APP_VALIDATION_CANCELLED"})

    def test_a_confirmation_never_approved_times_out_and_is_withdrawn(self):
        self.site.states = ["PENDING"] * 1000
        started = self.initiate()

        with patch.object(main, "APP_VALIDATION_TIMEOUT_SECONDS", 0.05):
            response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (408, {"detail": "APP_VALIDATION_TIMEOUT"}))
        self.assertTrue(self.site.cancelled)

    def test_an_unknown_process_has_expired(self):
        response = self.client.post("/complete", json={"processId": "nope"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (410, {"detail": "AUTH_ATTEMPT_EXPIRED"}))

    def test_a_code_is_refused(self):
        response = self.client.post("/complete", json={"processId": "p", "code": "123456"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (400, {"detail": "MFA_TYPE_UNSUPPORTED"}))

    def test_an_expired_session_reads_as_session_expired(self):
        dead = json.dumps({"cookies": [{"name": "other", "value": "x", "domain": "", "path": "/"}]})

        response = self.client.post("/positions", json={"sessionState": dead}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))

    def test_a_malformed_session_is_invalid_data(self):
        response = self.client.post("/positions", json={"sessionState": "{]"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (400, {"detail": "INVALID_DATA"}))


if __name__ == "__main__":
    unittest.main()
