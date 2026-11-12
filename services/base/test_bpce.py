"""The shared BPCE package: the Site, its allow-list and the bank-agnostic parsing.

The banks' own constants are tested by each sidecar against its `main.SITE`; here a
synthetic site on a reserved `.test` domain exercises the package alone. Synthetic
payloads only: no real data.
"""

import asyncio
import json
import unittest
from datetime import date

import httpx

from bpce import accounts_parser, fetcher, per_client, replay
from bpce.site import ChainProfile, HomeLoanApp, PerApp, Site

SITE = Site(
    domain="bank.test",
    login_url="https://www.icgauth.bank.test/se-connecter/sso?service=x",
    client_host="www.bank.test",
    authorize_url="https://www.as.bank.test/api/oauth/v2/authorize",
    token_url="https://www.as.bank.test/api/oauth/v2/token",
    data_base_url="https://www.rs.bank.test",
)
FULL_SITE = Site(
    domain="bank.test",
    login_url=SITE.login_url,
    client_host=SITE.client_host,
    authorize_url=SITE.authorize_url,
    token_url=SITE.token_url,
    data_base_url=SITE.data_base_url,
    extra_hosts=frozenset({"www.as.group.test"}),
    provisional_kinds=frozenset({"SAVINGS", "PEL", "LEP", "ASSURANCE_VIE", "TERM_DEPOSIT", "COOPERATIVE_SHARES"}),
    personal_loan=ChainProfile(name="personal-loan"),
    home_loan=HomeLoanApp(
        chain=ChainProfile(name="home-loan"),
        authorize_url="https://www.as.group.test/authorize",
        token_url="https://www.as.group.test/token",
        base_url="https://www.as.group.test",
    ),
    per=PerApp(chain=ChainProfile(name="per"), gateway_host="www.rs.bank.test", insurer_host="www.ins.bank.test"),
    regions=(("NORTH", "NORTH SHORE"), ("SOUTH", "SOUTH BAY")),
)


def contract(pfm_id, family, sub, product_label, *, balance=100.0, contract_id=None):
    return {
        "identification": {
            "contractId": {"entityCode": "00000", "id": contract_id or f"C{pfm_id}"},
            "contractPfmId": pfm_id,
        },
        "identity": {
            "balance": None if balance is None else {"value": balance, "currencyCode": "EUR"},
            "contractLabel": f"SYNTHETIC {product_label}",
            "productLabel": product_label,
            "productFamilyPFM": {"code": family},
            "subProductFamilyPFM": None if sub is None else {"code": sub},
            "augmentedCards": None,
        },
    }


def kinds(items, **kwargs):
    accounts, unsupported = accounts_parser._parse_synthesis({"items": items}, **kwargs)
    return [(a["externalId"], a["kind"]) for a in accounts], unsupported


class SiteTest(unittest.TestCase):
    def test_a_site_is_frozen(self):
        with self.assertRaises(AttributeError):
            SITE.domain = "other.test"  # type: ignore[misc]

    def test_families_without_an_app_are_unsupported(self):
        self.assertEqual(
            SITE.unsupported_families,
            frozenset(
                {("6", "12"), ("6", "13"), ("4", "05"), ("3", "02"), ("3", "04"), ("3", "27"), ("4", "09"), ("4", "10")}
            ),
        )
        self.assertEqual(FULL_SITE.unsupported_families, frozenset())

    def test_a_site_without_provisional_kinds_reads_no_pel_lep_and_never_demotes(self):
        items = [
            contract(1, "1", None, "Compte"),
            contract(5, "3", "02", "Compte Sur Livret"),
            contract(2, "3", "04", "PEL 99 9999"),
            contract(3, "3", "27", "LEP"),
            contract(None, "4", "09", "Tip Top'P 3 Ans", balance=1234.56, contract_id="TD1"),
            contract(None, "4", "10", "Part Sociale BP", balance=250.0, contract_id="CS1"),
        ]
        accounts, unsupported = kinds(items, skipped_families=SITE.unsupported_families,
                                      provisional_kinds=SITE.provisional_kinds)
        self.assertEqual(accounts, [("1", "CURRENT_ACCOUNT")])
        self.assertEqual([u["subFamilyCode"] for u in unsupported], ["02", "04", "27", "09", "10"])
        # A label-detected LEP (3/01) that cannot be read still fails loudly on such a site.
        broken = contract(4, "3", "01", "LEP", balance=None)
        with self.assertRaises(accounts_parser.ParseError):
            kinds([broken], skipped_families=SITE.unsupported_families, provisional_kinds=SITE.provisional_kinds)

    def test_a_label_detected_lep_never_has_its_cards_checked_even_when_not_provisional(self):
        lep = contract(4, "3", "01", "LEP")
        del lep["identity"]["augmentedCards"]
        self.assertEqual(
            kinds([lep], skipped_families=SITE.unsupported_families, provisional_kinds=SITE.provisional_kinds),
            ([("4", "LEP")], []),
        )
        # A Livret A on the same site still refuses a missing card list.
        livret = contract(2, "3", "01", "Livret A")
        del livret["identity"]["augmentedCards"]
        with self.assertRaises(accounts_parser.ParseError):
            kinds([livret], skipped_families=SITE.unsupported_families, provisional_kinds=SITE.provisional_kinds)

    def test_region_labels_are_exact_and_only_for_listed_codes(self):
        self.assertEqual(FULL_SITE.region_label("SOUTH"), "SOUTH BAY")
        for code in ("south", "SOUTH BAY", "", None, 1, "WEST"):
            with self.subTest(code=code):
                self.assertIsNone(FULL_SITE.region_label(code))
        self.assertIsNone(SITE.region_label("SOUTH"))


class AllowListTest(unittest.TestCase):
    def test_the_site_domain_and_its_exact_extra_hosts_only(self):
        for url in (
            "https://www.rs.bank.test/x",
            "https://a.b.bank.test:443/x",
        ):
            with self.subTest(url=url):
                self.assertTrue(replay.is_allowed_url(SITE, url))
        self.assertTrue(replay.is_allowed_url(FULL_SITE, "https://www.as.group.test/token"))
        for url in (
            "https://bank.test/x",  # the bare domain: no host label
            "https://www.as.group.test/token",  # an extra host of another site
            "https://evilbank.test/x",
            "https://www.bank.test.evil.test/x",
            "http://www.bank.test/x",
            "https://www.bank.test:8443/x",
            "https://user@www.bank.test/x",
            "https://www.bank.test\\@evil.test/",
            "https://www.bank.test/\nx",
        ):
            with self.subTest(url=url):
                self.assertFalse(replay.is_allowed_url(SITE, url))

    def test_the_replay_refuses_a_url_off_the_site_before_sending(self):
        sent = []
        state = replay.parse_session_state(json.dumps({
            "cookies": [{"name": "s", "value": "v", "domain": ".bank.test", "path": "/"}],
            "authorizeParams": {"client_id": "c", "redirect_uri": "https://www.bank.test/cb"},
        }))
        transport = httpx.MockTransport(lambda request: sent.append(request) or httpx.Response(500))
        with self.assertRaises(replay.HostNotAllowed):
            asyncio.run(replay.replay_chain(
                SITE, state, transport=transport, authorize_url="https://www.as.other.test/authorize",
            ))
        self.assertEqual(sent, [])


class RegulatedSavingsKindTest(unittest.TestCase):
    def test_a_3_01_contract_is_told_apart_by_its_product_label(self):
        cases = {
            "Livret A-Particuliers": "LIVRET_A",
            "LIVRET A": "LIVRET_A",
            "Livret Jeune": "LIVRET_JEUNE",
            "LIVRET JEUNE": "LIVRET_JEUNE",
            "livret jeune": "LIVRET_JEUNE",
            "Livret Jeûne": "LIVRET_JEUNE",
            "Some other regulated savings": "LIVRET_A",
        }
        for label, kind in cases.items():
            with self.subTest(label=label):
                self.assertEqual(kinds([contract(1, "3", "01", label)]), ([("1", kind)], []))

    def test_a_lep_label_is_imported_as_a_lep_never_a_livret_a(self):
        # Imported, not left out: an account `main` imported as a Livret A keeps updating.
        for label in ("LEP", "Lep Particuliers", "Livret d'Épargne Populaire", "LIVRET EPARGNE POPULAIRE"):
            with self.subTest(label=label):
                self.assertEqual(kinds([contract(7, "3", "01", label)]), ([("7", "LEP")], []))

    def test_jeune_is_matched_as_a_word(self):
        self.assertEqual(kinds([contract(1, "3", "01", "Livret A Jeunesse")]), ([("1", "LIVRET_A")], []))
        self.assertEqual(kinds([contract(2, "3", "01", "Livret Jeunes")]), ([("2", "LIVRET_JEUNE")], []))

    def test_lep_is_matched_as_a_word(self):
        self.assertEqual(kinds([contract(1, "3", "01", "Livret A Sleptique")]), ([("1", "LIVRET_A")], []))

    def test_a_missing_product_label_stays_a_livret_a(self):
        item = contract(1, "3", "01", "x")
        del item["identity"]["productLabel"]
        self.assertEqual(kinds([item]), ([("1", "LIVRET_A")], []))

    def test_a_livret_jeune_has_pfm_transactions(self):
        self.assertIn("LIVRET_JEUNE", accounts_parser.PFM_KINDS)


class SkippedFamiliesTest(unittest.TestCase):
    def test_skipped_families_are_listed_by_id_and_family_only(self):
        items = [
            contract(1, "1", None, "Compte"),
            contract(None, "6", "12", "Pret", balance=-1000.0, contract_id="L1"),
            contract(None, "6", "13", "Immo", balance=-9000.0, contract_id="L2"),
            contract(None, "4", "05", "PER", balance=50.0, contract_id="P1"),
            contract(None, "2", "11", "Revolving", balance=None, contract_id="R1"),
        ]
        accounts, unsupported = kinds(items, skipped_families=SITE.unsupported_families)
        self.assertEqual(accounts, [("1", "CURRENT_ACCOUNT")])
        # The parser also carries diagnostic-only keys (`hasPfmId`, `productLabel`) that the
        # fetcher drops before anything leaves the sidecar.
        unsupported = [
            {k: u[k] for k in ("externalId", "familyCode", "subFamilyCode")} for u in unsupported
        ]
        self.assertEqual(unsupported, [
            {"externalId": "contract:L1", "familyCode": "6", "subFamilyCode": "12"},
            {"externalId": "contract:L2", "familyCode": "6", "subFamilyCode": "13"},
            {"externalId": "contract:P1", "familyCode": "4", "subFamilyCode": "05"},
            {"externalId": "contract:R1", "familyCode": "2", "subFamilyCode": "11"},
        ])

    def test_without_skipped_families_the_detail_kinds_are_read(self):
        items = [
            contract(None, "6", "12", "Pret", balance=-1000.0, contract_id="L1"),
            contract(None, "4", "05", "PER", balance=50.0, contract_id="P1"),
        ]
        self.assertEqual(kinds(items)[0], [("contract:L1", "LOAN"), ("contract:P1", "PER")])


class FetchImportTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_livret_jeune_reads_its_pfm_transactions_and_skipped_families_are_never_fetched(self):
        synthesis = {"items": [
            contract(1, "1", None, "Compte"),
            contract(2, "3", "01", "Livret Jeune"),
            contract(None, "6", "12", "Pret", balance=-1000.0, contract_id="L1"),
            contract(None, "2", "11", "Revolving", balance=None, contract_id="R1"),
        ]}
        seen = []

        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(request)
            self.assertEqual(request.url.host, "www.rs.bank.test")
            if request.url.path == fetcher.SYNTHESIS_PATH:
                return httpx.Response(200, json=synthesis)
            self.assertEqual(request.url.path, fetcher.TRANSACTIONS_PATH)
            account = request.url.params["accountIds"]
            row = {"id": f"t{account}", "accountId": int(account), "date": "2026-11-01", "dueDate": "2026-11-01",
                   "amount": 10.0, "currency": "EUR", "text": "SYNTHETIC"}
            return httpx.Response(200, json={"data": [row], "meta": {}})

        loan_token_calls = []

        async def loan_token():
            loan_token_calls.append(1)
            return "unused"

        result = await fetcher.fetch_import(
            SITE, "synthetic-token", transport=httpx.MockTransport(handler), today=date(2026, 11, 5),
            loan_token=loan_token,
        )
        self.assertEqual([(a["externalId"], a["kind"]) for a in result["accounts"]],
                         [("1", "CURRENT_ACCOUNT"), ("2", "LIVRET_JEUNE")])
        self.assertEqual([t["externalId"] for t in result["accounts"][1]["transactions"]], ["t2"])
        self.assertEqual(result["unsupported"], [
            {"externalId": "contract:L1", "familyCode": "6", "subFamilyCode": "12"},
            {"externalId": "contract:R1", "familyCode": "2", "subFamilyCode": "11"},
        ])
        self.assertEqual(result["unavailable"], [])
        self.assertEqual(loan_token_calls, [])
        self.assertEqual(sorted(r.url.params.get("accountIds", "") for r in seen), ["", "1", "2"])

    async def test_a_data_host_off_the_site_is_refused_before_any_call(self):
        sent = []
        with self.assertRaises(replay.HostNotAllowed):
            await fetcher.fetch_import(
                SITE, "t", transport=httpx.MockTransport(lambda r: sent.append(r) or httpx.Response(500)),
                base_url="https://www.rs.other.test",
            )
        self.assertEqual(sent, [])



class CardSwitchTest(unittest.TestCase):
    CARD = {
        "cardPfmId": 9001,
        "cardProductLabel": "SYNTHETIC VISA",
        "cardStatusType": {"code": "100"},
        "cardCreditIndicator": True,
        "defferedDebitIndicator": False,
    }

    def current_with_card(self):
        item = contract(1, "1", None, "Compte De Cheques")
        item["identity"]["augmentedCards"] = [dict(self.CARD)]
        return item

    def test_cards_are_imported_by_default(self):
        self.assertEqual(kinds([self.current_with_card()]), ([("1", "CURRENT_ACCOUNT"), ("9001", "CARD")], []))

    def test_a_site_without_cards_imports_the_account_only_and_lists_nothing(self):
        self.assertEqual(
            kinds([self.current_with_card()], import_cards=False), ([("1", "CURRENT_ACCOUNT")], [])
        )

    def test_a_malformed_card_list_still_refuses_the_snapshot_without_cards(self):
        item = self.current_with_card()
        item["identity"]["augmentedCards"] = "not a list"
        with self.assertRaises(accounts_parser.ParseError):
            kinds([item], import_cards=False)

class PerContractIdParsingTest(unittest.TestCase):
    def test_parse_contract_id_supports_compact_bp_and_standard_ce_shapes(self):
        cases = [
            ("PERIB123456789", "PERIB", "123456789"),
            ("ASVPERIB123456", "PERIB", "123456"),
            ("00000     PERIC12345678", "PERIC", "12345678"),
            ("00000     ESSEN12345678", "ESSEN", "12345678"),
            ("00000     PERIB12345678", "PERIB", "12345678"),
        ]
        for raw, expected_product, expected_number in cases:
            with self.subTest(contract_id=raw):
                match = per_client._parse_contract_id(raw)
                self.assertEqual(match.group("product"), expected_product)
                self.assertEqual(match.group("number"), expected_number)

    def test_parse_contract_id_refuses_invalid_shapes(self):
        for raw in (
            "PERIC12345678", "ESSEN12345678", "PERIB", "PERIB12AB", "00000 PERIC123/../x", 123,
            "PERIA12345678", "ASVPERIC12345678", "ASVESSEN12345678", "", "00000     PERIB",
        ):
            with self.subTest(contract_id=raw):
                with self.assertRaises(per_client.UnknownPerContract):
                    per_client._parse_contract_id(raw)

    def test_insurer_kind_supports_compact_and_standard_shapes(self):
        cases = [
            ("PERIB123456789", "PER"),
            ("ASVPERIB123456", "PER"),
            ("00000     PERIC12345678", "PER"),
            ("00000     ESSEN12345678", "ASSURANCE_VIE"),
            ("00000     PERIB12345678", "PER"),
            ("00000     OTHER12345678", "PER"),
        ]
        for raw, expected_kind in cases:
            with self.subTest(contract_id=raw):
                self.assertEqual(accounts_parser._insurer_kind({"contractId": {"id": raw}}), expected_kind)


if __name__ == "__main__":
    unittest.main()
