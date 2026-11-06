"""La Banque Postale XHTML parsing.

The web space is server-rendered (`canalXHTML/*.ea`): there is no account JSON.
These parsers read the four pages the connector needs -- the login form, the
two synthesis pages and a statement page -- and fail with a typed
`FormatError` when the markup moved.
"""

import hashlib
import html as html_module
import re
from datetime import datetime
from decimal import Decimal, InvalidOperation


class FormatError(Exception):
    """A page does not match the observed markup."""

    code = "UPSTREAM_FORMAT_CHANGED"


# The `span.pseudo-h3` product label, lower-cased and accent-insensitive enough
# for the labels the bank actually shows.
_PRODUCTS = {
    "livret a": "LIVRET_A",
    "livret d'epargne populaire": "LEP",
    "livret d'épargne populaire": "LEP",
    "lep": "LEP",
    "ldds": "LDDS",
    "livret de developpement durable": "LDDS",
    "livret de développement durable": "LDDS",
    "compte sur livret": "CSL",
    "livret jeune": "LIVRET_JEUNE",
    "pel": "PEL",
    "plan d'epargne logement": "PEL",
    "plan d'épargne logement": "PEL",
    "cel": "CEL",
    "compte epargne logement": "CEL",
    "compte épargne logement": "CEL",
    "compte bancaire": "CURRENT_ACCOUNT",
}

_TAG = re.compile(r"<[^>]+>")
_SPACES = re.compile(r"\s+")


def text(fragment: str) -> str:
    """Visible text of an HTML fragment, entities decoded, whitespace collapsed."""
    return _SPACES.sub(" ", html_module.unescape(_TAG.sub(" ", fragment))).strip()


def _amount(fragment: str, code: str = "UPSTREAM_FORMAT_CHANGED") -> Decimal:
    raw = text(fragment)
    raw = (
        raw.replace("\u2212", "-")
        .replace("\u00a0", " ")
        .replace("\u202f", " ")
        .replace("€", "")
        .replace(",", ".")
    )
    raw = re.sub(r"[^0-9.\-+]", "", raw).replace("+", "")
    try:
        return Decimal(raw)
    except (InvalidOperation, ValueError) as exc:
        raise FormatError(code) from exc


# ─── login form ──────────────────────────────────────────────────────────────


def parse_login_form(source: str) -> dict:
    """`{action, hidden, keys}` for the bank's login form.

    The keypad is ten text buttons in a per-load shuffled order. The bank's own
    script writes the *DOM position* of the clicked button into `password`, so
    the UI only ever sends positions back.
    """
    action = re.search(r'<form[^>]*action="([^"]+)"', source, re.I)
    if not action:
        raise FormatError()

    hidden: dict[str, str] = {}
    for tag in re.finditer(r"<input[^>]*type=\"hidden\"[^>]*>", source, re.I):
        name = re.search(r'name="([^"]*)"', tag.group(0), re.I)
        value = re.search(r'value="([^"]*)"', tag.group(0), re.I)
        if name:
            hidden[html_module.unescape(name.group(1))] = html_module.unescape(
                value.group(1) if value else ""
            )

    keypad = re.search(r'data-tb-cvd-id="password"(.*?)(?:</div>\s*</div>|$)', source, re.S | re.I)
    if not keypad:
        raise FormatError()
    keys = [
        html_module.unescape(label.strip())
        for label in re.findall(r"<button[^>]*type=\"button\"[^>]*>([^<]+)</button>", keypad.group(1), re.I)
    ][:10]
    if len(keys) != 10 or sorted(keys) != sorted("0123456789"):
        raise FormatError()

    return {"action": html_module.unescape(action.group(1)), "hidden": hidden, "keys": keys}


# ─── mobile-approval challenge ───────────────────────────────────────────────


def parse_polling(source: str) -> tuple[str | None, str | None]:
    """`(pph, URL_AF_POLLING)` of the "trusted device" page, or `(None, None)`."""
    pph = re.search(r'var\s+PPH\s*=\s*"([^"]+)"', source)
    polling = re.search(r'var\s+URL_AF_POLLING\s*=\s*"([^"]+)"', source)
    if not polling:
        return None, None
    return (pph.group(1) if pph else None), polling.group(1)


# ─── account cards ───────────────────────────────────────────────────────────


def parse_account_cards(source: str) -> list[dict]:
    """Accounts shown on a synthesis page, with balance and statement coordinates."""
    starts = [
        m.start()
        for m in re.finditer(
            r'<div\b[^>]*?\bclass="(?=[^"]*\baccount-resume2020\b)[^"]*"', source, re.I
        )
    ]
    accounts: list[dict] = []
    seen: set[str] = set()
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else source.find("</li>", start)
        block = source[start:end if end >= 0 else len(source)]

        link = re.search(r'href="([^"]*compte\.numero=[^"]*)"', block, re.I)
        if not link:
            continue
        href = html_module.unescape(link.group(1))
        number = re.search(r"compte\.numero=([0-9a-fA-F-]{36})", href)
        type_recherche = re.search(r"typeRecherche=(\d+)", href)
        if not number or not type_recherche:
            continue
        external_id = number.group(1)
        if external_id in seen:
            continue

        product = re.search(r'class="pseudo-h3"[^>]*>(.*?)</span>', block, re.S | re.I)
        label = text(product.group(1)) if product else ""
        kind = _PRODUCTS.get(label.casefold().rstrip(" ."))
        if not kind:
            # Never skip a recognized account card: a supported account with an
            # unrecognized label must not be dropped from an otherwise "successful"
            # snapshot. Fail closed so the format change is diagnosed, not silent.
            raise FormatError()

        # `compte.numero` is a per-session UUID, not a stable account id: it
        # differs between two logins. The bank's displayed account number does
        # not, so the connector keys on a hash of it and keeps the UUID only for
        # the statement URL of this session.
        number = re.search(r"N°\s*([0-9A-Za-z]+)", text(block))
        if not number:
            raise FormatError()
        stable_id = hashlib.sha1(number.group(1).encode("utf-8")).hexdigest()

        balance = re.search(r'class="amount-euro"[^>]*>(.*?)</p>', block, re.S | re.I)
        if not balance:
            raise FormatError()
        if stable_id in seen:
            continue
        seen.add(stable_id)
        accounts.append(
            {
                "externalId": stable_id,
                "statementId": external_id,
                "kind": kind,
                "name": label or None,
                "balance": _amount(balance.group(1)),
                "typeRecherche": type_recherche.group(1),
            }
        )
    return accounts


# ─── operations table ────────────────────────────────────────────────────────


# A statement page with no operations carries no table; only an explicit message
# makes that an empty history rather than a changed page.
_EMPTY_OPERATIONS = re.compile(
    r"aucun(?:e|es)?\s+(?:op[ée]ration|mouvement)|pas d['’]op[ée]ration|no operations?",
    re.I,
)


def parse_transactions(source: str) -> list[dict]:
    """Rows of `#mouvementsTable`, or an empty list on an explicit no-operations page."""
    table = re.search(r'<table[^>]*id="mouvementsTable".*?</table>', source, re.S | re.I)
    if not table:
        # Returning [] for any 200 would mark a changed statement page as a complete
        # empty history; fail closed unless the page explicitly says there is nothing.
        if _EMPTY_OPERATIONS.search(text(source)):
            return []
        raise FormatError()
    rows = re.findall(r'<tr class="row btn-full-lightbox".*?</tr>', table.group(0), re.S | re.I)
    transactions: list[dict] = []
    occurrences: dict[tuple[str, str, str], int] = {}
    for row in rows:
        date_part = re.search(r'class="sorted-value"[^>]*>([^<]+)<', row, re.I)
        label_part = re.search(r'class="col label col-libelle"[^>]*>(.*?)</td>', row, re.S | re.I)
        amount_part = re.search(r'class="col label col-montant-brut[^"]*"[^>]*>(.*?)</td>', row, re.S | re.I)
        if not date_part or not label_part or not amount_part:
            raise FormatError()
        try:
            when = datetime.strptime(date_part.group(1).strip(), "%d/%m/%Y").date()
        except ValueError as exc:
            raise FormatError() from exc
        label = text(label_part.group(1)) or "La Banque Postale operation"
        amount = _amount(amount_part.group(1))
        # The rows carry no bank id. A deterministic hash over the visible fields
        # plus an occurrence counter among identical rows keeps re-syncs idempotent
        # when a new operation is prepended: the page index is not part of the key,
        # so every existing row keeps its id.
        key = (when.isoformat(), label, str(amount))
        occurrence = occurrences.get(key, 0)
        occurrences[key] = occurrence + 1
        external_id = hashlib.sha1(
            f"{key[0]}|{key[1]}|{key[2]}|{occurrence}".encode("utf-8")
        ).hexdigest()
        transactions.append(
            {
                "externalId": external_id,
                "date": when.isoformat(),
                "dueDate": None,
                "amount": str(amount),
                "currency": "EUR",
                "label": label,
                "typeCode": None,
            }
        )
    return transactions
