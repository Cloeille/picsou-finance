"""Regression tests for what the live end-to-end run found on 2026-09-26.

Each test here corresponds to a defect that a fixture-based test could not see,
because the fixture was written from the same wrong assumption as the code.
"""
import unittest

import main


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


if __name__ == "__main__":
    unittest.main()
