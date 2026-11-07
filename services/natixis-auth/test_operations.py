"""Operations mapping, on synthetic responses shaped after the real saver space."""

import copy
import unittest
from decimal import Decimal

from fixtures import EMPLOYEE_ID
from op_fixtures import INVESTMENT_DEMAND, PAYMENT_DEMAND, TRANSACTIONS
from operations_parser import (
    EMPLOYER_TOP_UP,
    external_id,
    finished_demands,
    investment_operations,
    operation_fund_ids,
    parse_operations,
    payment_operations,
)
from savings_parser import SavingsFormatError


class DemandsTest(unittest.TestCase):
    def test_finished_demands_with_their_detail_link(self):
        demands = finished_demands(TRANSACTIONS, EMPLOYEE_ID)
        self.assertEqual([d["type"] for d in demands], ["INVESTMENT_DEMANDS", "PAYMENT_DEMANDS", "INVESTMENT_DEMANDS"])
        self.assertTrue(all(d["path"].startswith(f"/transaction/v1/employees/{EMPLOYEE_ID}/") for d in demands))

    def test_unfinished_and_unknown_demands_are_skipped(self):
        transactions = copy.deepcopy(TRANSACTIONS)
        transactions["items"][0]["finished"] = False
        transactions["items"][1]["type"] = "ARBITRATION_DEMANDS"
        self.assertEqual(len(finished_demands(transactions, EMPLOYEE_ID)), 1)

    def test_listing_without_items_is_format_changed(self):
        with self.assertRaises(SavingsFormatError):
            finished_demands({}, EMPLOYEE_ID)

    def test_detail_path_is_rebuilt_from_an_absolute_api_link(self):
        transactions = copy.deepcopy(TRANSACTIONS)
        link = transactions["items"][0]["_links"]["self"]
        link["href"] = "https://nie.api.natixis.com" + link["href"]
        self.assertEqual(finished_demands(transactions, EMPLOYEE_ID)[0]["path"],
                         f"/transaction/v1/employees/{EMPLOYEE_ID}/investmentDemands/2024-03-15-INT--10000001-")

    def test_detail_links_outside_the_saver_space_are_format_changed(self):
        base = f"/transaction/v1/employees/{EMPLOYEE_ID}"
        for href in (
            f"{base}/investmentDemands/../../../../auth/v3/users/me",
            f"{base}/investmentDemands/..",
            f"{base}/investmentDemands/%2e%2e",
            f"{base}/investmentDemands/a%2Fb",
            f"{base}/investmentDemands//x",
            f"{base}/investmentDemands/x?y=1",
            f"{base}/investmentDemands/x#y",
            f"{base}/investmentDemands/",
            f"{base}/paymentDemands/2024-03-15-INT--10000001-",  # not this type's collection
            "/transaction/v1/employees/99999-1/investmentDemands/x",  # another saver
            f"http://nie.api.natixis.com{base}/investmentDemands/x",
            f"https://user@nie.api.natixis.com{base}/investmentDemands/x",
            f"https://nie.api.natixis.com:8443{base}/investmentDemands/x",
            f"https://evil.example{base}/investmentDemands/x",
            f"//evil.example{base}/investmentDemands/x",
            f"{base}/investmentDemands/x\n",  # urlsplit would drop the newline
            f"{base}/investmentDemands/x\ty",
            f"{base}/investmentDemands/x y",
            f"{base}/investmentDemands/x\u00e9",
        ):
            with self.subTest(href=href):
                transactions = copy.deepcopy(TRANSACTIONS)
                transactions["items"][0]["_links"]["self"]["href"] = href
                with self.assertRaises(SavingsFormatError):
                    finished_demands(transactions, EMPLOYEE_ID)


class InvestmentTest(unittest.TestCase):
    def test_each_fund_line_and_its_employer_top_up(self):
        operations = investment_operations(INVESTMENT_DEMAND, EMPLOYEE_ID, {})
        self.assertEqual(len(operations), 12)
        own = [op for op in operations if op["label"] != EMPLOYER_TOP_UP]
        top_up = [op for op in operations if op["label"] == EMPLOYER_TOP_UP]
        self.assertEqual(len(own), 6)
        self.assertEqual(len(top_up), 6)
        self.assertEqual({op["label"] for op in own}, {INVESTMENT_DEMAND["originLabel"]})
        first = INVESTMENT_DEMAND["investments"][0]
        line = next(op for op in own if op["fundLabel"] == first["fundLabel"])
        self.assertEqual(line["type"], "BUY")
        self.assertEqual(line["planExternalId"], f"natixis_{EMPLOYEE_ID}_PEE-4242")
        self.assertEqual(line["quantity"], Decimal(str(first["units"])))
        self.assertEqual(line["date"], first["investmentDate"])
        self.assertEqual(line["fundCode"], f"NIE-{first['fundId']}")

    def test_zero_unit_parts_are_not_operations(self):
        detail = copy.deepcopy(INVESTMENT_DEMAND)
        for line in detail["investments"]:
            line["additionalInvestment"]["units"] = 0
        self.assertEqual(len(investment_operations(detail, EMPLOYEE_ID, {})), 6)


class PaymentTest(unittest.TestCase):
    def test_each_sold_fund_line_is_a_sell(self):
        operations = payment_operations(PAYMENT_DEMAND, EMPLOYEE_ID, {1001: "QS0000001001"})
        self.assertEqual(len(operations), 4)
        self.assertEqual({op["type"] for op in operations}, {"SELL"})
        self.assertEqual({op["label"] for op in operations}, {"Déblocage anticipé"})
        first = PAYMENT_DEMAND["payments"][0]["products"][0]["investmentOptions"][0]
        sold = next(op for op in operations if op["fundLabel"] == first["label"])
        self.assertEqual(sold["fundCode"], "QS0000001001")
        self.assertEqual(sold["amountEur"], Decimal(str(first["gross"]["value"])))
        self.assertEqual(sold["date"], first["disvestmentValue"]["date"])


    def test_sold_amount_not_in_eur_is_format_changed(self):
        detail = copy.deepcopy(PAYMENT_DEMAND)
        detail["payments"][0]["products"][0]["investmentOptions"][0]["gross"]["currencyCode"] = "USD"
        with self.assertRaises(SavingsFormatError) as caught:
            payment_operations(detail, EMPLOYEE_ID, {})
        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")
        self.assertIn("not in EUR", str(caught.exception))

    def test_invested_amount_without_currency_is_format_changed(self):
        detail = copy.deepcopy(INVESTMENT_DEMAND)
        for line in detail["investments"]:
            for part in (line, line.get("additionalInvestment") or {}):
                for key in ("netInvested", "net"):
                    if isinstance(part.get(key), dict):
                        part[key].pop("currencyCode", None)
        with self.assertRaises(SavingsFormatError) as caught:
            investment_operations(detail, EMPLOYEE_ID, {})
        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")


class ParseOperationsTest(unittest.TestCase):
    def test_sold_out_funds_are_named_for_their_codes(self):
        self.assertIn(1007, operation_fund_ids([PAYMENT_DEMAND]))

    def test_ids_are_unique_and_bounded(self):
        demands = finished_demands(TRANSACTIONS, EMPLOYEE_ID)
        details = [{**(INVESTMENT_DEMAND if d["type"] == "INVESTMENT_DEMANDS" else PAYMENT_DEMAND), "id": d["id"]}
                   for d in demands]
        operations = parse_operations(demands, details, EMPLOYEE_ID, {})
        self.assertEqual(len({op["externalId"] for op in operations}), len(operations))
        self.assertTrue(all(len(op["externalId"]) <= 100 for op in operations))

    def test_same_detail_twice_is_rejected(self):
        demands = finished_demands(TRANSACTIONS, EMPLOYEE_ID)[:1] * 2
        with self.assertRaises(SavingsFormatError):
            parse_operations(demands, [INVESTMENT_DEMAND, INVESTMENT_DEMAND], EMPLOYEE_ID, {})

    def test_long_ids_are_hashed_not_truncated(self):
        long_id = external_id(EMPLOYEE_ID, "X" * 120, "line")
        self.assertLessEqual(len(long_id), 100)
        self.assertNotEqual(long_id, external_id(EMPLOYEE_ID, "X" * 121, "line"))


if __name__ == "__main__":
    unittest.main()
