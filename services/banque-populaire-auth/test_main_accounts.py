"""POST /token-check and POST /accounts for Banque Populaire, against a synthetic bank.

The bank is an `httpx.MockTransport` playing the hosts observed live (2026-11-05,
2026-11-08): the replay chain on `as-ext-bad-ib` and icgauth, the data API on
`rs-ext-bad-ib`. Products are those seen on real accounts (current account with an
immediate-debit card, which this site does not import, Livret A and Livret Jeune
both `3/01`, LDDS `3/28`, Compte sur Livret `3/02`, PEL `3/04`, term deposit `4/09`,
cooperative shares `4/10`, a revolving
credit `2/11` with a null balance, which this site does not read), a personal and a home loan and a
PER `4/05` read from the insurer app (the `PERIB` shape of issue #524, synthetic digits).
Every value is synthetic. The personal-loan and home-loan apps (their public OAuth clients,
read from the bank's web app configuration, never observed live) are played too: the first
on the main hosts, the second on the two BPCE hosts it shares with Caisse d'Epargne.
"""

import copy
import json
import os
import unittest
import urllib.parse
from unittest.mock import patch

import httpx
from bpce import fetcher, replay
from fastapi.testclient import TestClient

import main
from fixtures_per import (
    INSURER_HOST, PER_ACCESS_TOKEN, PER_BANK_ID, PER_CLIENT_ID, PER_DETAIL,
    PER_JWT, PER_PRODUCER_ID, PerFakeBank, make_bad_per_contracts,
)

TEST_KEY = "banque-populaire-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": TEST_KEY}
AS_HOST = "www.as-ext-bad-ib.banquepopulaire.fr"
LOGIN_HOST = "www.icgauth.banquepopulaire.fr"
DATA_HOST = "www.rs-ext-bad-ib.banquepopulaire.fr"
BPCE_AS_HOST = "www.as-ext-bad-me.bpce.fr"
BPCE_RS_HOST = "www.rs-ext-bad-me.bpce.fr"
ACCESS_TOKEN = "synthetic-access-token"
LOAN_TOKEN = "synthetic-personal-loan-token"
HOME_TOKEN = "synthetic-home-loan-token"
LOAN_CLIENT_ID = "4107d671-4e78-4f18-8842-83d7ca6735a9"
HOME_CLIENT_ID = "873a10ce-67a7-4917-ab31-e5a482617303"
LOAN_PATH = "/bapi/personalLoanPrd/v1/personalLoanPrdSynthesisView/"
HOME_LOAN_PATH = "/bapi/loan/v1/loans/"
LOAN_SCOPE = "managePersonalLoanPrd readPersonalLoanPrd readUser"
LOAN_REDIRECT = "https://www.banquepopulaire.fr/espace-gestion/pret-personnel/postMessage/index.html"
HOME_REDIRECT = "https://www.banquepopulaire.fr/gestion-client/credit-immobilier/postMessage/index.html"
PERSONAL_DETAIL = {"personalLoanPrdSynthesisView": {
    "linkedAccount": {"iban": "FR7600000000000000000000001"},
    "offer": {
        "duration": 48,
        "proposedAmount": {"value": 12000, "currencyCode": "EUR"},
        "signatureDate": "15/09/2026",
        "firstDueDate": "05/11/2026",
    },
    "folder": {
        "lastDueDate": "05/10/2030",
        "nextMonthlyPaymentAmount": {"value": 280.0, "currencyCode": "EUR"},
        "tNC": 3,
    },
}}
HOME_DETAIL = {"loan": {
    "contractCharacteristic": {"subscriptionDate": "2022-05-02"},
    "financialInformation": {
        "amountBorrowed": {"currencyCode": "EUR", "value": 200000.0},
        "duration": 240,
        "nextDuedateRate": 1.2,
        "nextDuedateAmount": {"currencyCode": "EUR", "value": 937.74},
        "lastDuedate": "2042-06-05",
    },
}}
CLIENT_ID = "00000000-0000-4000-8000-000000000001"
SESSION_STATE = json.dumps({
    "cookies": [{"name": "SSO", "value": "BP-COOKIE-SECRET", "domain": ".banquepopulaire.fr", "path": "/"}],
    "authorizeParams": {
        "client_id": CLIENT_ID,
        "redirect_uri": "https://www.banquepopulaire.fr/espace-client/postMessage/index.html",
        "scope": "",
        "response_type": "code",
        "cdetab": "00000",
        "login_hint": "SECRET-LOGIN-HINT",
    },
})


def contract(pfm_id, family, sub, product_label, *, balance=100.0, contract_id=None, cards=None, **identity):
    return {
        "identification": {
            "contractId": {"entityCode": "00000", "id": contract_id or f"000000{pfm_id}"},
            "contractPfmId": pfm_id,
        },
        "identity": {
            "balance": None if balance is None else {"value": balance, "currencyCode": "EUR"},
            "contractLabel": f"SYNTHETIC {product_label}",
            "productLabel": product_label,
            "productFamilyPFM": {"code": family},
            "subProductFamilyPFM": None if sub is None else {"code": sub},
            "augmentedCards": cards,
            **identity,
        },
    }


CARD = {
    "cardPfmId": 9001,
    "cardProductLabel": "SYNTHETIC VISA",
    "cardStatusType": {"code": "100"},
    "cardCreditIndicator": False,
    "defferedDebitIndicator": False,
}
SYNTHESIS = {"items": [
    contract(1001, "1", None, "Compte De Cheques", balance=1234.5, cards=[CARD],
             authorizedOverdraft={"value": 0.0, "currencyCode": "EUR"}),
    contract(1002, "3", "01", "Livret A-Particuliers", balance=5000.0,
             authorizedCeilingAmount={"value": 22950.0, "currencyCode": "EUR"}),
    contract(1003, "3", "01", "Livret Jeune", balance=800.0, contractLabel="Livret Pour La Jeunesse",
             authorizedCeilingAmount={"value": 1600.0, "currencyCode": "EUR"}),
    contract(1004, "3", "28", "LDDS", balance=300.0),
    contract(1005, "3", "02", "Compte Sur Livret", balance=250.0),
    contract(1006, "3", "04", "Plan Epargne Logement", balance=15000.0,
             authorizedCeilingAmount={"value": 61200.0, "currencyCode": "EUR"}),
    contract(None, "4", "09", "Tip Top'P 3 Ans", balance=1234.56, contract_id="TD1"),
    contract(None, "4", "10", "Part Sociale BP", balance=250.0, contract_id="CS1"),
    contract(None, "6", "12", "Pret Personnel", balance=-1000.0, contract_id="PRET1"),
    contract(None, "6", "13", "Credit Immobilier", balance=-90000.0, contract_id="IMMO1"),
    contract(None, "4", "05", "PER", balance=1700.0, contract_id=PER_BANK_ID),
    contract(None, "2", "11", "Credit Renouvelable Facelia", balance=None, contract_id="FACELIA1"),
]}


class FakeBank(PerFakeBank):
    """The four-step chain and the two data paths; records every request."""

    def __init__(self):
        super().__init__()
        self.requests: list[httpx.Request] = []
        self.authorize_action = f"https://{LOGIN_HOST}/dacsrest/api/v1u0/saml2/sso"
        self.authn_status = "AUTHENTICATION_SUCCESS"
        self.failing_transactions: set[str] = set()
        self.synthesis = SYNTHESIS
        # Client ids whose authorize answer leads to a refused AuthnRequest (401).
        self.refused_clients: set[str] = set()
        self.loan_detail = copy.deepcopy(PERSONAL_DETAIL)
        self.home_detail = copy.deepcopy(HOME_DETAIL)
        self.loan_detail_http = 200

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        host, path = request.url.host, request.url.path
        if (host, path) in ((AS_HOST, "/api/oauth/v2/authorize"), (BPCE_AS_HOST, "/api/oauth/v2/authorize")):
            action = self.authorize_action
            if request.url.params.get("client_id") in self.refused_clients:
                action = f"https://{LOGIN_HOST}/dacsrest/api/v1u0/saml2/refused"
            return httpx.Response(200, json={"action": action, "parameters": {"SAMLRequest": "S"}})
        if (host, path) == (LOGIN_HOST, "/dacsrest/api/v1u0/saml2/refused"):
            return httpx.Response(401)
        if (host, path) == (LOGIN_HOST, "/dacsrest/api/v1u0/saml2/sso"):
            # Observed on both banks: a 303 to the transaction on the same host.
            return httpx.Response(303, headers={"location": "/dacsrest/api/v1u0/transaction/ctx"})
        if (host, path) == (LOGIN_HOST, "/dacsrest/api/v1u0/transaction/ctx"):
            return httpx.Response(200, json={"response": {
                "status": self.authn_status,
                "saml2_post": {"action": f"https://{AS_HOST}/api/oauth/v2/consume", "samlResponse": "R"},
            }})
        if (host, path) == (AS_HOST, "/api/oauth/v2/consume"):
            return httpx.Response(200, json={"parameters": {"code": "synthetic-code"}})
        if (host, path) == (AS_HOST, "/api/oauth/v2/token"):
            form = dict(urllib.parse.parse_qsl(request.content.decode()))
            token = {PER_CLIENT_ID: PER_ACCESS_TOKEN, LOAN_CLIENT_ID: LOAN_TOKEN}.get(form.get("client_id"), ACCESS_TOKEN)
            return httpx.Response(200, json={"access_token": token, "token_type": "Bearer", "expires_in": 267})
        if (host, path) == (BPCE_AS_HOST, "/api/oauth/v2/token"):
            return httpx.Response(200, json={"access_token": HOME_TOKEN, "token_type": "Bearer", "expires_in": 267})
        if (host, path.rsplit("/", 1)[0] + "/") == (DATA_HOST, LOAN_PATH):
            if self.loan_detail_http != 200:
                return httpx.Response(self.loan_detail_http)
            if request.headers.get("authorization") != f"Bearer {LOAN_TOKEN}":
                return httpx.Response(401)
            return httpx.Response(200, json=self.loan_detail)
        if (host, path.rsplit("/", 1)[0] + "/") == (BPCE_RS_HOST, HOME_LOAN_PATH):
            if request.headers.get("authorization") != f"Bearer {HOME_TOKEN}":
                return httpx.Response(401)
            return httpx.Response(200, json=self.home_detail)
        per = self.handle_per(request, ACCESS_TOKEN)
        if per is not None:
            return per
        authorization = request.headers.get("authorization")
        if host == DATA_HOST and authorization != f"Bearer {ACCESS_TOKEN}":
            return httpx.Response(401)
        if (host, path) == (DATA_HOST, fetcher.SYNTHESIS_PATH):
            return httpx.Response(200, json=self.synthesis)
        if (host, path) == (DATA_HOST, fetcher.TRANSACTIONS_PATH):
            account = request.url.params["accountIds"]
            if account in self.failing_transactions:
                return httpx.Response(500)
            row = {
                "id": f"tx-{account}", "accountId": int(account), "date": "2026-11-02T00:00:00",
                "dueDate": "2099-01-01" if account == "9001" else "2026-11-02",
                "amount": -12.5, "currency": "EUR", "text": "SYNTHETIC OPERATION",
                "parsedData": {"accountingDate": "2026-11-02", "clientIBAN": f"FR76SYNTHETIC{account}"},
            }
            return httpx.Response(200, json={"data": [row], "meta": {}})
        raise AssertionError(f"unexpected request to {host}{path}")

    def token_forms(self) -> list[dict]:
        return [
            dict(urllib.parse.parse_qsl(r.content.decode()))
            for r in self.requests
            if (r.url.host, r.url.path) in ((AS_HOST, "/api/oauth/v2/token"), (BPCE_AS_HOST, "/api/oauth/v2/token"))
        ]

    def authorize_requests(self, client_id: str) -> list[httpx.Request]:
        return [
            r for r in self.requests
            if r.url.path == "/api/oauth/v2/authorize" and r.url.params.get("client_id") == client_id
        ]


class AccountsTest(unittest.TestCase):
    def setUp(self):
        for p in (
            patch.dict(os.environ, {"APP_SIDECAR_API_KEY": TEST_KEY}),
            patch.object(main, "_token_cache", replay.TokenCache(main.SITE)),
        ):
            p.start()
            self.addCleanup(p.stop)
        self.bank = FakeBank()

    def post(self, path):
        with patch.object(main, "_TRANSPORT", self.bank.transport()), TestClient(main.app) as client:
            return client.post(path, json={"sessionState": SESSION_STATE}, headers=HEADERS)

    def test_token_check_replays_the_main_chain_on_the_banque_populaire_hosts(self):
        response = self.post("/token-check")

        self.assertEqual(response.json(), {"ok": True, "expiresIn": 267})
        hosts = {r.url.host for r in self.bank.requests}
        self.assertEqual(hosts, {AS_HOST, LOGIN_HOST})
        self.assertEqual([f["client_id"] for f in self.bank.token_forms()], [CLIENT_ID])

    def test_the_supported_products_are_imported_with_their_transactions_and_no_card(self):
        response = self.post("/accounts")

        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(
            [(a["externalId"], a["kind"]) for a in body["accounts"]],
            [("1001", "CURRENT_ACCOUNT"), ("1002", "LIVRET_A"),
             ("1003", "LIVRET_JEUNE"), ("1004", "LDDS"),
             ("1005", "SAVINGS"), ("1006", "PEL"),
             ("contract:TD1", "TERM_DEPOSIT"), ("contract:CS1", "COOPERATIVE_SHARES"),
             ("contract:PRET1", "LOAN"), ("contract:IMMO1", "LOAN"),
             ("contract:" + PER_BANK_ID, "PER")],
        )
        jeune = body["accounts"][2]
        self.assertEqual(jeune["name"], "Livret Pour La Jeunesse")
        self.assertEqual(jeune["balance"], "800.0")
        self.assertEqual(jeune["ceiling"], "1600.0")
        self.assertEqual(jeune["iban"], "FR76SYNTHETIC1003")
        self.assertEqual([t["externalId"] for t in jeune["transactions"]], ["tx-1003"])
        # Provisional kinds (`SAVINGS`, `PEL`) never read their IBAN from transactions so
        # they cannot join the loan debit-account match.
        csl = body["accounts"][4]
        self.assertEqual(csl["name"], "SYNTHETIC Compte Sur Livret")
        self.assertEqual(csl["balance"], "250.0")
        self.assertIsNone(csl["iban"])
        self.assertFalse(csl["ibanAmbiguous"])
        self.assertEqual([t["externalId"] for t in csl["transactions"]], ["tx-1005"])
        pel = body["accounts"][5]
        self.assertEqual(pel["name"], "SYNTHETIC Plan Epargne Logement")
        self.assertEqual(pel["balance"], "15000.0")
        self.assertEqual(pel["ceiling"], "61200.0")
        self.assertIsNone(pel["iban"])
        self.assertFalse(pel["ibanAmbiguous"])
        self.assertEqual([t["externalId"] for t in pel["transactions"]], ["tx-1006"])

        td = body["accounts"][6]
        self.assertEqual(td["name"], "SYNTHETIC Tip Top'P 3 Ans")
        self.assertEqual(td["kind"], "TERM_DEPOSIT")
        self.assertEqual(td["balance"], "1234.56")
        self.assertIsNone(td["iban"])
        self.assertFalse(td["ibanAmbiguous"])
        self.assertEqual(td["transactions"], [])

        cs = body["accounts"][7]
        self.assertEqual(cs["name"], "SYNTHETIC Part Sociale BP")
        self.assertEqual(cs["kind"], "COOPERATIVE_SHARES")
        self.assertEqual(cs["balance"], "250.0")
        self.assertIsNone(cs["iban"])
        self.assertFalse(cs["ibanAmbiguous"])
        self.assertEqual(cs["transactions"], [])

        # The current account's card is not imported for this bank, and not listed either:
        # it is not a contract.
        self.assertNotIn("CARD", {a["kind"] for a in body["accounts"]})
        self.assertEqual(body["unavailable"], [])

    def test_a_provisional_account_whose_transactions_fail_is_imported_without_iban(self):
        # Compte sur Livret and PEL are provisional here: a failed feed imports them from
        # their synthesis entry, with no transaction and no IBAN, rather than dropping them.
        self.bank.failing_transactions = {"1005", "1006"}
        with self.assertLogs("bpce", "WARNING"):
            response = self.post("/accounts")

        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        imported = {a["externalId"]: a for a in body["accounts"]}
        for external_id, kind in (("1005", "SAVINGS"), ("1006", "PEL")):
            with self.subTest(kind=kind):
                account = imported[external_id]
                self.assertEqual(account["kind"], kind)
                self.assertEqual(account["transactions"], [])
                self.assertIsNone(account["iban"])
                self.assertFalse(account["ibanAmbiguous"])
        self.assertEqual(imported["1004"]["iban"], "FR76SYNTHETIC1004")  # the others are untouched
        self.assertEqual(body["unavailable"], [])

    def test_only_the_revolving_credit_is_unsupported_and_never_read(self):
        body = self.post("/accounts").json()

        self.assertEqual(body["unsupported"], [
            {"externalId": "contract:FACELIA1", "familyCode": "2", "subFamilyCode": "11"},
        ])
        paths = [r.url.path for r in self.bank.requests if r.url.host == DATA_HOST]
        self.assertEqual(paths.count(fetcher.SYNTHESIS_PATH), 1)
        self.assertFalse(any("FACELIA1" in r.url.path for r in self.bank.requests))
        self.assertEqual(
            sorted(r.url.params["accountIds"] for r in self.bank.requests if r.url.path == fetcher.TRANSACTIONS_PATH),
            ["1001", "1002", "1003", "1004", "1005", "1006"],  # never term deposit, shares, loans, PER or the card
        )
        # The main token, then the personal-loan, home-loan and PER chains'.
        self.assertEqual(
            [f["client_id"] for f in self.bank.token_forms()],
            [CLIENT_ID, LOAN_CLIENT_ID, HOME_CLIENT_ID, PER_CLIENT_ID],
        )
        self.assertTrue(all(r.url.host.endswith((".banquepopulaire.fr", ".bpce.fr")) for r in self.bank.requests))

    def test_a_personal_loan_is_read_with_its_own_client_scope_and_redirect(self):
        body = self.post("/accounts").json()

        loan = {a["externalId"]: a for a in body["accounts"]}["contract:PRET1"]
        self.assertEqual((loan["kind"], loan["balance"]), ("LOAN", "-1000.0"))
        self.assertEqual((loan["loan"]["borrowedAmount"], loan["loan"]["durationMonths"]), ("12000", 48))
        self.assertEqual(loan["loan"]["interestRate"], "0.03")
        [authorize] = self.bank.authorize_requests(LOAN_CLIENT_ID)
        self.assertEqual(authorize.url.host, AS_HOST)
        params = authorize.url.params
        self.assertEqual((params["scope"], params["redirect_uri"]), (LOAN_SCOPE, LOAN_REDIRECT))
        self.assertNotIn("claims", params)
        self.assertEqual(params["login_hint"], "SECRET-LOGIN-HINT")  # the session's other parameters are kept
        [request] = [r for r in self.bank.requests if r.url.path == LOAN_PATH + "PRET1"]
        self.assertEqual((request.url.host, request.headers["authorization"]), (DATA_HOST, f"Bearer {LOAN_TOKEN}"))
        self.assertNotIn(LOAN_TOKEN, json.dumps(body))

    def test_a_home_loan_is_read_from_the_bpce_host_with_its_own_client(self):
        body = self.post("/accounts").json()

        loan = {a["externalId"]: a for a in body["accounts"]}["contract:IMMO1"]
        self.assertEqual((loan["kind"], loan["balance"]), ("LOAN", "-90000.0"))
        self.assertEqual((loan["loan"]["borrowedAmount"], loan["loan"]["durationMonths"]), ("200000.0", 240))
        [authorize] = self.bank.authorize_requests(HOME_CLIENT_ID)
        self.assertEqual(authorize.url.host, BPCE_AS_HOST)
        params = authorize.url.params
        self.assertEqual((params["scope"], params["redirect_uri"]), ("", HOME_REDIRECT))
        self.assertNotIn("claims", params)
        [request] = [r for r in self.bank.requests if r.url.path == HOME_LOAN_PATH + "00000-IMMO1"]
        self.assertEqual((request.url.host, request.headers["authorization"]), (BPCE_RS_HOST, f"Bearer {HOME_TOKEN}"))
        self.assertNotIn(HOME_TOKEN, json.dumps(body))

    def test_a_refused_loan_client_leaves_the_session_alive_and_the_loan_imported_without_detail(self):
        for name, clients in (("personal", {LOAN_CLIENT_ID}), ("home", {HOME_CLIENT_ID})):
            with self.subTest(name), patch.object(main, "_token_cache", replay.TokenCache(main.SITE)):
                self.bank = FakeBank()
                self.bank.refused_clients = clients
                with self.assertLogs("bpce", "WARNING") as logs:
                    response = self.post("/accounts")

                self.assertEqual(response.status_code, 200, response.text)
                body = response.json()
                by_id = {a["externalId"]: a for a in body["accounts"]}
                refused, other = ("contract:PRET1", "contract:IMMO1") if name == "personal" else ("contract:IMMO1", "contract:PRET1")
                self.assertIsNone(by_id[refused]["loan"])
                self.assertEqual(by_id[refused]["kind"], "LOAN")
                self.assertIsNotNone(by_id[other]["loan"])
                self.assertEqual(len(by_id), 11)
                self.assertEqual(body["unavailable"], [])
                self.assertTrue(any("loan imported without detail: product=" in line for line in logs.output))
                # The session was not replayed again on account of the loan client: one refusal only.
                self.assertEqual(len(self.bank.authorize_requests(next(iter(clients)))), 1)

    def test_a_401_on_the_personal_loan_detail_leaves_the_session_alive(self):
        self.bank.loan_detail_http = 401
        with self.assertLogs("bpce", "WARNING") as logs:
            response = self.post("/accounts")

        self.assertEqual(response.status_code, 200, response.text)
        by_id = {a["externalId"]: a for a in response.json()["accounts"]}
        self.assertIsNone(by_id["contract:PRET1"]["loan"])
        self.assertEqual(by_id["contract:PRET1"]["balance"], "-1000.0")
        self.assertIsNotNone(by_id["contract:IMMO1"]["loan"])
        self.assertEqual(len(by_id), 11)
        self.assertTrue(any("loan detail unavailable: product=PERSONAL code=UPSTREAM_UNAVAILABLE" in line for line in logs.output))

    def test_an_unreadable_loan_detail_is_imported_without_detail_and_logs_the_field_only(self):
        self.bank.loan_detail["personalLoanPrdSynthesisView"]["offer"]["signatureDate"] = "secret-date-value"
        self.bank.loan_detail["personalLoanPrdSynthesisView"]["offer"]["proposedAmount"]["value"] = 4242.42
        with self.assertLogs("bpce", "WARNING") as logs:
            response = self.post("/accounts")

        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        by_id = {a["externalId"]: a for a in body["accounts"]}
        self.assertIsNone(by_id["contract:PRET1"]["loan"])
        self.assertEqual(by_id["contract:PRET1"]["balance"], "-1000.0")
        self.assertEqual(body["unavailable"], [])
        rendered = "\n".join(logs.output)
        self.assertIn("loan detail unreadable: product=PERSONAL code=INVALID_LOAN_DETAIL rejected=loan.offer.signatureDate", rendered)
        self.assertIn("loan imported without detail: product=PERSONAL", rendered)
        for value in ("secret-date-value", "4242", "PRET1", "FR7600000000000000000000001", LOAN_TOKEN, ACCESS_TOKEN):
            self.assertNotIn(value, rendered)

    def test_a_perib_per_is_read_from_the_insurer_with_the_per_token_only(self):
        body = self.post("/accounts").json()
        per = {a["externalId"]: a for a in body["accounts"]}["contract:" + PER_BANK_ID]
        self.assertEqual((per["kind"], per["balance"], per["per"]["euroFundBalance"], per["per"]["valuationDate"]),
                         ("PER", "1700", "1000", "2026-10-01"))
        self.assertEqual((per["per"]["positions"][0]["isin"], per["per"]["positions"][0]["quantity"], per["transactions"]),
                         ("XX0000000001", "10", []))
        insurer_calls = [(r.url.host, r.method) for r in self.bank.requests
                         if r.url.host == INSURER_HOST or "/clientToken/" in r.url.path
                         or r.url.path.endswith("/prepareRouting")]
        self.assertEqual(insurer_calls, [(DATA_HOST, "POST"), (DATA_HOST, "GET"), (INSURER_HOST, "GET")])
        self.assertTrue(all("cookie" not in r.headers for r in self.bank.requests if r.url.host == INSURER_HOST))
        per_authorize = [r for r in self.bank.requests
                         if r.url.path == "/api/oauth/v2/authorize" and r.url.params.get("client_id") == PER_CLIENT_ID]
        self.assertEqual(len(per_authorize), 1)
        self.assertEqual(per_authorize[0].url.params["redirect_uri"], f"https://{INSURER_HOST}/postMessage/index.html")
        self.assertNotIn("claims", per_authorize[0].url.params)
        self.assertNotIn(PER_ACCESS_TOKEN, json.dumps(body))
        self.assertNotIn(PER_JWT, json.dumps(body))

    def test_a_per_with_an_unknown_id_shape_is_listed_unsupported_and_never_sent(self):
        self.bank.synthesis = {"items": [*SYNTHESIS["items"],
                                          contract(None, "4", "05", "PER", balance=2500.0, contract_id="PER1")]}
        with self.assertLogs("bpce", "INFO") as logs:
            body = self.post("/accounts").json()

        self.assertIn({"externalId": "contract:PER1", "familyCode": "4", "subFamilyCode": "05"},
                      body["unsupported"])
        self.assertNotIn("contract:PER1", {a["externalId"] for a in body["accounts"]})
        self.assertFalse(any("PER1" in r.url.path for r in self.bank.requests))
        # Masked shape only: no letter and no digit of the id reaches the log.
        self.assertTrue(any("insurer request refused: idShape=" in line for line in logs.output))
        self.assertFalse(any("PER1" in line for line in logs.output))

    def test_a_producer_id_that_is_not_pinned_leaves_only_the_per_unavailable(self):
        self.bank.routing_producer = "PERIX876543"
        with self.assertLogs("bpce", "WARNING") as logs:
            body = self.post("/accounts").json()

        self.assertNotIn("contract:" + PER_BANK_ID, {a["externalId"] for a in body["accounts"]})
        self.assertIn("1001", {a["externalId"] for a in body["accounts"]})
        self.assertEqual([u["kind"] for u in body["unavailable"]], ["PER"])
        self.assertTrue(any("reason=producer-id-format shape=" in line for line in logs.output))
        self.assertFalse(any("PERIX" in line or "876543" in line for line in logs.output))
        self.assertFalse(any(r.url.host == INSURER_HOST or "/clientToken/" in r.url.path
                             for r in self.bank.requests))

    def test_an_unreadable_per_contract_is_demoted_to_unsupported_and_never_fails_sync(self):
        for name, bad_contract in make_bad_per_contracts(contract, PER_BANK_ID).items():
            with self.subTest(name):
                self.bank.synthesis = {
                    "items": [
                        *[i for i in SYNTHESIS["items"]
                          if i["identity"]["productFamilyPFM"]["code"] != "4"
                          or (i["identity"].get("subProductFamilyPFM") or {}).get("code") != "05"],
                        bad_contract,
                    ]
                }
                response = self.post("/accounts")
                self.assertEqual(response.status_code, 200)
                body = response.json()
                self.assertNotIn("contract:" + PER_BANK_ID, {a["externalId"] for a in body["accounts"]})
                self.assertEqual(len(body["accounts"]), 10)
                self.assertTrue(any(u.get("familyCode") == "4" and u.get("subFamilyCode") == "05" for u in body["unsupported"]))


    def test_per_chain_401_evicts_token_and_retries_once(self):
        self.bank.client_token_401_count = 1
        body = self.post("/accounts").json()
        self.assertIn("contract:" + PER_BANK_ID, {a["externalId"] for a in body["accounts"]})
        per_forms = [f for f in self.bank.token_forms() if f.get("client_id") == PER_CLIENT_ID]
        self.assertEqual(len(per_forms), 2)

    def test_per_chain_two_401s_leaves_per_unavailable_and_imports_other_accounts(self):
        self.bank.client_token_401_count = 2
        with self.assertLogs("bpce", "WARNING"):
            body = self.post("/accounts").json()
        self.assertNotIn("contract:" + PER_BANK_ID, {a["externalId"] for a in body["accounts"]})
        self.assertEqual([u["kind"] for u in body["unavailable"]], ["PER"])
        self.assertEqual(len(body["accounts"]), 10)

    def test_nothing_secret_is_returned(self):
        text = self.post("/accounts").text
        for secret in (ACCESS_TOKEN, "BP-COOKIE-SECRET", "SECRET-LOGIN-HINT", "synthetic-code"):
            self.assertNotIn(secret, text)

    def test_a_refused_session_is_session_expired(self):
        self.bank.authn_status = "AUTHENTICATION_FAILED"
        response = self.post("/accounts")
        self.assertEqual((response.status_code, response.json()), (401, {"detail": "SESSION_EXPIRED"}))

    def test_a_chain_step_off_banque_populaire_is_refused_before_it_is_sent(self):
        for action in (
            "https://www.icgauth.caisse-epargne.fr/dacsrest/api/v1u0/saml2/sso",
            "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/authorize",
            "https://www.bpce.fr/api/oauth/v2/authorize",
            "https://evil.example.com/sso",
        ):
            with self.subTest(action=action):
                self.bank = FakeBank()
                self.bank.authorize_action = action
                response = self.post("/token-check")
                self.assertEqual((response.status_code, response.json()), (502, {"detail": "UPSTREAM_ERROR"}))
                self.assertEqual({r.url.host for r in self.bank.requests}, {AS_HOST})


class SiteTest(unittest.TestCase):
    def test_the_hosts_observed_live(self):
        site = main.SITE
        self.assertEqual(site.login_url, "https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber")
        self.assertEqual((site.client_host, site.client_path_prefix), ("www.banquepopulaire.fr", "/espace-client/"))
        self.assertEqual(site.authorize_url, f"https://{AS_HOST}/api/oauth/v2/authorize")
        self.assertEqual(site.token_url, f"https://{AS_HOST}/api/oauth/v2/token")
        self.assertEqual(site.data_base_url, f"https://{DATA_HOST}")
        self.assertEqual(site.cookie_banner_button, "#no_consent_btn")
        for url in (site.login_url, site.authorize_url, site.token_url, site.data_base_url):
            self.assertTrue(replay.is_allowed_url(site, url), url)

    def test_a_loan_app_per_app_and_the_provisional_kinds(self):
        site = main.SITE
        self.assertEqual(
            (site.personal_loan.name, dict(site.personal_loan.overrides), site.personal_loan.drop),
            ("personal-loan", {"client_id": LOAN_CLIENT_ID, "scope": LOAN_SCOPE, "redirect_uri": LOAN_REDIRECT}, ("claims",)),
        )
        self.assertEqual(
            (site.home_loan.chain.name, dict(site.home_loan.chain.overrides), site.home_loan.chain.drop),
            ("home-loan", {"client_id": HOME_CLIENT_ID, "scope": "", "redirect_uri": HOME_REDIRECT}, ("claims",)),
        )
        self.assertEqual(site.home_loan.authorize_url, f"https://{BPCE_AS_HOST}/api/oauth/v2/authorize")
        self.assertEqual(site.home_loan.token_url, f"https://{BPCE_AS_HOST}/api/oauth/v2/token")
        self.assertEqual(site.home_loan.base_url, f"https://{BPCE_RS_HOST}")
        self.assertIsNotNone(site.per)
        self.assertEqual(site.unsupported_families, frozenset({("3", "27")}))
        self.assertEqual(
            site.provisional_kinds, frozenset({"SAVINGS", "PEL", "TERM_DEPOSIT", "COOPERATIVE_SHARES", "PER", "LOAN"})
        )

    def test_the_allow_list_is_banque_populaire_plus_the_two_bpce_loan_hosts(self):
        self.assertEqual(main.SITE.extra_hosts, frozenset({BPCE_AS_HOST, BPCE_RS_HOST}))
        for url in (
            f"https://{BPCE_AS_HOST}/api/oauth/v2/token",
            f"https://{BPCE_RS_HOST}/bapi/loan/v1/loans/x",
        ):
            with self.subTest(url=url):
                self.assertTrue(replay.is_allowed_url(main.SITE, url))
        for url in (
            "https://www.caisse-epargne.fr/espace-client/",
            "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/token",
            "https://www.bpce.fr/",
            "https://www.as-ext-bad-ib.bpce.fr/",
            "https://evil.rs-ext-bad-me.bpce.fr/",
            "https://www.rs-ext-bad-me.bpce.fr.evil.fr/",
            "http://www.rs-ext-bad-me.bpce.fr/",
            "https://banquepopulaire.fr/",
            "https://www.banquepopulaire.fr.evil.example/",
            "https://evilbanquepopulaire.fr/",
            "http://www.banquepopulaire.fr/",
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(main.SITE, url))


if __name__ == "__main__":
    unittest.main()
