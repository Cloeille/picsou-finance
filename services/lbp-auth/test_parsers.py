"""The XHTML parsers, against synthetic fixtures modelled on the real page structure."""

import hashlib
import unittest
from decimal import Decimal

import fixtures
import parsers


class LoginFormTest(unittest.TestCase):
    def test_reads_the_action_hidden_fields_and_shuffled_keypad(self):
        form = parsers.parse_login_form(fixtures.LOGIN_HTML)

        self.assertEqual(form["action"], "/wsost/OstBrokerWeb/auth")
        self.assertEqual(form["hidden"]["origin"], "particuliers")
        self.assertEqual(form["hidden"]["cv"], "true")
        self.assertEqual(form["hidden"]["iscdName"], "0")
        self.assertEqual(form["keys"], fixtures.KEYS)

    def test_refuses_a_keypad_that_is_not_exactly_the_ten_digits(self):
        broken = fixtures.LOGIN_HTML.replace(
            '<button type="button">4</button>', '<button type="button">5</button>'
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_login_form(broken)

    def test_refuses_a_page_without_the_keypad(self):
        with self.assertRaises(parsers.FormatError):
            parsers.parse_login_form("<form action='/x'></form>")


class ChallengeTest(unittest.TestCase):
    def test_reads_the_polling_url_and_base(self):
        pph, polling = parsers.parse_polling(fixtures.CHALLENGE_HTML)

        self.assertEqual(pph, "https://voscomptesenligne.labanquepostale.fr/wsost")
        self.assertIn("TAM_OP=login", polling)
        self.assertIn("cu=attenteAF", polling)


class AccountCardsTest(unittest.TestCase):
    def test_reads_the_current_account_balance_and_kind(self):
        accounts = parsers.parse_account_cards(fixtures.SYNTHESE_CCP_HTML)

        self.assertEqual(len(accounts), 1)
        account = accounts[0]
        self.assertEqual(account["externalId"], hashlib.sha1(b"00000000001").hexdigest())
        self.assertEqual(account["statementId"], "11111111-1111-1111-1111-111111111111")
        self.assertEqual(account["kind"], "CURRENT_ACCOUNT")
        self.assertEqual(account["typeRecherche"], "5")
        self.assertEqual(account["balance"], Decimal("1234.56"))
        self.assertEqual(account["currency"], "EUR")

    def test_reads_both_regulated_savings_books(self):
        accounts = parsers.parse_account_cards(fixtures.SYNTHESE_EP_HTML)

        self.assertEqual([a["kind"] for a in accounts], ["LIVRET_A", "LEP"])
        self.assertEqual([a["balance"] for a in accounts], [Decimal("10000.00"), Decimal("5000.00")])
        self.assertEqual({a["typeRecherche"] for a in accounts}, {"15"})
        self.assertEqual(
            {a["externalId"] for a in accounts},
            {hashlib.sha1(b"00000000002").hexdigest(), hashlib.sha1(b"00000000003").hexdigest()},
        )

    def test_the_stable_id_survives_a_new_session_uuid(self):
        before = parsers.parse_account_cards(fixtures.SYNTHESE_EP_HTML)
        other_session = fixtures.SYNTHESE_EP_HTML.replace(
            "22222222-2222-2222-2222-222222222222", "44444444-4444-4444-4444-444444444444"
        )
        after = parsers.parse_account_cards(other_session)

        self.assertEqual(before[0]["externalId"], after[0]["externalId"])
        self.assertNotEqual(before[0]["statementId"], after[0]["statementId"])

    def test_an_unknown_product_label_is_a_format_error(self):
        changed = fixtures.SYNTHESE_CCP_HTML.replace("Compte Bancaire", "Produit inconnu")
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(changed)

    def test_finds_the_card_when_the_marker_is_not_the_first_class(self):
        reordered = fixtures.SYNTHESE_CCP_HTML.replace(
            'class="account-resume2020 account-resume--ccp"',
            'class="account-resume--ccp account-resume2020"',
        )
        accounts = parsers.parse_account_cards(reordered)

        self.assertEqual(len(accounts), 1)
        self.assertEqual(accounts[0]["kind"], "CURRENT_ACCOUNT")

    def test_finds_the_card_when_an_attribute_precedes_class(self):
        reordered = fixtures.SYNTHESE_CCP_HTML.replace(
            '<div class="account-resume2020 account-resume--ccp">',
            '<div data-slot="account" class="account-resume2020 account-resume--ccp">',
        )
        accounts = parsers.parse_account_cards(reordered)

        self.assertEqual(len(accounts), 1)
        self.assertEqual(accounts[0]["kind"], "CURRENT_ACCOUNT")

    def test_a_ccp_maintenance_page_without_cards_is_a_format_error(self):
        maintenance = "<html><body><p>Service momentanement indisponible</p></body></html>"
        with self.assertRaises(parsers.FormatError) as raised:
            parsers.parse_account_cards(maintenance)
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_ep_maintenance_page_without_cards_is_a_format_error(self):
        maintenance = "<html><body><p>Service momentanement indisponible</p></body></html>"
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(maintenance)

    def test_a_cardless_page_with_a_container_but_no_empty_message_is_a_format_error(self):
        # The list marker survived but no card parsed (stripped links): without
        # an explicit empty-state message this is a partial page, not "no accounts".
        page = (
            '<div class="account-resume2020 account-resume--ccp">'
            "<p>partial page without a card link</p></div>"
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(page)

    def test_a_container_with_an_explicit_empty_message_parses_as_empty(self):
        # Provisional: no such empty state was observed on the real synthesis
        # pages, so in practice zero-card pages fail closed. When the page both
        # keeps its account-list container and explicitly says there is nothing,
        # it reads as an empty list (mirrors `_EMPTY_OPERATIONS` for statements).
        page = (
            '<div class="account-resume2020 account-resume--ccp">'
            "<p>Aucun compte</p></div>"
        )
        self.assertEqual(parsers.parse_account_cards(page), [])

    def test_a_recognised_card_without_its_statement_link_is_a_format_error(self):
        # N1-family: silently skipping the card would read as a successful
        # snapshot missing that account and let the backend close it at zero.
        without_href = fixtures.SYNTHESE_CCP_HTML.replace(
            "compte.numero=11111111-1111-1111-1111-111111111111", "compte.numero="
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(without_href)

    def test_a_healthy_card_beside_a_linkless_card_is_a_format_error(self):
        # The real regression: one healthy card plus one linkless card. Old
        # code returned the healthy card with snapshotComplete: true and the
        # backend closed the linkless account at zero.
        card = fixtures.SYNTHESE_CCP_HTML.split('<div class="account-resume2020')[1]
        linkless = '<div class="account-resume2020' + card.replace(
            "compte.numero=11111111-1111-1111-1111-111111111111", "compte.numero="
        )
        page = fixtures.SYNTHESE_CCP_HTML.replace("</ul>", "<li>" + linkless + "</li></ul>")
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(page)

    def test_a_recognised_card_with_a_bare_link_is_a_format_error(self):
        page = (
            '<div class="account-resume2020 account-resume--ccp">'
            '<h3><a href="/voscomptes/canalXHTML/CCP/releves_ccp/frame-releve_ccp.ea">'
            '<span class="pseudo-h3">Compte Bancaire&nbsp;</span>'
            "<span>N&deg;&nbsp;00000000001</span></a></h3>"
            '<p class="amount-euro"><span class="lib sr-only">Solde </span>'
            " + 1 234,56&nbsp;&euro;</p></div>"
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(page)


class TransactionsTest(unittest.TestCase):
    def test_reads_dates_labels_and_the_raw_signed_amount(self):
        transactions = parsers.parse_transactions(fixtures.STATEMENT_HTML)

        self.assertEqual(len(transactions), 2)
        self.assertEqual(transactions[0]["date"], "2026-10-02")
        self.assertEqual(transactions[0]["amount"], "-350.0")
        self.assertEqual(transactions[0]["currency"], "EUR")
        self.assertIn("VIREMENT POUR M TEST", transactions[0]["label"])
        self.assertEqual(transactions[1]["amount"], "205.0")
        self.assertEqual(transactions[1]["currency"], "EUR")

    def test_external_ids_are_stable_across_reads(self):
        first = [t["externalId"] for t in parsers.parse_transactions(fixtures.STATEMENT_HTML)]
        second = [t["externalId"] for t in parsers.parse_transactions(fixtures.STATEMENT_HTML)]

        self.assertEqual(first, second)
        self.assertEqual(len(set(first)), 2)

    def test_a_new_row_does_not_renumber_the_existing_ones(self):
        before = parsers.parse_transactions(fixtures.STATEMENT_HTML)
        new_row = (
            '<tr class="row btn-full-lightbox">'
            '<td class="col label col-date" data-label="Date">'
            '<span class="sorted-value">03/10/2026</span></td>'
            '<td class="col label col-libelle" data-label="Libellé">NOUVELLE OPERATION</td>'
            '<td class="col montant cell-right col-montant" data-label="Montant">'
            '<span>- 10,00&nbsp;&euro;</span></td>'
            '<td class="col label col-montant-brut sr-only" aria-hidden="true"> -10.0 </td>'
            "</tr>"
        )
        with_new_row = fixtures.STATEMENT_HTML.replace(
            '<tr class="row btn-full-lightbox">', new_row + '<tr class="row btn-full-lightbox">', 1
        )

        after = parsers.parse_transactions(with_new_row)

        before_by_date = {t["date"]: t["externalId"] for t in before}
        after_by_date = {t["date"]: t["externalId"] for t in after}
        self.assertEqual(len(after), 3)
        for date, external_id in before_by_date.items():
            self.assertEqual(after_by_date[date], external_id)

    def test_an_account_without_a_table_has_no_transactions(self):
        self.assertEqual(parsers.parse_transactions("<html><body>no operations</body></html>"), [])

    def test_a_page_without_a_table_or_empty_marker_is_a_format_error(self):
        with self.assertRaises(parsers.FormatError):
            parsers.parse_transactions("<html><body>something else</body></html>")

    def test_identical_rows_get_distinct_stable_ids(self):
        row = (
            '<tr class="row btn-full-lightbox">'
            '<td class="col label col-date" data-label="Date">'
            '<span class="sorted-value">05/10/2026</span></td>'
            '<td class="col label col-libelle" data-label="Libellé">FRAIS</td>'
            '<td class="col montant cell-right col-montant" data-label="Montant">'
            '<span>- 2,00&nbsp;&euro;</span></td>'
            '<td class="col label col-montant-brut sr-only" aria-hidden="true"> -2.0 </td>'
            "</tr>"
        )
        with_duplicates = fixtures.STATEMENT_HTML.replace(
            '<tr class="row btn-full-lightbox">', row + row + '<tr class="row btn-full-lightbox">', 1
        )
        first = parsers.parse_transactions(with_duplicates)
        ids = [t["externalId"] for t in first]
        self.assertEqual(len(ids), 4)
        self.assertEqual(len(set(ids)), 4)

        with_another = fixtures.STATEMENT_HTML.replace(
            '<tr class="row btn-full-lightbox">',
            row + row + row + '<tr class="row btn-full-lightbox">',
            1,
        )
        second_ids = {t["externalId"] for t in parsers.parse_transactions(with_another)}
        self.assertTrue(set(ids).issubset(second_ids))
        self.assertEqual(len(second_ids - set(ids)), 1)


class MoneyTest(unittest.TestCase):
    def test_eur_variants_parse_with_value_and_currency(self):
        cases = {
            "+ 1 234,56&nbsp;&euro;": (Decimal("1234.56"), "EUR"),
            "+ 1\u202f234,56\u00a0\u20ac": (Decimal("1234.56"), "EUR"),
            "-12,50 EUR": (Decimal("-12.50"), "EUR"),
            "-12,50 eur": (Decimal("-12.50"), "EUR"),
            "+45,00&nbsp;EUR": (Decimal("45.00"), "EUR"),
            "1 000,00&nbsp;&euro;": (Decimal("1000.00"), "EUR"),
            "&euro;12,50": (Decimal("12.50"), "EUR"),
        }
        for fragment, expected in cases.items():
            with self.subTest(fragment=fragment):
                self.assertEqual(parsers._money(fragment), expected)

    def test_non_eur_amounts_are_rejected(self):
        for fragment in (
            "-12,50\u202fUSD",
            "-12,50 USD",
            "+45,00 $",
            "12,50 \u00a3",
            "12,50 CHF",
            "12,50",
            "12,50 XYZ",
        ):
            with self.subTest(fragment=fragment):
                with self.assertRaises(parsers.FormatError):
                    parsers._money(fragment)

    def test_a_euro_sign_next_to_another_currency_is_rejected(self):
        # The old blacklist only knew a fixed ISO list, so an unknown code
        # next to a euro sign read as EUR. After stripping `€`/`EUR` any
        # remaining letter or currency symbol fails closed.
        for fragment in (
            "12,50 \u20ac KRW",
            "12,50 \u20ac ILS",
            "1 000 000 \u20ac XPF",
            "12,50 EUR USD",
            "12,50 \u20ac $",
        ):
            with self.subTest(fragment=fragment):
                with self.assertRaises(parsers.FormatError):
                    parsers._money(fragment)

    def test_a_non_eur_balance_is_a_format_error(self):
        changed = fixtures.SYNTHESE_CCP_HTML.replace("+ 1 234,56&nbsp;&euro;", "-12,50&nbsp;USD")
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(changed)

    def test_a_balance_without_a_currency_is_a_format_error(self):
        changed = fixtures.SYNTHESE_CCP_HTML.replace("+ 1 234,56&nbsp;&euro;", "1 234,56")
        with self.assertRaises(parsers.FormatError):
            parsers.parse_account_cards(changed)

    def test_a_non_eur_transaction_is_a_format_error(self):
        changed = fixtures.STATEMENT_HTML.replace(
            "&minus; 350,00&nbsp;&euro;", "-350,00&nbsp;USD", 1
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_transactions(changed)

    def test_a_transaction_without_a_visible_amount_is_a_format_error(self):
        changed = fixtures.STATEMENT_HTML.replace(
            '<td class="col montant cell-right col-montant" data-label="Montant">'
            "<span>&minus; 350,00&nbsp;&euro;</span></td>",
            "",
            1,
        )
        with self.assertRaises(parsers.FormatError):
            parsers.parse_transactions(changed)


if __name__ == "__main__":
    unittest.main()
