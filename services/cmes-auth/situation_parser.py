"""Parse the Crédit Mutuel Épargne Salariale "Situation financière détaillée" page.

Server-rendered by the Euro-Information framework. One accordion table per plan
(PEG, PEE, PERCOL...), captioned "Situation détaillée - <plan>":

    <table class="... tb_accordeon ...">
      <tr class="... ___TrExpander">
        <th class="rupture">Plan d'épargne groupe</th>
        <td>Disponible <span class="em">0,00 €</span></td>
        <td>Bloqué <span class="em">12 345,67 €</span></td>
        <td>Montant total estimé <span class="em">12 345,67 €</span></td>
      </tr>
      <tr><td><table class="eir_xs_listtable ...">     <- one row per fund
        <tr>
          <td><a class="es_fund-clickable">FUND NAME</a>
              <input type="submit" name="_FID_GoFundSummary_selectedFundIndex:0"></td>
          <td class="mntt"><span class="nowrap">1 234,56 €</span> ...</td>
          <td class="mntt"><span class="es_pvl pos">+ 12,34 %</span></td>
          ...

The page gives each fund's estimated value and its gain as a percentage only.
Units, unit value and ISIN live on the fund's own pages, reached by posting the
page form with the fund's `_FID_GoFundSummary_selectedFundIndex:<n>` button.

Every failure is explicit: a value that cannot be read fails the whole page
rather than reading as zero, and the funds must add up to the plan total.
"""

import re
from decimal import Decimal, InvalidOperation

from euro_information.forms import html_text as _text

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"
INCOMPLETE = "PORTFOLIO_INCOMPLETE"

MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")

# Plan label -> the plan family Picsou shows next to the account name.
_PLAN_KINDS: tuple[tuple[re.Pattern, str], ...] = (
    (re.compile(r"retraite", re.IGNORECASE), "PERCOL"),
    (re.compile(r"groupe", re.IGNORECASE), "PEG"),
    (re.compile(r"entreprise", re.IGNORECASE), "PEE"),
    (re.compile(r"courant bloqu", re.IGNORECASE), "CCB"),
)

_PLAN_TABLE_RE = re.compile(r'<table\b[^>]*class="[^"]*\btb_accordeon\b[^"]*"[^>]*>', re.IGNORECASE)
_TABLE_TAG_RE = re.compile(r"<(/?)table\b[^>]*>", re.IGNORECASE)
_PLAN_HEADER_RE = re.compile(r'<th\b[^>]*class="[^"]*\brupture\b[^"]*"[^>]*>(?P<label>.*?)</th>', re.DOTALL)
_PLAN_TOTAL_RE = re.compile(
    r"Montant total estim(?:é|&#233;|&eacute;)\s*</span>\s*<br\s*/?>\s*"
    r'<span\b[^>]*class="[^"]*\bem\b[^"]*"[^>]*>(?P<amount>.*?)</span>',
    re.DOTALL,
)
_FUND_TABLE_RE = re.compile(r'<table\b[^>]*class="[^"]*\beir_xs_listtable\b[^"]*"[^>]*>', re.IGNORECASE)
_ROW_RE = re.compile(r"<tr\b[^>]*>(?P<row>.*?)</tr>", re.DOTALL)
_FUND_NAME_RE = re.compile(r'<a\b[^>]*class="[^"]*\bes_fund-clickable\b[^"]*"[^>]*>(?P<name>.*?)</a>', re.DOTALL)
_FUND_BUTTON_RE = re.compile(r'name="(?P<button>_FID_GoFundSummary_selectedFundIndex:\d+)"')
_CELL_RE = re.compile(r"<td\b[^>]*>(?P<cell>.*?)</td>", re.DOTALL)
_VALUE_RE = re.compile(r'<span\b[^>]*class="[^"]*\bnowrap\b[^"]*"[^>]*>(?P<amount>[^<]*)', re.DOTALL)
_GAIN_RE = re.compile(r'<span\b[^>]*class="[^"]*\bes_pvl\b[^"]*"[^>]*>(?P<gain>[^<]*)', re.DOTALL)
_MONEY_RE = re.compile(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s]*(?:,\d+)?)\s*€$")
_PERCENT_RE = re.compile(r"^(?P<sign>[+-]?)\s*(?P<value>\d+(?:,\d+)?)\s*%$")


class SituationFormatError(Exception):
    """A page that cannot be trusted, carrying the stable code to surface."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


def _decimal(digits: str, sign: str) -> Decimal:
    try:
        value = Decimal(re.sub(r"\s", "", digits).replace(",", "."))
    except InvalidOperation as exc:
        raise SituationFormatError(FORMAT_CHANGED, "Unreadable number") from exc
    return -value if sign == "-" else value


def parse_money(raw: str) -> Decimal:
    """Read "12 345,67 €" (non-breaking spaces included) as a Decimal."""
    match = _MONEY_RE.match(_text(raw))
    if match is None:
        raise SituationFormatError(FORMAT_CHANGED, "Unreadable amount")
    return _decimal(match.group("value"), match.group("sign"))


def parse_percent(raw: str) -> Decimal:
    """Read "+ 12,34 %" or "-3,10 %" as a Decimal percentage."""
    match = _PERCENT_RE.match(_text(raw))
    if match is None:
        raise SituationFormatError(FORMAT_CHANGED, "Unreadable gain percentage")
    return _decimal(match.group("value"), match.group("sign"))


def plan_kind(label: str) -> str | None:
    for pattern, kind in _PLAN_KINDS:
        if pattern.search(label):
            return kind
    return None


def money_close(actual: Decimal, expected: Decimal) -> bool:
    tolerance = max(MONEY_ABSOLUTE_TOLERANCE, abs(expected) * MONEY_RELATIVE_TOLERANCE)
    return abs(actual - expected) <= tolerance


def _table_at(page: str, start: int) -> str:
    """Return the table opening at `start`, nested tables included."""
    depth = 0
    for tag in _TABLE_TAG_RE.finditer(page, start):
        depth += -1 if tag.group(1) else 1
        if depth == 0:
            return page[start:tag.end()]
    raise SituationFormatError(FORMAT_CHANGED, "Unterminated table")


def _parse_fund(row: str) -> dict | None:
    name = _FUND_NAME_RE.search(row)
    if name is None:
        return None
    button = _FUND_BUTTON_RE.search(row)
    cells = [cell.group("cell") for cell in _CELL_RE.finditer(row)]
    value = _VALUE_RE.search(cells[1]) if len(cells) > 1 else None
    gain = _GAIN_RE.search(cells[2]) if len(cells) > 2 else None
    if button is None or value is None or gain is None:
        raise SituationFormatError(FORMAT_CHANGED, "A fund row lacks its button, value or gain")
    return {
        "label": _text(name.group("name")),
        "button": button.group("button"),
        "valueEur": parse_money(value.group("amount")),
        "gainPercent": parse_percent(gain.group("gain")),
    }


def _parse_plan(table: str) -> dict:
    header = _PLAN_HEADER_RE.search(table)
    total = _PLAN_TOTAL_RE.search(table)
    fund_table = _FUND_TABLE_RE.search(table)
    if header is None or total is None or fund_table is None:
        raise SituationFormatError(FORMAT_CHANGED, "A plan lacks its label, total or fund list")
    label = _text(header.group("label"))
    funds = [
        fund
        for row in _ROW_RE.finditer(_table_at(table, fund_table.start()))
        if (fund := _parse_fund(row.group("row"))) is not None
    ]
    balance = parse_money(total.group("amount"))
    if not funds and balance != 0:
        raise SituationFormatError(INCOMPLETE, "A funded plan lists no fund")
    if not money_close(sum((fund["valueEur"] for fund in funds), Decimal("0")), balance):
        raise SituationFormatError(INCOMPLETE, "The funds do not add up to the plan total")
    return {"name": label, "planKind": plan_kind(label), "balanceEur": balance, "funds": funds}


_PROFILE_RE = re.compile(
    r'<p\b[^>]*class="[^"]*\bei_profil_entrep\b[^"]*"[^>]*>(?P<employer>.*?)<span>(?P<number>[^<]*)</span>',
    re.DOTALL,
)


def parse_profile(page: str) -> tuple[str | None, str]:
    """Return the employer and the saver's contract number from the page header.

    The number is what keeps two savers' plans apart in the external id; the
    employer only labels the account.
    """
    match = _PROFILE_RE.search(page)
    number = re.sub(r"\W", "", _text(match.group("number"))) if match else ""
    if not number:
        raise SituationFormatError(FORMAT_CHANGED, "No saver number in the page header")
    employer = _text(match.group("employer")) or None
    return employer, number


def parse_situation(page: str) -> list[dict]:
    """Return one entry per plan, each with its funds."""
    starts = [match.start() for match in _PLAN_TABLE_RE.finditer(page)]
    if not starts:
        raise SituationFormatError(FORMAT_CHANGED, "No plan table on the detailed situation page")
    plans = [_parse_plan(_table_at(page, start)) for start in starts]
    labels = [plan["name"] for plan in plans]
    if len(set(labels)) != len(labels):
        raise SituationFormatError(FORMAT_CHANGED, "The same plan is listed twice")
    return plans
