"""Parse a fund's "Mon épargne" tab (`supports/epargne-sur-le-support.html`).

The tab holds a "Synthèse" table of `<th>label</th><td>value</td>` rows:

    Montant estimé                        22 427,72 €
    Dont plus ou moins value              +4 171,84 € (soit +22,85 %)
    Nombre de parts                       917,1619
    Valeur de la part au 22/10/2026       24,4534 €

followed by a per-maturity breakdown that is not needed here. The tab is one of
the fund's tabs (`ul.ei_newtabs`), next to "Synthèse"
(`supports/synthese-du-support.html`), which is where a fund button on the
detailed situation page lands and which carries the fund's code.
"""

import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from situation_parser import FORMAT_CHANGED, SituationFormatError, _text, money_close, parse_money

MY_SAVINGS_PATH_MARKER = "epargne-sur-le-support"

_ROW_RE = re.compile(r"<tr\b[^>]*>\s*<th\b[^>]*>(?P<label>.*?)</th>\s*<td\b[^>]*>(?P<value>.*?)</td>", re.DOTALL)
_GAIN_RE = re.compile(r'<span\b[^>]*class="[^"]*\bes_pvl\b[^"]*"[^>]*>(?P<gain>[^<]*)</span>')
_TABS_RE = re.compile(r'<ul\b[^>]*class="[^"]*\bei_newtabs\b[^"]*"[^>]*>(?P<tabs>.*?)</ul>', re.DOTALL)
_TAB_LINK_RE = re.compile(r'<a\b[^>]*href="(?P<href>[^"]+)"[^>]*>(?P<text>.*?)</a>', re.DOTALL)
_PRICE_DATE_RE = re.compile(r"(?P<date>\d{2}/\d{2}/\d{4})")
_ISIN_RE = re.compile(r"\b(?P<isin>[A-Z]{2}[A-Z0-9]{9}\d)\b")


def _decimal(raw: str) -> Decimal:
    digits = re.sub(r"\s", "", _text(raw)).replace(",", ".")
    try:
        value = Decimal(digits)
    except InvalidOperation as exc:
        raise SituationFormatError(FORMAT_CHANGED, "Unreadable number of units") from exc
    if not value.is_finite():
        raise SituationFormatError(FORMAT_CHANGED, "Unreadable number of units")
    return value


def my_savings_tab(page: str) -> str | None:
    """The href of the fund's "Mon épargne" tab, read from the fund's tab list only.

    The site header also links "Mon épargne", to the whole savings section:
    looking anywhere else than the tab list would follow that one.
    """
    tabs = _TABS_RE.search(page)
    if tabs is None:
        return None
    for link in _TAB_LINK_RE.finditer(tabs.group("tabs")):
        text = _text(link.group("text")).lower()
        if text in ("mon épargne", "mes avoirs"):
            return link.group("href").replace("&amp;", "&")
    return None


def parse_fund_identity(page: str) -> tuple[str | None, str | None]:
    """The fund's ISIN and its own code from the "Synthèse" tab.

    Employer funds (FCPE) usually show only the manager's "Code du support",
    no ISIN; both are optional, the label stays the fallback identity.
    """
    isin = code = None
    for row in _ROW_RE.finditer(page):
        label = _text(row.group("label")).lower()
        value = _text(row.group("value"))
        if "isin" in label:
            match = _ISIN_RE.search(value)
            isin = match.group("isin") if match else None
        elif label == "code du support" and re.fullmatch(r"[\w-]{1,20}", value):
            code = value
    return isin, code


def parse_my_savings(page: str) -> dict:
    rows = {_text(row.group("label")): row.group("value") for row in _ROW_RE.finditer(page)}

    def value_of(prefix: str) -> tuple[str, str]:
        for label, value in rows.items():
            if label.startswith(prefix):
                return label, value
        raise SituationFormatError(FORMAT_CHANGED, f"No '{prefix}' row on the fund page")

    _, amount = value_of("Montant estimé")
    _, gain_cell = value_of("Dont plus ou moins value")
    _, units = value_of("Nombre de parts")
    price_label, price = value_of("Valeur de la part")

    gain = _GAIN_RE.search(gain_cell)
    if gain is None:
        raise SituationFormatError(FORMAT_CHANGED, "No gain amount on the fund page")
    price_date = _PRICE_DATE_RE.search(price_label)

    value_eur = parse_money(amount)
    quantity = _decimal(units)
    unit_value = parse_money(price)
    # The site rounds the value to the cent from units and price: a figure that
    # does not follow from them was read off the wrong row.
    if not money_close(quantity * unit_value, value_eur):
        raise SituationFormatError(FORMAT_CHANGED, "Units times unit value do not give the fund value")
    return {
        "valueEur": value_eur,
        "pnlEur": parse_money(gain.group("gain")),
        "quantity": quantity,
        "unitValue": unit_value,
        "priceDate": _parse_date(price_date.group("date")) if price_date else None,
    }


def _parse_date(raw: str) -> date | None:
    try:
        return datetime.strptime(raw, "%d/%m/%Y").date()
    except ValueError:
        return None
