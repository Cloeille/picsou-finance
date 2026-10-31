"""The "Comptes et contrats" parser, against synthetic pages shaped like the real one."""

import time
import unittest
from decimal import Decimal

from accounts_parser import (
    _BALANCE_RE,
    _ROW_MONEY_RE,
    AccountsFormatError,
    account_type,
    categories_read,
    parse_accounts,
    parse_amount,
)
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


class MoneyScanTest(unittest.TestCase):
    def test_scanning_text_for_money_stays_linear_on_a_long_digit_run(self):
        text = "1 " * 20000
        for name, pattern in (("balance", _BALANCE_RE), ("row money", _ROW_MONEY_RE)):
            with self.subTest(name):
                started = time.perf_counter()
                pattern.search(text)
                self.assertLess(time.perf_counter() - started, 1)


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

    def test_accented_labels_are_typed_like_plain_ones(self):
        cases = {
            "PLAN D'ÉPARGNE LOGEMENT": "PEL",
            "Plan d\u2019épargne logement": "PEL",
            "COMPTE ÉPARGNE LOGEMENT": "CEL",
            "LIVRET DE DÉVELOPPEMENT DURABLE": "LDDS",
            "LIVRET D'ÉPARGNE POPULAIRE": "LEP",
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
        page = accounts_table("Livrets et comptes d&#39;épargne", row)

        with self.assertRaises(AccountsFormatError):
            parse_accounts(f"<html><body>{page}</body></html>")

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

    def test_an_unparsed_savings_table_fails_the_whole_page(self):
        prefix, remaining = ACCOUNTS_PAGE.split("ei_comptescontrats", 1)
        for replacement in ("ei_savingscontracts", ""):
            with self.subTest(replacement=replacement):
                page = prefix + "ei_comptescontrats" + remaining.replace("ei_comptescontrats", replacement, 1)

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_truncated_savings_table_fails_the_whole_page(self):
        for ending in (
            'class="_c1 ei_sdsf_title _c1">LIVRET A',
            '>LIVRET A</span>',
            '+5&nbsp;000,00 EUR',
            '+5&nbsp;000,00 EUR</span>',
        ):
            with self.subTest(ending=ending):
                page = ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index(ending) + len(ending)]

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_early_truncated_savings_table_fails_the_whole_page(self):
        savings_start = ACCOUNTS_PAGE.index("Livrets et comptes d&#39;épargne")
        for ending in (
            'Livrets et comptes d&#39;épargne</span></caption>',
            'class="_c1 ei_sdsf_ti',
        ):
            with self.subTest(ending=ending):
                page = ACCOUNTS_PAGE[:ACCOUNTS_PAGE.index(ending, savings_start) + len(ending)]

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_cut_before_or_during_the_next_table_opening_fails_the_whole_page(self):
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        opening_end = ACCOUNTS_PAGE.index('>', table_start) + 1
        for cut in range(table_start, opening_end + 1):
            with self.subTest(cut=cut):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE[:cut])

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_cut_after_the_last_table_still_fails_the_whole_page(self):
        table_end = ACCOUNTS_PAGE.rindex('</table>') + len('</table>')
        for cut in range(table_end, ACCOUNTS_PAGE.index('</html>') + len('</html>')):
            with self.subTest(cut=cut):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE[:cut])

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_complete_page_can_contain_only_checking_accounts(self):
        table_start = ACCOUNTS_PAGE.index('<table ', ACCOUNTS_PAGE.index('</table>'))
        page = ACCOUNTS_PAGE[:table_start] + ACCOUNTS_PAGE[ACCOUNTS_PAGE.index('</form>'):]

        accounts, skipped = parse_accounts(page)

        self.assertEqual([account["type"] for account in accounts], ["CHECKING"])
        self.assertEqual(skipped, [])

    def test_the_document_end_is_case_insensitive_and_allows_whitespace(self):
        page = ACCOUNTS_PAGE.replace("</html>", "</HtMl   >\n")

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés"])

    def test_an_unterminated_empty_contract_table_fails_the_whole_page(self):
        page = ACCOUNTS_PAGE.replace('</form>', '<table class="ei_comptescontrats"></form>')

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unterminated_orphan_account_row_fails_the_whole_page(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")
        for ending in ('>LIVRET A', '+1,00 EUR</span>'):
            with self.subTest(ending=ending):
                page = ACCOUNTS_PAGE.replace('</form>', row[:row.index(ending) + len(ending)] + '</form>')

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unknown_table_with_changed_account_markers_still_fails(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")
        for marker in ("ei_sdsf_title", "doux", "ei_sdsf_montant", "ei_sdsf_solde"):
            row = row.replace(marker, "other")

        for tag in ("table", "TABLE"):
            with self.subTest(tag=tag), self.assertRaises(AccountsFormatError):
                page = ACCOUNTS_PAGE.replace('</form>', f'<{tag} class="new_accounts">{row}</{tag}></form>')
                parse_accounts(page)

    def test_account_data_outside_a_parsed_row_fails_the_whole_page(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")
        unparsed_row = row.replace("<tr ", "<div ").replace("</tr>", "</div>")
        for index, page in enumerate((
            ACCOUNTS_PAGE.replace('</form>', row + '</form>'),
            ACCOUNTS_PAGE.replace('</form>', row.replace("<tr ", "<TR ").replace("</tr>", "</TR>") + '</form>'),
            ACCOUNTS_PAGE.replace(
                '</form>', accounts_table("Livrets et comptes d&#39;épargne", unparsed_row) + '</form>'
            ),
        )):
            with self.subTest(index=index), self.assertRaises(AccountsFormatError):
                parse_accounts(page)

    def test_a_missing_row_end_cannot_merge_two_accounts_into_one(self):
        rows = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")
        rows += account_row(1, "LDDS", "10000 000123456 03", "+2,00 EUR")

        with self.assertRaises(AccountsFormatError):
            page = accounts_table("Livrets et comptes d&#39;épargne", rows.replace("</tr>", "", 1))
            parse_accounts(f"<html><body>{page}</body></html>")

    def test_unrelated_tables_and_summary_amounts_do_not_hide_accounts(self):
        extra = '<p>Total : 10 135,06 EUR</p><table class="help"><tr><td>Aide</td></tr></table>'
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés"])

    def test_an_unknown_category_with_account_data_fails_the_whole_page(self):
        page = ACCOUNTS_PAGE.replace("Livrets et comptes d&#39;épargne", "Nouveaux comptes")

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unnamed_table_without_account_data_is_skipped(self):
        extra = '<table class="ei_comptescontrats"><tr><td>Aide</td></tr></table>'
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_captionless_contracts_and_offers_link_is_skipped(self):
        # Seen live: a captionless contract table whose only row is a titled link, with no amount.
        offers = (
            '<table class="_c1 ei_comptescontrats _c1 liste ei_cellspacing"><tbody><tr class="_c1 _c1">'
            '<td class="_c1 decal1 _c1"><a href="https://www.cic.fr/fr/banque/contrats.html">'
            '<span class="_c1 ei_sdsf_title _c1">Vos contrats et nos offres</span></a></td>'
            '<td class="d nowrap "></td><td class="d nowrap ei_sdsf_solde"></td></tr></tbody></table>'
        )
        page = ACCOUNTS_PAGE.replace('</form>', offers + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_captionless_row_with_a_plain_text_symbol_amount_fails_the_page(self):
        for amount in ("$1,234.56", "1 234,56 $", "£12.00", "12,00 £"):
            with self.subTest(amount=amount):
                row = (
                    '<table class="ei_comptescontrats"><tr><td>'
                    f'<span class="ei_sdsf_title">COMPTE EN DEVISE</span> {amount}'
                    '</td></tr></table>'
                )
                page = ACCOUNTS_PAGE.replace('</form>', row + '</form>')

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_script_text_in_a_layout_table_is_not_mistaken_for_money(self):
        extra = '<table><tr><td><script>s.replace(/x/, "$1")</script></td></tr></table>'
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, _ = parse_accounts(page)

        self.assertEqual(len(accounts), 4)

    def test_script_text_in_an_unsupported_contract_row_is_not_mistaken_for_money(self):
        # Inside a selected contract table, so _is_account_row itself reads the script.
        extra = ('<table class="ei_comptescontrats"><tr><td>'
                 '<script>s.replace(/x/, "$1")</script></td></tr></table>')
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_year_before_a_product_name_is_not_an_amount(self):
        extra = ('<table class="ei_comptescontrats"><tr><td>'
                 '<span class="ei_sdsf_title">Contrat 2024 PEL</span></td></tr></table>')
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_whole_euro_amount_in_an_unsupported_row_still_fails_the_page(self):
        extra = ('<table class="ei_comptescontrats"><tr><td>'
                 '<span class="ei_sdsf_title">Nouveau produit</span> 1 234 EUR</td></tr></table>')
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertIn("'Nouveau produit'", str(error.exception))

    def test_a_currency_code_inside_a_product_title_is_not_an_amount(self):
        extra = ('<table class="ei_comptescontrats"><tr><td>'
                 '<span class="ei_sdsf_title">Contrat 2024 USD</span></td></tr></table>')
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_an_amount_in_any_currency_outside_the_title_still_fails_the_page(self):
        extra = ('<table class="ei_comptescontrats"><tr><td>'
                 '<span class="ei_sdsf_title">Compte en roupies</span> 100 INR</td></tr></table>')
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertIn("'Compte en roupies'", str(error.exception))

    def test_an_unknown_category_with_a_balance_is_named_in_the_error(self):
        page = ACCOUNTS_PAGE.replace("Livrets et comptes d&#39;épargne", "Nouveaux comptes")

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertIn("'nouveaux comptes'", str(error.exception))
        self.assertIn("'LIVRET A'", str(error.exception))

    def test_a_captionless_row_with_more_than_a_title_fails_the_page(self):
        title = '<span class="ei_sdsf_title">LIVRET X</span>'
        empty_solde = '<td class="d nowrap ei_sdsf_solde"></td>'
        variants = {
            "bare number in the balance cell":
                f'<td>{title}</td><td class="d nowrap ei_sdsf_solde">1 234,56</td>',
            "account number without an amount span":
                f'<td>{title}<span class="nowrap doux">0001 0000000</span></td>{empty_solde}',
            "amount cell": f'<td>{title}</td><td class="ei_sdsf_montant">1,00 EUR</td>',
            "single-quoted amount span": f"<td>{title}<span class='ei_sdsf_montant'>1,00 EUR</span></td>",
            "code before the amount": f"<td>{title} EUR 1 234,56</td>",
            "symbol before the amount": f"<td>{title} € 1 234</td>",
            "lower-case code": f"<td>{title} 1 234,56 eur</td>",
            "separator before the amount": f"<td>{title} Solde. 1 234,56 EUR</td>",
            "amount nested in the title":
                '<td><span class="ei_sdsf_title">LIVRET X <span>1 234,56 EUR</span></span></td>',
        }
        for name, row in variants.items():
            with self.subTest(name):
                table = f'<table class="ei_comptescontrats"><tr>{row}</tr></table>'
                page = ACCOUNTS_PAGE.replace('</form>', table + '</form>')

                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")
                self.assertIn("unrecognized category", str(error.exception))

    def test_script_text_with_dollar_signs_does_not_fail_the_page(self):
        script = '<script>f(1, $("#a")); var k = "item1${id}";</script>'
        pages = {
            "layout table": f"<table><tr><td>{script}</td></tr></table>",
            "captionless contract row": (
                f'<table class="ei_comptescontrats"><tr><td>{script}'
                '<span class="ei_sdsf_title">Vos contrats et nos offres</span></td></tr></table>'
            ),
        }
        for name, extra in pages.items():
            with self.subTest(name):
                page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

                accounts, _ = parse_accounts(page)

                self.assertEqual(len(accounts), 4)

    def test_a_long_category_name_is_truncated_in_the_error(self):
        page = ACCOUNTS_PAGE.replace("Livrets et comptes d&#39;épargne", "x" * 100)

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(page)

        self.assertIn(repr("x" * 60), str(error.exception))
        self.assertNotIn("x" * 61, str(error.exception))

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
            page = accounts_table("Livrets et comptes d&#39;épargne", row)
            parse_accounts(f"<html><body>{page}</body></html>")

    def test_header_and_action_rows_without_account_markers_are_skipped(self):
        rows = '<tr><th>Compte</th><th>Solde</th></tr><tr><td><a href="/add">Ajouter un compte</a></td></tr>'
        rows += account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")

        page = accounts_table("Livrets et comptes d&#39;épargne", rows)
        accounts, _ = parse_accounts(f"<html><body>{page}</body></html>")

        self.assertEqual([account["id"] for account in accounts], ["1000000012345602"])

    def test_the_tables_read_are_reported_an_empty_one_included(self):
        current = accounts_table("Comptes courants", account_row(0, "COMPTE COURANT", "10000 000123456 01", "+1,00 EUR"))
        empty_savings = accounts_table("Livrets et comptes d&#39;épargne", "")

        self.assertEqual(categories_read(ACCOUNTS_PAGE), ["CHECKING", "SAVINGS"])
        # The customer closed their last livret: the table is there, with no row.
        self.assertEqual(categories_read(current + empty_savings), ["CHECKING", "SAVINGS"])
        # The savings table is missing: nothing is said about the livrets.
        self.assertEqual(categories_read(current), ["CHECKING"])

    def test_a_page_without_contract_table_is_a_format_change(self):
        with self.assertRaises(AccountsFormatError):
            parse_accounts("<html><body>Bienvenue</body></html>")

    def test_the_same_account_listed_twice_is_refused(self):
        row = account_row(0, "LIVRET A", "10000 000123456 02", "+1,00 EUR")

        with self.assertRaises(AccountsFormatError):
            page = accounts_table("Livrets et comptes d&#39;épargne", row + row)
            parse_accounts(f"<html><body>{page}</body></html>")


if __name__ == "__main__":
    unittest.main()
