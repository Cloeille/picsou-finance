"""Banque Populaire browser login, played against the shared fake Playwright.

The flow is the shared BPCE one (tested at length by caisse-epargne-auth); these
tests cover what this site adds or changes: the establishment picker before the
identifier, the consent banner, the hosts, and the cookies kept. No browser, no
network, no real credential or bank data: every value below is synthetic.
"""

import json
import unittest
from dataclasses import replace
from unittest.mock import patch

from bpce import login, replay
from bpce.testing import COOKIE_BANNER_BUTTON, World

import main

CUSTOMER_ID = "7305918264"
POSITIONS = [3, 0, 5, 2, 7, 9, 1]
REGION = "RIVES_DE_PARIS"
LABEL = "RIVES DE PARIS"
LABELS = [label for _, label in main.REGIONS]

LOGIN_HOST = "https://www.icgauth.banquepopulaire.fr"
# The client-space authorize, as observed (2026-11-05) but with synthetic values.
AUTHORIZE_URL = (
    "https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/authorize"
    "?client_id=00000000-0000-4000-8000-000000000001"
    "&redirect_uri=https%3A%2F%2Fwww.banquepopulaire.fr%2Fespace-client%2FpostMessage%2Findex.html"
    "&scope=&response_type=code&cdetab=00000&claims=%7B%7D&bpcesta=SYNTHETIC-BPCESTA"
    "&login_hint=SECRET-LOGIN-HINT&display=page"
    "&code_challenge=stale-challenge&code_challenge_method=S256&nonce=stale-nonce"
)
# The login pages' own authorize (another client, implicit flow), made on icgauth
# before the client space: it must never be captured.
DECOY_AUTHORIZE_URL = (
    f"{LOGIN_HOST}/api/oauth/v2/authorize?client_id=synthetic-login-pages-client"
    "&response_type=id_token%20token&redirect_uri=https%3A%2F%2Fwww.icgauth.banquepopulaire.fr%2Fcb"
)


def bp_world() -> World:
    world = World(
        identifier_url=f"{LOGIN_HOST}/se-connecter/identifier",
        keys_url=f"{LOGIN_HOST}/se-connecter/icg/mot-de-passe",
        client_url="https://www.banquepopulaire.fr/espace-client/synthese",
        error_url="https://www.banquepopulaire.fr/erreur",
        authorize_url=AUTHORIZE_URL,
        decoy_authorize_url=DECOY_AUTHORIZE_URL,
    )
    world.region_options = list(LABELS)
    world.cookies = [
        {"name": "SSO", "value": "BP-COOKIE-SECRET-1", "domain": ".banquepopulaire.fr", "path": "/"},
        {"name": "SESSION", "value": "BP-COOKIE-SECRET-2", "domain": "www.banquepopulaire.fr",
         "path": "/espace-client"},
        {"name": "ce", "value": "other-bank", "domain": ".caisse-epargne.fr", "path": "/"},
        {"name": "group", "value": "group", "domain": ".bpce.fr", "path": "/"},
        {"name": "lookalike", "value": "evil", "domain": "evilbanquepopulaire.fr", "path": "/"},
    ]
    return world


class BanquePopulaireLoginTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.world = bp_world()
        patches = [
            patch.object(login, "_playwright_factory", lambda: self.world.factory()),
            patch.object(login, "STEP_TIMEOUT_SECONDS", 0.3),
            patch.object(login, "COMPLETE_WAIT_SECONDS", 0.5),
            patch.object(login, "AUTHORIZE_WAIT_SECONDS", 0.1),
            patch.object(login, "POLL_INTERVAL_SECONDS", 0.001),
            patch.object(login, "RESOURCE_CLOSE_TIMEOUT_SECONDS", 0.5),
        ]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        login._pending.clear()
        login._reset_slots_for_tests()

    async def asyncTearDown(self):
        await login.close_all()

    async def initiate(self, region=REGION, customer_id=CUSTOMER_ID):
        return await login.initiate(main.SITE, customer_id, region=region)

    async def assertFails(self, status, code, coro):
        with self.assertRaises(login.LoginError) as ctx:
            await coro
        self.assertEqual((ctx.exception.status, ctx.exception.code), (status, code))

    def assertClean(self):
        self.assertEqual(login.browsers_in_use(), 0)
        self.assertEqual(len(login._pending), 0)
        self.assertEqual(self.world.still_open(), [])

    def clicks(self):
        return [e for e in self.world.events if e.startswith(("click", "key:"))]


class RegionPickerTest(BanquePopulaireLoginTest):
    async def test_the_establishment_is_picked_before_the_identifier(self):
        result = await self.initiate()

        self.assertEqual(len(result["keypad"]["images"]), 10)
        self.assertEqual(self.world.gotos, [main.SITE.login_url])
        self.assertEqual(self.world.region_option_clicks, [LABEL])
        self.assertEqual(self.world.selected_region, LABEL)
        self.assertEqual(
            self.clicks(), ["click:region-trigger", f"click:region:{LABEL}", f"click:{login.NEXT_BUTTON}"]
        )
        self.assertEqual(self.world.fills, [(login.IDENTIFIER_INPUT, CUSTOMER_ID)])
        self.assertEqual(self.world.key_clicks, [])

    async def test_every_region_code_clicks_its_exact_option(self):
        for code, label in main.REGIONS:
            with self.subTest(code=code):
                self.world = bp_world()
                await self.initiate(region=code)
                await login.close_all()
                self.assertEqual(self.world.region_option_clicks, [label])

    async def test_an_option_matching_after_trimming_only_is_still_the_exact_text(self):
        # The fake pads option texts with blanks, as rendered markup does.
        self.world.region_options = ["RIVES DE PARIS EST", "rives de paris", LABEL]
        await self.initiate()
        self.assertEqual(self.world.region_option_clicks, [LABEL])

    async def test_an_option_with_a_decomposed_accent_or_odd_spacing_still_matches(self):
        label = "BOURGOGNE FRANCHE-COMTÉ"
        rendered = "BOURGOGNE\u00a0 FRANCHE-COMTE\u0301"
        self.world.region_options = [rendered if option == label else option for option in LABELS]
        await self.initiate(region="BOURGOGNE_FRANCHE_COMTE")
        self.assertEqual(self.world.region_option_clicks, [rendered])

    async def test_a_missing_option_is_a_format_change_and_nothing_else_is_picked(self):
        self.world.region_options = [label for label in LABELS if label != LABEL]
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.world.region_option_clicks, [])
        self.assertEqual(self.world.fills, [])
        self.assertClean()

    async def test_two_options_with_the_same_text_is_a_format_change(self):
        self.world.region_options = [*LABELS, LABEL]
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.world.region_option_clicks, [])
        self.assertClean()

    async def test_no_option_at_all_is_a_format_change(self):
        self.world.region_options = []
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertClean()

    async def test_a_missing_picker_is_a_format_change_before_the_identifier(self):
        self.world.region_options = None
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.world.fills, [])
        self.assertEqual(self.clicks(), [])
        self.assertClean()

    async def test_two_pickers_is_a_format_change(self):
        self.world.region_triggers = 2
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.clicks(), [])
        self.assertClean()

    async def test_a_choice_the_picker_does_not_show_stops_before_the_identifier(self):
        self.world.region_select_works = False
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        self.assertEqual(self.world.region_option_clicks, [LABEL])
        self.assertEqual(self.world.fills, [])
        self.assertClean()

    async def test_failures_log_counts_only(self):
        self.world.region_options = ["SYNTHETIC ONE", "SYNTHETIC TWO"]
        with self.assertLogs(login.log, level="WARNING") as captured:
            await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", self.initiate())
        text = "\n".join(captured.output)
        self.assertIn("options=2, matching=0", text)
        self.assertNotIn("SYNTHETIC", text)
        self.assertNotIn(LABEL, text)

    async def test_an_unknown_or_missing_region_is_refused_before_any_browser_work(self):
        for region in (None, "rives_de_paris", LABEL, "", "ATLANTIS"):
            with self.subTest(region=region):
                await self.assertFails(400, "INVALID_REQUEST", self.initiate(region=region))
        self.assertEqual(self.world.launches, 0)

    async def test_the_caisse_epargne_site_takes_no_region(self):
        await self.assertFails(
            400, "INVALID_REQUEST", login.initiate(_ce_like_site(), CUSTOMER_ID, region=REGION)
        )
        self.assertEqual(self.world.launches, 0)


def _ce_like_site():
    """A site without a picker (as Caisse d'Epargne): a region is a caller bug there."""
    return replace(main.SITE, regions=())


class CookieBannerTest(BanquePopulaireLoginTest):
    async def test_the_banner_is_refused_before_the_picker_is_opened(self):
        self.world.cookie_banner = True
        await self.initiate()

        self.assertTrue(self.world.cookie_banner_dismissed)
        self.assertEqual(self.clicks()[:2], [f"click:{COOKIE_BANNER_BUTTON}", "click:region-trigger"])

    async def test_no_banner_is_fine(self):
        await self.initiate()

        self.assertNotIn(f"click:{COOKIE_BANNER_BUTTON}", self.world.events)
        self.assertIn(COOKIE_BANNER_BUTTON, self.world.visible_queries)


class FullLoginTest(BanquePopulaireLoginTest):
    async def login(self) -> dict:
        pid = (await self.initiate())["processId"]
        await login.keypad(pid, POSITIONS)
        return json.loads(await login.complete(pid))

    async def test_complete_returns_a_session_state_with_the_client_space_authorize(self):
        state = await self.login()

        self.assertEqual(state["authorizeParams"], {
            "client_id": "00000000-0000-4000-8000-000000000001",
            "redirect_uri": "https://www.banquepopulaire.fr/espace-client/postMessage/index.html",
            "scope": "",
            "response_type": "code",
            "cdetab": "00000",
            "claims": "{}",
            "bpcesta": "SYNTHETIC-BPCESTA",
            "login_hint": "SECRET-LOGIN-HINT",
            "display": "page",
        })
        replay.parse_session_state(json.dumps(state))
        self.assertClean()

    async def test_the_login_pages_own_authorize_is_never_captured(self):
        self.world.early_authorize = True
        state = await self.login()

        self.assertEqual(state["authorizeParams"]["client_id"], "00000000-0000-4000-8000-000000000001")
        self.assertNotIn("id_token token", json.dumps(state))

    async def test_only_banque_populaire_cookies_are_kept(self):
        state = await self.login()

        self.assertEqual(
            [(c["name"], c["domain"]) for c in state["cookies"]],
            [("SSO", ".banquepopulaire.fr"), ("SESSION", "www.banquepopulaire.fr")],
        )

    async def test_the_error_page_of_the_client_host_is_not_a_connected_session(self):
        self.world.after_push = "erreur"
        pid = (await self.initiate())["processId"]
        await login.keypad(pid, POSITIONS)
        await self.assertFails(502, "UPSTREAM_FORMAT_CHANGED", login.complete(pid))
        self.assertClean()


class NavigationAllowListTest(BanquePopulaireLoginTest):
    async def test_only_https_banque_populaire_hosts_pass_the_route(self):
        await self.initiate()
        context = self.world.contexts[0]
        allowed = (
            "https://www.icgauth.banquepopulaire.fr/se-connecter/identifier",
            "https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/authorize",
            "https://www.banquepopulaire.fr/espace-client/",
            # The home-loan app's two BPCE hosts (`SITE.extra_hosts`), nothing else on bpce.fr.
            "https://www.as-ext-bad-me.bpce.fr/api/oauth/v2/authorize",
            "https://www.rs-ext-bad-me.bpce.fr/bapi/loan/v1/loans/x",
            "data:image/png;base64,AAAA",
        )
        refused = (
            "https://www.icgauth.caisse-epargne.fr/se-connecter/identifier",
            "https://www.as-ext-bad-ib.bpce.fr/api/oauth/v2/token",
            "https://www.bpce.fr/",
            "https://evilbanquepopulaire.fr/",
            "http://www.banquepopulaire.fr/",
            "https://tracker.example.com/pixel",
        )
        for url in allowed:
            with self.subTest(url=url):
                self.assertTrue((await context.request_through_route(url)).continued)
        for url in refused:
            with self.subTest(url=url):
                self.assertTrue((await context.request_through_route(url)).aborted)

    async def test_web_sockets_off_the_site_are_closed(self):
        await self.initiate()
        context = self.world.contexts[0]
        self.assertTrue((await context.open_web_socket("wss://www.banquepopulaire.fr/ws")).connected)
        self.assertTrue((await context.open_web_socket("wss://www.caisse-epargne.fr/ws")).closed)



class IdentifierShapeTest(BanquePopulaireLoginTest):
    async def test_a_letter_and_digits_identifier_is_typed_as_is(self):
        # Banque Populaire identifiers hold a letter (observed live): never stripped to digits.
        await self.initiate(customer_id="A1234567")
        self.assertEqual(self.world.fills, [(login.IDENTIFIER_INPUT, "A1234567")])

    async def test_identifiers_outside_ascii_letters_and_digits_are_refused_before_any_browser_work(self):
        for customer_id in ("", "A" * 21, "A123 4567", "A123-4567", "É1234567", "A1234567\n", "Ａ1234567"):
            with self.subTest(customer=customer_id):
                await self.assertFails(401, "INVALID_CREDENTIALS", self.initiate(customer_id=customer_id))
        self.assertEqual(self.world.launches, 0)
        self.assertClean()


if __name__ == "__main__":
    unittest.main()
