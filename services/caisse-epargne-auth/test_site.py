"""The Caisse d'Epargne `Site`: its allow-list stays its own.

The `bpce` package serves Banque Populaire too; nothing of that bank may pass here.
"""

import unittest

from bpce import replay

import main


class SiteAllowListTest(unittest.TestCase):
    def test_banque_populaire_hosts_are_refused(self):
        for url in (
            "https://www.banquepopulaire.fr/espace-client/",
            "https://www.icgauth.banquepopulaire.fr/se-connecter/sso?service=cyber",
            "https://www.as-ext-bad-ib.banquepopulaire.fr/api/oauth/v2/token",
            "https://www.rs-ext-bad-ib.banquepopulaire.fr/bapi/contract/v2/augmentedSynthesisViews",
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(main.SITE, url))

    def test_only_the_two_exact_bpce_hosts_pass(self):
        self.assertEqual(main.SITE.extra_hosts, frozenset({"www.as-ext-bad-me.bpce.fr", "www.rs-ext-bad-me.bpce.fr"}))
        for url in ("https://www.bpce.fr/", "https://www.as-ext-bad-ib.bpce.fr/"):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(main.SITE, url))

    def test_every_configured_url_passes_its_own_allow_list(self):
        site = main.SITE
        for url in (
            site.login_url, site.authorize_url, site.token_url, site.data_base_url,
            site.home_loan.authorize_url, site.home_loan.token_url, site.home_loan.base_url,
            f"https://{site.per.gateway_host}/", f"https://{site.per.insurer_host}/",
        ):
            with self.subTest(url=url):
                self.assertTrue(replay.is_allowed_url(site, url))

    def test_no_region_picker_and_every_detail_app(self):
        self.assertEqual(main.SITE.regions, ())
        self.assertIsNone(main.SITE.cookie_banner_button)
        self.assertEqual(main.SITE.unsupported_families, frozenset())


if __name__ == "__main__":
    unittest.main()
