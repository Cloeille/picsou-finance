"""Assurance-vie contracts: the dashboard card, the positions page, the sync payload."""

import unittest
from decimal import Decimal

import httpx

from accounts_parser import FORMAT_CHANGED, INCOMPLETE, AccountsFormatError, parse_dashboard, parse_life_insurance
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
