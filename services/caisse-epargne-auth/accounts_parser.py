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
* which product families exist beyond those observed (`1`, `3/01` Livret A,
  `3/28` LDDS, `4/05` PER, `6/12` personal loan, `6/13` home loan): they are skipped (with their cards)
  and reported in `build_import(...)["unsupported"]` by id and family code
  only, never mapped to a guessed kind, so a PEA or an insurance contract
  cannot block the sync of the supported accounts. PER, loan and insurance
  contracts carry a null `contractPfmId`: they are keyed by
  `contract:<contractId.id>` instead, and the kind is decided before any id
  is read;
* a card carries no currency of its own: its balance currency is its parent
  contract's;
* whether `dueDate` can be null on real rows: it is treated as required;
* which id a card's transactions carry in `accountId`: verified as
  `contractPfmId` for the current account and the Livret A only. For cards the
  fixture assumes `cardPfmId`; a mismatch raises `INVALID_TRANSACTIONS`
  (wrong account) rather than mis-filing rows. To confirm on the next real run.
"""

import calendar
import math
import re
from datetime import date
from decimal import ROUND_CEILING, ROUND_FLOOR, ROUND_HALF_UP, Decimal, localcontext
from fractions import Fraction
from typing import Any, Iterable, Optional, Union

INVALID_SYNTHESIS = "INVALID_SYNTHESIS"
INVALID_TRANSACTIONS = "INVALID_TRANSACTIONS"
MISSING_TRANSACTIONS = "MISSING_TRANSACTIONS"
INVALID_LOAN_DETAIL = "INVALID_LOAN_DETAIL"

# Observed on the real bank, 2026-10-07: Active = "100", Indéterminé = "XXX".
CARD_STATUS_ACTIVE = "100"
FAMILY_CURRENT = "1"
FAMILY_SAVINGS_AVAILABLE = "3"
LIVRET_A_LABEL = "LIVRET A"

# Observed live 2026-10-13: `productFamilyPFM.code` / `subProductFamilyPFM.code`.
# Any other pair (insurance `5`, other sub codes) is unsupported, never fatal.
_KINDS_BY_FAMILY = {
    (FAMILY_SAVINGS_AVAILABLE, "01"): "LIVRET_A",
    (FAMILY_SAVINGS_AVAILABLE, "28"): "LDDS",
    ("4", "05"): "PER",
    ("6", "12"): "LOAN",
    ("6", "13"): "LOAN",
}
# Which detail a LOAN reads: the personal-loan app or the home-loan app
# (observed live 2026-10-25, `6/13` "Crédit immobilier").
LOAN_PRODUCTS = {("6", "12"): "PERSONAL", ("6", "13"): "HOME"}
# Kinds with PFM transactions, keyed by `contractPfmId`. PER and loan contracts
# carry a null `contractPfmId` and are keyed by `contract:<contractId.id>`.
PFM_KINDS = frozenset({"CURRENT_ACCOUNT", "LIVRET_A", "LDDS"})
CONTRACT_ID_PREFIX = "contract:"

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
    sub = identity.get("subProductFamilyPFM")
    if sub is not None:
        sub = _object(sub, INVALID_SYNTHESIS, f"{where}.subProductFamilyPFM")
        sub_code = _text(sub.get("code"), INVALID_SYNTHESIS, f"{where}.subProductFamilyPFM.code")
        # PEA, insurance, other savings...: skipped, never mapped to a guessed
        # kind, and never allowed to block the sync of the supported accounts.
        return _KINDS_BY_FAMILY.get((code, sub_code))
    # No sub family: only the Livret A label is recognised (older payloads).
    label = identity.get("productLabel")
    if (
        code == FAMILY_SAVINGS_AVAILABLE
        and isinstance(label, str)
        and label.strip().upper() == LIVRET_A_LABEL
    ):
        return "LIVRET_A"
    return None


def _contract_id(identification: dict, where: str) -> str:
    """`contract:<contractId.id>`, blanks stripped; the key when there is no PFM id."""
    contract_id = _object(identification.get("contractId"), INVALID_SYNTHESIS, f"{where}.contractId")
    raw = _text(contract_id.get("id"), INVALID_SYNTHESIS, f"{where}.contractId.id")
    stripped = raw.strip()
    if not stripped:
        raise ParseError(INVALID_SYNTHESIS, f"{where}.contractId.id: expected a non-empty string")
    return CONTRACT_ID_PREFIX + stripped


def _external_id(kind: Optional[str], identification: dict, where: str) -> str:
    pfm = identification.get("contractPfmId")
    if kind in PFM_KINDS:
        return _pfm_id(pfm, INVALID_SYNTHESIS, f"{where}.contractPfmId")
    if pfm is not None:
        # Null or absent on PER and loans as observed; an integer is tolerated,
        # anything else is a format change.
        pfm_id = _pfm_id(pfm, INVALID_SYNTHESIS, f"{where}.contractPfmId")
        if kind is None:
            return pfm_id
    return _contract_id(identification, where)


def _check_balance_sign(kind: str, balance: Optional[dict], where: str) -> None:
    # A loan is outstanding capital, negative on the bank side; a PER is an
    # asset. The other sign means the field changed meaning.
    if balance is None:  # `_money(required=True)` has already refused it
        raise ParseError(INVALID_SYNTHESIS, f"{where}.balance: missing")
    value = balance["value"]
    if (kind == "LOAN" and value > 0) or (kind == "PER" and value < 0):
        raise ParseError(INVALID_SYNTHESIS, f"{where}.balance.value: unexpected sign for this kind")


def _contract_account(item: dict, where: str) -> tuple:
    """`(account, None)` for a supported contract, `(None, unsupported)` otherwise."""
    identification = _object(item.get("identification"), INVALID_SYNTHESIS, f"{where}.identification")
    identity = _object(item.get("identity"), INVALID_SYNTHESIS, f"{where}.identity")
    # The kind decides which id to read: PER, loan and insurance contracts have
    # a null `contractPfmId`, and must not fail the sync of the others.
    kind = _contract_kind(identity, where)
    external_id = _external_id(kind, identification, where)
    if kind is None:
        # Id and family code only: no label, no balance leaves the parser.
        return None, {"externalId": external_id, "familyCode": identity["productFamilyPFM"]["code"]}

    balance = _money(identity.get("balance"), f"{where}.balance", required=True)
    _check_balance_sign(kind, balance, where)
    filling = identity.get("fillingRatio")
    filling_ratio = (
        None if filling is None else _decimal(filling, INVALID_SYNTHESIS, f"{where}.fillingRatio")
    )
    name = identity.get("contractLabel")
    account = {
        "externalId": external_id,
        "kind": kind,
        "name": name if isinstance(name, str) else None,
        "balance": balance,
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
    }
    if kind == "PER":
        # Routing uses the contract's actual entity, not an assumed login-query field.
        contract_id = identification.get("contractId")
        account["perEntityCode"] = contract_id.get("entityCode") if isinstance(contract_id, dict) else None
    if kind == "LOAN":
        account["loanProduct"] = LOAN_PRODUCTS[_family_pair(identity)]
        # The home-loan API names the loan `<entityCode>-<contractId.id>`.
        contract_id = identification.get("contractId")
        account["loanEntityCode"] = contract_id.get("entityCode") if isinstance(contract_id, dict) else None
    return account, None


def _family_pair(identity: dict) -> tuple:
    """`(productFamilyPFM.code, subProductFamilyPFM.code)`, already checked by `_contract_kind`."""
    return identity["productFamilyPFM"]["code"], identity["subProductFamilyPFM"]["code"]


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


# ─── Personal-loan detail ───────────────────────────────────────────────────
#
# `GET /bapi/personalLoanPrd/v1/personalLoanPrdSynthesisView/{contractId.id}`,
# observed live 2026-10-13. Dates are `DD/MM/YYYY`, `folder.tNC` is the nominal
# rate in percent. `participants` (names) and `linkedAccount.label` are never
# read: the import does not need them.

_FR_DATE_RE = re.compile(r"^(\d{2})/(\d{2})/(\d{4})$")
_CENT = Decimal("0.01")
# Fifty years. Above it a duration is not a loan the bank granted, and the exact
# annuity fallback (a Fraction raised to `months`) would grow with it until it
# blocks the event loop or runs out of memory, failing the whole import.
MAX_LOAN_MONTHS = 600


def _fr_date(value: Any, where: str) -> date:
    match = _FR_DATE_RE.match(value) if isinstance(value, str) else None
    if match is None:
        raise ParseError(INVALID_LOAN_DETAIL, f"{where}: expected a DD/MM/YYYY date")
    day, month, year = (int(g) for g in match.groups())
    try:
        return date(year, month, day)
    except ValueError:
        raise ParseError(INVALID_LOAN_DETAIL, f"{where}: expected a valid date") from None


def _positive_amount(value: Any, where: str) -> Decimal:
    obj = _object(value, INVALID_LOAN_DETAIL, where)
    amount = _decimal(obj.get("value"), INVALID_LOAN_DETAIL, f"{where}.value")
    if amount <= 0:
        raise ParseError(INVALID_LOAN_DETAIL, f"{where}.value: expected a positive amount")
    return amount


def _annuity(borrowed: Decimal, rate: Decimal, months: int) -> Decimal:
    """Constant monthly installment at the yearly `rate`, certified to the cent."""
    bounds = []
    with localcontext() as ctx:
        ctx.prec = 60 + max(0, -rate.adjusted())
        for rounding, reciprocal_rounding in (
            (ROUND_FLOOR, ROUND_CEILING),
            (ROUND_CEILING, ROUND_FLOOR),
        ):
            ctx.rounding = rounding
            if rate == 0:
                bounds.append(borrowed / months)
                continue
            monthly_factor = 1 + rate / 12
            # Reciprocals reverse the bounds. All sum terms are positive.
            ctx.rounding = reciprocal_rounding
            discount = 1 / monthly_factor
            block_discount = discount
            block_sum = discount
            offset = Decimal(1)
            present_value = Decimal(0)
            remaining = months
            # Binary doubling sums discounted payments without cancellation.
            while remaining:
                if remaining & 1:
                    present_value += offset * block_sum
                    offset *= block_discount
                block_sum *= 1 + block_discount
                block_discount *= block_discount
                remaining >>= 1
            ctx.rounding = rounding
            bounds.append(borrowed / present_value)

    lower, upper = (bound.quantize(_CENT, rounding=ROUND_HALF_UP) for bound in bounds)
    if lower == upper:
        return lower

    # Decimal bounds can straddle an exact tie at any precision.
    annuity = Fraction(borrowed)
    if rate == 0:
        annuity /= months
    else:
        monthly_rate = Fraction(rate) / 12
        annuity *= monthly_rate / (1 - (1 + monthly_rate) ** -months)
    cents, remainder = divmod(annuity.numerator * 100, annuity.denominator)
    if remainder * 2 >= annuity.denominator:
        cents += 1
    return (Decimal(cents) * _CENT).quantize(_CENT)


def _insurance(borrowed: Decimal, rate: Decimal, months: int, installment: Decimal) -> Optional[Decimal]:
    """Monthly borrower insurance, derived rather than read, or None below the annuity.

    The bank's installment exceeds the annuity at the nominal rate because of the
    borrower insurance (types D and I seen live on a personal loan). Picsou's
    amortisation schedule takes the insurance back out of the installment, so it is
    sent apart. A home loan's insurance is often taken elsewhere: the installment then
    matches the annuity to the cent and 0.00 is sent. Below the annuity: no figure.
    The annuity uses directed Decimal bounds and exact rational rounding when
    those bounds disagree at the cent. An amount of 10^26 or more still
    cannot be rounded to the cent in the default 28-digit context: it refuses
    this loan's detail instead of failing the import.
    """
    try:
        annuity = _annuity(borrowed, rate, months)
        insurance = (installment - annuity).quantize(_CENT, rounding=ROUND_HALF_UP)
    except ArithmeticError:
        # Every decimal signal (InvalidOperation, DivisionByZero, ...) is an ArithmeticError.
        raise ParseError(INVALID_LOAN_DETAIL, "loan: figures outside the decimal precision") from None
    return insurance if insurance >= 0 else None


def parse_loan_detail(payload: Any) -> dict:
    """`personalLoanPrdSynthesisView` -> the `loan` block of a LOAN account.

    Decimals and dates are returned as `Decimal` / `date`; the fetcher
    serialises them. Any missing or malformed field raises `ParseError`.
    """
    root = _object(payload, INVALID_LOAN_DETAIL, "loan")
    view = _object(root.get("personalLoanPrdSynthesisView"), INVALID_LOAN_DETAIL, "loan.view")
    offer = _object(view.get("offer"), INVALID_LOAN_DETAIL, "loan.offer")
    folder = _object(view.get("folder"), INVALID_LOAN_DETAIL, "loan.folder")
    linked = _object(view.get("linkedAccount"), INVALID_LOAN_DETAIL, "loan.linkedAccount")

    borrowed = _positive_amount(offer.get("proposedAmount"), "loan.offer.proposedAmount")
    duration = offer.get("duration")
    if isinstance(duration, bool) or not isinstance(duration, int) or not 0 < duration <= MAX_LOAN_MONTHS:
        raise ParseError(INVALID_LOAN_DETAIL, "loan.offer.duration: expected 1 to 600 months")
    percent = _decimal(folder.get("tNC"), INVALID_LOAN_DETAIL, "loan.folder.tNC")
    rate = percent / 100
    if not 0 <= rate <= 1:
        raise ParseError(INVALID_LOAN_DETAIL, "loan.folder.tNC: expected a rate between 0 and 100 %")
    installment = _positive_amount(
        folder.get("nextMonthlyPaymentAmount"), "loan.folder.nextMonthlyPaymentAmount"
    )

    return {
        "borrowedAmount": borrowed,
        "interestRate": rate,
        "monthlyPayment": installment,
        "insuranceMonthly": _insurance(borrowed, rate, duration, installment),
        "signatureDate": _fr_date(offer.get("signatureDate"), "loan.offer.signatureDate"),
        "firstDueDate": _fr_date(offer.get("firstDueDate"), "loan.offer.firstDueDate"),
        "lastDueDate": _fr_date(folder.get("lastDueDate"), "loan.folder.lastDueDate"),
        "durationMonths": duration,
        "linkedIban": _text(linked.get("iban"), INVALID_LOAN_DETAIL, "loan.linkedAccount.iban"),
    }


# ─── Home-loan detail ───────────────────────────────────────────────────────
#
# `GET /bapi/loan/v1/loans/{entityCode}-{contractId.id}` on the BPCE host,
# observed live 2026-10-25 for a fixed-rate home loan. Dates are ISO
# (`YYYY-MM-DD`), `nextDuedateRate` is the rate in percent and `duration` the
# number of monthly installments. No first due date is given: it is the last
# one minus `duration - 1` months. The debit account comes without its IBAN.


def _add_months(day: date, months: int) -> date:
    year, month = divmod(day.year * 12 + day.month - 1 + months, 12)
    return date(year, month + 1, min(day.day, calendar.monthrange(year, month + 1)[1]))


def _eur_amount(value: Any, where: str) -> Decimal:
    """A positive `{value, currencyCode}` in euros: the loan account and its Debt are in EUR."""
    amount = _positive_amount(value, where)
    if value.get("currencyCode") != "EUR":
        raise ParseError(INVALID_LOAN_DETAIL, f"{where}.currencyCode: expected EUR")
    return amount


def parse_home_loan_detail(payload: Any) -> dict:
    """`loan/v1/loans` -> the `loan` block of a LOAN account, same shape as a personal loan.

    Any missing or malformed field raises `ParseError`.
    """
    root = _object(payload, INVALID_LOAN_DETAIL, "loan")
    loan = _object(root.get("loan"), INVALID_LOAN_DETAIL, "loan.loan")
    contract = _object(loan.get("contractCharacteristic"), INVALID_LOAN_DETAIL, "loan.contractCharacteristic")
    money = _object(loan.get("financialInformation"), INVALID_LOAN_DETAIL, "loan.financialInformation")

    borrowed = _eur_amount(money.get("amountBorrowed"), "loan.financialInformation.amountBorrowed")
    duration = money.get("duration")
    if isinstance(duration, bool) or not isinstance(duration, int) or not 0 < duration <= MAX_LOAN_MONTHS:
        raise ParseError(INVALID_LOAN_DETAIL, "loan.financialInformation.duration: expected 1 to 600 months")
    percent = _decimal(money.get("nextDuedateRate"), INVALID_LOAN_DETAIL, "loan.financialInformation.nextDuedateRate")
    rate = percent / 100
    if not 0 <= rate <= 1:
        raise ParseError(INVALID_LOAN_DETAIL, "loan.financialInformation.nextDuedateRate: expected a rate between 0 and 100 %")
    installment = _eur_amount(money.get("nextDuedateAmount"), "loan.financialInformation.nextDuedateAmount")
    last_due = _iso_date(money.get("lastDuedate"), INVALID_LOAN_DETAIL, "loan.financialInformation.lastDuedate")
    signature = _iso_date(
        contract.get("subscriptionDate"), INVALID_LOAN_DETAIL, "loan.contractCharacteristic.subscriptionDate"
    )
    # The duration is capped at MAX_LOAN_MONTHS above, but a last due date in the first
    # fifty years of the calendar can still put the first one before year 1: ValueError,
    # which refuses this loan only.
    try:
        first_due = _add_months(last_due, -(duration - 1))
    except ValueError:
        raise ParseError(
            INVALID_LOAN_DETAIL, "loan.financialInformation.duration: first due date out of range"
        ) from None
    if first_due < signature:
        raise ParseError(
            INVALID_LOAN_DETAIL, "loan.financialInformation: first due date before the subscription date"
        )

    return {
        "borrowedAmount": borrowed,
        "interestRate": rate,
        "monthlyPayment": installment,
        "insuranceMonthly": _insurance(borrowed, rate, duration, installment),
        "signatureDate": signature,
        "firstDueDate": first_due,
        "lastDueDate": last_due,
        "durationMonths": duration,
        "linkedIban": None,
    }


# ─── Whole import ───────────────────────────────────────────────────────────


def build_account(account: dict, pages: Pages, *, today: Optional[date] = None) -> list:
    """Attach what `pages` says to one account and return its transactions.

    Sets the IBAN, or a card's outstanding, next due date and balance, on `account`.
    PER and loan have no PFM transactions and no IBAN: `pages` is ignored for them.
    Raises `ParseError` for this account's data only; the caller decides what that costs.
    """
    kind = account["kind"]
    if kind != "CARD" and kind not in PFM_KINDS:
        account["iban"], account["ibanAmbiguous"] = None, False
        return []
    transactions = parse_transactions(pages, account_external_id=account["externalId"])
    if kind == "CARD":
        outstanding = card_outstanding(pages, today=today)
        account["outstanding"] = outstanding
        account["nextDueDate"] = next_due_date(pages, today=today)
        account["balance"] = {"value": outstanding, "currency": account["currency"]}
    else:
        account["iban"], account["ibanAmbiguous"] = extract_iban(pages)
    return transactions


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
        if account["kind"] != "CARD" and account["kind"] not in PFM_KINDS:
            # PER and loan have no PFM transactions and no IBAN: nothing is
            # fetched for them, and any pages handed over are ignored.
            transactions[ext] = build_account(account, [], today=today)
            continue
        if ext not in transactions_by_account:
            raise ParseError(MISSING_TRANSACTIONS, "transactions missing for an imported account")
        transactions[ext] = build_account(account, transactions_by_account[ext], today=today)
    return {"accounts": accounts, "transactions": transactions, "unsupported": unsupported}
