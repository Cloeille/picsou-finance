"""Offline tests for the Caisse d'Epargne accounts fetcher.

The bank (OAuth chain + data host) is played by `httpx.MockTransport`; the
payloads come from `fixtures_synthetic.json` (invented values). No network, no
real credentials, no bank data.
"""

import copy
import io
import json
import logging
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Callable
from unittest.mock import patch

import httpx

import fetcher
import replay
from test_replay import SECRET_TOKEN, FakeBank

FIXTURES = json.loads(Path(__file__).with_name("fixtures_synthetic.json").read_text(encoding="utf-8"))
TODAY = date(2026, 10, 7)
DATA_HOST = "www.rs-ext-bad-ce.caisse-epargne.fr"
SYNTHESIS_PATH = "/bapi/contract/v2/augmentedSynthesisViews"
TX_PATH = "/pfm/user/v1.1/transactions"
IBAN_A = "FR7600000000000000000000001"
LABEL_MARKER = "FAKE ACHAT 1"  # a transaction label in the fixtures
BALANCE_MARKER = "1234.56"  # the current account balance in the fixtures
PER_ID = "contract:00000     FAKE00000014"  # synthesis_products
LOAN_ID = "contract:00000000000015"
LOAN_PATH = "/bapi/personalLoanPrd/v1/personalLoanPrdSynthesisView/"
LOAN_TOKEN = "loan-access-token-secret-LLLL"
ALL_IDS = {"1001", "1002", "1003", PER_ID, LOAN_ID, "2001"}  # every account of products_ce()
DEFAULT_IDS = {"1001", "1002", "2001"}  # what FakeCE() imports: no 1003, PER or LOAN


def synthesis() -> dict:
    return copy.deepcopy(FIXTURES["synthesis"])


def default_pages() -> dict:
    tx = FIXTURES["transactions"]
    return copy.deepcopy(
        {
            "1001": [tx["courant_page1"], tx["courant_page2"]],
            "1002": [tx["livret_a"]],
            "2001": [tx["carte_active"]],
        }
    )


class FakeCE:
    """MockTransport handler: the OAuth chain (FakeBank) plus the data host."""

    def __init__(
        self,
        *,
        pages: dict | None = None,
        synth: dict | None = None,
        synthesis_http: int = 200,
        synthesis_body: bytes | None = None,
        tx_http: int = 200,
        tx_body: bytes | None = None,
        tx_override: Callable[[httpx.Request], httpx.Response | None] | None = None,
        loan_http: int = 200,
        loan_detail: dict | None = None,
        **bank_kwargs,
    ):
        self.bank = FakeBank(**bank_kwargs)
        self.loan_http = loan_http
        self.loan_detail = copy.deepcopy(FIXTURES["loan_detail"]) if loan_detail is None else loan_detail
        self.pages = default_pages() if pages is None else pages
        self.synth = synthesis() if synth is None else synth
        self.synthesis_http = synthesis_http
        self.synthesis_body = synthesis_body
        self.tx_http = tx_http
        self.tx_body = tx_body
        self.tx_override = tx_override
        self.data_requests: list[httpx.Request] = []

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handle)

    def tx_requests(self) -> list[httpx.Request]:
        return [r for r in self.data_requests if r.url.path == TX_PATH]

    def handle(self, request: httpx.Request) -> httpx.Response:
        if request.url.host != DATA_HOST:
            return self.bank.handle(request)
        self.data_requests.append(request)
        if request.url.path == SYNTHESIS_PATH:
            if self.synthesis_body is not None:
                return httpx.Response(self.synthesis_http, content=self.synthesis_body)
            return httpx.Response(self.synthesis_http, json=self.synth)
        if request.url.path == TX_PATH:
            if self.tx_override is not None:
                response = self.tx_override(request)
                if response is not None:
                    return response
            if self.tx_body is not None:
                return httpx.Response(self.tx_http, content=self.tx_body)
            if self.tx_http != 200:
                return httpx.Response(self.tx_http, content=b"")
            return httpx.Response(200, json=self._page(request))
        if request.url.path.startswith(LOAN_PATH):
            if self.loan_http != 200:
                return httpx.Response(self.loan_http, content=b"")
            return httpx.Response(200, json=self.loan_detail)
        return httpx.Response(404)

    def loan_requests(self) -> list[httpx.Request]:
        return [r for r in self.data_requests if r.url.path.startswith(LOAN_PATH)]

    def _page(self, request: httpx.Request) -> dict:
        account = request.url.params.get("accountIds")
        token = request.url.params.get("pageToken")
        pages = self.pages.get(account)
        if pages is None:
            return {"data": [], "meta": {}, "included": {}}
        if token is None:
            return copy.deepcopy(pages[0])
        for i in range(1, len(pages)):
            if pages[i - 1]["meta"].get("nextPageToken") == token:
                return copy.deepcopy(pages[i])
        raise AssertionError("unknown page token requested")


async def run_fetch(ce: FakeCE, **kwargs) -> dict:
    return await fetcher.fetch_import(
        SECRET_TOKEN, transport=ce.transport(), today=TODAY, **kwargs
    )


class HappyPathTest(unittest.IsolatedAsyncioTestCase):
    async def test_fixture_yields_the_contract_shape(self):
        ce = FakeCE()

        result = await run_fetch(ce)

        self.assertEqual(set(result), {"accounts", "unsupported", "unavailable"})
        self.assertEqual(result["unsupported"], [])
        by_id = {a["externalId"]: a for a in result["accounts"]}
        self.assertEqual(set(by_id), {"1001", "1002", "2001"})  # card 2002 is XXX: not imported
        self.assertEqual(
            [by_id[k]["kind"] for k in ("1001", "1002", "2001")],
            ["CURRENT_ACCOUNT", "LIVRET_A", "CARD"],
        )
        keys = {
            "externalId", "kind", "name", "balance", "currency", "iban", "ibanAmbiguous",
            "authorizedOverdraft", "ceiling", "remainingDepositCapacity", "fillingRatio",
            "cardNature", "parentExternalId", "nextDueDate", "transactions", "snapshotComplete",
            "loan",
        }
        for account in result["accounts"]:
            self.assertEqual(set(account), keys)
            self.assertIs(account["snapshotComplete"], True)
            self.assertEqual(account["currency"], "EUR")

        current, livret, card = by_id["1001"], by_id["1002"], by_id["2001"]
        self.assertEqual(current["balance"], BALANCE_MARKER)
        self.assertIsNone(current["cardNature"])
        self.assertIsNone(current["parentExternalId"])
        self.assertEqual(livret["balance"], "5000.0")
        self.assertEqual(card["cardNature"], "DEFERRED_DEBIT")
        self.assertEqual(card["parentExternalId"], "1001")
        self.assertEqual(card["balance"], "0")  # both fixture rows are due before TODAY
        self.assertIsNone(card["nextDueDate"])
        self.assertIsNone(current["nextDueDate"])
        self.assertEqual([t["typeCode"] for t in card["transactions"]], ["11", "11"])

    async def test_transactions_are_mapped_without_the_account_id(self):
        result = await run_fetch(FakeCE())

        current = next(a for a in result["accounts"] if a["externalId"] == "1001")
        self.assertEqual([t["externalId"] for t in current["transactions"]], ["9001", "9002", "9003", "9004"])
        first = current["transactions"][0]
        self.assertEqual(
            first,
            {
                "externalId": "9001",
                "date": "2026-10-01",
                "dueDate": "2026-10-01",
                "amount": "-12.5",
                "currency": "EUR",
                "label": LABEL_MARKER,
                "typeCode": "1",
            },
        )

    async def test_iban_comes_from_the_transactions(self):
        pages = default_pages()
        for page in pages["1001"]:
            for row in page["data"]:
                row["parsedData"]["clientIBAN"] = IBAN_A

        result = await run_fetch(FakeCE(pages=pages))

        by_id = {a["externalId"]: a for a in result["accounts"]}
        self.assertEqual(by_id["1001"]["iban"], IBAN_A)
        self.assertFalse(by_id["1001"]["ibanAmbiguous"])
        self.assertIsNone(by_id["1002"]["iban"])
        self.assertIsNone(by_id["2001"]["iban"])

    async def test_savings_fields_are_strings_or_null(self):
        result = await run_fetch(FakeCE())

        livret = next(a for a in result["accounts"] if a["externalId"] == "1002")
        for key in ("authorizedOverdraft", "ceiling", "remainingDepositCapacity", "fillingRatio"):
            self.assertTrue(livret[key] is None or isinstance(livret[key], str), key)

    async def test_requests_follow_the_observed_calls(self):
        ce = FakeCE()

        await run_fetch(ce)

        synthesis_requests = [r for r in ce.data_requests if r.url.path == SYNTHESIS_PATH]
        self.assertEqual(len(synthesis_requests), 1)
        params = synthesis_requests[0].url.params
        self.assertEqual(params["pfmCharacteristicsIndicator"], "true")
        self.assertEqual(params["productFamilyPFM"], "1,2,3,4,6,7,17,18,20,19")

        first_pages = [r for r in ce.tx_requests() if "pageToken" not in r.url.params]
        self.assertEqual(sorted(r.url.params["accountIds"] for r in first_pages), ["1001", "1002", "2001"])
        # Observed live 2026-10-07: the parsedData filter (status AC, ST/IN) drops
        # every card operation (status UP, granularity XT). Cards use the bare query.
        card_pages = [r for r in first_pages if r.url.params["accountIds"] == "2001"]
        self.assertEqual(len(card_pages), 1)
        self.assertNotIn("parsedData", card_pages[0].url.params)
        self.assertEqual(card_pages[0].url.params["take"], "50")
        first_pages = [r for r in first_pages if r.url.params["accountIds"] != "2001"]
        for request in first_pages:
            p = request.url.params
            self.assertEqual(p["businessType"], "UserProfile")
            self.assertEqual(p["include"], "Merchant")
            self.assertEqual(p["take"], "50")
            self.assertEqual(p["includeDisabledAccounts"], "true")
            self.assertEqual(p["orderBy"], "ByParsedData")
            self.assertEqual(p["parsedDataNameToOrderBy"], "accountingDate")
            self.assertEqual(p["includeSummary"], "true")
            self.assertEqual(p["useAndSearchForParsedData"], "false")
            self.assertEqual(
                json.loads(p["parsedData"]),
                [
                    {"key": "transactionGranularityCode", "value": "IN"},
                    {"key": "transactionGranularityCode", "value": "ST"},
                    {"key": "status", "value": "AC"},
                ],
            )

    async def test_only_get_with_bearer_and_accept_and_no_cookie(self):
        ce = FakeCE()

        await run_fetch(ce)

        self.assertTrue(ce.data_requests)
        for request in ce.data_requests:
            self.assertEqual(request.method, "GET")
            self.assertEqual(request.url.host, DATA_HOST)
            self.assertEqual(request.headers["Authorization"], f"Bearer {SECRET_TOKEN}")
            self.assertEqual(request.headers["Accept"], "application/json")
            self.assertNotIn("cookie", request.headers)
        self.assertEqual(ce.bank.requests, [])  # the fetcher alone never replays the chain

    async def test_decimals_are_strings_and_floats_never_leak(self):
        pages = default_pages()
        pages["1001"][0]["data"][0]["amount"] = 0.3
        pages["1001"][0]["data"][1]["amount"] = -0.1
        ce = FakeCE(pages=pages)

        result = await run_fetch(ce)

        rendered = json.dumps(result)
        current = next(a for a in result["accounts"] if a["externalId"] == "1001")
        amounts = {t["externalId"]: t["amount"] for t in current["transactions"]}
        self.assertEqual(amounts["9001"], "0.3")
        self.assertEqual(amounts["9002"], "-0.1")
        for account in result["accounts"]:
            self.assertIsInstance(account["balance"], str)
            for t in account["transactions"]:
                self.assertIsInstance(t["amount"], str)
        self.assertNotIn('"balance": 1234.56', rendered)  # a float would serialise bare

    async def test_card_balance_is_the_outstanding_of_future_due_dates(self):
        pages = default_pages()
        pages["2001"][0]["data"][0]["dueDate"] = "2026-10-28"  # future
        pages["2001"][0]["data"][1]["dueDate"] = "2026-10-04"  # past
        ce = FakeCE(pages=pages)

        result = await run_fetch(ce)

        card = next(a for a in result["accounts"] if a["externalId"] == "2001")
        self.assertEqual(card["balance"], "-25.0")
        self.assertEqual(card["nextDueDate"], "2026-10-28")


class PaginationTest(unittest.IsolatedAsyncioTestCase):
    async def test_two_pages_are_chained_with_page_token_and_merged(self):
        ce = FakeCE()

        result = await run_fetch(ce)

        current_requests = [r for r in ce.tx_requests() if r.url.params["accountIds"] == "1001"]
        self.assertEqual(len(current_requests), 2)
        self.assertNotIn("pageToken", current_requests[0].url.params)
        self.assertEqual(current_requests[1].url.params["pageToken"], "FAKE_PAGE_TOKEN_2")
        # The rest of the query is re-sent unchanged.
        first = dict(current_requests[0].url.params)
        second = dict(current_requests[1].url.params)
        second.pop("pageToken")
        self.assertEqual(first, second)
        current = next(a for a in result["accounts"] if a["externalId"] == "1001")
        self.assertEqual(len(current["transactions"]), 4)

    async def test_an_absent_next_page_token_ends_the_pagination(self):
        pages = default_pages()
        del pages["1001"][1]["meta"]["nextPageToken"]  # the real last page has no key at all
        ce = FakeCE(pages=pages)

        await run_fetch(ce)

        self.assertEqual(len([r for r in ce.tx_requests() if r.url.params["accountIds"] == "1001"]), 2)

    async def test_page_cap_is_exactly_twenty_pages_then_format_changed(self):
        template = default_pages()["1001"][0]

        def endless(request: httpx.Request):
            if request.url.params["accountIds"] != "1001":
                return None
            page = copy.deepcopy(template)
            n = int(request.url.params.get("pageToken", "T0")[1:]) + 1
            page["data"] = []
            page["meta"] = {"nextPageToken": f"T{n}"}
            return httpx.Response(200, json=page)

        ce = FakeCE(tx_override=endless)

        with self.assertRaises(fetcher.UpstreamFormatChanged) as ctx:
            await run_fetch(ce)

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")
        self.assertEqual(len([r for r in ce.tx_requests() if r.url.params["accountIds"] == "1001"]), 20)

    async def test_exactly_twenty_pages_that_end_are_accepted(self):
        template = default_pages()["1001"][0]

        def twenty(request: httpx.Request):
            if request.url.params["accountIds"] != "1001":
                return None
            page = copy.deepcopy(template)
            n = int(request.url.params.get("pageToken", "T0")[1:]) + 1
            page["data"] = []
            page["meta"] = {"nextPageToken": f"T{n}"} if n < 20 else {}
            return httpx.Response(200, json=page)

        result = await run_fetch(FakeCE(tx_override=twenty))

        self.assertEqual(len(result["accounts"]), 3)


class ErrorMappingTest(unittest.IsolatedAsyncioTestCase):
    async def test_401_on_the_synthesis_is_session_expired(self):
        with self.assertRaises(replay.SessionExpired) as ctx:
            await run_fetch(FakeCE(synthesis_http=401))

        self.assertEqual(ctx.exception.code, "SESSION_EXPIRED")

    async def test_401_on_a_transactions_call_is_session_expired(self):
        with self.assertRaises(replay.SessionExpired):
            await run_fetch(FakeCE(tx_http=401))

    async def test_5xx_and_transport_failures_are_upstream_unavailable(self):
        for ce in (FakeCE(synthesis_http=503), FakeCE(tx_http=500)):
            with self.subTest(ce=ce), self.assertRaises(fetcher.UpstreamUnavailable) as ctx:
                await run_fetch(ce)
            self.assertEqual(ctx.exception.code, "UPSTREAM_UNAVAILABLE")

        def boom(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("secret-host-detail " + SECRET_TOKEN)

        with self.assertRaises(fetcher.UpstreamUnavailable) as ctx:
            await fetcher.fetch_import(SECRET_TOKEN, transport=httpx.MockTransport(boom), today=TODAY)
        self.assertNotIn(SECRET_TOKEN, str(ctx.exception))
        self.assertNotIn("secret-host-detail", str(ctx.exception))

    async def test_non_json_or_wrong_shape_is_format_changed(self):
        cases = {
            "synthesis not json": FakeCE(synthesis_body=b"<html>"),
            "synthesis not an object": FakeCE(synthesis_body=b"[]"),
            "synthesis without items": FakeCE(synth={"nope": 1}),
            "tx not json": FakeCE(tx_body=b"<html>"),
            "tx without data": FakeCE(tx_body=b"{}"),
            "next token not a string": FakeCE(
                pages={**default_pages(), "1001": [{"data": [], "meta": {"nextPageToken": 5}}]}
            ),
        }
        for name, ce in cases.items():
            with self.subTest(name), self.assertRaises(fetcher.UpstreamFormatChanged):
                await run_fetch(ce)

    async def test_a_serialisation_failure_fails_the_whole_import_only_for_the_current_account(self):
        real = fetcher._serialise_account

        def failing_for(target: str):
            def flaky(account, transactions, loan=None, per=None):
                if account["externalId"] == target:
                    raise TypeError("bad")  # the type only: the message is never embedded
                return real(account, transactions, loan, per)

            return flaky

        with patch.object(fetcher, "_serialise_account", failing_for("1001")):
            with self.assertRaises(fetcher.UpstreamFormatChanged) as ctx:
                await run_fetch(FakeCE())
        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

        with patch.object(fetcher, "_serialise_account", failing_for("1002")):
            result = await run_fetch(FakeCE())
        self.assertEqual(
            result["unavailable"],
            [{"externalId": "1002", "kind": "LIVRET_A", "code": "UPSTREAM_FORMAT_CHANGED"}],
        )

    async def test_oversized_response_is_upstream_unavailable(self):
        big = b"{" + b" " * (replay.MAX_RESPONSE_BYTES + 1) + b"}"
        with self.assertRaises(fetcher.UpstreamUnavailable):
            await run_fetch(FakeCE(synthesis_body=big))

    async def test_a_current_account_parse_error_gives_no_partial_result(self):
        missing_due_date = default_pages()
        del missing_due_date["1001"][1]["data"][0]["dueDate"]  # current account, page 2

        with self.assertRaises(fetcher.UpstreamFormatChanged) as ctx:
            await run_fetch(FakeCE(pages=missing_due_date))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    async def test_a_host_outside_the_allow_list_is_refused_before_any_request(self):
        ce = FakeCE()

        for base in ("https://evil.example.com", "http://www.rs-ext-bad-ce.caisse-epargne.fr"):
            with self.subTest(base=base), self.assertRaises(replay.HostNotAllowed):
                await run_fetch(ce, base_url=base)

        self.assertEqual(ce.data_requests, [])

    async def test_the_data_host_passes_the_existing_allow_list(self):
        self.assertTrue(replay.is_allowed_url(fetcher.DATA_BASE_URL + SYNTHESIS_PATH))
        self.assertEqual(fetcher.DATA_BASE_URL, f"https://{DATA_HOST}")

    async def test_redirects_are_never_followed(self):
        def redirect(request: httpx.Request) -> httpx.Response:
            return httpx.Response(302, headers={"Location": "https://evil.example.com/x"})

        with self.assertRaises(fetcher.UpstreamUnavailable):
            await fetcher.fetch_import(SECRET_TOKEN, transport=httpx.MockTransport(redirect), today=TODAY)


class UnsupportedPassThroughTest(unittest.IsolatedAsyncioTestCase):
    async def test_other_families_are_listed_and_never_fetched(self):
        synth = synthesis()
        extra = copy.deepcopy(synth["items"][1])
        extra["identification"]["contractPfmId"] = 1003
        extra["identity"]["productFamilyPFM"] = {"code": "7", "label": "Epargne retraite"}
        extra["identity"]["productLabel"] = "PER"
        synth["items"].append(extra)
        ce = FakeCE(synth=synth)

        result = await run_fetch(ce)

        self.assertEqual(result["unsupported"], [{"externalId": "1003", "familyCode": "7"}])
        self.assertNotIn("1003", {a["externalId"] for a in result["accounts"]})
        self.assertNotIn("1003", {r.url.params["accountIds"] for r in ce.tx_requests()})

    async def test_per_and_loan_get_no_transactions_call_and_no_iban(self):
        # PER and loan have no PFM transactions: their contract id must never
        # reach the transactions endpoint, and they carry no IBAN.
        pages = default_pages()
        iban_page = copy.deepcopy(FIXTURES["transactions"]["livret_a"])
        pages[PER_ID] = [iban_page]
        pages[LOAN_ID] = [copy.deepcopy(iban_page)]
        ce = FakeCE(synth=copy.deepcopy(FIXTURES["synthesis_products"]), pages=pages)

        result = await run_fetch(ce)

        fetched = {r.url.params["accountIds"] for r in ce.tx_requests()}
        self.assertNotIn(PER_ID, fetched)
        self.assertNotIn(LOAN_ID, fetched)
        self.assertFalse(any(a.startswith("contract:") for a in fetched))
        self.assertIn("1003", fetched)  # the LDDS still reads its PFM transactions
        by_id = {a["externalId"]: a for a in result["accounts"]}
        for ext in (PER_ID, LOAN_ID):
            self.assertEqual(by_id[ext]["transactions"], [])
            self.assertIsNone(by_id[ext]["iban"])
            self.assertFalse(by_id[ext]["ibanAmbiguous"])


def loan_token_provider(calls: list, *, raises: Exception | None = None):
    async def provide() -> str:
        calls.append(1)
        if raises is not None:
            raise raises
        return LOAN_TOKEN

    return provide


def products_ce(**kwargs) -> FakeCE:
    return FakeCE(synth=copy.deepcopy(FIXTURES["synthesis_products"]), **kwargs)


class LoanDetailTest(unittest.IsolatedAsyncioTestCase):
    async def test_loan_account_carries_the_parsed_detail_as_strings(self):
        ce = products_ce()
        calls: list = []

        result = await run_fetch(ce, loan_token=loan_token_provider(calls))

        by_id = {a["externalId"]: a for a in result["accounts"]}
        self.assertEqual(
            by_id[LOAN_ID]["loan"],
            {
                "borrowedAmount": "12000",
                "interestRate": "0.03",
                "monthlyPayment": "280.0",
                "insuranceMonthly": "14.39",
                "signatureDate": "2026-09-15",
                "firstDueDate": "2026-11-05",
                "lastDueDate": "2030-10-05",
                "durationMonths": 48,
                "linkedIban": IBAN_A,
            },
        )
        for ext, account in by_id.items():
            if ext != LOAN_ID:
                self.assertIsNone(account["loan"], ext)
        self.assertEqual(len(calls), 1)

    async def test_detail_is_read_with_the_loan_token_on_the_stripped_contract_id(self):
        ce = products_ce()

        await run_fetch(ce, loan_token=loan_token_provider([]))

        [request] = ce.loan_requests()
        self.assertEqual(request.method, "GET")
        self.assertEqual(request.url.host, DATA_HOST)
        self.assertEqual(request.url.path, LOAN_PATH + "00000000000015")
        self.assertEqual(request.headers["Authorization"], f"Bearer {LOAN_TOKEN}")
        # The main token never reads the loan detail, the loan token nothing else.
        for other in ce.data_requests:
            if other is not request:
                self.assertEqual(other.headers["Authorization"], f"Bearer {SECRET_TOKEN}")

    async def test_no_loan_contract_means_no_loan_token_and_no_detail_call(self):
        ce = FakeCE()
        calls: list = []

        result = await run_fetch(ce, loan_token=loan_token_provider(calls))

        self.assertEqual(calls, [])
        self.assertEqual(ce.loan_requests(), [])
        self.assertTrue(all(a["loan"] is None for a in result["accounts"]))

    async def test_without_a_loan_token_provider_the_loan_is_null(self):
        ce = products_ce()

        result = await run_fetch(ce)

        self.assertEqual(ce.loan_requests(), [])
        by_id = {a["externalId"]: a for a in result["accounts"]}
        self.assertIsNone(by_id[LOAN_ID]["loan"])
        self.assertEqual(by_id[LOAN_ID]["balance"], "-9876.54")

    async def test_a_failed_detail_leaves_the_loan_unavailable_and_the_rest_imported(self):
        bad_detail = copy.deepcopy(FIXTURES["loan_detail"])
        del bad_detail["personalLoanPrdSynthesisView"]["offer"]["duration"]
        cases = {
            "5xx": (products_ce(loan_http=503), "UPSTREAM_UNAVAILABLE"),
            "403": (products_ce(loan_http=403), "UPSTREAM_UNAVAILABLE"),
            "format": (products_ce(loan_detail=bad_detail), "INVALID_LOAN_DETAIL"),
        }
        for name, (ce, code) in cases.items():
            with self.subTest(name):
                result = await run_fetch(ce, loan_token=loan_token_provider([]))
                self.assertNotIn(LOAN_ID, {a["externalId"] for a in result["accounts"]})
                self.assertEqual(
                    result["unavailable"], [{"externalId": LOAN_ID, "kind": "LOAN", "code": code}]
                )
                self.assertEqual(len([a for a in result["accounts"] if a["kind"] != "CARD"]), 4)

    async def test_a_failed_loan_oauth_leaves_every_loan_unavailable(self):
        for exc in (replay.UpstreamError("x"), replay.HostNotAllowed("x")):
            with self.subTest(type(exc).__name__):
                ce = products_ce()
                result = await run_fetch(ce, loan_token=loan_token_provider([], raises=exc))
                self.assertNotIn(LOAN_ID, {a["externalId"] for a in result["accounts"]})
                self.assertEqual(
                    result["unavailable"], [{"externalId": LOAN_ID, "kind": "LOAN", "code": exc.code}]
                )
                self.assertEqual(ce.loan_requests(), [])
                self.assertEqual(len([a for a in result["accounts"] if a["kind"] != "CARD"]), 4)

    async def test_session_expired_on_the_detail_or_the_loan_oauth_propagates(self):
        with self.assertRaises(replay.SessionExpired):
            await run_fetch(products_ce(loan_http=401), loan_token=loan_token_provider([]))
        with self.assertRaises(replay.SessionExpired):
            await run_fetch(
                products_ce(), loan_token=loan_token_provider([], raises=replay.SessionExpired("x"))
            )

    async def test_loan_failure_logs_the_code_only(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        bad = copy.deepcopy(FIXTURES["loan_detail"])
        bad["personalLoanPrdSynthesisView"]["offer"]["signatureDate"] = "secret-date-value"
        try:
            await run_fetch(products_ce(loan_detail=bad), loan_token=loan_token_provider([]))
            await run_fetch(products_ce(loan_http=500), loan_token=loan_token_provider([]))
            await run_fetch(products_ce(), loan_token=loan_token_provider([]))
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)
        rendered = stream.getvalue()
        self.assertIn("INVALID_LOAN_DETAIL", rendered)
        self.assertIn("UPSTREAM_UNAVAILABLE", rendered)
        for value in (
            "secret-date-value", "00000000000015", IBAN_A, "12000", "280", "FAKEFIRST",
            "FAKELAST", "FAKE LINKED", LOAN_TOKEN, SECRET_TOKEN,
        ):
            self.assertNotIn(value, rendered)


class PerHoldingsImportTest(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def payload():
        stamp = int(datetime(2026, 10, 1, tzinfo=timezone.utc).timestamp() * 1000)
        return {"detailContratVie": {"valorisation": {
            "montant": 1700, "date": stamp,
            "globalInvestCalcRest": {"globalAmountFondEuros": 1000, "globalAmountUnitesCompte": 700},
            "supports": [{"nom": "Synthetic fund", "codeIsin": "XX0000000001",
                          "nombreUnitesCompte": 10, "valeurUniteCompte": 70,
                          "montant": 700, "dateValeurUniteCompte": stamp,
                          "idCompartment": 1, "familleFondsGeneraux": False}],
        }}}

    async def test_complete_positions_and_euro_pocket_replace_only_per_synthesis_total(self):
        calls = []

        async def detail(contract_id, entity_code):
            calls.append((contract_id, entity_code))
            return self.payload()

        result = await run_fetch(products_ce(), per_detail=detail)
        account = next(a for a in result["accounts"] if a["externalId"] == PER_ID)
        self.assertEqual(calls, [(PER_ID.removeprefix("contract:"), "00000")])
        self.assertEqual(account["balance"], "1700")
        self.assertEqual(account["per"]["euroFundBalance"], "1000")
        self.assertEqual(account["per"]["valuationDate"], "2026-10-01")
        self.assertEqual(account["per"]["positions"][0]["isin"], "XX0000000001")
        self.assertEqual(account["per"]["positions"][0]["quantity"], "10")
        self.assertEqual(account["per"]["positions"][0]["unitPrice"], "70")
        self.assertEqual(account["transactions"], [])
        self.assertFalse(any("per" in a for a in result["accounts"] if a["externalId"] != PER_ID))

    async def test_without_per_contract_the_provider_is_never_called(self):
        async def detail(_, _entity_code):
            self.fail("Non-PER import must not contact the insurer")

        result = await run_fetch(FakeCE(), per_detail=detail)
        self.assertFalse(any("per" in a for a in result["accounts"]))

    async def test_bad_insurer_detail_leaves_the_per_unavailable_and_the_rest_imported(self):
        async def detail(_, _entity_code):
            payload = self.payload()
            payload["detailContratVie"]["valorisation"]["montant"] = 999999
            return payload

        result = await run_fetch(products_ce(), per_detail=detail)

        self.assertNotIn(PER_ID, {a["externalId"] for a in result["accounts"]})
        self.assertEqual(
            result["unavailable"], [{"externalId": PER_ID, "kind": "PER", "code": "INVALID_PER_DETAIL"}]
        )
        self.assertIn(LOAN_ID, {a["externalId"] for a in result["accounts"]})

    async def test_expired_insurer_session_is_not_a_successful_balance_only_import(self):
        async def detail(_, _entity_code):
            raise replay.SessionExpired("Insurer session expired")

        with self.assertRaises(replay.SessionExpired):
            await run_fetch(products_ce(), per_detail=detail)


class LogHygieneTest(unittest.IsolatedAsyncioTestCase):
    async def test_no_value_in_logs_or_error_messages(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        handler.setLevel(logging.DEBUG)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        pages = default_pages()
        for page in pages["1001"]:
            for row in page["data"]:
                row["parsedData"]["clientIBAN"] = IBAN_A
        bad = default_pages()
        bad["1001"][0]["data"][0]["amount"] = "secret-amount-string"
        messages = []
        try:
            await run_fetch(FakeCE(pages=pages))
            for ce in (FakeCE(pages=bad), FakeCE(tx_http=401), FakeCE(synthesis_http=500)):
                try:
                    await run_fetch(ce)
                except Exception as exc:  # noqa: BLE001
                    messages.append(str(exc))
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)

        rendered = stream.getvalue() + "\n".join(messages)
        self.assertEqual(len(messages), 3)
        for value in (
            SECRET_TOKEN, IBAN_A, BALANCE_MARKER, LABEL_MARKER, "FAKE CPT", "FAKE LIVRET",
            "secret-amount-string", "Bearer", "Authorization",
        ):
            self.assertNotIn(value, rendered)


def fail_transactions(account_id: str, status: int = 500):
    """The transactions call for one account answers `status`; every other call is served."""

    def override(request: httpx.Request):
        if request.url.params.get("accountIds") == account_id:
            return httpx.Response(status, content=b"")
        return None

    return override


class AccountIsolationTest(unittest.IsolatedAsyncioTestCase):
    """Only the current account may fail the whole import; any other account is listed as unavailable."""

    @staticmethod
    def ids(accounts) -> set:
        return {a["externalId"] for a in accounts}

    async def test_livret_a_transactions_failure_leaves_only_that_account_unavailable(self):
        result = await run_fetch(FakeCE(tx_override=fail_transactions("1002")))

        self.assertEqual(self.ids(result["accounts"]), DEFAULT_IDS - {"1002"})
        self.assertEqual(
            result["unavailable"],
            [{"externalId": "1002", "kind": "LIVRET_A", "code": "UPSTREAM_UNAVAILABLE"}],
        )

    async def test_card_transactions_failure_leaves_only_the_card_unavailable(self):
        result = await run_fetch(FakeCE(tx_override=fail_transactions("2001")))

        self.assertEqual(self.ids(result["accounts"]), DEFAULT_IDS - {"2001"})
        self.assertEqual(
            result["unavailable"],
            [{"externalId": "2001", "kind": "CARD", "code": "UPSTREAM_UNAVAILABLE"}],
        )

    async def test_current_account_transactions_failure_still_fails_the_whole_import(self):
        with self.assertRaises(fetcher.UpstreamUnavailable):
            await run_fetch(FakeCE(tx_override=fail_transactions("1001")))

    async def test_livret_a_format_change_is_unavailable_with_its_code(self):
        pages = default_pages()
        pages["1002"][0]["data"][0]["amount"] = "12,5"  # a string where a number is expected

        result = await run_fetch(FakeCE(pages=pages))

        self.assertEqual(self.ids(result["accounts"]), DEFAULT_IDS - {"1002"})
        self.assertEqual(
            result["unavailable"],
            [{"externalId": "1002", "kind": "LIVRET_A", "code": "INVALID_TRANSACTIONS"}],
        )

    async def test_card_rows_filed_under_another_account_make_only_the_card_unavailable(self):
        pages = default_pages()
        pages["2001"][0]["data"][0]["accountId"] = 1001  # card rows filed under the current account

        result = await run_fetch(FakeCE(pages=pages))

        self.assertEqual(self.ids(result["accounts"]), DEFAULT_IDS - {"2001"})
        self.assertEqual(
            result["unavailable"],
            [{"externalId": "2001", "kind": "CARD", "code": "INVALID_TRANSACTIONS"}],
        )

    async def test_per_detail_refusal_leaves_only_the_per_unavailable(self):
        async def detail(_contract_id, _entity_code):
            raise replay.UpstreamError("PER insurer refused the request with insurer status 461")

        result = await run_fetch(products_ce(), per_detail=detail)

        self.assertEqual(self.ids(result["accounts"]), ALL_IDS - {PER_ID})
        self.assertEqual(
            result["unavailable"], [{"externalId": PER_ID, "kind": "PER", "code": "UPSTREAM_ERROR"}]
        )

    async def test_a_raw_transport_error_on_the_per_detail_is_isolated_too(self):
        async def detail(_contract_id, _entity_code):
            raise httpx.ConnectError("refused")

        result = await run_fetch(products_ce(), per_detail=detail)

        self.assertEqual(self.ids(result["accounts"]), ALL_IDS - {PER_ID})
        self.assertEqual(
            result["unavailable"], [{"externalId": PER_ID, "kind": "PER", "code": "UPSTREAM_UNAVAILABLE"}]
        )

    async def test_per_and_loan_failures_are_listed_together(self):
        async def detail(_contract_id, _entity_code):
            raise replay.UpstreamError("PER insurer refused the request")

        result = await run_fetch(
            products_ce(loan_http=503), loan_token=loan_token_provider([]), per_detail=detail
        )

        self.assertEqual(self.ids(result["accounts"]), ALL_IDS - {PER_ID, LOAN_ID})
        self.assertEqual(
            sorted((u["kind"], u["code"]) for u in result["unavailable"]),
            [("LOAN", "UPSTREAM_UNAVAILABLE"), ("PER", "UPSTREAM_ERROR")],
        )

    async def test_nothing_is_unavailable_when_every_account_reads(self):
        result = await run_fetch(FakeCE())

        self.assertEqual(result["unavailable"], [])
        self.assertEqual(self.ids(result["accounts"]), DEFAULT_IDS)

    async def test_unavailable_accounts_log_their_code_and_no_value(self):
        stream = io.StringIO()
        handler = logging.StreamHandler(stream)
        root = logging.getLogger()
        old_level = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        try:
            await run_fetch(FakeCE(tx_override=fail_transactions("1002")))
            await run_fetch(products_ce(loan_http=503), loan_token=loan_token_provider([]))
        finally:
            root.removeHandler(handler)
            root.setLevel(old_level)
        rendered = stream.getvalue()
        self.assertIn("kind=LIVRET_A code=UPSTREAM_UNAVAILABLE", rendered)
        self.assertIn("kind=LOAN code=UPSTREAM_UNAVAILABLE", rendered)
        for value in (LABEL_MARKER, BALANCE_MARKER, IBAN_A, LOAN_TOKEN, SECRET_TOKEN):
            self.assertNotIn(value, rendered)


if __name__ == "__main__":
    unittest.main()
