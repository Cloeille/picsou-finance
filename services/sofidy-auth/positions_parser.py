"""Reads the Sofidy Espace Associé portfolio table into a typed snapshot.

`3,clients.html` is server-rendered PHP: the fund table, the totals row and each
fund's `Code_Produit` are all in the initial HTML, so no browser and no
JavaScript evaluation is involved. What the page gives us is a `Valeur unitaire`
column, which its own footnote defines as the withdrawal price for a
capital-variable SCPI -- that is the figure a SCPI share is worth, and the one
the account balance is built from.

The portal is the single source of truth for the valuation, so this module never
derives one: `totalEur` is read from the row and used to check the snapshot, not
recomputed from `shareCount × price` and written back as if it were Sofidy's.
"""

import html as html_module
import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Any

MONEY_QUANT = Decimal("0.01")

_ROW_RE = re.compile(r"<tr\b[^>]*>(.*?)</tr>", re.IGNORECASE | re.DOTALL)
_CELL_RE = re.compile(r"<td[^>]*>(.*?)</td>", re.IGNORECASE | re.DOTALL)
_TAG_RE = re.compile(r"<[^>]+>")
_CODE_RE = re.compile(r"Code_Produit=([A-Za-z0-9_-]+)")
_VALUATION_DATE_RE = re.compile(
    r"Portefeuille\s+SCPI\s+au\s+(\d{2}/\d{2}/\d{4})", re.IGNORECASE
)
_TOTAL_RE = re.compile(r"<td>\s*Total\s*</td>", re.IGNORECASE)
_LOGIN_FORM_MARKER = "connexion_espace_partenaire"
_HEADER_RE = re.compile(r"Nombre\s+de\s+parts", re.IGNORECASE)


class PositionsFormatError(Exception):
    """The page is not a portfolio this parser can read.

    `code` is a stable machine-readable reason, surfaced verbatim as the
    sidecar's HTTP detail so the adapter can translate it.
    """

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


@dataclass(frozen=True)
class Holding:
    fund_code: str
    label: str
    share_count: Decimal
    withdrawal_price_eur: Decimal | None
    total_eur: Decimal


def _text(fragment: str) -> str:
    return " ".join(_TAG_RE.sub(" ", html_module.unescape(fragment)).split())


def _cell_texts(row_html: str) -> list[str]:
    return [_text(cell) for cell in _CELL_RE.findall(row_html)]


def _parse_decimal(raw: str, field: str) -> Decimal:
    """Parses a Sofidy number: dot decimal, no thousands separator.

    The page styles numbers with a `sepmillier` class, which invites reading them
    as French-formatted, but the markup carries a bare dot and no separator --
    `2.00000` is two parts to five decimals, not two thousand. A value the
    portal did not render comes back as an empty cell, never as a zero.
    """
    value = raw.strip().replace("€", "").replace(" ", "").replace(" ", "")
    if not value:
        raise PositionsFormatError("INVALID_DATA", f"{field} is empty")
    # A French-formatted figure would carry a comma; the portal does not emit
    # one, and accepting it would silently turn `1,234` into `1.234`.
    if "," in value:
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED", f"{field} is comma-formatted")
    try:
        number = Decimal(value)
    except InvalidOperation as exc:
        raise PositionsFormatError("INVALID_DATA", f"{field} is not a number") from exc
    if not number.is_finite():
        raise PositionsFormatError("INVALID_DATA", f"{field} is not finite")
    return number


def _parse_optional_price(raw: str) -> Decimal | None:
    """A missing unit value is a real state, not a parse failure.

    A fund whose withdrawal price the portal has not published yet still holds a
    known number of shares. Returning None lets the caller write the quantity and
    leave the balance alone, which is what a manual entry does.

    The empty cell still carries its currency mark, so the cell reads as "€"
    rather than as nothing; a cell with no digit in it is the missing value, and
    "0.00" is a price rather than an absence.
    """
    if not re.search(r"\d", raw):
        return None
    return _parse_decimal(raw, "withdrawal price")


def _parse_holding(row_html: str) -> Holding:
    cells = _cell_texts(row_html)
    if len(cells) < 5:
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED", "portfolio row has too few cells")
    label, parts, _tenure, unit_value, total = cells[:5]
    if not label:
        raise PositionsFormatError("INVALID_DATA", "fund label is empty")
    code_match = _CODE_RE.search(row_html)
    if not code_match:
        # Without the product code the row cannot be matched to an account, and
        # a snapshot that silently drops unmatchable rows would understate the
        # portfolio.
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED", "fund row carries no Code_Produit")
    return Holding(
        fund_code=code_match.group(1),
        label=label,
        share_count=_parse_decimal(parts, "share count"),
        withdrawal_price_eur=_parse_optional_price(unit_value),
        total_eur=_parse_decimal(total, "total"),
    )


def _parse_valuation_date(html: str) -> str | None:
    match = _VALUATION_DATE_RE.search(html)
    if not match:
        return None
    day, month, year = match.group(1).split("/")
    return f"{year}-{month}-{day}"


def parse_portfolio(html: str) -> dict[str, Any]:
    """Turns the portfolio page into a snapshot, or refuses it.

    Two refusals matter more than a successful parse. An incomplete snapshot --
    rows whose parts or totals do not add up to the row Sofidy itself prints --
    is worse than no sync at all, because a partial portfolio still writes
    balances and looks correct. And a page that is really the login form means
    the session died, which is `SESSION_EXPIRED`, never an empty portfolio.
    """
    if _LOGIN_FORM_MARKER in html:
        raise PositionsFormatError("SESSION_EXPIRED", "page is the login form")
    if not _HEADER_RE.search(html):
        raise PositionsFormatError("UPSTREAM_FORMAT_CHANGED", "no portfolio table on the page")

    # Every row of the table is a candidate, not only the ones that carry a
    # product code: a fund row whose "Rapport détaillé" link disappeared must be
    # refused below, not skipped here, or a refonte of the page would read as a
    # smaller portfolio that still adds up.
    candidates = []
    for row in _ROW_RE.findall(html):
        cells = _cell_texts(row)
        if not cells:
            continue
        if cells[0].strip().lower() == "total":
            continue
        if len(cells) >= 5:
            candidates.append(row)

    if not candidates:
        if _declared_total(html) != 0:
            raise PositionsFormatError("PORTFOLIO_INCOMPLETE", "no funds but a nonzero total")
        # A holder with no fund still gets the totals row. That is a real,
        # complete, empty portfolio -- an account whose funds were all sold.
        return {
            "currency": "EUR",
            "totalEur": Decimal("0"),
            "valuationDate": _parse_valuation_date(html),
            "holdings": [],
            "snapshotComplete": True,
        }

    holdings = [_parse_holding(row) for row in candidates]
    codes = [h.fund_code for h in holdings]
    duplicates = {code for code in codes if codes.count(code) > 1}
    if duplicates:
        # Two rows sharing a product code cannot both be matched to one account;
        # picking either would write one holding over the other.
        raise PositionsFormatError(
            "UPSTREAM_FORMAT_CHANGED",
            f"duplicate fund codes: {', '.join(sorted(duplicates))}",
        )

    _check_totals(html, holdings)
    return {
        "currency": "EUR",
        "totalEur": _declared_total(html),
        "valuationDate": _parse_valuation_date(html),
        "holdings": [
            {
                "fundCode": h.fund_code,
                "label": h.label,
                "shareCount": h.share_count,
                "withdrawalPriceEur": h.withdrawal_price_eur,
                "totalEur": h.total_eur,
                "snapshotComplete": True,
            }
            for h in holdings
        ],
        "snapshotComplete": True,
    }


def _declared_total(html: str) -> Decimal:
    """The `Total` row's own value, which a snapshot has to reconcile against.

    Every failure mode raises rather than returning None. An unreadable total
    used to be read as "nothing to check", which turned the one guard that
    catches a partial read off: a refonte that dropped the funds and reformatted
    the total would pass a smaller portfolio as complete, and the sync would
    write the smaller balances.
    """
    total_row = re.search(
        r"<td>\s*Total\s*</td>(.*?)</tr>", html, re.IGNORECASE | re.DOTALL
    )
    if not total_row:
        raise PositionsFormatError(
            "UPSTREAM_FORMAT_CHANGED",
            "the portfolio page carries fund rows but no total row",
        )
    cells = _cell_texts(total_row.group(1))
    values = [c for c in cells if c.strip()]
    if len(values) < 2:
        raise PositionsFormatError(
            "UPSTREAM_FORMAT_CHANGED", "the portfolio total row has no value"
        )
    return _parse_decimal(values[-1], "total row")


def _check_totals(html: str, holdings: list[Holding]) -> None:
    """Refuses a snapshot that does not add up to the row Sofidy prints.

    A dropped row is invisible in the payload -- every holding present looks
    perfectly valid -- so the total row is the only place a partial read shows
    up. Comparing it is what keeps a refonte of the page from quietly halving
    somebody's portfolio.
    """
    declared = _declared_total(html)
    computed = sum((h.total_eur for h in holdings), Decimal("0")).quantize(MONEY_QUANT)
    if abs(computed - declared) > MONEY_QUANT:
        raise PositionsFormatError(
            "PORTFOLIO_INCOMPLETE",
            f"funds total {computed} but the page declares {declared}",
        )
