"""Turn the CORUM client-space payloads into Picsou's typed contract shape.

The two calls disagree about what a position is, and getting that wrong
overstates net worth:

- the contract call (`/contract/realEstate/...`) knows the envelope and which
  funds exist, but carries no unit price;
- the per-fund call (`.../product/{code}`) is the only place a withdrawal
  price appears.

`saving.value` is `quantity * unitPrice`, and `unitPrice` there is the
SUBSCRIPTION-side price -- it equals `averageUnitPrice`, and the portal uses
it for the client-space subscription form. `withdrawalPrice` is materially
lower (entry fees sit between the two). A snapshot that valued a share at
`saving.value` would overstate net worth by that fee, so the balance here is
always `quantity * withdrawalPrice` and `saving.value` is carried as
display-only.

The fund codes are `US` / `XL` -- market short names, not labels. A code is
all the client space carries, so it is what the domain gets as its external
id, and the display name is mapped explicitly rather than guessed from it.
"""

from decimal import Decimal, InvalidOperation
from typing import Any

FUND_NAMES = {
    "US": "CORUM USA",
    "XL": "CORUM XL",
    "EU": "CORUM EU",
    "ORIGIN": "CORUM Origin",
}

# CORUM quotes these in euros, with no foreign leg on a real-estate line.
CURRENCY = "EUR"


class PositionsFormatError(Exception):
    """The upstream payload no longer matches what this parser reads."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _decimal(value: Any, field: str) -> Decimal:
    if value is None or isinstance(value, bool):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED") from exc


def _product_codes(contract: dict[str, Any]) -> list[str]:
    products = contract.get("productsData")
    if not isinstance(products, list) or not products:
        raise PositionsFormatError("PORTFOLIO_INCOMPLETE")
    codes: list[Any] = [p.get("productCode") for p in products if isinstance(p, dict)]
    if len(codes) != len(products) or not all(isinstance(c, str) and c for c in codes):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    if len(set(codes)) != len(codes):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    return codes


def _envelope_total(contract: dict[str, Any]) -> Decimal:
    total = contract.get("valuationAmount")
    if total is None:
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    return _decimal(total, "valuationAmount")


def _share(product: dict[str, Any]) -> dict[str, Any]:
    saving = product.get("saving")
    if not isinstance(saving, dict):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    share = saving.get("share")
    if not isinstance(share, dict):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
    return share


def _valuation_date(raw: Any) -> str | None:
    """CORUM renders dates as `DD/MM/YYYY`; the domain wants ISO."""
    if not isinstance(raw, str) or not raw:
        return None
    parts = raw.split("/")
    if len(parts) != 3:
        return None
    day, month, year = parts
    if not (day.isdigit() and month.isdigit() and year.isdigit()):
        return None
    return f"{year}-{month.zfill(2)}-{day.zfill(2)}"


def _lines_sum(contract: dict[str, Any], values: list[Decimal]) -> None:
    """Fail closed when the funds do not add up to the contract.

    Mirrors the Amundi rule. A contract that disagrees with itself is a
    half-read snapshot, and writing it would silently corrupt a long-horizon
    net-worth series.
    """
    total = _envelope_total(contract)
    if abs(sum(values) - total) > Decimal("0.01"):
        raise PositionsFormatError("PORTFOLIO_INCOMPLETE")


def parse_snapshot(contract: dict[str, Any], products: list[dict[str, Any]]) -> dict[str, Any]:
    """Contract envelope + one payload per fund code -> a Picsou snapshot."""
    if not isinstance(contract, dict):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")

    codes = _product_codes(contract)
    by_code: dict[str, dict[str, Any]] = {}
    for product in products:
        if not isinstance(product, dict):
            raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
        code = product.get("productCode")
        if not isinstance(code, str):
            raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")
        by_code[code] = product

    missing = [code for code in codes if code not in by_code]
    if missing:
        # One fund unread means the portfolio is partial. Refuse it whole.
        raise PositionsFormatError("PORTFOLIO_INCOMPLETE")

    positions: list[dict[str, Any]] = []
    displayed_values: list[Decimal] = []

    for code in codes:
        product = by_code[code]
        share = _share(product)
        quantity = _decimal(share.get("quantity"), "quantity")
        if quantity < 0:
            raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED")

        # A zero is a real price, an absent one is not the same thing: it means
        # CORUM is not quoting that fund today, and the domain has to keep the
        # previous balance rather than write a zero.
        raw_withdrawal = share.get("withdrawalPrice")
        withdrawal = (
            _decimal(raw_withdrawal, "withdrawalPrice")
            if raw_withdrawal is not None
            else None
        )
        subscription_raw = share.get("unitPrice")
        subscription = (
            _decimal(subscription_raw, "unitPrice")
            if subscription_raw is not None
            else None
        )

        saving = product["saving"]
        value_raw = saving.get("value")
        if value_raw is not None:
            displayed_values.append(_decimal(value_raw, "saving.value"))

        positions.append(
            {
                "fundCode": code,
                "label": FUND_NAMES.get(code, f"CORUM {code}"),
                "quantity": quantity,
                "withdrawalPrice": withdrawal,
                "subscriptionPrice": subscription,
                "displayedValueEur": _decimal(value_raw, "saving.value")
                if value_raw is not None
                else None,
                "valuationDate": _valuation_date(share.get("valuationDate")),
            }
        )

    if len(displayed_values) == len(codes):
        # Only a complete read can be checked against the envelope.
        _lines_sum(contract, displayed_values)

    valuation_date = contract.get("valuationDate")
    return {
        "contractCode": contract.get("contractCode"),
        "propertyRightType": contract.get("propertyRightType"),
        "currency": CURRENCY,
        "totalValuationEur": _envelope_total(contract),
        "valuationDate": _valuation_date(valuation_date) if valuation_date else None,
        "snapshotComplete": True,
        "positions": positions,
    }
