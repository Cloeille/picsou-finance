"""Turn one CORUM Life (`/api/contract/life/{code}`) payload into a snapshot.

CORUM Life is an insurance wrapper, not a share register, and the payload
says so in two different places that have to be read together:

- `distributionTableSections` are the management pockets ("Gestion libre",
  "Corum Life Horizon", "CORUM Eurolife"). They carry the per-line quantity,
  unit price and amount, and the same fund (CC, XL, EU) can sit in several
  pockets at once. Picsou keeps one holding per fund, so the lines are summed
  per code here rather than written twice.
- `chartSections` is the asset-class view. It is the only place that says
  which code is the euro fund (section `euroLife`). The euro fund is invested
  money, so it is a holding like the others, flagged `euroFund`. Its line has
  an integer `quantity`/`unitPrice` whose meaning is unproven, so both are
  ignored: the fund is written as 1-euro units, quantity = value. Every
  holding carries its section name as `assetClass`.
- `contractDetail` carries the contract's own net invested amount. A line's
  `netInvestedAmountCumulated` leaves out money switched between funds (the
  fund switched out shows a negative `reallocationAmount`, the one switched
  into a positive one and a gain it never made), so the lines' costs do not
  add up to the contract's and only the contract figure gives its gain.

Every figure is a JSON number. A string, a bool or a missing amount means the
format moved, and the whole contract is refused rather than half-read: a
long-horizon net-worth series is worse off with a wrong point than with a
missing one.
"""

import logging
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

from positions_parser import CURRENCY, PositionsFormatError, _valuation_date

# The sidecar's own logger (`configure_logging("corum-auth")` in main.py).
log = logging.getLogger("corum-auth")

# Scale of a unit price derived across pockets, matching the backend's per-unit figures.
UNIT_PRICE_EXPONENT = Decimal("0.00000001")

# A code is all the payload carries. These names were confirmed by a CORUM Life
# holder against the client space; an unknown code keeps the code visible rather
# than guessing a name. CC, XL and EU are SCPI units held inside the policy (the
# real-estate contract calls Origin `ORIGIN`, hence no shared map); BCO, CBEHY
# and CBE are bond funds.
LIFE_FUND_NAMES = {
    "CC": "CORUM Origin",
    "XL": "CORUM XL",
    "EU": "CORUM Eurion",
    "BCO": "CORUM BCO",
    "CBEHY": "CORUM BEHY",
    "CBE": "CORUM Butler Entreprise",
    "FGCORUM": "CORUM EuroLife (fonds euros)",
}

EURO_FUND_SECTION = "euroLife"


def _format_changed() -> PositionsFormatError:
    return PositionsFormatError("UPSTREAM_FORMAT_CHANGED")


def _number(value: Any) -> Decimal:
    """A JSON number, read exactly.

    Stricter than `positions_parser._decimal`: that one accepts a numeric
    string, but nothing in this payload is ever a string, so one showing up is
    a format change rather than something to coerce.
    """
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise _format_changed()
    parsed = Decimal(str(value))
    if not parsed.is_finite():
        raise _format_changed()
    return parsed


def _optional_number(value: Any) -> Decimal | None:
    """A JSON number, or None when the field is absent or null."""
    return None if value is None else _number(value)


def _list(value: Any) -> list[Any]:
    """`value` itself when it is a list; anything else means the format moved."""
    if not isinstance(value, list):
        raise _format_changed()
    return value


def _dict(value: Any) -> dict[str, Any]:
    """`value` itself when it is an object; anything else means the format moved."""
    if not isinstance(value, dict):
        raise _format_changed()
    return value


def _code(product: dict[str, Any]) -> str:
    """A product's `productName`, the only identifier the payload carries.

    A blank or non-string name is a format change, not a line to skip."""
    code = product.get("productName")
    if not isinstance(code, str) or not code:
        raise _format_changed()
    return code


def _tolerance(line_count: int) -> Decimal:
    """Same rule as the backend's `linesClose`: a cent per line, never under
    five cents. CORUM rounds each line, so an exact match would refuse sound
    contracts, and a looser one would let a missing small line through."""
    return max(Decimal("0.05"), Decimal("0.01") * line_count)


def _reconcile(values: list[Decimal], total: Decimal, line_count: int) -> None:
    """Refuse the contract when its lines do not add up to `total`.

    A missing line would otherwise be written as a smaller portfolio."""
    if abs(sum(values, Decimal("0")) - total) > _tolerance(line_count):
        raise PositionsFormatError("PORTFOLIO_INCOMPLETE")


def _asset_classes(profile: dict[str, Any]) -> dict[str, str]:
    """Each code's `chartSections` section name, as CORUM spells it.

    The asset-class view is the only place that says what a fund is: the
    backend maps `realEstate` to a wealth tier and the euro fund is
    recognised by `euroLife`. A code under two sections would make that
    reading a guess, so it is refused rather than resolved by order.
    """
    classes: dict[str, str] = {}
    for section in _list(profile.get("chartSections")):
        section = _dict(section)
        name = section.get("sectionName")
        if not isinstance(name, str) or not name:
            raise _format_changed()
        for product in _list(section.get("sectionProduct")):
            code = _code(_dict(product))
            if classes.setdefault(code, name) != name:
                raise _format_changed()
    return classes


def _scheduled_payment(detail: dict[str, Any]) -> dict[str, Any] | None:
    """The programmed savings plan, or None when there is none to show.

    Informational only: nothing is reconciled against it, so an odd shape
    drops the figure instead of refusing the contract.
    """
    plan = detail.get("programSavingAmount")
    if not isinstance(plan, dict):
        return None
    amount = plan.get("amount")
    frequency = plan.get("frequency")
    if (
        isinstance(amount, bool)
        or not isinstance(amount, (int, float))
        or not isinstance(frequency, str)
        or not frequency
    ):
        return None
    parsed = Decimal(str(amount))
    if not parsed.is_finite() or parsed < 0:
        return None
    return {"amountEur": parsed, "frequency": frequency}


def parse_life(payload: Any) -> dict[str, Any]:
    """One `/api/contract/life/{code}` payload -> a Picsou LIFE snapshot."""
    payload = _dict(payload)
    contract_code = payload.get("contractCode")
    if not isinstance(contract_code, str) or not contract_code:
        raise _format_changed()
    contract_type = payload.get("contractType")
    if not isinstance(contract_type, str) or contract_type.casefold() != "life":
        raise _format_changed()

    detail = _dict(payload.get("contractDetail"))
    total = _number(detail.get("valuationAmount"))
    # Mirrors the SCPI envelope: a negative contract is not "nothing to check".
    if total < 0:
        raise PositionsFormatError("PORTFOLIO_INCOMPLETE")
    # The contract's own cost basis. The lines' `netInvestedAmountCumulated`
    # leaves out money switched between funds, so only this figure gives the
    # contract's real gain. CORUM states that gain too: when the three do not
    # agree, the figures no longer mean what this reader assumes.
    contract_net = _number(detail.get("netInvestedAmount"))
    if contract_net < 0:
        raise _format_changed()
    if abs(total - contract_net - _number(detail.get("gainOrLoss"))) > Decimal("0.05"):
        raise _format_changed()
    paid_in = _optional_number(detail.get("grossInvestedAmount"))
    if paid_in is not None and paid_in < 0:
        raise _format_changed()

    profile = _dict(payload.get("contractProfile"))
    asset_classes = _asset_classes(profile)
    euro_codes = {code for code, name in asset_classes.items() if name == EURO_FUND_SECTION}

    holdings: dict[str, dict[str, Any]] = {}
    pocket_totals: list[Decimal] = []
    # Codes whose pockets quote different unit prices.
    mixed_prices: set[str] = set()
    line_count = 0

    for pocket in _list(profile.get("distributionTableSections")):
        pocket = _dict(pocket)
        products = _list(pocket.get("sectionProduct"))
        amounts: list[Decimal] = []
        for product in products:
            product = _dict(product)
            code = _code(product)
            amount = _number(product.get("amount"))
            if amount < 0:
                raise _format_changed()
            amounts.append(amount)
            # `reallocationAmount` is a pending switch, already inside
            # `amount`; reading it would count the same euros twice.

            euro_fund = code in euro_codes
            if euro_fund:
                # The euro fund has no unit and no NAV date. One that suddenly
                # carries a date is no longer the line this rule was written
                # for, so it is not silently read as 1-euro units.
                if product.get("valorisationDate") is not None:
                    raise _format_changed()
                quantity = amount
                unit_price = Decimal("1")
            else:
                quantity = _number(product.get("quantity"))
                if quantity < 0:
                    raise _format_changed()
                if quantity == 0 and amount != 0:
                    # Value with no units is a half-written line, not a position.
                    raise PositionsFormatError("PORTFOLIO_INCOMPLETE")
                unit_price = _number(product.get("unitPrice"))
                if unit_price < 0:
                    raise _format_changed()
            net_invested = _optional_number(product.get("netInvestedAmountCumulated"))
            date = _valuation_date(product.get("valorisationDate"))

            held = holdings.get(code)
            if held is None:
                holdings[code] = {
                    "productCode": code,
                    "label": LIFE_FUND_NAMES.get(code, f"CORUM {code}"),
                    "quantity": quantity,
                    "unitPrice": unit_price,
                    "valueEur": amount,
                    "netInvestedEur": net_invested,
                    "valuationDate": date,
                    "euroFund": euro_fund,
                    "assetClass": asset_classes.get(code),
                }
                continue
            held["quantity"] += quantity
            held["valueEur"] += amount
            if held["unitPrice"] != unit_price:
                mixed_prices.add(code)
            # An unknown cost in any pocket makes the fund's cost unknown:
            # a partial sum would understate it and invent a gain.
            if held["netInvestedEur"] is None or net_invested is None:
                held["netInvestedEur"] = None
            else:
                held["netInvestedEur"] += net_invested
            if date is not None and (held["valuationDate"] is None or date > held["valuationDate"]):
                held["valuationDate"] = date

        pocket_total = _number(pocket.get("amount"))
        _reconcile(amounts, pocket_total, len(products))
        pocket_totals.append(pocket_total)
        line_count += len(products)

    _reconcile(pocket_totals, total, line_count)
    # Pockets can value one fund at different prices (one priced at an older
    # NAV). The holding then carries the price its summed figures imply; with no
    # unit at all, the first pocket's price stands.
    for code in mixed_prices:
        held = holdings[code]
        if held["quantity"] > 0:
            held["unitPrice"] = (held["valueEur"] / held["quantity"]).quantize(
                UNIT_PRICE_EXPONENT, rounding=ROUND_HALF_UP
            )
    # The rule the backend re-runs on the wire shape: what is written as
    # holdings must give back the contract.
    _reconcile([h["valueEur"] for h in holdings.values()], total, line_count)

    return {
        "contractCode": contract_code,
        "currency": CURRENCY,
        "totalValuationEur": total,
        "valuationDate": _valuation_date(payload.get("valuationDate")),
        # Raw rows across every pocket, euro fund included: the backend
        # re-runs the reconciliation and needs the same line count for its
        # tolerance.
        "valuationLineCount": line_count,
        # Contract-level figures. A line's cost leaves out switched money, so
        # the gain is only right against this one.
        "netInvestedEur": contract_net,
        "paidInEur": paid_in,
        "openedAt": _valuation_date(payload.get("creationDate")),
        "scheduledPayment": _scheduled_payment(detail),
        "snapshotComplete": True,
        "holdings": list(holdings.values()),
    }


# The operation list a CORUM Life contract shows under "Mes opérations". Bounds
# match the wire model: past them the field is not what this reader expects.
OPERATION_TYPE_MAX = 60
OPERATION_ID_MAX = 40


def _operation_date(raw: Any) -> str:
    """`DD/MM/YYYY` -> ISO. An operation without a readable date cannot be
    placed in the ledger, so it is a format change, not a row to skip."""
    iso = _valuation_date(raw)
    if iso is None:
        raise _format_changed()
    return iso


def _operation_id(raw: Any) -> str:
    """The one id CORUM gives an operation, as the string of an integer.

    Live, `operationIds` holds exactly one id, sent as a string of digits
    (`["1234567"]`); a bare integer is accepted too. Several would mean one row
    now stands for several operations, which the backend's key per operation
    cannot express, so it is refused rather than guessed.
    """
    ids = _list(raw)
    if len(ids) != 1:
        raise _format_changed()
    value = ids[0]
    if isinstance(value, bool):
        raise _format_changed()
    if isinstance(value, int):
        text = str(value)
    elif isinstance(value, str) and value.isascii() and value.isdigit():
        text = value
    else:
        raise _format_changed()
    if len(text) > OPERATION_ID_MAX:
        raise _format_changed()
    return text


def _fund_codes(raw: Any) -> list[str] | None:
    """`"CC"` or `"CC, XL"` -> `["CC"]` / `["CC", "XL"]`; null or blank -> None."""
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise _format_changed()
    codes = [part.strip().upper() for part in raw.split(",")]
    codes = [code for code in codes if code]
    if any(len(code) > 40 for code in codes):
        raise _format_changed()
    return codes or None


def _operation(raw: Any) -> dict[str, Any]:
    """One operation row, normalised for the backend.

    A bad type, category, id, date or amount refuses the whole payload."""
    raw = _dict(raw)
    op_type = raw.get("operationType")
    if not isinstance(op_type, str) or not op_type.strip() or len(op_type.strip()) > OPERATION_TYPE_MAX:
        raise _format_changed()
    category = raw.get("operationCategory")
    if category is not None and not isinstance(category, str):
        raise _format_changed()
    category = category.strip() if category else None
    if category is not None and len(category) > OPERATION_TYPE_MAX:
        raise _format_changed()
    return {
        "operationId": _operation_id(raw.get("operationIds")),
        "type": op_type.strip(),
        "category": category or None,
        "date": _operation_date(raw.get("date")),
        # Signed as CORUM shows it: fees are negative, an arbitrage has a leg each way.
        "amountEur": _number(raw.get("amount")),
        "fundCodes": _fund_codes(raw.get("fundCodes")),
    }


def parse_operations(payload: Any, life_codes: list[str]) -> dict[str, list[dict[str, Any]] | None]:
    """One `/api/operations/` payload -> the operations of each Life contract.

    The payload lists every contract of the login, each split by year. Only the
    codes in `life_codes` are kept; one that the payload does not list maps to
    None ("not reported"), which the backend reads as "keep what is stored".
    Any malformed entry refuses the whole payload: the caller then reports no
    operations at all rather than a list missing some.

    Two operations of one contract that share an id and a sign cannot be told
    apart by the backend's key (the id, plus the sign when the id repeats).
    That is no malformed entry, so it does not refuse the payload: that
    contract alone reports None, and its valuation still syncs.
    """
    wanted = set(life_codes)
    found: dict[str, list[dict[str, Any]]] = {}
    for contract in _list(payload):
        contract = _dict(contract)
        code = contract.get("contractCode")
        if not isinstance(code, str) or not code:
            raise _format_changed()
        if code not in wanted:
            # Another kind of contract (real estate, PER...): not read here.
            continue
        if code in found:
            raise _format_changed()
        operations: list[dict[str, Any]] = []
        for year in _list(contract.get("yearData")):
            year = _dict(year)
            for raw in _list(year.get("operations")):
                operations.append(_operation(raw))
        found[code] = operations if _keys_distinct(operations) else None
    return {code: found.get(code) for code in life_codes}


def _keys_distinct(operations: list[dict[str, Any]]) -> bool:
    """True when no two operations share an id and a sign.

    The two legs of a switch can share an id; their signs tell them apart, so
    the backend keys them `<id>:+` / `<id>:-`. A third operation under that id,
    or two of one sign, would have both own one row. Logged by cause only.
    """
    keys = {(op["operationId"], op["amountEur"] < 0) for op in operations}
    if len(keys) == len(operations):
        return True
    log.warning("CORUM Life operations share a key, not reported (code=DUPLICATE_OPERATION)")
    return False
