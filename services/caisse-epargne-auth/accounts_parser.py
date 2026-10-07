"""Turns Caisse d'Epargne synthesis and transaction JSON into the sidecar contract.

Two upstream shapes are parsed here, both observed on the real bank (see the
spike findings) and mirrored by `fixtures_synthetic.json`:

* `augmentedSynthesisViews`: one item per contract, each carrying its
  `augmentedCards`;
* the per-account transaction pages (`data`, `meta`, `included`), where
  `accountId` is the contract's `contractPfmId` (or a card's `cardPfmId`).

Kept free of FastAPI and httpx. Nothing is coerced to zero and nothing is
skipped to "recover": a payload that does not look like what was observed
raises `ParseError`, so a partial read can never overwrite a good portfolio.
Error messages name fields and positions only, never a value: the payloads
carry balances, labels and IBANs that must not reach logs.

Facts deliberately NOT guessed (the fixtures and findings do not settle them):

* the unit of `fillingRatio` (21.8 in the fixture): passed through untouched;
* the meaning of contract `status` (`2 Actif`): contracts are not filtered on it;
* which other product families exist beyond `1` and `3 / LIVRET A`: they are
  skipped (with their cards) and reported in `build_import(...)["unsupported"]`
  by id and family code only, never mapped to a guessed kind, so a PER or PEA
  opened later cannot block the sync of the supported accounts;
* a card carries no currency of its own: its balance currency is its parent
  contract's;
* whether `dueDate` can be null on real rows: it is treated as required;
* which id a card's transactions carry in `accountId`: verified as
  `contractPfmId` for the current account and the Livret A only. For cards the
  fixture assumes `cardPfmId`; a mismatch raises `INVALID_TRANSACTIONS`
  (wrong account) rather than mis-filing rows. To confirm on the next real run.
"""

import math
import re
from datetime import date
from decimal import Decimal
from typing import Any, Iterable, Optional, Union

INVALID_SYNTHESIS = "INVALID_SYNTHESIS"
INVALID_TRANSACTIONS = "INVALID_TRANSACTIONS"
MISSING_TRANSACTIONS = "MISSING_TRANSACTIONS"

# Observed on the real bank, 2026-10-07: Active = "100", Indéterminé = "XXX".
CARD_STATUS_ACTIVE = "100"
FAMILY_CURRENT = "1"
FAMILY_SAVINGS_AVAILABLE = "3"
LIVRET_A_LABEL = "LIVRET A"

# Observed live 2026-10-07: transaction dates carry a time part
# (`YYYY-MM-DDTHH:MM:SS`). Only the day is kept.
_ISO_DATE_RE = re.compile(r"^(\d{4}-\d{2}-\d{2})(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?)?$")

Page = dict
Pages = Union[Page, Iterable[Page]]


class ParseError(Exception):
    """A payload that cannot be trusted, carrying the stable code to surface."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


# ─── Value helpers ──────────────────────────────────────────────────────────


def _decimal(value: Any, code: str, where: str) -> Decimal:
    """JSON numbers only. A float goes through its repr so 0.1 stays 0.1."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ParseError(code, f"{where}: expected a number")
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ParseError(code, f"{where}: expected a finite number")
        return Decimal(repr(value))
    return Decimal(value)


def _text(value: Any, code: str, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise ParseError(code, f"{where}: expected a non-empty string")
    return value


def _object(value: Any, code: str, where: str) -> dict:
    if not isinstance(value, dict):
        raise ParseError(code, f"{where}: expected an object")
    return value


def _pfm_id(value: Any, code: str, where: str) -> str:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ParseError(code, f"{where}: expected an integer id")
    return str(value)


def _iso_date(value: Any, code: str, where: str) -> date:
    match = _ISO_DATE_RE.match(value) if isinstance(value, str) else None
    if match is None:
        raise ParseError(code, f"{where}: expected a YYYY-MM-DD date")
    try:
        return date.fromisoformat(match.group(1))
    except ValueError:
        raise ParseError(code, f"{where}: expected a valid date") from None


def _money(value: Any, where: str, *, required: bool) -> Optional[dict]:
    """`{value, currencyCode}` -> `{value: Decimal, currency}`; null if allowed."""
    if value is None:
        if required:
            raise ParseError(INVALID_SYNTHESIS, f"{where}: missing")
        return None
    obj = _object(value, INVALID_SYNTHESIS, where)
    return {
        "value": _decimal(obj.get("value"), INVALID_SYNTHESIS, f"{where}.value"),
        "currency": _text(obj.get("currencyCode"), INVALID_SYNTHESIS, f"{where}.currencyCode"),
    }


def _flag(card: dict, key: str, where: str) -> bool:
    value = card.get(key)
    if not isinstance(value, bool):
        raise ParseError(INVALID_SYNTHESIS, f"{where}.{key}: expected a boolean")
    return value


# ─── Synthesis → accounts ───────────────────────────────────────────────────


def _card_nature(card: dict, where: str) -> str:
    # `defferedDebitIndicator` is the API's own (misspelled) key. Credit wins
    # when both indicators are true. Nature is a property, never a filter.
    credit = _flag(card, "cardCreditIndicator", where)
    deferred = _flag(card, "defferedDebitIndicator", where)
    if credit:
        return "CREDIT"
    if deferred:
        return "DEFERRED_DEBIT"
    return "IMMEDIATE_DEBIT"


def _contract_kind(identity: dict, where: str) -> Optional[str]:
    """The supported kind, or None for a contract this slice does not import."""
    family = _object(identity.get("productFamilyPFM"), INVALID_SYNTHESIS, f"{where}.productFamilyPFM")
    code = _text(family.get("code"), INVALID_SYNTHESIS, f"{where}.productFamilyPFM.code")
    if code == FAMILY_CURRENT:
        return "CURRENT_ACCOUNT"
    label = identity.get("productLabel")
    if (
        code == FAMILY_SAVINGS_AVAILABLE
        and isinstance(label, str)
        and label.strip().upper() == LIVRET_A_LABEL
    ):
        return "LIVRET_A"
    # PER, PEA, other savings...: skipped, never mapped to a guessed kind, and
    # never allowed to block the sync of the supported accounts.
    return None


def _contract_account(item: dict, where: str) -> tuple:
    """`(account, None)` for a supported contract, `(None, unsupported)` otherwise."""
    identification = _object(item.get("identification"), INVALID_SYNTHESIS, f"{where}.identification")
    identity = _object(item.get("identity"), INVALID_SYNTHESIS, f"{where}.identity")
    external_id = _pfm_id(identification.get("contractPfmId"), INVALID_SYNTHESIS, f"{where}.contractPfmId")
    kind = _contract_kind(identity, where)
    if kind is None:
        # Id and family code only: no label, no balance leaves the parser.
        return None, {"externalId": external_id, "familyCode": identity["productFamilyPFM"]["code"]}

    filling = identity.get("fillingRatio")
    filling_ratio = (
        None if filling is None else _decimal(filling, INVALID_SYNTHESIS, f"{where}.fillingRatio")
    )
    name = identity.get("contractLabel")
    return {
        "externalId": external_id,
        "kind": kind,
        "name": name if isinstance(name, str) else None,
        "balance": _money(identity.get("balance"), f"{where}.balance", required=True),
        "authorizedOverdraft": _money(
            identity.get("authorizedOverdraft"), f"{where}.authorizedOverdraft", required=False
        ),
        "ceiling": _money(
            identity.get("authorizedCeilingAmount"), f"{where}.authorizedCeilingAmount", required=False
        ),
        "remainingDepositCapacity": _money(
            identity.get("remainingDepositCapacity"), f"{where}.remainingDepositCapacity", required=False
        ),
        "fillingRatio": filling_ratio,
        # Filled from the transactions by `build_import`; the synthesis has none.
        "iban": None,
        "ibanAmbiguous": False,
    }, None


def _card_account(card: Any, parent: dict, where: str) -> Optional[dict]:
    """The imported card, or None when its status says it is not active."""
    card = _object(card, INVALID_SYNTHESIS, where)
    status = _object(card.get("cardStatusType"), INVALID_SYNTHESIS, f"{where}.cardStatusType")
    code = _text(status.get("code"), INVALID_SYNTHESIS, f"{where}.cardStatusType.code")
    if code != CARD_STATUS_ACTIVE:
        return None  # "XXX" (Indéterminé) and anything else: inactive, never imported

    name = card.get("cardProductLabel")
    return {
        "externalId": _pfm_id(card.get("cardPfmId"), INVALID_SYNTHESIS, f"{where}.cardPfmId"),
        "kind": "CARD",
        "name": name if isinstance(name, str) else None,
        "status": "Active",
        "nature": _card_nature(card, where),
        "parentExternalId": parent["externalId"],
        # Both come from the card's own transactions in `build_import`.
        "outstanding": None,
        "balance": None,
        "currency": parent["balance"]["currency"],
        "iban": None,
        "ibanAmbiguous": False,
    }


def parse_synthesis(synthesis: Any) -> list:
    """Supported contracts and active cards as account dicts, in synthesis order."""
    return _parse_synthesis(synthesis)[0]


def _parse_synthesis(synthesis: Any) -> tuple:
    """`(accounts, unsupported)`; unsupported carries id and family code only."""
    root = _object(synthesis, INVALID_SYNTHESIS, "synthesis")
    items = root.get("items")
    if not isinstance(items, list):
        raise ParseError(INVALID_SYNTHESIS, "synthesis.items: expected a list")

    accounts: list = []
    unsupported: list = []
    seen: set = set()

    def add(account: dict, where: str) -> None:
        if account["externalId"] in seen:
            raise ParseError(INVALID_SYNTHESIS, f"{where}: duplicate id")
        seen.add(account["externalId"])
        accounts.append(account)

    for i, item in enumerate(items):
        where = f"items[{i}]"
        _object(item, INVALID_SYNTHESIS, where)
        contract, skipped = _contract_account(item, where)
        if contract is None:
            if skipped["externalId"] in seen:
                raise ParseError(INVALID_SYNTHESIS, f"{where}: duplicate id")
            seen.add(skipped["externalId"])
            unsupported.append(skipped)
            continue  # its cards are skipped with it
        add(contract, where)

        if "augmentedCards" not in item["identity"]:
            raise ParseError(INVALID_SYNTHESIS, f"{where}.augmentedCards: missing")
        cards = item["identity"]["augmentedCards"]
        if cards is None:
            cards = []  # observed live: a contract without card carries null
        if not isinstance(cards, list):
            raise ParseError(INVALID_SYNTHESIS, f"{where}.augmentedCards: expected a list")
        for j, raw_card in enumerate(cards):
            card_where = f"{where}.augmentedCards[{j}]"
            card = _card_account(raw_card, contract, card_where)
            if card is not None:
                add(card, card_where)
    return accounts, unsupported


# ─── Transactions ───────────────────────────────────────────────────────────


def _page_list(pages: Pages) -> list:
    if isinstance(pages, dict):
        return [pages]
    if isinstance(pages, (list, tuple)):
        return list(pages)
    raise ParseError(INVALID_TRANSACTIONS, "pages: expected a page or a list of pages")


def _rows(pages: Pages) -> Iterable[tuple]:
    """(page index, row index, raw row) over every page; `data: []` is fine."""
    for p, page in enumerate(_page_list(pages)):
        page = _object(page, INVALID_TRANSACTIONS, f"pages[{p}]")
        data = page.get("data")
        if not isinstance(data, list):
            raise ParseError(INVALID_TRANSACTIONS, f"pages[{p}].data: expected a list")
        for r, row in enumerate(data):
            yield p, r, row


def _type_code(row: dict) -> Optional[str]:
    """`parsedData.transactionTypeCode` as a string, or None when absent (never an error)."""
    parsed = row.get("parsedData")
    if not isinstance(parsed, dict):
        return None
    code = parsed.get("transactionTypeCode")
    if code is None or isinstance(code, bool) or not isinstance(code, (int, str)):
        return None
    return str(code)


def _transaction(row: Any, where: str) -> dict:
    row = _object(row, INVALID_TRANSACTIONS, where)
    tx_id = row.get("id")
    if tx_id is None or isinstance(tx_id, bool) or not isinstance(tx_id, (int, str)) or tx_id == "":
        raise ParseError(INVALID_TRANSACTIONS, f"{where}.id: expected an int or string id")
    account_id = row.get("accountId")
    if account_id is None or isinstance(account_id, bool) or not isinstance(account_id, (int, str)):
        raise ParseError(INVALID_TRANSACTIONS, f"{where}.accountId: expected an int or string id")
    return {
        "externalId": str(tx_id),
        "date": _iso_date(row.get("date"), INVALID_TRANSACTIONS, f"{where}.date").isoformat(),
        "dueDate": _iso_date(row.get("dueDate"), INVALID_TRANSACTIONS, f"{where}.dueDate").isoformat(),
        "amount": _decimal(row.get("amount"), INVALID_TRANSACTIONS, f"{where}.amount"),
        "currency": _text(row.get("currency"), INVALID_TRANSACTIONS, f"{where}.currency"),
        "label": _text(row.get("text"), INVALID_TRANSACTIONS, f"{where}.text"),
        "typeCode": _type_code(row),
        "accountExternalId": str(account_id),
    }


def parse_transactions(pages: Pages, *, account_external_id: Optional[str] = None) -> list:
    """Map the rows of one account's pages, in order, deduplicated by id.

    A repeated id with identical content (a re-fetched page) is dropped; a
    repeated id with different content is a contradiction and raises.
    """
    result: list = []
    by_id: dict = {}
    for p, r, raw in _rows(pages):
        where = f"pages[{p}].data[{r}]"
        row = _transaction(raw, where)
        if account_external_id is not None and row["accountExternalId"] != account_external_id:
            raise ParseError(INVALID_TRANSACTIONS, f"{where}.accountId: belongs to another account")
        known = by_id.get(row["externalId"])
        if known is None:
            by_id[row["externalId"]] = row
            result.append(row)
        elif known != row:
            raise ParseError(INVALID_TRANSACTIONS, f"{where}.id: duplicate id with different content")
    return result


def extract_iban(pages: Pages) -> tuple:
    """`(iban, ambiguous)` from `parsedData.clientIBAN` of the rows.

    The synthesis has no IBAN. Rows without a `clientIBAN` are ignored; several
    distinct values give `(None, True)` rather than a guess.
    """
    found: set = set()
    for p, r, row in _rows(pages):
        parsed = row.get("parsedData") if isinstance(row, dict) else None
        if not isinstance(parsed, dict) or parsed.get("clientIBAN") is None:
            continue
        found.add(_text(parsed["clientIBAN"], INVALID_TRANSACTIONS, f"pages[{p}].data[{r}].parsedData.clientIBAN"))
    if len(found) == 1:
        return next(iter(found)), False
    if len(found) > 1:
        return None, True
    return None, False


def _today(today: Optional[date]) -> date:
    if today is None:
        return date.today()
    if not isinstance(today, date):
        raise TypeError("today must be a date")
    return today


def next_due_date(pages: Pages, *, today: Optional[date] = None) -> Optional[date]:
    """Smallest `dueDate` strictly after `today` among the card operations, or None."""
    today = _today(today)
    upcoming = [
        due
        for due in (date.fromisoformat(row["dueDate"]) for row in parse_transactions(pages))
        if due > today
    ]
    return min(upcoming) if upcoming else None


def card_outstanding(pages: Pages, *, today: Optional[date] = None) -> Decimal:
    """Sum of the card operations whose `dueDate` is strictly after `today`.

    Known gap: on the due day itself the card reads 0 even if the debit is not
    yet posted on the current account. To be measured at the next live test.
    """
    today = _today(today)
    total = Decimal(0)
    for row in parse_transactions(pages):
        if date.fromisoformat(row["dueDate"]) > today:
            total += row["amount"]
    return total


# ─── Whole import ───────────────────────────────────────────────────────────


def build_import(synthesis: Any, transactions_by_account: dict, *, today: Optional[date] = None) -> dict:
    """Accounts (with IBAN and card outstanding) plus their transactions.

    `transactions_by_account` maps an account's external id to its pages. Every
    imported account must have an entry (`[]` for none): an absent entry means
    the fetch did not happen, which must not read as "no transactions" or as a
    zero card outstanding. Any failure raises; there is no partial result.
    """
    accounts, unsupported = _parse_synthesis(synthesis)
    transactions: dict = {}
    for account in accounts:
        ext = account["externalId"]
        if ext not in transactions_by_account:
            raise ParseError(MISSING_TRANSACTIONS, "transactions missing for an imported account")
        pages = transactions_by_account[ext]
        transactions[ext] = parse_transactions(pages, account_external_id=ext)
        if account["kind"] == "CARD":
            outstanding = card_outstanding(pages, today=today)
            account["outstanding"] = outstanding
            account["nextDueDate"] = next_due_date(pages, today=today)
            account["balance"] = {"value": outstanding, "currency": account["currency"]}
        else:
            account["iban"], account["ibanAmbiguous"] = extract_iban(pages)
    return {"accounts": accounts, "transactions": transactions, "unsupported": unsupported}
