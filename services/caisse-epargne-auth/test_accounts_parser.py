"""Parsing rules for the Caisse d'Epargne synthesis and transaction payloads.

Everything runs on `fixtures_synthetic.json`: invented values, real key
structure. The fixture has no `parsedData.clientIBAN` (the real field is only
documented in the findings), so IBAN tests inject an invented one on a copy.
"""

import copy
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from accounts_parser import (
    ParseError,
    build_import,
    card_outstanding,
    extract_iban,
    next_due_date,
    parse_synthesis,
    parse_transactions,
)

FIXTURES = json.loads(
    (Path(__file__).with_name("fixtures_synthetic.json")).read_text(encoding="utf-8")
)
TODAY = date(2026, 10, 7)
IBAN_A = "FR7600000000000000000000001"
IBAN_B = "FR7600000000000000000000002"


def synthesis():
    return copy.deepcopy(FIXTURES["synthesis"])


def tx(name):
    return copy.deepcopy(FIXTURES["transactions"][name])


def contract(s, pfm_id):
    return next(i for i in s["items"] if i["identification"]["contractPfmId"] == pfm_id)


def card(s, pfm_id):
    return next(
        c
        for i in s["items"]
        for c in i["identity"]["augmentedCards"]
        if c["cardPfmId"] == pfm_id
    )


def by_id(accounts):
    return {a["externalId"]: a for a in accounts}


def with_iban(page, iban):
    for row in page["data"]:
        row["parsedData"]["clientIBAN"] = iban
    return page


def card_page(*rows):
    """A card page from (id, amount, dueDate) tuples, built off a fixture row."""
    page = tx("carte_active")
    template = page["data"][0]
    page["data"] = []
    for tx_id, amount, due in rows:
        row = copy.deepcopy(template)
        row.update({"id": tx_id, "amount": amount, "dueDate": due})
        page["data"].append(row)
    return page


# ─── Contracts → accounts ───────────────────────────────────────────────────


def test_fixture_yields_current_livret_and_active_card_only():
    accounts = by_id(parse_synthesis(synthesis()))
    assert set(accounts) == {"1001", "1002", "2001"}  # card 2002 (XXX) ignored
    assert [accounts[k]["kind"] for k in ("1001", "1002", "2001")] == [
        "CURRENT_ACCOUNT",
        "LIVRET_A",
        "CARD",
    ]


def test_current_account_fields():
    a = by_id(parse_synthesis(synthesis()))["1001"]
    assert a["balance"] == {"value": Decimal("1234.56"), "currency": "EUR"}
    assert a["authorizedOverdraft"] == {"value": Decimal("0.0"), "currency": "EUR"}
    assert a["name"] == "FAKE CPT DEPOT PART."
    assert a["iban"] is None and a["ibanAmbiguous"] is False
    assert a["ceiling"] is None
    assert a["remainingDepositCapacity"] is None
    assert a["fillingRatio"] is None


def test_livret_a_savings_fields():
    a = by_id(parse_synthesis(synthesis()))["1002"]
    assert a["balance"] == {"value": Decimal("5000.0"), "currency": "EUR"}
    assert a["ceiling"] == {"value": Decimal("22950.0"), "currency": "EUR"}
    assert a["remainingDepositCapacity"] == {
        "value": Decimal("17950.0"),
        "currency": "EUR",
    }
    # Unit of fillingRatio is unverified: passed through, never rescaled.
    assert a["fillingRatio"] == Decimal("21.8")


def test_savings_fields_tolerate_null():
    s = synthesis()
    ident = contract(s, 1002)["identity"]
    ident["authorizedCeilingAmount"] = None
    ident["remainingDepositCapacity"] = None
    ident["fillingRatio"] = None
    ident["authorizedOverdraft"] = None
    a = by_id(parse_synthesis(s))["1002"]
    assert a["ceiling"] is None
    assert a["remainingDepositCapacity"] is None
    assert a["fillingRatio"] is None
    assert a["authorizedOverdraft"] is None


def test_external_id_is_contract_pfm_id_as_string():
    ids = {a["externalId"] for a in parse_synthesis(synthesis())}
    assert all(isinstance(i, str) for i in ids)
    assert {"1001", "1002"} <= ids


def test_unsupported_family_is_skipped_not_guessed_and_does_not_block_the_rest():
    # A PER or PEA opened later must not stop the sync of the supported accounts.
    s = synthesis()
    contract(s, 1001)["identity"]["productFamilyPFM"]["code"] = "7"
    ids = {a["externalId"] for a in parse_synthesis(s)}
    assert ids == {"1002"}  # its cards are skipped with it


def test_family_3_other_than_livret_a_is_skipped():
    s = synthesis()
    contract(s, 1002)["identity"]["productLabel"] = "LDDS"
    assert "1002" not in {a["externalId"] for a in parse_synthesis(s)}


def test_build_import_reports_unsupported_contracts_by_id_and_family_only():
    s = synthesis()
    contract(s, 1002)["identity"]["productLabel"] = "LDDS"
    inputs = full_inputs()
    del inputs["1002"]
    result = build_import(s, inputs, today=TODAY)
    assert result["unsupported"] == [{"externalId": "1002", "familyCode": "3"}]


def test_unsupported_contract_with_malformed_identification_still_raises():
    s = synthesis()
    item = contract(s, 1002)
    item["identity"]["productLabel"] = "LDDS"
    item["identification"].pop("contractPfmId")
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


def test_transaction_dates_with_a_time_part_are_accepted():
    # Observed live 2026-10-07: date/dueDate come as YYYY-MM-DDTHH:MM:SS.
    pages = copy.deepcopy(tx("courant_page1"))
    row = pages["data"][0]
    day = row["date"]
    row["date"] = day + "T00:00:00"
    row["dueDate"] = row["dueDate"] + "T00:00:00"
    out = parse_transactions([pages])
    assert out[0]["date"] == day
    assert "T" not in out[0]["dueDate"]


def test_transaction_date_with_garbage_after_the_day_still_raises():
    pages = copy.deepcopy(tx("courant_page1"))
    pages["data"][0]["date"] = pages["data"][0]["date"] + "Tnope"
    with pytest.raises(ParseError) as e:
        parse_transactions([pages])
    assert e.value.code == "INVALID_TRANSACTIONS"


def test_null_augmented_cards_means_no_card():
    # Observed live 2026-10-07: the Livret A carries `augmentedCards: null`.
    s = synthesis()
    contract(s, 1002)["identity"]["augmentedCards"] = None
    assert "1002" in {a["externalId"] for a in parse_synthesis(s)}


def test_augmented_cards_of_another_type_still_raises():
    s = synthesis()
    contract(s, 1002)["identity"]["augmentedCards"] = "oops"
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


def test_empty_items_is_a_legitimate_empty_list():
    s = synthesis()
    s["items"] = []
    assert parse_synthesis(s) == []


# ─── Cards ──────────────────────────────────────────────────────────────────


def test_active_card_is_linked_to_its_parent_account():
    c = by_id(parse_synthesis(synthesis()))["2001"]
    assert c["kind"] == "CARD"
    assert c["parentExternalId"] == "1001"
    assert c["nature"] == "DEFERRED_DEBIT"
    assert c["status"] == "Active"
    assert c["iban"] is None


def test_indeterminate_card_xxx_is_never_imported():
    s = synthesis()
    assert "2002" not in by_id(parse_synthesis(s))


def test_any_status_other_than_100_is_skipped():
    s = synthesis()
    card(s, 2001)["cardStatusType"] = {"code": "200", "label": "Bloquée"}
    assert "2001" not in by_id(parse_synthesis(s))


def test_xxx_card_is_skipped_even_if_its_nature_would_qualify():
    s = synthesis()
    c = card(s, 2002)
    c["cardCreditIndicator"] = True
    assert "2002" not in by_id(parse_synthesis(s))


@pytest.mark.parametrize(
    "credit, deferred, nature",
    [
        (True, False, "CREDIT"),
        (False, True, "DEFERRED_DEBIT"),
        (False, False, "IMMEDIATE_DEBIT"),
        (True, True, "CREDIT"),  # credit wins when both indicators are true
    ],
)
def test_card_nature_is_a_property_with_credit_precedence(credit, deferred, nature):
    s = synthesis()
    c = card(s, 2001)
    c["cardCreditIndicator"] = credit
    c["defferedDebitIndicator"] = deferred
    got = by_id(parse_synthesis(s))["2001"]
    assert got["nature"] == nature  # and the card is imported whatever it is


def test_card_indicator_spelling_follows_the_real_api():
    s = synthesis()
    c = card(s, 2001)
    c["deferredDebitIndicator"] = True  # correct English spelling is NOT the API's
    c["defferedDebitIndicator"] = False
    assert by_id(parse_synthesis(s))["2001"]["nature"] == "IMMEDIATE_DEBIT"


def test_card_with_non_boolean_indicator_raises():
    s = synthesis()
    card(s, 2001)["cardCreditIndicator"] = "false"
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


def test_card_without_status_raises():
    s = synthesis()
    del card(s, 2001)["cardStatusType"]
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


# ─── Malformed synthesis ────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "mutate",
    [
        lambda s: s.pop("items"),
        lambda s: s.update(items={"not": "a list"}),
        lambda s: s["items"].append("junk"),
        lambda s: contract(s, 1001)["identity"].pop("balance"),
        lambda s: contract(s, 1001)["identity"]["balance"].update(value="12,5"),
        lambda s: contract(s, 1001)["identity"]["balance"].update(value=True),
        lambda s: contract(s, 1001)["identity"]["balance"].update(currencyCode=None),
        lambda s: contract(s, 1001)["identification"].pop("contractPfmId"),
        lambda s: contract(s, 1001)["identification"].update(contractPfmId=True),
        lambda s: contract(s, 1001)["identity"].pop("productFamilyPFM"),
        lambda s: contract(s, 1001)["identity"].pop("augmentedCards"),
        lambda s: card(s, 2001).pop("cardPfmId"),
        lambda s: contract(s, 1002)["identity"].update(fillingRatio="full"),
    ],
)
def test_malformed_synthesis_raises_parse_error(mutate):
    s = synthesis()
    mutate(s)
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


def test_non_dict_synthesis_raises():
    with pytest.raises(ParseError) as e:
        parse_synthesis([])
    assert e.value.code == "INVALID_SYNTHESIS"


def test_duplicate_contract_id_raises():
    s = synthesis()
    s["items"].append(copy.deepcopy(s["items"][0]))
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert e.value.code == "INVALID_SYNTHESIS"


def test_error_messages_carry_no_payload_values():
    s = synthesis()
    contract(s, 1001)["identity"]["balance"]["value"] = "SECRET-1234,56"
    with pytest.raises(ParseError) as e:
        parse_synthesis(s)
    assert "SECRET" not in str(e.value) and "1234" not in str(e.value)


def test_parse_does_not_mutate_its_input():
    s = synthesis()
    before = copy.deepcopy(s)
    parse_synthesis(s)
    assert s == before


# ─── Transactions ───────────────────────────────────────────────────────────


def test_transaction_mapping_matches_the_contract():
    rows = parse_transactions(tx("courant_page1"), account_external_id="1001")
    assert rows[0] == {
        "externalId": "9001",
        "date": "2026-10-01",
        "dueDate": "2026-10-01",
        "amount": Decimal("-12.5"),
        "currency": "EUR",
        "label": "FAKE ACHAT 1",
        "typeCode": "1",
        "accountExternalId": "1001",
    }


def test_type_code_is_the_transaction_type_code_as_a_string():
    page = tx("courant_page1")
    page["data"][0]["parsedData"]["transactionTypeCode"] = 4
    page["data"][1]["parsedData"]["transactionTypeCode"] = "04"
    rows = parse_transactions(page, account_external_id="1001")
    assert [r["typeCode"] for r in rows] == ["4", "04", "2"]


def test_type_code_is_none_when_absent_and_never_fails():
    page = tx("courant_page1")
    del page["data"][0]["parsedData"]["transactionTypeCode"]
    page["data"][1]["parsedData"] = None
    del page["data"][2]["parsedData"]
    rows = parse_transactions(page, account_external_id="1001")
    assert [r["typeCode"] for r in rows] == [None, None, None]


def test_amount_sign_is_kept_negative_outflow_positive_inflow():
    rows = {
        r["externalId"]: r for r in parse_transactions(tx("courant_page1"))
    }
    assert rows["9002"]["amount"] == Decimal("-30.0")
    assert rows["9003"]["amount"] == Decimal("1500.0")


def test_pages_are_concatenated_in_order():
    rows = parse_transactions([tx("courant_page1"), tx("courant_page2")])
    assert [r["externalId"] for r in rows] == ["9001", "9002", "9003", "9004"]


def test_float_amounts_become_exact_decimals():
    rows = parse_transactions(tx("livret_a"))
    assert rows[1]["amount"] == Decimal("0.42")


def test_empty_data_is_an_empty_list_not_an_error():
    assert parse_transactions(tx("carte_indeterminee")) == []
    assert parse_transactions([]) == []


def test_duplicate_id_inside_an_account_is_deduplicated():
    page1 = tx("courant_page1")
    overlap = tx("courant_page2")
    overlap["data"].append(copy.deepcopy(page1["data"][0]))
    rows = parse_transactions([page1, overlap])
    ids = [r["externalId"] for r in rows]
    assert ids == ["9001", "9002", "9003", "9004"]


def test_same_id_with_different_content_raises():
    page = tx("courant_page1")
    clash = copy.deepcopy(page["data"][0])
    clash["amount"] = -99.0
    page["data"].append(clash)
    with pytest.raises(ParseError) as e:
        parse_transactions(page)
    assert e.value.code == "INVALID_TRANSACTIONS"


@pytest.mark.parametrize(
    "mutate",
    [
        lambda p: p.pop("data"),
        lambda p: p.update(data="nope"),
        lambda p: p["data"].append(42),
        lambda p: p["data"][0].pop("id"),
        lambda p: p["data"][0].update(id=None),
        lambda p: p["data"][0].pop("amount"),
        lambda p: p["data"][0].update(amount="-12,5"),
        lambda p: p["data"][0].update(amount=True),
        lambda p: p["data"][0].update(amount=float("nan")),
        lambda p: p["data"][0].update(date="01/10/2026"),
        lambda p: p["data"][0].update(date=None),
        lambda p: p["data"][0].update(dueDate=None),
        lambda p: p["data"][0].pop("currency"),
        lambda p: p["data"][0].pop("text"),
        lambda p: p["data"][0].pop("accountId"),
    ],
)
def test_malformed_transactions_raise_parse_error(mutate):
    page = tx("courant_page1")
    mutate(page)
    with pytest.raises(ParseError) as e:
        parse_transactions(page)
    assert e.value.code == "INVALID_TRANSACTIONS"


def test_transaction_for_another_account_raises():
    with pytest.raises(ParseError) as e:
        parse_transactions(tx("courant_page1"), account_external_id="1002")
    assert e.value.code == "INVALID_TRANSACTIONS"


def test_transaction_errors_carry_no_payload_values():
    page = tx("courant_page1")
    page["data"][0]["text"] = None
    page["data"][0]["amount"] = "SECRET-AMOUNT"
    with pytest.raises(ParseError) as e:
        parse_transactions(page)
    assert "SECRET" not in str(e.value) and "FAKE ACHAT" not in str(e.value)


# ─── IBAN ───────────────────────────────────────────────────────────────────


def test_iban_comes_from_client_iban_of_the_transactions():
    pages = [with_iban(tx("courant_page1"), IBAN_A), with_iban(tx("courant_page2"), IBAN_A)]
    assert extract_iban(pages) == (IBAN_A, False)


def test_distinct_ibans_are_ambiguous_and_not_guessed():
    page = with_iban(tx("courant_page1"), IBAN_A)
    page["data"][1]["parsedData"]["clientIBAN"] = IBAN_B
    assert extract_iban(page) == (None, True)


def test_no_transactions_means_no_iban():
    assert extract_iban(tx("carte_indeterminee")) == (None, False)
    assert extract_iban([]) == (None, False)


def test_transactions_without_client_iban_mean_no_iban():
    assert extract_iban(tx("courant_page1")) == (None, False)


def test_rows_missing_iban_are_ignored_next_to_rows_that_have_one():
    page = tx("courant_page1")
    page["data"][0]["parsedData"]["clientIBAN"] = IBAN_A
    assert extract_iban(page) == (IBAN_A, False)


def test_non_string_iban_raises():
    page = tx("courant_page1")
    page["data"][0]["parsedData"]["clientIBAN"] = 123
    with pytest.raises(ParseError) as e:
        extract_iban(page)
    assert e.value.code == "INVALID_TRANSACTIONS"


# ─── Card outstanding ───────────────────────────────────────────────────────


def test_only_due_dates_strictly_after_today_count():
    page = card_page(
        (1, -10.0, "2026-10-06"),  # past
        (2, -20.0, "2026-10-07"),  # today: not strictly after
        (3, -30.0, "2026-10-08"),  # future
        (4, -5.5, "2026-11-04"),  # future
    )
    assert card_outstanding(page, today=TODAY) == Decimal("-35.5")


def test_all_past_due_dates_give_zero():
    assert card_outstanding(tx("carte_active"), today=TODAY) == Decimal("0")


def test_empty_card_has_zero_outstanding():
    assert card_outstanding(tx("carte_indeterminee"), today=TODAY) == Decimal("0")


def test_outstanding_sums_across_pages_without_double_counting():
    p1 = card_page((1, -10.0, "2026-10-28"), (2, -2.5, "2026-10-28"))
    p2 = card_page((2, -2.5, "2026-10-28"), (3, -1.0, "2026-11-28"))
    assert card_outstanding([p1, p2], today=TODAY) == Decimal("-13.5")


def test_outstanding_uses_exact_decimals():
    page = card_page((1, -0.1, "2026-10-28"), (2, -0.2, "2026-10-28"))
    assert card_outstanding(page, today=TODAY) == Decimal("-0.3")


def test_outstanding_defaults_today_to_the_system_date():
    far_future = card_page((1, -7.0, "2999-01-01"))
    assert card_outstanding(far_future) == Decimal("-7.0")


# ─── Next due date ──────────────────────────────────────────────────────────


def test_next_due_date_is_the_smallest_due_date_after_today():
    page = card_page(
        (1, -10.0, "2026-10-07"),  # today: not strictly after
        (2, -20.0, "2026-11-04"),
        (3, -30.0, "2026-10-28"),
        (4, -5.0, "2026-10-28"),
    )
    assert next_due_date(page, today=TODAY) == date(2026, 10, 28)


def test_next_due_date_is_none_without_future_operations():
    assert next_due_date(tx("carte_active"), today=TODAY) is None
    assert next_due_date(tx("carte_indeterminee"), today=TODAY) is None


def test_build_import_card_carries_next_due_date():
    inputs = full_inputs()
    inputs["2001"] = [card_page((1, -25.0, "2026-10-02"), (2, -9.9, "2026-10-28"))]
    accounts = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])
    assert accounts["2001"]["nextDueDate"] == date(2026, 10, 28)
    assert "nextDueDate" not in accounts["1001"]


def test_build_import_card_without_future_operations_has_no_next_due_date():
    accounts = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])
    assert accounts["2001"]["nextDueDate"] is None


# ─── build_import ───────────────────────────────────────────────────────────


def full_inputs():
    return {
        "1001": [with_iban(tx("courant_page1"), IBAN_A), with_iban(tx("courant_page2"), IBAN_A)],
        "1002": [with_iban(tx("livret_a"), IBAN_B)],
        "2001": [tx("carte_active")],
    }


def test_build_import_matches_the_expected_import_of_the_fixture():
    result = build_import(synthesis(), full_inputs(), today=TODAY)
    accounts = by_id(result["accounts"])
    expected = FIXTURES["_fixture"]["expected_import"]
    assert len(accounts) == len(expected["import"]) == 3
    assert accounts["1001"]["kind"] == "CURRENT_ACCOUNT"
    assert accounts["1002"]["kind"] == "LIVRET_A"
    assert accounts["2001"]["kind"] == "CARD"
    assert accounts["2001"]["nature"] == expected["import"][2]["nature"]
    assert accounts["2001"]["parentExternalId"] == "1001"
    assert "2002" not in accounts


def test_build_import_attaches_iban_per_account():
    accounts = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])
    assert accounts["1001"]["iban"] == IBAN_A and accounts["1001"]["ibanAmbiguous"] is False
    assert accounts["1002"]["iban"] == IBAN_B
    assert accounts["2001"]["iban"] is None


def test_build_import_flags_ambiguous_iban():
    inputs = full_inputs()
    inputs["1001"][0]["data"][0]["parsedData"]["clientIBAN"] = IBAN_B
    a = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["1001"]
    assert a["iban"] is None and a["ibanAmbiguous"] is True


def test_build_import_account_without_transactions_has_no_iban():
    inputs = full_inputs()
    inputs["1002"] = []
    a = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["1002"]
    assert a["iban"] is None and a["ibanAmbiguous"] is False


def test_build_import_transactions_are_keyed_by_account_and_deduplicated():
    inputs = full_inputs()
    inputs["1001"].append(tx("courant_page1"))  # a re-fetched page
    result = build_import(synthesis(), inputs, today=TODAY)
    assert [r["externalId"] for r in result["transactions"]["1001"]] == [
        "9001", "9002", "9003", "9004",
    ]
    assert [r["externalId"] for r in result["transactions"]["1002"]] == ["9011", "9012"]
    assert {r["accountExternalId"] for r in result["transactions"]["2001"]} == {"2001"}


def test_card_balance_is_its_outstanding_future_due_dates_only():
    inputs = full_inputs()
    inputs["2001"] = [card_page((1, -25.0, "2026-10-02"), (2, -9.9, "2026-10-28"))]
    card_acc = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["2001"]
    assert card_acc["outstanding"] == Decimal("-9.9")
    assert card_acc["balance"] == {"value": Decimal("-9.9"), "currency": "EUR"}


def test_card_with_only_past_due_dates_has_zero_outstanding_in_the_import():
    card_acc = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])["2001"]
    assert card_acc["outstanding"] == Decimal("0")
    assert card_acc["balance"]["value"] == Decimal("0")


def test_missing_card_transactions_raise_rather_than_report_zero():
    inputs = full_inputs()
    del inputs["2001"]
    with pytest.raises(ParseError) as e:
        build_import(synthesis(), inputs, today=TODAY)
    assert e.value.code == "MISSING_TRANSACTIONS"


def test_ignored_card_needs_no_transactions():
    inputs = full_inputs()
    inputs["2002"] = [tx("carte_indeterminee")]  # extra, harmless
    result = build_import(synthesis(), inputs, today=TODAY)
    assert "2002" not in by_id(result["accounts"])
    assert "2002" not in result["transactions"]


def test_malformed_transactions_fail_the_whole_import_no_partial_result():
    inputs = full_inputs()
    inputs["1002"][0]["data"][0]["amount"] = "oops"
    with pytest.raises(ParseError):
        build_import(synthesis(), inputs, today=TODAY)


def test_transactions_filed_under_the_wrong_account_raise():
    inputs = full_inputs()
    inputs["1001"], inputs["1002"] = inputs["1002"], inputs["1001"]
    with pytest.raises(ParseError) as e:
        build_import(synthesis(), inputs, today=TODAY)
    assert e.value.code == "INVALID_TRANSACTIONS"
