"""Home loans (`6/13`): synthesis kind, BPCE detail parsing and the fetch path.

The detail payload is invented, with the key structure observed live on
2026-10-25 (`GET /bapi/loan/v1/loans/{entityCode}-{contractId.id}`).
"""

import copy
import unittest
from datetime import date
from decimal import Decimal

import httpx

import accounts_parser
import fetcher
import replay
from accounts_parser import ParseError, parse_home_loan_detail
from test_fetcher import FIXTURES, LOAN_ID, SECRET_TOKEN, TODAY, FakeCE, loan_token_provider

HOME_TOKEN = "home-loan-access-token-secret-HHHH"
HOME_HOST = "www.rs-ext-bad-me.bpce.fr"

DETAIL = {
    "loan": {
        "contractCharacteristic": {
            "subscriptionDate": "2022-05-02",
            "firstPayoutDate": "2022-05-10",
            "loanType": {"code": "7", "label": "PRET A TAUX FIXE"},
        },
        "directDebitAccount": {"bic": None, "iban": None, "label": "FAKE COMPTE"},
        "financialInformation": {
            "amountBorrowed": {"currencyCode": "EUR", "value": 200000.0},
            "duration": 240,
            "nextDuedateRate": 1.2,
            "nextDuedateAmount": {"currencyCode": "EUR", "value": 937.74},
            "lastDuedate": "2042-06-05",
            "outstandingCapital": {"currencyCode": "EUR", "value": 150000.0},
        },
    }
}


def detail() -> dict:
    return copy.deepcopy(DETAIL)


def home_synthesis() -> dict:
    synth = copy.deepcopy(FIXTURES["synthesis_products"])
    for item in synth["items"]:
        if item["identity"]["productFamilyPFM"]["code"] == "6":
            item["identity"]["subProductFamilyPFM"]["code"] = "13"
    return synth


class HomeLoanSynthesisTest(unittest.TestCase):
    def test_a_home_loan_is_a_loan_read_from_the_home_loan_app(self):
        accounts, unsupported = accounts_parser._parse_synthesis(home_synthesis())

        [loan] = [a for a in accounts if a["kind"] == "LOAN"]
        self.assertEqual((loan["externalId"], loan["loanProduct"], loan["loanEntityCode"]), (LOAN_ID, "HOME", "00000"))
        self.assertNotIn(LOAN_ID, [u["externalId"] for u in unsupported])

    def test_a_personal_loan_keeps_its_own_app(self):
        accounts, _ = accounts_parser._parse_synthesis(copy.deepcopy(FIXTURES["synthesis_products"]))

        [loan] = [a for a in accounts if a["kind"] == "LOAN"]
        self.assertEqual(loan["loanProduct"], "PERSONAL")


class HomeLoanDetailTest(unittest.TestCase):
    def test_fixture_gives_the_loan_fields(self):
        self.assertEqual(
            parse_home_loan_detail(detail()),
            {
                "borrowedAmount": Decimal("200000.0"),
                "interestRate": Decimal("0.012"),
                "monthlyPayment": Decimal("937.74"),
                "insuranceMonthly": None,
                "signatureDate": date(2022, 5, 2),
                # 240 installments ending 2042-06-05: the first is 239 months before.
                "firstDueDate": date(2022, 7, 5),
                "lastDueDate": date(2042, 6, 5),
                "durationMonths": 240,
                "linkedIban": None,
            },
        )

    def test_an_installment_above_the_annuity_carries_the_insurance(self):
        d = detail()
        d["loan"]["financialInformation"]["nextDuedateAmount"]["value"] = 977.74

        self.assertEqual(parse_home_loan_detail(d)["insuranceMonthly"], Decimal("40.00"))

    def test_the_first_due_date_keeps_to_the_end_of_a_short_month(self):
        d = detail()
        d["loan"]["financialInformation"]["lastDuedate"] = "2042-05-31"
        d["loan"]["financialInformation"]["duration"] = 4

        self.assertEqual(parse_home_loan_detail(d)["firstDueDate"], date(2042, 2, 28))

    def test_a_missing_or_odd_field_is_refused(self):
        for path, value in (
            (("financialInformation", "amountBorrowed"), None),
            (("financialInformation", "duration"), 0),
            (("financialInformation", "nextDuedateRate"), 120),
            (("financialInformation", "lastDuedate"), "05/06/2042"),
            (("contractCharacteristic", "subscriptionDate"), None),
        ):
            with self.subTest(path=path):
                d = detail()
                d["loan"][path[0]][path[1]] = value
                with self.assertRaises(ParseError) as caught:
                    parse_home_loan_detail(d)
                self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_an_amount_in_another_currency_is_refused(self):
        for field in ("amountBorrowed", "nextDuedateAmount"):
            with self.subTest(field=field):
                d = detail()
                d["loan"]["financialInformation"][field]["currencyCode"] = "CHF"
                with self.assertRaises(ParseError) as caught:
                    parse_home_loan_detail(d)
                self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_duration_reaching_before_year_one_is_refused(self):
        d = detail()
        d["loan"]["financialInformation"]["duration"] = 30000

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_first_due_date_before_the_subscription_is_refused(self):
        d = detail()
        d["loan"]["contractCharacteristic"]["subscriptionDate"] = "2023-01-02"

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)


class HomeLoanFetchTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.ce = FakeCE(synth=home_synthesis())
        self.home_requests: list[httpx.Request] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.url.host == HOME_HOST:
            self.home_requests.append(request)
            return httpx.Response(200, json=detail())
        return self.ce.handle(request)

    async def fetch(self, **kwargs) -> dict:
        return await fetcher.fetch_import(
            SECRET_TOKEN, transport=httpx.MockTransport(self.handle), today=TODAY, **kwargs
        )

    async def test_the_detail_is_read_on_the_bpce_host_with_the_home_loan_token(self):
        personal: list = []
        home: list = []

        result = await self.fetch(
            loan_token=loan_token_provider(personal),
            home_loan_token=self.home_token(home),
        )

        [request] = self.home_requests
        self.assertEqual(request.url.path, "/bapi/loan/v1/loans/00000-00000000000015")
        self.assertEqual(request.headers["Authorization"], f"Bearer {HOME_TOKEN}")
        self.assertEqual((len(home), personal, self.ce.loan_requests()), (1, [], []))
        loan = {a["externalId"]: a for a in result["accounts"]}[LOAN_ID]["loan"]
        self.assertEqual((loan["firstDueDate"], loan["linkedIban"]), ("2022-07-05", None))

    async def test_a_refused_home_loan_token_leaves_only_the_loan_unavailable(self):
        result = await self.fetch(
            loan_token=loan_token_provider([]),
            home_loan_token=self.home_token([], raises=replay.UpstreamError("refused")),
        )

        self.assertIn(LOAN_ID, [u["externalId"] for u in result["unavailable"]])
        self.assertNotIn(LOAN_ID, [a["externalId"] for a in result["accounts"]])
        self.assertTrue(result["accounts"])
        self.assertEqual(self.home_requests, [])

    async def test_a_401_on_the_home_loan_detail_leaves_only_the_loan_unavailable(self):
        def refuse(request: httpx.Request) -> httpx.Response:
            if request.url.host == HOME_HOST:
                self.home_requests.append(request)
                return httpx.Response(401)
            return self.ce.handle(request)

        result = await fetcher.fetch_import(
            SECRET_TOKEN, transport=httpx.MockTransport(refuse), today=TODAY,
            loan_token=loan_token_provider([]), home_loan_token=self.home_token([]),
        )

        self.assertEqual(len(self.home_requests), 1)
        self.assertIn(LOAN_ID, [u["externalId"] for u in result["unavailable"]])
        self.assertNotIn(LOAN_ID, [a["externalId"] for a in result["accounts"]])
        self.assertTrue(result["accounts"])

    @staticmethod
    def home_token(calls: list, *, raises: Exception | None = None):
        async def provide() -> str:
            calls.append(1)
            if raises is not None:
                raise raises
            return HOME_TOKEN

        return provide


class BpceHostsTest(unittest.TestCase):
    def test_only_the_two_home_loan_hosts_of_bpce_are_allowed(self):
        self.assertTrue(replay.is_allowed_url("https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/token"))
        self.assertTrue(replay.is_allowed_url("https://www.rs-ext-bad-me.bpce.fr/bapi/loan/v1/loans/x"))
        for url in (
            "https://www.bpce.fr/",
            "https://evil.rs-ext-bad-me.bpce.fr/",
            "http://www.rs-ext-bad-me.bpce.fr/",
            "https://www.rs-ext-bad-me.bpce.fr.evil.fr/",
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(url))


if __name__ == "__main__":
    unittest.main()
