"""The httpx login, mobile-approval polling and page import, against a fake bank."""

import unittest
from urllib.parse import parse_qs, urlparse

import httpx
from fastapi import HTTPException
from unittest.mock import patch

import bank
import fixtures

BASE = "https://voscomptesenligne.labanquepostale.fr"
CUSTOMER = "1234567890"
POSITIONS = [1, 0, 2, 3, 4, 5]
PASSWORD = "102345"


class FakeBank:
    def __init__(self, mode="direct"):
        self.mode = mode
        self.polls = 0
        self.expired = False
        self.auth_body = ""
        self.statement_html = fixtures.STATEMENT_HTML
        self.synthese_ccp_html = fixtures.SYNTHESE_CCP_HTML
        self.synthese_ep_html = fixtures.SYNTHESE_EP_HTML

    def __call__(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        query = request.url.params

        if path == "/voscomptes/canalXHTML/identif.ea":
            # A fresh client goes to the form; once the login form has set the
            # session cookie, the same URL is the post-login chain.
            if "XO1_SESSIONID" in request.headers.get("cookie", ""):
                return httpx.Response(302, headers={"location": bank.SYNTHESE_CCP})
            return httpx.Response(
                302,
                headers={
                    "location": "/wsost/OstBrokerWeb/loginform"
                    "?TAM_OP=login&ERROR_CODE=0x00000000"
                    "&URL=%2Fvoscomptes%2FcanalXHTML%2Fidentif.ea%3Forigin%3Dparticuliers"
                },
            )

        if path == "/wsost/OstBrokerWeb/loginform":
            if "ERROR_CODE" in query or "URL" in query:
                return httpx.Response(
                    200,
                    text=fixtures.LOGIN_HTML,
                    headers={"set-cookie": "XO1_SESSIONID=abc; Path=/; Secure; HttpOnly"},
                )
            if "cu" in query and "origin" in query:
                return httpx.Response(200, text=fixtures.CHALLENGE_HTML)
            if "cu" in query:
                self.polls += 1
                if self.polls < 2:
                    return httpx.Response(200, text="")
                return httpx.Response(
                    302, headers={"location": "/voscomptes/canalXHTML/identif.ea?origin=particuliers"}
                )
            return httpx.Response(200, text=fixtures.LOGIN_HTML)

        if path == "/wsost/OstBrokerWeb/auth" and request.method == "POST":
            self.auth_body = request.content.decode()
            data = parse_qs(self.auth_body)
            if data.get("username") != [CUSTOMER]:
                raise AssertionError("the identifier was not submitted")
            if data.get("password") != [PASSWORD]:
                raise AssertionError("the keypad positions were not submitted")
            if self.mode == "two_factor":
                return httpx.Response(
                    302,
                    headers={
                        "location": "/wsost/OstBrokerWeb/loginform"
                        "?TAM_OP=login&phase=3b&cu=attenteAF&origin=particuliers"
                    },
                )
            return httpx.Response(
                302, headers={"location": "/voscomptes/canalXHTML/identif.ea?origin=particuliers"}
            )

        if path == bank.SYNTHESE_CCP:
            if self.expired:
                return httpx.Response(302, headers={"location": "/wsost/OstBrokerWeb/loginform"})
            return httpx.Response(200, text=self.synthese_ccp_html)

        if path == bank.SYNTHESE_EP:
            return httpx.Response(200, text=self.synthese_ep_html)

        if path in (bank.RELEVE_CCP, bank.RELEVE_CNE, bank.FRAME_CNE):
            return httpx.Response(200, text=self.statement_html)

        return httpx.Response(404)


class BankTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.fake = FakeBank()
        self.client = bank.HttpxBankClient(transport=httpx.MockTransport(self.fake))

    async def login(self):
        initiated = await self.client.initiate(CUSTOMER)
        await self.client.keypad(initiated["processId"], POSITIONS)
        return await self.client.complete(initiated["processId"])

    async def test_initiate_returns_the_shuffled_keypad(self):
        initiated = await self.client.initiate(CUSTOMER)

        self.assertEqual(initiated["keypad"]["keys"], fixtures.KEYS)
        self.assertEqual(initiated["keypad"]["columns"], 5)
        self.assertEqual(initiated["expiresInSeconds"], bank.PENDING_TTL_SECONDS)

    async def test_a_direct_login_returns_the_cookie_jar(self):
        state = await self.login()

        self.assertIn("XO1_SESSIONID", state)
        self.assertEqual(self.fake.polls, 0)

    async def test_the_login_sends_positions_not_digits(self):
        await self.login()

        self.assertIn(f"password={PASSWORD}", self.fake.auth_body)
        self.assertIn(f"username={CUSTOMER}", self.fake.auth_body)

    async def test_a_new_device_polls_until_the_mobile_approval(self):
        self.fake.mode = "two_factor"
        with patch.object(bank, "POLL_INTERVAL_SECONDS", 0.01):
            state = await self.login()

        self.assertIn("XO1_SESSIONID", state)
        self.assertGreaterEqual(self.fake.polls, 2)

    async def test_accounts_reads_the_three_accounts_and_their_operations(self):
        state = await self.login()

        snapshot = await self.client.accounts(state)

        self.assertEqual(
            [(a["kind"], a["balance"]) for a in snapshot["accounts"]],
            [
                ("CURRENT_ACCOUNT", "1234.56"),
                ("LIVRET_A", "10000.00"),
                ("LEP", "5000.00"),
            ],
        )
        self.assertEqual(len(snapshot["accounts"][0]["transactions"]), 2)
        self.assertTrue(all(a["snapshotComplete"] for a in snapshot["accounts"]))
        self.assertTrue(all(a["currency"] == "EUR" for a in snapshot["accounts"]))
        self.assertTrue(
            all(t["currency"] == "EUR" for a in snapshot["accounts"] for t in a["transactions"])
        )
        self.assertEqual(len({a["externalId"] for a in snapshot["accounts"]}), 3)
        self.assertTrue(all(len(a["externalId"]) == 40 for a in snapshot["accounts"]))

    async def test_check_distinguishes_a_live_session_from_an_expired_one(self):
        state = await self.login()
        self.assertEqual(await self.client.check(state), bank.CHECK_EXPIRES_IN_SECONDS)

        self.fake.expired = True
        with self.assertRaises(HTTPException) as raised:
            await self.client.check(state)
        self.assertEqual(raised.exception.detail, "SESSION_EXPIRED")

    async def test_a_balance_page_without_a_table_has_no_transactions(self):
        self.fake.expired = False
        self.fake.statement_html = "<html><body>no operations</body></html>"
        state = await self.login()

        snapshot = await self.client.accounts(state)

        self.assertTrue(all(a["transactions"] == [] for a in snapshot["accounts"]))

    async def test_a_cardless_ep_page_never_reads_as_a_partial_snapshot(self):
        self.fake.synthese_ep_html = (
            "<html><body><p>Service momentanement indisponible</p></body></html>"
        )
        state = await self.login()

        with self.assertRaises(HTTPException) as raised:
            await self.client.accounts(state)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    async def test_a_cardless_ccp_page_never_reads_as_a_partial_snapshot(self):
        self.fake.synthese_ccp_html = (
            "<html><body><p>Service momentanement indisponible</p></body></html>"
        )
        state = await self.login()

        with self.assertRaises(HTTPException) as raised:
            await self.client.accounts(state)
        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "UPSTREAM_FORMAT_CHANGED")


if __name__ == "__main__":
    unittest.main()
