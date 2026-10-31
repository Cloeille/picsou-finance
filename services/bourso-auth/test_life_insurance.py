"""Assurance-vie contracts: the dashboard card, the positions page, the sync payload."""

import unittest
from decimal import Decimal

import httpx

from accounts_parser import (
    FORMAT_CHANGED,
    INCOMPLETE,
    AccountsFormatError,
    guard_symbol_collisions,
    parse_dashboard,
    parse_life_insurance,
)
from fixtures import (
    LIFE_INSURANCE_HTML,
    LIFE_INSURANCE_ID,
    LIFE_INSURANCE_ROWS,
    dashboard_with_life_insurance,
    life_insurance_page,
    life_insurance_row,
)
from main import _collect_accounts
from test_main import build_client, default_handler


class LifeInsuranceCardTest(unittest.TestCase):
    def test_a_contract_card_without_trailing_slash_is_read_as_an_assurance_vie(self):
        accounts, _ = parse_dashboard(dashboard_with_life_insurance())

        contract = next(account for account in accounts if account["id"] == LIFE_INSURANCE_ID)
        self.assertEqual(contract["type"], "ASSURANCE_VIE")
        self.assertEqual(contract["href"], f"/compte/assurance-vie/{LIFE_INSURANCE_ID}")
        self.assertEqual(contract["balanceEur"], Decimal("3300.00"))
        # The PEA next to it keeps its own type.
        self.assertIn("PEA", [account["type"] for account in accounts])


class LifeInsurancePageTest(unittest.TestCase):
    def test_reads_unit_linked_lines_and_the_euro_fund(self):
        contract = parse_life_insurance(LIFE_INSURANCE_HTML)

        self.assertEqual(contract["totalEur"], Decimal("3300.00"))
        # The euro fund has no units: it is the contract's cash leg.
        self.assertEqual(contract["cashEur"], Decimal("1100.00"))
        first, second = contract["positions"]
        self.assertEqual(
            (first["isin"], first["symbol"], first["quantity"], first["buyingPriceEur"],
             first["currentPrice"], first["currentValueEur"], first["pnlEur"]),
            ("IE00BYX2JD69", "1rASUSW", Decimal("100.0000"), Decimal("10.00"),
             Decimal("12.00"), Decimal("1200.00"), Decimal("200.00")),
        )
        # BoursoBank prints negatives with U+2212, not ASCII '-'.
        self.assertEqual(second["pnlEur"], Decimal("-40.00"))
        self.assertEqual(second["quoteCurrency"], "EUR")

    def test_a_euro_fund_shown_with_zero_units_is_the_cash_leg(self):
        rows = (
            life_insurance_row("iShares MSCI World SRI UCITS ETF", "IE00BYX2JD69", "1rASUSW",
                               "100,0000", "10,00 €", "12,00 €", "1 200,00 €", "200,00 €")
            + life_insurance_row("Fonds en Euro Eurossima", None, None,
                                 "0,0000", "0,00 €", "0,00 €", "2 100,00 €", "0,00 €")
        )

        contract = parse_life_insurance(life_insurance_page(rows))

        self.assertEqual(contract["cashEur"], Decimal("2100.00"))
        self.assertEqual([position["isin"] for position in contract["positions"]], ["IE00BYX2JD69"])

    def test_a_unit_linked_line_with_zero_units_but_a_value_is_refused(self):
        rows = life_insurance_row("Fonds Actions Europe A", "LU0123456789", "0P0000ABCD",
                                  "0,0000", "26,00 €", "25,00 €", "3 300,00 €", "0,00 €")

        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(rows))

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_lines_that_do_not_add_up_to_the_valuation_are_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(LIFE_INSURANCE_ROWS, valuation="9 999,99 €"))

        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_a_line_without_units_that_is_not_a_euro_fund_is_refused(self):
        rows = life_insurance_row("SCPI Mystère", None, None, "", "", "", "3 300,00 €", "")

        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(rows))

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_unitless_fund_whose_label_starts_with_europe_is_not_the_euro_fund(self):
        rows = life_insurance_row("Fonds Europe Actions", None, None, "", "", "", "3 300,00 €", "")

        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(rows))

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_an_unreadable_quantity_is_a_format_change_not_an_empty_one(self):
        rows = life_insurance_row("Euro Stoxx 50", None, "1rTXYZ",
                                  "n/a", "", "120,00 €", "3 300,00 €", "")

        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(rows))

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_quoted_unitless_line_naming_the_euro_is_not_the_euro_fund(self):
        # Without units, a quote link or a price is enough to show this is a fund, not cash.
        # A query string on the quote link must not hide it.
        for symbol, price in (("1rTXYZ", ""), ("1rTXYZ/?foo=1", ""), ("1rTXYZ?foo=1", ""),
                              (None, "120,00 €")):
            with self.subTest(symbol=symbol, price=price):
                rows = life_insurance_row("Euro Stoxx 50", None, symbol, "", "", price, "3 300,00 €", "")

                with self.assertRaises(AccountsFormatError) as raised:
                    parse_life_insurance(life_insurance_page(rows))

                self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_quote_link_with_a_query_string_keeps_its_symbol_as_the_ticker(self):
        rows = life_insurance_row("iShares MSCI World SRI UCITS ETF", "IE00BYX2JD69", "1rASUSW/?foo=1&amp;bar=2",
                                  "100,0000", "10,00 €", "12,00 €", "1 200,00 €", "200,00 €")

        position, = parse_life_insurance(life_insurance_page(rows, valuation="1 200,00 €"))["positions"]

        self.assertEqual(position["symbol"], "1rASUSW")

    def test_a_quote_link_without_a_readable_symbol_is_a_format_change(self):
        # Neither cash nor a position under a guessed ticker: the row is refused.
        for symbol, quantity in (("1rTXYZ/extra", ""), ("", ""), ("1rTXYZ/extra", "100,0000")):
            with self.subTest(symbol=symbol, quantity=quantity):
                rows = life_insurance_row("Fonds Euro", None, None, quantity, "", "", "3 300,00 €", "")
                rows = rows.replace('<span class="c-link__label ">',
                                    f'<a href="//bourse.boursobank.com/cours/{symbol}">'
                                    '<span class="c-link__label ">', 1)

                with self.assertRaises(AccountsFormatError) as raised:
                    parse_life_insurance(life_insurance_page(rows))

                self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_several_euro_fund_rows_report_every_row_for_the_backend_check(self):
        rows = "".join(
            life_insurance_row(f"Fonds Euro {index}", None, None, "", "", "", "1 000,01 €", "")
            for index in range(12)
        )

        contract = parse_life_insurance(life_insurance_page(rows, valuation="12 000,06 €"))

        self.assertEqual(contract["positions"], [])
        self.assertEqual(contract["cashEur"], Decimal("12000.12"))
        self.assertEqual(contract["valuationLineCount"], 12)

    def test_an_empty_valuation_is_a_format_change(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(LIFE_INSURANCE_ROWS, valuation="&nbsp;"))

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_support_without_isin_or_quote_link_gets_a_ticker_the_backend_accepts(self):
        label = "Support en unités de compte sans code ISIN publié par l'assureur"
        rows = (
            life_insurance_row(label, None, None, "10,0000", "300,00 €", "330,00 €", "3 300,00 €", "300,00 €")
        )

        page = life_insurance_page(rows)

        position, = parse_life_insurance(page)["positions"]
        again, = parse_life_insurance(page)["positions"]

        self.assertLessEqual(len(position["symbol"]), 30)
        self.assertTrue(position["symbol"].startswith(label[:21].rstrip() + "-"))
        # The backend keys holdings by ticker: it must not change from one sync to the next.
        self.assertEqual(position["symbol"], again["symbol"])
        self.assertEqual(position["label"], label)

    def test_two_labels_sharing_their_first_30_characters_get_distinct_tickers(self):
        prefix = "Support en unités de compte A"
        rows = (
            life_insurance_row(prefix + " première variante", None, None,
                               "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
            + life_insurance_row(prefix + " seconde variante", None, None,
                                 "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
        )

        contract = parse_life_insurance(life_insurance_page(rows, valuation="1 200,00 €"))

        first, second = contract["positions"]
        self.assertEqual(first["label"][:30], second["label"][:30])
        self.assertNotEqual(first["symbol"], second["symbol"])
        self.assertLessEqual(len(first["symbol"]), 30)
        self.assertLessEqual(len(second["symbol"]), 30)
        guard_symbol_collisions(contract["positions"])

    def test_a_quote_link_symbol_over_the_backend_limit_gives_way_to_the_isin(self):
        long_symbol = "1r" + "A" * 30
        rows = (
            life_insurance_row("iShares MSCI World SRI UCITS ETF", "IE00BYX2JD69", long_symbol,
                               "100,0000", "10,00 €", "12,00 €", "1 200,00 €", "200,00 €")
        )

        position, = parse_life_insurance(life_insurance_page(rows, valuation="1 200,00 €"))["positions"]

        self.assertGreaterEqual(len(long_symbol), 31)
        self.assertEqual(position["symbol"], "IE00BYX2JD69")

    def test_a_short_label_without_isin_or_quote_link_is_kept_as_the_ticker(self):
        rows = (
            life_insurance_row("Support sans code", None, None,
                               "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
        )

        position, = parse_life_insurance(life_insurance_page(rows, valuation="600,00 €"))["positions"]

        self.assertEqual(position["symbol"], "Support sans code")

    def test_the_ticker_limit_is_counted_in_utf16_units_like_the_backend(self):
        label = "\U0001F4C8" * 16  # 16 code points, 32 UTF-16 units
        rows = (
            life_insurance_row(label, None, None, "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
        )

        position, = parse_life_insurance(life_insurance_page(rows, valuation="600,00 €"))["positions"]

        self.assertLessEqual(len(position["symbol"].encode("utf-16-le")) // 2, 30)
        self.assertNotEqual(position["symbol"], label)

    def test_labels_that_only_differ_after_the_200_character_display_cut_get_distinct_tickers(self):
        prefix = "Support en unités de compte " * 8
        rows = (
            life_insurance_row(prefix + "première variante", None, None,
                               "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
            + life_insurance_row(prefix + "seconde variante", None, None,
                                 "10,0000", "50,00 €", "60,00 €", "600,00 €", "100,00 €")
        )

        contract = parse_life_insurance(life_insurance_page(rows, valuation="1 200,00 €"))

        first, second = contract["positions"]
        self.assertEqual(first["label"], second["label"])
        self.assertLessEqual(len(first["label"]), 200)
        self.assertNotEqual(first["symbol"], second["symbol"])
        guard_symbol_collisions(contract["positions"])

    def test_a_missing_support_on_a_large_contract_is_not_hidden_by_a_relative_tolerance(self):
        rows = (
            life_insurance_row("iShares MSCI World SRI UCITS ETF", "IE00BYX2JD69", "1rASUSW",
                               "100,0000", "10,00 €", "12,00 €", "49 910,00 €", "200,00 €")
            + life_insurance_row("Fonds Euro Nouvelle Génération", None, None,
                                 "", "", "", "50 000,00 €", "")
        )

        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance(life_insurance_page(rows, valuation="100 000,00 €"))

        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_a_few_cents_of_per_line_rounding_are_accepted(self):
        rows = "".join(
            life_insurance_row(f"Support {index}", f"FR00000000{index:02d}", f"SYM{index}",
                               "10,0000", "90,00 €", "100,00 €",
                               "1 000,08 €" if index == 0 else "1 000,00 €", "0,00 €")
            for index in range(10)
        )

        contract = parse_life_insurance(life_insurance_page(rows, valuation="10 000,00 €"))

        self.assertEqual(len(contract["positions"]), 10)

    def test_a_page_without_the_supports_table_is_a_format_change(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_life_insurance("<html><body>Bienvenue</body></html>")

        self.assertEqual(raised.exception.code, FORMAT_CHANGED)


class LifeInsuranceSyncTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_contract_arrives_with_its_supports_and_euro_fund(self):
        base = default_handler(dashboard=dashboard_with_life_insurance())
        requested = []

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path == f"/compte/assurance-vie/{LIFE_INSURANCE_ID}/positions":
                requested.append(request.url.path)
                return httpx.Response(200, text=LIFE_INSURANCE_HTML)
            return base(request)

        async with build_client(handler) as client:
            accounts = await _collect_accounts(client)

        contract = next(account for account in accounts if account.type == "ASSURANCE_VIE")
        self.assertEqual(requested, [f"/compte/assurance-vie/{LIFE_INSURANCE_ID}/positions"])
        self.assertEqual(contract.externalId, f"bourso_{LIFE_INSURANCE_ID}")
        self.assertEqual(contract.balanceEur, Decimal("3300.00"))
        self.assertEqual(contract.cashBalance, Decimal("1100.00"))
        self.assertEqual([position.isin for position in contract.positions], ["IE00BYX2JD69", "LU0123456789"])
        # Two supports and the euro fund: the backend grants its allowance on all three.
        self.assertEqual(contract.valuationLineCount, 3)
        self.assertTrue(contract.snapshotComplete)

    async def test_an_expired_session_on_the_contract_page_reads_as_such(self):
        base = default_handler(dashboard=dashboard_with_life_insurance())

        def handler(request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith("/positions"):
                return httpx.Response(302, headers={"location": "/connexion/"})
            return base(request)

        async with build_client(handler) as client:
            with self.assertRaises(Exception) as raised:
                await _collect_accounts(client)

        self.assertEqual(getattr(raised.exception, "detail", None), "SESSION_EXPIRED")


if __name__ == "__main__":
    unittest.main()
