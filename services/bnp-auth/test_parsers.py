"""Parsers on invented payloads shaped like the woob `bnp` module's reading of the bank's JSON."""

import unittest
from decimal import Decimal

import parsers

IBAN = "FR7630006000011234567890189"


def account(key, family, product, balance, value="0000 1234 5678"):
    return {"key": key, "value": value, "libelleProduit": product, "libellePersoProduit": "",
            "devise": "EUR", "soldeDispo": balance}


def accounts_payload(*families):
    return {"infoUdc": {"familleCompte": [{"idFamilleCompte": fid, "compte": rows} for fid, rows in families]}}


class EnvelopeTest(unittest.TestCase):
    def test_zero_returns_the_data_and_a_dead_session_is_told_apart(self):
        self.assertEqual(parsers.envelope({"codeRetour": "0", "data": {"a": 1}}), {"a": 1})
        for body in ({"codeRetour": -30}, {"codeRetour": -10, "message": "Client non authentifie"}):
            with self.assertRaises(parsers.SessionExpired):
                parsers.envelope(body)

    def test_anything_else_fails_closed(self):
        for body in ([], {}, {"codeRetour": "x"}, {"codeRetour": 5, "message": "boom"}):
            with self.assertRaises(parsers.ParseError):
                parsers.envelope(body)

    def test_json_floats_stay_exact(self):
        self.assertEqual(parsers.loads('{"a": 1234.10}')["a"], Decimal("1234.10"))
        with self.assertRaises(parsers.ParseError):
            parsers.loads("<html>")


class AccountsTest(unittest.TestCase):
    def test_families_and_labels_become_kinds_and_unknown_ones_are_unsupported(self):
        payload = accounts_payload(
            ("1", [account("k1", "1", "Compte de cheques", 12.3, "1111 2222")]),
            ("2", [account("k2", "2", "Livret A", 100, "3333"), account("k3", "2", "Compte Epargne Logement", 5, "4444"),
                   account("k4", "2", "Epargne mystere", 5, "5555")]),
            ("4", [account("k5", "4", "PEA Bourse", 1, "6666"), account("k6", "4", "Compte titres", 1, "7777")]),
            ("5", [account("k7", "5", "Contrat vie", 9, "8888"), account("k8", "5", "Multiplacements PER", 9, "9999")]),
            ("8", [account("k9", "8", "Credit immobilier", -500, "1010")]),
            ("3", [account("k10", "3", "Depot a terme", 1, "1212")]),
            ("7", [account("k11", "7", "Inconnu", 1, "1313")]),
        )
        rows, unsupported = parsers.parse_accounts(parsers.loads(__import__("json").dumps(payload)), {})
        self.assertEqual([r["kind"] for r in rows], [
            "CURRENT_ACCOUNT", "LIVRET_A", "CEL", "SAVINGS", "PEA", "COMPTE_TITRES", "ASSURANCE_VIE", "PER", "LOAN"])
        self.assertEqual({u["familyCode"] for u in unsupported}, {"3", "7"})
        self.assertTrue(all(len(u["externalId"]) == 20 for u in unsupported))

    def test_the_external_id_does_not_depend_on_the_session_key(self):
        a = parsers.parse_accounts(accounts_payload(("1", [account("k-one", "1", "Compte", 1)])), {})[0][0]
        b = parsers.parse_accounts(accounts_payload(("1", [account("k-two", "1", "Compte", 1)])), {})[0][0]
        self.assertEqual(a["externalId"], b["externalId"])
        self.assertNotIn("k-one", a["externalId"])

    def test_a_closed_loan_and_a_keyless_row_are_skipped_and_an_empty_answer_fails(self):
        payload = accounts_payload(("8", [account("k1", "8", "Credit immobilier", None)]), ("1", [account("", "1", "X", 1)]))
        self.assertEqual(parsers.parse_accounts(payload, {})[0], [])
        with self.assertRaises(parsers.ParseError):
            parsers.parse_accounts({"infoUdc": {}}, {})

    def test_ibans_join_on_the_key_and_a_non_iban_is_dropped(self):
        ibans = parsers.parse_ibans({"listeRib": [
            {"infoCompte": {"ibanCrypte": "k1", "iban": "fr76 3000 6000 0112 3456 7890 189"}},
            {"infoCompte": {"ibanCrypte": "k2", "iban": "nope"}}]})
        self.assertEqual(ibans, {"k1": IBAN})
        row = parsers.parse_accounts(accounts_payload(("1", [account("k1", "1", "Compte", 1)])), ibans)[0][0]
        self.assertEqual(row["iban"], IBAN)


class OperationsTest(unittest.TestCase):
    def payload(self, *ops):
        return {"listerOperations": {"compte": {"operationPassee": list(ops)}}}

    def op(self, label="CARTE SYNTHETIQUE", amount=-12.3, date="05/01/2026", id=None):
        return {"idOperation": id, "libelleOperation": label, "dateOperation": date, "montant": {"montant": amount},
                "operationType": {"codeFamille": "CB"}}

    def test_operations_are_normalized_and_the_deferred_card_summary_is_dropped(self):
        data = parsers.loads(__import__("json").dumps(self.payload(
            self.op(id="o1"), self.op("FACTURE CARTE SELON RELEVE DU 31/01", -50, id="o2"))))
        ops = parsers.parse_operations(data)
        self.assertEqual(len(ops), 1)
        self.assertEqual((ops[0]["externalId"], ops[0]["date"], ops[0]["amount"], ops[0]["kind"]),
                         ("o1", "2026-01-05", "-12.30", "CB"))

    def test_the_dash_date_of_the_live_site_is_read_day_first(self):
        ops = parsers.parse_operations(self.payload(
            self.op(date="05-01-2026", id="100000000000000001"), self.op(date="31-01-2026", id="100000000000000002")))
        self.assertEqual([op["date"] for op in ops], ["2026-01-05", "2026-01-31"])
        self.assertIsNone(parsers.parse_operations(self.payload(self.op(date="31-02-2026"))))

    def test_without_an_operation_id_two_identical_rows_still_get_distinct_stable_ids(self):
        first = parsers.parse_operations(self.payload(self.op(), self.op()))
        second = parsers.parse_operations(self.payload(self.op(), self.op()))
        self.assertEqual(len({o["externalId"] for o in first}), 2)
        self.assertEqual([o["externalId"] for o in first], [o["externalId"] for o in second])

    def test_a_row_that_cannot_be_read_makes_the_whole_history_unusable(self):
        for bad in (self.op(date="31/02/2026"), self.op(amount=None), self.op(label=""), self.op(date="2026/01/05")):
            self.assertIsNone(parsers.parse_operations(self.payload(bad)))
        self.assertIsNone(parsers.parse_operations({}))
        self.assertIsNone(parsers.parse_operations(self.payload(self.op(id="x"), self.op(id="x"))))

    def test_no_past_operation_is_an_empty_history(self):
        self.assertEqual(parsers.parse_operations({"listerOperations": {"compte": {}}}), [])


class SecuritiesTest(unittest.TestCase):
    def line(self, **over):
        base = {"securityCode": "FR0000000001", "securityName": "Action A", "quantityOwned": 10,
                "valorizationValuation": 1000, "profitLossValorisation": 100}
        return {**base, **over}

    def test_positions_are_read_and_a_non_isin_line_makes_the_portfolio_unusable(self):
        lines = parsers.parse_positions({"listofPortfolios": [self.line()]})
        self.assertEqual(lines, [{"isin": "FR0000000001", "label": "Action A", "quantity": "10", "value": "1000.00", "pnl": "100.00"}])
        self.assertIsNone(parsers.parse_positions({"listofPortfolios": [self.line(securityCode="CASH")]}))
        self.assertIsNone(parsers.parse_positions({}))

    def test_matching_needs_exactly_one_securities_account(self):
        row = {"value": "0000 1234 5678"}
        one = [{"number": "XX5678", "value": Decimal(1)}, {"number": "XX1111", "value": Decimal(2)}]
        self.assertEqual(parsers.match_securities(row, one)["number"], "XX5678")
        self.assertIsNone(parsers.match_securities(row, one + [{"number": "YY5678", "value": Decimal(3)}]))
        self.assertIsNone(parsers.match_securities({"value": "12"}, one))


class BuildAccountsTest(unittest.TestCase):
    def row(self, **over):
        base = {"key": "k", "value": "0000 5678", "iban": None, "family": "4", "label": "PEA", "currency": "EUR",
                "balance": Decimal(10), "externalId": "e1", "kind": "PEA", "cash": False}
        return {**base, **over}

    def test_a_pea_uses_the_provider_valuation_its_lines_and_its_cash_row(self):
        rows = [self.row(), self.row(externalId="e2", cash=True, balance=Decimal("500.00"), label="PEA Especes")]
        lines = [{"isin": "FR0000000001", "label": "A", "quantity": "1", "value": "1000.00", "pnl": None}]
        out = parsers.build_accounts(rows, {}, [{"number": "XX5678", "name": "", "value": Decimal("1500.00")}], {"e1": lines})
        self.assertEqual(len(out), 1)
        self.assertEqual((out[0]["balance"], out[0]["cashBalance"], out[0]["snapshotComplete"]), ("1500.00", "500.00", True))

    def test_a_pea_without_its_lines_or_with_an_ambiguous_match_is_incomplete(self):
        synthesis = [{"number": "XX5678", "name": "", "value": Decimal(7)}]
        self.assertFalse(parsers.build_accounts([self.row()], {}, synthesis, {"e1": None})[0]["snapshotComplete"])
        self.assertFalse(parsers.build_accounts([self.row()], {}, [], {})[0]["snapshotComplete"])

    def test_a_cash_account_without_a_readable_history_is_incomplete_and_a_loan_keeps_its_sign(self):
        checking = self.row(kind="CURRENT_ACCOUNT", family="1", externalId="c1", balance=Decimal("12.30"))
        loan = self.row(kind="LOAN", family="8", externalId="l1", balance=Decimal("-500"))
        out = parsers.build_accounts([checking, loan], {"c1": None}, [], {})
        self.assertEqual([(a["kind"], a["balance"], a["snapshotComplete"]) for a in out],
                         [("CURRENT_ACCOUNT", "12.30", False), ("LOAN", "-500.00", True)])
        ok = parsers.build_accounts([checking], {"c1": [{"externalId": "o"}]}, [], {})[0]
        self.assertTrue(ok["snapshotComplete"])
        self.assertEqual(ok["transactions"], [{"externalId": "o"}])

    def test_a_missing_balance_is_never_a_zero_that_looks_real(self):
        self.assertFalse(parsers.build_accounts([self.row(kind="LOAN", balance=None)], {}, [], {})[0]["snapshotComplete"])


if __name__ == "__main__":
    unittest.main()
