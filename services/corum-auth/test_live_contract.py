"""Regression tests for what the live end-to-end run found on 2026-09-26.

Each test here corresponds to a defect that a fixture-based test could not see,
because the fixture was written from the same wrong assumption as the code.
"""
import unittest
from unittest.mock import patch

import main


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
        import inspect

        for handler in (main.initiate, main.positions):
            source = inspect.getsource(handler)
            self.assertIn("finally:", source, f"{handler.__name__} has no finally")
            self.assertEqual(
                source.count("_close_resources("), 1,
                f"{handler.__name__} closes from more than one place",
            )


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


if __name__ == "__main__":
    unittest.main()
