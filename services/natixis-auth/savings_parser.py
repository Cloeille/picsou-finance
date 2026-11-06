"""Maps Natixis Interépargne `savings` and `products` responses to Picsou plans.

`saving/v1/employees/{id}/savings` lists the saver's plans (PEE, PERCO, ...),
each split into compartments whose `investmentOptions` are the fund lines.
`saving/v1/employees/{id}/products` carries the same totals, computed on the
other side of the API; the two are checked against each other so a partial
answer is never taken for the whole portfolio.
"""

from decimal import Decimal, InvalidOperation

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"
PORTFOLIO_INCOMPLETE = "PORTFOLIO_INCOMPLETE"

# Same tolerance as the backend's SyncValues.moneyClose.
MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")


class SavingsFormatError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def money_close(actual: Decimal, expected: Decimal) -> bool:
    tolerance = max(MONEY_ABSOLUTE_TOLERANCE, abs(expected) * MONEY_RELATIVE_TOLERANCE)
    return abs(actual - expected) <= tolerance


def _decimal(value, what: str, *, required: bool = True) -> Decimal | None:
    if value is None and not required:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is not a number")
    try:
        number = Decimal(str(value))
    except InvalidOperation as exc:
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is not a number") from exc
    if not number.is_finite():
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is not finite")
    return number


def _amount(holder: dict, key: str, what: str, *, required: bool = True) -> Decimal | None:
    node = holder.get(key)
    if node is None:
        if required:
            raise SavingsFormatError(FORMAT_CHANGED, f"{what} is missing")
        return None
    if not isinstance(node, dict):
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is not an amount")
    value = node.get("value")
    if value is None and not required:
        return None
    # Natixis tags every amount with its currency. The backend books everything
    # in EUR, so any other or a missing code fails closed instead of being
    # stored as euros.
    if node.get("currencyCode") != "EUR":
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is not in EUR")
    return _decimal(value, what, required=required)


def _text(value, what: str, *, required: bool = True) -> str | None:
    if isinstance(value, str) and value.strip():
        return value.strip()
    if required:
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} is missing")
    return None


def _items(payload, what: str) -> list:
    items = payload.get("items") if isinstance(payload, dict) else None
    if not isinstance(items, list):
        raise SavingsFormatError(FORMAT_CHANGED, f"{what} has no item list")
    return items


def _compartments(holder: dict, depth: int = 0):
    """A plan's compartments and, recursively, their `subCompartments`: voluntary
    payments keep their funds one level down (PEE_INVESTMENTS, PER_INVESTMENTS, ...)."""
    if depth > 5:
        raise SavingsFormatError(FORMAT_CHANGED, "Compartments nest too deep")
    key = "compartments" if depth == 0 else "subCompartments"
    for compartment in holder.get(key) or []:
        if not isinstance(compartment, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "A compartment is not an object")
        yield compartment
        yield from _compartments(compartment, depth + 1)


def _options(plan: dict):
    for compartment in _compartments(plan):
        for option in compartment.get("investmentOptions") or []:
            if not isinstance(option, dict):
                raise SavingsFormatError(FORMAT_CHANGED, "An investment option is not an object")
            yield option


def fund_ids(savings: dict) -> list[int]:
    """Every fund the plans hold, once, in the order they first appear."""
    seen: dict[int, None] = {}
    for plan in _items(savings, "savings"):
        if not isinstance(plan, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "A plan is not an object")
        for option in _options(plan):
            if isinstance(option.get("fundId"), int):
                seen.setdefault(option["fundId"], None)
    return list(seen)


def fund_code(detail: dict) -> str | None:
    """The fund's AMF code (`QS…`) when Natixis lists one; FCPE have no ISIN."""
    for identifier in detail.get("identifiers") or []:
        if isinstance(identifier, dict) and identifier.get("type") == "QS":
            value = identifier.get("value")
            if isinstance(value, str) and 0 < len(value) <= 20:
                return value
    return None


def _positions(plan: dict, codes: dict[int, str | None]) -> list[dict]:
    """One position per fund: a fund held in two compartments (employee savings
    and voluntary payments) is one holding, its units and amounts added up."""
    merged: dict[object, dict] = {}
    for option in _options(plan):
        if option.get("type") not in (None, "FUND"):
            raise SavingsFormatError(FORMAT_CHANGED, "An investment option is not a fund")
        fund_id = option.get("fundId")
        label = _text(option.get("label"), "Fund label")
        if isinstance(fund_id, int):
            key: object = fund_id
        else:
            # Without Natixis' fund id, a label seen twice cannot be told apart
            # from two different funds: merging them would be a guess.
            key = ("label", label)
            if key in merged:
                raise SavingsFormatError(FORMAT_CHANGED, "Two fund lines share a label and have no fund id")
        quantity = _decimal(option.get("units"), "Fund units")
        value = _amount(option, "amount", "Fund amount")
        pnl = _amount(option, "gainsOrLossesAmount", "Fund gains", required=False)
        last_value = option.get("lastValue") if isinstance(option.get("lastValue"), dict) else {}
        unit_value = _amount(last_value, "amount", "Fund unit value", required=False)
        line = merged.get(key)
        if line is None:
            merged[key] = {
                "isin": None,
                # The AMF code when Natixis gives one, else its own fund id: every
                # line keeps a distinct, stable identifier either way.
                "fundCode": (codes.get(fund_id) or f"NIE-{fund_id}") if isinstance(fund_id, int) else None,
                "label": label,
                "quantity": quantity,
                "unitValue": unit_value,
                "valueEur": value,
                "pnlEur": pnl,
            }
        else:
            line["quantity"] += quantity
            line["valueEur"] += value
            line["pnlEur"] = None if line["pnlEur"] is None or pnl is None else line["pnlEur"] + pnl
    return list(merged.values())


def parse_plans(savings: dict, products: dict, employee_id: str, codes: dict[int, str | None]) -> list[dict]:
    plans = []
    for plan in _items(savings, "savings"):
        if not isinstance(plan, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "A plan is not an object")
        plan_id = _text(plan.get("id"), "Plan id")
        balance = _amount(plan, "amount", "Plan amount")
        positions = _positions(plan, codes)
        lines_total = sum((p["valueEur"] for p in positions), Decimal(0))
        if not money_close(lines_total, balance):
            raise SavingsFormatError(PORTFOLIO_INCOMPLETE, "Fund lines do not add up to the plan amount")
        external_id = f"natixis_{employee_id}_{plan_id}"
        if len(external_id) > 100:
            # Truncating could give two plans the same account across syncs.
            raise SavingsFormatError(FORMAT_CHANGED, "Plan id too long")
        plans.append({
            "externalId": external_id,
            "name": _text(plan.get("displayableProductLabel"), "Plan label", required=False)
            or _text(plan.get("family"), "Plan family"),
            "planKind": _text(plan.get("family"), "Plan family", required=False),
            "employer": _text(plan.get("conventionDenomination"), "Employer", required=False),
            "balanceEur": balance,
            "positions": positions,
            "snapshotComplete": True,
        })
    if len({p["externalId"] for p in plans}) != len(plans):
        raise SavingsFormatError(FORMAT_CHANGED, "Two plans share an id")

    # `products` is computed separately: the plans must account for its whole total.
    info = products.get("additionalInformation") if isinstance(products, dict) else None
    if not isinstance(info, dict):
        raise SavingsFormatError(FORMAT_CHANGED, "Products carry no totals")
    managed = info.get("internalAmountsManagedByNIE") or info.get("internalAmounts")
    if not isinstance(managed, dict):
        raise SavingsFormatError(FORMAT_CHANGED, "Products carry no Natixis total")
    products_total = _amount(managed, "total", "Products total")
    # Totals alone let a small plan go missing inside the tolerance, and the backend
    # closes a plan it no longer sees: the plan count must match too.
    natixis_products = [
        item for item in _items(products, "products")
        if isinstance(item, dict) and item.get("internalAccount") is True
    ]
    if len(natixis_products) != len(plans):
        raise SavingsFormatError(PORTFOLIO_INCOMPLETE, "Plans and products list a different number of plans")
    plans_total = sum((p["balanceEur"] for p in plans), Decimal(0))
    if not money_close(plans_total, products_total):
        raise SavingsFormatError(PORTFOLIO_INCOMPLETE, "Plans do not add up to the products total")
    return plans
