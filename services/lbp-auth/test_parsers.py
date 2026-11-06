"""The XHTML parsers, against the real page structure (anonymized)."""

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


class TransactionsTest(unittest.TestCase):
    def test_reads_dates_labels_and_the_raw_signed_amount(self):
        transactions = parsers.parse_transactions(fixtures.STATEMENT_HTML)

        self.assertEqual(len(transactions), 2)
        self.assertEqual(transactions[0]["date"], "2026-10-02")
        self.assertEqual(transactions[0]["amount"], "-350.0")
        self.assertIn("VIREMENT POUR M TEST", transactions[0]["label"])
        self.assertEqual(transactions[1]["amount"], "205.0")
        self.assertEqual(transactions[1]["currency"], "EUR")

    def test_external_ids_are_stable_across_reads(self):
        first = [t["externalId"] for t in parsers.parse_transactions(fixtures.STATEMENT_HTML)]
        second = [t["externalId"] for t in parsers.parse_transactions(fixtures.STATEMENT_HTML)]

        self.assertEqual(first, second)
        self.assertEqual(len(set(first)), 2)

    def test_an_account_without_a_table_has_no_transactions(self):
        self.assertEqual(parsers.parse_transactions("<html><body>no operations</body></html>"), [])


if __name__ == "__main__":
    unittest.main()
