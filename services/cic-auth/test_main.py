"""The sidecar's flows end to end, against a fake CIC customer site.

`main._new_client` is patched to an `httpx.MockTransport` that plays the site:
login form, mobile confirmation or SMS code, remembered-device cookie,
"Gestion des pouvoirs" notice and the accounts page. Nothing reaches the bank.
"""

import json
import re
import unittest
from decimal import Decimal
from unittest.mock import AsyncMock, patch
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
    accounts_table,
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
        self.accounts_page = ACCOUNTS_PAGE

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
            state = self.states.pop(0)
            if state == "UNREACHABLE":
                raise httpx.ConnectError("polling endpoint unreachable", request=request)
            if isinstance(state, int):
                return httpx.Response(state, text="Polling failed")
            if state == "MALFORMED":
                return httpx.Response(200, text="Unknown polling response")
            return httpx.Response(200, text=transaction_state(state))
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
            return httpx.Response(200, text=self.accounts_page)
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

    def test_a_wrong_code_the_full_pool_cannot_keep_is_disposed_of(self):
        self.bank.second_factor = "sms"
        process = self.initiate().json()["processId"]
        dispose = AsyncMock(wraps=main._dispose_pending_state)

        with (
            patch.object(main, "_store_pending", AsyncMock(side_effect=main.HTTPException(503, "UPSTREAM_UNAVAILABLE"))),
            patch.object(main, "_dispose_pending_state", dispose),
        ):
            wrong = self.client.post("/complete", json={"processId": process, "code": "000000"}, headers=HEADERS)

        self.assertEqual(wrong.status_code, 503)
        dispose.assert_awaited_once()

    def test_a_polling_failure_withdraws_the_confirmation_from_the_phone(self):
        self.bank.states = ["PENDING", "UNREACHABLE"]
        started = self.initiate()

        response = self.client.post("/complete", json={"processId": started.json()["processId"]}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_UNAVAILABLE"}))
        self.assertTrue(self.bank.cancelled)

    def test_terminal_http_polling_failures_withdraw_and_classify_the_confirmation(self):
        for status, expected_status, code in (
            (500, 502, "UPSTREAM_UNAVAILABLE"),
            (503, 502, "UPSTREAM_UNAVAILABLE"),
            (429, 502, "UPSTREAM_UNAVAILABLE"),
            (403, 410, "AUTH_ATTEMPT_EXPIRED"),
            (302, 410, "AUTH_ATTEMPT_EXPIRED"),
            (404, 502, "UPSTREAM_FORMAT_CHANGED"),
        ):
            with self.subTest(status=status):
                self.bank.states = [status]
                self.bank.cancelled = False
                process = self.initiate().json()["processId"]

                response = self.client.post("/complete", json={"processId": process}, headers=HEADERS)

                self.assertEqual((response.status_code, response.json()), (expected_status, {"detail": code}))
                self.assertTrue(self.bank.cancelled)
                repeated = self.client.post("/complete", json={"processId": process}, headers=HEADERS)
                self.assertEqual(repeated.status_code, 410)

    def test_unreadable_polling_responses_withdraw_the_confirmation(self):
        for state in ("MALFORMED", "UNKNOWN"):
            with self.subTest(state=state):
                self.bank.states = [state]
                self.bank.cancelled = False
                process = self.initiate().json()["processId"]

                response = self.client.post("/complete", json={"processId": process}, headers=HEADERS)

                self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))
                self.assertTrue(self.bank.cancelled)

    def test_an_account_without_its_title_does_not_return_a_partial_snapshot(self):
        session = self.confirm()
        self.bank.accounts_page = ACCOUNTS_PAGE.replace('ei_sdsf_title _c1">LIVRET A', 'other _c1">LIVRET A')
        self.assertNotEqual(self.bank.accounts_page, ACCOUNTS_PAGE)

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_unknown_product_names_and_balances_do_not_reach_logs(self):
        session = self.confirm()
        title = "Assurance 12 500,00 EUR M EXAMPLE"
        row = f'<tr><td><span class="ei_sdsf_title">{title}</span></td></tr>'
        table = accounts_table("Nouveaux contrats", row)
        self.bank.accounts_page = ACCOUNTS_PAGE.replace("</body>", table + "</body>")

        for path in ("/accounts", "/accounts/listing"):
            with self.subTest(path=path), self.assertLogs(main.log, level="WARNING") as logs:
                response = self.client.post(path, json={"sessionState": session}, headers=HEADERS)
            self.assertEqual((response.status_code, response.json()),
                             (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))
            message = " ".join(logs.output)
            self.assertIn("nouveaux contrats", message)
            for private_text in (title, "12 500", "EXAMPLE", "Assurance"):
                self.assertNotIn(private_text, message)

    def test_a_renamed_savings_table_cannot_certify_a_partial_snapshot(self):
        session = self.confirm()
        prefix, remaining = ACCOUNTS_PAGE.split("ei_comptescontrats", 1)
        self.bank.accounts_page = prefix + "ei_comptescontrats" + remaining.replace(
            "ei_comptescontrats", "ei_savingscontracts", 1)

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_a_truncated_savings_table_cannot_certify_a_partial_snapshot(self):
        session = self.confirm()
        ending = '+5&nbsp;000,00 EUR</span>'
        self.bank.accounts_page = ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index(ending) + len(ending)]

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_an_early_truncated_savings_table_cannot_certify_a_partial_snapshot(self):
        session = self.confirm()
        savings_start = ACCOUNTS_PAGE.index("Livrets et comptes d&#39;épargne")
        for ending in (
            'Livrets et comptes d&#39;épargne</span></caption>',
            'class="_c1 ei_sdsf_ti',
        ):
            with self.subTest(ending=ending):
                self.bank.accounts_page = ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index(ending, savings_start) + len(ending)]

                response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

                self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_an_entity_encoded_skip_link_is_found(self):
        page = '<a href="/skip.html">Passer cette &eacute;tape</a>'

        self.assertEqual(main._link_with(page, "passer cette étape"), "/skip.html")

    def test_a_cut_before_or_during_the_next_table_opening_cannot_certify_a_partial_snapshot(self):
        session = self.confirm()
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        opening_end = ACCOUNTS_PAGE.index('>', table_start) + 1
        for cut in range(table_start, opening_end + 1):
            with self.subTest(cut=cut):
                self.bank.accounts_page = ACCOUNTS_PAGE[:cut]

                response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

                self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_a_cut_after_the_last_table_cannot_certify_a_snapshot(self):
        session = self.confirm()
        self.bank.accounts_page = ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index('</form>')]

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_a_complete_page_with_only_checking_accounts_certifies_the_snapshot(self):
        session = self.confirm()
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        self.bank.accounts_page = ACCOUNTS_PAGE[:table_start] + ACCOUNTS_PAGE[ACCOUNTS_PAGE.index('</form>'):]

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual([account["type"] for account in response.json()], ["CHECKING"])
        self.assertTrue(response.json()[0]["snapshotComplete"])

    def test_a_savings_table_without_its_caption_does_not_return_a_partial_snapshot(self):
        session = self.confirm()
        self.bank.accounts_page = ACCOUNTS_PAGE.replace(
            '<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : '
            'Livrets et comptes d&#39;épargne</span></caption>',
            '',
        )
        self.assertNotEqual(self.bank.accounts_page, ACCOUNTS_PAGE)

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_a_typographic_apostrophe_returns_a_complete_snapshot(self):
        session = self.confirm()
        self.bank.accounts_page = ACCOUNTS_PAGE.replace("d&#39;épargne", "d’épargne")

        response = self.client.post("/accounts", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual([account["type"] for account in response.json()], ["CHECKING", "LIVRET_A", "LDDS", "PEL"])
        self.assertTrue(all(account["snapshotComplete"] for account in response.json()))

        listing = self.client.post("/accounts/listing", json={"sessionState": session}, headers=HEADERS)
        self.assertEqual(listing.status_code, 200, listing.text)
        self.assertEqual([account["type"] for account in listing.json()["accounts"]],
                         ["CHECKING", "LIVRET_A", "LDDS", "PEL"])
        self.assertEqual(listing.json()["categoriesRead"], ["CHECKING", "SAVINGS"])

    def test_incidental_page_content_and_title_only_contracts_preserve_certified_accounts(self):
        session = self.confirm()
        fragments = (
            '<table class="layout"><tr><td>Depuis 2026 ils proposent. '
            'SOS 24h/24. Top 10</td></tr></table>',
            "<p>Total : 10 135,06 EUR</p>",
            '<div class="doux">Mentions légales</div>',
            "<p>Jusqu'à 80 € offerts</p>",
            '<table class="ei_comptescontrats"><tr><td>'
            '<span class="ei_sdsf_title">Vos contrats et nos offres</span></td>'
            '<td class="ei_sdsf_solde"></td></tr></table>',
            '<table class="ei_comptescontrats"><tr><td>'
            '<span class="ei_sdsf_title">Contrat 2024 USD</span></td></tr></table>',
        )
        self.bank.accounts_page = ACCOUNTS_PAGE.replace("</body>", "".join(fragments) + "</body>")

        for path in ("/accounts", "/accounts/listing"):
            with self.subTest(path=path):
                response = self.client.post(path, json={"sessionState": session}, headers=HEADERS)

                self.assertEqual(response.status_code, 200, response.text)
                payload = response.json()
                accounts = payload if path == "/accounts" else payload["accounts"]
                self.assertEqual([account["type"] for account in accounts], ["CHECKING", "LIVRET_A", "LDDS", "PEL"])
                self.assertTrue(all(account["snapshotComplete"] for account in accounts))
                if path == "/accounts/listing":
                    self.assertEqual(payload["categoriesRead"], ["CHECKING", "SAVINGS"])

    def test_the_listing_reports_only_the_tables_on_the_page(self):
        # The savings table is missing: the backend must not read that as "no livret any more".
        self.bank.accounts_page = re.sub(
            r"<table[^>]*>\s*<caption[^>]*>\s*<span[^>]*>[^<]*cat&#233;gorie : Livrets.*?</table>",
            "", ACCOUNTS_PAGE, flags=re.DOTALL)
        self.assertNotIn("LIVRET A", self.bank.accounts_page)
        session = self.confirm()

        listing = self.client.post("/accounts/listing", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual(listing.status_code, 200, listing.text)
        self.assertEqual([account["type"] for account in listing.json()["accounts"]], ["CHECKING"])
        self.assertEqual(listing.json()["categoriesRead"], ["CHECKING"])

    def test_the_listing_reports_an_empty_savings_table_as_read(self):
        session = self.confirm()
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        self.bank.accounts_page = (
            ACCOUNTS_PAGE[:table_start]
            + accounts_table("Livrets et comptes d&#39;épargne", "")
            + ACCOUNTS_PAGE[ACCOUNTS_PAGE.index('</form>'):]
        )

        listing = self.client.post("/accounts/listing", json={"sessionState": session}, headers=HEADERS)

        self.assertEqual(listing.status_code, 200, listing.text)
        self.assertEqual([account["type"] for account in listing.json()["accounts"]], ["CHECKING"])
        self.assertTrue(listing.json()["accounts"][0]["snapshotComplete"])
        self.assertEqual(listing.json()["categoriesRead"], ["CHECKING", "SAVINGS"])

    def test_the_listing_rejects_a_partial_page_before_reporting_categories(self):
        session = self.confirm()
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        prefix, remaining = ACCOUNTS_PAGE.split("ei_comptescontrats", 1)
        for page in (
            ACCOUNTS_PAGE[:table_start],
            ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index('>LIVRET A</span>')],
            prefix + "ei_comptescontrats" + remaining.replace("ei_comptescontrats", "ei_savingscontracts", 1),
        ):
            with self.subTest(page=page):
                self.bank.accounts_page = page

                listing = self.client.post("/accounts/listing", json={"sessionState": session}, headers=HEADERS)

                self.assertEqual((listing.status_code, listing.json()), (502, {"detail": "UPSTREAM_FORMAT_CHANGED"}))

    def test_only_checked_boxes_are_sent(self):
        [form] = main.parse_forms(
            '<form action="/a">'
            '<input type="checkbox" name="consent" value="unchecked" />'
            '<input type="checkbox" name="remember" checked />'
            '<input type="radio" name="channel" value="sms" checked="checked" />'
            "</form>"
        )

        self.assertEqual(form.fields, {"remember": "on", "channel": "sms"})

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
