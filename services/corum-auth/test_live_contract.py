"""Regression tests for what the live end-to-end run found on 2026-09-26.

Each test here corresponds to a defect that a fixture-based test could not see,
because the fixture was written from the same wrong assumption as the code.
"""
import asyncio
import os
import unittest
from unittest.mock import patch

from fastapi import HTTPException
from fastapi.testclient import TestClient

import main
from fixtures_life import LIFE_CODE, life_payload, operations_payload
from positions_parser import PositionsFormatError


class _QuietContext:
    async def route(self, *_args, **_kwargs):
        return None


class _ExplodingContext:
    async def route(self, *_args, **_kwargs):
        raise RuntimeError("route failed")


class _FakeBrowser:
    def __init__(self, explode: bool):
        self._explode = explode
        self.closed = False

    async def new_context(self, **_kwargs):
        if self._explode:
            return _ExplodingContext()
        return _QuietContext()

    async def close(self):
        self.closed = True


class _FakeChromium:
    def __init__(self, explode: bool):
        self._browser = _FakeBrowser(explode)

    async def launch(self, **_kwargs):
        return self._browser


class _FakePlaywright:
    def __init__(self, explode: bool):
        self.chromium = _FakeChromium(explode)


class ProductPathTest(unittest.TestCase):
    """The per-fund read has no `realEstate` segment, the contract read has it.

    Verified live: `/api/contract/realEstate/{code}/FULL_PROPERTY/product/US`
    answers 404 with a message that reads like a fund that does not exist,
    while `/api/contract/{code}/FULL_PROPERTY/product/US` answers 200. A spike
    note carried the wrong path into the first implementation, and nothing
    caught it because the parser tests feed it a dict, not a URL.
    """

    def test_product_path_drops_the_real_estate_segment(self):
        path = main.PRODUCT_PATH.format(
            code="089665", investment_type="FULL_PROPERTY", product="US"
        )
        self.assertEqual(path, "/api/contract/089665/FULL_PROPERTY/product/US")
        self.assertNotIn("realEstate", path)

    def test_contract_detail_path_keeps_the_real_estate_segment(self):
        path = main.CONTRACT_DETAIL_PATH.format(
            code="089665", investment_type="FULL_PROPERTY"
        )
        self.assertEqual(path, "/api/contract/realEstate/089665/FULL_PROPERTY")


class SessionCollectorTest(unittest.TestCase):
    """CORUM authenticates on a whole cookie jar, and one cookie is not enough.

    Verified live: replaying only `ai_session` -- the httpOnly cookie the SPA
    sets, and the obvious candidate -- is answered `all_tokens_expired` on a
    session that is demonstrably still valid. `au_t` and `re_t` are the tokens
    the API actually checks; `ai_session` is only the SPA's own flag.
    """

    def test_keeps_every_corum_cookie_not_just_the_session_one(self):
        collector = main.SessionCollector()
        for name, value in [
            ("ai_session", "s" * 50),
            ("au_t", "a" * 655),
            ("re_t", "r" * 657),
            ("cusid", "c" * 13),
        ]:
            collector.record("client.corum.fr", name, value)
        self.assertIn("au_t", collector.cookies)
        self.assertIn("re_t", collector.cookies)
        header = collector.cookie_header()
        self.assertIn("au_t=", header)
        self.assertIn("re_t=", header)
        self.assertIn("ai_session=", header)

    def test_accepts_parent_and_child_domains(self):
        collector = main.SessionCollector()
        collector.record(".corum.fr", "cf_clearance", "c" * 26)
        collector.record(".client.corum.fr", "cusid", "d" * 13)
        collector.record("client.corum.fr", "au_t", "e" * 10)
        self.assertEqual(len(collector.cookies), 3)

    def test_ignores_cookies_from_other_sites(self):
        collector = main.SessionCollector()
        collector.record("example.com", "tracking", "x" * 40)
        collector.record("google.com", "sid", "y" * 40)
        self.assertEqual(collector.cookies, {})

    def test_cookie_header_is_a_valid_header(self):
        collector = main.SessionCollector()
        collector.record("client.corum.fr", "au_t", "a=1")
        collector.record("client.corum.fr", "re_t", "b=2")
        self.assertEqual(collector.cookie_header(), "au_t=a=1; re_t=b=2")


class BrowserHeadersTest(unittest.TestCase):
    """Cloudflare keys on the client fingerprint, not on the session cookie.

    Verified live: the same live cookies requested from the sidecar's own
    process answer 403 Error 1010 -- "the site owner has banned your browser's
    signature" -- before any application code runs, while the identical request
    issued by the page answers 200. The reads therefore happen in the page.
    """

    def test_browser_headers_carry_a_user_agent(self):
        self.assertIn("User-Agent", main.BROWSER_HEADERS)
        self.assertIn("Mozilla/5.0", main.BROWSER_HEADERS["User-Agent"])
        # A default Python/urllib UA is exactly what Error 1010 rejects.
        self.assertNotIn("python-requests", main.BROWSER_HEADERS["User-Agent"])

    def test_reads_are_same_origin(self):
        self.assertEqual(main.BROWSER_HEADERS["Referer"], f"{main.BASE_URL}/")
        self.assertEqual(main.BROWSER_HEADERS["Origin"], main.BASE_URL)


class HarvestReadsTheJarTest(unittest.IsolatedAsyncioTestCase):
    """`harvest` must read the context's cookie jar, not the response headers.

    The first implementation hooked `context.on("response")` and iterated
    `response.headers_array`. That is HTTP headers, not cookies: the entries
    carry no `domain`, so every one of them failed the CORUM-domain check and
    was dropped. The bare `except` around the loop then swallowed the error, so
    the jar stayed empty, `au_t` never appeared, and every login -- including a
    correct one -- was answered 401.

    Unit tests of `record()` could not see it, because they call `record()`
    directly and never go through the path the login actually takes.
    """

    class _FakeContext:
        def __init__(self, cookies):
            self._cookies = cookies

        async def cookies(self):
            return self._cookies

    async def test_harvest_keeps_every_corum_cookie(self):
        collector = main.SessionCollector()
        await collector.harvest(self._FakeContext([
            {"domain": "client.corum.fr", "name": "au_t", "value": "a" * 20},
            {"domain": ".corum.fr", "name": "re_t", "value": "r" * 20},
            {"domain": "example.com", "name": "sid", "value": "x" * 20},
        ]))
        self.assertIn("au_t", collector.cookies)
        self.assertIn("re_t", collector.cookies)
        self.assertNotIn("sid", collector.cookies)

    async def test_harvest_is_idempotent_across_polls(self):
        """The wait loop polls, so a second read must not lose what the first kept."""
        collector = main.SessionCollector()
        await collector.harvest(self._FakeContext(
            [{"domain": "client.corum.fr", "name": "au_t", "value": "a" * 20}]
        ))
        await collector.harvest(self._FakeContext(
            [{"domain": "client.corum.fr", "name": "cf_clearance", "value": "c" * 20}]
        ))
        self.assertIn("au_t", collector.cookies)
        self.assertIn("cf_clearance", collector.cookies)

    async def test_harvest_tolerates_an_entry_without_a_domain(self):
        collector = main.SessionCollector()
        await collector.harvest(self._FakeContext([{"name": "au_t", "value": "a"}]))
        self.assertEqual(collector.cookies, {})


class InitiateClosesItsBrowserTest(unittest.IsolatedAsyncioTestCase):
    """A successful login must release the browser too.

    `_close_resources` used to run only in the `except` branches, so the success
    path returned while still holding a Chromium process, a Playwright driver
    and one of the `MAX_CONCURRENT_BROWSERS` slots. After four successful
    logins the sidecar answered 503 to everything until it was restarted.

    The check is on the handler's own control flow, so it reads the source:
    a `finally` that calls the closer, and no `except` branch that calls it
    again.
    """
    # A browser slot is the scarce resource, so this is asserted on the code
    # rather than by standing up four real Chromium instances in CI.
    def test_initiate_closes_resources_in_a_finally(self):
        import inspect

        source = inspect.getsource(main.initiate)
        self.assertIn("finally:", source)
        self.assertIn("_close_resources", source.split("finally:")[-1])
        # Exactly one call site: a closer in a branch too would double-close.
        self.assertEqual(source.count("_close_resources("), 1)

    def test_every_open_path_closes_exactly_once(self):
        """Each path that opens a browser closes it from one place, in a finally."""
        import inspect

        # `/positions` and `/portfolio` open their browser through
        # `_read_in_portal`, so that is the open path to pin for them.
        for handler in (main.initiate, main._read_in_portal):
            source = inspect.getsource(handler)
            self.assertIn("finally:", source, f"{handler.__name__} has no finally")
            self.assertEqual(
                source.count("_close_resources("), 1,
                f"{handler.__name__} closes from more than one place",
            )

    def test_read_endpoints_open_no_browser_of_their_own(self):
        """The read endpoints use the browser of `_read_in_portal`, never their own."""
        import inspect

        for handler in (main.positions, main.portfolio):
            source = inspect.getsource(handler)
            self.assertIn("_read_in_portal(", source)
            self.assertNotIn("_new_browser(", source)

    async def test_each_read_endpoint_passes_its_own_reader(self):
        """Each read endpoint passes its own reader; a swap would pass the source checks."""
        from unittest.mock import AsyncMock

        for handler, reader in (
            (main.positions, main._read_positions),
            (main.portfolio, main._read_portfolio),
        ):
            with self.subTest(handler=handler.__name__), \
                    patch.object(main, "_read_in_portal", AsyncMock(return_value={})) as read_in_portal:
                await handler(main.SessionRequest(sessionState="{}"))

            read_in_portal.assert_awaited_once()
            self.assertEqual(read_in_portal.await_args.args, ("{}", reader))


class NewBrowserReleasesOnSetupFailureTest(unittest.IsolatedAsyncioTestCase):
    """A failure between the launch and the return must give the slot back.

    `_acquire_browser_slot` is called before the launch, and `new_context` /
    `route` run after it. A raise in between left the slot counted and the
    Chromium process running: the caller raises too, so it never receives the
    browser and its own `finally` has nothing to close. Four of those and the
    sidecar answers 503 until it is restarted.

    Driven with a fake Playwright rather than a real Chromium, so the failure
    point is exact and the assertion is on the slot count itself.
    """

    async def _run(self, explode):
        with patch.object(main, "_browsers", 0):
            pw = _FakePlaywright(explode)
            if explode:
                with self.assertRaises(RuntimeError):
                    await main._new_browser(pw)
            else:
                await main._new_browser(pw)
            return main._browsers

    async def test_a_failure_in_setup_releases_the_slot(self):
        self.assertEqual(await self._run(explode=True), 0)

    async def test_a_failure_in_setup_closes_the_browser(self):
        pw = _FakePlaywright(explode=True)
        with patch.object(main, "_browsers", 0):
            with self.assertRaises(RuntimeError):
                await main._new_browser(pw)
        self.assertTrue(pw.chromium._browser.closed)

    async def test_a_successful_setup_still_holds_the_slot(self):
        """The success path must not close early -- the caller owns it now."""
        self.assertEqual(await self._run(explode=False), 1)


class SelectsContractsTest(unittest.TestCase):
    def assertRefused(self, active, status, detail):
        with self.assertRaises(HTTPException) as caught:
            main._select_contracts(active)
        self.assertEqual(caught.exception.status_code, status)
        self.assertEqual(caught.exception.detail, detail)

    def test_a_life_contract_does_not_make_one_real_estate_contract_ambiguous(self):
        selected = main._select_contracts([
            {"contractCode": "LIFE-1", "contractType": "LIFE"},
            {"contractCode": "RE-1", "contractType": "real_estate"},
        ])

        self.assertEqual(selected, ("RE-1", ["LIFE-1"]))

    def test_a_life_only_login_is_read_without_a_scpi_part(self):
        selected = main._select_contracts([
            {"contractCode": "LIFE-1", "contractType": "LIFE"},
            {"contractCode": "LIFE-2", "contractType": "LIFE"},
        ])

        self.assertEqual(selected, (None, ["LIFE-1", "LIFE-2"]))

    def test_per_and_capitalisation_are_still_left_out(self):
        selected = main._select_contracts([
            {"contractCode": "RE-1", "contractType": "REAL_ESTATE"},
            {"contractCode": "PER-1", "contractType": "PER"},
            {"contractCode": "CAP-1", "contractType": "CAPITALISATION"},
        ])

        self.assertEqual(selected, ("RE-1", []))

    def test_two_real_estate_contracts_stay_ambiguous(self):
        self.assertRefused([
            {"contractCode": "RE-1", "contractType": "REAL_ESTATE"},
            {"contractCode": "RE-2", "contractType": "REAL_ESTATE"},
        ], 409, "MULTIPLE_CONTRACTS")

    def test_two_real_estate_contracts_block_the_life_part_too(self):
        # The backend commits both parts or neither, so a 409 on the SCPI side
        # is a 409 for the whole read.
        self.assertRefused([
            {"contractCode": "RE-1", "contractType": "REAL_ESTATE"},
            {"contractCode": "RE-2", "contractType": "REAL_ESTATE"},
            {"contractCode": "LIFE-1", "contractType": "LIFE"},
        ], 409, "MULTIPLE_CONTRACTS")

    def test_a_life_entry_without_a_usable_code_is_refused_when_life_is_read(self):
        """A life entry whose code cannot key an account is refused, not skipped."""
        for code in (None, "", 123, ["LIFE-2"]):
            with self.subTest(code=code), self.assertRaises(HTTPException) as caught:
                main._select_contracts([
                    {"contractCode": "LIFE-1", "contractType": "LIFE"},
                    {"contractCode": code, "contractType": "life"},
                ], reads_life=True)
            self.assertEqual(caught.exception.status_code, 502)
            self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_a_real_estate_entry_without_a_usable_code_is_refused_when_life_is_read(self):
        """A real-estate entry whose code cannot be looked up is refused, not skipped."""
        for code in (None, "", 123, ["RE-2"]):
            with self.subTest(code=code), self.assertRaises(HTTPException) as caught:
                main._select_contracts([
                    {"contractCode": "LIFE-1", "contractType": "LIFE"},
                    {"contractCode": code, "contractType": "real_estate"},
                ], reads_life=True)
            self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_a_life_entry_without_a_usable_code_is_skipped_by_the_scpi_read(self):
        selected = main._select_contracts([
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": "", "contractType": "LIFE"},
        ])

        self.assertEqual(selected, ("RE-1", []))

    def test_neither_kind_is_incomplete(self):
        self.assertRefused([
            {"contractCode": "PER-1", "contractType": "PER"},
        ], 502, "PORTFOLIO_INCOMPLETE")


class LifeDetailPathTest(unittest.TestCase):
    """Verified live: `/api/contract/life/{code}` answers 200 with the whole
    contract. Pinned here because the parser tests feed a dict, not a URL."""

    def test_life_detail_path(self):
        self.assertEqual(
            main.LIFE_DETAIL_PATH.format(code="000123"), "/api/contract/life/000123"
        )

    def test_operations_path(self):
        self.assertEqual(main.OPERATIONS_PATH, "/api/operations/")


def _scpi_contract():
    return {
        "contractCode": "RE-1",
        "contractType": "REAL_ESTATE",
        "propertyRightType": "FULL_PROPERTY",
        "valuationAmount": 300.0,
        "valuationDate": "26/09/2026",
        "productsData": [{"productCode": "US", "quantity": 2, "valuationAmount": 300.0}],
    }


def _scpi_product():
    return {
        "productCode": "US",
        "saving": {
            "share": {
                "quantity": 2,
                "unitPrice": 170,
                "withdrawalPrice": 150,
                "valuationDate": "26/09/2026",
            },
            "value": 300.0,
        },
    }


class ReadsTest(unittest.IsolatedAsyncioTestCase):
    """The reads behind `/portfolio` and `/positions`, with the in-page fetch
    replaced by a path -> body map. The browser is not what is under test."""

    def _serve(self, active, **bodies):
        """Serve `active` and the default contract bodies by path, overridden by
        `bodies`; a body that is an exception is raised. Each requested path is
        recorded."""
        routes = {
            main.CONTRACT_PATH: active,
            main.CONTRACT_DETAIL_PATH.format(code="RE-1", investment_type="FULL_PROPERTY"):
                _scpi_contract(),
            main.PRODUCT_PATH.format(code="RE-1", investment_type="FULL_PROPERTY", product="US"):
                _scpi_product(),
            main.LIFE_DETAIL_PATH.format(code=LIFE_CODE): life_payload(),
            main.OPERATIONS_PATH: operations_payload(),
            **bodies,
        }
        self.requested = []

        async def get_json(_page, path):
            """Stand-in for the in-page fetch: record the path, return or raise its body."""
            self.requested.append(path)
            body = routes[path]
            if isinstance(body, Exception):
                raise body
            return 200, body

        return patch.object(main, "_get_json", get_json)

    async def test_portfolio_reads_both_kinds(self):
        """A login with a real-estate and a life contract returns both parts."""
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE", "valuationAmount": 3506.56},
        ]
        with self._serve(active):
            result = await main._read_portfolio(object())

        self.assertEqual(result["realEstate"]["contractCode"], "RE-1")
        self.assertEqual([c["contractCode"] for c in result["life"]], [LIFE_CODE])

    async def test_portfolio_without_scpi_carries_a_null_real_estate(self):
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active):
            result = await main._read_portfolio(object())

        self.assertIsNone(result["realEstate"])
        self.assertEqual(len(result["life"]), 1)
        # Only the Life detail was read: no SCPI call for a contract that is not there.
        self.assertEqual(
            self.requested,
            [main.CONTRACT_PATH, main.LIFE_DETAIL_PATH.format(code=LIFE_CODE), main.OPERATIONS_PATH],
        )

    async def test_an_upstream_code_stays_one_path_segment(self):
        # A code that would walk out of /api/contract/life/ is encoded, so the
        # fetch stays on the Life endpoint instead of reaching another one.
        hostile = "../../profile"
        encoded = main.LIFE_DETAIL_PATH.format(code="..%2F..%2Fprofile")
        active = [{"contractCode": hostile, "contractType": "LIFE"}]
        with self._serve(active, **{encoded: life_payload()}):
            with self.assertRaises(main.PositionsFormatError):
                await main._read_portfolio(object())

        self.assertEqual(self.requested[-1], encoded)

    def test_a_dot_segment_code_is_refused(self):
        """An empty or all-dots code is refused: `..` survives percent-encoding."""
        for code in ("", ".", ".."):
            with self.subTest(code=code):
                with self.assertRaises(main.HTTPException) as caught:
                    main._segment(code)
                self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    async def test_portfolio_matches_the_wire_contract(self):
        # Exactly the keys `CorumAdapter` decodes; `extra="forbid"` on the Java
        # side turns a renamed key into a 502, so this pins the names here.
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active):
            result = await main._read_portfolio(object())

        self.assertEqual(set(result), {"realEstate", "life"})
        contract = result["life"][0]
        self.assertEqual(set(contract), {
            "contractCode", "currency", "totalValuationEur",
            "valuationDate", "valuationLineCount", "netInvestedEur", "paidInEur",
            "openedAt", "scheduledPayment", "snapshotComplete", "holdings", "operations",
        })
        self.assertEqual(set(contract["operations"][0]), {
            "operationId", "type", "category", "date", "amountEur", "fundCodes",
        })
        self.assertEqual(set(contract["holdings"][0]), {
            "productCode", "label", "quantity", "unitPrice", "valueEur",
            "netInvestedEur", "valuationDate", "euroFund", "assetClass",
        })
        self.assertEqual(set(contract["scheduledPayment"]), {"amountEur", "frequency"})
        self.assertEqual(contract["valuationLineCount"], 10)

    async def test_portfolio_carries_each_contracts_operations(self):
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active):
            result = await main._read_portfolio(object())

        operations = result["life"][0]["operations"]
        self.assertEqual(len(operations), 8)
        self.assertEqual(operations[0]["date"], "2026-10-31")
        # One read for every contract, not one per contract.
        self.assertEqual(self.requested.count(main.OPERATIONS_PATH), 1)

    async def test_an_operations_read_failure_keeps_the_valuation(self):
        """A failed operations read keeps the valuation and leaves operations unread."""
        for failure in (
            HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED"),
            HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE"),
            main.PlaywrightError("Target page, context or browser has been closed"),
        ):
            active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
            with self.subTest(failure=repr(failure)), self._serve(active, **{main.OPERATIONS_PATH: failure}):
                result = await main._read_portfolio(object())

            contract = result["life"][0]
            self.assertIsNone(contract["operations"])
            self.assertEqual(str(contract["totalValuationEur"]), "3506.56")

    async def test_a_browser_failure_on_operations_logs_its_cause_only(self):
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        failure = main.PlaywrightError("page said: IBAN FR76 secret")
        with self._serve(active, **{main.OPERATIONS_PATH: failure}), \
                self.assertLogs("corum-auth", level="WARNING") as logs:
            result = await main._read_portfolio(object())

        self.assertIsNone(result["life"][0]["operations"])
        self.assertNotIn("secret", "".join(logs.output))

    async def test_a_malformed_operations_payload_reports_none_for_every_contract(self):
        payload = operations_payload()
        payload[1]["yearData"][0]["operations"][0]["amount"] = "3.21"
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active, **{main.OPERATIONS_PATH: payload}):
            result = await main._read_portfolio(object())

        self.assertIsNone(result["life"][0]["operations"])

    async def test_a_scpi_only_login_reads_no_operations(self):
        active = [{"contractCode": "RE-1", "contractType": "real_estate"}]
        with self._serve(active):
            result = await main._read_portfolio(object())

        self.assertEqual(result["life"], [])
        self.assertNotIn(main.OPERATIONS_PATH, self.requested)

    async def test_portfolio_carries_a_contract_without_a_savings_plan(self):
        payload = life_payload()
        payload["contractDetail"]["programSavingAmount"] = None
        payload["contractDetail"]["grossInvestedAmount"] = None
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active, **{main.LIFE_DETAIL_PATH.format(code=LIFE_CODE): payload}):
            result = await main._read_portfolio(object())

        contract = result["life"][0]
        self.assertIsNone(contract["scheduledPayment"])
        self.assertIsNone(contract["paidInEur"])

    async def test_a_life_payload_for_another_contract_is_refused(self):
        active = [{"contractCode": "OTHER", "contractType": "LIFE"}]
        other = {main.LIFE_DETAIL_PATH.format(code="OTHER"): life_payload()}
        with self._serve(active, **other):
            with self.assertRaises(PositionsFormatError) as caught:
                await main._read_portfolio(object())

        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    async def test_positions_still_answers_the_scpi_snapshot_only(self):
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
        ]
        with self._serve(active):
            result = await main._read_positions(object())

        self.assertEqual(result["contractCode"], "RE-1")
        self.assertNotIn(main.LIFE_DETAIL_PATH.format(code=LIFE_CODE), self.requested)

    async def test_portfolio_refuses_a_life_code_listed_twice(self):
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
        ]
        with self._serve(active):
            with self.assertRaises(HTTPException) as caught:
                await main._read_portfolio(object())

        self.assertEqual(caught.exception.status_code, 502)
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        # Refused before any contract is read.
        self.assertEqual(self.requested, [main.CONTRACT_PATH])

    async def test_positions_ignores_a_life_code_listed_twice(self):
        # The SCPI-only read never touches the Life part, so it answers as before.
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
        ]
        with self._serve(active):
            result = await main._read_positions(object())

        self.assertEqual(result["contractCode"], "RE-1")

    async def test_portfolio_refuses_a_real_estate_entry_whose_code_is_not_a_string(self):
        # Skipping it would commit the Life part and leave the SCPI funds stale, silently.
        active = [
            {"contractCode": ["RE-1"], "contractType": "real_estate", "valuationAmount": 1.0},
            {"contractCode": LIFE_CODE, "contractType": "LIFE", "valuationAmount": 3506.56},
        ]
        with self._serve(active):
            with self.assertRaises(HTTPException) as caught:
                await main._read_portfolio(object())

        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    async def test_portfolio_refuses_a_life_entry_without_a_code(self):
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": LIFE_CODE, "contractType": "LIFE"},
            {"contractCode": "", "contractType": "LIFE"},
        ]
        with self._serve(active):
            with self.assertRaises(HTTPException) as caught:
                await main._read_portfolio(object())

        self.assertEqual(caught.exception.status_code, 502)
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        # Refused before any contract is read, so nothing is half-reported.
        self.assertEqual(self.requested, [main.CONTRACT_PATH])

    async def test_positions_ignores_a_life_entry_without_a_code(self):
        active = [
            {"contractCode": "RE-1", "contractType": "real_estate"},
            {"contractCode": None, "contractType": "LIFE"},
        ]
        with self._serve(active):
            result = await main._read_positions(object())

        self.assertEqual(result["contractCode"], "RE-1")

    async def test_positions_on_a_life_only_login_is_incomplete(self):
        active = [{"contractCode": LIFE_CODE, "contractType": "LIFE"}]
        with self._serve(active):
            with self.assertRaises(HTTPException) as caught:
                await main._read_positions(object())

        self.assertEqual(caught.exception.status_code, 502)
        self.assertEqual(caught.exception.detail, "PORTFOLIO_INCOMPLETE")


class _TimedOutPage:
    """A page whose in-page fetch hit its abort timer."""

    def __init__(self):
        self.args = None

    async def goto(self, *_args, **_kwargs):
        return None

    async def evaluate(self, script, args):
        self.script = script
        self.args = args
        return {"status": None, "body": None, "timedOut": True}


class _WedgedPage(_TimedOutPage):
    """A page that never answers the evaluation at all."""

    async def evaluate(self, script, args):
        await asyncio.sleep(3600)


class ReadTimeoutTest(unittest.IsolatedAsyncioTestCase):
    """A stalled CORUM read must fail, so its browser slot comes back."""

    async def test_the_in_page_fetch_is_aborted_after_the_read_timeout(self):
        page = _TimedOutPage()
        with self.assertRaises(HTTPException) as caught:
            await main._get_json(page, main.CONTRACT_PATH)

        self.assertEqual(caught.exception.status_code, 502)
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertIn("AbortController", page.script)
        self.assertIn("signal: controller.signal", page.script)
        self.assertEqual(page.args, [main.CONTRACT_PATH, main.READ_TIMEOUT_SECONDS * 1000])

    async def test_the_timeout_is_within_the_navigation_bound(self):
        self.assertGreater(main.READ_TIMEOUT_SECONDS, 0)
        self.assertLess(main.READ_TIMEOUT_SECONDS + main.READ_EVALUATE_GRACE_SECONDS, 30)

    async def test_a_wedged_page_is_bounded_too(self):
        with patch.object(main, "READ_TIMEOUT_SECONDS", 0.01), \
                patch.object(main, "READ_EVALUATE_GRACE_SECONDS", 0):
            with self.assertRaises(HTTPException) as caught:
                await main._get_json(_WedgedPage(), main.CONTRACT_PATH)

        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")

    async def test_a_timed_out_read_releases_its_browser_slot(self):
        class _Starter:
            async def start(self):
                return _FakePlaywright(explode=False)

        async def new_page(_context):
            return _TimedOutPage()

        session = main._encode_session({"cookies": []}, "au_t=x")
        with patch.object(main, "_browsers", 0), \
                patch.object(main, "async_playwright", lambda: _Starter()), \
                patch.object(main, "_new_page", new_page):
            with self.assertRaises(HTTPException) as caught:
                await main._read_in_portal(session, main._read_portfolio)
            browsers = main._browsers

        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")
        self.assertEqual(browsers, 0)


class InvalidBodyTest(unittest.TestCase):
    def test_an_invalid_body_is_400_invalid_data_without_echoing_it(self):
        with patch.dict(os.environ, {"APP_SIDECAR_API_KEY": "k"}), TestClient(main.app) as client:
            response = client.post("/initiate", json={"password": "secret"}, headers={"X-Picsou-Sidecar-Key": "k"})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json(), {"detail": "INVALID_DATA"})


if __name__ == "__main__":
    unittest.main()
