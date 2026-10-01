"""Portfolio parser tests.

The fixtures are the real shape of `3,clients.html`, trimmed of the client's
name and chart scripts. What matters is not that a hand-written fragment parses
but that the parts the portal actually varies are refused rather than
half-read: a French-formatted number, a missing withdrawal price, a row whose
funds do not add up to the page's own total.
"""

import unittest
from decimal import Decimal

from positions_parser import PositionsFormatError, parse_portfolio


def page(rows_html: str, total_html: str = "", header: bool = True) -> str:
    thead = "<th>Nombre de parts</th><th>Valeur unitaire*</th>" if header else ""
    return (
        "<html><body>"
        "<span>Portefeuille SCPI au 27/09/2026</span>"
        f"<table><thead><tr>{thead}</tr></thead><tbody>{rows_html}{total_html}</tbody>"
        "</table></body></html>"
    )


def fund_row(
    label: str = "FONDS-EXEMPLE",
    parts: str = "3.00000",
    tenure: str = "Pleine propriété",
    unit: str = "100.00",
    total: str = "300.00",
    code: str = "XY",
) -> str:
    return f"""
    <tr class="line_marron">
      <td>{label}</td>
      <td class="sepmillier">{parts}</td>
      <td>{tenure}</td>
      <td><span class="sepmillier">{unit}</span> &euro;</td>
      <td><span class="sepmillier">{total}</span> &euro;</td>
      <td><a href="scripts/ajax/rapportdetail.php?Code_Produit={code}">Rapport detaille</a></td>
    </tr>"""


def total_row(parts: str = "3.00000", total: str = "300.00") -> str:
    return f"""
    <tr class="titre_table">
      <td>Total</td>
      <td class="sepmillier">{parts}</td>
      <td></td><td></td>
      <td><span class="sepmillier">{total}</span> &euro;</td>
      <td></td>
    </tr>"""


class ParsesRealPage(unittest.TestCase):
    def test_reads_fund_quantity_and_withdrawal_price(self):
        snapshot = parse_portfolio(page(fund_row(), total_row()))

        self.assertEqual(snapshot["valuationDate"], "2026-09-27")
        self.assertEqual(len(snapshot["holdings"]), 1)
        holding = snapshot["holdings"][0]
        self.assertEqual(holding["fundCode"], "XY")
        self.assertEqual(holding["label"], "FONDS-EXEMPLE")
        self.assertEqual(holding["shareCount"], Decimal("3.00000"))
        self.assertEqual(holding["withdrawalPriceEur"], Decimal("100.00"))
        self.assertEqual(holding["totalEur"], Decimal("300.00"))
        self.assertTrue(snapshot["snapshotComplete"])

    def test_reads_a_fractional_share_count(self):
        # A scheduled purchase or a reinvested dividend lands on fractions
        # routinely, so a whole-share-only read would misprice the account.
        snapshot = parse_portfolio(page(fund_row(parts="12.34567", total="3870.06"),
                                        total_row(parts="12.34567", total="3870.06")))

        self.assertEqual(snapshot["holdings"][0]["shareCount"], Decimal("12.34567"))

    def test_reads_several_funds(self):
        rows = fund_row(code="XY") + fund_row(
            label="IMMORENTE", parts="1.00000", unit="412.00", total="412.00", code="IM"
        )
        snapshot = parse_portfolio(
            page(rows, total_row(parts="4.00000", total="712.00"))
        )

        self.assertEqual(
            [h["fundCode"] for h in snapshot["holdings"]], ["XY", "IM"]
        )

    def test_ignores_the_tenure_column(self):
        # "Pleine propriété" is not a value and must not be parsed as one.
        snapshot = parse_portfolio(page(fund_row(tenure="Pleine propriété"), total_row()))

        self.assertEqual(snapshot["holdings"][0]["withdrawalPriceEur"], Decimal("100.00"))


class MissingWithdrawalPrice(unittest.TestCase):
    def test_missing_unit_value_keeps_the_price_null(self):
        # The share count is still known and still worth writing; the balance
        # stays alone and the position reports PRICE_INCOMPLETE, exactly as a
        # manual entry with no price would.
        snapshot = parse_portfolio(page(fund_row(unit=""), total_row(total="300.00")))

        holding = snapshot["holdings"][0]
        self.assertIsNone(holding["withdrawalPriceEur"])
        self.assertEqual(holding["shareCount"], Decimal("3.00000"))

    def test_zero_is_a_price_not_a_missing_one(self):
        snapshot = parse_portfolio(page(fund_row(unit="0.00", total="0.00"),
                                        total_row(parts="3.00000", total="0.00")))

        self.assertEqual(snapshot["holdings"][0]["withdrawalPriceEur"], Decimal("0.00"))


class RefusesPartialOrChangedPayloads(unittest.TestCase):
    def test_empty_portfolio_requires_a_readable_exact_zero_total(self):
        for total, code in (("300.00", "PORTFOLIO_INCOMPLETE"),
                            ("0.01", "PORTFOLIO_INCOMPLETE"),
                            ("n/c", "INVALID_DATA"),
                            ("NaN", "INVALID_DATA"),
                            ("Infinity", "INVALID_DATA")):
            with self.subTest(total=total):
                with self.assertRaises(PositionsFormatError) as ctx:
                    parse_portfolio(page("", total_row(parts="0", total=total)))
                self.assertEqual(ctx.exception.code, code)

    def test_empty_portfolio_without_a_total_is_refused(self):
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(""))
        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_login_form_is_an_expired_session_not_an_empty_portfolio(self):
        # The portal answers 200 with the login form for a dead session. Reading
        # that as "no funds" would wipe every Sofidy-linked account's balance.
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio('<form id="connexion_espace_partenaire"></form>')

        self.assertEqual(ctx.exception.code, "SESSION_EXPIRED")

    def test_page_without_the_table_is_an_upstream_change(self):
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio("<html><body>Maintenance</body></html>")

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_funds_that_do_not_add_up_to_the_declared_total_are_refused(self):
        # A dropped row is invisible in the payload: every holding present looks
        # valid. The total row is the only place a partial read shows up.
        rows = fund_row(code="XY") + fund_row(
            label="IMMORENTE", parts="1.00000", unit="412.00", total="412.00", code="IM"
        )
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(rows, total_row(parts="3.00000", total="5000.00")))

        self.assertEqual(ctx.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_fund_rows_without_a_readable_total_row_are_refused(self):
        # The total row is the only place a partial read shows up, so losing it
        # has to be a refusal: reading "no total" as "nothing to check" would let
        # a refonte that drops the funds pass a smaller portfolio as complete.
        rows = fund_row(code="XY")
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(rows, ""))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unreadable_total_value_is_refused_rather_than_skipped(self):
        # A comma-formatted total used to be swallowed, which disabled the check
        # without saying so. The format change is now the answer.
        rows = fund_row(code="XY")
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(
                page(rows, total_row(parts="1.00000", total="1\u202f234,00"))
            )

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_one_cent_of_drift_is_still_a_pass(self):
        rows = fund_row(code="XY") + fund_row(
            label="IMMORENTE", parts="1.00000", unit="412.00", total="412.00", code="IM"
        )
        snapshot = parse_portfolio(
            page(rows, total_row(parts="4.00000", total="712.01"))
        )

        self.assertEqual(len(snapshot["holdings"]), 2)

    def test_duplicate_fund_codes_are_refused(self):
        # Two rows sharing a product code cannot both be matched to one account;
        # picking either would write one holding over the other.
        rows = fund_row(code="XY") + fund_row(code="XY")
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(rows, total_row()))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_row_without_a_product_code_is_refused(self):
        row = fund_row().replace("?Code_Produit=XY", "")
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(row, total_row()))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_comma_formatted_number_is_a_format_change_not_a_value(self):
        # `1,234` read as 1.234 would be a 1000x error in somebody's net worth.
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(fund_row(parts="1,5"), total_row(parts="1,5")))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_non_numeric_quantity_is_refused(self):
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(fund_row(parts="n/c"), total_row()))

        self.assertEqual(ctx.exception.code, "INVALID_DATA")

    def test_truncated_fund_row_is_not_an_empty_portfolio(self):
        # Three cells, a zero total, and no product code. Skipping the row
        # would read this as a sold-out portfolio and zero every linked account.
        short = "<tr><td>FONDS-EXEMPLE</td><td>3.00000</td><td>Pleine propriété</td></tr>"
        with self.assertRaises(PositionsFormatError) as ctx:
            parse_portfolio(page(short, total_row(parts="0", total="0.00")))

        self.assertEqual(ctx.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_empty_portfolio_is_a_real_state(self):
        # A holder who sold every fund still gets the totals row. That is a
        # complete, empty portfolio, not a failure.
        snapshot = parse_portfolio(page("", total_row(parts="0.00000", total="0.00")))

        self.assertEqual(snapshot["holdings"], [])
        self.assertTrue(snapshot["snapshotComplete"])


if __name__ == "__main__":
    unittest.main()
