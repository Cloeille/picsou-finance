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
skipped only when their rows contain no account-number markers or balance data.
A title alone can be a contract link. An unrecognized category with a balance fails the whole page,
as does account data in unrecognized table or row blocks or an unreadable
account row in a supported category. Silently dropping an account would hide an incomplete sync.
"""

import re
import unicodedata
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from typing import Literal

AccountKind = Literal[
    "CHECKING", "SAVINGS", "LIVRET_A", "LDDS", "LEP", "LIVRET_JEUNE", "PEL", "CEL"
]

FORMAT_CHANGED = "UPSTREAM_FORMAT_CHANGED"

CHECKING_CATEGORY = "comptes courants"
SAVINGS_CATEGORY = "livrets et comptes d'épargne"

_DOCUMENT_END_RE = re.compile(r"</html\s*>\s*\Z", re.IGNORECASE)
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
_SCRIPT_STYLE_RE = re.compile(
    r"<(?P<tag>script|style)\b[^>]*>.*?</(?P=tag)\s*>",
    re.IGNORECASE | re.DOTALL,
)
_MONEY_RE = re.compile(r"^(?P<sign>[+-]?)\s*(?P<value>\d[\d\s.]*(?:,\d+)?)\s*(?P<currency>[A-Z]{3}|€)$")
# ISO 4217 List One (SIX, 2026-09-17). Words such as "CIC" are not currencies.
_CURRENCY_CODES = frozenset((
    "AED AFN ALL AMD AOA ARS AUD AWG AZN BAM BBD BDT BHD BIF BMD BND BOB BOV BRL BSD "
    "BTN BWP BYN BZD CAD CDF CHE CHF CHW CLF CLP CNY COP COU CRC CUP CVE CZK DJF DKK "
    "DOP DZD EGP ERN ETB EUR FJD FKP GBP GEL GHS GIP GMD GNF GTQ GYD HKD HNL HTG HUF "
    "IDR ILS INR IQD IRR ISK JMD JOD JPY KES KGS KHR KMF KPW KRW KWD KYD KZT LAK LBP "
    "LKR LRD LSL LYD MAD MDL MGA MKD MMK MNT MOP MRU MUR MVR MWK MXN MXV MYR MZN NAD "
    "NGN NIO NOK NPR NZD OMR PAB PEN PGK PHP PKR PLN PYG QAR RON RSD RUB RWF SAR SBD "
    "SCR SDG SEK SGD SHP SLE SOS SRD SSP STN SVC SYP SZL THB TJS TMT TND TOP TRY TTD "
    "TWD TZS UAH UGX USD USN UYI UYU UYW UZS VED VES VND VUV WST XAD XAF XAG XAU XBA "
    "XBB XBC XBD XCD XCG XDR XOF XPD XPF XPT XSU XTS XUA XXX YER ZAR ZMW ZWG"
).split())
# Consume each numeric run once, even when it has no currency. Optional currency
# tokens cannot force retries at every digit of a long nonmonetary run.
_BALANCE_RE = re.compile(
    r"(?:(?P<prefix>\b[A-Z]{3}\b|[€$£])\s*+[+-]?\s*+)?"
    r"(?P<number>\d[\d\s.,]*+)(?P<suffix>[A-Z]{3}\b|[€$£])?",
    re.IGNORECASE,
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


class _ClassMarkerParser(HTMLParser):
    def __init__(self, markers: set[str]):
        super().__init__()
        self.markers = markers
        self.found = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        classes = next((value for name, value in attrs if name == "class"), None)
        self.found |= bool(classes and self.markers.intersection(classes.split()))


class _TextParser(HTMLParser):
    def __init__(self, *, ignore_titles: bool = False):
        super().__init__()
        self.parts: list[str] = []
        self.ignored_tag: str | None = None
        self.ignore_titles = ignore_titles
        self.title_depth = 0
        self.in_balance_cell = False
        self.has_balance_text = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        classes = next((value for name, value in attrs if name == "class"), None)
        if tag in ("script", "style"):
            self.ignored_tag = tag
        if tag in ("td", "th"):
            self.in_balance_cell = bool(classes and "ei_sdsf_solde" in classes.split())
        if tag == "span":
            if self.title_depth or (self.ignore_titles and classes and "ei_sdsf_title" in classes.split()):
                self.title_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag in ("td", "th"):
            self.in_balance_cell = False
        if tag == self.ignored_tag:
            self.ignored_tag = None
        if tag == "span" and self.title_depth:
            self.title_depth -= 1

    def handle_data(self, data: str) -> None:
        if self.ignored_tag is None and self.in_balance_cell and data.strip():
            self.has_balance_text = True
        if self.ignored_tag is None and not self.title_depth:
            self.parts.append(data)


def _text(fragment: str, *, ignore_titles: bool = False) -> str:
    parser = _TextParser(ignore_titles=ignore_titles)
    parser.feed(fragment)
    parser.close()
    return " ".join(" ".join(parser.parts).split())


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


def _has_class_marker(fragment: str, markers: set[str]) -> bool:
    marker = _ClassMarkerParser(markers)
    marker.feed(fragment)
    return marker.found


def _has_money(text: str, *, explicit: bool = False) -> bool:
    for match in _BALANCE_RE.finditer(text):
        currencies = (match.group("prefix"), match.group("suffix"))
        if not any(token and (token in "€$£" or token.upper() in _CURRENCY_CODES)
                   for token in currencies):
            continue
        # A title can contain a year and a currency ("Contrat 2024 USD").
        # Decimal punctuation or a symbol distinguishes money inside a title.
        if (not explicit or any(token in ("€", "$", "£") for token in currencies)
                or re.search(r"[.,]\d", match.group("number"))):
            return True
    return False


def _has_balance(fragment: str) -> bool:
    # Number markers and nonempty balance cells must fail closed even when the
    # amount format changed. Empty cells still occur beside title-only links.
    if _has_class_marker(fragment, {"ei_sdsf_montant", "doux"}):
        return True
    parser = _TextParser(ignore_titles=True)
    parser.feed(fragment)
    parser.close()
    return (parser.has_balance_text or _has_money(" ".join(parser.parts))
            or _has_money(_text(fragment), explicit=True))


def _has_account_data(fragment: str) -> bool:
    return _has_class_marker(fragment, {"ei_sdsf_title", "doux", "ei_sdsf_solde"}) or _has_balance(fragment)


# What the backend calls each in-scope table: it closes an account no longer
# listed only when the table of its category was read.
CATEGORY_GROUPS = {CHECKING_CATEGORY: "CHECKING", SAVINGS_CATEGORY: "SAVINGS"}


def categories_read(page: str) -> list[str]:
    """The in-scope tables present on the page, an empty one included, as CHECKING / SAVINGS.

    A table with no row says the customer holds no account of that kind any more;
    a table absent from the page says nothing, so the backend leaves that
    category's accounts as they were.
    """
    page = _SCRIPT_STYLE_RE.sub(" ", page)
    found = {_category(table.group("body")) for table in _TABLE_RE.finditer(page)}
    return [group for name, group in CATEGORY_GROUPS.items() if name in found]


def parse_accounts(page: str) -> tuple[list[dict], list[str]]:
    """Return the in-scope accounts and the names of the categories skipped."""
    if _DOCUMENT_END_RE.search(page) is None:
        raise AccountsFormatError(FORMAT_CHANGED, "Incomplete accounts page")

    page = _SCRIPT_STYLE_RE.sub(" ", page)
    tables = list(_TABLE_RE.finditer(page))
    if not tables:
        raise AccountsFormatError(FORMAT_CHANGED, "No contract table on the accounts page")
    # A certified partial page would zero accounts the backend no longer sees.
    if any(not table.group("closing_tag") for table in tables):
        raise AccountsFormatError(FORMAT_CHANGED, "Unterminated contract table on the accounts page")
    remainder = _TABLE_RE.sub("", page)
    if any(_has_account_data(match.group("body")) for match in _UNPARSED_CONTAINER_RE.finditer(remainder)):
        raise AccountsFormatError(FORMAT_CHANGED, "Account data outside the contract tables")

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
            if not supported:
                # A title without an account number or balance can be an offer link.
                if _has_balance(cells):
                    label = _text(title.group("title"))[:60] if title else ""
                    product = f", product {label!r}" if label else ""
                    raise AccountsFormatError(
                        FORMAT_CHANGED,
                        f"An account table has an unrecognized category {category[:60] or 'unnamed'!r}{product}",
                    )
                continue
            if not _has_account_data(cells):
                continue
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
