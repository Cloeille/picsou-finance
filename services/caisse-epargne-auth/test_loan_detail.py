"""Parsing of the personal-loan detail (`personalLoanPrdSynthesisView`).

Runs on `fixtures_synthetic.json["loan_detail"]`: invented values, the key
structure captured in the 2026-10-13 spike (dates DD/MM/YYYY, `tNC` in percent).
"""

import copy
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from accounts_parser import ParseError, parse_loan_detail

FIXTURES = json.loads(Path(__file__).with_name("fixtures_synthetic.json").read_text(encoding="utf-8"))


def detail():
    return copy.deepcopy(FIXTURES["loan_detail"])


def view(d):
    return d["personalLoanPrdSynthesisView"]


def test_fixture_gives_the_loan_fields():
    loan = parse_loan_detail(detail())
    assert loan == {
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
    }


def test_rate_in_percent_becomes_a_fraction():
    d = detail()
    view(d)["folder"]["tNC"] = 4.25
    assert parse_loan_detail(d)["interestRate"] == Decimal("0.0425")


@pytest.mark.parametrize("rate", [-0.5, 100.5])
def test_rate_outside_zero_to_one_hundred_percent_raises(rate):
    d = detail()
    view(d)["folder"]["tNC"] = rate
    with pytest.raises(ParseError):
        parse_loan_detail(d)


def test_zero_rate_annuity_is_capital_over_duration():
    d = detail()
    view(d)["folder"]["tNC"] = 0
    view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 260
    loan = parse_loan_detail(d)
    assert loan["interestRate"] == Decimal("0")
    assert loan["insuranceMonthly"] == Decimal("10.00")


def test_installment_below_the_annuity_gives_no_insurance():
    d = detail()
    view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 250.0
    assert parse_loan_detail(d)["insuranceMonthly"] is None


def test_installment_equal_to_the_annuity_gives_zero_insurance():
    d = detail()
    view(d)["folder"]["nextMonthlyPaymentAmount"]["value"] = 265.61
    assert parse_loan_detail(d)["insuranceMonthly"] == Decimal("0.00")


@pytest.mark.parametrize(
    "path",
    [
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
    ],
)
def test_missing_field_raises(path):
    d = detail()
    node = view(d)
    for key in path[:-1]:
        node = node[key]
    del node[path[-1]]
    with pytest.raises(ParseError) as e:
        parse_loan_detail(d)
    assert e.value.code == "INVALID_LOAN_DETAIL"


@pytest.mark.parametrize("value", ["2026-11-05", "5/11/2026", "31/02/2026", 20261105, None])
def test_dates_must_be_dd_mm_yyyy(value):
    d = detail()
    view(d)["offer"]["firstDueDate"] = value
    with pytest.raises(ParseError):
        parse_loan_detail(d)


@pytest.mark.parametrize("value", [0, -12, 48.5, True, "48"])
def test_duration_must_be_a_positive_integer(value):
    d = detail()
    view(d)["offer"]["duration"] = value
    with pytest.raises(ParseError):
        parse_loan_detail(d)


@pytest.mark.parametrize("value", [0, -100, "12000"])
def test_borrowed_amount_must_be_a_positive_number(value):
    d = detail()
    view(d)["offer"]["proposedAmount"]["value"] = value
    with pytest.raises(ParseError):
        parse_loan_detail(d)


@pytest.mark.parametrize("payload", [None, [], "x", {}, {"personalLoanPrdSynthesisView": []}])
def test_wrong_envelope_raises(payload):
    with pytest.raises(ParseError):
        parse_loan_detail(payload)


def test_participants_and_account_label_are_never_read():
    # Names and the linked account label are personal data the import does
    # not need: anything there, even malformed, changes nothing.
    d = detail()
    view(d)["participants"] = "garbage"
    view(d)["linkedAccount"]["label"] = {"not": "a string"}
    loan = parse_loan_detail(d)
    assert "FAKEFIRST" not in repr(loan) and "FAKE LINKED" not in repr(loan)
    del view(d)["participants"]
    del view(d)["linkedAccount"]["label"]
    assert parse_loan_detail(d) == loan


def test_error_messages_carry_no_payload_values():
    d = detail()
    view(d)["offer"]["signatureDate"] = "secret-date-value"
    view(d)["linkedAccount"]["iban"] = 12345
    with pytest.raises(ParseError) as e:
        parse_loan_detail(d)
    assert "secret-date-value" not in str(e.value) and "12345" not in str(e.value)


def test_parse_does_not_mutate_its_input():
    d = detail()
    before = copy.deepcopy(d)
    parse_loan_detail(d)
    assert d == before
