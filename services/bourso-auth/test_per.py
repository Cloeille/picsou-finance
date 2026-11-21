"""A BoursoBank PER: its dashboard card and its server-rendered positions page (#351)."""
import unittest
from decimal import Decimal

from accounts_parser import AccountsFormatError, account_type, parse_dashboard, parse_per
from fixtures import (
    EUROPE_ROW,
    PER_HTML,
    PER_ID,
    WORLD_ROW,
    dashboard_with_per,
    per_compartment,
    per_page,
    per_row,
)


def single(rows, total, solde):
    return per_page(per_compartment("Épargne Volontaire Déductible", rows, total), solde=solde)


class PerCardTest(unittest.TestCase):
    def test_a_per_card_in_the_trading_section_is_a_per(self):
        accounts, _ = parse_dashboard(dashboard_with_per())

        plan = next(account for account in accounts if account["id"] == PER_ID)
        self.assertEqual(plan["type"], "PER")
        self.assertEqual(plan["href"], f"/compte/per/{PER_ID}")
        # The PEA next to it keeps its own type.
        self.assertIn("PEA", [account["type"] for account in accounts])

    def test_a_per_link_is_a_per_whatever_its_label(self):
        # Not a COMPTE_TITRES: that would send it to the trading board, which 404s.
        self.assertEqual(account_type("trading", "Mon épargne retraite", f"/compte/per/{PER_ID}"), "PER")


class PerPageTest(unittest.TestCase):
    def test_reads_the_solde_and_merges_a_fund_held_in_several_compartments(self):
        plan = parse_per(PER_HTML)

        self.assertEqual(plan["totalEur"], Decimal("2230.00"))
        self.assertEqual(len(plan["positions"]), 2)
        world = next(position for position in plan["positions"] if position["isin"] == "IE00BYX2JD69")
        self.assertEqual(world["symbol"], "1rASUSW")
        self.assertEqual(world["quantity"], Decimal("100.0000"))
        self.assertEqual(world["currentValueEur"], Decimal("1200.00"))
        # Cost price weighted by units: (50 x 10 + 50 x 8) / 100.
        self.assertEqual(world["buyingPriceEur"], Decimal("9"))
        self.assertEqual(world["pnlEur"], Decimal("300.00"))
        # Every line was rounded on its own, before the merge: the backend's allowance counts them all.
        self.assertEqual(plan["valuationLineCount"], 4)

    def test_a_payment_not_yet_converted_into_units_is_cash(self):
        plan = parse_per(PER_HTML)

        self.assertEqual(plan["cashEur"], Decimal("30.00"))
        self.assertEqual(sum(p["currentValueEur"] for p in plan["positions"]) + plan["cashEur"], plan["totalEur"])

    def test_a_missing_line_is_refused(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "1 600,00 €", "1 600,00 €"))
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_a_missing_compartment_is_refused(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "600,00 €", "2 230,00 €"))
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_the_rounding_of_each_line_is_allowed(self):
        # Two lines each rounded up by up to a cent: 0.02 over, inside one cent per line.
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,01 €")
                + per_row(*EUROPE_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,01 €"))
        plan = parse_per(single(rows, "24,00 €", "24,00 €"))
        self.assertEqual(plan["totalEur"], Decimal("24.00"))

    def test_any_other_text_in_a_number_cell_is_refused(self):
        rows = per_row(*WORLD_ROW, "n/a", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "600,00 €", "600,00 €"))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_en_cours_is_only_accepted_on_a_payment_awaiting_conversion(self):
        # Units are known, so this is not a pending payment: "En cours" cannot stand for a price.
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "En cours", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError):
            parse_per(single(rows, "600,00 €", "600,00 €"))

    def test_a_line_without_units_is_refused(self):
        rows = per_row(*WORLD_ROW, "", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError):
            parse_per(single(rows, "600,00 €", "600,00 €"))

    def test_a_compartment_without_its_total_row_is_refused(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, None, "600,00 €"))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_line_of_an_unexpected_shape_is_refused(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        eight_cells = rows.replace("</tr>", '<td class="c-table__cell">22/10/2026</td></tr>')
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(eight_cells, "600,00 €", "600,00 €"))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_page_without_a_solde_is_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(PER_HTML.replace("Solde au", "Valorisation au"))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_a_page_without_a_supports_table_is_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(per_page(solde="0,00 €"))
        self.assertEqual(raised.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_two_lines_without_an_isin_are_never_merged(self):
        # Same label, no ISIN: nothing proves they are one instrument (the backend's own rule).
        rows = (per_row("Fonds Maison", None, None, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
                + per_row("Fonds Maison", None, None, "2,0000", "11,00 €", "12,00 €", "2,00 €", "9,09 %", "24,00 €"))
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "36,00 €", "36,00 €"))
        self.assertEqual(raised.exception.code, "INVALID_DATA")

    def test_a_line_missing_its_isin_is_not_merged_into_the_fund_that_has_one(self):
        # Merged, it would take an identity it cannot prove; kept apart, it would land on the
        # fund's ticker, which the backend refuses for the whole sync. Refused here: the PER is skipped.
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
                + per_row(WORLD_ROW[0], None, WORLD_ROW[2], "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €"))
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "24,00 €", "24,00 €"))
        self.assertEqual(raised.exception.code, "INVALID_DATA")

    def test_the_weighted_cost_price_is_rounded_like_the_backend_merge(self):
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
                + per_row(*WORLD_ROW, "2,0000", "11,00 €", "12,00 €", "2,00 €", "9,09 %", "24,00 €"))
        plan = parse_per(single(rows, "36,00 €", "36,00 €"))
        self.assertEqual(str(plan["positions"][0]["buyingPriceEur"]), "10.66666667")

    def test_small_gaps_that_add_up_past_the_backend_allowance_are_refused(self):
        # Each compartment 0.05 under its total and the totals 0.05 under the Solde pass one
        # by one, but 0.20 over 3 lines is past what the backend allows for the whole plan.
        compartments = [per_compartment(name, per_row(*WORLD_ROW, "1,0000", "10,00 €", "100,00 €",
                                                      "90,00 €", "900,00 %", "100,00 €"), "100,05 €")
                        for name in ("A", "B", "C")]
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(per_page(*compartments, solde="300,20 €"))
        self.assertEqual(raised.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_a_solde_box_shown_twice_is_read_when_both_agree(self):
        box = "Solde au 12/11/2026 </div><div> 2 230,00 €"
        twice = PER_HTML.replace("<body>", f"<body><div>{box}</div>")
        self.assertEqual(parse_per(twice)["totalEur"], Decimal("2230.00"))
        with self.assertRaises(AccountsFormatError):
            parse_per(PER_HTML.replace("<body>", "<body><div>Solde au 12/11/2026 1,00 €</div>"))

    def test_a_total_row_padded_to_seven_cells_is_read(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        padded = '<td class="c-table__cell"></td>' * 5
        page = single(rows, "600,00 €", "600,00 €").replace(
            '<td class="c-table__cell c-table__cell--right"><div class="c-table__cell-wrapper">'
            '<div class="c-table__cell-content-wrapper">600,00 €</div></div></td></tr></tbody>',
            padded + '<td class="c-table__cell">600,00 €</td></tr></tbody>',
        )
        self.assertEqual(parse_per(page)["totalEur"], Decimal("600.00"))

    def test_a_fund_named_total_is_a_line_not_a_total(self):
        rows = per_row("Total Return Fund", "FR0000000003", "1rTOT", "1,0000", "10,00 €", "12,00 €",
                       "2,00 €", "20,00 %", "12,00 €")
        self.assertEqual(len(parse_per(single(rows, "12,00 €", "12,00 €"))["positions"]), 1)

    def test_a_euro_fund_is_cash(self):
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
                + per_row("Fonds Euro Retraite", None, None, "", "", "", "", "", "400,00 €"))
        plan = parse_per(single(rows, "412,00 €", "412,00 €"))
        self.assertEqual(plan["cashEur"], Decimal("400.00"))
        self.assertEqual(len(plan["positions"]), 1)

    def test_a_line_without_units_that_is_not_a_euro_fund_is_refused(self):
        rows = per_row("Fonds Europe Actions", None, None, "", "", "", "", "", "400,00 €")
        with self.assertRaises(AccountsFormatError):
            parse_per(single(rows, "400,00 €", "400,00 €"))

    def test_a_table_that_is_not_a_compartment_is_left_out(self):
        other = ('<table data-table-trading-operations><thead><tr><th>Date</th><th>Opération</th>'
                 '<th>Montant</th></tr></thead><tbody><tr><td>12/11/2026</td><td>Versement</td>'
                 '<td>30,00 €</td></tr></tbody></table>')
        self.assertEqual(parse_per(PER_HTML.replace("</body>", other + "</body>"))["totalEur"], Decimal("2230.00"))

    def test_a_merged_fund_keeps_the_price_one_compartment_shows(self):
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "-", "2,00 €", "20,00 %", "12,00 €")
                + per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €"))
        plan = parse_per(single(rows, "24,00 €", "24,00 €"))
        self.assertEqual(plan["positions"][0]["currentPrice"], Decimal("12.00"))

    def test_a_negative_solde_with_a_unicode_minus_is_read(self):
        rows = per_row(*WORLD_ROW, "0,0000", "", "", "", "", "0,00 €")
        page = single(rows, "0,00 €", "−0,01 €")
        self.assertEqual(parse_per(page)["totalEur"], Decimal("-0.01"))

    def test_a_total_row_with_a_colon_is_read(self):
        rows = per_row(*WORLD_ROW, "50,0000", "10,00 €", "12,00 €", "100,00 €", "20,00 %", "600,00 €")
        page = single(rows, "600,00 €", "600,00 €").replace(">Total<", ">Total :<")
        self.assertEqual(parse_per(page)["totalEur"], Decimal("600.00"))

    def test_one_symbol_naming_two_isins_is_refused_rather_than_merged(self):
        rows = (per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
                + per_row("Autre fonds", "FR0000000002", "1rASUSW",
                          "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €"))
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "24,00 €", "24,00 €"))
        self.assertEqual(raised.exception.code, "INVALID_DATA")

    def test_a_line_with_negative_units_is_refused(self):
        rows = per_row(*WORLD_ROW, "-1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(single(rows, "12,00 €", "12,00 €"))
        self.assertEqual(raised.exception.code, "INVALID_DATA")

    def test_a_fund_cannot_net_to_zero_units_across_compartments(self):
        # +1 and -1 unit would merge into a zero-unit position still worth 24 €.
        compartments = (
            per_compartment("A", per_row(*WORLD_ROW, "1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €"),
                            "12,00 €"),
            per_compartment("B", per_row(*WORLD_ROW, "−1,0000", "10,00 €", "12,00 €", "2,00 €", "20,00 %", "12,00 €"),
                            "12,00 €"),
        )
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(per_page(*compartments, solde="24,00 €"))
        self.assertEqual(raised.exception.code, "INVALID_DATA")


class PerDiagnosticsTest(unittest.TestCase):
    """A refused reconciliation names the page's shape, never an amount (#624)."""

    def assertShapeOnly(self, message, *expected):
        for part in expected:
            self.assertIn(part, message)
        # No euro sign and no amount, French or Decimal-formatted ("1 700,00", "1700.00"):
        # the gap's ratio, with 4 decimals, is the only number with any.
        self.assertNotIn("€", message)
        self.assertNotRegex(message, r"\d[.,]\d{2}(?!\d)")

    def test_a_compartment_gap_names_the_compartment_and_the_page_shape(self):
        page = PER_HTML.replace("1 600,00 €", "1 700,00 €")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(page)
        self.assertShapeOnly(str(raised.exception), "compartment 1 of 3", "2 line(s), 0 pending")

    def test_a_solde_gap_names_compartments_lines_and_pending_payments(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(PER_HTML.replace("2 230,00 €", "2 330,00 €"))
        self.assertShapeOnly(str(raised.exception), "3 compartment(s)", "4 line(s)", "1 pending")

    def test_a_gap_between_all_lines_and_the_solde_names_the_page_shape(self):
        # Each check passes on its own; only the lines-vs-Solde one fails (see PerPageTest).
        compartments = [per_compartment(name, per_row(*WORLD_ROW, "1,0000", "10,00 €", "100,00 €",
                                                      "90,00 €", "900,00 %", "100,00 €"), "100,05 €")
                        for name in ("A", "B", "C")]
        with self.assertRaises(AccountsFormatError) as raised:
            parse_per(per_page(*compartments, solde="300,20 €"))
        self.assertIn("lines do not reconcile with the Solde", str(raised.exception))
        self.assertShapeOnly(str(raised.exception), "3 compartment(s)", "3 line(s)", "0 pending")


if __name__ == "__main__":
    unittest.main()
