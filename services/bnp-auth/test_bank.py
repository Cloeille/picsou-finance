"""Allow-list, login outcomes, session state and the browser flows, on fake pages (no network, no browser).

All values are synthetic. Selectors and hosts that were seen on the public login pages are the ones
asserted here; the second factor is a heuristic and is only tested as "never guess".
"""

import asyncio
import base64
import json
import time
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import HTTPException

import bank

BNP = bank.BRANDS["bnp"]
HELLO = bank.BRANDS["hellobank"]
LOGIN = "https://connexion.mabanque.bnpparibas/as/authorization.oauth2?client_id=x"
STEP2 = "https://connexion.mabanque.bnpparibas/as/abc/resume/as/authorization.ping"
SECURE = "https://mabanque.bnpparibas/fr/connexion/comptes"
KEYS = ["3", "8", "1", "0", "5", "9", "2", "7", "4", "6"]
JAR = [{"name": "S", "value": "v", "domain": "mabanque.bnpparibas"}]
# InfosClient once signed in (shape seen live): the code, the status text and a customer block.
SIGNED_IN = {"codeRetour": "0", "message": "OK", "data": {"client": {"ikpiPersonne": "synthetic"}}}


def run(coro):
    return asyncio.run(coro)


def req(url, kind="script", method="GET", body=None, frame="main", redirected_from=None):
    """A Playwright request, synthetic. `kind` is the resource type; a main-frame or sub-frame document is a navigation."""
    request = MagicMock()
    request.url = url
    request.method = method
    request.post_data = body
    request.resource_type = kind
    request.redirected_from = redirected_from
    request.is_navigation_request.return_value = kind == "document"
    request.frame.parent_frame = None if frame == "main" else MagicMock()
    return request


def routed(request):
    route = MagicMock()
    route.request = request
    route.continue_, route.abort = AsyncMock(), AsyncMock()
    return route


class AllowListTest(unittest.TestCase):
    def test_only_exact_https_brand_hosts_pass(self):
        ok = [
            "https://mabanque.bnpparibas/fr/x",
            "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2",
            "https://content.connexion.mabanque.bnpparibas/a.css",
            "https://www.hellobank.fr/",
            "https://connexion.hellobank.fr:443/as/x",
            "https://content.connexion.hellobank.fr/a.css",
            "data:text/plain,a",
            "about:blank",
            # Every host of the dotBrand TLD (only BNP Paribas can register under it).
            "https://mabanqueprivee.bnpparibas/fr/x",
            "https://a.b-c.bnpparibas:443/",
            "https://xmabanque.bnpparibas/",  # a different name under the same TLD: still BNP Paribas'
        ]
        bad = [
            "http://mabanque.bnpparibas/",
            "https://mabanque.bnpparibas.evil.example/",
            "https://evil.example/mabanque.bnpparibas",
            "https://user@mabanque.bnpparibas/",
            "https://mabanque.bnpparibas:8443/",
            "https://hellobank.fr/",  # not an allow-listed host: only www.
            "https://other.hellobank.fr/",  # hellobank.fr stays on exact hosts
            "https://bnpparibas/",  # the TLD apex itself
            "https://evilbnpparibas/",
            "https://xbnpparibas.example/",
            "https://mabanqueprivee.bnpparibas.evil.example/",
            "https://mabanqueprivee.bnpparibas./",
            "https://mabanqueprivee.bnpparibas:8443/",
            "http://mabanqueprivee.bnpparibas/",
            "https://user@mabanqueprivee.bnpparibas/",
            "https://under_score.bnpparibas/",
            "https://mabanqueprivee.bnpparibas.com/",
            "https://assets.adobedtm.com/x.js",
            "https://aa.online-metrix.net/fp.js",
            "https://h64.online-metrix.net/fp/tags.js",
            "https://169.254.169.254/",
            "https://localhost/",
            "ftp://mabanque.bnpparibas/",
            "//mabanque.bnpparibas/",
            "",
            None,
            5,
        ]
        for url in ok:
            with self.subTest(url=url):
                self.assertTrue(bank.is_allowed_url(url))
        for url in bad:
            with self.subTest(url=url):
                self.assertFalse(bank.is_allowed_url(url))

    def test_a_brand_never_reaches_the_other_brands_exact_hosts(self):
        self.assertFalse(bank.is_allowed_url("https://www.hellobank.fr/", BNP.hosts))
        self.assertFalse(bank.is_allowed_url("https://connexion.hellobank.fr/", BNP.hosts))

    def test_the_dotbrand_tld_is_a_brand_host_for_both_brands(self):
        for brand in (BNP, HELLO):
            with self.subTest(brand=brand.name):
                self.assertTrue(bank.is_allowed_url("https://mabanqueprivee.bnpparibas/fr/x", brand.hosts))

    def test_a_navigation_or_post_to_another_bnpparibas_host_loads_and_never_stops_the_attempt(self):
        guard = bank.Guard(BNP.hosts)
        url = "https://mabanqueprivee.bnpparibas/fr/secure/x?code=c&state=s"
        for request in (req(url, "document"), req(url, "xhr", "POST", "{}"), req(url, "script")):
            route = routed(request)
            run(guard.route(route))
            route.continue_.assert_awaited_once()
            route.abort.assert_not_awaited()
        guard.observe(req(url, "document", redirected_from=req(LOGIN, "document")))
        self.assertFalse(guard.tainted)
        self.assertEqual(guard.unknown_hosts, [])

    def test_another_bnpparibas_host_is_logged_once_by_name_only(self):
        guard = bank.Guard(BNP.hosts)
        with self.assertLogs("bnp-auth", level="INFO") as logs:
            for _ in range(3):
                run(guard.route(routed(req("https://mabanqueprivee.bnpparibas/a?token=secret", "document"))))
            run(guard.route(routed(req("https://mabanque.bnpparibas/a", "document"))))
        self.assertEqual(len(logs.output), 1)
        self.assertIn("mabanqueprivee.bnpparibas", logs.output[0])
        self.assertNotIn("secret", logs.output[0])

    def test_every_hop_of_a_redirect_chain_is_checked(self):
        guard = bank.Guard(BNP.hosts)
        first = req(LOGIN, "document")
        clean = req(SECURE, "document", redirected_from=first)
        guard.observe(clean)
        self.assertFalse(guard.tainted)
        hijacked = req(SECURE, "document", redirected_from=req("https://evil.example/x", "document"))
        guard.observe(hijacked)
        self.assertTrue(guard.tainted)
        self.assertEqual(guard.unknown_hosts, ["evil.example"])

    def test_a_redirect_loop_is_cut_off(self):
        hop = req(LOGIN, "document")
        for _ in range(bank.MAX_REDIRECTS + 1):
            hop = req(LOGIN, "document", redirected_from=hop)
        guard = bank.Guard(BNP.hosts)
        guard.observe(hop)
        self.assertTrue(guard.tainted)

    def test_the_unknown_host_log_line_names_the_host_only(self):
        guard = bank.Guard(BNP.hosts)
        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            guard._unknown("https://secure-sms.example.net/otp?token=secret")
        self.assertIn("secure-sms.example.net", logs.output[0])
        self.assertNotIn("secret", logs.output[0])


class VendorPolicyTest(unittest.TestCase):
    """ThreatMetrix loads from the bank's own login page; its sub-resources must load, nothing else may."""

    TMX = (
        "https://h64.online-metrix.net/fp/tags.js?org_id=x&session_id=y",
        "https://cco37scfbztyhx63g3yuc3llnfycgs3blfvtrdnbf5131eafbf5fa653am1.e.aa.online-metrix.net/fp/clear.png",
        "https://aa.online-metrix.net/fp/check.js",
    )

    def decide(self, request, brand=BNP):
        guard = bank.Guard(brand.hosts)
        route = routed(request)
        run(guard.route(route))
        return guard, route

    def test_threatmetrix_sub_resources_of_the_page_load_without_stopping_the_login(self):
        for url in self.TMX:
            for kind in ("script", "image", "xhr", "fetch", "ping", "other"):
                with self.subTest(url=url[:40], kind=kind):
                    guard, route = self.decide(req(url, kind))
                    route.continue_.assert_awaited_once()
                    route.abort.assert_not_awaited()
                    self.assertFalse(guard.tainted)
                    self.assertEqual(guard.unknown_hosts, [])

    def test_a_threatmetrix_iframe_loads_but_a_threatmetrix_page_never_replaces_the_bank_page(self):
        guard, route = self.decide(req(self.TMX[0], "document", frame="child"))
        route.continue_.assert_awaited_once()
        self.assertFalse(guard.tainted)
        guard, route = self.decide(req(self.TMX[0], "document", method="POST", frame="main"))
        route.abort.assert_awaited_once()
        route.continue_.assert_not_awaited()
        self.assertTrue(guard.tainted)
        self.assertEqual(guard.unknown_hosts, ["h64.online-metrix.net"])

    def test_a_form_post_or_navigation_to_any_off_list_host_stops_the_attempt(self):
        for url in ("https://evil.example/login", "https://adobedtm.example/x", self.TMX[1]):
            with self.subTest(url=url[:40]):
                guard, route = self.decide(req(url, "document", method="POST", body="pf.pass.positions=x"))
                route.abort.assert_awaited_once()
                self.assertTrue(guard.tainted)

    def test_a_lookalike_vendor_host_is_not_the_vendor(self):
        for url in (
            "https://evil-online-metrix.net/fp/tags.js",
            "https://online-metrix.net.evil.example/fp/tags.js",
            "https://xonline-metrix.net/fp.js",
            "https://online-metrix.net/fp.js",
            "https://h64.online-metrix.net./fp.js",
            "http://h64.online-metrix.net/fp.js",
            "https://user@h64.online-metrix.net/fp.js",
            "https://h64.online-metrix.net:8443/fp.js",
        ):
            with self.subTest(url=url):
                self.assertIsNone(bank.vendor_of(url))
                guard, route = self.decide(req(url, "script"))
                route.continue_.assert_not_awaited()
                route.abort.assert_awaited_once()
        self.assertEqual(bank.vendor_of(self.TMX[1]), ".online-metrix.net")

    def test_other_third_party_scripts_and_images_are_aborted_silently(self):
        guard = bank.Guard(BNP.hosts)
        for url, kind in (
            ("https://assets.adobedtm.com/launch.min.js", "script"),
            ("https://stats.example.net/pixel.gif", "image"),
            ("https://fonts.example.net/a.woff2", "font"),
            ("https://stats.example.net/beat", "ping"),
        ):
            route = routed(req(url, kind))
            run(guard.route(route))
            route.abort.assert_awaited_once()
            route.continue_.assert_not_awaited()
        self.assertFalse(guard.tainted)
        self.assertEqual(guard.unknown_hosts, [])
        self.assertEqual(guard.blocked, 4)

    # Seen on the signed-in site: the bank's cookie-consent SDK calls these on every landing.
    CONSENT = (
        ("https://geolocation.onetrust.com/cookieconsentpub/v1/geo/location", "xhr", "GET"),
        ("https://cookies-data.onetrust.io/cfw/cmp/v1/session", "fetch", "POST"),
        ("https://bnp-privacy.my.onetrust.com/request/v1/consentreceipts", "xhr", "POST"),
    )

    def test_the_consent_vendor_of_the_signed_in_site_is_dropped_without_stopping_the_login(self):
        guard = bank.Guard(BNP.hosts)
        for url, kind, method in self.CONSENT:
            with self.subTest(url=url[:40]):
                request = req(url, kind, method=method, body="{}" if method == "POST" else None)
                route = routed(request)
                run(guard.route(route))
                route.abort.assert_awaited_once()
                route.continue_.assert_not_awaited()
                guard.observe(request)
        # A finished request whose redirect chain went through a consent host is no stop either.
        guard.observe(req("https://mabanqueprivee.bnpparibas/x.js", "script", redirected_from=req(self.CONSENT[0][0], "xhr")))
        self.assertFalse(guard.tainted)
        self.assertEqual(guard.unknown_hosts, [])
        self.assertEqual(guard.ignored_hosts, [bank.host_of(url) for url, _, _ in self.CONSENT])
        self.assertEqual(guard.vendors_seen, [])

    def test_a_consent_vendor_page_or_a_lookalike_host_still_stops_the_attempt(self):
        guard, route = self.decide(req(self.CONSENT[1][0], "document", method="POST", body="x"))
        route.abort.assert_awaited_once()
        self.assertTrue(guard.tainted)
        # `my.onetrust.com` hosts every OneTrust customer: another tenant is not the bank's.
        for url in ("https://evil-onetrust.io/cfw/cmp/v1/session", "https://onetrust.io.evil.example/x", "https://onetrust.io/x",
                    "https://other-tenant.my.onetrust.com/request/v1/consentreceipts", "https://cdn.onetrust.com/x",
                    "https://x.cookies-data.onetrust.io/cfw/cmp/v1/session"):
            with self.subTest(url=url):
                guard, route = self.decide(req(url, "fetch", method="POST", body="{}"))
                route.abort.assert_awaited_once()
                self.assertTrue(guard.tainted)

    def test_a_third_party_request_that_could_carry_the_identifier_or_the_code_stops_the_attempt(self):
        cases = (
            req("https://stats.example.net/c", "xhr", method="POST", body='{"a":1}'),
            req("https://stats.example.net/c", "fetch", method="PUT"),
            req("https://stats.example.net/c", "ping", method="POST", body="x"),
            req("https://stats.example.net/frame", "document", frame="child"),
        )
        for request in cases:
            with self.subTest(kind=request.resource_type, method=request.method):
                guard, route = self.decide(request)
                route.abort.assert_awaited_once()
                self.assertTrue(guard.tainted)
                self.assertEqual(guard.unknown_hosts, ["stats.example.net"])

    def test_brand_hosts_and_exact_allow_listing_are_unchanged(self):
        for url, kind in ((LOGIN, "document"), (STEP2, "document"), ("https://content.connexion.mabanque.bnpparibas/akam/13/x", "script")):
            guard, route = self.decide(req(url, kind))
            route.continue_.assert_awaited_once()
            self.assertFalse(guard.tainted)
        # The other brand's hosts are off-list for this brand, also as sub-resources with a body.
        guard, route = self.decide(req("https://www.hellobank.fr/x", "xhr", method="POST", body="x"))
        self.assertTrue(guard.tainted)

    def test_observe_does_not_taint_for_vendor_or_ignored_hops_but_does_for_a_navigation_hop(self):
        guard = bank.Guard(BNP.hosts)
        guard.observe(req(self.TMX[0], "script"))
        guard.observe(req("https://assets.adobedtm.com/x.js", "script"))
        self.assertFalse(guard.tainted)
        guard.observe(req(SECURE, "document", redirected_from=req(self.TMX[0], "document", method="GET")))
        self.assertTrue(guard.tainted)

    def test_each_vendor_and_ignored_host_is_logged_once_by_name_only(self):
        guard = bank.Guard(BNP.hosts)
        with self.assertLogs("bnp-auth", level="INFO") as logs:
            for _ in range(3):
                run(guard.route(routed(req(self.TMX[0] + "&secret=1", "script"))))
                run(guard.route(routed(req("https://assets.adobedtm.com/x.js?token=secret", "script"))))
        text = "\n".join(logs.output)
        self.assertEqual(text.count("online-metrix.net"), 1)
        self.assertEqual(text.count("assets.adobedtm.com"), 1)
        self.assertNotIn("secret", text)
        self.assertNotIn("h64", text)


class StageLogTest(unittest.TestCase):
    def test_a_site_changed_failure_names_its_stage_and_nothing_else(self):
        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            exc = bank.fail(502, "UPSTREAM_FORMAT_CHANGED", stage="page_load")
        self.assertEqual(exc.detail, "UPSTREAM_FORMAT_CHANGED")
        self.assertEqual(len(logs.output), 1)
        self.assertIn("stage=page_load", logs.output[0])
        self.assertIn("UPSTREAM_FORMAT_CHANGED", logs.output[0])

    def test_a_clean_attempt_is_silent_and_a_tainted_one_logs_the_stage(self):
        state = {"guard": bank.Guard(BNP.hosts)}
        bank.require_clean(state, "page_load")
        state["guard"]._unknown = MagicMock()
        state["guard"].tainted = True
        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            with self.assertRaises(HTTPException) as caught:
                bank.require_clean(state, "customer_number")
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        self.assertIn("stage=customer_number", logs.output[0])


class LoginOutcomeTest(unittest.TestCase):
    def failure(self, url):
        error = bank.login_failure(url)
        return (error.status_code, error.detail) if error else None

    def test_error_code_parameter_maps_to_codes(self):
        base = "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2?error_code="
        self.assertEqual(self.failure(base + "201"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "21501"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "3"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "202"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "50"), (409, "ACTION_REQUIRED"))
        self.assertEqual(self.failure(base + "1002"), (410, "AUTH_ATTEMPT_EXPIRED"))
        self.assertEqual(self.failure(base + "SecretErrorException"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "LockedAccount"), (429, "RATE_LIMITED"))
        self.assertEqual(self.failure(base + "TechnicalException"), (502, "UPSTREAM_UNAVAILABLE"))

    def test_an_unknown_error_code_is_never_read_as_a_wrong_password(self):
        base = "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2?error_code="
        for value in ("99999", "-7", "NeverSeen", "%00"):
            with self.subTest(value=value):
                self.assertEqual(self.failure(base + value), (502, "UPSTREAM_FORMAT_CHANGED"))

    def test_normal_urls_are_not_failures(self):
        self.assertIsNone(self.failure(LOGIN))
        self.assertIsNone(self.failure(SECURE))

    def test_site_and_login_urls_are_told_apart(self):
        self.assertTrue(bank.on_login(LOGIN, BNP))
        self.assertFalse(bank.on_login(LOGIN, HELLO))
        self.assertTrue(bank.on_site(SECURE, BNP))
        self.assertFalse(bank.on_site("https://mabanque.bnpparibas/auth/login", BNP))
        self.assertFalse(bank.on_site(LOGIN, BNP))

    def test_the_error_code_parameter_is_read_under_both_spellings(self):
        base = "https://mabanqueprivee.bnpparibas/fr/connexion?"
        self.assertEqual(self.failure(base + "errorCode=201"), (401, "INVALID_CREDENTIALS"))
        self.assertEqual(self.failure(base + "errorCode=TechnicalException"), (502, "UPSTREAM_UNAVAILABLE"))

    def test_every_bnpparibas_host_past_the_login_is_the_site(self):
        self.assertTrue(bank.on_site("https://mabanqueprivee.bnpparibas/fr/secure/comptes-et-contrats", BNP))
        self.assertTrue(bank.on_site("https://mabanqueprivee.bnpparibas/fr/secure/comptes-et-contrats", HELLO))
        self.assertFalse(bank.on_site("https://connexion.mabanqueprivee.bnpparibas/as/x/authorization.ping", BNP))
        self.assertFalse(bank.on_site("https://evil.example/fr/secure/x", BNP))
        self.assertFalse(bank.on_site("https://mabanqueprivee.bnpparibas/auth/login", BNP))

    def test_pages_after_the_submit_are_told_apart_by_what_the_url_carries(self):
        cases = {
            "https://connexion.mabanque.bnpparibas/as/x/authorization.oauth2?error_code=201": "error",
            "https://mabanqueprivee.bnpparibas/fr/connexion?errorCode=SecretErrorException": "error",
            "https://mabanqueprivee.bnpparibas/fr/secure/authentification-forte": "second_factor",
            "https://mabanque.bnpparibas/fr/espace-prive/authentification-forte-anr": "second_factor",
            "https://mabanqueprivee.bnpparibas/fr/secure/authentForte?x=1": "second_factor",
            "https://mabanque.bnpparibas/fr/secure/mot-de-passe-expire": "password",
            "https://mabanque.bnpparibas/fr/espace-pro/changer-son-mot-de-passe": "password",
            "https://mabanque.bnpparibas/fr/connexion/100-connexion": "password",
            "https://connexion.mabanque.bnpparibas/as/abc/resume/as/authorization.ping": "login",
            "https://mabanqueprivee.bnpparibas/fr/callback?code=c&state=s": "callback",
            "https://mabanque.bnpparibas/fr/secure/comptes-et-contrats": "site",
            "https://evil.example/fr/secure/authentification-forte": "other",
            "https://mabanque.bnpparibas/fr/secure/authentification-forte?error_code=3": "error",
        }
        for url, expected in cases.items():
            with self.subTest(url=url):
                self.assertEqual(bank.classify_url(url, BNP), expected)

    def test_an_ok_answer_without_the_customer_block_is_a_dead_session(self):
        # Seen live: once the session has idled out, the signed-in site still answers "0" / "OK", with no data.
        self.assertFalse(bank.session_alive({"codeRetour": "0", "message": "OK", "data": None}))
        self.assertFalse(bank.session_alive({"codeRetour": "0", "message": "OK", "data": {}}))
        self.assertFalse(bank.session_alive({"codeRetour": "0", "message": "OK"}))
        self.assertTrue(bank.session_alive({"codeRetour": "0", "message": "OK", "data": {"client": {"ikpiPersonne": "x"}}}))
        self.assertTrue(bank.session_alive({"codeRetour": "0", "message": "OK", "data": [{"client": "x"}]}))

    def test_session_death_is_the_string_minus_ten(self):
        self.assertFalse(bank.session_alive({"codeRetour": "-10", "message": "user not connected"}))
        self.assertFalse(bank.session_alive({"codeRetour": -10}))
        self.assertFalse(bank.session_alive({"codeRetour": "-30"}))
        self.assertFalse(bank.session_alive("nope"))
        self.assertFalse(bank.session_alive({"codeRetour": "7"}))
        self.assertFalse(bank.session_alive({"codeRetour": 0}))
        self.assertFalse(bank.session_alive({"message": "OK", "data": {"client": {}}}))
        self.assertTrue(bank.session_alive(SIGNED_IN))
        self.assertTrue(bank.session_alive({**SIGNED_IN, "codeRetour": 0}))

    def test_second_factor_classification_never_guesses(self):
        self.assertEqual(bank.classify_second_factor(1, "Saisissez le code reçu par SMS"), "sms")
        self.assertEqual(bank.classify_second_factor(0, "Validez dans votre application Clé Digitale"), "app")
        self.assertIsNone(bank.classify_second_factor(0, "Bienvenue"))
        self.assertIsNone(bank.classify_second_factor(2, "code"))


class StructureLogTest(unittest.TestCase):
    def test_a_url_keeps_host_path_shape_and_query_keys_only(self):
        url = ("https://mabanqueprivee.bnpparibas/fr/secure/ABCdef123456/pay/987654/authorization.oauth2"
               "?code=SECRETCODE1&state=xyz789&error_code=201&x#frag=tok")
        text = bank.describe_url(url)
        self.assertEqual(
            text,
            "host=mabanqueprivee.bnpparibas path=/fr/secure/<ID>/pay/<N>/authorization.oauth<N> query=[code,state,error_code,x]",
        )
        for leaked in ("SECRETCODE1", "xyz789", "987654", "ABCdef", "frag", "tok", "201"):
            self.assertNotIn(leaked, text)

    def test_a_foreign_host_is_named_with_its_digits_masked_and_junk_is_not_a_host(self):
        self.assertEqual(bank.describe_url("https://h64.evil.example/a?b=c"), "host=h<N>.evil.example path=/a query=[b]")
        for junk in ("not a url", None, 5, "https://[::1"):
            with self.subTest(junk=junk):
                self.assertTrue(bank.describe_url(junk).startswith("host=<none>"))

    def test_query_keys_that_look_like_values_are_masked(self):
        text = bank.describe_url("https://mabanque.bnpparibas/a?" + "A1b2C3d4E5f6G7h8I9j0K1l2=v&ok=1&" + "k" * 80 + "=v")
        self.assertEqual(text, "host=mabanque.bnpparibas path=/a query=[<K>,ok,<K>]")

    def test_the_structure_line_counts_the_title_and_never_prints_it(self):
        raw = {
            "title": "Bonjour Jean Dupont", "ready": "complete", "frames": 2,
            "forms": [
                {"id": "form123", "fields": [{"n": "otp", "t": "tel"}, {"n": "token9f8a7b6c5d4e3f21", "t": "hidden"}]},
                {"id": "", "fields": []},
            ],
            "landmarks": ["main-content", "acct-1234567890", "Jean Dupont", "x" * 90],
            "types": {"tel": 1, "hidden": 1},
        }
        text = bank.describe_structure(raw)
        self.assertIn("title_len=19", text)
        self.assertIn("form<N>(otp:tel,<X>:hidden)", text)
        self.assertIn("acct-<N>", text)
        self.assertIn("main-content", text)
        for leaked in ("Jean", "Dupont", "1234567890", "9f8a7b6c5d4e3f21", "xxxx"):
            self.assertNotIn(leaked, text)

    def test_a_malformed_answer_is_reported_as_unreadable_not_raised(self):
        for raw in (None, "text", 5, {"forms": "x", "landmarks": 3, "title": None}):
            with self.subTest(raw=raw):
                self.assertIsInstance(bank.describe_structure(raw), str)


class SessionDiagnosticTest(unittest.TestCase):
    def test_the_cookie_line_has_names_domains_and_flags_and_never_a_value_or_an_expiry(self):
        jar = [
            {"name": "SESSIONID", "value": "tok-SECRET-123", "domain": "mabanqueprivee.bnpparibas", "secure": True, "httpOnly": True, "expires": -1},
            {"name": "pref", "value": "SECRET-456", "domain": ".mabanque.bnpparibas", "secure": False, "httpOnly": False, "expires": 1893456000},
            {"name": "9f8e7d6c5b4a39281706f5e4d3c2b1a0", "value": "SECRET-789", "domain": "x.online-metrix.net"},
        ]
        line = bank.describe_cookies(jar, "mabanqueprivee.bnpparibas")
        self.assertIn("cookies=3 sent_to_landing=1", line)
        self.assertIn("<X>@x.online-metrix.net", line)
        self.assertIn("pref@*mabanque.bnpparibas[]", line)
        self.assertEqual(line.count("SESSIONID@mabanqueprivee.bnpparibas[SHT]"), 1)
        for secret in ("SECRET", "1893456000", "tok-"):
            self.assertNotIn(secret, line)

    def test_the_page_path_in_a_session_stop_line_is_cut_before_a_deep_segment(self):
        self.assertEqual(
            bank.shallow_url("https://mabanqueprivee.bnpparibas/fr/clients/carlos/comptes?code=SECRET&a=1"),
            "host=mabanqueprivee.bnpparibas path=/fr/clients/... query=[code,a]",
        )
        self.assertEqual(bank.shallow_url("https://mabanque.bnpparibas/fr/x?y=1"), "host=mabanque.bnpparibas path=/fr/x query=[y]")
        self.assertEqual(bank.shallow_url("https://mabanque.bnpparibas/"), "host=mabanque.bnpparibas path=/ query=[]")
        self.assertEqual(bank.shallow_url(None), "host=<none> path=<none> query=[]")

    def test_a_domain_cookie_travels_to_subdomains_and_a_host_only_one_to_its_own_host_only(self):
        host_only = {"domain": "mabanque.bnpparibas"}
        domain = {"domain": ".mabanque.bnpparibas"}
        self.assertTrue(bank.cookie_sent_to(host_only, "mabanque.bnpparibas"))
        self.assertFalse(bank.cookie_sent_to(host_only, "api.mabanque.bnpparibas"))
        self.assertTrue(bank.cookie_sent_to(domain, "api.mabanque.bnpparibas"))
        self.assertTrue(bank.cookie_sent_to(domain, "mabanque.bnpparibas"))
        self.assertFalse(bank.cookie_sent_to(domain, "mabanqueprivee.bnpparibas"))
        self.assertFalse(bank.cookie_sent_to({}, "mabanque.bnpparibas"))

    def test_a_big_or_malformed_jar_is_capped_and_never_raises(self):
        big = [{"name": f"c{i}", "domain": "mabanque.bnpparibas"} for i in range(bank.MAX_LOGGED_COOKIES + 5)]
        line = bank.describe_cookies(big, None)
        self.assertIn(f"cookies={len(big)} sent_to_landing=0", line)
        self.assertIn("+5]", line)
        for raw in (None, "x", [None, 5, {}]):
            self.assertIsInstance(bank.describe_cookies(raw, "mabanque.bnpparibas"), str)


class SessionStateTest(unittest.TestCase):
    def test_only_the_brands_cookies_survive_a_round_trip(self):
        jar = bank.encode_session(BNP, [
            {"name": "S", "value": "v", "domain": ".mabanque.bnpparibas", "path": "/", "extra": 1},
            {"name": "T", "value": "t", "domain": "connexion.mabanque.bnpparibas"},
            {"name": "X", "value": "x", "domain": "evil.example"},
            {"name": "H", "value": "h", "domain": "www.hellobank.fr"},
        ])
        brand, cookies = bank.decode_session(jar)
        self.assertIs(brand, BNP)
        self.assertEqual([c["name"] for c in cookies], ["S", "T"])
        self.assertNotIn("extra", cookies[0])

    def test_cookies_of_another_bnpparibas_host_are_kept_and_the_tld_apex_is_not(self):
        jar = bank.encode_session(BNP, [
            {"name": "P", "value": "p", "domain": "mabanqueprivee.bnpparibas"},
            {"name": "Q", "value": "q", "domain": ".mabanqueprivee.bnpparibas"},
            {"name": "A", "value": "a", "domain": ".bnpparibas"},
            {"name": "E", "value": "e", "domain": "mabanqueprivee.bnpparibas.evil.example"},
        ])
        _, cookies = bank.decode_session(jar)
        self.assertEqual([c["name"] for c in cookies], ["P", "Q"])

    def test_every_cookie_of_the_banks_domains_is_kept_with_its_subdomains(self):
        for brand, kept, dropped in (
            (BNP, ["mabanque.bnpparibas", ".mabanque.bnpparibas", "api.mabanque.bnpparibas", "mabanqueprivee.bnpparibas",
                   "connexion.mabanque.bnpparibas", "content.connexion.mabanque.bnpparibas"],
             [".bnpparibas", "mabanque.bnpparibas.evil.example", "evilmabanque.bnpparibas.example", "x.online-metrix.net"]),
            (HELLO, ["www.hellobank.fr", ".hellobank.fr", "espace.hellobank.fr", "connexion.hellobank.fr", "mabanqueprivee.bnpparibas"],
             ["evilhellobank.fr", "hellobank.fr.evil.example", "mabanque.fr"]),
        ):
            with self.subTest(brand=brand.name):
                jar = bank.encode_session(brand, [{"name": f"K{i}", "value": "v", "domain": d} for i, d in enumerate(kept + dropped)])
                _, cookies = bank.decode_session(jar)
                self.assertEqual([c["domain"] for c in cookies], kept)

    def test_the_cookie_limit_is_the_same_when_saving_and_restoring(self):
        many = [{"name": f"K{i}", "value": "v", "domain": "mabanque.bnpparibas"} for i in range(bank.MAX_COOKIES)]
        self.assertEqual(len(bank.decode_session(bank.encode_session(BNP, many))[1]), bank.MAX_COOKIES)
        with self.assertRaises(HTTPException) as caught:
            bank.encode_session(BNP, many + [{"name": "Z", "value": "v", "domain": "mabanque.bnpparibas"}])
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_the_landing_host_round_trips_and_an_old_state_without_it_still_restores(self):
        jar = bank.encode_session(BNP, JAR, "mabanqueprivee.bnpparibas")
        self.assertEqual(bank.decode_session_state(jar)[2], "mabanqueprivee.bnpparibas")
        self.assertIsNone(bank.decode_session_state(bank.encode_session(BNP, JAR))[2])
        self.assertIsNone(bank.decode_session_state(bank.encode_session(BNP, JAR, None))[2])

    def test_a_landing_that_is_not_a_site_host_is_dropped_when_saving_and_refused_when_restoring(self):
        for landing in ("evil.example", "connexion.mabanque.bnpparibas", "content.connexion.mabanque.bnpparibas",
                        "mabanque.bnpparibas/x", "MABANQUE.bnpparibas", "", "bnpparibas", "mabanque.bnpparibas:444", "user@mabanque.bnpparibas"):
            with self.subTest(landing=landing):
                self.assertIsNone(bank.decode_session_state(bank.encode_session(BNP, JAR, landing))[2])

                def enc(data):
                    return base64.b64encode(json.dumps(data).encode()).decode()

                with self.assertRaises(HTTPException) as caught:
                    bank.decode_session_state(enc({"v": 2, "brand": "bnp", "cookies": JAR, "landing": landing}))
                self.assertEqual(caught.exception.detail, "INVALID_SESSION_STATE")
        with self.assertRaises(HTTPException):
            bank.decode_session_state(base64.b64encode(json.dumps({"v": 2, "brand": "bnp", "cookies": JAR, "landing": 5}).encode()).decode())

    def test_the_landing_host_is_read_from_the_page_and_never_a_login_or_foreign_host(self):
        self.assertEqual(bank.landing_host("https://mabanqueprivee.bnpparibas/fr/x?code=secret", BNP), "mabanqueprivee.bnpparibas")
        self.assertEqual(bank.landing_host(SECURE, BNP), "mabanque.bnpparibas")
        for url in (LOGIN, STEP2, "https://evil.example/", "http://mabanque.bnpparibas/", None, "https://mabanque.bnpparibas/auth/login"):
            with self.subTest(url=url):
                self.assertIsNone(bank.landing_host(url, BNP))

    def test_nothing_to_keep_is_a_failure_not_an_empty_session(self):
        with self.assertRaises(HTTPException) as caught:
            bank.encode_session(BNP, [{"name": "X", "value": "x", "domain": "evil.example"}])
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_a_tampered_state_is_refused(self):
        def enc(data):
            return base64.b64encode(json.dumps(data).encode()).decode()

        good = {"name": "S", "value": "v", "domain": "mabanque.bnpparibas"}
        for state in (
            "not base64!", enc({"v": 1, "brand": "bnp", "cookies": [good]}), enc({"v": 2, "brand": "other", "cookies": [good]}),
            enc({"v": 2, "brand": "bnp", "cookies": []}),
            enc({"v": 2, "brand": "bnp", "cookies": [{"name": "S", "value": "v", "domain": "evil.example"}]}),
            enc({"v": 2, "brand": "bnp", "cookies": [{"name": 1, "value": "v", "domain": "mabanque.bnpparibas"}]}), enc([]),
        ):
            with self.subTest(state=state[:20]), self.assertRaises(HTTPException) as caught:
                bank.decode_session(state)
            self.assertEqual(caught.exception.detail, "INVALID_SESSION_STATE")


class FakeKeys:
    def __init__(self, page):
        self.page = page

    def nth(self, index):
        button = MagicMock()
        button.click = AsyncMock(side_effect=lambda **_: self.page.pressed.append(index))
        return button


class FakePage:
    """`plan` = [(seconds after the submit, url)]: the page follows the chain on its own clock."""

    def __init__(self, url=LOGIN):
        self._url = url
        self.plan = []
        self.submitted_at = None
        self.results = []
        self.evaluated = []
        self.filled = []
        self.pressed = []
        self.clicked = []
        self.codes = 0
        self.text = ""
        self.on_submit = lambda: None
        self.sms_typed = []

    @property
    def url(self):
        if self.plan and self.submitted_at is not None:
            elapsed = time.monotonic() - self.submitted_at
            due = [url for after, url in self.plan if after <= elapsed]
            if due:
                return due[-1]
        return self._url

    @url.setter
    def url(self, value):
        self._url = value
        self.plan = []

    async def goto(self, url, **_):
        return None  # the real browser follows the redirect; tests set `url` up front

    async def evaluate(self, script, arg=None):
        self.evaluated.append((script, arg))
        return self.results.pop(0)

    async def fill(self, selector, value, **_):
        self.filled.append((selector, value))

    def get_by_role(self, *_, **__):
        button = MagicMock()
        button.click = AsyncMock(side_effect=lambda **_: self.clicked.append("continue"))
        return button

    async def wait_for_selector(self, *_, **__):
        return None

    def locator(self, selector):
        if selector == bank.KEY_BUTTONS:
            return FakeKeys(self)
        field = MagicMock()
        field.fill = AsyncMock(side_effect=lambda value, **_: self.sms_typed.append(value))
        field.press = AsyncMock()
        return MagicMock(first=field)

    async def click(self, selector, **_):
        self.clicked.append(selector)
        self.submitted_at = time.monotonic()
        if self.on_submit:
            self.on_submit()

    async def query_selector_all(self, _):
        return [MagicMock(is_visible=AsyncMock(return_value=True)) for _ in range(self.codes)]

    async def inner_text(self, *_, **__):
        return self.text


def state_for(page, brand=BNP, **extra):
    guard = bank.Guard(brand.hosts)
    context = MagicMock(cookies=AsyncMock(return_value=list(JAR)), add_cookies=AsyncMock())
    return {"brand": brand, "page": page, "guard": guard, "context": context, "created_at": time.time(), **extra}


def form(**over):
    found = {
        "form": True, "user": True, "grid": True, "submit": True, "pad": True, "method": "post",
        "action": "https://connexion.mabanque.bnpparibas/as/abc/resume/as/authorization.ping",
        "keys": list(KEYS), "columns": 5,
    }
    found.update(over)
    return found


class FlowTest(unittest.TestCase):
    def setUp(self):
        self.client = bank.BrowserBankClient()
        for target, value in (("_close", AsyncMock()), ("MIN_LOGIN_GAP", 0), ("POLL_SECONDS", 0.01), ("SETTLE_POLL_SECONDS", 0.02), ("STRUCTURE_RETRY_SECONDS", 0)):
            patcher = patch.object(bank, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def initiate(self, page, *, brand="bnp", results=None, state=None):
        page.results = results if results is not None else [form(), form()]
        with patch.object(bank, "launch", AsyncMock(return_value=state or state_for(page))):
            return run(self.client.initiate("1234567890", brand))

    def test_initiate_returns_ten_digit_keys_and_parks_the_attempt(self):
        page = FakePage()
        result = self.initiate(page)
        self.assertEqual(result["keypad"], {"keys": KEYS, "columns": 5})
        self.assertIn(result["processId"], self.client.pending)
        self.assertEqual(page.filled, [(bank.USER_INPUT, "1234567890")])
        self.assertEqual(page.clicked, ["continue"])
        self.assertNotIn("1234567890", json.dumps(result))

    def test_a_page_that_is_not_the_expected_form_is_a_changed_keypad(self):
        for over in ({"form": False}, {"user": False}, {"grid": False}, {"submit": False}, {"pad": False},
                     {"method": "get"}, {"action": "https://evil.example/as/x/authorization.ping"},
                     {"action": "https://connexion.mabanque.bnpparibas/other"},
                     {"action": "http://connexion.mabanque.bnpparibas/as/x/authorization.ping"}):
            page = FakePage()
            with self.subTest(over=over), self.assertRaises(HTTPException) as caught:
                self.initiate(page, results=[form(**over)])
            self.assertEqual((caught.exception.status_code, caught.exception.detail), (409, "KEYPAD_CHANGED"))
            # A refused page never receives the customer number.
            self.assertEqual(page.filled, [])
        self.assertEqual(len(self.client.pending), 0)

    def test_a_keypad_that_is_not_ten_distinct_digits_in_five_columns_is_refused(self):
        bad = [KEYS[:9], KEYS + ["1"], ["a"] + KEYS[1:], ["1"] * 10, ["12"] + KEYS[1:], [3] + KEYS[1:]]
        for keys in bad:
            with self.subTest(keys=keys), self.assertRaises(HTTPException) as caught:
                self.initiate(FakePage(), results=[form(), form(keys=keys)])
            self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")
        with self.assertRaises(HTTPException):
            self.initiate(FakePage(), results=[form(), form(columns=4)])

    def test_an_unexpected_start_page_is_a_changed_keypad(self):
        page = FakePage("https://mabanque.bnpparibas/auth/login")
        page.goto = AsyncMock()
        with patch.object(bank, "launch", AsyncMock(return_value=state_for(page))), self.assertRaises(HTTPException) as caught:
            run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")

    def test_an_unknown_host_during_the_load_is_reported(self):
        page = FakePage()
        state = state_for(page)
        state["guard"]._unknown("https://sso.unknown.example/x")
        with self.assertRaises(HTTPException) as caught:
            self.initiate(page, state=state)
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def test_unknown_brands_and_a_full_pool_are_refused_before_any_browser(self):
        with self.assertRaises(HTTPException):
            run(self.client.initiate("1234567890", "other"))
        self.client.pending._states.update({str(i): {"created_at": time.time()} for i in range(bank.MAX_PENDING)})
        with patch.object(bank, "launch", AsyncMock()) as launch, self.assertRaises(HTTPException) as caught:
            run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.status_code, 503)
        launch.assert_not_awaited()

    def test_two_logins_in_a_row_are_spaced_out(self):
        with patch.object(bank, "MIN_LOGIN_GAP", 60):
            self.client._last_login = time.monotonic()
            with patch.object(bank, "launch", AsyncMock()) as launch, self.assertRaises(HTTPException) as caught:
                run(self.client.initiate("1234567890", "bnp"))
        self.assertEqual(caught.exception.detail, "RATE_LIMITED")
        launch.assert_not_awaited()

    def park(self, page, stage="keypad", **extra):
        run(self.client.pending.put("p1", state_for(page, stage=stage, **extra)))

    def keypad(self, page, positions, *, alive=None, text="", codes=0):
        page.results = [{"value": "".join(str(p) for p in positions), "enabled": True}]
        page.text, page.codes = text, codes
        self.park(page, submit_url=LOGIN)
        if page.url == LOGIN:
            page.on_submit = lambda: setattr(page, "url", STEP2)
        with patch.object(bank, "probe_session", AsyncMock(return_value=alive)), patch.object(bank, "STABLE_SECONDS", 0.2):
            return run(self.client.keypad("p1", positions))

    def test_the_keypad_positions_are_clicked_in_order_and_submitted_once(self):
        page = FakePage()
        status = self.keypad(page, [0, 9, 2, 3, 4, 5], codes=1, text="code")
        self.assertEqual(status, "CODE_REQUIRED")
        self.assertEqual(page.pressed, [0, 9, 2, 3, 4, 5])
        self.assertEqual(page.clicked.count(bank.SUBMIT_BUTTON), 1)
        # The same attempt cannot send the keypad again.
        with self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")

    def test_the_pages_own_field_must_hold_the_clicked_positions_before_submit(self):
        for pressed in ({"value": "012", "enabled": True}, {"value": "012345", "enabled": False}):
            page = FakePage()
            page.results = [pressed]
            self.park(page, submit_url=LOGIN)
            with self.subTest(pressed=pressed), self.assertRaises(HTTPException) as caught:
                run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
            self.assertEqual(caught.exception.detail, "KEYPAD_CHANGED")
            self.assertNotIn(bank.SUBMIT_BUTTON, page.clicked)

    def test_bad_positions_never_reach_the_page(self):
        for positions in ([0, 1, 2, 3, 4], [0, 1, 2, 3, 4, 10], [0, 1, 2, 3, 4, -1]):
            page = FakePage()
            self.park(page, submit_url=LOGIN)
            with self.subTest(positions=positions), self.assertRaises(HTTPException):
                run(self.client.keypad("p1", positions))
            self.assertEqual(page.pressed, [])

    def test_an_app_approval_page_is_told_apart_from_an_sms_page(self):
        page = FakePage()
        status = self.keypad(page, [0, 1, 2, 3, 4, 5], text="Validez avec votre application Clé Digitale")
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "app")

    def test_an_unclassifiable_strong_authentication_stops_the_login(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as caught:
            self.keypad(page, [0, 1, 2, 3, 4, 5], text="Bienvenue")
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (422, "MFA_TYPE_UNSUPPORTED"))
        self.assertNotIn("p1", self.client.pending)

    def test_a_login_without_second_factor_goes_straight_to_the_session(self):
        page = FakePage(SECURE)
        status = self.keypad(page, [0, 1, 2, 3, 4, 5], alive=True)
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "none")

    def test_a_refusal_after_the_keypad_ends_the_attempt_with_the_banks_reason(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}]
        self.park(page, submit_url=LOGIN)
        page.on_submit = lambda: setattr(page, "url", "https://connexion.mabanque.bnpparibas/as/authorization.oauth2?error_code=201")
        with patch.object(bank, "probe_session", AsyncMock(return_value=False)), self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "INVALID_CREDENTIALS"))
        self.assertNotIn("p1", self.client.pending)

    def chain(self, page, plan, *, alive=None, text="", codes=0, settle=None, fetch=None):
        """Submit once, then the page follows `plan`; returns the keypad() status."""
        page.results = [{"value": "012345", "enabled": True}]
        page.text, page.codes, page.plan = text, codes, plan
        self.park(page, submit_url=LOGIN)
        patches = [patch.object(bank, "STABLE_SECONDS", 0.2)]
        if settle is not None:
            patches.append(patch.object(bank, "SETTLE_SECONDS", settle))
        patches.append(patch.object(bank, "fetch_json", fetch or AsyncMock(return_value={"codeRetour": "-10"})))
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)
        return run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))

    CALLBACK = "https://mabanqueprivee.bnpparibas/fr/callback?code=c&state=s"
    FORTE = "https://mabanqueprivee.bnpparibas/fr/secure/authentification-forte"

    def test_the_chain_through_the_private_banking_host_ends_on_an_sms_code_page(self):
        page = FakePage()
        status = self.chain(page, [(0, STEP2), (0.05, self.CALLBACK), (0.15, self.FORTE)], codes=1, text="code")
        self.assertEqual(status, "CODE_REQUIRED")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "sms")

    def test_the_chain_through_the_private_banking_host_ends_on_an_app_approval_page(self):
        page = FakePage()
        status = self.chain(page, [(0, STEP2), (0.05, self.CALLBACK), (0.15, self.FORTE)], text="Validez dans votre application Clé Digitale")
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "app")

    def test_a_callback_that_never_moves_on_is_a_timeout_not_a_second_factor(self):
        page = FakePage()
        with self.assertRaises(HTTPException) as caught:
            self.chain(page, [(0, STEP2), (0.05, self.CALLBACK)], codes=1, text="code", settle=1.0)
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (409, "KEYPAD_CHANGED"))

    def test_a_landing_on_another_bnpparibas_host_with_a_session_is_a_login(self):
        page = FakePage()
        landing = "https://mabanqueprivee.bnpparibas/fr/secure/comptes-et-contrats"
        fetch = AsyncMock(return_value=SIGNED_IN)
        status = self.chain(page, [(0, STEP2), (0.05, self.CALLBACK), (0.1, landing)], fetch=fetch)
        self.assertEqual(status, "APPROVAL_PENDING")
        self.assertEqual(self.client.pending._states["p1"]["kind"], "none")
        fetch.assert_awaited()

    def test_an_expired_password_page_is_an_action_for_the_member(self):
        page = FakePage()
        expired = "https://mabanqueprivee.bnpparibas/fr/secure/mot-de-passe-expire"
        with self.assertLogs("bnp-auth", level="WARNING") as logs, self.assertRaises(HTTPException) as caught:
            self.chain(page, [(0, STEP2), (0.05, expired)])
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (409, "ACTION_REQUIRED"))
        self.assertIn("stage=password_expired", logs.output[0])
        self.assertNotIn("p1", self.client.pending)

    def test_a_refusal_under_the_other_spelling_of_the_error_parameter(self):
        page = FakePage()
        refused = "https://mabanqueprivee.bnpparibas/fr/connexion?errorCode=201"
        with self.assertRaises(HTTPException) as caught:
            self.chain(page, [(0, STEP2), (0.05, refused)])
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "INVALID_CREDENTIALS"))

    def test_a_strong_authentication_page_nobody_can_classify_stops_with_a_structure_line(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}, {
            "title": 31, "ready": "complete", "frames": 1,
            "forms": [{"id": "otpForm", "fields": [{"n": "tokenField", "t": "hidden"}]}],
            "landmarks": ["main-content"], "types": {"hidden": 1},
        }]
        self.park(page, submit_url=LOGIN)
        page.plan = [(0, STEP2), (0.05, self.FORTE + "/12345678?ref=SECRETREF&state=SECRETSTATE")]
        with (
            self.assertLogs("bnp-auth", level="WARNING") as logs,
            patch.object(bank, "STABLE_SECONDS", 0.2),
            patch.object(bank, "fetch_json", AsyncMock(return_value={"codeRetour": "-10"})),
            self.assertRaises(HTTPException) as caught,
        ):
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "MFA_TYPE_UNSUPPORTED")
        lines = [line for line in logs.output if "page structure" in line]
        self.assertEqual(len(lines), 1)
        text = "\n".join(logs.output)
        for leaked in ("SECRETREF", "SECRETSTATE", "12345678"):
            self.assertNotIn(leaked, text)
        self.assertIn("stage=second_factor", lines[0])
        self.assertIn("host=mabanqueprivee.bnpparibas", lines[0])
        self.assertIn("otpForm", lines[0])
        self.assertIn("title_len=31", lines[0])

    def test_a_stop_before_the_submit_logs_no_structure(self):
        page = FakePage()
        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            with self.assertRaises(HTTPException):
                self.initiate(page, results=[form(form=False)])
        self.assertFalse([line for line in logs.output if "page structure" in line])

    def test_a_page_that_cannot_be_read_never_hides_the_original_stop(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}]  # no structure answer: evaluate raises
        self.park(page, submit_url=LOGIN)
        page.plan = [(0, STEP2), (0.05, "https://mabanque.bnpparibas/fr/secure/mot-de-passe-expire")]
        with (
            self.assertLogs("bnp-auth", level="WARNING") as logs,
            patch.object(bank, "fetch_json", AsyncMock(return_value={"codeRetour": "-10"})),
            self.assertRaises(HTTPException) as caught,
        ):
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "ACTION_REQUIRED")
        self.assertTrue([line for line in logs.output if "page structure unavailable" in line])

    def test_a_redirect_to_an_unknown_host_after_submit_is_reported_not_followed(self):
        page = FakePage()
        page.results = [{"value": "012345", "enabled": True}]
        state = state_for(page, stage="keypad", submit_url=LOGIN)
        run(self.client.pending.put("p1", state))
        page.on_submit = lambda: state["guard"]._unknown("https://mfa.unknown.example/x")
        with patch.object(bank, "probe_session", AsyncMock(return_value=None)), self.assertRaises(HTTPException) as caught:
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")
        self.assertNotIn("p1", self.client.pending)

    def test_a_tainted_browser_never_clicks(self):
        page = FakePage()
        state = state_for(page, stage="keypad", submit_url=LOGIN)
        state["guard"].tainted = True
        run(self.client.pending.put("p1", state))
        with self.assertRaises(HTTPException):
            run(self.client.keypad("p1", [0, 1, 2, 3, 4, 5]))
        self.assertEqual(page.pressed, [])

    def test_complete_without_a_matching_attempt_is_expired(self):
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("nope", None))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")
        self.park(FakePage())  # still at the keypad stage
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "123456"))
        self.assertEqual(caught.exception.detail, "AUTH_ATTEMPT_EXPIRED")

    def test_an_sms_login_needs_a_code_and_returns_only_the_cookie_jar(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="sms")
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual(caught.exception.detail, "INVALID_OTP")
        self.park(page, stage="mfa", kind="sms")
        with patch.object(bank, "probe_session", AsyncMock(return_value=True)):
            jar = run(self.client.complete("p1", "123456"))
        self.assertEqual(page.sms_typed, ["123456"])
        self.assertNotIn("123456", jar)
        self.assertIs(bank.decode_session(jar)[0], BNP)
        self.assertNotIn("p1", self.client.pending)

    def test_a_wrong_sms_code_keeps_the_attempt_for_one_more_try(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="sms")
        with patch.object(bank, "SMS_SETTLE_SECONDS", 0.05), patch.object(bank, "probe_session", AsyncMock(return_value=False)), \
                self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "000000"))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "INVALID_OTP"))
        self.assertIn("p1", self.client.pending)

    def test_an_app_approval_is_waited_for_then_returns_the_jar(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        answers = iter([False, False, True])
        with patch.object(bank, "probe_session", AsyncMock(side_effect=lambda *_: next(answers))):
            jar = run(self.client.complete("p1", None))
        self.assertIs(bank.decode_session(jar)[0], BNP)

    def test_an_app_approval_that_never_comes_times_out_and_closes_the_browser(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        with patch.object(bank, "APP_APPROVAL_SECONDS", 0.05), patch.object(bank, "probe_session", AsyncMock(return_value=False)), \
                self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (408, "APP_VALIDATION_TIMEOUT"))
        self.assertNotIn("p1", self.client.pending)

    def test_a_code_sent_to_an_app_approval_is_refused_and_never_typed(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="app")
        with self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", "123456"))
        self.assertEqual(caught.exception.detail, "INVALID_REQUEST")
        self.assertEqual(page.sms_typed, [])

    def test_a_login_that_never_reaches_a_session_is_not_a_session(self):
        page = FakePage()
        self.park(page, stage="mfa", kind="none")
        with patch.object(bank, "probe_session", AsyncMock(return_value=False)), self.assertRaises(HTTPException) as caught:
            run(self.client.complete("p1", None))
        self.assertEqual(caught.exception.detail, "UPSTREAM_FORMAT_CHANGED")

    def accounts(self, page, fetch):
        jar = bank.encode_session(BNP, JAR)
        page.goto = AsyncMock()
        st = state_for(page)
        with patch.object(bank, "launch", AsyncMock(return_value=st)), patch.object(bank, "fetch_json", fetch):
            return run(self.client.accounts(jar)), st

    def test_accounts_with_a_dead_session_asks_for_a_new_login(self):
        async def fetch(*_a, **_k):
            raise AssertionError("not asked off the secure site")

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(LOGIN), fetch)  # the bank bounced back to the login page
        self.assertEqual((caught.exception.status_code, caught.exception.detail), (401, "SESSION_EXPIRED"))

    def test_accounts_with_the_minus_ten_answer_asks_for_a_new_login(self):
        async def fetch(_page, key, body=None):
            return {"codeRetour": "-10", "message": "user not connected"}

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(caught.exception.detail, "SESSION_EXPIRED")

    def test_accounts_returns_the_snapshot_and_the_rotated_jar(self):
        answers = {
            bank.INFOS_CLIENT: SIGNED_IN,
            "ibans": {"codeRetour": 0, "data": {"listeRib": []}},
            "accounts": {"codeRetour": 0, "data": {"infoUdc": {"familleCompte": [
                {"idFamilleCompte": "8", "compte": [{"key": "k", "value": "1234", "libelleProduit": "Credit immobilier", "soldeDispo": -10}]}]}}},
        }

        async def fetch(_page, key, body=None):
            return answers[key]

        result, st = self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(result["brand"], "bnp")
        self.assertEqual([(a["kind"], a["balance"]) for a in result["accounts"]], [("LOAN", "-10.00")])
        self.assertIs(bank.decode_session(result["sessionState"])[0], BNP)
        st["context"].add_cookies.assert_awaited_once()

    def test_a_dead_session_during_the_snapshot_is_not_an_incomplete_account(self):
        async def fetch(_page, key, body=None):
            if key == bank.INFOS_CLIENT:
                return SIGNED_IN
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        with self.assertRaises(HTTPException) as caught:
            self.accounts(FakePage(SECURE), fetch)
        self.assertEqual(caught.exception.detail, "SESSION_EXPIRED")


    # -- the first snapshot and the session stages ---------------------------------

    ANSWERS = {
        bank.INFOS_CLIENT: SIGNED_IN,
        "ibans": {"codeRetour": 0, "data": {"listeRib": []}},
        "accounts": {"codeRetour": 0, "data": {"infoUdc": {"familleCompte": [
            {"idFamilleCompte": "8", "compte": [{"key": "k", "value": "1234", "libelleProduit": "Credit immobilier", "soldeDispo": -10}]}]}}},
    }
    SESSION_JAR = [
        {"name": "SESSIONID", "value": "tok-SECRET-123", "domain": "mabanqueprivee.bnpparibas", "path": "/", "secure": True, "httpOnly": True, "expires": -1},
        {"name": "PREF", "value": "SECRET-456", "domain": ".mabanque.bnpparibas", "path": "/", "secure": True, "expires": 1893456000},
    ]

    def logged_in_page(self, url="https://mabanqueprivee.bnpparibas/fr/comptes?code=SECRET-QUERY"):
        page = FakePage(url)
        page.goto = AsyncMock()
        return page

    def complete_none(self, page, fetch, *, jar=None):
        """/complete of a login without second factor: the page is already signed in."""
        st = state_for(page, stage="mfa", kind="none")
        st["context"].cookies = AsyncMock(return_value=list(jar if jar is not None else self.SESSION_JAR))
        run(self.client.pending.put("p1", st))
        with patch.object(bank, "fetch_json", fetch), patch.object(bank, "probe_session", AsyncMock(return_value=True)):
            return run(self.client.complete("p1", None)), st

    def answering(self, calls=None):
        async def fetch(_page, key, body=None):
            if calls is not None:
                calls.append(key)
            return self.ANSWERS[key]

        return fetch

    def test_complete_reads_the_first_snapshot_in_the_browser_that_logged_in_and_accounts_returns_it(self):
        calls = []
        page = self.logged_in_page()
        session, st = self.complete_none(page, self.answering(calls))
        self.assertIn(bank.INFOS_CLIENT, calls)
        self.assertIn("accounts", calls)
        # The first snapshot was read before the browser closed: the browser closed only after it.
        self.assertEqual(bank.decode_session_state(session)[2], "mabanqueprivee.bnpparibas")
        calls.clear()
        launch = AsyncMock(side_effect=AssertionError("no second browser for the first snapshot"))
        with patch.object(bank, "launch", launch), patch.object(bank, "fetch_json", self.answering(calls)):
            result = run(self.client.accounts(session))
        self.assertEqual(calls, [])
        self.assertEqual([(a["kind"], a["balance"]) for a in result["accounts"]], [("LOAN", "-10.00")])
        self.assertEqual(result["sessionState"], session)
        self.assertEqual(result["brand"], "bnp")

    def test_the_first_snapshot_serves_one_accounts_call_only_then_a_fresh_browser_is_used(self):
        session, _ = self.complete_none(self.logged_in_page(), self.answering())
        with patch.object(bank, "launch", AsyncMock(side_effect=AssertionError)):
            run(self.client.accounts(session))
        page = self.logged_in_page()
        st = state_for(page)
        with patch.object(bank, "launch", AsyncMock(return_value=st)) as launch, patch.object(bank, "fetch_json", self.answering()):
            run(self.client.accounts(session))
        launch.assert_awaited_once()

    def test_an_old_first_snapshot_or_another_session_is_not_served(self):
        session, _ = self.complete_none(self.logged_in_page(), self.answering())
        page = self.logged_in_page()
        with patch.object(bank, "SNAPSHOT_CACHE_SECONDS", -1):
            self.assertEqual(len(self.client._first), 1)
            self.client._first = {k: (0.0, v[1]) for k, v in self.client._first.items()}
        st = state_for(page)
        with patch.object(bank, "launch", AsyncMock(return_value=st)) as launch, patch.object(bank, "fetch_json", self.answering()):
            run(self.client.accounts(session))
        launch.assert_awaited_once()
        other = bank.encode_session(BNP, JAR)
        self.assertIsNone(self.client._take(other))

    def test_the_cache_is_bounded(self):
        for index in range(bank.MAX_PENDING + 3):
            self.client._remember(f"s{index}", ([], []))
        self.assertEqual(len(self.client._first), bank.MAX_PENDING)
        self.assertIsNone(self.client._take("s0"))
        self.assertEqual(self.client._take(f"s{bank.MAX_PENDING + 2}"), ([], []))

    def test_a_first_snapshot_that_cannot_be_read_never_fails_the_login(self):
        for failing in (
            "unavailable",
            RuntimeError("boom with SECRET-VALUE"),
        ):
            with self.subTest(failing=type(failing).__name__):
                async def fetch(*_a, **_k):
                    raise bank.fail(502, "UPSTREAM_UNAVAILABLE", stage="snapshot_fetch") if failing == "unavailable" else failing

                with self.assertLogs("bnp-auth", level="WARNING") as logs:
                    session, _ = self.complete_none(self.logged_in_page(), fetch)
                self.assertEqual(bank.decode_session_state(session)[2], "mabanqueprivee.bnpparibas")
                self.assertEqual(len(self.client._first), 0)
                self.assertNotIn("SECRET", "\n".join(logs.output))

    def test_a_first_snapshot_that_hangs_is_cut_off_and_the_login_still_completes(self):
        async def hang(*_a, **_k):
            await asyncio.sleep(10)

        with patch.object(bank, "FIRST_SNAPSHOT_SECONDS", 0.05), self.assertLogs("bnp-auth", level="WARNING") as logs:
            session, _ = self.complete_none(self.logged_in_page(), hang)
        self.assertTrue(session)
        self.assertEqual(len(self.client._first), 0)
        self.assertIn("stage=snapshot_same_browser_timeout", "\n".join(logs.output))

    def test_a_late_app_approval_leaves_the_first_snapshot_only_the_time_that_is_left(self):
        st = state_for(self.logged_in_page(), stage="mfa", kind="app")
        st["context"].cookies = AsyncMock(return_value=list(self.SESSION_JAR))
        run(self.client.pending.put("p1", st))

        async def approved_late(*_a):
            await asyncio.sleep(0.2)
            return True

        async def hang(*_a, **_k):
            await asyncio.sleep(10)

        started = time.monotonic()
        with patch.object(bank, "COMPLETE_SECONDS", 0.3), patch.object(bank, "FIRST_SNAPSHOT_SECONDS", 5), \
                patch.object(bank, "probe_session", approved_late), patch.object(bank, "fetch_json", hang), \
                self.assertLogs("bnp-auth", level="WARNING") as logs:
            session = run(self.client.complete("p1", None))
        # The backend stops waiting for /complete after a fixed time: the snapshot never runs past it.
        self.assertLess(time.monotonic() - started, 2.5)
        self.assertTrue(session)
        self.assertIn("stage=snapshot_same_browser_timeout", "\n".join(logs.output))

    def test_an_approval_that_uses_all_the_time_skips_the_first_snapshot_and_the_login_completes(self):
        st = state_for(self.logged_in_page(), stage="mfa", kind="app")
        st["context"].cookies = AsyncMock(return_value=list(self.SESSION_JAR))
        run(self.client.pending.put("p1", st))
        asked = []

        async def approved_late(*_a):
            await asyncio.sleep(0.1)
            return True

        async def fetch(_page, key, body=None):
            asked.append(key)
            return self.ANSWERS[key]

        with patch.object(bank, "COMPLETE_SECONDS", 0.05), patch.object(bank, "probe_session", approved_late), \
                patch.object(bank, "fetch_json", fetch), self.assertLogs("bnp-auth", level="WARNING") as logs:
            session = run(self.client.complete("p1", None))
        self.assertEqual(bank.decode_session_state(session)[2], "mabanqueprivee.bnpparibas")
        self.assertEqual(asked, [])
        self.assertEqual(len(self.client._first), 0)
        self.assertIn("stage=snapshot_same_browser_timeout", "\n".join(logs.output))

    def test_a_tainted_browser_drops_the_first_snapshot(self):
        page = self.logged_in_page()
        st = state_for(page, stage="mfa", kind="none")
        run(self.client.pending.put("p1", st))

        async def fetch(_page, key, body=None):
            st["guard"].tainted = True
            return self.ANSWERS[key]

        with patch.object(bank, "fetch_json", fetch), patch.object(bank, "probe_session", AsyncMock(return_value=True)):
            session = run(self.client.complete("p1", None))
        self.assertTrue(session)
        self.assertEqual(len(self.client._first), 0)

    def test_accounts_opens_the_landing_host_the_login_ended_on_and_the_site_home_for_an_old_state(self):
        for landing, home in (("mabanqueprivee.bnpparibas", "https://mabanqueprivee.bnpparibas/"), (None, "https://mabanque.bnpparibas/")):
            with self.subTest(landing=landing):
                page = FakePage("https://mabanqueprivee.bnpparibas/fr/x")
                page.goto = AsyncMock()
                st = state_for(page)
                session = bank.encode_session(BNP, self.SESSION_JAR, landing)
                with patch.object(bank, "launch", AsyncMock(return_value=st)), patch.object(bank, "fetch_json", self.answering()):
                    result = run(self.client.accounts(session))
                self.assertEqual(page.goto.await_args.args[0], home)
                # What the page ended on is remembered for the next call.
                self.assertEqual(bank.decode_session_state(result["sessionState"])[2], "mabanqueprivee.bnpparibas")

    def stopped(self, page, fetch, *, jar=None, landing="mabanqueprivee.bnpparibas"):
        st = state_for(page)
        st["context"].cookies = AsyncMock(return_value=list(jar if jar is not None else self.SESSION_JAR))
        session = bank.encode_session(BNP, self.SESSION_JAR, landing)
        with patch.object(bank, "launch", AsyncMock(return_value=st)), patch.object(bank, "fetch_json", fetch), \
                self.assertLogs("bnp-auth", level="WARNING") as logs, self.assertRaises(HTTPException) as caught:
            run(self.client.accounts(session))
        return caught.exception, "\n".join(logs.output)

    def assert_structure_only(self, text):
        for secret in ("SECRET", "1893456000", "tok-", "1234"):
            self.assertNotIn(secret, text)

    def test_stage_off_site_names_where_the_page_landed_and_the_cookie_jar_without_values(self):
        async def fetch(*_a, **_k):
            raise AssertionError("never asked off the site")

        exc, text = self.stopped(FakePage("https://connexion.mabanque.bnpparibas/as/authorization.oauth2?client_id=SECRET-1&state=SECRET-2"), fetch)
        self.assertEqual((exc.status_code, exc.detail, exc.stage), (401, "SESSION_EXPIRED", "snapshot_off_site"))
        self.assertIn("stage=snapshot_off_site phase=fresh_browser", text)
        self.assertIn("landing=mabanqueprivee.bnpparibas", text)
        self.assertIn("host=connexion.mabanque.bnpparibas path=/as/authorization.oauth<N> query=[client_id,state] kind=login", text)
        self.assertIn("cookies=2 sent_to_landing=1", text)
        self.assertIn("SESSIONID@mabanqueprivee.bnpparibas[SHT]", text)
        self.assert_structure_only(text)

    def test_stage_info_dead_when_the_bank_says_nobody_is_signed_in(self):
        for code in ("-10", "-30", -10):
            with self.subTest(code=code):
                async def fetch(_page, key, body=None):
                    return {"codeRetour": code, "message": "user not connected"}

                exc, text = self.stopped(self.logged_in_page(), fetch)
                self.assertEqual((exc.status_code, exc.detail, exc.stage), (401, "SESSION_EXPIRED", "snapshot_info_dead"))
                self.assertIn("stage=snapshot_info_dead phase=fresh_browser", text)
                self.assertIn("host=mabanqueprivee.bnpparibas path=/fr/comptes query=[code] kind=site", text)
                self.assert_structure_only(text)

    def test_stage_info_dead_when_the_bank_answers_ok_without_a_customer(self):
        async def fetch(_page, key, body=None):
            if key == bank.INFOS_CLIENT:
                return {"codeRetour": "0", "message": "OK", "data": None}
            return {"codeRetour": 99, "message": "Une erreur est survenue", "data": None}

        exc, text = self.stopped(self.logged_in_page(), fetch)
        self.assertEqual((exc.status_code, exc.detail, exc.stage), (401, "SESSION_EXPIRED", "snapshot_info_dead"))
        self.assertIn("stage=snapshot_info_dead phase=fresh_browser", text)

    def test_stage_info_dead_when_a_data_answer_says_the_session_is_over(self):
        async def fetch(_page, key, body=None):
            if key == bank.INFOS_CLIENT:
                return self.ANSWERS[key]
            return {"codeRetour": "-10", "message": "user not connected"}

        exc, text = self.stopped(self.logged_in_page(), fetch)
        self.assertEqual((exc.detail, exc.stage), ("SESSION_EXPIRED", "snapshot_info_dead"))

    def test_stage_http_401_when_the_bank_refuses_a_request(self):
        for status in (401, 403):
            with self.subTest(status=status):
                page = self.logged_in_page()

                async def refused(_page, key, body=None):
                    raise bank.fail(401, "SESSION_EXPIRED")  # what fetch_json raises for a 401 / 403

                exc, text = self.stopped(page, refused)
                self.assertEqual((exc.status_code, exc.detail, exc.stage), (401, "SESSION_EXPIRED", "snapshot_http_401"))
                self.assertIn("stage=snapshot_http_401 phase=fresh_browser", text)
                self.assert_structure_only(text)

    def test_stage_http_401_when_a_data_request_is_refused_after_the_info_call_answered(self):
        async def fetch(_page, key, body=None):
            if key == bank.INFOS_CLIENT:
                return self.ANSWERS[key]
            raise HTTPException(status_code=401, detail="SESSION_EXPIRED")

        exc, _ = self.stopped(self.logged_in_page(), fetch)
        self.assertEqual((exc.detail, exc.stage), ("SESSION_EXPIRED", "snapshot_http_401"))

    def test_fetch_json_maps_a_401_to_the_session_code_through_the_real_function(self):
        for status in (401, 403):
            page = self.logged_in_page()
            page.results = [{"status": status, "text": ""}]
            st = state_for(page)
            session = bank.encode_session(BNP, self.SESSION_JAR, "mabanqueprivee.bnpparibas")
            with patch.object(bank, "launch", AsyncMock(return_value=st)), self.assertLogs("bnp-auth", level="WARNING") as logs, \
                    self.assertRaises(HTTPException) as caught:
                run(self.client.accounts(session))
            self.assertEqual(caught.exception.stage, "snapshot_http_401")
            self.assertTrue(any("stage=snapshot_http_401" in line for line in logs.output))

    def test_the_stages_of_the_first_snapshot_say_it_was_the_same_browser(self):
        async def fetch(_page, key, body=None):
            return {"codeRetour": "-10"}

        with self.assertLogs("bnp-auth", level="WARNING") as logs:
            session, _ = self.complete_none(self.logged_in_page(), fetch)
        self.assertTrue(session)
        text = "\n".join(logs.output)
        self.assertIn("stage=snapshot_info_dead phase=same_browser", text)
        self.assert_structure_only(text)

    def test_other_session_failures_keep_their_codes(self):
        async def broken(_page, key, body=None):
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

        with patch.object(bank, "launch", AsyncMock(return_value=state_for(self.logged_in_page()))), \
                patch.object(bank, "fetch_json", broken), self.assertRaises(HTTPException) as caught:
            run(self.client.accounts(bank.encode_session(BNP, self.SESSION_JAR, None)))
        self.assertEqual(caught.exception.detail, "UPSTREAM_UNAVAILABLE")


class FetchTest(unittest.TestCase):
    def fetch(self, result):
        page = FakePage(SECURE)
        page.results = [result]
        return run(bank.fetch_json(page, "accounts"))

    def test_status_mapping(self):
        self.assertEqual(self.fetch({"status": 200, "text": '{"codeRetour": 0}'})["codeRetour"], 0)
        for status, detail in ((401, "SESSION_EXPIRED"), (403, "SESSION_EXPIRED"), (500, "UPSTREAM_UNAVAILABLE")):
            with self.subTest(status=status), self.assertRaises(HTTPException) as caught:
                self.fetch({"status": status, "text": ""})
            self.assertEqual(caught.exception.detail, detail)
        with self.assertRaises(HTTPException):
            self.fetch(None)

    def test_only_the_fixed_endpoints_are_ever_requested(self):
        self.assertTrue(all(method in ("GET", "POST") and path.startswith("/") and "//" not in path
                            for method, path in bank.ENDPOINTS.values()))
        self.assertEqual({path.split("/")[1] for _, path in bank.ENDPOINTS.values()},
                         {"udc-wspl", "rib-wspl", "rop2-wspl", "pe-war"})


if __name__ == "__main__":
    unittest.main()
