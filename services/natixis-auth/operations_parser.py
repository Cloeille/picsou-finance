"""Maps Natixis Interépargne operations to Picsou BUY / SELL lines.

`transaction/v1/employees/{id}/transactions` lists the saver's operations (five
years deep); each finished one has a detail:

- an investment demand (`INVESTMENT_DEMANDS`, e.g. profit-sharing invested) holds
  one line per fund, and the employer top-up of that line as `additionalInvestment`:
  two BUYs, so the top-up stays visible as such;
- a payment demand (`PAYMENT_DEMANDS`, e.g. an early release) holds payments, each
  selling units of one or more funds: one SELL per fund line.

Operations are history only: the plan snapshot stays the source of truth for
balances and holdings.
"""

import hashlib
import re
from decimal import Decimal
from urllib.parse import quote, urlsplit

from savings_parser import FORMAT_CHANGED, SavingsFormatError, _amount, _decimal, _text

INVESTMENT = "INVESTMENT_DEMANDS"
PAYMENT = "PAYMENT_DEMANDS"
SUPPORTED = (INVESTMENT, PAYMENT)
API_HOST = "nie.api.natixis.com"
# The detail resource of each supported type, under the saver's transaction space.
DETAIL_COLLECTION = {INVESTMENT: "investmentDemands", PAYMENT: "paymentDemands"}
# A demand key is one path segment of plain characters: no dot, slash, percent sign,
# query or fragment can reach the URL Picsou calls with the saver's token.
_DEMAND_KEY = re.compile(r"[0-9A-Za-z_-]{1,120}")
_LINK_CHARS = re.compile(r"[\x21-\x7e]{1,512}")
EMPLOYER_TOP_UP = "Abondement employeur"
PAYMENT_LABELS = {
    "EARLY_RELEASE": "Déblocage anticipé",
    "MATURITY": "Déblocage à échéance",
}
DEFAULT_PAYMENT_LABEL = "Remboursement"


def finished_demands(transactions: dict, employee_id: str) -> list[dict]:
    """The finished operations Picsou reads, as {type, id, path}; the rest is skipped.

    The detail path is rebuilt from the saver's id, the operation type and the key the
    link ends with, never taken from the link as sent: an upstream answer must not
    choose which URL the saver's token is sent to. A link of any other shape -- another
    host, another saver, a traversal, an encoded character, a query -- is a format change.
    """
    items = transactions.get("items") if isinstance(transactions, dict) else None
    if not isinstance(items, list):
        raise SavingsFormatError(FORMAT_CHANGED, "Transactions have no item list")
    base = f"/transaction/v1/employees/{quote(employee_id, safe='')}"
    demands = []
    for item in items:
        if not isinstance(item, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "A transaction is not an object")
        if item.get("type") not in SUPPORTED or item.get("finished") is not True:
            continue
        href = ((item.get("_links") or {}).get("self") or {}).get("href")
        demand_id = _text(item.get("id"), "Transaction id")
        if not isinstance(href, str):
            raise SavingsFormatError(FORMAT_CHANGED, "A transaction has no detail link")
        path = _detail_path(href, f"{base}/{DETAIL_COLLECTION[item['type']]}/")
        demands.append({"type": item["type"], "id": demand_id, "path": path})
    return demands


def _detail_path(href: str, prefix: str) -> str:
    # urlsplit silently drops tabs and newlines, so a link holding any whitespace,
    # control or non-ASCII character is refused before it is parsed.
    if _LINK_CHARS.fullmatch(href) is None:
        raise SavingsFormatError(FORMAT_CHANGED, "Operation detail outside the saver's space")
    link = urlsplit(href)
    if (link.scheme, link.netloc) not in (("", ""), ("https", API_HOST)) or link.query or link.fragment \
            or "?" in href or "#" in href or not link.path.startswith(prefix):
        raise SavingsFormatError(FORMAT_CHANGED, "Operation detail outside the saver's space")
    key = link.path[len(prefix):]
    if not _DEMAND_KEY.fullmatch(key):
        raise SavingsFormatError(FORMAT_CHANGED, "Operation detail outside the saver's space")
    return prefix + key


def external_id(employee_id: str, *parts: str) -> str:
    """Stable per line across syncs; hashed when the readable form would not fit."""
    readable = "_".join(("natixis", employee_id, *parts))
    if len(readable) <= 100:
        return readable
    return f"natixis_{employee_id}_{hashlib.sha256(readable.encode()).hexdigest()[:32]}"


def _fund_code(fund_id, codes: dict[int, str | None]) -> str | None:
    if not isinstance(fund_id, int):
        return None
    return codes.get(fund_id) or f"NIE-{fund_id}"


def _plan_id(employee_id: str, product: dict) -> str:
    return f"natixis_{employee_id}_{_text((product or {}).get('id'), 'Operation plan id')}"


def _buy(employee_id, demand_id, line, part, suffix, label, codes) -> dict | None:
    if part.get("status") not in (None, "INVESTED"):
        return None
    quantity = _decimal(part.get("units"), "Invested units")
    if quantity == 0:
        return None
    fees = _amount(part, "fees", "Investment fees", required=False)
    amount = _amount(part, "netInvested", "Invested amount", required=False)
    if amount is None:
        amount = _amount(part, "net", "Invested amount")
    return {
        "externalId": external_id(employee_id, demand_id, _text(line.get("id"), "Investment line id"), suffix),
        "planExternalId": _plan_id(employee_id, part.get("product") or line.get("product")),
        "date": _text(part.get("investmentDate") or line.get("investmentDate"), "Investment date"),
        "type": "BUY",
        "label": label,
        "fundLabel": _text(part.get("fundLabel") or line.get("fundLabel"), "Fund label"),
        "fundCode": _fund_code(part.get("fundId", line.get("fundId")), codes),
        "quantity": quantity,
        "unitValue": _decimal(part.get("unitValue"), "Unit value", required=False),
        "amountEur": amount,
        "feesEur": abs(fees) if fees is not None else None,
    }


def investment_operations(detail: dict, employee_id: str, codes: dict[int, str | None]) -> list[dict]:
    demand_id = _text(detail.get("id"), "Investment demand id")
    label = _text(detail.get("originLabel"), "Investment label", required=False) or "Versement"
    lines = detail.get("investments")
    if not isinstance(lines, list):
        raise SavingsFormatError(FORMAT_CHANGED, "Investment demand has no lines")
    operations = []
    for line in lines:
        if not isinstance(line, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "An investment line is not an object")
        if line.get("type") not in (None, "FUND"):
            raise SavingsFormatError(FORMAT_CHANGED, "An investment line is not a fund")
        own = _buy(employee_id, demand_id, line, line, "E", label, codes)
        if own:
            operations.append(own)
        top_up = line.get("additionalInvestment")
        if isinstance(top_up, dict):
            employer = _buy(employee_id, demand_id, line, top_up, "A", EMPLOYER_TOP_UP, codes)
            if employer:
                operations.append(employer)
    return operations


def payment_operations(detail: dict, employee_id: str, codes: dict[int, str | None]) -> list[dict]:
    demand_id = _text(detail.get("id"), "Payment demand id")
    label = PAYMENT_LABELS.get(detail.get("type"), DEFAULT_PAYMENT_LABEL)
    payments = detail.get("payments")
    if not isinstance(payments, list):
        raise SavingsFormatError(FORMAT_CHANGED, "Payment demand has no payments")
    operations = []
    for payment in payments:
        if not isinstance(payment, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "A payment is not an object")
        payment_id = _text(payment.get("id"), "Payment id")
        for product in payment.get("products") or []:
            if not isinstance(product, dict):
                raise SavingsFormatError(FORMAT_CHANGED, "A paid product is not an object")
            for option in product.get("investmentOptions") or []:
                if not isinstance(option, dict) or option.get("type") not in (None, "FUND"):
                    raise SavingsFormatError(FORMAT_CHANGED, "A paid line is not a fund")
                quantity = _decimal(option.get("units"), "Sold units")
                if quantity == 0:
                    continue
                disinvestment = option.get("disvestmentValue") if isinstance(option.get("disvestmentValue"), dict) else {}
                operations.append({
                    "externalId": external_id(employee_id, payment_id, _text(option.get("id"), "Paid line id")),
                    "planExternalId": _plan_id(employee_id, product),
                    "date": _text(disinvestment.get("date") or payment.get("date"), "Disinvestment date"),
                    "type": "SELL",
                    "label": label,
                    "fundLabel": _text(option.get("label"), "Fund label"),
                    "fundCode": _fund_code(option.get("fundId"), codes),
                    "quantity": abs(quantity),
                    "unitValue": _amount(disinvestment, "amount", "Disinvestment unit value", required=False),
                    "amountEur": abs(_amount(option, "gross", "Sold amount")),
                    "feesEur": None,
                })
    return operations


def operation_fund_ids(details: list[dict]) -> list[int]:
    """Funds the operations name, sold-out ones included, for their AMF codes."""
    seen: dict[int, None] = {}

    def visit(o):
        if isinstance(o, dict):
            if isinstance(o.get("fundId"), int):
                seen.setdefault(o["fundId"], None)
            for v in o.values():
                visit(v)
        elif isinstance(o, list):
            for v in o:
                visit(v)

    for detail in details:
        visit(detail)
    return list(seen)


def parse_operations(demands: list[dict], details: list[dict], employee_id: str,
                     codes: dict[int, str | None]) -> list[dict]:
    operations = []
    for demand, detail in zip(demands, details):
        if not isinstance(detail, dict):
            raise SavingsFormatError(FORMAT_CHANGED, "An operation detail is not an object")
        if demand["type"] == INVESTMENT:
            operations.extend(investment_operations(detail, employee_id, codes))
        else:
            operations.extend(payment_operations(detail, employee_id, codes))
    if len({op["externalId"] for op in operations}) != len(operations):
        raise SavingsFormatError(FORMAT_CHANGED, "Two operations share an id")
    for op in operations:
        if op["amountEur"] < Decimal(0) or op["quantity"] <= Decimal(0):
            raise SavingsFormatError(FORMAT_CHANGED, "An operation has a negative amount")
    return operations
