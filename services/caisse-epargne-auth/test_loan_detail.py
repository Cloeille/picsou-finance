"""Parsing of the personal-loan detail (`personalLoanPrdSynthesisView`).

Runs on `fixtures_synthetic.json["loan_detail"]`: invented values, the key
structure captured in the 2026-10-13 spike (dates DD/MM/YYYY, `tNC` in percent).
"""

import copy
import json
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path

from accounts_parser import ParseError, parse_loan_detail

FIXTURES = json.loads(Path(__file__).with_name("fixtures_synthetic.json").read_text(encoding="utf-8"))


def detail():
    return copy.deepcopy(FIXTURES["loan_detail"])


def view(d):
    return d["personalLoanPrdSynthesisView"]


class LoanDetailTest(unittest.TestCase):
    def test_fixture_gives_the_loan_fields(self):
        loan = parse_loan_detail(detail())
        self.assertEqual(loan, {
            "borrowedAmount": Decimal("12000"),
            "interestRate": Decimal("0.03"),
            "monthlyPayment": Decimal("280.0"),
            # 12 000 at 3 % over 48 months: annuity 265.61, so 14.39 of insurance.
            "insuranceMonthly": Decimal("14.39"),
            "signatureDate": date(2026, 9, 15),
            "firstDueDate": date(2026, 11, 5),
            "lastDueDate": date(2030, 10, 5),
            "durationMonths": 48,
            "linkedIban": "FR7600000000000000000000001",
        })

    def test_rate_in_percent_becomes_a_fraction(self):
        d = detail()
        view(d)["folder"]["tNC"] = 4.25
        self.assertEqual(parse_loan_detail(d)["interestRate"], Decimal("0.0425"))

    def test_rate_outside_zero_to_one_hundred_percent_raises(self):
        for rate in [-0.5, 100.5]:
            with self.subTest(rate=rate):
                d = detail()
                view(d)["folder"]["tNC"] = rate
                with self.assertRaises(ParseError):
                    parse_loan_detail(d)

    def test_an_amount_too_large_to_quantize_raises(self):
        d = detail()
        view(d)["offer"]["proposedAmount"]["value"] = 1e30
        with self.assertRaises(ParseError):
            parse_loan_detail(d)

    def test_a_duration_above_fifty_years_raises(self):
        for months in (601, 10**9):
            with self.subTest(months=months):
                d = detail()
                view(d)["offer"]["duration"] = months
                with self.assertRaises(ParseError):
                    parse_loan_detail(d)

    def test_zero_rate_annuity_is_capital_over_duration(self):
        d = detail()
        view(d)["folder"]["tNC"] = 0
        view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 260
        loan = parse_loan_detail(d)
        self.assertEqual(loan["interestRate"], Decimal("0"))
        self.assertEqual(loan["insuranceMonthly"], Decimal("10.00"))

    def test_installment_below_the_annuity_gives_no_insurance(self):
        d = detail()
        view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 250.0
        self.assertIsNone(parse_loan_detail(d)["insuranceMonthly"])

    def test_installment_equal_to_the_annuity_gives_zero_insurance(self):
        d = detail()
        view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 265.61
        self.assertEqual(parse_loan_detail(d)["insuranceMonthly"], Decimal("0.00"))

    def test_missing_field_raises(self):
        for path in [
            ("offer", "proposedAmount"),
            ("offer", "duration"),
            ("offer", "signatureDate"),
            ("offer", "firstDueDate"),
            ("folder", "lastDueDate"),
            ("folder", "tNC"),
            ("folder", "nextMonthlyPaymentAmount"),
            ("linkedAccount", "iban"),
            ("offer",),
            ("folder",),
            ("linkedAccount",),
        ]:
            with self.subTest(path=path):
                d = detail()
                node = view(d)
                for key in path[:-1]:
                    node = node[key]
                del node[path[-1]]
                with self.assertRaises(ParseError) as e:
                    parse_loan_detail(d)
                self.assertEqual(e.exception.code, "INVALID_LOAN_DETAIL")

    def test_dates_must_be_dd_mm_yyyy(self):
        for value in ["2026-11-05", "5/11/2026", "31/02/2026", 20261105, None]:
            with self.subTest(value=value):
                d = detail()
                view(d)["offer"]["firstDueDate"] = value
                with self.assertRaises(ParseError):
                    parse_loan_detail(d)

    def test_duration_must_be_a_positive_integer(self):
        for value in [0, -12, 48.5, True, "48"]:
            with self.subTest(value=value):
                d = detail()
                view(d)["offer"]["duration"] = value
                with self.assertRaises(ParseError):
                    parse_loan_detail(d)

    def test_borrowed_amount_must_be_a_positive_number(self):
        for value in [0, -100, "12000"]:
            with self.subTest(value=value):
                d = detail()
                view(d)["offer"]["proposedAmount"]["value"] = value
                with self.assertRaises(ParseError):
                    parse_loan_detail(d)

    def test_an_amount_beyond_decimal_precision_is_refused(self):
        d = detail()
        view(d)["offer"]["proposedAmount"]["value"] = 10**30
        with self.assertRaises(ParseError) as e:
            parse_loan_detail(d)
        self.assertEqual(e.exception.code, "INVALID_LOAN_DETAIL")

    def test_a_tiny_rate_keeps_the_zero_rate_insurance(self):
        # 12 000 / 48 = 250.00; interest at these rates cannot change a cent.
        for rate in (0, 1e-24, 1e-30, 1e-56, 1e-100, 5e-324):
            with self.subTest(rate=rate):
                tiny = detail()
                view(tiny)["folder"]["tNC"] = rate
                self.assertEqual(
                    parse_loan_detail(tiny)["insuranceMonthly"],
                    Decimal("30.00"),
                )

    def test_tiny_rates_round_the_annuity_at_half_cent_boundaries(self):
        for borrowed, duration, expected in (
            (250.005, 1, "29.99"),
            (750.015, 3, "29.99"),
            (12000.23, 48, "30.00"),
            (12000.24, 48, "29.99"),
            (12000.25, 48, "29.99"),
            (12250.245, 49, "29.99"),
        ):
            for rate in (0, 1e-24, 1e-30, 1e-56, 2e-100, 5e-324):
                with self.subTest(borrowed=borrowed, duration=duration, rate=rate):
                    d = detail()
                    view(d)["offer"]["proposedAmount"]["value"] = borrowed
                    view(d)["offer"]["duration"] = duration
                    view(d)["folder"]["tNC"] = rate
                    view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 280

                    self.assertEqual(parse_loan_detail(d)["insuranceMonthly"], Decimal(expected))

    def test_annuity_rounding_at_an_exact_half_cent(self):
        # At 12 % over two months, 100.50 gives an exact annuity of 10201/200 = 51.005.
        for borrowed, expected in (
            (100.49999999999999, "29.00"),
            (100.50, "28.99"),
            (100.50000000000001, "28.99"),
        ):
            with self.subTest(borrowed=borrowed):
                d = detail()
                view(d)["offer"]["proposedAmount"]["value"] = borrowed
                view(d)["offer"]["duration"] = 2
                view(d)["folder"]["tNC"] = 12
                view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 80

                self.assertEqual(parse_loan_detail(d)["insuranceMonthly"], Decimal(expected))

    def test_wrong_envelope_raises(self):
        for payload in [None, [], "x", {}, {"personalLoanPrdSynthesisView": []}]:
            with self.subTest(payload=payload):
                with self.assertRaises(ParseError):
                    parse_loan_detail(payload)

    def test_participants_and_account_label_are_never_read(self):
        # Names and the linked account label are personal data the import does
        # not need: anything there, even malformed, changes nothing.
        d = detail()
        view(d)["participants"] = "garbage"
        view(d)["linkedAccount"]["label"] = {"not": "a string"}
        loan = parse_loan_detail(d)
        self.assertNotIn("FAKEFIRST", repr(loan))
        self.assertNotIn("FAKE LINKED", repr(loan))
        del view(d)["participants"]
        del view(d)["linkedAccount"]["label"]
        self.assertEqual(parse_loan_detail(d), loan)

    def test_error_messages_carry_no_payload_values(self):
        d = detail()
        view(d)["offer"]["signatureDate"] = "secret-date-value"
        view(d)["linkedAccount"]["iban"] = 12345
        with self.assertRaises(ParseError) as e:
            parse_loan_detail(d)
        self.assertNotIn("secret-date-value", str(e.exception))
        self.assertNotIn("12345", str(e.exception))

    def test_parse_does_not_mutate_its_input(self):
        d = detail()
        before = copy.deepcopy(d)
        parse_loan_detail(d)
        self.assertEqual(d, before)


if __name__ == "__main__":
    unittest.main()
