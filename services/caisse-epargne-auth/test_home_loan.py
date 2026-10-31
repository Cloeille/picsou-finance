"""Home loans (`6/13`): synthesis kind, BPCE detail parsing and the fetch path.

The detail payload is invented, with the key structure observed live on
2026-10-25 (`GET /bapi/loan/v1/loans/{entityCode}-{contractId.id}`).
"""

import copy
import unittest
import urllib.parse
from datetime import date
from decimal import Decimal
from types import SimpleNamespace
from unittest.mock import AsyncMock, call, patch

import httpx

import accounts_parser
import fetcher
import main
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
                # 200 000 at 1.2 % over 240 months: annuity 937.74, so no insurance.
                "insuranceMonthly": Decimal("0.00"),
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

    def test_a_small_excess_over_the_annuity_is_kept_as_insurance(self):
        d = detail()
        d["loan"]["financialInformation"]["nextDuedateAmount"]["value"] = 942.74

        self.assertEqual(parse_home_loan_detail(d)["insuranceMonthly"], Decimal("5.00"))

    def test_an_installment_below_the_annuity_gives_no_insurance(self):
        d = detail()
        d["loan"]["financialInformation"]["nextDuedateAmount"]["value"] = 900.0

        self.assertIsNone(parse_home_loan_detail(d)["insuranceMonthly"])

    def test_an_amount_too_large_to_quantize_is_refused(self):
        d = detail()
        d["loan"]["financialInformation"]["amountBorrowed"]["value"] = 1e30

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_duration_above_fifty_years_is_refused(self):
        # Signed early enough that 601 months stays after the subscription: only the cap refuses it.
        d = detail()
        d["loan"]["contractCharacteristic"]["subscriptionDate"] = "1990-01-01"
        d["loan"]["financialInformation"]["duration"] = 601

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_duration_of_fifty_years_is_accepted(self):
        d = detail()
        d["loan"]["contractCharacteristic"]["subscriptionDate"] = "1990-01-01"
        d["loan"]["financialInformation"]["duration"] = 600

        loan = parse_home_loan_detail(d)

        self.assertEqual(loan["durationMonths"], 600)
        # 600 installments ending 2042-06-05: the first is 599 months before.
        self.assertEqual(loan["firstDueDate"], date(1992, 7, 5))

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

    def test_an_amount_beyond_decimal_precision_is_refused(self):
        d = detail()
        d["loan"]["financialInformation"]["amountBorrowed"]["value"] = 10**30

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_duration_reaching_before_year_one_is_refused(self):
        # Within the cap, but counted back from a last due date in the year 40.
        d = detail()
        d["loan"]["financialInformation"]["lastDuedate"] = "0040-06-05"
        d["loan"]["financialInformation"]["duration"] = 600

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_duration_far_above_the_cap_is_refused(self):
        d = detail()
        d["loan"]["financialInformation"]["duration"] = 10**30

        with self.assertRaises(ParseError) as caught:
            parse_home_loan_detail(d)
        self.assertEqual(caught.exception.code, accounts_parser.INVALID_LOAN_DETAIL)

    def test_a_tiny_rate_keeps_the_zero_rate_insurance(self):
        # 200 000 / 240 rounds to 833.33, leaving 104.41 of the 937.74 payment.
        for rate in (0, 1e-24, 1e-30, 1e-56, 1e-100, 5e-324):
            with self.subTest(rate=rate):
                tiny = detail()
                tiny["loan"]["financialInformation"]["nextDuedateRate"] = rate

                self.assertEqual(
                    parse_home_loan_detail(tiny)["insuranceMonthly"],
                    Decimal("104.41"),
                )

    def test_tiny_rates_round_the_annuity_at_half_cent_boundaries(self):
        for borrowed, duration, expected in (
            (250.005, 1, "29.99"),
            (750.015, 3, "29.99"),
            (12000.23, 48, "30.00"),
            (12000.24, 48, "29.99"),
            (12000.25, 48, "29.99"),
            (12250.245, 49, "29.99"),
        ):
            for rate in (0, 1e-24, 1e-30, 1e-56, 2e-100, 5e-324):
                with self.subTest(borrowed=borrowed, duration=duration, rate=rate):
                    d = detail()
                    money = d["loan"]["financialInformation"]
                    money["amountBorrowed"]["value"] = borrowed
                    money["duration"] = duration
                    money["nextDuedateRate"] = rate
                    money["nextDuedateAmount"]["value"] = 280

                    self.assertEqual(parse_home_loan_detail(d)["insuranceMonthly"], Decimal(expected))

    def test_annuity_rounding_at_an_exact_half_cent(self):
        # At 12 % over two months, 100.50 gives an exact annuity of 10201/200 = 51.005.
        for borrowed, expected in (
            (100.49999999999999, "29.00"),
            (100.50, "28.99"),
            (100.50000000000001, "28.99"),
        ):
            with self.subTest(borrowed=borrowed):
                d = detail()
                money = d["loan"]["financialInformation"]
                money["amountBorrowed"]["value"] = borrowed
                money["duration"] = 2
                money["nextDuedateRate"] = 12
                money["nextDuedateAmount"]["value"] = 80

                self.assertEqual(parse_home_loan_detail(d)["insuranceMonthly"], Decimal(expected))

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
        self.assertEqual(
            [(u["externalId"], u["code"]) for u in result["unavailable"]],
            [(LOAN_ID, fetcher.UpstreamUnavailable.code)],
        )
        self.assertNotIn(LOAN_ID, [a["externalId"] for a in result["accounts"]])
        self.assertTrue(result["accounts"])

    async def test_a_first_due_date_before_year_one_leaves_only_the_loan_unavailable(self):
        # The date-range path, not the cap: 600 months counted back from the year 40.
        oversized = detail()
        oversized["loan"]["financialInformation"]["lastDuedate"] = "0040-06-05"
        oversized["loan"]["financialInformation"]["duration"] = 600

        def answer(request: httpx.Request) -> httpx.Response:
            if request.url.host == HOME_HOST:
                return httpx.Response(200, json=oversized)
            return self.ce.handle(request)

        result = await fetcher.fetch_import(
            SECRET_TOKEN, transport=httpx.MockTransport(answer), today=TODAY,
            loan_token=loan_token_provider([]), home_loan_token=self.home_token([]),
        )

        self.assertEqual(
            [(u["externalId"], u["code"]) for u in result["unavailable"]],
            [(LOAN_ID, accounts_parser.INVALID_LOAN_DETAIL)],
        )
        self.assertNotIn(LOAN_ID, [a["externalId"] for a in result["accounts"]])
        self.assertTrue(result["accounts"])

    async def test_a_home_loan_token_refused_as_expired_leaves_only_the_loan_unavailable(self):
        result = await self.fetch(
            loan_token=loan_token_provider([]),
            home_loan_token=self.home_token([], raises=replay.SessionExpired("refused")),
        )

        self.assertEqual(self.home_requests, [])
        self.assertEqual(
            [(u["externalId"], u["code"]) for u in result["unavailable"]],
            [(LOAN_ID, fetcher.UpstreamUnavailable.code)],
        )
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


class HomeLoanMainWiringTest(unittest.IsolatedAsyncioTestCase):
    async def run_fetch_once(self, awaits_home_token: bool):
        tokens = {
            replay.MAIN_CHAIN: "synthetic-main-token",
            replay.HOME_LOAN_CHAIN: "synthetic-home-loan-token",
        }
        seen: list = []

        async def get(_state, *, chain=replay.MAIN_CHAIN, **_kwargs):
            return SimpleNamespace(access_token=tokens[chain])

        async def fetch(access_token, **kwargs):
            self.assertEqual(access_token, "synthetic-main-token")
            if awaits_home_token:
                seen.append(await kwargs["home_loan_token"]())
            return {"accounts": []}

        with patch.object(main._token_cache, "get", AsyncMock(side_effect=get)) as cache, \
             patch.object(main.fetcher, "fetch_import", AsyncMock(side_effect=fetch)):
            await main._fetch_once("synthetic-state")
        return cache, seen

    async def test_the_home_loan_token_is_minted_on_the_bpce_chain(self):
        cache, seen = await self.run_fetch_once(awaits_home_token=True)

        self.assertEqual(seen, ["synthetic-home-loan-token"])
        self.assertIn(
            call(
                "synthetic-state",
                authorize_url=replay.HOME_LOAN_AUTHORIZE_URL,
                token_url=replay.HOME_LOAN_TOKEN_URL,
                chain=replay.HOME_LOAN_CHAIN,
                transport=main._TRANSPORT,
            ),
            cache.await_args_list,
        )
        for url in (replay.HOME_LOAN_AUTHORIZE_URL, replay.HOME_LOAN_TOKEN_URL):
            with self.subTest(url=url):
                self.assertTrue(replay.is_allowed_url(url))
                self.assertIn(urllib.parse.urlsplit(url).hostname, replay.BPCE_HOSTS)

    async def test_no_home_loan_does_not_mint_a_home_loan_token(self):
        cache, _ = await self.run_fetch_once(awaits_home_token=False)

        self.assertEqual(cache.await_count, 1)
        self.assertTrue(all(c.kwargs.get("chain") is not replay.HOME_LOAN_CHAIN for c in cache.await_args_list))


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
