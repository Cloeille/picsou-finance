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
from datetime import date
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
        **bank_kwargs,
    ):
        self.bank = FakeBank(**bank_kwargs)
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
        return httpx.Response(404)

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

        self.assertEqual(set(result), {"accounts", "unsupported"})
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

    async def test_a_serialisation_failure_is_format_changed_never_internal_or_partial(self):
        for exc in (KeyError("missing"), TypeError("bad"), ValueError("bad")):
            calls = {"n": 0}
            real = fetcher._serialise_account

            def flaky(account, transactions, _real=real, _calls=calls, _exc=exc):
                _calls["n"] += 1
                if _calls["n"] == 2:  # the first account serialises, the second breaks
                    raise _exc
                return _real(account, transactions)

            with self.subTest(exc=type(exc).__name__):
                result = None
                with patch.object(fetcher, "_serialise_account", flaky), self.assertRaises(
                    fetcher.UpstreamFormatChanged
                ) as ctx:
                    result = await run_fetch(FakeCE())
                self.assertIsNone(result)
                self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    async def test_oversized_response_is_upstream_unavailable(self):
        big = b"{" + b" " * (replay.MAX_RESPONSE_BYTES + 1) + b"}"
        with self.assertRaises(fetcher.UpstreamUnavailable):
            await run_fetch(FakeCE(synthesis_body=big))

    async def test_a_parse_error_anywhere_gives_no_partial_result(self):
        broken = default_pages()
        broken["1002"][0]["data"][0]["amount"] = "12,5"  # a string where a number is expected
        mismatch = default_pages()
        mismatch["2001"][0]["data"][0]["accountId"] = 1001  # card rows filed under another account
        missing_due_date = default_pages()
        del missing_due_date["1001"][1]["data"][0]["dueDate"]
        for name, pages in (
            ("bad amount", broken),
            ("card accountId mismatch", mismatch),
            ("missing dueDate on page 2", missing_due_date),
        ):
            with self.subTest(name):
                result = None
                with self.assertRaises(fetcher.UpstreamFormatChanged) as ctx:
                    result = await run_fetch(FakeCE(pages=pages))
                self.assertIsNone(result)
                self.assertNotIn("12,5", str(ctx.exception))

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
        bad["1002"][0]["data"][0]["amount"] = "secret-amount-string"
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


if __name__ == "__main__":
    unittest.main()
