import re
from decimal import Decimal, InvalidOperation
from typing import Any


class PortfolioFormatError(ValueError):
    """Raised when an upstream monetary field is present but unusable."""


_CURRENCY_CODE = r"(?:EUR|USD|GBP|CHF|JPY|CAD|AUD|NZD|SEK|NOK|DKK|GBX)"


def decimal_value(raw: Any, field: str = "value") -> Decimal:
    """Parse a required decimal without turning protocol drift into a false zero."""
    if raw is None or isinstance(raw, bool):
        raise PortfolioFormatError(f"Missing {field}")
    value = str(raw).strip().replace("\xa0", " ").replace("\u202f", " ")
    negative_parentheses = value.startswith("(") and value.endswith(")")
    if negative_parentheses:
        value = value[1:-1].strip()
    # Accept presentation-only currency/percentage affixes, but reject letters
    # embedded in the numeric value instead of silently deleting them (for
    # example, "1e3" must never become "13").
    value = re.sub(
        rf"^(?:{_CURRENCY_CODE}|[€$£¥])\s*",
        "",
        value,
        flags=re.IGNORECASE,
    )
    while True:
        stripped = re.sub(
            rf"\s*(?:{_CURRENCY_CODE}|[€$£¥%])$",
            "",
            value,
            flags=re.IGNORECASE,
        )
        if stripped == value:
            break
        value = stripped
    cleaned = value.replace(" ", "").replace("'", "")
    if negative_parentheses:
        cleaned = "-" + cleaned
    if not re.fullmatch(r"[+-]?[0-9][0-9,.-]*", cleaned):
        raise PortfolioFormatError(f"Invalid {field}")
    if not cleaned or cleaned in {"-", ".", ",", "-.", "-,"}:
        raise PortfolioFormatError(f"Missing {field}")
    if "," in cleaned and "." in cleaned:
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif cleaned.count(",") > 1:
        head, tail = cleaned.rsplit(",", 1)
        cleaned = head.replace(",", "") + "." + tail
    elif cleaned.count(".") > 1:
        head, tail = cleaned.rsplit(".", 1)
        cleaned = head.replace(".", "") + "." + tail
    else:
        cleaned = cleaned.replace(",", ".")
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise PortfolioFormatError(f"Invalid {field}") from exc


def optional_decimal_value(raw: Any, field: str = "value") -> Decimal | None:
    """Parse an optional decimal, while still rejecting malformed present values."""
    if raw is None or (isinstance(raw, str) and not raw.strip()):
        return None
    return decimal_value(raw, field)


def _currency_from_instrument(raw: str) -> str | None:
    match = re.search(r"(?:^|[-{])([A-Z]{3})(?:[-}]|$)", raw.upper())
    return match.group(1) if match else None


def unwrap_message(text: str) -> str | None:
    text = text.strip()
    if not text or text == "NULL" or "message='NULL'" in text:
        return None
    if text.startswith("<"):
        return None
    match = re.search(r"message='(.*)'\s*;?\s*$", text, re.DOTALL)
    return match.group(1) if match else None


def parse_portfolio(
    text: str,
    account_index: int,
    account_id: str | None = None,
    account_name: str | None = None,
) -> dict | None:
    """Parse Bourse Direct's legacy real-time stream into a stable DTO."""
    message = unwrap_message(text)
    if not message:
        return None
    chunks = message.split("|")
    portfolio = chunks[0].split("{")
    if len(portfolio) < 4:
        return None
    external_id = account_id or (portfolio[11].strip() if len(portfolio) > 11 else "") or f"bd_{account_index}"
    name = account_name or (portfolio[0].strip() if portfolio else "") or f"Bourse Direct {account_index}"
    try:
        cash = decimal_value(portfolio[3], "cash balance")
        total = decimal_value(portfolio[1], "account total")
    except PortfolioFormatError:
        return None

    positions: list[dict] = []
    expecting_position = True
    for chunk in chunks[1:]:
        if chunk == "1":
            expecting_position = True
            continue
        if not expecting_position:
            continue
        fields = chunk.split("#")
        if len(fields) < 6:
            return None
        label = fields[0].strip()
        if not label or label.lower() == "total":
            continue
        raw_instrument = fields[1].strip()
        instrument = raw_instrument.split("{")[0].strip() or label
        try:
            quantity = decimal_value(fields[2], "position quantity")
            native_buying_price = optional_decimal_value(fields[3], "position buying price")
            current_price = optional_decimal_value(fields[4], "position current price")
            current_value_eur = optional_decimal_value(fields[5], "position EUR valuation")
        except PortfolioFormatError:
            return None
        if quantity == 0:
            expecting_position = False
            continue
        quote_currency = _currency_from_instrument(raw_instrument)
        buying_price_eur = native_buying_price if quote_currency == "EUR" else None
        if current_price is not None and quote_currency is None:
            current_price = None
        if current_value_eur is None and current_price is not None and quote_currency == "EUR":
            current_value_eur = quantity * current_price
        if current_value_eur is None:
            return None
        pnl_eur = (
            current_value_eur - (quantity * buying_price_eur)
            if buying_price_eur is not None
            else None
        )
        positions.append({
            "isin": instrument if re.fullmatch(r"[A-Z]{2}[A-Z0-9]{10}", instrument) else None,
            "symbol": instrument,
            "label": label,
            "quantity": str(quantity),
            "buyingPriceEur": str(buying_price_eur) if buying_price_eur is not None else None,
            "currentPrice": str(current_price) if current_price is not None else None,
            "quoteCurrency": quote_currency,
            "currentValueEur": str(current_value_eur),
            "pnlEur": str(pnl_eur) if pnl_eur is not None else None,
        })
        expecting_position = False

    securities = sum(
        (Decimal(position["currentValueEur"]) for position in positions),
        Decimal("0"),
    )
    expected_securities = total - cash
    tolerance = max(Decimal("0.05"), abs(expected_securities) * Decimal("0.001"))
    if abs(securities - expected_securities) > tolerance:
        return None

    account_type = "PEA" if "pea" in name.lower() else "COMPTE_TITRES"
    return {
        "externalId": external_id,
        "name": name,
        "type": account_type,
        "balanceEur": str(total),
        "cashBalance": str(cash),
        "positions": positions,
        "snapshotComplete": True,
    }
