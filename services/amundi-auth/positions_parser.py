"""Normalises Amundi's `dispositifsMulti` payload into the sidecar's contract.

`positionsSalarieFondsDto` is the *fund catalogue* a dispositif offers, not the
employee's holdings: a fund that is merely available carries nulls throughout.
On a real account 275 of 283 lines were catalogue entries, so treating them as
holdings rejects the whole payload.

Kept free of FastAPI and Playwright so the parsing rules -- which are the part
that silently breaks when Amundi reshapes a response -- can be tested on their
own. Every failure is explicit: nothing is ever coerced to zero, because a
missing valuation read as 0 EUR would wipe a plan's history.
"""

from decimal import Decimal, InvalidOperation
from typing import Any

# Amundi reports both the per-plan total and every line. If they disagree the
# read is partial and must not overwrite known-good data.
MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")
# Counted against *funded* plans, not the raw list: a real account carried 33
# dispositifs, nearly all of them long-closed and empty. Capping the raw list
# would have rejected it outright.
MAX_PLANS = 50

# typeDispositif -> the plan family shown next to the account name. Unknown
# values are passed through verbatim rather than dropped: a plan Picsou cannot
# label is still a plan the user owns.
PLAN_KINDS = {
    "PEE": "PEE",
    "PEG": "PEG",
    "PEI": "PEI",
    "PEGI": "PEG",
    "HES": "PEE",
    "RSP": "RSP",
    "PERCO": "PERCO",
    "PERCOI": "PERCO",
    "PERECO": "PERECO",
    "PERECOI": "PERECO",
    "PER": "PER",
    "PERO": "PER",
    "ART 83": "ART 83",
}

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"
INCOMPLETE = "PORTFOLIO_INCOMPLETE"


class PositionsFormatError(Exception):
    """A payload that cannot be trusted, carrying the stable code to surface."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def decimal_value(value: Any) -> Decimal | None:
    """Amundi sends money as JSON numbers; go through str to avoid float drift.

    Non-finite values are refused rather than passed on: `json.loads` accepts a
    bare `NaN`/`Infinity`, and an ordered comparison against one raises
    `InvalidOperation`, which would escape `PositionsFormatError` handling and
    surface as an opaque INTERNAL_ERROR instead of a typed format failure.
    """
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, Decimal):
        return _finite(value)
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, float):
        return _finite(Decimal(str(value)))
    if isinstance(value, str):
        cleaned = value.strip().replace("\u00a0", "").replace(" ", "").replace(",", ".")
        if not cleaned:
            return None
        try:
            return _finite(Decimal(cleaned))
        except InvalidOperation:
            return None
    return None


def _finite(value: Decimal) -> Decimal | None:
    return value if value.is_finite() else None


def text_value(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    cleaned = " ".join(value.split())
    if not cleaned:
        return None
    return cleaned[:limit]


def money_close(actual: Decimal, expected: Decimal) -> bool:
    difference = abs(actual - expected)
    if difference <= MONEY_ABSOLUTE_TOLERANCE:
        return True
    return difference <= abs(expected) * MONEY_RELATIVE_TOLERANCE


def plan_kind(raw: Any) -> str | None:
    kind = text_value(raw, 30)
    if kind is None:
        return None
    return PLAN_KINDS.get(kind.upper(), kind)


def _parse_position(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        raise PositionsFormatError(FORMAT_CHANGED, "Fund line is not an object")

    value_eur = decimal_value(raw.get("mtBrut"))
    quantity = decimal_value(raw.get("nbParts"))

    # An offered-but-unheld fund: no valuation, no units, no gain. Skipping it
    # is not the same as tolerating a missing valuation on a fund that IS held,
    # which still fails below -- that would be a partial read.
    if value_eur is None and quantity is None:
        return None

    label = text_value(raw.get("libelleFonds"), 200)
    if value_eur is None or quantity is None or label is None:
        raise PositionsFormatError(FORMAT_CHANGED, "Held fund line is missing a required field")
    if quantity == 0 and value_eur == 0:
        return None

    isin = text_value(raw.get("codeIsin"), 12)
    return {
        "isin": isin.upper() if isin else None,
        # Amundi's own identifier for the fund. Carried because `codeIsin` is not
        # always an ISIN -- on employer funds it can hold the AMF code instead --
        # and the backend then has nothing left but the label to key a holding on.
        "fundCode": text_value(raw.get("codeFonds"), 20),
        "label": label,
        "quantity": quantity,
        "unitValue": decimal_value(raw.get("vl")),
        "valueEur": value_eur,
        "pnlEur": decimal_value(raw.get("mtPMV")),
    }


def _parse_plan(raw: Any) -> dict[str, Any] | None:
    if not isinstance(raw, dict):
        raise PositionsFormatError(FORMAT_CHANGED, "Plan is not an object")

    external_id = text_value(raw.get("codeDispositif"), 80) or text_value(raw.get("idDispositif"), 80)
    name = text_value(raw.get("libelleDispositif"), 200)
    balance = decimal_value(raw.get("mtBrut"))
    if external_id is None or name is None or balance is None:
        raise PositionsFormatError(FORMAT_CHANGED, "Plan is missing a required field")

    lines = raw.get("positionsSalarieFondsDto")
    if lines is None:
        lines = []
    if not isinstance(lines, list):
        raise PositionsFormatError(FORMAT_CHANGED, "Plan fund lines are not a list")

    positions = [parsed for parsed in (_parse_position(line) for line in lines) if parsed]
    # A plan the employee has emptied: no units, no money, nothing to track.
    if not positions and balance == 0:
        return None
    if not positions:
        raise PositionsFormatError(INCOMPLETE, "Plan has a balance but reported no fund lines")

    total = sum((position["valueEur"] for position in positions), Decimal("0"))
    if not money_close(total, balance):
        raise PositionsFormatError(INCOMPLETE, "Plan valuation does not reconcile with its lines")

    return {
        "externalId": external_id,
        "name": name,
        "planKind": plan_kind(raw.get("typeDispositif")),
        "employer": text_value(raw.get("nomEntreprise"), 200),
        "balanceEur": balance,
        "positions": positions,
        "snapshotComplete": True,
    }


def parse_plans(payload: Any) -> list[dict[str, Any]]:
    """Turn a dispositifsMulti response into plan dicts, or raise."""
    if not isinstance(payload, dict):
        raise PositionsFormatError(FORMAT_CHANGED, "Response is not an object")
    raw_plans = payload.get("listPositionsSalarieDispositifsDto")
    if not isinstance(raw_plans, list):
        raise PositionsFormatError(FORMAT_CHANGED, "Response is missing the plan list")
    plans = [parsed for parsed in (_parse_plan(plan) for plan in raw_plans) if parsed]
    if not plans:
        raise PositionsFormatError(INCOMPLETE, "Response holds no usable plan")
    if len(plans) > MAX_PLANS:
        raise PositionsFormatError(INCOMPLETE, "Response holds more funded plans than supported")
    return plans
