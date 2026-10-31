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
skipped only when their rows contain no balance data. A title alone can be a
contract link. An unrecognized category with a balance fails the whole page,
as does account data outside the contract tables or an unreadable account row
in a supported category. Silently dropping an account would hide an incomplete sync.
"""

import html as html_module
import re
from decimal import Decimal, InvalidOperation
from typing import Literal

AccountKind = Literal[
    "CHECKING", "SAVINGS", "LIVRET_A", "LDDS", "LEP", "LIVRET_JEUNE", "PEL", "CEL"
]

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"

CHECKING_CATEGORY = "comptes courants"
SAVINGS_CATEGORY = "livrets et comptes d'épargne"

_TABLE_RE = re.compile(
    r'<table\b[^>]*class="[^"]*\bei_comptescontrats\b[^"]*"[^>]*>(?P<body>.*?)</table>',
    re.DOTALL,
)
_CAPTION_RE = re.compile(r"<caption\b[^>]*>(?P<text>.*?)</caption>", re.DOTALL)
_CATEGORY_RE = re.compile(r"cat[ée]gorie\s*:\s*(?P<name>.+)$", re.IGNORECASE)
_ROW_RE = re.compile(r"<tr\b[^>]*>(?P<row>.*?)</tr>", re.DOTALL)
_TITLE_RE = re.compile(r'class="[^"]*\bei_sdsf_title\b[^"]*"[^>]*>(?P<title>.*?)</span>', re.DOTALL)
_NUMBER_RE = re.compile(r'class="[^"]*\bdoux\b[^"]*"[^>]*>(?P<number>.*?)</span>', re.DOTALL)
_AMOUNT_RE = re.compile(r'class="[^"]*\bei_sdsf_montant\b[^"]*"[^>]*>(?P<amount>.*?)</span>', re.DOTALL)
_TAG_RE = re.compile(r"<[^>]+>")
_MONEY_RE = re.compile(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s.]*(?:,\d+)?)\s*(?P<currency>[A-Z]{3}|€)$")
# Symbol-first amounts need cents so script replacement strings like "$1" are not money.
_BALANCE_RE = re.compile(
    r"\d[\d\s.,]*\s*(?:[A-Z]{3}\b|[€$£])|[€$£]\s*[+-]?\s*\d[\d\s.,]*[.,]\d{2}\b"
)

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


def account_type(category: str, label: str) -> AccountKind:
    if category == CHECKING_CATEGORY:
        return "CHECKING"
    upper = label.upper()
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


def _has_balance(fragment: str) -> bool:
    return bool(_AMOUNT_RE.search(fragment) or "ei_sdsf_solde" in fragment or _BALANCE_RE.search(_text(fragment)))


def _has_account_data(fragment: str) -> bool:
    return bool(_TITLE_RE.search(fragment) or _NUMBER_RE.search(fragment) or _has_balance(fragment))


def parse_accounts(page: str) -> tuple[list[dict], list[str]]:
    """Return the in-scope accounts and the names of the categories skipped."""
    tables = list(_TABLE_RE.finditer(page))
    if not tables:
        raise AccountsFormatError(FORMAT_CHANGED, "No contract table on the accounts page")
    if _has_account_data(_TABLE_RE.sub("", page)):
        raise AccountsFormatError(FORMAT_CHANGED, "Account data outside the contract tables")

    accounts: list[dict] = []
    skipped: list[str] = []
    seen: set[str] = set()
    for table in tables:
        body = table.group("body")
        category = _category(body)
        supported = category in (CHECKING_CATEGORY, SAVINGS_CATEGORY)
        for row in _ROW_RE.finditer(body):
            cells = row.group("row")
            title = _TITLE_RE.search(cells)
            number = _NUMBER_RE.search(cells)
            amount = _AMOUNT_RE.search(cells)
            if not supported:
                if _has_balance(cells):
                    label = _text(title.group("title")) if title else ""
                    product = f", product {label!r}" if label else ""
                    raise AccountsFormatError(
                        FORMAT_CHANGED,
                        f"An account table has an unrecognized category {category or 'unnamed'!r}{product}",
                    )
                continue
            if title is None and number is None and not _has_balance(cells):
                continue
            if title is None or number is None or amount is None:
                raise AccountsFormatError(FORMAT_CHANGED, "An account row has no title, number, or balance")
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
