"""Parse the CIC "Comptes et contrats" page into account balances.

The page (`/fr/banque/comptes-et-contrats.html`) groups contracts into one
table per category, each with an invisible caption naming it:

    <table class="... ei_comptescontrats ...">
      <caption>Liste des comptes liés à la catégorie : Comptes courants</caption>
      <tr>
        <td><a href="...mouvements.html?webid=...">
          <span class="ei_sdsf_title">COMPTE COURANT</span>
          <span class="nowrap">M JOHN DOE</span>
          <span class="nowrap doux">10000 000123456 01</span></a></td>
        <td class="d nowrap"></td>
        <td class="d nowrap ei_sdsf_solde">
          <span class="ei_sdsf_montant pos">+1 234,56 EUR</span></td>
        ...

Only current accounts and savings accounts are in scope. Other tables are
skipped only when their rows contain no account data. An unrecognized category
with account data fails the whole page, as does a balance-bearing row that
cannot be read. Silently dropping an account would hide an incomplete sync.
"""

import html as html_module
import re
import unicodedata
from decimal import Decimal, InvalidOperation
from typing import Literal

AccountKind = Literal[
    "CHECKING", "SAVINGS", "LIVRET_A", "LDDS", "LEP", "LIVRET_JEUNE", "PEL", "CEL"
]

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"

CHECKING_CATEGORY = "comptes courants"
SAVINGS_CATEGORY = "livrets et comptes d'épargne"

_TABLE_RE = re.compile(
    r'<table\b[^>]*class="[^"]*\bei_comptescontrats\b[^"]*"[^>]*>(?P<body>.*?)(?P<closing_tag></table>|\Z)',
    re.DOTALL,
)
_UNPARSED_CONTAINER_RE = re.compile(
    r"<(?P<tag>table|tr)\b[^>]*>(?P<body>.*?)(?:</(?P=tag)>|\Z)",
    re.DOTALL | re.IGNORECASE,
)
_CAPTION_RE = re.compile(r"<caption\b[^>]*>(?P<text>.*?)</caption>", re.DOTALL)
_CATEGORY_RE = re.compile(r"cat[ée]gorie\s*:\s*(?P<name>.+)$", re.IGNORECASE)
_ROW_RE = re.compile(r"<tr\b[^>]*>(?P<row>.*?)</tr>", re.DOTALL | re.IGNORECASE)
_TITLE_RE = re.compile(r'class="[^"]*\bei_sdsf_title\b[^"]*"[^>]*>(?P<title>.*?)</span>', re.DOTALL)
_NUMBER_RE = re.compile(r'class="[^"]*\bdoux\b[^"]*"[^>]*>(?P<number>.*?)</span>', re.DOTALL)
_AMOUNT_RE = re.compile(r'class="[^"]*\bei_sdsf_montant\b[^"]*"[^>]*>(?P<amount>.*?)</span>', re.DOTALL)
_ACCOUNT_MARKER_RE = re.compile(r'class="[^"]*\b(?:ei_sdsf_title|doux|ei_sdsf_montant)\b')
_TAG_RE = re.compile(r"<[^>]+>")
_MONEY_RE = re.compile(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s.]*(?:,\d+)?)\s*(?P<currency>[A-Z]{3}|€)$")
_BALANCE_RE = re.compile(r"\d[\d\s.,]*\s*(?:[A-Z]{3}\b|€)")

# Ordered: the first match wins, so the specific products come before the
# generic "livret".
_SAVINGS_TYPES: tuple[tuple[re.Pattern, AccountKind], ...] = (
    (re.compile(r"\bLIVRET A\b"), "LIVRET_A"),
    (re.compile(r"\bLDDS?\b|DEVELOPPEMENT DURABLE"), "LDDS"),
    (re.compile(r"\bLEP\b|EPARGNE POPULAIRE"), "LEP"),
    (re.compile(r"LIVRET JEUNE"), "LIVRET_JEUNE"),
    (re.compile(r"PLAN (D')?EPARGNE LOGEMENT|\bPEL\b"), "PEL"),
    (re.compile(r"COMPTE (D')?EPARGNE LOGEMENT|\bCEL\b"), "CEL"),
)


class AccountsFormatError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def _text(fragment: str) -> str:
    return " ".join(html_module.unescape(_TAG_RE.sub(" ", fragment)).split())


def parse_amount(raw: str) -> Decimal:
    """Read "+1 234,56 EUR" (non-breaking spaces included) as a Decimal."""
    match = _MONEY_RE.match(_text(raw).replace(" ", " ").replace(" ", " "))
    if match is None:
        raise AccountsFormatError(FORMAT_CHANGED, "Unreadable account balance")
    if match.group("currency") not in ("EUR", "€"):
        raise AccountsFormatError(FORMAT_CHANGED, "Balance is not in euros")
    digits = re.sub(r"[\s.]", "", match.group("value")).replace(",", ".")
    try:
        value = Decimal(digits)
    except InvalidOperation as exc:
        raise AccountsFormatError(FORMAT_CHANGED, "Unreadable account balance") from exc
    return -value if match.group("sign") == "-" else value


def _fold(label: str) -> str:
    """Upper case without diacritics or typographic apostrophes: "Plan d’Épargne" -> "PLAN D'EPARGNE"."""
    decomposed = unicodedata.normalize("NFKD", label.replace("\u2019", "'"))
    return "".join(char for char in decomposed if not unicodedata.combining(char)).upper()


def account_type(category: str, label: str) -> AccountKind:
    if category == CHECKING_CATEGORY:
        return "CHECKING"
    upper = _fold(label)
    for pattern, kind in _SAVINGS_TYPES:
        if pattern.search(upper):
            return kind
    return "SAVINGS"


def _category(table: str) -> str:
    caption = _CAPTION_RE.search(table)
    if caption is None:
        return ""
    match = _CATEGORY_RE.search(_text(caption.group("text")))
    return match.group("name").strip().lower().replace("’", "'") if match else ""


def _has_account_data(fragment: str) -> bool:
    return bool(
        _ACCOUNT_MARKER_RE.search(fragment)
        or "ei_sdsf_solde" in fragment or _BALANCE_RE.search(_text(fragment))
    )


def parse_accounts(page: str) -> tuple[list[dict], list[str]]:
    """Return the in-scope accounts and the names of the categories skipped."""
    tables = list(_TABLE_RE.finditer(page))
    if not tables:
        raise AccountsFormatError(FORMAT_CHANGED, "No contract table on the accounts page")

    # A certified partial page would zero accounts the backend no longer sees.
    if any(not table.group("closing_tag") for table in tables):
        raise AccountsFormatError(FORMAT_CHANGED, "Unterminated contract table on the accounts page")
    remainder = _TABLE_RE.sub("", page)
    if any(_has_account_data(match.group("body")) for match in _UNPARSED_CONTAINER_RE.finditer(remainder)):
        raise AccountsFormatError(FORMAT_CHANGED, "Account data outside a recognized contract table")

    accounts: list[dict] = []
    skipped: list[str] = []
    seen: set[str] = set()
    for table in tables:
        body = table.group("body")
        category = _category(body)
        supported = category in (CHECKING_CATEGORY, SAVINGS_CATEGORY)
        if _has_account_data(_ROW_RE.sub("", body)):
            raise AccountsFormatError(FORMAT_CHANGED, "Account data outside a recognized account row")
        for row in _ROW_RE.finditer(body):
            cells = row.group("row")
            title = _TITLE_RE.search(cells)
            number = _NUMBER_RE.search(cells)
            amount = _AMOUNT_RE.search(cells)
            if not _has_account_data(cells):
                continue
            if not supported:
                raise AccountsFormatError(FORMAT_CHANGED, "An account table has an unrecognized category")
            if (title is None or number is None or amount is None
                    or any(len(pattern.findall(cells)) > 1 for pattern in (_TITLE_RE, _NUMBER_RE, _AMOUNT_RE))):
                raise AccountsFormatError(FORMAT_CHANGED, "An account row has no unique title, number, or balance")
            account_id = re.sub(r"\D", "", _text(number.group("number")))
            if not account_id:
                raise AccountsFormatError(FORMAT_CHANGED, "An account row has an empty number")
            if account_id in seen:
                raise AccountsFormatError(FORMAT_CHANGED, "The same account is listed twice")
            seen.add(account_id)
            label = _text(title.group("title"))
            if not label:
                raise AccountsFormatError(FORMAT_CHANGED, "An account row has an empty title")
            accounts.append({
                "id": account_id,
                "name": label.title(),
                "type": account_type(category, label),
                "balanceEur": parse_amount(amount.group("amount")),
            })
        if not supported:
            skipped.append(category or "unnamed")
    return accounts, skipped
