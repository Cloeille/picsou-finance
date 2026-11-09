"""Parse Caisse d'Epargne PER detail without transport or side effects.

The meaning of mixed ``familleFondsGeneraux`` classifications is inferred:
``True`` denotes euro funds and ``False`` unit-linked funds. Mixed lines are
accepted only when their euro and UC pockets reconcile to the insurer total.
Cost basis is deliberately not imported from ``capitalGainView``.
"""

from datetime import datetime, timedelta, timezone
from decimal import Decimal, DecimalException
import math
import re
from typing import Any, NoReturn

from accounts_parser import ParseError


INVALID_PER_DETAIL = "INVALID_PER_DETAIL"
_TOLERANCE = Decimal("0.02")
_EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
_ISIN_RE = re.compile(r"^[A-Z]{2}[A-Z0-9]{9}[0-9]$")


def _fail(where: str, expectation: str) -> NoReturn:
    raise ParseError(INVALID_PER_DETAIL, f"{where}: {expectation}")


def _object(value: Any, where: str) -> dict:
    if not isinstance(value, dict):
        _fail(where, "expected an object")
    return value


def _required(mapping: dict, key: str, where: str) -> Any:
    if key not in mapping:
        _fail(f"{where}.{key}", "missing field")
    return mapping[key]


def _decimal(value: Any, where: str, *, nonnegative: bool = False) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _fail(where, "expected a number")
    if isinstance(value, float) and not math.isfinite(value):
        _fail(where, "expected a finite number")
    try:
        result = Decimal(repr(value)) if isinstance(value, float) else Decimal(value)
    except DecimalException:
        _fail(where, "expected a finite number")
    if not result.is_finite():
        _fail(where, "expected a finite number")
    if nonnegative and result < 0:
        _fail(where, "expected a nonnegative number")
    return result


def _text(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        _fail(where, "expected a non-empty string")
    return value


def _epoch_date(value: Any, where: str) -> str:
    milliseconds = _decimal(value, where)
    if milliseconds != milliseconds.to_integral_value():
        _fail(where, "expected an integer epoch-millisecond date")
    try:
        result = _EPOCH + timedelta(milliseconds=int(milliseconds))
    except (OverflowError, ValueError):
        _fail(where, "expected a valid epoch-millisecond date")
    return result.date().isoformat()


def parse_per_detail(payload: Any, *, currency: str = "EUR") -> dict:
    """Return reconciled EUR balance, euro-fund balance, date, and UC lines."""
    if currency != "EUR":
        _fail("currency", "only EUR is supported")

    root = _object(payload, "payload")
    contract = _object(_required(root, "detailContratVie", "payload"), "detailContratVie")
    valuation = _object(
        _required(contract, "valorisation", "detailContratVie"), "detailContratVie.valorisation"
    )
    summary = _object(
        _required(valuation, "globalInvestCalcRest", "valorisation"),
        "valorisation.globalInvestCalcRest",
    )
    balance = _decimal(_required(valuation, "montant", "valorisation"), "valorisation.montant", nonnegative=True)
    euro_balance = _decimal(
        _required(summary, "globalAmountFondEuros", "globalInvestCalcRest"),
        "globalAmountFondEuros",
        nonnegative=True,
    )
    valuation_date = _epoch_date(_required(valuation, "date", "valorisation"), "valorisation.date")
    supports = _required(valuation, "supports", "valorisation")
    if not isinstance(supports, list):
        _fail("valorisation.supports", "expected a list")

    positions = []
    identities = set()
    uc_total = Decimal("0")
    euro_lines_total = Decimal("0")
    has_euro_lines = False
    for index, raw_support in enumerate(supports):
        where = f"supports[{index}]"
        support = _object(raw_support, where)
        classification = _required(support, "familleFondsGeneraux", where)
        if not isinstance(classification, bool):
            _fail(f"{where}.familleFondsGeneraux", "expected an explicit boolean classification")
        amount = _decimal(_required(support, "montant", where), f"{where}.montant", nonnegative=True)
        if classification:
            has_euro_lines = True
            euro_lines_total += amount
            continue

        quantity = _decimal(
            _required(support, "nombreUnitesCompte", where),
            f"{where}.nombreUnitesCompte",
            nonnegative=True,
        )
        uc_total += amount
        if quantity == 0:
            if amount != 0:
                _fail(where, "zero quantity carries a value")
            continue

        unit_price = _decimal(
            _required(support, "valeurUniteCompte", where),
            f"{where}.valeurUniteCompte",
            nonnegative=True,
        )
        if unit_price <= 0:
            _fail(f"{where}.valeurUniteCompte", "expected a positive NAV")
        if abs(quantity * unit_price - amount) > _TOLERANCE:
            _fail(where, "quantity and NAV do not reconcile with reported value")

        isin = _text(_required(support, "codeIsin", where), f"{where}.codeIsin")
        if _ISIN_RE.fullmatch(isin) is None:
            _fail(f"{where}.codeIsin", "expected an uppercase source ISIN")
        name = _text(_required(support, "nom", where), f"{where}.nom")
        compartment_id = _required(support, "idCompartment", where)
        if isinstance(compartment_id, bool) or not isinstance(compartment_id, int) or compartment_id < 0:
            _fail(f"{where}.idCompartment", "expected a nonnegative integer compartment id")
        price_date = _epoch_date(
            _required(support, "dateValeurUniteCompte", where), f"{where}.dateValeurUniteCompte"
        )
        if price_date > valuation_date:
            _fail(f"{where}.dateValeurUniteCompte", "NAV date is after contract valuation")

        identity = (isin, compartment_id)
        if identity in identities:
            _fail(where, "duplicate ISIN and compartment identity")
        identities.add(identity)
        positions.append(
            {
                "isin": isin,
                "name": name,
                "quantity": quantity,
                "unitPrice": unit_price,
                "value": amount,
                "priceDate": price_date,
                "compartmentId": compartment_id,
            }
        )

    if "globalAmountUnitesCompte" in summary:
        reported_uc_total = _decimal(
            summary["globalAmountUnitesCompte"], "globalAmountUnitesCompte", nonnegative=True
        )
        if abs(reported_uc_total - uc_total) > _TOLERANCE:
            _fail("globalAmountUnitesCompte", "UC lines do not reconcile")
    if abs(balance - euro_balance - uc_total) > _TOLERANCE:
        _fail("valorisation", "total does not reconcile with UC and euro pockets")
    if has_euro_lines and abs(euro_lines_total - euro_balance) > _TOLERANCE:
        _fail("supports", "euro lines do not reconcile with euro pocket")

    return {
        "balance": balance,
        "euroFundBalance": euro_balance,
        "valuationDate": valuation_date,
        "positions": positions,
    }
