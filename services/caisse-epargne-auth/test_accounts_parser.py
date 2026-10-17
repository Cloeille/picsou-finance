"""Parsing rules for the Caisse d'Epargne synthesis and transaction payloads.

Everything runs on `fixtures_synthetic.json`: invented values, real key
structure. The fixture has no `parsedData.clientIBAN` (the real field is only
documented in the findings), so IBAN tests inject an invented one on a copy.
"""

import copy
import json
import unittest
from datetime import date
from decimal import Decimal
from pathlib import Path

from accounts_parser import (
    ParseError,
    _parse_synthesis,
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


class ContractsTest(unittest.TestCase):
    def test_fixture_yields_current_livret_and_active_card_only(self):
        accounts = by_id(parse_synthesis(synthesis()))
        self.assertEqual(set(accounts), {"1001", "1002", "2001"})  # card 2002 (XXX) ignored
        self.assertEqual([accounts[k]["kind"] for k in ("1001", "1002", "2001")], [
            "CURRENT_ACCOUNT",
            "LIVRET_A",
            "CARD",
        ])

    def test_current_account_fields(self):
        a = by_id(parse_synthesis(synthesis()))["1001"]
        self.assertEqual(a["balance"], {"value": Decimal("1234.56"), "currency": "EUR"})
        self.assertEqual(a["authorizedOverdraft"], {"value": Decimal("0.0"), "currency": "EUR"})
        self.assertEqual(a["name"], "FAKE CPT DEPOT PART.")
        self.assertIsNone(a["iban"])
        self.assertIs(a["ibanAmbiguous"], False)
        self.assertIsNone(a["ceiling"])
        self.assertIsNone(a["remainingDepositCapacity"])
        self.assertIsNone(a["fillingRatio"])

    def test_livret_a_savings_fields(self):
        a = by_id(parse_synthesis(synthesis()))["1002"]
        self.assertEqual(a["balance"], {"value": Decimal("5000.0"), "currency": "EUR"})
        self.assertEqual(a["ceiling"], {"value": Decimal("22950.0"), "currency": "EUR"})
        self.assertEqual(a["remainingDepositCapacity"], {
            "value": Decimal("17950.0"),
            "currency": "EUR",
        })
        # Unit of fillingRatio is unverified: passed through, never rescaled.
        self.assertEqual(a["fillingRatio"], Decimal("21.8"))

    def test_savings_fields_tolerate_null(self):
        s = synthesis()
        ident = contract(s, 1002)["identity"]
        ident["authorizedCeilingAmount"] = None
        ident["remainingDepositCapacity"] = None
        ident["fillingRatio"] = None
        ident["authorizedOverdraft"] = None
        a = by_id(parse_synthesis(s))["1002"]
        self.assertIsNone(a["ceiling"])
        self.assertIsNone(a["remainingDepositCapacity"])
        self.assertIsNone(a["fillingRatio"])
        self.assertIsNone(a["authorizedOverdraft"])

    def test_external_id_is_contract_pfm_id_as_string(self):
        ids = {a["externalId"] for a in parse_synthesis(synthesis())}
        self.assertTrue(all(isinstance(i, str) for i in ids))
        self.assertLessEqual({"1001", "1002"}, ids)

    def test_unsupported_family_is_skipped_not_guessed_and_does_not_block_the_rest(self):
        # A PER or PEA opened later must not stop the sync of the supported accounts.
        s = synthesis()
        contract(s, 1001)["identity"]["productFamilyPFM"]["code"] = "7"
        ids = {a["externalId"] for a in parse_synthesis(s)}
        self.assertEqual(ids, {"1002"})  # its cards are skipped with it

    def test_family_3_other_than_livret_a_is_skipped(self):
        s = synthesis()
        contract(s, 1002)["identity"]["subProductFamilyPFM"]["code"] = "99"
        self.assertNotIn("1002", {a["externalId"] for a in parse_synthesis(s)})

    def test_build_import_reports_unsupported_contracts_by_id_and_family_only(self):
        s = synthesis()
        contract(s, 1002)["identity"]["subProductFamilyPFM"]["code"] = "99"
        inputs = full_inputs()
        del inputs["1002"]
        result = build_import(s, inputs, today=TODAY)
        self.assertEqual(result["unsupported"], [{"externalId": "1002", "familyCode": "3"}])

    def test_unsupported_contract_with_malformed_identification_still_raises(self):
        s = synthesis()
        item = contract(s, 1002)
        item["identity"]["subProductFamilyPFM"]["code"] = "99"
        item["identification"].pop("contractPfmId")
        item["identification"].pop("contractId")  # no id left to report it by
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_transaction_dates_with_a_time_part_are_accepted(self):
        # Observed live 2026-10-07: date/dueDate come as YYYY-MM-DDTHH:MM:SS.
        pages = copy.deepcopy(tx("courant_page1"))
        row = pages["data"][0]
        day = row["date"]
        row["date"] = day + "T00:00:00"
        row["dueDate"] = row["dueDate"] + "T00:00:00"
        out = parse_transactions([pages])
        self.assertEqual(out[0]["date"], day)
        self.assertNotIn("T", out[0]["dueDate"])

    def test_transaction_date_with_garbage_after_the_day_still_raises(self):
        pages = copy.deepcopy(tx("courant_page1"))
        pages["data"][0]["date"] = pages["data"][0]["date"] + "Tnope"
        with self.assertRaises(ParseError) as e:
            parse_transactions([pages])
        self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")

    def test_null_augmented_cards_means_no_card(self):
        # Observed live 2026-10-07: the Livret A carries `augmentedCards: null`.
        s = synthesis()
        contract(s, 1002)["identity"]["augmentedCards"] = None
        self.assertIn("1002", {a["externalId"] for a in parse_synthesis(s)})

    def test_augmented_cards_of_another_type_still_raises(self):
        s = synthesis()
        contract(s, 1002)["identity"]["augmentedCards"] = "oops"
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_empty_items_is_a_legitimate_empty_list(self):
        s = synthesis()
        s["items"] = []
        self.assertEqual(parse_synthesis(s), [])


# ─── Cards ──────────────────────────────────────────────────────────────────


class CardsTest(unittest.TestCase):
    def test_active_card_is_linked_to_its_parent_account(self):
        c = by_id(parse_synthesis(synthesis()))["2001"]
        self.assertEqual(c["kind"], "CARD")
        self.assertEqual(c["parentExternalId"], "1001")
        self.assertEqual(c["nature"], "DEFERRED_DEBIT")
        self.assertEqual(c["status"], "Active")
        self.assertIsNone(c["iban"])

    def test_indeterminate_card_xxx_is_never_imported(self):
        s = synthesis()
        self.assertNotIn("2002", by_id(parse_synthesis(s)))

    def test_any_status_other_than_100_is_skipped(self):
        s = synthesis()
        card(s, 2001)["cardStatusType"] = {"code": "200", "label": "Bloquée"}
        self.assertNotIn("2001", by_id(parse_synthesis(s)))

    def test_xxx_card_is_skipped_even_if_its_nature_would_qualify(self):
        s = synthesis()
        c = card(s, 2002)
        c["cardCreditIndicator"] = True
        self.assertNotIn("2002", by_id(parse_synthesis(s)))

    def test_card_nature_is_a_property_with_credit_precedence(self):
        for credit, deferred, nature in [
            (True, False, "CREDIT"),
            (False, True, "DEFERRED_DEBIT"),
            (False, False, "IMMEDIATE_DEBIT"),
            (True, True, "CREDIT"),  # credit wins when both indicators are true
        ]:
            with self.subTest(credit=credit, deferred=deferred, nature=nature):
                s = synthesis()
                c = card(s, 2001)
                c["cardCreditIndicator"] = credit
                c["defferedDebitIndicator"] = deferred
                got = by_id(parse_synthesis(s))["2001"]
                self.assertEqual(got["nature"], nature)  # and the card is imported whatever it is

    def test_card_indicator_spelling_follows_the_real_api(self):
        s = synthesis()
        c = card(s, 2001)
        c["deferredDebitIndicator"] = True  # correct English spelling is NOT the API's
        c["defferedDebitIndicator"] = False
        self.assertEqual(by_id(parse_synthesis(s))["2001"]["nature"], "IMMEDIATE_DEBIT")

    def test_card_with_non_boolean_indicator_raises(self):
        s = synthesis()
        card(s, 2001)["cardCreditIndicator"] = "false"
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_card_without_status_raises(self):
        s = synthesis()
        del card(s, 2001)["cardStatusType"]
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")


# ─── Malformed synthesis ────────────────────────────────────────────────────


class MalformedSynthesisTest(unittest.TestCase):
    def test_malformed_synthesis_raises_parse_error(self):
        for mutate in [
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
        ]:
            with self.subTest(mutate=mutate):
                s = synthesis()
                mutate(s)
                with self.assertRaises(ParseError) as e:
                    parse_synthesis(s)
                self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_non_dict_synthesis_raises(self):
        with self.assertRaises(ParseError) as e:
            parse_synthesis([])
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_duplicate_contract_id_raises(self):
        s = synthesis()
        s["items"].append(copy.deepcopy(s["items"][0]))
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_error_messages_carry_no_payload_values(self):
        s = synthesis()
        contract(s, 1001)["identity"]["balance"]["value"] = "SECRET-1234,56"
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertNotIn("SECRET", str(e.exception))
        self.assertNotIn("1234", str(e.exception))

    def test_parse_does_not_mutate_its_input(self):
        s = synthesis()
        before = copy.deepcopy(s)
        parse_synthesis(s)
        self.assertEqual(s, before)


# ─── Product families: LDDS, PER, loan (six-contract synthesis) ─────────────


def products():
    return copy.deepcopy(FIXTURES["synthesis_products"])


def family(s, code, sub=None):
    return next(
        i
        for i in s["items"]
        if i["identity"]["productFamilyPFM"]["code"] == code
        and (sub is None or (i["identity"]["subProductFamilyPFM"] or {}).get("code") == sub)
    )


PER_ID = "contract:00000     FAKE00000014"
LOAN_ID = "contract:00000000000015"


class ProductFamiliesTest(unittest.TestCase):
    def test_six_contracts_give_five_accounts_and_one_unsupported_without_error(self):
        # PER, loan and insurance carry a null contractPfmId: none of them may
        # fail the sync of the others.
        accounts, unsupported = _parse_synthesis(products())
        kinds = {a["externalId"]: a["kind"] for a in accounts if a["kind"] != "CARD"}
        self.assertEqual(kinds, {
            "1001": "CURRENT_ACCOUNT",
            "1002": "LIVRET_A",
            "1003": "LDDS",
            PER_ID: "PER",
            LOAN_ID: "LOAN",
        })
        self.assertEqual(unsupported, [{"externalId": "contract:0000000016", "familyCode": "5"}])

    def test_build_import_of_six_contracts_needs_transactions_for_imported_ones_only(self):
        # The current account keeps its active card (2001) from the base fixture.
        inputs = {k: [] for k in ("1001", "2001", "1002", "1003", PER_ID, LOAN_ID)}
        result = build_import(products(), inputs, today=TODAY)
        self.assertEqual(len([a for a in result["accounts"] if a["kind"] != "CARD"]), 5)
        self.assertEqual(result["unsupported"], [{"externalId": "contract:0000000016", "familyCode": "5"}])

    def test_build_import_never_extracts_an_iban_for_per_or_loan(self):
        # Even if pages were handed over, a PER or a loan has no PFM IBAN.
        inputs = {k: [] for k in ("1001", "2001", "1002", "1003")}
        inputs[PER_ID] = [tx("livret_a")]
        inputs[LOAN_ID] = [tx("livret_a")]
        accounts = {a["externalId"]: a for a in build_import(products(), inputs, today=TODAY)["accounts"]}
        for ext in (PER_ID, LOAN_ID):
            self.assertIsNone(accounts[ext]["iban"])
            self.assertIs(accounts[ext]["ibanAmbiguous"], False)

    def test_build_import_ignores_pages_handed_over_for_per_or_loan(self):
        inputs = {k: [] for k in ("1001", "2001", "1002", "1003")}
        inputs[PER_ID] = [tx("livret_a")]
        inputs[LOAN_ID] = [tx("livret_a")]
        result = build_import(products(), inputs, today=TODAY)
        self.assertEqual(result["transactions"][PER_ID], [])
        self.assertEqual(result["transactions"][LOAN_ID], [])

    def test_sub_family_code_decides_the_savings_kind_over_the_label(self):
        s = products()
        family(s, "3", "01")["identity"]["productLabel"] = "SOMETHING ELSE"
        family(s, "3", "28")["identity"]["productLabel"] = "LIVRET A"
        kinds = {a["externalId"]: a["kind"] for a in parse_synthesis(s)}
        self.assertEqual(kinds["1002"], "LIVRET_A")
        self.assertEqual(kinds["1003"], "LDDS")

    def test_livret_a_label_still_counts_when_the_sub_family_is_absent(self):
        s = synthesis()
        contract(s, 1002)["identity"]["subProductFamilyPFM"] = None
        self.assertEqual(by_id(parse_synthesis(s))["1002"]["kind"], "LIVRET_A")

    def test_other_family_and_sub_family_pairs_are_unsupported_never_fatal(self):
        for fam, sub in [("3", "99"), ("4", "99"), ("6", "99"), ("5", "99"), ("7", "01"), ("2", "05")]:
            with self.subTest(fam=fam, sub=sub):
                s = products()
                item = family(s, "6", "12")
                item["identity"]["productFamilyPFM"]["code"] = fam
                item["identity"]["subProductFamilyPFM"]["code"] = sub
                accounts, unsupported = _parse_synthesis(s)
                self.assertNotIn(LOAN_ID, {a["externalId"] for a in accounts})
                self.assertIn({"externalId": LOAN_ID, "familyCode": fam}, unsupported)

    def test_ldds_carries_the_savings_fields(self):
        a = by_id(parse_synthesis(products()))["1003"]
        self.assertEqual(a["balance"], {"value": Decimal("1200.0"), "currency": "EUR"})
        self.assertEqual(a["ceiling"], {"value": Decimal("22950.0"), "currency": "EUR"})

    def test_per_and_loan_ids_are_the_stripped_contract_id(self):
        s = products()
        accounts = by_id(parse_synthesis(s))
        self.assertEqual(accounts[PER_ID]["kind"], "PER")
        self.assertEqual(accounts[LOAN_ID]["kind"], "LOAN")  # leading/trailing blanks stripped
        self.assertIsNone(accounts[PER_ID]["iban"])
        self.assertIsNone(accounts[LOAN_ID]["iban"])

    def test_per_and_loan_tolerate_an_integer_pfm_id_but_keep_the_contract_id(self):
        s = products()
        family(s, "4", "05")["identification"]["contractPfmId"] = 4004
        family(s, "6", "12")["identification"].pop("contractPfmId")
        ids = {a["externalId"] for a in parse_synthesis(s)}
        self.assertLessEqual({PER_ID, LOAN_ID}, ids)
        self.assertNotIn("4004", ids)

    def test_loan_balance_is_kept_negative(self):
        a = by_id(parse_synthesis(products()))[LOAN_ID]
        self.assertEqual(a["balance"], {"value": Decimal("-9876.54"), "currency": "EUR"})

    def test_zero_loan_and_zero_per_balances_are_accepted(self):
        s = products()
        family(s, "6", "12")["identity"]["balance"]["value"] = 0
        family(s, "4", "05")["identity"]["balance"]["value"] = 0.0
        accounts = by_id(parse_synthesis(s))
        self.assertEqual(accounts[LOAN_ID]["balance"]["value"], 0)
        self.assertEqual(accounts[PER_ID]["balance"]["value"], 0)

    def test_malformed_product_contracts_raise_parse_error(self):
        for mutate in [
            # A positive loan or a negative PER is a format change, not a value to keep.
            lambda s: family(s, "6", "12")["identity"]["balance"].update(value=0.01),
            lambda s: family(s, "4", "05")["identity"]["balance"].update(value=-0.01),
            lambda s: family(s, "6", "12")["identity"].update(balance=None),
            lambda s: family(s, "4", "05")["identification"]["contractId"].update(id="   "),
            lambda s: family(s, "4", "05")["identification"]["contractId"].pop("id"),
            lambda s: family(s, "6", "12")["identification"].pop("contractId"),
            lambda s: family(s, "6", "12")["identification"].update(contractPfmId="7"),
            lambda s: family(s, "3", "28")["identification"].update(contractPfmId=None),
            lambda s: family(s, "3", "28")["identity"].update(subProductFamilyPFM="28"),
            lambda s: family(s, "3", "28")["identity"]["subProductFamilyPFM"].update(code=28),
            lambda s: family(s, "5")["identification"]["contractId"].pop("id"),
        ]:
            with self.subTest(mutate=mutate):
                s = products()
                mutate(s)
                with self.assertRaises(ParseError) as e:
                    parse_synthesis(s)
                self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")

    def test_unsupported_contract_uses_its_integer_pfm_id_when_it_has_one(self):
        s = products()
        family(s, "5")["identification"]["contractPfmId"] = 5005
        self.assertEqual(_parse_synthesis(s)[1], [{"externalId": "5005", "familyCode": "5"}])

    def test_contract_ids_never_reach_error_messages(self):
        s = products()
        family(s, "6", "12")["identity"]["balance"]["value"] = 1.0
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertNotIn("00000000000015", str(e.exception))
        self.assertNotIn("1.0", str(e.exception))

    def test_duplicate_contract_id_between_per_entries_raises(self):
        s = products()
        s["items"].append(copy.deepcopy(family(s, "4", "05")))
        with self.assertRaises(ParseError) as e:
            parse_synthesis(s)
        self.assertEqual(e.exception.code, "INVALID_SYNTHESIS")


# ─── Transactions ───────────────────────────────────────────────────────────


class TransactionsTest(unittest.TestCase):
    def test_transaction_mapping_matches_the_contract(self):
        rows = parse_transactions(tx("courant_page1"), account_external_id="1001")
        self.assertEqual(rows[0], {
            "externalId": "9001",
            "date": "2026-10-01",
            "dueDate": "2026-10-01",
            "amount": Decimal("-12.5"),
            "currency": "EUR",
            "label": "FAKE ACHAT 1",
            "typeCode": "1",
            "accountExternalId": "1001",
        })

    def test_type_code_is_the_transaction_type_code_as_a_string(self):
        page = tx("courant_page1")
        page["data"][0]["parsedData"]["transactionTypeCode"] = 4
        page["data"][1]["parsedData"]["transactionTypeCode"] = "04"
        rows = parse_transactions(page, account_external_id="1001")
        self.assertEqual([r["typeCode"] for r in rows], ["4", "04", "2"])

    def test_type_code_is_none_when_absent_and_never_fails(self):
        page = tx("courant_page1")
        del page["data"][0]["parsedData"]["transactionTypeCode"]
        page["data"][1]["parsedData"] = None
        del page["data"][2]["parsedData"]
        rows = parse_transactions(page, account_external_id="1001")
        self.assertEqual([r["typeCode"] for r in rows], [None, None, None])

    def test_amount_sign_is_kept_negative_outflow_positive_inflow(self):
        rows = {
            r["externalId"]: r for r in parse_transactions(tx("courant_page1"))
        }
        self.assertEqual(rows["9002"]["amount"], Decimal("-30.0"))
        self.assertEqual(rows["9003"]["amount"], Decimal("1500.0"))

    def test_pages_are_concatenated_in_order(self):
        rows = parse_transactions([tx("courant_page1"), tx("courant_page2")])
        self.assertEqual([r["externalId"] for r in rows], ["9001", "9002", "9003", "9004"])

    def test_float_amounts_become_exact_decimals(self):
        rows = parse_transactions(tx("livret_a"))
        self.assertEqual(rows[1]["amount"], Decimal("0.42"))

    def test_empty_data_is_an_empty_list_not_an_error(self):
        self.assertEqual(parse_transactions(tx("carte_indeterminee")), [])
        self.assertEqual(parse_transactions([]), [])

    def test_duplicate_id_inside_an_account_is_deduplicated(self):
        page1 = tx("courant_page1")
        overlap = tx("courant_page2")
        overlap["data"].append(copy.deepcopy(page1["data"][0]))
        rows = parse_transactions([page1, overlap])
        ids = [r["externalId"] for r in rows]
        self.assertEqual(ids, ["9001", "9002", "9003", "9004"])

    def test_same_id_with_different_content_raises(self):
        page = tx("courant_page1")
        clash = copy.deepcopy(page["data"][0])
        clash["amount"] = -99.0
        page["data"].append(clash)
        with self.assertRaises(ParseError) as e:
            parse_transactions(page)
        self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")

    def test_malformed_transactions_raise_parse_error(self):
        for mutate in [
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
        ]:
            with self.subTest(mutate=mutate):
                page = tx("courant_page1")
                mutate(page)
                with self.assertRaises(ParseError) as e:
                    parse_transactions(page)
                self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")

    def test_transaction_for_another_account_raises(self):
        with self.assertRaises(ParseError) as e:
            parse_transactions(tx("courant_page1"), account_external_id="1002")
        self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")

    def test_transaction_errors_carry_no_payload_values(self):
        page = tx("courant_page1")
        page["data"][0]["text"] = None
        page["data"][0]["amount"] = "SECRET-AMOUNT"
        with self.assertRaises(ParseError) as e:
            parse_transactions(page)
        self.assertNotIn("SECRET", str(e.exception))
        self.assertNotIn("FAKE ACHAT", str(e.exception))


# ─── IBAN ───────────────────────────────────────────────────────────────────


class IbanTest(unittest.TestCase):
    def test_iban_comes_from_client_iban_of_the_transactions(self):
        pages = [with_iban(tx("courant_page1"), IBAN_A), with_iban(tx("courant_page2"), IBAN_A)]
        self.assertEqual(extract_iban(pages), (IBAN_A, False))

    def test_distinct_ibans_are_ambiguous_and_not_guessed(self):
        page = with_iban(tx("courant_page1"), IBAN_A)
        page["data"][1]["parsedData"]["clientIBAN"] = IBAN_B
        self.assertEqual(extract_iban(page), (None, True))

    def test_no_transactions_means_no_iban(self):
        self.assertEqual(extract_iban(tx("carte_indeterminee")), (None, False))
        self.assertEqual(extract_iban([]), (None, False))

    def test_transactions_without_client_iban_mean_no_iban(self):
        self.assertEqual(extract_iban(tx("courant_page1")), (None, False))

    def test_rows_missing_iban_are_ignored_next_to_rows_that_have_one(self):
        page = tx("courant_page1")
        page["data"][0]["parsedData"]["clientIBAN"] = IBAN_A
        self.assertEqual(extract_iban(page), (IBAN_A, False))

    def test_non_string_iban_raises(self):
        page = tx("courant_page1")
        page["data"][0]["parsedData"]["clientIBAN"] = 123
        with self.assertRaises(ParseError) as e:
            extract_iban(page)
        self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")


# ─── Card outstanding ───────────────────────────────────────────────────────


class CardOutstandingTest(unittest.TestCase):
    def test_only_due_dates_strictly_after_today_count(self):
        page = card_page(
            (1, -10.0, "2026-10-06"),  # past
            (2, -20.0, "2026-10-07"),  # today: not strictly after
            (3, -30.0, "2026-10-08"),  # future
            (4, -5.5, "2026-11-04"),  # future
        )
        self.assertEqual(card_outstanding(page, today=TODAY), Decimal("-35.5"))

    def test_all_past_due_dates_give_zero(self):
        self.assertEqual(card_outstanding(tx("carte_active"), today=TODAY), Decimal("0"))

    def test_empty_card_has_zero_outstanding(self):
        self.assertEqual(card_outstanding(tx("carte_indeterminee"), today=TODAY), Decimal("0"))

    def test_outstanding_sums_across_pages_without_double_counting(self):
        p1 = card_page((1, -10.0, "2026-10-28"), (2, -2.5, "2026-10-28"))
        p2 = card_page((2, -2.5, "2026-10-28"), (3, -1.0, "2026-11-28"))
        self.assertEqual(card_outstanding([p1, p2], today=TODAY), Decimal("-13.5"))

    def test_outstanding_uses_exact_decimals(self):
        page = card_page((1, -0.1, "2026-10-28"), (2, -0.2, "2026-10-28"))
        self.assertEqual(card_outstanding(page, today=TODAY), Decimal("-0.3"))

    def test_outstanding_defaults_today_to_the_system_date(self):
        far_future = card_page((1, -7.0, "2999-01-01"))
        self.assertEqual(card_outstanding(far_future), Decimal("-7.0"))


# ─── Next due date ──────────────────────────────────────────────────────────


class NextDueDateTest(unittest.TestCase):
    def test_next_due_date_is_the_smallest_due_date_after_today(self):
        page = card_page(
            (1, -10.0, "2026-10-07"),  # today: not strictly after
            (2, -20.0, "2026-11-04"),
            (3, -30.0, "2026-10-28"),
            (4, -5.0, "2026-10-28"),
        )
        self.assertEqual(next_due_date(page, today=TODAY), date(2026, 10, 28))

    def test_next_due_date_is_none_without_future_operations(self):
        self.assertIsNone(next_due_date(tx("carte_active"), today=TODAY))
        self.assertIsNone(next_due_date(tx("carte_indeterminee"), today=TODAY))

    def test_build_import_card_carries_next_due_date(self):
        inputs = full_inputs()
        inputs["2001"] = [card_page((1, -25.0, "2026-10-02"), (2, -9.9, "2026-10-28"))]
        accounts = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])
        self.assertEqual(accounts["2001"]["nextDueDate"], date(2026, 10, 28))
        self.assertNotIn("nextDueDate", accounts["1001"])

    def test_build_import_card_without_future_operations_has_no_next_due_date(self):
        accounts = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])
        self.assertIsNone(accounts["2001"]["nextDueDate"])


# ─── build_import ───────────────────────────────────────────────────────────


def full_inputs():
    return {
        "1001": [with_iban(tx("courant_page1"), IBAN_A), with_iban(tx("courant_page2"), IBAN_A)],
        "1002": [with_iban(tx("livret_a"), IBAN_B)],
        "2001": [tx("carte_active")],
    }


class BuildImportTest(unittest.TestCase):
    def test_build_import_matches_the_expected_import_of_the_fixture(self):
        result = build_import(synthesis(), full_inputs(), today=TODAY)
        accounts = by_id(result["accounts"])
        expected = FIXTURES["_fixture"]["expected_import"]
        self.assertEqual(len(accounts), len(expected["import"]))
        self.assertEqual(len(expected["import"]), 3)
        self.assertEqual(accounts["1001"]["kind"], "CURRENT_ACCOUNT")
        self.assertEqual(accounts["1002"]["kind"], "LIVRET_A")
        self.assertEqual(accounts["2001"]["kind"], "CARD")
        self.assertEqual(accounts["2001"]["nature"], expected["import"][2]["nature"])
        self.assertEqual(accounts["2001"]["parentExternalId"], "1001")
        self.assertNotIn("2002", accounts)

    def test_build_import_attaches_iban_per_account(self):
        accounts = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])
        self.assertEqual(accounts["1001"]["iban"], IBAN_A)
        self.assertIs(accounts["1001"]["ibanAmbiguous"], False)
        self.assertEqual(accounts["1002"]["iban"], IBAN_B)
        self.assertIsNone(accounts["2001"]["iban"])

    def test_build_import_flags_ambiguous_iban(self):
        inputs = full_inputs()
        inputs["1001"][0]["data"][0]["parsedData"]["clientIBAN"] = IBAN_B
        a = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["1001"]
        self.assertIsNone(a["iban"])
        self.assertIs(a["ibanAmbiguous"], True)

    def test_build_import_account_without_transactions_has_no_iban(self):
        inputs = full_inputs()
        inputs["1002"] = []
        a = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["1002"]
        self.assertIsNone(a["iban"])
        self.assertIs(a["ibanAmbiguous"], False)

    def test_build_import_transactions_are_keyed_by_account_and_deduplicated(self):
        inputs = full_inputs()
        inputs["1001"].append(tx("courant_page1"))  # a re-fetched page
        result = build_import(synthesis(), inputs, today=TODAY)
        self.assertEqual([r["externalId"] for r in result["transactions"]["1001"]], [
            "9001", "9002", "9003", "9004",
        ])
        self.assertEqual([r["externalId"] for r in result["transactions"]["1002"]], ["9011", "9012"])
        self.assertEqual({r["accountExternalId"] for r in result["transactions"]["2001"]}, {"2001"})

    def test_card_balance_is_its_outstanding_future_due_dates_only(self):
        inputs = full_inputs()
        inputs["2001"] = [card_page((1, -25.0, "2026-10-02"), (2, -9.9, "2026-10-28"))]
        card_acc = by_id(build_import(synthesis(), inputs, today=TODAY)["accounts"])["2001"]
        self.assertEqual(card_acc["outstanding"], Decimal("-9.9"))
        self.assertEqual(card_acc["balance"], {"value": Decimal("-9.9"), "currency": "EUR"})

    def test_card_with_only_past_due_dates_has_zero_outstanding_in_the_import(self):
        card_acc = by_id(build_import(synthesis(), full_inputs(), today=TODAY)["accounts"])["2001"]
        self.assertEqual(card_acc["outstanding"], Decimal("0"))
        self.assertEqual(card_acc["balance"]["value"], Decimal("0"))

    def test_missing_card_transactions_raise_rather_than_report_zero(self):
        inputs = full_inputs()
        del inputs["2001"]
        with self.assertRaises(ParseError) as e:
            build_import(synthesis(), inputs, today=TODAY)
        self.assertEqual(e.exception.code, "MISSING_TRANSACTIONS")

    def test_ignored_card_needs_no_transactions(self):
        inputs = full_inputs()
        inputs["2002"] = [tx("carte_indeterminee")]  # extra, harmless
        result = build_import(synthesis(), inputs, today=TODAY)
        self.assertNotIn("2002", by_id(result["accounts"]))
        self.assertNotIn("2002", result["transactions"])

    def test_malformed_transactions_fail_the_whole_import_no_partial_result(self):
        inputs = full_inputs()
        inputs["1002"][0]["data"][0]["amount"] = "oops"
        with self.assertRaises(ParseError):
            build_import(synthesis(), inputs, today=TODAY)

    def test_transactions_filed_under_the_wrong_account_raise(self):
        inputs = full_inputs()
        inputs["1001"], inputs["1002"] = inputs["1002"], inputs["1001"]
        with self.assertRaises(ParseError) as e:
            build_import(synthesis(), inputs, today=TODAY)
        self.assertEqual(e.exception.code, "INVALID_TRANSACTIONS")


if __name__ == "__main__":
    unittest.main()
