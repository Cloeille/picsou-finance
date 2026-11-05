"""CORUM Life parsing: one holding per fund across pockets, the euro fund as
1-euro units, and a contract that does not add up refused whole.

`unittest`, not pytest: the CI job runs the sidecar image with
`python -m unittest discover`. The payload is synthetic (`fixtures_life`).
"""

import unittest
from decimal import Decimal

from fixtures_life import LIFE_CODE, life_payload, line, operations_payload, pocket, set_total
from life_parser import parse_life, parse_operations
from positions_parser import PositionsFormatError


class ParseLifeTest(unittest.TestCase):
    def assertRefused(self, payload, code):
        with self.assertRaises(PositionsFormatError) as caught:
            parse_life(payload)
        self.assertEqual(caught.exception.code, code)

    def by_code(self, snapshot):
        return {h["productCode"]: h for h in snapshot["holdings"]}

    def test_contract_envelope(self):
        snapshot = parse_life(life_payload())

        self.assertEqual(snapshot["contractCode"], LIFE_CODE)
        self.assertEqual(snapshot["currency"], "EUR")
        self.assertEqual(snapshot["totalValuationEur"], Decimal("3506.56"))
        self.assertEqual(snapshot["valuationDate"], "2026-11-04")
        self.assertTrue(snapshot["snapshotComplete"])

    def test_line_count_is_every_raw_row_including_the_euro_fund(self):
        # 5 + 4 + 1 rows, even though they collapse into 7 holdings.
        snapshot = parse_life(life_payload())

        self.assertEqual(snapshot["valuationLineCount"], 10)
        self.assertEqual(len(snapshot["holdings"]), 7)

    def test_a_fund_in_two_pockets_is_one_holding(self):
        holdings = self.by_code(parse_life(life_payload()))

        self.assertEqual(
            list(holdings), ["CC", "XL", "CBEHY", "BCO", "EU", "CBE", "FGCORUM"]
        )
        xl = holdings["XL"]
        self.assertEqual(xl["quantity"], Decimal("3.5"))
        self.assertEqual(xl["valueEur"], Decimal("665.0"))
        self.assertEqual(xl["netInvestedEur"], Decimal("652.0"))
        self.assertEqual(xl["unitPrice"], Decimal("190.0"))
        self.assertEqual(holdings["EU"]["valueEur"], Decimal("700.0"))
        self.assertEqual(holdings["CC"]["quantity"], Decimal("3.5"))

    def test_the_latest_nav_date_across_pockets_is_kept(self):
        holdings = self.by_code(parse_life(life_payload()))

        self.assertEqual(holdings["XL"]["valuationDate"], "2026-11-03")
        self.assertEqual(holdings["CC"]["valuationDate"], "2026-10-31")

    def test_the_euro_fund_is_an_invested_holding_in_one_euro_units(self):
        snapshot = parse_life(life_payload())

        self.assertNotIn("euroFundEur", snapshot)
        euro = self.by_code(snapshot)["FGCORUM"]
        self.assertTrue(euro["euroFund"])
        self.assertEqual(euro["label"], "CORUM EuroLife (fonds euros)")
        self.assertEqual(euro["valueEur"], Decimal("1234.56"))
        # The payload's integer quantity/unitPrice (0/0 here) are not read.
        self.assertEqual(euro["quantity"], Decimal("1234.56"))
        self.assertEqual(euro["unitPrice"], Decimal("1"))
        self.assertEqual(euro["netInvestedEur"], Decimal("1200.0"))
        self.assertIsNone(euro["valuationDate"])

    def test_unit_linked_holdings_are_not_flagged_euro_fund(self):
        holdings = self.by_code(parse_life(life_payload()))

        for code, holding in holdings.items():
            self.assertIs(holding["euroFund"], code == "FGCORUM", code)

    def test_holdings_give_back_the_contract(self):
        snapshot = parse_life(life_payload())

        held = sum(h["valueEur"] for h in snapshot["holdings"])
        self.assertEqual(held, snapshot["totalValuationEur"])

    def test_the_euro_fund_split_across_pockets_is_one_holding(self):
        payload = life_payload()
        euro = line(payload, "CORUM Eurolife", "FGCORUM")
        euro["amount"] = 1000.0
        pocket(payload, "CORUM Eurolife")["amount"] = 1000.0
        pocket(payload, "Gestion libre")["sectionProduct"].append(
            {**euro, "amount": 234.56, "netInvestedAmountCumulated": 200.0}
        )
        pocket(payload, "Gestion libre")["amount"] = 1759.56

        snapshot = parse_life(payload)
        holding = self.by_code(snapshot)["FGCORUM"]

        self.assertEqual(holding["valueEur"], Decimal("1234.56"))
        self.assertEqual(holding["quantity"], Decimal("1234.56"))
        self.assertEqual(holding["netInvestedEur"], Decimal("1400.0"))
        self.assertEqual(snapshot["valuationLineCount"], 11)

    def test_figures_are_decimals_not_floats(self):
        snapshot = parse_life(life_payload())

        for holding in snapshot["holdings"]:
            for field in ("quantity", "unitPrice", "valueEur", "netInvestedEur"):
                self.assertIsInstance(holding[field], Decimal, field)

    def test_labels_are_mapped_or_keep_the_code(self):
        holdings = self.by_code(parse_life(life_payload()))

        self.assertEqual(holdings["CC"]["label"], "CORUM Origin")
        self.assertEqual(holdings["XL"]["label"], "CORUM XL")
        self.assertEqual(holdings["EU"]["label"], "CORUM Eurion")
        self.assertEqual(holdings["CBEHY"]["label"], "CORUM BEHY")
        self.assertEqual(holdings["CBE"]["label"], "CORUM Butler Entreprise")

    def test_an_unknown_code_keeps_the_code_as_label(self):
        payload = life_payload()
        line(payload, "Gestion libre", "BCO")["productName"] = "NEWFUND"

        holdings = self.by_code(parse_life(payload))

        self.assertEqual(holdings["NEWFUND"]["label"], "CORUM NEWFUND")

    def test_an_unknown_cost_in_one_pocket_makes_the_fund_cost_unknown(self):
        payload = life_payload()
        line(payload, "Corum Life Horizon", "XL")["netInvestedAmountCumulated"] = None

        holdings = self.by_code(parse_life(payload))

        self.assertIsNone(holdings["XL"]["netInvestedEur"])
        self.assertEqual(holdings["CC"]["netInvestedEur"], Decimal("615.0"))

    def test_reallocation_amount_is_ignored(self):
        payload = life_payload()
        line(payload, "Gestion libre", "CBEHY")["reallocationAmount"] = 99999.0

        holdings = self.by_code(parse_life(payload))

        self.assertEqual(holdings["CBEHY"]["valueEur"], Decimal("105.0"))

    def test_two_prices_for_one_fund_give_the_price_of_the_summed_figures(self):
        # The same 95 EUR at an older, lower NAV: more units in that pocket.
        payload = life_payload()
        xl = line(payload, "Corum Life Horizon", "XL")
        xl["quantity"] = 0.6
        xl["unitPrice"] = 158.33

        holdings = self.by_code(parse_life(payload))

        self.assertEqual(holdings["XL"]["quantity"], Decimal("3.6"))
        self.assertEqual(holdings["XL"]["valueEur"], Decimal("665.0"))
        # 665 / 3.6, at the backend's 8-decimal scale.
        self.assertEqual(holdings["XL"]["unitPrice"], Decimal("184.72222222"))
        self.assertEqual(holdings["XL"]["valuationDate"], "2026-11-03")

    def test_two_prices_for_a_fund_with_no_unit_keep_the_first_price(self):
        payload = life_payload()
        for pocket_name, price in (("Gestion libre", 190.0), ("Corum Life Horizon", 191.0)):
            xl = line(payload, pocket_name, "XL")
            xl.update(quantity=0, unitPrice=price, amount=0)
        # The 665 EUR moved to CC so every total still adds up.
        line(payload, "Gestion libre", "CC")["amount"] = 1020.0
        line(payload, "Corum Life Horizon", "CC")["amount"] = 275.0

        holdings = self.by_code(parse_life(payload))

        self.assertEqual(holdings["XL"]["quantity"], Decimal("0"))
        self.assertEqual(holdings["XL"]["unitPrice"], Decimal("190.0"))

    def test_a_dated_euro_fund_is_refused(self):
        payload = life_payload()
        line(payload, "CORUM Eurolife", "FGCORUM")["valorisationDate"] = "31/10/2026"

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_without_the_euro_life_section_the_euro_fund_is_refused(self):
        # Its int quantity is 0 against a positive amount: read as a unit line
        # it is a half-written position, so the contract is refused, not
        # written with a made-up unit count.
        payload = life_payload()
        payload["contractProfile"]["chartSections"] = [
            s for s in payload["contractProfile"]["chartSections"]
            if s["sectionName"] != "euroLife"
        ]

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_a_pocket_that_does_not_add_up_is_refused(self):
        payload = life_payload()
        pocket(payload, "Gestion libre")["amount"] = 1525.06
        # Keep the contract total in step so only the pocket check can fail.
        set_total(payload, 3506.62)

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_a_pocket_within_tolerance_passes(self):
        payload = life_payload()
        pocket(payload, "Gestion libre")["amount"] = 1525.05
        set_total(payload, 3506.61)

        self.assertEqual(parse_life(payload)["totalValuationEur"], Decimal("3506.61"))

    def test_pockets_that_do_not_reach_the_contract_are_refused(self):
        # Ten rows: tolerance is ten cents.
        payload = life_payload()
        set_total(payload, 3506.67)

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_contract_tolerance_scales_with_the_line_count(self):
        payload = life_payload()
        set_total(payload, 3506.66)

        self.assertEqual(parse_life(payload)["totalValuationEur"], Decimal("3506.66"))

    def test_a_missing_pocket_is_refused(self):
        payload = life_payload()
        sections = payload["contractProfile"]["distributionTableSections"]
        sections.remove(pocket(payload, "Corum Life Horizon"))

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_no_pockets_with_a_positive_contract_is_refused(self):
        payload = life_payload()
        payload["contractProfile"]["distributionTableSections"] = []

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_zero_units_with_a_value_is_incomplete(self):
        payload = life_payload()
        line(payload, "Gestion libre", "BCO")["quantity"] = 0.0

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_negative_quantity_is_refused(self):
        payload = life_payload()
        line(payload, "Gestion libre", "BCO")["quantity"] = -4.0

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_an_amount_that_is_not_a_json_number_is_refused(self):
        for bad in ("100.0", True, None, float("nan"), float("inf")):
            with self.subTest(amount=bad):
                payload = life_payload()
                line(payload, "Gestion libre", "BCO")["amount"] = bad
                self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_missing_amount_is_refused(self):
        payload = life_payload()
        del line(payload, "Gestion libre", "BCO")["amount"]

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_string_quantity_or_price_is_refused(self):
        for field in ("quantity", "unitPrice"):
            with self.subTest(field=field):
                payload = life_payload()
                line(payload, "Gestion libre", "BCO")[field] = "4"
                self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_string_contract_total_is_refused(self):
        payload = life_payload()
        payload["contractDetail"]["valuationAmount"] = "3506.56"

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_negative_contract_is_incomplete(self):
        payload = life_payload()
        payload["contractDetail"]["valuationAmount"] = -1.0

        self.assertRefused(payload, "PORTFOLIO_INCOMPLETE")

    def test_a_reshaped_payload_is_refused(self):
        cases = {
            "not a dict": lambda p: [p],
            "no profile": lambda p: {**p, "contractProfile": None},
            "no chart sections": lambda p: {
                **p, "contractProfile": {**p["contractProfile"], "chartSections": None}
            },
            "no pockets": lambda p: {
                **p,
                "contractProfile": {**p["contractProfile"], "distributionTableSections": None},
            },
            "no contract code": lambda p: {**p, "contractCode": ""},
            "not a life contract": lambda p: {**p, "contractType": "REAL_ESTATE"},
        }
        for name, reshape in cases.items():
            with self.subTest(case=name):
                self.assertRefused(reshape(life_payload()), "UPSTREAM_FORMAT_CHANGED")

    def test_a_line_without_a_code_is_refused(self):
        payload = life_payload()
        line(payload, "Gestion libre", "BCO")["productName"] = None

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unparseable_date_is_dropped_rather_than_guessed(self):
        payload = life_payload()
        line(payload, "Gestion libre", "BCO")["valorisationDate"] = "not a date"

        holdings = self.by_code(parse_life(payload))

        self.assertIsNone(holdings["BCO"]["valuationDate"])

    def test_contract_facts(self):
        snapshot = parse_life(life_payload())

        self.assertEqual(snapshot["netInvestedEur"], Decimal("3480.0"))
        self.assertEqual(snapshot["paidInEur"], Decimal("3540"))
        self.assertEqual(snapshot["openedAt"], "2024-01-15")
        self.assertEqual(
            snapshot["scheduledPayment"], {"amountEur": Decimal("50"), "frequency": "monthly"}
        )

    def test_a_switch_leaves_the_lines_short_of_the_contract_cost(self):
        # The euro fund switched 100 EUR into CBEHY: the lines' own costs leave
        # it out, so only the contract figure is a cost basis.
        snapshot = parse_life(life_payload())
        holdings = self.by_code(snapshot)

        lines_net = sum(h["netInvestedEur"] for h in snapshot["holdings"])
        self.assertEqual(lines_net, Decimal("3315.0"))
        self.assertLess(lines_net, snapshot["netInvestedEur"])
        # Per-line costs are still sent raw; the backend decides whether to use them.
        self.assertEqual(holdings["CBEHY"]["netInvestedEur"], Decimal("0.0"))

    def test_the_contract_cost_is_required(self):
        for bad in (None, "3480.0", True):
            with self.subTest(net=bad):
                payload = life_payload()
                payload["contractDetail"]["netInvestedAmount"] = bad
                self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_missing_contract_cost_is_refused(self):
        payload = life_payload()
        del payload["contractDetail"]["netInvestedAmount"]

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_negative_contract_cost_is_refused(self):
        payload = life_payload()
        payload["contractDetail"]["netInvestedAmount"] = -1.0
        payload["contractDetail"]["gainOrLoss"] = 3507.56

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_gain_that_does_not_match_value_minus_cost_is_refused(self):
        payload = life_payload()
        payload["contractDetail"]["gainOrLoss"] = 26.62

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_a_gain_within_five_cents_passes(self):
        payload = life_payload()
        payload["contractDetail"]["gainOrLoss"] = 26.61

        self.assertEqual(parse_life(payload)["netInvestedEur"], Decimal("3480.0"))

    def test_a_missing_gain_is_refused(self):
        payload = life_payload()
        del payload["contractDetail"]["gainOrLoss"]

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_paid_in_is_optional_but_never_negative(self):
        payload = life_payload()
        del payload["contractDetail"]["grossInvestedAmount"]
        self.assertIsNone(parse_life(payload)["paidInEur"])

        payload = life_payload()
        payload["contractDetail"]["grossInvestedAmount"] = -1
        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unreadable_opening_date_is_dropped(self):
        for bad in (None, "2024-01-15", 20240115, "31/02/2024"):
            with self.subTest(date=bad):
                payload = life_payload()
                payload["creationDate"] = bad
                self.assertIsNone(parse_life(payload)["openedAt"])

    def test_an_odd_savings_plan_is_dropped_not_refused(self):
        cases = (
            None,
            {"amount": 50},
            {"amount": "50", "frequency": "monthly"},
            {"amount": -5, "frequency": "monthly"},
            {"amount": 50, "frequency": ""},
            "monthly",
        )
        for plan in cases:
            with self.subTest(plan=plan):
                payload = life_payload()
                payload["contractDetail"]["programSavingAmount"] = plan
                self.assertIsNone(parse_life(payload)["scheduledPayment"])

    def test_a_zero_savings_plan_is_sent_as_it_is(self):
        # The backend decides a zero plan is no plan; the sidecar reports.
        payload = life_payload()
        payload["contractDetail"]["programSavingAmount"] = {"amount": 0, "frequency": "monthly"}

        self.assertEqual(parse_life(payload)["scheduledPayment"]["amountEur"], Decimal("0"))

    def test_each_holding_carries_its_asset_class(self):
        holdings = self.by_code(parse_life(life_payload()))

        self.assertEqual(holdings["CC"]["assetClass"], "realEstate")
        self.assertEqual(holdings["XL"]["assetClass"], "realEstate")
        self.assertEqual(holdings["EU"]["assetClass"], "realEstate")
        self.assertEqual(holdings["CBEHY"]["assetClass"], "bondFund")
        self.assertEqual(holdings["BCO"]["assetClass"], "bondFund")
        self.assertEqual(holdings["FGCORUM"]["assetClass"], "euroLife")

    def test_a_code_missing_from_the_asset_class_view_has_none(self):
        payload = life_payload()
        payload["contractProfile"]["chartSections"][0]["sectionProduct"] = [
            p for p in payload["contractProfile"]["chartSections"][0]["sectionProduct"]
            if p["productName"] != "BCO"
        ]

        holdings = self.by_code(parse_life(payload))

        self.assertIsNone(holdings["BCO"]["assetClass"])

    def test_a_code_in_two_asset_classes_is_refused(self):
        payload = life_payload()
        payload["contractProfile"]["chartSections"][0]["sectionProduct"].append(
            {"productName": "XL", "percent": 1.0, "amount": 1.0}
        )

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_an_asset_class_without_a_name_is_refused(self):
        payload = life_payload()
        payload["contractProfile"]["chartSections"][0]["sectionName"] = None

        self.assertRefused(payload, "UPSTREAM_FORMAT_CHANGED")

    def test_an_unknown_asset_class_is_passed_through(self):
        payload = life_payload()
        payload["contractProfile"]["chartSections"][0]["sectionName"] = "privateEquity"

        holdings = self.by_code(parse_life(payload))

        self.assertEqual(holdings["CBE"]["assetClass"], "privateEquity")


class ParseOperationsTest(unittest.TestCase):
    def ops(self, payload=None):
        return parse_operations(
            operations_payload() if payload is None else payload, [LIFE_CODE]
        )[LIFE_CODE]

    def life_years(self, payload):
        for contract in payload:
            if contract["contractCode"] == LIFE_CODE:
                return contract["yearData"]
        raise KeyError(LIFE_CODE)

    def assertRefused(self, payload):
        with self.assertRaises(PositionsFormatError) as caught:
            parse_operations(payload, [LIFE_CODE])
        self.assertEqual(caught.exception.code, "UPSTREAM_FORMAT_CHANGED")

    def test_every_year_is_flattened_in_payload_order(self):
        ops = self.ops()

        self.assertEqual(
            [op["operationId"] for op in ops],
            ["700010", "700009", "700008", "700007", "700006", "700005", "700004", "700001"],
        )

    def test_one_operation_is_read_whole(self):
        coupon = self.ops()[0]

        self.assertEqual(coupon, {
            "operationId": "700010",
            "type": "couponDetachment",
            "category": "couponDetachment",
            "date": "2026-10-31",
            "amountEur": Decimal("3.21"),
            "fundCodes": ["CC"],
        })

    def test_amounts_keep_their_sign(self):
        by_id = {op["operationId"]: op for op in self.ops()}

        self.assertEqual(by_id["700008"]["amountEur"], Decimal("-1.42"))
        self.assertEqual(by_id["700007"]["amountEur"], Decimal("-100.0"))
        self.assertEqual(by_id["700006"]["amountEur"], Decimal("100.0"))

    def test_fund_codes_are_split_and_upper_cased(self):
        by_id = {op["operationId"]: op for op in self.ops()}

        self.assertEqual(by_id["700004"]["fundCodes"], ["EU", "XL"])
        self.assertIsNone(by_id["700009"]["fundCodes"])

    def test_blank_fund_codes_are_none(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["fundCodes"] = " , "

        self.assertIsNone(self.ops(payload)[0]["fundCodes"])

    def test_other_contracts_are_left_out(self):
        result = parse_operations(operations_payload(), [LIFE_CODE])

        self.assertEqual(list(result), [LIFE_CODE])

    def test_a_contract_the_payload_does_not_list_is_not_reported(self):
        result = parse_operations(operations_payload(), [LIFE_CODE, "000999"])

        self.assertIsNone(result["000999"])
        self.assertEqual(len(result[LIFE_CODE]), 8)

    def test_a_contract_without_operations_is_an_empty_list(self):
        payload = operations_payload()
        for year in self.life_years(payload):
            year["operations"] = []

        self.assertEqual(self.ops(payload), [])

    def test_a_contract_listed_twice_is_refused(self):
        payload = operations_payload()
        payload.append(payload[1])

        self.assertRefused(payload)

    def test_a_string_amount_is_refused(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["amount"] = "3.21"

        self.assertRefused(payload)

    def test_an_unreadable_date_is_refused(self):
        # 31/02 passes a per-field range check but is not a calendar date.
        for date in (None, "2026-10-31", "31/13/2026", "", "31/02/2026"):
            payload = operations_payload()
            self.life_years(payload)[0]["operations"][0]["date"] = date
            with self.subTest(date=date):
                self.assertRefused(payload)

    def test_an_operation_with_several_ids_is_refused(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["operationIds"] = [1, 2]

        self.assertRefused(payload)

    def test_the_two_legs_of_a_switch_may_share_an_id(self):
        payload = operations_payload()
        # 700007 (-100) and 700006 (+100) are the two legs of one switch.
        self.life_years(payload)[0]["operations"][4]["operationIds"] = ["700007"]
        ids = [op["operationId"] for op in self.ops(payload)]

        self.assertEqual(ids.count("700007"), 2)

    def test_two_operations_sharing_an_id_and_a_sign_drop_that_contract_only(self):
        payload = operations_payload()
        # 700010 (+3.21) and 700009 (a payment, positive): one key for two rows.
        self.life_years(payload)[0]["operations"][1]["operationIds"] = ["700010"]

        with self.assertLogs("corum-auth", level="WARNING") as logs:
            result = parse_operations(payload, [LIFE_CODE])

        self.assertIsNone(result[LIFE_CODE])
        self.assertNotIn("700010", "".join(logs.output))

    def test_a_digit_string_or_integer_id_is_kept_as_its_digits(self):
        for raw in ("700010", 700010):
            payload = operations_payload()
            self.life_years(payload)[0]["operations"][0]["operationIds"] = [raw]
            with self.subTest(raw=raw):
                self.assertEqual(self.ops(payload)[0]["operationId"], "700010")

    def test_a_non_integer_id_is_refused(self):
        for ids in ([], ["70a010"], ["-1"], [" 7"], [1.5], [True], None):
            payload = operations_payload()
            self.life_years(payload)[0]["operations"][0]["operationIds"] = ids
            with self.subTest(ids=ids):
                self.assertRefused(payload)

    def test_a_missing_type_is_refused(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["operationType"] = None

        self.assertRefused(payload)

    def test_an_unknown_type_is_passed_through(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["operationType"] = "partialSurrender"

        self.assertEqual(self.ops(payload)[0]["type"], "partialSurrender")

    def test_a_null_category_is_kept_null(self):
        payload = operations_payload()
        self.life_years(payload)[0]["operations"][0]["operationCategory"] = None

        self.assertIsNone(self.ops(payload)[0]["category"])

    def test_a_payload_that_is_not_a_list_is_refused(self):
        self.assertRefused({"contracts": []})


if __name__ == "__main__":
    unittest.main()
