"""Synthetic tests for the pure PER detail parser."""

import copy
import unittest
from datetime import date
from decimal import Decimal

from accounts_parser import ParseError
from per_parser import parse_per_detail


ISIN_A = "AA0000000001"


def epoch_ms(day):
    return (date.fromisoformat(day) - date(1970, 1, 1)).days * 86_400_000


def uc_line(**overrides):
    line = {
        "nom": "Synthetic growth fund",
        "nombreUnitesCompte": 1.5,
        "montant": 150.0,
        "valeurUniteCompte": 100.0,
        "codeIsin": ISIN_A,
        "repartition": 88.0,
        "dateValeurUniteCompte": epoch_ms("2026-10-10"),
        "idCompartment": 7,
        "familleFondsGeneraux": False,
        "capitalGainView": {"referenceValueOrigin": 999.0},
    }
    line.update(overrides)
    return line


def euro_line(amount=20.0):
    return {"nom": "Synthetic euro fund", "montant": amount, "familleFondsGeneraux": True}


def per_detail(*supports, total=170.0, uc_total=150.0, euro_total=20.0):
    return {
        "detailContratVie": {
            "valorisation": {
                "montant": total,
                "date": epoch_ms("2026-10-12"),
                "supports": list(supports),
                "globalInvestCalcRest": {
                    "globalAmountUnitesCompte": uc_total,
                    "globalAmountFondEuros": euro_total,
                },
            },
            "supportsWithoutValorization": [],
        },
        # The caller supplies currency explicitly; this unrelated shape is ignored.
        "devise": {"unexpected": ["do not infer currency"]},
        "detailProduit": {"supports": [{"codeIsin": "ZZ0000000009"}]},
        "versementProgramme": {"supports": [{"codeIsin": "YY0000000008"}]},
    }


class PerParserTest(unittest.TestCase):
    def test_nav_date_cannot_be_after_the_contract_valuation_date(self):
        with self.assertRaisesRegex(ParseError, "dateValeurUniteCompte"):
            parse_per_detail(per_detail(uc_line(dateValeurUniteCompte=epoch_ms("2026-10-13")), euro_line()))

    def test_negative_compartment_is_not_a_valid_owned_support_identity(self):
        with self.assertRaisesRegex(ParseError, "idCompartment"):
            parse_per_detail(per_detail(uc_line(idCompartment=-1), euro_line()))

    def test_parse_mixed_valuation_returns_owned_uc_position_and_separate_euro_pocket(self):
        source = per_detail(uc_line(), euro_line())
        before = copy.deepcopy(source)

        result = parse_per_detail(source, currency="EUR")

        self.assertEqual(result, {
            "balance": Decimal("170.0"),
            "euroFundBalance": Decimal("20.0"),
            "valuationDate": "2026-10-12",
            "positions": [
                {
                    "isin": ISIN_A,
                    "name": "Synthetic growth fund",
                    "quantity": Decimal("1.5"),
                    "unitPrice": Decimal("100.0"),
                    "value": Decimal("150.0"),
                    "priceDate": "2026-10-10",
                    "compartmentId": 7,
                }
            ],
        })
        self.assertEqual(result["balance"], result["euroFundBalance"] + sum(
            (position["value"] for position in result["positions"]), Decimal("0")
        ))
        self.assertEqual(source, before)

    def test_non_eur_currency_is_refused_instead_of_inferred_from_payload(self):
        with self.assertRaises(ParseError) as error:
            parse_per_detail(per_detail(uc_line(), euro_line()), currency="USD")

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_uc_line_value_must_reconcile_to_quantity_times_nav(self):
        detail = per_detail(uc_line(montant=149.0), euro_line(), total=169.0, uc_total=149.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_all_euro_fund_balance_is_preserved_without_uc_positions(self):
        result = parse_per_detail(per_detail(euro_line(42.0), total=42.0, uc_total=0.0, euro_total=42.0))

        self.assertEqual(result["balance"], Decimal("42.0"))
        self.assertEqual(result["euroFundBalance"], Decimal("42.0"))
        self.assertEqual(result["positions"], [])

    def test_aggregate_only_euro_pocket_is_valid_when_total_reconciles(self):
        result = parse_per_detail(per_detail(total=42.0, uc_total=0.0, euro_total=42.0))

        self.assertEqual(result["balance"], result["euroFundBalance"])
        self.assertEqual(result["euroFundBalance"], Decimal("42.0"))
        self.assertEqual(result["positions"], [])

    def test_zero_total_with_empty_supports_is_valid(self):
        result = parse_per_detail(per_detail(total=0.0, uc_total=0.0, euro_total=0.0))

        self.assertEqual(result["balance"], result["euroFundBalance"])
        self.assertEqual(result["euroFundBalance"], Decimal("0.0"))
        self.assertEqual(result["positions"], [])

    def test_zero_only_uc_line_is_omitted_without_position_metadata(self):
        line = {"familleFondsGeneraux": False, "nombreUnitesCompte": 0.0, "montant": 0.0}
        result = parse_per_detail(per_detail(line, total=20.0, uc_total=0.0))

        self.assertEqual(result["positions"], [])

    def test_zero_quantity_cannot_hide_a_nonzero_uc_value(self):
        line = {"familleFondsGeneraux": False, "nombreUnitesCompte": 0.0, "montant": 12.0}
        detail = per_detail(line, total=32.0, uc_total=12.0, euro_total=20.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_optional_uc_summary_must_reconcile_when_present(self):
        detail = per_detail(uc_line(), euro_line())
        detail["detailContratVie"]["valorisation"]["globalInvestCalcRest"]["globalAmountUnitesCompte"] = 149.0

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_uc_classification_must_be_an_explicit_boolean(self):
        detail = per_detail(uc_line(familleFondsGeneraux=0), euro_line())

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_missing_support_classification_is_not_inferred_from_other_fields(self):
        line = uc_line()
        del line["familleFondsGeneraux"]

        with self.assertRaises(ParseError) as error:
            parse_per_detail(per_detail(line, euro_line()))

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_positive_uc_quantity_requires_all_position_fields(self):
        for field in [
            "nom",
            "nombreUnitesCompte",
            "montant",
            "valeurUniteCompte",
            "codeIsin",
            "dateValeurUniteCompte",
            "idCompartment",
        ]:
            with self.subTest(field=field):
                line = uc_line()
                del line[field]

                with self.assertRaises(ParseError) as error:
                    parse_per_detail(per_detail(line, euro_line()))

                self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_positive_uc_isin_must_match_strict_uppercase_source_format(self):
        for isin in ["aa0000000001", "AA000000000X", "AA00000000-1", "A00000000001"]:
            with self.subTest(isin=isin):
                with self.assertRaises(ParseError) as error:
                    parse_per_detail(per_detail(uc_line(codeIsin=isin), euro_line()))

                self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_positive_uc_nav_must_be_positive(self):
        detail = per_detail(uc_line(valeurUniteCompte=0.0, montant=0.0), total=20.0, uc_total=0.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_epoch_millisecond_date_must_not_be_fractional(self):
        line = uc_line(dateValeurUniteCompte=epoch_ms("2026-10-10") + 0.5)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(per_detail(line, euro_line()))

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_negative_uc_quantities_and_values_are_rejected_even_if_they_reconcile(self):
        line = uc_line(nombreUnitesCompte=-1.0, montant=-100.0)
        detail = per_detail(line, total=-100.0, uc_total=-100.0, euro_total=0.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_negative_euro_values_and_pocket_are_rejected_even_if_they_reconcile(self):
        detail = per_detail(euro_line(-1.0), total=-1.0, uc_total=0.0, euro_total=-1.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_duplicate_isin_and_compartment_identity_is_rejected(self):
        first = uc_line(nombreUnitesCompte=0.5, montant=50.0)
        second = uc_line(nombreUnitesCompte=1.0, montant=100.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(per_detail(first, second, euro_line()))

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_malformed_boolean_or_nonfinite_amounts_are_rejected_without_echoing_values(self):
        for path, value in [
            (("detailContratVie", "valorisation", "montant"), float("nan")),
            (("detailContratVie", "valorisation", "supports", 0, "montant"), "SYNTHETIC-BAD-AMOUNT"),
            (("detailContratVie", "valorisation", "supports", 0, "nombreUnitesCompte"), float("inf")),
            (("detailContratVie", "valorisation", "supports", 0, "valeurUniteCompte"), float("-inf")),
            (("detailContratVie", "valorisation", "globalInvestCalcRest", "globalAmountFondEuros"), True),
            (("detailContratVie", "valorisation", "globalInvestCalcRest", "globalAmountUnitesCompte"), float("nan")),
        ]:
            with self.subTest(path=path, value=value):
                detail = per_detail(uc_line(), euro_line())
                target = detail
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value

                with self.assertRaises(ParseError) as error:
                    parse_per_detail(detail)

                self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")
                self.assertNotIn("SYNTHETIC-BAD-AMOUNT", str(error.exception))

    def test_invalid_valuation_and_price_dates_are_rejected(self):
        for path, value in [
            (("detailContratVie", "valorisation", "date"), "not-a-date"),
            (("detailContratVie", "valorisation", "date"), float("nan")),
            (("detailContratVie", "valorisation", "date"), 1e300),
            (("detailContratVie", "valorisation", "supports", 0, "dateValeurUniteCompte"), True),
            (("detailContratVie", "valorisation", "supports", 0, "dateValeurUniteCompte"), float("nan")),
        ]:
            with self.subTest(path=path, value=value):
                detail = per_detail(uc_line(), euro_line())
                target = detail
                for key in path[:-1]:
                    target = target[key]
                target[path[-1]] = value

                with self.assertRaises(ParseError) as error:
                    parse_per_detail(detail)

                self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_missing_required_payload_fields_raise_sanitized_parse_error(self):
        for path in [
            ("detailContratVie",),
            ("detailContratVie", "valorisation"),
            ("detailContratVie", "valorisation", "montant"),
            ("detailContratVie", "valorisation", "date"),
            ("detailContratVie", "valorisation", "supports"),
            ("detailContratVie", "valorisation", "globalInvestCalcRest"),
            ("detailContratVie", "valorisation", "globalInvestCalcRest", "globalAmountFondEuros"),
        ]:
            with self.subTest(path=path):
                detail = per_detail(uc_line(), euro_line())
                target = detail
                for key in path[:-1]:
                    target = target[key]
                del target[path[-1]]

                with self.assertRaises(ParseError) as error:
                    parse_per_detail(detail)

                self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_total_must_reconcile_with_uc_and_euro_pockets(self):
        detail = per_detail(uc_line(), euro_line(), total=171.0)

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_euro_lines_must_reconcile_with_aggregate_euro_pocket(self):
        detail = per_detail(uc_line(), euro_line(19.0))

        with self.assertRaises(ParseError) as error:
            parse_per_detail(detail)

        self.assertEqual(error.exception.code, "INVALID_PER_DETAIL")

    def test_rounding_difference_at_two_cents_is_accepted(self):
        detail = per_detail(
            uc_line(montant=149.98),
            euro_line(),
            total=169.98,
            uc_total=149.98,
        )

        result = parse_per_detail(detail)

        self.assertEqual(result["positions"][0]["value"], Decimal("149.98"))

    def test_distinct_compartments_may_hold_the_same_isin(self):
        first = uc_line(nombreUnitesCompte=0.5, montant=50.0, idCompartment=7)
        second = uc_line(nombreUnitesCompte=1.0, montant=100.0, idCompartment=8)

        result = parse_per_detail(per_detail(first, second, euro_line()))

        self.assertEqual([(position["isin"], position["compartmentId"]) for position in result["positions"]], [
            (ISIN_A, 7),
            (ISIN_A, 8),
        ])

    def test_optional_uc_summary_can_be_absent(self):
        detail = per_detail(uc_line(), euro_line())
        del detail["detailContratVie"]["valorisation"]["globalInvestCalcRest"]["globalAmountUnitesCompte"]

        result = parse_per_detail(detail)

        self.assertEqual(result["balance"], Decimal("170.0"))


if __name__ == "__main__":
    unittest.main()
