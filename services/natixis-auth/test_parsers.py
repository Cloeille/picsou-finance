"""The savings mapping, on synthetic responses shaped after the real saver space."""

import copy
import unittest
from decimal import Decimal

from fixtures import EMPLOYEE_ID, FUND_DETAIL, PRODUCTS, SAVINGS
from savings_parser import SavingsFormatError, fund_code, fund_ids, money_close, parse_plans


def plans(savings=SAVINGS, products=PRODUCTS, codes=None):
    return parse_plans(copy.deepcopy(savings), copy.deepcopy(products), EMPLOYEE_ID, codes or {})


class ParsePlansTest(unittest.TestCase):
    def test_captured_plans_reconcile(self):
        result = plans()
        self.assertEqual([p["planKind"] for p in result], ["PEE", "PER"])
        pee, perco = result
        self.assertEqual(pee["externalId"], f"natixis_{EMPLOYEE_ID}_PEE-4242")
        self.assertEqual(pee["name"], "Plan d'Épargne Entreprise")
        self.assertEqual(pee["employer"], "UES ACME")
        self.assertEqual(len(pee["positions"]), 6)
        self.assertTrue(money_close(sum(p["valueEur"] for p in pee["positions"]), pee["balanceEur"]))
        self.assertEqual(perco["balanceEur"], Decimal("0.0"))
        self.assertEqual(perco["positions"], [])

    def test_position_fields(self):
        line = next(p for p in plans(codes={1002: "QS0000001002"})[0]["positions"]
                    if p["label"] == "FONDS ACTIONS EUROPE I")
        self.assertIsNone(line["isin"])
        self.assertEqual(line["fundCode"], "QS0000001002")
        self.assertGreater(line["quantity"], 0)
        self.assertEqual(line["unitValue"], Decimal("25.5"))
        self.assertIsNotNone(line["pnlEur"])
        self.assertTrue(money_close(line["quantity"] * line["unitValue"], line["valueEur"]))

    def test_fund_held_in_two_compartments_is_one_line(self):
        savings = copy.deepcopy(SAVINGS)
        pee = savings["items"][0]
        employee, voluntary = pee["compartments"]
        moved = copy.deepcopy(employee["investmentOptions"][0])
        half = moved["amount"]["value"] / 2
        employee["investmentOptions"][0]["amount"]["value"] = half
        employee["investmentOptions"][0]["units"] = moved["units"] / 2
        moved["amount"]["value"] = half
        moved["units"] = moved["units"] / 2
        voluntary["investmentOptions"] = [moved]
        result = plans(savings)[0]["positions"]
        self.assertEqual(len(result), 6)
        merged = next(p for p in result if p["label"] == moved["label"])
        self.assertTrue(money_close(merged["valueEur"], Decimal(str(half * 2))))

    def test_missing_line_is_incomplete(self):
        savings = copy.deepcopy(SAVINGS)
        savings["items"][0]["compartments"][0]["investmentOptions"].pop()
        with self.assertRaises(SavingsFormatError) as caught:
            plans(savings)
        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_missing_plan_against_products_is_incomplete(self):
        savings = copy.deepcopy(SAVINGS)
        savings["items"].pop(0)
        with self.assertRaises(SavingsFormatError) as caught:
            plans(savings)
        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_drift_within_tolerance_is_accepted(self):
        savings = copy.deepcopy(SAVINGS)
        savings["items"][0]["compartments"][0]["investmentOptions"][0]["amount"]["value"] += 0.04
        self.assertEqual(len(plans(savings)), 2)

    def test_unexpected_shape_is_format_changed(self):
        for mutate in (
            lambda s: s.pop("items"),
            lambda s: s["items"][0].pop("amount"),
            lambda s: s["items"][0]["compartments"][0]["investmentOptions"][0].update(units="many"),
            lambda s: s["items"][0]["compartments"][0]["investmentOptions"][0].update(type="CASH"),
            lambda s: s["items"][0].pop("id"),
        ):
            savings = copy.deepcopy(SAVINGS)
            mutate(savings)
            with self.subTest(mutate=mutate), self.assertRaises(SavingsFormatError) as caught:
                plans(savings)
            self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_amount_not_in_eur_is_format_changed(self):
        def set_currency(node, code):
            if isinstance(node, dict):
                if "currencyCode" in node:
                    if code is None:
                        node.pop("currencyCode")
                    else:
                        node["currencyCode"] = code
                for child in node.values():
                    set_currency(child, code)
            elif isinstance(node, list):
                for child in node:
                    set_currency(child, code)

        for code in ("USD", None):
            for target in ("savings", "products"):
                payloads = {"savings": copy.deepcopy(SAVINGS), "products": copy.deepcopy(PRODUCTS)}
                set_currency(payloads[target], code)
                with self.subTest(code=code, target=target):
                    with self.assertRaises(SavingsFormatError) as caught:
                        plans(payloads["savings"], payloads["products"])
                    self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")
                    self.assertIn("not in EUR", str(caught.exception))

    def test_optional_amount_without_value_is_none(self):
        savings = copy.deepcopy(SAVINGS)
        option = savings["items"][0]["compartments"][0]["investmentOptions"][0]
        option["gainsOrLossesAmount"] = {"value": None}
        line = next(p for p in plans(savings)[0]["positions"] if p["label"] == option["label"])
        self.assertIsNone(line["pnlEur"])

    def test_funds_in_sub_compartments_are_read(self):
        savings = copy.deepcopy(SAVINGS)
        pee = savings["items"][0]
        moved = pee["compartments"][0]["investmentOptions"].pop()
        pee["compartments"][1]["subCompartments"][0]["investmentOptions"] = [moved]
        lines = plans(savings)[0]["positions"]
        self.assertIn(moved["label"], [p["label"] for p in lines])

    def test_plan_missing_within_tolerance_is_incomplete(self):
        products = copy.deepcopy(PRODUCTS)
        products["items"].append({**products["items"][0], "id": "extra"})
        with self.assertRaises(SavingsFormatError) as caught:
            plans(products=products)
        self.assertEqual(caught.exception.code, "PORTFOLIO_INCOMPLETE")

    def test_same_label_without_fund_id_is_rejected(self):
        savings = copy.deepcopy(SAVINGS)
        options = savings["items"][0]["compartments"][0]["investmentOptions"]
        for option in options[:2]:
            option.pop("fundId")
            option["label"] = "SAME FUND"
        with self.assertRaises(SavingsFormatError) as caught:
            plans(savings)
        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_fund_without_code_keeps_its_natixis_id(self):
        line = plans()[0]["positions"][0]
        self.assertTrue(line["fundCode"].startswith("NIE-"))

    def test_too_long_plan_id_is_rejected_not_truncated(self):
        savings = copy.deepcopy(SAVINGS)
        savings["items"][0]["id"] = "P" * 120
        with self.assertRaises(SavingsFormatError):
            plans(savings)

    def test_no_plan_left_is_a_valid_empty_portfolio(self):
        products = copy.deepcopy(PRODUCTS)
        products["items"] = []
        for amounts in products["additionalInformation"].values():
            amounts["total"]["value"] = 0
        self.assertEqual(plans(savings={"items": []}, products=products), [])

    def test_products_without_totals_is_format_changed(self):
        with self.assertRaises(SavingsFormatError) as caught:
            plans(products={"items": []})
        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")


class FundTest(unittest.TestCase):
    def test_fund_ids_once_in_order(self):
        ids = fund_ids(SAVINGS)
        self.assertEqual(len(ids), 6)
        self.assertEqual(len(set(ids)), 6)

    def test_fund_code(self):
        self.assertEqual(fund_code(FUND_DETAIL), "QS0000001002")
        self.assertIsNone(fund_code({"identifiers": [{"type": "ISIN", "value": "FR0000000000"}]}))
        self.assertIsNone(fund_code({}))


if __name__ == "__main__":
    unittest.main()
