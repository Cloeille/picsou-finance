"""The "Comptes et contrats" parser, against synthetic pages shaped like the real one."""

import unittest
from decimal import Decimal

from accounts_parser import AccountsFormatError, account_type, categories_read, parse_accounts, parse_amount
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

    def test_footer_totals_promotions_and_legal_text_keep_all_accounts(self):
        fragments = [
            "<p>Total : 10 135,06 EUR</p>",
            '<div class="doux">Mentions légales</div>',
            "<p>Jusqu'à 80 € offerts</p>",
            "<p>© 2026 CIC | 3 ANS | 2 SMS | 2026 EUROPE</p>",
        ]
        fragments.extend(f"<footer>Total : 12,34 {currency}</footer>"
                         for currency in ("EUR", "USD", "GBP", "CHF", "JPY", "AED", "XCG", "ZWG"))
        for fragment in fragments:
            page = ACCOUNTS_PAGE.replace("</body>", f"{fragment}</body>")

            with self.subTest(fragment=fragment):
                self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_plain_text_currency_balances_still_fail_in_account_containers(self):
        for currency in ("EUR", "USD", "GBP", "CHF", "JPY", "AED", "XCG", "ZWG"):
            amount = f"12,34 {currency}"
            row = f"<tr><td>{amount}</td></tr>"
            for fragment in (
                f"<table><tr><td>{amount}</td></tr></table>",
                row,
                f'<table class="ei_comptescontrats">{row}</table>',
                accounts_table("Livrets et comptes d&#39;épargne", row),
            ):
                page = ACCOUNTS_PAGE.replace("</body>", f"{fragment}</body>")

                with self.subTest(currency=currency, fragment=fragment):
                    with self.assertRaises(AccountsFormatError) as error:
                        parse_accounts(page)

                    self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

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
                accounts, skipped = parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"{table}</body>"))

                self.assertEqual(accounts, parse_accounts(ACCOUNTS_PAGE)[0])
                self.assertEqual(skipped, ["autres contrats liés", category])

    def test_a_captionless_row_with_a_plain_text_symbol_amount_fails_the_page(self):
        for amount in ("$12.34", "12.34 $", "£12.34", "12.34 £", "€12,34", "12,34 €"):
            for title in ("", '<span class="ei_sdsf_title">NOUVEAU COMPTE</span>'):
                row = f"<tr><td>{title}</td><td>{amount}</td></tr>"
                table = f'<table class="ei_comptescontrats">{row}</table>'

                with self.subTest(amount=amount, title=bool(title)):
                    with self.assertRaises(AccountsFormatError) as error:
                        parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"{table}</body>"))

                    self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_supported_row_with_plain_text_symbol_balance_is_not_skipped(self):
        for amount in ("$12.34", "12.34 $", "£12.34", "12.34 £"):
            table = accounts_table("Livrets et comptes d&#39;épargne", f"<tr><td>{amount}</td></tr>")

            with self.subTest(amount=amount):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"{table}</body>"))

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_script_text_in_a_layout_table_is_not_mistaken_for_money(self):
        for replacement in ("$1", "$1$2", "$1 $2", "$12.34"):
            for tag in ("script", "SCRIPT"):
                page = (
                    f'<table class="layout"><tr><td><{tag} type="text/javascript">\n'
                    f'const accountLabel = name.replace(/(a)(b)/, "{replacement}");\n'
                    f"</{tag}>"
                )
                page = ACCOUNTS_PAGE.replace(
                    "<form ", page + "<form ", 1
                ).replace("</form>", "</form></td></tr></table>")

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
                accounts, skipped = parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"{table}</body>"))

                self.assertEqual(accounts, parse_accounts(ACCOUNTS_PAGE)[0])
                self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_script_balance_selectors_do_not_change_the_accounts(self):
        page = ACCOUNTS_PAGE.replace("</body>", '<script>document.querySelectorAll(".ei_sdsf_solde");</script></body>')

        self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_style_balance_selectors_do_not_change_the_accounts(self):
        page = ACCOUNTS_PAGE.replace('</body>', '<style>.ei_sdsf_solde { text-align: right; }</style></body>')

        self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_script_and_style_bodies_are_ignored_before_finding_contract_tables(self):
        table = accounts_table("Nouveaux comptes", account_row(9, "LIVRET A", "10000 000999999 01", "+12,34 EUR"))

        for tag in ("script", "SCRIPT", "style", "STYLE"):
            with self.subTest(tag=tag):
                self.assertEqual(
                    parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"<{tag}>{table}</{tag}></body>")),
                    parse_accounts(ACCOUNTS_PAGE),
                )

    def test_balance_marker_names_are_not_balances_without_element_classes(self):
        for marker in ("ei_sdsf_montant", "ei_sdsf_solde"):
            for fragment in (
                f"<p>{marker}</p>",
                f'<div data-marker="{marker}"></div>',
                f'<div class="prefix-{marker}-suffix">Aide</div>',
                f'<div title=\'class="{marker}"\'>Aide</div>',
                f'<!-- <span class="{marker}">N/A</span> -->',
            ):
                with self.subTest(fragment=fragment):
                    page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{fragment}</td></tr></table></body>")
                    self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_commented_title_and_number_markers_keep_all_accounts(self):
        for marker, text in (("ei_sdsf_title", "LIVRET A"), ("doux", "10000 000999999 01")):
            comment = f'<!-- <span class="{marker}">{text}</span> -->'
            page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{comment}</td></tr></table></body>")

            with self.subTest(marker=marker):
                self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_commented_balances_keep_all_accounts(self):
        for fragment in (
            '<!-- <span class="ei_sdsf_montant">+12,34 EUR</span> -->',
            '<!-- Plafond > 12,34 EUR -->',
        ):
            page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{fragment}</td></tr></table></body>")

            with self.subTest(fragment=fragment):
                self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_balances_in_quoted_attributes_keep_all_accounts(self):
        for quote in ('"', "'"):
            fragment = f"<div title={quote}Aide > Plafond 12,34 EUR{quote}>Aide</div>"
            page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{fragment}</td></tr></table></body>")

            with self.subTest(quote=quote):
                self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_visible_balances_after_hidden_html_still_fail_the_page(self):
        for fragment in (
            '<!-- <span class="ei_sdsf_montant">+12,34 EUR</span> -->',
            '<div title="Aide > Plafond 12,34 EUR">Aide</div>',
        ):
            page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{fragment}<div>56,78 EUR</div></td></tr></table></body>")

            with self.subTest(fragment=fragment):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(page)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_title_and_number_marker_references_are_not_element_classes(self):
        for marker in ("ei_sdsf_title", "doux"):
            for fragment in (
                f'<span class="prefix-{marker}-suffix">Aide</span>',
                f'<div title=\'class="{marker}">Aide</span>\'>Aide</div>',
            ):
                page = ACCOUNTS_PAGE.replace("</body>", f"<table><tr><td>{fragment}</td></tr></table></body>")

                with self.subTest(marker=marker, fragment=fragment):
                    self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_actual_title_and_number_class_tokens_outside_tables_keep_all_accounts(self):
        for marker in ("ei_sdsf_title", "doux"):
            for tag in ("span", "div"):
                for classes in (f'"nowrap {marker}"', f"'nowrap {marker}'", marker):
                    fragment = f"<{tag} class={classes}>N/A</{tag}>"
                    page = ACCOUNTS_PAGE.replace("</body>", f"{fragment}</body>")

                    with self.subTest(marker=marker, tag=tag, classes=classes):
                        self.assertEqual(parse_accounts(page), parse_accounts(ACCOUNTS_PAGE))

    def test_actual_account_class_tokens_in_unrecognized_containers_fail_the_page(self):
        for marker in ("ei_sdsf_title", "doux", "ei_sdsf_montant", "ei_sdsf_solde"):
            for classes in (f'"nowrap {marker}"', f"'nowrap {marker}'", marker):
                for tag in ("table", "tr"):
                    fragment = f"<{tag}><td><span class={classes}>N/A</span></td></{tag}>"
                    page = ACCOUNTS_PAGE.replace("</body>", f"{fragment}</body>")

                    with self.subTest(marker=marker, classes=classes, tag=tag):
                        with self.assertRaises(AccountsFormatError) as error:
                            parse_accounts(page)

                        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_plain_text_money_between_scripts_still_fails_the_page(self):
        table = (
            '<table class="layout"><tr><td><script>name.replace(/(a)/, "$1");</script>'
            '12.34 $<script>name.replace(/(b)/, "$1");</script></td></tr></table>'
        )

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"{table}</body>"))

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
            "marked row before": ACCOUNTS_PAGE.replace("<form ", marked_row + "<form ", 1),
            "marked row after": ACCOUNTS_PAGE.replace("</body>", marked_row + "</body>"),
            "plain euro in layout table": ACCOUNTS_PAGE.replace("<form ", f'<table class="layout">{plain_euro_row}</table><form ', 1),
            "plain symbol in layout table": ACCOUNTS_PAGE.replace("</body>", f'<table class="layout">{plain_symbol_row}</table></body>'),
            "contract table lost its class": ACCOUNTS_PAGE.replace("</body>", lost_table + "</body>"),
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
                    parse_accounts(ACCOUNTS_PAGE.replace("</body>", f"<table><tr>{cells}</tr></table></body>"))

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_title_only_row_in_a_supported_table_remains_a_format_change(self):
        rows = '<tr><td><span class="ei_sdsf_title">LIVRET A</span></td></tr>'

        with self.assertRaises(AccountsFormatError) as error:
            parse_accounts("<html><body>" + accounts_table("Livrets et comptes d&#39;épargne", rows) + "</body></html>")

        self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unnamed_table_without_account_data_is_skipped(self):
        extra = '<table class="ei_comptescontrats"><tr><td>Aide</td></tr></table>'
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, skipped = parse_accounts(page)

        self.assertEqual(len(accounts), 4)
        self.assertEqual(skipped, ["autres contrats liés", "unnamed"])

    def test_a_captionless_contracts_and_offers_link_is_skipped(self):
        # A contract link can include an empty balance cell without holding an account.
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

    def test_a_captionless_row_with_a_plain_text_symbol_amount_fails_the_page_in_a_separate_table(self):
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

    def test_script_text_in_a_layout_table_is_not_mistaken_for_money_in_a_separate_table(self):
        extra = '<table><tr><td><script>s.replace(/x/, "$1")</script></td></tr></table>'
        page = ACCOUNTS_PAGE.replace('</form>', extra + '</form>')

        accounts, _ = parse_accounts(page)

        self.assertEqual(len(accounts), 4)

    def test_script_text_in_an_unsupported_contract_row_is_not_mistaken_for_money(self):
        # Inside a selected contract table, so _has_balance itself reads the script.
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

    def test_product_title_exclusion_preserves_html_and_balance_checks(self):
        title = '<span class=\'ei_sdsf_title\'>Contrat <span>2024 USD</span></span>'
        for suffix, fails in (
            ('<td class="ei_sdsf_solde"></td>', False),
            ('<td title="Aide > 12,34 EUR">Aide</td>', False),
            ('<td><!-- 12,34 EUR --></td>', False),
            ('<td>100 INR</td>', True),
            ('<td class="ei_sdsf_solde">12,34 EUR</td>', True),
            ('<td><span class="ei_sdsf_montant">N/A</span></td>', True),
        ):
            table = f'<table class="ei_comptescontrats"><tr><td>{title}</td>{suffix}</tr></table>'
            page = ACCOUNTS_PAGE.replace('</form>', table + '</form>')

            with self.subTest(suffix=suffix):
                if fails:
                    with self.assertRaises(AccountsFormatError) as error:
                        parse_accounts(page)
                    self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")
                else:
                    accounts, skipped = parse_accounts(page)
                    self.assertEqual(accounts, parse_accounts(ACCOUNTS_PAGE)[0])
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
            parse_accounts("<html><body>" + accounts_table("Livrets et comptes d&#39;épargne", row + row) + "</body></html>")








    def test_content_after_the_document_end_is_not_a_complete_page(self):
        for fragment in ("<script>ignored()</script>", "<style>p { color: grey; }</style>", "<p>Aide</p>"):
            with self.subTest(fragment=fragment):
                with self.assertRaises(AccountsFormatError) as error:
                    parse_accounts(ACCOUNTS_PAGE + fragment)

                self.assertEqual(error.exception.code, "UPSTREAM_FORMAT_CHANGED")









if __name__ == "__main__":
    unittest.main()
