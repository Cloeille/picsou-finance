"""Parsing rules for the dashboard HTML and the trading board's JSON.

Every case here is a way a partial or misread response could quietly replace a
correct portfolio. The dashboard cases run against `fixtures.DASHBOARD_HTML`,
which is markup BoursoBank actually served.
"""

import unittest
from decimal import Decimal
from typing import get_args

from accounts_parser import (
    FORMAT_CHANGED,
    INCOMPLETE,
    INVALID_DATA,
    AccountKind,
    AccountsFormatError,
    _SAVINGS_PATTERNS,
    account_type,
    guard_symbol_collisions,
    describe_payload,
    is_own_account,
    money_close,
    parse_amount,
    parse_dashboard,
    parse_trading_summary,
)
from fixtures import DASHBOARD_HTML


def money(value, currency="EUR"):
    return {"value": value, "decimals": 2, "currency": currency}


def summary(cash="100.00", valuation="900.00", total="1000.00", positions=None):
    """The real shape: a list of view sections, the account in one and the lines
    in another. Reading both off the first section is what reported a funded PEA
    as having no positions."""
    return [
        {
            "id": "summary",
            "label": "Synthèse",
            "headings": [{"id": "h1"}],
            "account": {
                "name": "PEA DOE",
                "reference": "0123",
                "currency": "EUR",
                "balance": money(0),
                "cash": money(cash),
                "valuation": money(valuation),
                "total": money(total),
                "gainLoss": money("120.00"),
            },
            "actions": [],
        },
        {
            "id": "positions",
            "label": "Positions",
            "headings": [{"id": "h1"}],
            "actions": [],
            "positions": positions if positions is not None else [position()],
            "count": 1,
        },
    ]


def position(symbol="1rTCW8", quantity="10", amount="900.00", currency="EUR", **overrides):
    raw = {
        "symbol": symbol,
        "label": "Amundi MSCI World",
        "isin": "IE00B4L5Y983",
        "permalink": f"/cours/{symbol}/",
        "exchangeCode": "XPAR",
        "currency": "EUR",
        "quantity": {"value": quantity, "decimals": 4, "currency": None},
        "buyingPrice": money("75.00"),
        "amount": money(amount, currency),
        "last": money("90.00"),
        "gainLoss": money("150.00"),
    }
    raw.update(overrides)
    return raw


class AmountTest(unittest.TestCase):
    def test_parses_french_formatting(self):
        self.assertEqual(parse_amount("11 010,00"), Decimal("11010.00"))
        self.assertEqual(parse_amount("143 088,89 €"), Decimal("143088.89"))
        self.assertEqual(parse_amount("1 234,5"), Decimal("1234.5"))
        self.assertEqual(parse_amount("42"), Decimal("42"))

    def test_reads_the_unicode_minus_boursobank_renders(self):
        # U+2212, not ASCII '-': a loan parsed as positive would flip a debt
        # into an asset.
        self.assertEqual(parse_amount("− 94 959,82"), Decimal("-94959.82"))

    def test_refuses_rather_than_defaulting_to_zero(self):
        for bad in ("", "   ", "n/a", "-", "€"):
            self.assertIsNone(parse_amount(bad))


class AccountTypeTest(unittest.TestCase):
    def test_maps_trading_accounts_onto_their_envelope(self):
        self.assertEqual(account_type("trading", "PEA DOE"), "PEA")
        self.assertEqual(account_type("trading", "PEA-PME"), "PEA")
        self.assertEqual(account_type("trading", "Compte titres ordinaire"), "COMPTE_TITRES")

    def test_maps_savings_accounts(self):
        self.assertEqual(account_type("savings", "LEP"), "LEP")
        self.assertEqual(account_type("savings", "Livret d'Épargne Populaire"), "LEP")

    def test_maps_each_regulated_passbook_onto_its_own_type(self):
        self.assertEqual(account_type("savings", "Livret A"), "LIVRET_A")
        self.assertEqual(account_type("savings", "LIVRET DEVELOPPEMENT DURABLE"), "LDDS")
        self.assertEqual(account_type("savings", "LDD"), "LDDS")
        self.assertEqual(account_type("savings", "LDDS"), "LDDS")
        self.assertEqual(account_type("savings", "Livret Jeune"), "LIVRET_JEUNE")
        self.assertEqual(account_type("savings", "Plan d'Épargne Logement"), "PEL")
        self.assertEqual(account_type("savings", "PLAN EPARGNE LOGEMENT"), "PEL")
        self.assertEqual(account_type("savings", "Compte d'Épargne Logement"), "CEL")

    def test_a_house_passbook_stays_the_generic_savings_type(self):
        self.assertEqual(account_type("savings", "Livret Bourso+"), "SAVINGS")

    def test_a_savings_label_merely_containing_lep_is_not_an_lep(self):
        self.assertEqual(account_type("savings", "Livret Leplus"), "SAVINGS")

    def test_a_livret_whose_name_merely_starts_with_a_is_not_a_livret_a(self):
        self.assertEqual(account_type("savings", "Livret Avenir"), "SAVINGS")

    def test_banking_is_the_default(self):
        self.assertEqual(account_type("banking", "BoursoBank"), "CHECKING")

    def test_every_kind_the_parser_emits_is_in_the_sidecar_contract(self):
        """AccountPayload.type is AccountKind, so a kind missing from it is not a
        type quibble: pydantic rejects the account and the whole sync fails.
        That is exactly how the regulated passbooks shipped -- the parser learned
        to emit LDDS while the contract still allowed five kinds. CI runs no type
        checker, so the annotation alone would not have caught it; this does.
        """
        emitted = {kind for kind, _ in _SAVINGS_PATTERNS}
        emitted.update({"CHECKING", "SAVINGS", "PEA", "COMPTE_TITRES"})
        self.assertEqual(emitted, set(get_args(AccountKind)))


class OwnAccountTest(unittest.TestCase):
    def test_recognises_the_bank_under_both_names(self):
        self.assertTrue(is_own_account("BoursoBank"))
        self.assertTrue(is_own_account("Boursorama Banque"))

    def test_aggregated_banks_are_not_ours(self):
        self.assertFalse(is_own_account("Crédit Agricole"))
        self.assertFalse(is_own_account("CIC"))


def insurance_panel(marker, card_id, name, balance):
    return f"""<div class="c-panel c-panel--primary">
    <div class="c-panel__header ">
        <span class="c-panel__title">
            Mes assurances
        </span>
    </div>
    <div class="c-panel__body ">
        <div class="c-panel__no-animation-glitch ">
            <ul class="c-info-box " role="list" data-brs-list-header {marker}>
                <li class="c-panel__item c-info-box__item" data-brs-filterable>
                    <a class="c-info-box__link-wrapper" href="/compte/assurance/{card_id}/"
                        aria-label="Détails du compte {name} - Solde : {balance} €" title="{name}">
                        <span class="c-info-box__account">
                            <span class="c-info-box__account-label"
                                data-account-label="{card_id}" data-brs-list-item-label>
                                {name}
                            </span>
                            <span class="c-info-box__account-balance c-info-box__account-balance--positive">
                                {balance} €
                            </span>
                        </span>
                        <span class="c-info-box__account-sub-label" data-brs-list-item-label>
                            BoursoBank
                        </span>
                    </a>
                </li>
            </ul>
        </div>
    </div>
</div>"""


class DashboardTest(unittest.TestCase):
    def test_parses_a_real_dashboard(self):
        accounts, third_party = parse_dashboard(DASHBOARD_HTML)

        self.assertEqual(third_party, 2)
        self.assertEqual(
            [(account["type"], account["name"]) for account in accounts],
            [
                ("CHECKING", "BoursoBank"),
                ("LDDS", "LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE"),
                ("PEA", "PEA DOE"),
            ],
        )
        self.assertEqual(accounts[0]["balanceEur"], Decimal("20810.50"))
        self.assertEqual(accounts[2]["balanceEur"], Decimal("143088.89"))

    def test_the_loan_is_excluded_without_failing_the_completeness_check(self):
        accounts, _ = parse_dashboard(DASHBOARD_HTML)
        self.assertNotIn("Prêt personnel", [account["name"] for account in accounts])

    def test_a_card_that_stops_parsing_fails_the_whole_sync(self):
        # Silently dropping it would delete a real account's holdings and write
        # a wrong net worth; the previous connector did exactly that.
        broken = DASHBOARD_HTML.replace("c-info-box__account-sub-label", "c-info-box__bank", 1)
        with self.assertRaises(AccountsFormatError) as raised:
            parse_dashboard(broken)
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_page_without_any_section_is_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_dashboard("<html><body>Maintenance</body></html>")
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_dashboard_of_only_third_party_accounts_is_incomplete(self):
        foreign = DASHBOARD_HTML.replace("BoursoBank", "Crédit Mutuel")
        with self.assertRaises(AccountsFormatError) as raised:
            parse_dashboard(foreign)
        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_insurance_contracts_are_skipped_without_failing_the_sync(self):
        # Assurance-vie and Bourso Protect cards are account-shaped links in
        # their own sections. They are out of scope like loans, so they must
        # not trip the completeness check that guards real format changes.
        html = (
            DASHBOARD_HTML
            + insurance_panel(
                "data-summary-insurance",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "Assurance Vie **01",
                "45 210,30",
            )
            + insurance_panel(
                "data-summary-assurance",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "Bourso Protect",
                "150,00",
            )
        )
        accounts, third_party = parse_dashboard(html)
        self.assertEqual(
            [(account["type"], account["name"]) for account in accounts],
            [
                ("CHECKING", "BoursoBank"),
                ("LDDS", "LIVRET DEVELOPPEMENT DURABLE SOLIDAIRE"),
                ("PEA", "PEA DOE"),
            ],
        )
        self.assertEqual(third_party, 2)

    def test_a_section_with_an_unknown_marker_still_fails_the_sync(self):
        html = DASHBOARD_HTML + insurance_panel(
            "data-summary-foobar",
            "cccccccccccccccccccccccccccccccc",
            "Produit Inconnu",
            "10,00",
        )
        with self.assertRaises(AccountsFormatError) as raised:
            parse_dashboard(html)
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)


class TradingSummaryTest(unittest.TestCase):
    def test_normalises_an_account_and_its_positions(self):
        parsed = parse_trading_summary(summary(), "acc")

        self.assertEqual(parsed["cashEur"], Decimal("100.00"))
        self.assertEqual(parsed["totalEur"], Decimal("1000.00"))
        self.assertEqual(len(parsed["positions"]), 1)
        line = parsed["positions"][0]
        self.assertEqual(line["symbol"], "1rTCW8")
        self.assertEqual(line["quantity"], Decimal("10"))
        self.assertEqual(line["buyingPriceEur"], Decimal("75.00"))
        self.assertEqual(line["currentPrice"], Decimal("90.00"))
        self.assertEqual(line["quoteCurrency"], "EUR")
        self.assertEqual(line["currentValueEur"], Decimal("900.00"))
        self.assertEqual(line["pnlEur"], Decimal("150.00"))

    def test_accepts_a_difference_inside_the_tolerance(self):
        parsed = parse_trading_summary(summary(total="1000.04"), "acc")
        self.assertEqual(parsed["totalEur"], Decimal("1000.04"))

    def test_a_total_that_does_not_reconcile_is_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_trading_summary(summary(total="1500.00"), "acc")
        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_lines_that_do_not_add_up_to_the_valuation_are_refused(self):
        # The failure mode this exists for: a truncated position list still
        # looks like a valid portfolio, just a smaller one.
        with self.assertRaises(AccountsFormatError) as raised:
            parse_trading_summary(summary(positions=[position(amount="400.00")]), "acc")
        self.assertEqual(raised.exception.code, INCOMPLETE)

    def test_an_empty_portfolio_of_pure_cash_reconciles(self):
        parsed = parse_trading_summary(
            summary(cash="1000.00", valuation="0", total="1000.00", positions=[]), "acc"
        )
        self.assertEqual(parsed["positions"], [])

    def test_a_fully_sold_line_is_dropped_without_breaking_reconciliation(self):
        parsed = parse_trading_summary(
            summary(positions=[position(), position(symbol="1rTX", quantity="0", amount="0")]),
            "acc",
        )
        self.assertEqual(len(parsed["positions"]), 1)

    def test_a_position_valued_in_a_foreign_currency_is_refused(self):
        with self.assertRaises(AccountsFormatError) as raised:
            parse_trading_summary(summary(positions=[position(currency="USD")]), "acc")
        self.assertEqual(raised.exception.code, INVALID_DATA)

    def test_a_native_quote_keeps_its_currency_and_drops_the_cost_basis(self):
        # A USD cost basis recorded as EUR reports a gain the size of the FX
        # spread; null is the honest answer.
        parsed = parse_trading_summary(
            summary(
                positions=[
                    position(last=money("90.00", "USD"), buyingPrice=money("75.00", "USD"))
                ]
            ),
            "acc",
        )
        self.assertEqual(parsed["positions"][0]["quoteCurrency"], "USD")
        self.assertIsNone(parsed["positions"][0]["buyingPriceEur"])

    def test_reads_the_isin_the_trading_board_ships_with_each_line(self):
        parsed = parse_trading_summary(summary(), "acc")
        self.assertEqual(parsed["positions"][0]["isin"], "IE00B4L5Y983")

    def test_a_line_without_a_usable_isin_still_syncs(self):
        # The ISIN only resolves a Yahoo ticker; the money is in `amount`.
        for broken in (None, "", "NOT-AN-ISIN"):
            parsed = parse_trading_summary(summary(positions=[position(isin=broken)]), "acc")
            self.assertIsNone(parsed["positions"][0]["isin"])
            self.assertEqual(parsed["positions"][0]["currentValueEur"], Decimal("900.00"))

    def test_finds_the_lines_whichever_section_carries_them(self):
        reordered = list(reversed(summary()))
        self.assertEqual(len(parse_trading_summary(reordered, "acc")["positions"]), 1)

    def test_an_empty_positions_section_does_not_mask_a_populated_one(self):
        sections = summary()
        sections.insert(1, {"id": "other", "positions": [], "count": 0})
        self.assertEqual(len(parse_trading_summary(sections, "acc")["positions"]), 1)

    def test_a_missing_valuation_field_is_refused(self):
        broken = summary()
        del broken[0]["account"]["valuation"]
        with self.assertRaises(AccountsFormatError) as raised:
            parse_trading_summary(broken, "acc")
        self.assertEqual(raised.exception.code, FORMAT_CHANGED)

    def test_a_non_finite_amount_cannot_slip_through(self):
        with self.assertRaises(AccountsFormatError):
            parse_trading_summary(summary(positions=[position(amount=float("nan"))]), "acc")


class SymbolCollisionTest(unittest.TestCase):
    def test_lines_carrying_their_own_isin_never_collide(self):
        guard_symbol_collisions([
            {"symbol": "1rTCW8", "isin": "IE00B4L5Y983"},
            {"symbol": "1rTCW8", "isin": "IE00BJ0KDQ92"},
        ])

    def test_one_isin_less_line_is_fine(self):
        guard_symbol_collisions([{"symbol": "1rTCW8", "isin": None}])

    def test_two_isin_less_positions_sharing_a_symbol_are_refused(self):
        # They would merge into one holding downstream, silently halving the
        # portfolio.
        with self.assertRaises(AccountsFormatError) as raised:
            guard_symbol_collisions([
                {"symbol": "1rTCW8", "isin": None}, {"symbol": "1rtcw8", "isin": None},
            ])
        self.assertEqual(raised.exception.code, INVALID_DATA)


class DescribePayloadTest(unittest.TestCase):
    """The diagnostic that says where a field moved to. It must never leak a value."""

    def test_reports_containers_and_key_names(self):
        described = describe_payload(
            [{"id": "x", "account": {"cash": {"value": 1}}, "positions": None}]
        )
        self.assertEqual(described, "list[1]({id,account:{cash},positions:null})")

    def test_never_prints_a_value(self):
        described = describe_payload(
            [{"account": {"name": "PEA DOE", "total": {"value": 143088.89}}}]
        )
        self.assertNotIn("143088", described)
        self.assertNotIn("PEA DOE", described)

    def test_bounds_long_collections(self):
        self.assertTrue(describe_payload([{"a": 1}] * 40).startswith("list[40]("))
        self.assertIn("…", describe_payload([{"a": 1}] * 40))

    def test_a_zero_line_account_says_where_the_positions_went(self):
        payload = [{"id": "acc", "account": summary()[0]["account"], "lines": []}]
        with self.assertRaises(AccountsFormatError) as raised:
            parse_trading_summary(payload, "acc")
        self.assertEqual(raised.exception.code, INCOMPLETE)
        self.assertIn("payload=", str(raised.exception))
        self.assertIn("lines:list[0]", str(raised.exception))


class ToleranceTest(unittest.TestCase):
    def test_absolute_tolerance_covers_rounding(self):
        self.assertTrue(money_close(Decimal("100.04"), Decimal("100.00")))
        self.assertFalse(money_close(Decimal("100.20"), Decimal("100.00")))

    def test_relative_tolerance_scales_with_the_amount(self):
        self.assertTrue(money_close(Decimal("100050"), Decimal("100000")))
        self.assertFalse(money_close(Decimal("100200"), Decimal("100000")))


if __name__ == "__main__":
    unittest.main()
