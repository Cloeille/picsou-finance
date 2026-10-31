"""The "Comptes et contrats" parser, against synthetic pages shaped like the real one."""

import unittest
from decimal import Decimal

from accounts_parser import AccountsFormatError, account_type, parse_accounts, parse_amount
from fixtures import ACCOUNTS_PAGE, account_row, accounts_table


class AmountTest(unittest.TestCase):
    def test_reads_signed_french_amounts_with_non_breaking_spaces(self):
        self.assertEqual(parse_amount("+1&nbsp;234,56 EUR"), Decimal("1234.56"))
        self.assertEqual(parse_amount("-42,10 EUR"), Decimal("-42.10"))
        self.assertEqual(parse_amount("+0,00 €"), Decimal("0.00"))

    def test_a_foreign_currency_or_garbage_is_refused_not_read_as_zero(self):
        for raw in ("+12,00 USD", "", "N/A"):
            with self.subTest(raw=raw), self.assertRaises(AccountsFormatError):
                parse_amount(raw)


class TypeTest(unittest.TestCase):
    def test_regulated_savings_are_told_apart_from_house_passbooks(self):
        cases = {
            "LIVRET A SUP": "LIVRET_A",
            "LIVRET DE DEVELOPPEMENT DURABLE SOLIDAIRE SUP": "LDDS",
            "PLAN EPARGNE LOGEMENT": "PEL",
            "COMPTE EPARGNE LOGEMENT": "CEL",
            "LIVRET D'EPARGNE POPULAIRE": "LEP",
            "LIVRET JEUNE": "LIVRET_JEUNE",
            "LIVRET PREMIERE EPARGNE": "SAVINGS",
        }
        for label, expected in cases.items():
            with self.subTest(label=label):
                self.assertEqual(account_type("livrets et comptes d'épargne", label), expected)

    def test_the_current_accounts_category_is_checking(self):
        self.assertEqual(account_type("comptes courants", "COMPTE COURANT DU PERSONNEL"), "CHECKING")


class PageTest(unittest.TestCase):
    def test_reads_current_and_savings_accounts_and_skips_other_contracts(self):
        accounts, skipped = parse_accounts(ACCOUNTS_PAGE)

        self.assertEqual(
            [(a["id"], a["type"], a["name"]) for a in accounts],
            [
                ("1000000012345601", "CHECKING", "Compte Courant"),
                ("1000000012345602", "LIVRET_A", "Livret A"),
                ("1000000012345603", "LDDS", "Livret De Developpement Durable Solidaire"),
                ("1000000012345604", "PEL", "Plan Epargne Logement"),
            ],
        )
        self.assertEqual(skipped, ["autres contrats liés"])

    def test_an_account_row_without_balance_fails_the_page(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR").replace("ei_sdsf_montant", "other")

        with self.assertRaises(AccountsFormatError):
            parse_accounts(accounts_table("Livrets et comptes d&#39;épargne", row))

    def test_a_savings_table_without_its_caption_fails_the_whole_page(self):
        page = ACCOUNTS_PAGE.replace(
            '<caption class="invisible"><span class="invisible">Liste des comptes li&#233;s &#224; la cat&#233;gorie : '
            'Livrets et comptes d&#39;épargne</span></caption>',
            '',
        )
        self.assertNotEqual(page, ACCOUNTS_PAGE)

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_typographic_apostrophe_keeps_all_savings_accounts(self):
        page = ACCOUNTS_PAGE.replace("d&#39;épargne", "d’épargne")

        accounts, _ = parse_accounts(page)

        self.assertEqual([account["type"] for account in accounts], ["CHECKING", "LIVRET_A", "LDDS", "PEL"])

    def test_an_unknown_category_with_account_data_fails_the_whole_page(self):
        page = ACCOUNTS_PAGE.replace("Livrets et comptes d&#39;épargne", "Nouveaux comptes")

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")
        self.assertIn("nouveaux comptes", str(error.exception))
        self.assertIn("LIVRET A", str(error.exception))

    def test_title_only_contract_links_in_unsupported_tables_are_skipped(self):
        rows = '<tr><td><span class="ei_sdsf_title">Vos contrats et nos offres</span></td></tr>'
        cases = {
            "unnamed": f'<table class="ei_comptescontrats">{rows}</table>',
            "autres contrats liés": accounts_table("Autres contrats liés", rows),
        }

        for category, table in cases.items():
            with self.subTest(category=category):
                accounts, skipped = parse_accounts(ACCOUNTS_PAGE + table)

                self.assertEqual(accounts, parse_accounts(ACCOUNTS_PAGE)[0])
                self.assertEqual(skipped, ["autres contrats liés", category])

    def test_a_captionless_row_with_a_plain_text_symbol_amount_fails_the_page(self):
        for amount in ("$12.34", "12.34 $", "£12.34", "12.34 £", "€12,34", "12,34 €"):
            for title in ("", '<span class="ei_sdsf_title">NOUVEAU COMPTE</span>'):
                row = f"<tr><td>{title}</td><td>{amount}</td></tr>"
                table = f'<table class="ei_comptescontrats">{row}</table>'

                with self.subTest(amount=amount, title=bool(title)):
                    with self.assertRaises(AccountsFormatError) as error:
                        parse_accounts(ACCOUNTS_PAGE + table)

                    self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_supported_row_with_plain_text_symbol_balance_is_not_skipped(self):
        for amount in ("$12.34", "12.34 $", "£12.34", "12.34 £"):
            table = accounts_table("Livrets et comptes d&#39;épargne", f"<tr><td>{amount}</td></tr>")

            with self.subTest(amount=amount):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE + table)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_script_text_in_a_layout_table_is_not_mistaken_for_money(self):
        for replacement in ("$1", "$1$2", "$1 $2", "$12.34"):
            for tag in ("script", "SCRIPT"):
                page = (
                    f'<table class="layout"><tr><td><{tag} type="text/javascript">\n'
                    f'const accountLabel = name.replace(/(a)(b)/, "{replacement}");\n'
                    f"</{tag}>{ACCOUNTS_PAGE}</td></tr></table>"
                )

                with self.subTest(replacement=replacement, tag=tag):
                    self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_script_text_in_a_captionless_contract_link_is_not_mistaken_for_money(self):
        for replacement in ("$1", "$1$2", "$1 $2", "$12.34"):
            table = (
                '<table class="ei_comptescontrats"><tr><td>'
                '<span class="ei_sdsf_title">Vos contrats et nos offres</span>'
                f'<script>name.replace(/(a)(b)/, "{replacement}");</script>'
                '</td></tr></table>'
            )

            with self.subTest(replacement=replacement):
                accounts, skipped = parse_accounts(ACCOUNTS_PAGE + table)

                self.assertEqual(accounts, parse_accounts(ACCOUNTS_PAGE)[0])
                self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_plain_text_money_between_scripts_still_fails_the_page(self):
        table = (
            '<table class="layout"><tr><td><script>name.replace(/(a)/, "$1");</script>'
            '12.34 $<script>name.replace(/(b)/, "$1");</script></td></tr></table>'
        )

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(ACCOUNTS_PAGE + table)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_account_data_outside_contract_tables_fails_the_page(self):
        marked_row = account_row(9, "LIVRET A", "10000 000999999 01", "+12,34 EUR")
        plain_euro_row = marked_row
        for marker in ("ei_sdsf_title", "doux", "ei_sdsf_montant", "ei_sdsf_solde"):
            plain_euro_row = plain_euro_row.replace(marker, "other")
        plain_symbol_row = plain_euro_row.replace("+12,34 EUR", "£12.34")
        lost_table = accounts_table("Livrets et comptes d&#39;épargne", marked_row).replace(
            "ei_comptescontrats", "layout"
        )
        cases = {
            "marked row before": marked_row + ACCOUNTS_PAGE,
            "marked row after": ACCOUNTS_PAGE + marked_row,
            "plain euro in layout table": f'<table class="layout">{plain_euro_row}</table>' + ACCOUNTS_PAGE,
            "plain symbol in layout table": ACCOUNTS_PAGE + f'<table class="layout">{plain_symbol_row}</table>',
            "contract table lost its class": ACCOUNTS_PAGE + lost_table,
        }

        for name, page in cases.items():
            with self.subTest(name=name):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_account_markers_outside_contract_tables_fail_even_without_money(self):
        for cells in (
            '<td><span class="ei_sdsf_title">LIVRET A</span></td>',
            '<td><span class="doux">10000 000999999 01</span></td>',
            '<td><span class="ei_sdsf_montant">N/A</span></td>',
            '<td class="ei_sdsf_solde"></td>',
        ):
            with self.subTest(cells=cells):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE + f"<table><tr>{cells}</tr></table>")

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_title_only_row_in_a_supported_table_remains_a_format_change(self):
        rows = '<tr><td><span class="ei_sdsf_title">LIVRET A</span></td></tr>'

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(accounts_table("Livrets et comptes d&#39;épargne", rows))

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unnamed_table_without_account_data_is_skipped(self):
        page = ACCOUNTS_PAGE + '<table class="ei_comptescontrats"><tr><td>Aide</td></tr></table>'

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_balance_row_without_its_title_fails_the_whole_page(self):
        page = ACCOUNTS_PAGE.replace('ei_sdsf_title _c1">LIVRET A', 'other _c1">LIVRET A')
        self.assertNotEqual(page, ACCOUNTS_PAGE)

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_empty_account_title_fails_the_page(self):
        page = ACCOUNTS_PAGE.replace('>LIVRET A</span>', '></span>')

        with self.assertRaises(AccountsFormatError):
            parse_accounts(page)

    def test_a_balance_row_with_changed_markers_is_not_skipped_as_an_action(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")
        for marker in ("ei_sdsf_title", "doux", "ei_sdsf_montant", "ei_sdsf_solde"):
            row = row.replace(marker, "other")

        with self.assertRaises(AccountsFormatError):
            parse_accounts(accounts_table("Livrets et comptes d&#39;épargne", row))

    def test_header_and_action_rows_without_account_markers_are_skipped(self):
        rows = '<tr><th>Compte</th><th>Solde</th></tr><tr><td><a href="/add">Ajouter un compte</a></td></tr>'
        rows += account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")

        accounts, _ = parse_accounts(accounts_table("Livrets et comptes d&#39;épargne", rows))

        self.assertEqual([account["id"] for account in accounts], ["1000000012345602"])

    def test_a_page_without_contract_table_is_a_format_change(self):
        with self.assertRaises(AccountsFormatError):
            parse_accounts("<html><body>Bienvenue</body></html>")

    def test_the_same_account_listed_twice_is_refused(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")

        with self.assertRaises(AccountsFormatError):
            parse_accounts(accounts_table("Livrets et comptes d&#39;épargne", row + row))


if __name__ == "__main__":
    unittest.main()
