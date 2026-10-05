import unittest

from portfolio_parser import PortfolioFormatError, decimal_value, parse_portfolio, unwrap_message


class PortfolioParserTest(unittest.TestCase):
    def test_decimal_parses_french_values(self):
        self.assertEqual(str(decimal_value("1 234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("1.234,56 €")), "1234.56")
        self.assertEqual(str(decimal_value("-12,30 %")), "-12.30")

    def test_missing_or_invalid_decimal_is_never_coerced_to_zero(self):
        with self.assertRaises(PortfolioFormatError):
            decimal_value(None, "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("not-a-number", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("1e3", "balance")
        with self.assertRaises(PortfolioFormatError):
            decimal_value("12oops34", "balance")

    def test_null_stream_is_rejected(self):
        self.assertIsNone(unwrap_message("message='NULL';"))
        self.assertIsNone(parse_portfolio("NULL", 1))

    def test_html_page_is_rejected(self):
        self.assertIsNone(parse_portfolio("<html><head></head></html>", 1))

    def test_synthetic_stream_maps_account_cash_and_position(self):
        portfolio = ["PEA Bourse Direct", "1250,00", "", "250,00"] + [""] * 7 + ["ABC123"]
        stream = (
            "message='" + "{".join(portfolio)
            + "|ACME#FR0000000001{X-EUR-cash}#10#80,00#100,00#1000,00|1';"
        )
        parsed = parse_portfolio(stream, 1)
        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["externalId"], "ABC123")
        self.assertEqual(parsed["type"], "PEA")
        self.assertEqual(parsed["cashBalance"], "250.00")
        self.assertEqual(parsed["positions"][0]["quantity"], "10")
        self.assertEqual(parsed["positions"][0]["buyingPriceEur"], "80.00")
        self.assertEqual(parsed["positions"][0]["currentValueEur"], "1000.00")
        self.assertTrue(parsed["snapshotComplete"])

    def test_foreign_legacy_buying_price_is_not_mislabeled_as_eur(self):
        portfolio = ["CTO Bourse Direct", "90,00", "", "0,00"] + [""] * 7 + ["ABC123"]
        stream = (
            "message='" + "{".join(portfolio)
            + "|US share#US0000000001{X-USD-cash}#1#80,00#100,00#90,00|1';"
        )

        parsed = parse_portfolio(stream, 1)

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed["positions"][0]["quoteCurrency"], "USD")
        self.assertIsNone(parsed["positions"][0]["buyingPriceEur"])
        self.assertIsNone(parsed["positions"][0]["pnlEur"])

    def test_real_account_selector_overrides_stream_identity(self):
        portfolio = ["Legacy label", "250,00", "", "250,00"] + [""] * 7 + ["legacy-id"]
        stream = "message='" + "{".join(portfolio) + "|1';"
        parsed = parse_portfolio(stream, 1, "account-42", "PEA principal")
        self.assertEqual(parsed["externalId"], "account-42")
        self.assertEqual(parsed["name"], "PEA principal")
        self.assertEqual(parsed["type"], "PEA")

    def test_incomplete_legacy_positions_reject_the_whole_snapshot(self):
        portfolio = ["PEA", "1250,00", "", "250,00"] + [""] * 7 + ["ABC123"]
        missing_valuation = (
            "message='" + "{".join(portfolio)
            + "|ACME#FR0000000001-USD-cash#10#80,00#100,00#|1';"
        )
        missing_position = "message='" + "{".join(portfolio) + "|1';"

        self.assertIsNone(parse_portfolio(missing_valuation, 1))
        self.assertIsNone(parse_portfolio(missing_position, 1))


if __name__ == "__main__":
    unittest.main()
