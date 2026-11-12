"""Pure parsers for BNP Paribas / Hello bank! JSON (shapes taken from the woob `bnp` module).

No I/O and no browser here: `main.py` fetches, these functions turn a response into the
snapshot the backend expects. Anything that does not look as expected raises `ParseError`, or
marks one account `snapshotComplete: false`, so an incomplete answer never replaces data.
Seen on a live account: the envelope, families 1 and 2, two savings labels, the RIB list
and the operations. Every other product kind and key is UNVERIFIED (docs/features/bnp-paribas.md).
"""

import hashlib
import json
import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

CENT = Decimal("0.01")
# -10 ("user not connected", seen live as the STRING "-10" on the public InfosClient) and -30 (woob, unverified).
# An idled-out session on the signed-in site answers neither: InfosClient says 0 without data (`bank.session_alive`).
SESSION_EXPIRED_CODES = {-10, -30}
DEFERRED_SUMMARY = "FACTURE CARTE SELON RELEVE DU"
# idFamilleCompte -> kind (woob pages.py:342-351). Family 3 (term deposits) and unknown ones are unsupported.
FAMILIES = {"1": "CURRENT_ACCOUNT", "2": "SAVINGS", "4": "COMPTE_TITRES", "5": "ASSURANCE_VIE", "6": "ASSURANCE_VIE", "8": "LOAN", "9": "LOAN"}
SECURITIES_FAMILY = "4"
# (substring of the lower-cased product label, kind), first match wins, per family.
LABEL_KINDS = {
    "2": [
        ("livret a", "LIVRET_A"), ("developpement durable", "LDDS"), ("ldd", "LDDS"),
        ("epargne populaire", "LEP"), ("lep", "LEP"), ("livret jeune", "LIVRET_JEUNE"),
        ("compte epargne logement", "CEL"), ("cel", "CEL"), ("pel", "PEL"),
    ],
    "4": [("pea", "PEA")],
    "5": [("multiplacements", "PER"), ("per", "PER")],
    "6": [("multiplacements", "PER"), ("per", "PER")],
}
MAX_ACCOUNTS = 200
MAX_OPERATIONS = 2000
MAX_POSITIONS = 500


class ParseError(Exception):
    """The bank answered with a shape this sidecar does not know."""

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class SessionExpired(Exception):
    """The bank says the cookie session is over."""


def loads(text: str) -> Any:
    try:
        return json.loads(text, parse_float=Decimal)
    except ValueError as exc:
        raise ParseError("UPSTREAM_FORMAT_CHANGED") from exc


def envelope(body: Any) -> Any:
    """`data` of a `{codeRetour, message, data}` answer; a dead session raises `SessionExpired`."""
    if not isinstance(body, dict) or "codeRetour" not in body:
        raise ParseError("UPSTREAM_FORMAT_CHANGED")
    try:
        code = int(str(body["codeRetour"]).strip())
    except ValueError as exc:
        raise ParseError("UPSTREAM_FORMAT_CHANGED") from exc
    # Matched on the code, never the message text (the live message is "user not connected").
    if code in SESSION_EXPIRED_CODES:
        raise SessionExpired()
    if code != 0:
        raise ParseError("UPSTREAM_UNAVAILABLE")
    return body.get("data")


def _dict(value: Any) -> dict:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list:
    return value if isinstance(value, list) else []


def money(value: Any) -> Decimal | None:
    """A JSON number or numeric string as a Decimal; None when absent or not a finite number."""
    if value is None or isinstance(value, bool):
        return None
    try:
        number = Decimal(str(value).strip().replace(",", ".").replace(" ", ""))
    except InvalidOperation:
        return None
    return number if number.is_finite() else None


def amount_text(value: Decimal) -> str:
    return format(value.quantize(CENT), "f")


def quantity_text(value: Decimal) -> str:
    text = format(value.normalize(), "f")
    return text


def text(value: Any, limit: int = 200) -> str:
    return " ".join(str(value or "").split())[:limit]


def stable_id(account: dict) -> str:
    """The account's own `key` is an encrypted handle that may change per session: derive the id
    from the IBAN or the masked number instead."""
    basis = text(account.get("iban") or account.get("value"))
    return hashlib.sha256(basis.encode()).hexdigest()[:20] if basis else ""


def kind_of(family: str, label: str) -> str | None:
    base = FAMILIES.get(family)
    if base is None:
        return None
    lowered = label.lower()
    for needle, kind in LABEL_KINDS.get(family, []):
        if re.search(rf"(?<![a-z]){re.escape(needle)}(?![a-z])", lowered):
            return kind
    return base


def parse_ibans(data: Any) -> dict[str, str]:
    """`key` (ibanCrypte) -> valid-looking IBAN."""
    out: dict[str, str] = {}
    for item in _list(_dict(data).get("listeRib")):
        info = _dict(_dict(item).get("infoCompte"))
        iban = "".join(str(info.get("iban") or "").split()).upper()
        key = text(info.get("ibanCrypte"), 400)
        if key and re.fullmatch(r"[A-Z]{2}\d{2}[A-Z0-9]{11,30}", iban):
            out[key] = iban
    return out


def parse_accounts(data: Any, ibans: dict[str, str]) -> tuple[list[dict], list[dict]]:
    """(rows, unsupported). A row keeps the opaque `key` for later calls; it never leaves the sidecar."""
    rows: list[dict] = []
    unsupported: list[dict] = []
    families = _list(_dict(_dict(data).get("infoUdc")).get("familleCompte"))
    if not families:
        raise ParseError("UPSTREAM_FORMAT_CHANGED")
    for family in families:
        family_id = text(_dict(family).get("idFamilleCompte"), 10)
        for raw in _list(_dict(family).get("compte")):
            raw = _dict(raw)
            key = text(raw.get("key"), 400)
            label = text(raw.get("libellePersoProduit") or raw.get("libelleProduit"), 120)
            product = text(raw.get("libelleProduit"), 120)
            row = {
                "key": key, "value": text(raw.get("value"), 60), "iban": ibans.get(key),
                "family": family_id, "label": label, "currency": text(raw.get("devise") or "EUR", 3).upper(),
                "balance": money(raw.get("soldeDispo")),
            }
            row["externalId"] = stable_id(row)
            kind = kind_of(family_id, product.lower() or label.lower())
            if not key or not row["externalId"] or kind is None:
                if row["externalId"]:
                    unsupported.append({"externalId": row["externalId"], "familyCode": family_id[:10]})
                continue
            if kind == "LOAN" and row["balance"] is None:
                continue  # a closed loan carries only nulls (woob pages.py:331-337)
            row["kind"] = kind
            row["cash"] = family_id == SECURITIES_FAMILY and "especes" in product.lower()
            rows.append(row)
    if len(rows) + len(unsupported) > MAX_ACCOUNTS:
        raise ParseError("UPSTREAM_FORMAT_CHANGED")
    return rows, unsupported


def french_date(value: Any) -> date | None:
    raw = str(value or "").strip()
    # `releveOp` writes `dateOperation` day first with dashes (31-01-2026).
    for pattern in ("%d-%m-%Y", "%d/%m/%Y", "%Y-%m-%d"):
        try:
            return datetime.strptime(raw, pattern).date()
        except ValueError:
            continue
    return None


def parse_operations(data: Any) -> list[dict] | None:
    """Past operations as sidecar transactions; None when the shape is not the expected one."""
    listing = _dict(_dict(data).get("listerOperations"))
    compte = listing.get("compte")
    if not isinstance(compte, dict):
        return None
    past = compte.get("operationPassee")
    if past is None:
        return []
    if not isinstance(past, list) or len(past) > MAX_OPERATIONS:
        return None
    out: list[dict] = []
    seen: dict[str, int] = {}
    for item in past:
        item = _dict(item)
        label = text(item.get("libelleOperation"), 300)
        when = french_date(item.get("dateOperation"))
        amount = money(_dict(item.get("montant")).get("montant"))
        if when is None or amount is None or not label:
            return None
        if DEFERRED_SUMMARY in label.upper():
            continue
        basis = text(item.get("idOperation"), 100)
        if not basis:
            digest = f"{when.isoformat()}|{amount_text(amount)}|{label}"
            seen[digest] = seen.get(digest, 0) + 1
            basis = hashlib.sha256(f"{digest}|{seen[digest]}".encode()).hexdigest()[:24]
        out.append({
            "externalId": basis, "date": when.isoformat(), "description": label,
            "amount": amount_text(amount), "counterparty": None,
            "kind": text(_dict(item.get("operationType")).get("codeFamille") or item.get("categorie"), 40) or None,
        })
    ids = [op["externalId"] for op in out]
    return out if len(ids) == len(set(ids)) else None


def parse_synthesis(data: Any) -> list[dict]:
    out = []
    for item in _list(_dict(data).get("synSecurityAccounts")):
        item = _dict(item)
        number = text(item.get("securityAccountNumber"), 40)
        value = money(item.get("valorisation"))
        if number and value is not None:
            out.append({"number": number, "name": text(item.get("securityAccountName"), 120), "value": value})
    return out


ISIN = re.compile(r"[A-Z]{2}[A-Z0-9]{9}\d")


def parse_positions(data: Any) -> list[dict] | None:
    """Portfolio lines; None when one line cannot be read (the account is then incomplete)."""
    raw = _dict(data).get("listofPortfolios")
    if not isinstance(raw, list) or len(raw) > MAX_POSITIONS:
        return None
    lines = []
    for item in raw:
        item = _dict(item)
        isin = text(item.get("securityCode"), 20).upper()
        quantity = money(item.get("quantityOwned"))
        value = money(item.get("valorizationValuation"))
        if not ISIN.fullmatch(isin) or quantity is None or value is None:
            return None
        pnl = money(item.get("profitLossValorisation"))
        lines.append({
            "isin": isin, "label": text(item.get("securityName"), 120),
            "quantity": quantity_text(quantity), "value": amount_text(value),
            "pnl": amount_text(pnl) if pnl is not None else None,
        })
    return lines


def match_securities(row: dict, synthesis: list[dict]) -> dict | None:
    """The one securities account whose number ends like the account's masked number; ambiguity is None."""
    tail = row["value"][-4:]
    found = [item for item in synthesis if len(tail) == 4 and item["number"].endswith(tail)]
    return found[0] if len(found) == 1 else None


def build_accounts(rows: list[dict], histories: dict, synthesis: list[dict], portfolios: dict) -> list[dict]:
    """Combine the fetched pieces; `histories` / `portfolios` map external id -> parsed list or None."""
    out = []
    cash_rows = [row for row in rows if row["cash"]]
    for row in rows:
        if row["cash"]:
            continue
        kind = row["kind"]
        entry = {
            "externalId": row["externalId"], "kind": kind, "name": row["label"] or "BNP Paribas",
            "balance": None, "cashBalance": None, "currency": row["currency"], "iban": row["iban"],
            "transactions": [], "positions": [], "snapshotComplete": True,
        }
        balance = row["balance"]
        if kind in ("PEA", "COMPTE_TITRES"):
            match = match_securities(row, synthesis)
            lines = portfolios.get(row["externalId"])
            cash = [c["balance"] for c in cash_rows if c["value"][-4:] == row["value"][-4:] and c["balance"] is not None]
            if match is None or lines is None or len(cash) > 1:
                entry["snapshotComplete"] = False
                balance = match["value"] if match else balance
            else:
                balance = match["value"]
                entry["positions"] = lines
                entry["cashBalance"] = amount_text(cash[0]) if cash else None
        elif kind in ("CURRENT_ACCOUNT", "LIVRET_A", "LDDS", "LEP", "LIVRET_JEUNE", "PEL", "CEL", "SAVINGS"):
            operations = histories.get(row["externalId"])
            if operations is None:
                entry["snapshotComplete"] = False
            else:
                entry["transactions"] = operations
        if balance is None:
            entry["snapshotComplete"] = False
            balance = Decimal(0)
        entry["balance"] = amount_text(balance)
        out.append(entry)
    return out
