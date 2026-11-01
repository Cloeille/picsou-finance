"""The sidecar's flows end to end, against a fake saver's site.

`euro_information.client.new_client` is patched to an `httpx.MockTransport` that plays the site:
login form, mobile confirmation, remembered-device cookie, detailed situation
and fund pages. Nothing reaches the real site.
"""

import json
import os
import unittest
from decimal import Decimal
from unittest.mock import AsyncMock, patch
from urllib.parse import parse_qs

import httpx
from euro_information import client as euro_client
from euro_information import forms
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
    SMS_WRONG_CODE_PAGE,
    fund_summary_page,
    plan_table,
    situation_page,
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
        self.fund_fields: dict[str, list[str]] = {}
        self.cancelled = False
        self.situation = SITUATION_PAGE
        self.expire_before_savings = False

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
            state = self.states.pop(0)
            if state == "UNREACHABLE":
                raise httpx.ConnectError("polling endpoint unreachable", request=request)
            if isinstance(state, int):
                return httpx.Response(state, text="")
            return httpx.Response(200, text=transaction_state(state))
        if path.endswith("SOSD_OTP_CancelTransaction.htm"):
            self.cancelled = True
            return httpx.Response(200, text="")
        if path == MFA_PATH:
            self.final_fields = parse_qs(request.content.decode(), keep_blank_values=True)
            if self.second_factor == "sms" and (
                self.final_fields.get("otp_password") != ["123456"]
                or "_FID_CheckOrDeliverBackup" not in self.final_fields
            ):
                return httpx.Response(200, text=SMS_WRONG_CODE_PAGE)
            if self.second_factor == "mobile" and "_FID_DoValidate" not in self.final_fields:
                # Approved on the phone but not submitted with the button: still parked.
                return httpx.Response(200, text=MOBILE_CONFIRMATION_PAGE)
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
            return httpx.Response(200, text=self.situation)
        if path == main.SITUATION_PATH:
            fields = parse_qs(request.content.decode(), keep_blank_values=True)
            self.fund_fields = fields
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
            if self.expire_before_savings:
                return self.redirect(main.LOGIN_PATH)
            return httpx.Response(200, text=FUND_SAVINGS[int(request.url.params["k_fundIndex"])])
        return httpx.Response(404)


class FlowTest(unittest.TestCase):
    def setUp(self):
        for patcher in (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": KEY}),
            patch.object(euro_client, "APP_VALIDATION_POLL_SECONDS", 0),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)
        self.site = FakeSite()
        patcher = patch.object(euro_client, "new_client", self.client_factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.client = TestClient(main.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)

    def client_factory(self, site):
        return httpx.AsyncClient(
            base_url=site.base_url,
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
        self.assertEqual(plan["externalId"], main.plan_external_id("12345", "Plan d'épargne groupe"))
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

    def test_an_unattended_re_login_withdraws_the_confirmation_from_the_phone(self):
        response = self.client.post(
            "/initiate", json={"login": "12345678", "password": "secret", "unattended": True}, headers=HEADERS
        )

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))
        self.assertTrue(self.site.cancelled)

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

    def sms_login(self) -> str:
        self.site.second_factor = "sms"
        started = self.initiate()
        self.assertEqual(started.status_code, 200, started.text)
        self.assertEqual(started.json()["mfaType"], "SMS")
        return started.json()["processId"]

    def test_an_sms_code_logs_in_and_earns_the_remembered_device(self):
        process = self.sms_login()

        response = self.client.post("/complete", json={"processId": process, "code": "123456"}, headers=HEADERS)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(self.site.final_fields["otp_password"], ["123456"])
        self.assertEqual(self.site.final_fields["otp_hidden"], ["opaque-otp-state"])
        self.assertEqual(self.site.final_fields["$CPT"], ["opaque-page-state"])
        # The form's button is what checks the code; without it the site sends a new SMS.
        self.assertEqual(self.site.final_fields["_FID_CheckOrDeliverBackup"], ["Valider"])
        names = {cookie["name"] for cookie in json.loads(response.json()["sessionState"])["cookies"]}
        self.assertIn("auth_client_state", names)

    def test_a_mistyped_sms_code_can_be_typed_again(self):
        process = self.sms_login()

        wrong = self.client.post("/complete", json={"processId": process, "code": "000000"}, headers=HEADERS)
        right = self.client.post("/complete", json={"processId": process, "code": "123456"}, headers=HEADERS)

        self.assertEqual((wrong.status_code, wrong.json()), (400, {"detail": "INVALID_OTP"}))
        self.assertEqual(right.status_code, 200, right.text)

    def test_an_sms_login_without_its_code_stays_open(self):
        process = self.sms_login()

        missing = self.client.post("/complete", json={"processId": process}, headers=HEADERS)
        right = self.client.post("/complete", json={"processId": process, "code": "123456"}, headers=HEADERS)

        self.assertEqual(missing.json(), {"detail": "INVALID_OTP"})
        self.assertEqual(right.status_code, 200, right.text)

    def test_a_confirmation_refused_on_the_phone(self):
        self.site.states = ["PENDING", "CANCELLED"]
        started = self.initiate()

        response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual(response.json(), {"detail": "APP_VALIDATION_CANCELLED"})

    def test_a_confirmation_never_approved_times_out_and_is_withdrawn(self):
        self.site.states = ["PENDING"] * 1000
        started = self.initiate()

        with patch.object(euro_client, "APP_VALIDATION_TIMEOUT_SECONDS", 0.05):
            response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (408, {"detail": "APP_VALIDATION_TIMEOUT"}))
        self.assertTrue(self.site.cancelled)

    def test_a_polling_failure_withdraws_the_confirmation_from_the_phone(self):
        self.site.states = ["PENDING", "UNREACHABLE"]
        started = self.initiate()

        response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_UNAVAILABLE"}))
        self.assertTrue(self.site.cancelled)

    def test_a_polling_error_status_withdraws_the_confirmation_from_the_phone(self):
        for status in (429, 503):
            with self.subTest(status=status):
                self.site.states = ["PENDING", status]
                self.site.cancelled = False
                started = self.initiate()

                response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

                self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_UNAVAILABLE"}))
                self.assertTrue(self.site.cancelled)

    def test_a_wrong_code_the_full_pool_cannot_keep_is_disposed_of(self):
        process = self.sms_login()
        dispose = AsyncMock(wraps=euro_client.dispose_pending_state)

        with (
            patch.object(main._pending, "put", AsyncMock(return_value=False)),
            patch.object(main._pending, "_dispose", dispose),
        ):
            wrong = self.client.post("/complete", json={"processId": process, "code": "000000"}, headers=HEADERS)

        self.assertEqual(wrong.status_code, 503)
        dispose.assert_awaited_once()

    def test_the_fund_button_is_sent_with_its_served_value(self):
        self.site.situation = SITUATION_PAGE.replace(
            'value="FONDS ACTIONS MONDE" name=', 'value="Voir le support" name='
        )
        session = self.confirm()

        response = self.client.post("/positions", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(self.site.fund_fields["_FID_GoFundSummary_selectedFundIndex:1"], ["Voir le support"])

    def test_a_session_that_ends_before_the_savings_tab_reads_as_session_expired(self):
        session = self.confirm()
        self.site.expire_before_savings = True

        response = self.client.post("/positions", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))

    def plans_listed(self, *labels: str) -> list[dict]:
        self.site.situation = situation_page("".join(plan_table(label, "0,00", "") for label in labels))
        response = self.client.post("/positions", json={"sessionState": self.session}, headers=HEADERS)
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_a_plan_keeps_its_id_while_other_plans_come_and_go(self):
        self.session = self.confirm()
        company, inter_company = "Plan d&#39;&#233;pargne entreprise", "Plan d&#39;&#233;pargne interentreprises"

        [alone] = self.plans_listed(company)
        together = self.plans_listed(company, inter_company)
        [other_alone] = self.plans_listed(inter_company)

        self.assertEqual([plan["externalId"] for plan in together], [alone["externalId"], other_alone["externalId"]])
        self.assertNotEqual(alone["externalId"], other_alone["externalId"])
        self.assertTrue(alone["externalId"].startswith("cmes_12345_plan-d-pargne-entreprise-"))

    def test_labels_that_slug_alike_keep_distinct_ids(self):
        self.session = self.confirm()

        plans = self.plans_listed("Plan d&#39;&#233;pargne entreprise ACME+A", "Plan d&#39;&#233;pargne entreprise ACME A")

        self.assertEqual(len({plan["externalId"] for plan in plans}), 2)

    def test_two_plans_sharing_an_id_are_refused(self):
        self.session = self.confirm()
        self.site.situation = situation_page(
            plan_table("Plan d&#39;&#233;pargne groupe", "0,00", "")
            + plan_table("Plan d&#39;&#233;pargne entreprise", "0,00", "")
        )

        with patch.object(main, "plan_external_id", lambda number, label: f"cmes_{number}_same"):
            response = self.client.post("/positions", json={"sessionState": self.session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_each_plan_names_every_id_earlier_builds_could_have_given_it(self):
        self.session = self.confirm()

        [alone] = self.plans_listed("Plan d&#39;&#233;pargne groupe ACME")
        beside = self.plans_listed("Plan d&#39;&#233;pargne groupe ACME", "Plan d&#39;&#233;pargne groupe GLOBEX")

        # The plans listed beside it at the last old sync are unknown now: a plan
        # names the same candidates whatever it is listed with.
        self.assertEqual(
            alone["legacyExternalIds"],
            ["cmes_12345_PEG", "cmes_12345_PEG-plan-d-pargne-groupe-acme"],
        )
        self.assertEqual(beside[0]["legacyExternalIds"], alone["legacyExternalIds"])
        self.assertEqual(
            beside[1]["legacyExternalIds"],
            ["cmes_12345_PEG", "cmes_12345_PEG-plan-d-pargne-groupe-globex"],
        )

    def test_labels_that_differ_only_by_an_accent_keep_distinct_ids(self):
        # The slug drops accented letters, so both slug to plan-d-pargne-groupe-acme;
        # the label digest still tells them apart.
        self.session = self.confirm()

        plans = self.plans_listed("Plan d&#39;&#233;pargne groupe ACME", "Plan d&#39;&#232;pargne groupe ACME")

        self.assertEqual(len({plan["externalId"] for plan in plans}), 2)

    def test_an_entity_encoded_skip_link_is_found(self):
        page = '<a href="/skip.html">Passer cette &eacute;tape</a>'

        self.assertEqual(euro_client.link_with(page, "passer cette étape"), "/skip.html")

    def test_an_unknown_process_has_expired(self):
        response = self.client.post("/complete", json={"processId": "nope"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (410, {"detail": "AUTH_ATTEMPT_EXPIRED"}))

    def test_a_malformed_code_is_refused(self):
        response = self.client.post("/complete", json={"processId": "p", "code": "12ab"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (400, {"detail": "INVALID_OTP"}))

    def test_a_mobile_confirmation_takes_no_code(self):
        started = self.initiate()

        response = self.client.post(
            "/complete", json={"processId": started.json()["processId"], "code": "123456"}, headers=HEADERS
        )

        self.assertEqual(response.json(), {"detail": "MFA_TYPE_UNSUPPORTED"})

    def test_an_expired_session_reads_as_session_expired(self):
        dead = json.dumps({"cookies": [{"name": "other", "value": "x", "domain": "", "path": "/"}]})

        response = self.client.post("/positions", json={"sessionState": dead}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))

    def test_a_malformed_session_is_invalid_data(self):
        response = self.client.post("/positions", json={"sessionState": "{]"}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (400, {"detail": "INVALID_DATA"}))


if __name__ == "__main__":
    unittest.main()
