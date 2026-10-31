"""Page parsers, run against synthetic pages shaped like the real ones."""

import unittest
from decimal import Decimal

from fixtures import (
    FUND_SAVINGS,
    PEG_ROWS,
    SITUATION_PAGE,
    fund_row,
    fund_savings_page,
    fund_summary_page,
    plan_table,
    situation_page,
)
from forms import find_form, field_names, parse_forms
from fund_parser import _decimal, my_savings_tab, parse_fund_identity, parse_my_savings
from situation_parser import (
    FORMAT_CHANGED,
    INCOMPLETE,
    SituationFormatError,
    parse_money,
    parse_percent,
    parse_profile,
    parse_situation,
    plan_kind,
)


class NumbersTest(unittest.TestCase):
    def test_reads_french_amounts_with_every_kind_of_space(self):
        self.assertEqual(parse_money("22&nbsp;427,72 &#160; &#8364;"), Decimal("22427.72"))
        self.assertEqual(parse_money("+4 171,84 €"), Decimal("4171.84"))
        self.assertEqual(parse_money("-12,83 €"), Decimal("-12.83"))

    def test_reads_signed_percentages(self):
        self.assertEqual(parse_percent("+ 22,85 %"), Decimal("22.85"))
        self.assertEqual(parse_percent("-1,43 %"), Decimal("-1.43"))

    def test_an_amount_without_euro_sign_is_refused_not_read_as_zero(self):
        for raw in ("", "N/A", "1 234,56", "1 234,56 USD"):
            with self.subTest(raw=raw), self.assertRaises(SituationFormatError):
                parse_money(raw)


class SituationTest(unittest.TestCase):
    def test_reads_each_plan_and_its_funds(self):
        plans = parse_situation(SITUATION_PAGE)

        self.assertEqual(len(plans), 1)
        plan = plans[0]
        self.assertEqual(plan["name"], "Plan d'épargne groupe")
        self.assertEqual(plan["planKind"], "PEG")
        self.assertEqual(plan["balanceEur"], Decimal("1500.50"))
        self.assertEqual(
            [(f["label"], f["button"], f["valueEur"], f["gainPercent"]) for f in plan["funds"]],
            [
                ("FONDS EQUILIBRE SOLIDAIRE", "_FID_GoFundSummary_selectedFundIndex:0", Decimal("1000.00"), Decimal("10.00")),
                ("FONDS ACTIONS MONDE", "_FID_GoFundSummary_selectedFundIndex:1", Decimal("500.50"), Decimal("-2.50")),
            ],
        )

    def test_reads_the_employer_and_saver_number(self):
        self.assertEqual(parse_profile(SITUATION_PAGE), ("ACME INDUSTRIES", "12345"))

    def test_a_retirement_plan_is_told_apart_from_the_group_plan(self):
        page = situation_page(
            plan_table("Plan d&#39;&#233;pargne groupe", "1&nbsp;500,50", PEG_ROWS)
            + plan_table(
                "Plan d&#39;&#233;pargne retraite collectif",
                "300,00",
                fund_row(2, "FONDS RETRAITE", "300,00", "+ 1,00"),
            )
        )

        self.assertEqual([plan["planKind"] for plan in parse_situation(page)], ["PEG", "PERCOL"])

    def test_plan_kinds(self):
        self.assertEqual(plan_kind("Plan d'épargne entreprise"), "PEE")
        self.assertEqual(plan_kind("Compte courant bloqué"), "CCB")
        self.assertIsNone(plan_kind("Something new"))

    def test_funds_that_do_not_add_up_to_the_plan_total_are_refused(self):
        page = situation_page(plan_table("Plan d&#39;&#233;pargne groupe", "9&nbsp;999,99", PEG_ROWS))

        with self.assertRaises(SituationFormatError) as raised:
            parse_situation(page)
        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_a_fund_row_without_its_value_fails_the_page(self):
        broken = PEG_ROWS.replace('<span class="_c1 nowrap _c1">500,50', '<span class="_c1 _c1">500,50')
        page = situation_page(plan_table("Plan d&#39;&#233;pargne groupe", "1&nbsp;500,50", broken))

        with self.assertRaises(SituationFormatError) as raised:
            parse_situation(page)
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_page_without_plan_table_is_a_format_change(self):
        with self.assertRaises(SituationFormatError) as raised:
            parse_situation("<html><body>Bienvenue</body></html>")
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_an_empty_plan_is_kept_with_no_fund(self):
        page = situation_page(plan_table("Plan d&#39;&#233;pargne entreprise", "0,00", ""))

        plan = parse_situation(page)[0]
        self.assertEqual((plan["balanceEur"], plan["funds"]), (Decimal("0.00"), []))


class FundPagesTest(unittest.TestCase):
    def test_reads_units_price_and_gain(self):
        details = parse_my_savings(FUND_SAVINGS[0])

        self.assertEqual(details["valueEur"], Decimal("1000.00"))
        self.assertEqual(details["pnlEur"], Decimal("90.91"))
        self.assertEqual(details["quantity"], Decimal("50.0000"))
        self.assertEqual(details["unitValue"], Decimal("20.0000"))
        self.assertEqual(str(details["priceDate"]), "2026-10-22")

    def test_a_negative_gain_keeps_its_sign(self):
        self.assertEqual(parse_my_savings(FUND_SAVINGS[1])["pnlEur"], Decimal("-12.83"))

    def test_units_that_do_not_explain_the_value_are_refused(self):
        with self.assertRaises(SituationFormatError):
            parse_my_savings(fund_savings_page("1&nbsp;000,00", "+90,91", "5,0000", "20,0000"))

    def test_finds_the_my_savings_tab_not_the_header_link_of_the_same_name(self):
        tab = my_savings_tab(fund_summary_page(1))

        self.assertIn("synthese-du-support.html", tab)
        self.assertIn("k_fundIndex=1&_fid=GoMyAsset", tab)

    def test_reads_the_fund_code_and_no_isin_when_none_is_shown(self):
        self.assertEqual(parse_fund_identity(fund_summary_page(0, code="1613")), (None, "1613"))


class UnitsTest(unittest.TestCase):
    def test_a_non_finite_number_of_units_is_a_format_change(self):
        for raw in ("NaN", "Infinity", "-inf"):
            with self.subTest(raw=raw), self.assertRaises(SituationFormatError) as raised:
                _decimal(raw)
            self.assertEqual(raised.exception.code, FORMAT_CHANGED)


class FormsTest(unittest.TestCase):
    def test_finds_the_form_holding_a_fund_button_with_its_hidden_state(self):
        form = find_form(SITUATION_PAGE, button="_FID_GoFundSummary_selectedFundIndex:1")

        self.assertIsNotNone(form)
        self.assertEqual(form.method, "post")
        self.assertIn("situation-financiere-detaillee", form.action)
        self.assertEqual(form.fields["$CPT"], "opaque-page-state")
        # Buttons are sent only when pressed.
        self.assertNotIn("_FID_GoFundSummary_selectedFundIndex:0", form.fields)

    def test_only_checked_boxes_are_sent(self):
        [form] = parse_forms(
            '<form action="/a">'
            '<input type="checkbox" name="consent" value="unchecked" />'
            '<input type="checkbox" name="remember" checked />'
            '<input type="radio" name="channel" value="sms" checked="checked" />'
            "</form>"
        )

        self.assertEqual(form.fields, {"remember": "on", "channel": "sms"})

    def test_field_names_never_carry_values(self):
        names = field_names(SITUATION_PAGE)

        self.assertIn("$CPT", names)
        self.assertNotIn("opaque-page-state", " ".join(names))


if __name__ == "__main__":
    unittest.main()
