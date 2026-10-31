"""The parser is the only place CORUM's two price notions meet, so these
tests pin the rule that keeps entry fees out of net worth: the balance is
`quantity * withdrawalPrice`, never the `saving.value` CORUM displays.

`unittest`, not pytest: the CI job runs the sidecar image with
`python -m unittest discover`, and pytest is not a runtime dependency.

Fixtures are shaped like the real client-space payloads, with invented
figures. No live account data belongs in the repo.
"""

import unittest
from decimal import Decimal

from positions_parser import PositionsFormatError, parse_snapshot


def _contract(products, total="300.00"):
    return {
        "contractCode": "000000",
        "contractType": "REAL_ESTATE",
        "propertyRightType": "FULL_PROPERTY",
        "investedAmount": Decimal(total),
        "valuationAmount": Decimal(total),
        "valuationDate": "26/09/2026",
        "productsData": products,
    }


def _line(code, value):
    return {"productCode": code, "quantity": Decimal("1"), "valuationAmount": Decimal(value)}


def _product(code, quantity, withdrawal, subscription, value):
    """CORUM types the prices as an int on one fund and a float on the other, so
    the parser has to accept either."""
    return {
        "productCode": code,
        "saving": {
            "share": {
                "quantity": Decimal(quantity),
                "unitPrice": subscription,
                "withdrawalPrice": withdrawal,
                "averageUnitPrice": subscription,
                "valuationDate": "26/09/2026",
            },
            "value": Decimal(value) if value is not None else None,
        },
    }


class ParseSnapshotTest(unittest.TestCase):
    def test_balance_uses_the_withdrawal_price_not_the_displayed_value(self):
        contract = _contract([_line("US", "340.00")], total="340.00")
        product = _product("US", "2", "150", "170", "340.00")

        position = parse_snapshot(contract, [product])["holdings"][0]

        # The portal displays quantity * subscription price. Valuing the share at
        # that figure is what inflates net worth by the entry fee.
        self.assertEqual(position["withdrawalPrice"], Decimal("150"))
        self.assertEqual(position["displayedValueEur"], Decimal("340.00"))
        self.assertEqual(
            position["quantity"] * position["withdrawalPrice"], Decimal("300.00")
        )

    def test_fractional_quantity_is_kept(self):
        contract = _contract([_line("US", "534.37")], total="534.37")
        product = _product("US", "2.67183", 176, 200, "534.37")

        position = parse_snapshot(contract, [product])["holdings"][0]

        self.assertEqual(position["quantity"], Decimal("2.67183"))

    def test_withdrawal_price_is_parsed_as_a_decimal(self):
        # CORUM types this field as an int on one fund and a float on the other.
        # Reading it as an int would silently drop the cents.
        contract = _contract([_line("XL", "341.25")], total="341.25")
        product = _product("XL", "1.75", 171.6, 195, "341.25")

        position = parse_snapshot(contract, [product])["holdings"][0]

        self.assertEqual(position["withdrawalPrice"], Decimal("171.6"))

    def test_missing_withdrawal_price_is_kept_distinct_from_zero(self):
        contract = _contract([_line("US", "340.00")], total="340.00")
        product = _product("US", "2", None, 170, "340.00")

        position = parse_snapshot(contract, [product])["holdings"][0]

        # None means CORUM is not quoting that fund: the domain must keep the
        # previous balance. Zero would be a real price and would write a zero.
        self.assertIsNone(position["withdrawalPrice"])

    def test_zero_withdrawal_price_is_a_real_value(self):
        contract = _contract([_line("US", "0.00")], total="0.00")
        product = _product("US", "0", 0, 0, "0.00")

        position = parse_snapshot(contract, [product])["holdings"][0]

        self.assertEqual(position["withdrawalPrice"], Decimal("0"))

    def test_positive_total_with_no_lines_is_not_an_empty_portfolio(self):
        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(_contract([], total="300.00"), [])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_missing_displayed_value_is_not_a_complete_snapshot(self):
        contract = _contract([_line("US", "340.00")], total="340.00")
        product = _product("US", "2", "150", "170", None)

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [product])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_negative_envelope_is_refused(self):
        contract = _contract([_line("US", "-1.00")], total="-1.00")
        product = _product("US", "1", "150", "170", "-1.00")

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [product])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_snapshot_is_refused_when_a_fund_is_missing(self):
        contract = _contract([_line("US", "150.00"), _line("XL", "150.00")])
        only_one = _product("US", "1", 150, 170, "150.00")

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [only_one])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_snapshot_is_refused_when_the_funds_disagree_with_the_envelope(self):
        # The sum of the lines must reproduce the contract total, or this is a
        # half-read snapshot.
        contract = _contract(
            [_line("US", "150.00"), _line("XL", "150.00")], total="400.00"
        )
        us = _product("US", "1", 150, 170, "150.00")
        xl = _product("XL", "1", 150, 170, "150.00")

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [us, xl])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_two_funds_reconcile_to_the_envelope(self):
        contract = _contract(
            [_line("US", "534.37"), _line("XL", "518.61")], total="1052.98"
        )
        us = _product("US", "2.67183", 176, 200, "534.37")
        xl = _product("XL", "2.65955", 171.6, 195, "518.61")

        snapshot = parse_snapshot(contract, [us, xl])

        self.assertEqual([p["fundCode"] for p in snapshot["holdings"]], ["US", "XL"])
        self.assertEqual(snapshot["totalValuationEur"], Decimal("1052.98"))

    def test_fund_code_is_mapped_to_a_label_rather_than_guessed(self):
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = _product("US", "2", 150, 170, "300.00")

        position = parse_snapshot(contract, [product])["holdings"][0]

        self.assertEqual(position["label"], "CORUM USA")

    def test_unknown_fund_code_still_carries_a_label(self):
        contract = _contract([_line("ZZ", "300.00")], total="300.00")
        product = _product("ZZ", "2", 150, 170, "300.00")

        position = parse_snapshot(contract, [product])["holdings"][0]

        self.assertEqual(position["label"], "CORUM ZZ")

    def test_corum_life_is_not_a_scpi(self):
        # One login can also see insurance. It has no productsData, so it must be
        # refused rather than read as an empty portfolio.
        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot({"contractCode": "000000", "contractType": "LIFE"}, [])

        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_malformed_share_is_rejected(self):
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = {"productCode": "US", "saving": {"value": Decimal("300.00")}}

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [product])

        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_negative_quantity_is_rejected(self):
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = _product("US", "-2", 150, 170, "300.00")

        with self.assertRaises(PositionsFormatError) as caught:
            parse_snapshot(contract, [product])

        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_valuation_date_is_converted_to_iso(self):
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = _product("US", "2", 150, 170, "300.00")

        snapshot = parse_snapshot(contract, [product])

        self.assertEqual(snapshot["valuationDate"], "2026-09-26")
        self.assertEqual(snapshot["holdings"][0]["valuationDate"], "2026-09-26")

    def test_unparseable_date_is_dropped_rather_than_guessed(self):
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = _product("US", "2", 150, 170, "300.00")
        product["saving"]["share"]["valuationDate"] = "not a date"

        snapshot = parse_snapshot(contract, [product])

        self.assertIsNone(snapshot["holdings"][0]["valuationDate"])

    def test_snapshot_key_is_holdings_to_match_the_java_port(self):
        # `CorumPort.Snapshot` decodes `holdings`. A key renamed on only one
        # side decodes into a null list and writes an empty portfolio, so this
        # pins the name the Java record expects.
        contract = _contract([_line("US", "300.00")], total="300.00")
        product = _product("US", "2", 150, 170, "300.00")

        snapshot = parse_snapshot(contract, [product])

        self.assertIn("holdings", snapshot)
        self.assertNotIn("positions", snapshot)
        self.assertEqual(len(snapshot["holdings"]), 1)


if __name__ == "__main__":
    unittest.main()
