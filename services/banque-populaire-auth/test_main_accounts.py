"""POST /token-check and POST /accounts for Banque Populaire, against a synthetic bank.

The bank is an `httpx.MockTransport` playing the hosts observed live (2026-11-05):
the replay chain on `as-ext-bad-ib` and icgauth, the data API on `rs-ext-bad-ib`.
Products are those seen on a real account (current account with an immediate-debit
card, which this site does not import, Livret A and Livret Jeune both `3/01`, a revolving credit `2/11` with a null
balance) plus the loan and PER families this site does not read. Every value is
synthetic.
"""

import json
import os
import unittest
import urllib.parse
from unittest.mock import patch

import httpx
from bpce import fetcher, replay
from fastapi.testclient import TestClient

import main

TEST_KEY = "banque-populaire-sidecar-test-key"
HEADERS = {"X-Picsou-Sidecar-Key": TEST_KEY}
AS_HOST = "www.as-ext-bad-ib.banquepopulaire.fr"
LOGIN_HOST = "www.icgauth.banquepopulaire.fr"
DATA_HOST = "www.rs-ext-bad-ib.banquepopulaire.fr"
ACCESS_TOKEN = "synthetic-access-token"
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
    contract(None, "6", "12", "Pret Personnel", balance=-1000.0, contract_id="PRET1"),
    contract(None, "6", "13", "Credit Immobilier", balance=-90000.0, contract_id="IMMO1"),
    contract(None, "4", "05", "PER", balance=2500.0, contract_id="PER1"),
    contract(None, "2", "11", "Credit Renouvelable Facelia", balance=None, contract_id="FACELIA1"),
]}


class FakeBank:
    """The four-step chain and the two data paths; records every request."""

    def __init__(self):
        self.requests: list[httpx.Request] = []
        self.authorize_action = f"https://{LOGIN_HOST}/dacsrest/api/v1u0/saml2/sso"
        self.authn_status = "AUTHENTICATION_SUCCESS"

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        host, path = request.url.host, request.url.path
        if (host, path) == (AS_HOST, "/api/oauth/v2/authorize"):
            return httpx.Response(200, json={"action": self.authorize_action, "parameters": {"SAMLRequest": "S"}})
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
            return httpx.Response(200, json={"access_token": ACCESS_TOKEN, "token_type": "Bearer", "expires_in": 267})
        if host == DATA_HOST and request.headers.get("authorization") != f"Bearer {ACCESS_TOKEN}":
            return httpx.Response(401)
        if (host, path) == (DATA_HOST, fetcher.SYNTHESIS_PATH):
            return httpx.Response(200, json=SYNTHESIS)
        if (host, path) == (DATA_HOST, fetcher.TRANSACTIONS_PATH):
            account = request.url.params["accountIds"]
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
            if (r.url.host, r.url.path) == (AS_HOST, "/api/oauth/v2/token")
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
             ("1003", "LIVRET_JEUNE"), ("1004", "LDDS")],
        )
        jeune = body["accounts"][2]
        self.assertEqual(jeune["name"], "Livret Pour La Jeunesse")
        self.assertEqual(jeune["balance"], "800.0")
        self.assertEqual(jeune["ceiling"], "1600.0")
        self.assertEqual(jeune["iban"], "FR76SYNTHETIC1003")
        self.assertEqual([t["externalId"] for t in jeune["transactions"]], ["tx-1003"])
        # The current account's card is not imported for this bank, and not listed either:
        # it is not a contract.
        self.assertNotIn("CARD", {a["kind"] for a in body["accounts"]})
        self.assertEqual(body["unavailable"], [])

    def test_loans_per_and_revolving_credit_are_unsupported_and_never_read(self):
        body = self.post("/accounts").json()

        self.assertEqual(body["unsupported"], [
            {"externalId": "contract:PRET1", "familyCode": "6"},
            {"externalId": "contract:IMMO1", "familyCode": "6"},
            {"externalId": "contract:PER1", "familyCode": "4"},
            {"externalId": "contract:FACELIA1", "familyCode": "2"},
        ])
        paths = [r.url.path for r in self.bank.requests if r.url.host == DATA_HOST]
        self.assertEqual(paths.count(fetcher.SYNTHESIS_PATH), 1)
        self.assertEqual(
            sorted(r.url.params["accountIds"] for r in self.bank.requests if r.url.path == fetcher.TRANSACTIONS_PATH),
            ["1001", "1002", "1003", "1004"],  # never the card's operations
        )
        # One token, the main one: no loan, home-loan or PER chain is ever minted.
        self.assertEqual([f["client_id"] for f in self.bank.token_forms()], [CLIENT_ID])
        self.assertTrue(all(r.url.host.endswith(".banquepopulaire.fr") for r in self.bank.requests))

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
            "https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/authorize",
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

    def test_no_loan_or_per_app(self):
        self.assertIsNone(main.SITE.personal_loan)
        self.assertIsNone(main.SITE.home_loan)
        self.assertIsNone(main.SITE.per)
        self.assertEqual(main.SITE.unsupported_families, frozenset({("6", "12"), ("6", "13"), ("4", "05")}))

    def test_the_allow_list_is_banque_populaire_only(self):
        self.assertEqual(main.SITE.extra_hosts, frozenset())
        for url in (
            "https://www.caisse-epargne.fr/espace-client/",
            "https://www.as-ext-bad-ce.caisse-epargne.fr/api/oauth/v2/token",
            "https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/token",
            "https://www.rs-ext-bad-me.bpce.fr/bapi/loan/v1/loans/x",
            "https://banquepopulaire.fr/",
            "https://www.banquepopulaire.fr.evil.example/",
            "https://evilbanquepopulaire.fr/",
            "http://www.banquepopulaire.fr/",
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(main.SITE, url))


if __name__ == "__main__":
    unittest.main()
