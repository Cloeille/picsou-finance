"""Reads the accounts and their transactions from the Caisse d'Epargne data host.

Read-only: GET calls only, with the Bearer token minted by `replay`. The raw
JSON goes to `accounts_parser.build_import`, whose result is serialised to the
`POST /accounts` contract (money as decimal strings, never floats).

Fatal, all-or-nothing: the synthesis call and the current account (a transport error,
a parse error, a malformed page or an exceeded page cap on it raises `UpstreamUnavailable`
or `UpstreamFormatChanged`). Any other account that cannot
be read or serialised is left out of `accounts` and listed in `unavailable`
(`{externalId, kind, code}`, synthesis order). `replay.SessionExpired` always
propagates (main.py replays once), as do `InvalidSessionState` and `HostNotAllowed`
raised outside the loan token and the PER detail. Nothing read from the bank (balance, label, IBAN) or
the token is logged or put in an error message: errors carry a stable code and a
fixed text.

Observed on the real bank (spike/E2E_FINDINGS.md): the synthesis call, the
transactions query, and the `meta.nextPageToken` / `pageToken` pagination
(the last page has no token). Not verified: see `_fetch_card_pages`.
"""

import copy
import json
import logging
import urllib.parse
from datetime import date
from decimal import Decimal
from typing import Any, Awaitable, Callable, Optional

import httpx

import accounts_parser
import per_parser
import replay

log = logging.getLogger("caisse-epargne-auth")

DATA_BASE_URL = "https://www.rs-ext-bad-ce.caisse-epargne.fr"
SYNTHESIS_PATH = "/bapi/contract/v2/augmentedSynthesisViews"
TRANSACTIONS_PATH = "/pfm/user/v1.1/transactions"
# Observed live 2026-10-13, read with the personal-loan token (the main token
# gets 403). The last segment is the synthesis `contractId.id`, blanks stripped.
LOAN_DETAIL_PATH = "/bapi/personalLoanPrd/v1/personalLoanPrdSynthesisView/"
# Observed live 2026-10-25, read with the home-loan token on the BPCE host.
# The last segment is `<contractId.entityCode>-<contractId.id>`.
HOME_LOAN_BASE_URL = "https://www.rs-ext-bad-me.bpce.fr"
HOME_LOAN_DETAIL_PATH = "/bapi/loan/v1/loans/"
SYNTHESIS_QUERY = {
    "pfmCharacteristicsIndicator": "true",
    "productFamilyPFM": "1,2,3,4,6,7,17,18,20,19",
}
# `parsedData` is sent as a JSON array of {key, value}; the findings write it
# `[{key:transactionGranularityCode,value:IN},{…:ST},{key:status,value:AC}]`.
TRANSACTIONS_PARSED_DATA = [
    {"key": "transactionGranularityCode", "value": "IN"},
    {"key": "transactionGranularityCode", "value": "ST"},
    {"key": "status", "value": "AC"},
]
PAGE_SIZE = 50
# More than this many pages for one account is treated as a format change:
# never a silent truncation.
MAX_PAGES_PER_ACCOUNT = 20


class FetchError(Exception):
    """Base class. `code` is the stable, caller-facing error code; messages are fixed."""

    code = "UPSTREAM_UNAVAILABLE"

    def __init__(self, message: Optional[str] = None):
        super().__init__(message or self.code)


class UpstreamUnavailable(FetchError):
    code = "UPSTREAM_UNAVAILABLE"


class UpstreamFormatChanged(FetchError):
    code = "UPSTREAM_FORMAT_CHANGED"


# --- Bounded GET ---------------------------------------------------------------


async def _get_json(
    client: httpx.AsyncClient, url: str, params: dict, *, bearer: Optional[str] = None
) -> Any:
    """GET and decode a JSON body capped at `replay.MAX_RESPONSE_BYTES`.

    `bearer` replaces the client's token for this one call (the loan detail).
    """
    headers = {"Accept": "application/json"}
    if bearer is not None:
        headers["Authorization"] = f"Bearer {bearer}"
    try:
        async with client.stream("GET", url, params=params, headers=headers) as response:
            chunks: list = []
            size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > replay.MAX_RESPONSE_BYTES:
                    raise UpstreamUnavailable("Upstream response too large")
                chunks.append(chunk)
            status = response.status_code
            body = b"".join(chunks)
    except httpx.HTTPError:
        # Fixed message, no chained cause: httpx errors can embed URLs.
        raise UpstreamUnavailable("Upstream request failed") from None
    if status == 401:
        raise replay.SessionExpired("Token no longer accepted")
    if status != 200:
        raise UpstreamUnavailable("Upstream answered an unexpected status")
    try:
        return json.loads(body)
    except ValueError:
        raise UpstreamFormatChanged("Upstream answered a non-JSON body") from None


def _next_page_token(page: Any) -> Optional[str]:
    if not isinstance(page, dict) or not isinstance(page.get("data"), list):
        raise UpstreamFormatChanged("Transactions page has an unexpected shape")
    meta = page.get("meta")
    if meta is None:
        return None
    if not isinstance(meta, dict):
        raise UpstreamFormatChanged("Transactions page has an unexpected meta")
    token = meta.get("nextPageToken")
    if token is None:
        return None  # absent (or null): the last page
    if not isinstance(token, str) or not token:
        raise UpstreamFormatChanged("Transactions page has an unusable page token")
    return token


CARD_QUERY = {
    "businessType": "UserProfile",
    "take": str(PAGE_SIZE),
    "includeDisabledAccounts": "true",
}


async def _paginate(client: httpx.AsyncClient, url: str, query: dict) -> list:
    pages: list = []
    token: Optional[str] = None
    for _ in range(MAX_PAGES_PER_ACCOUNT):
        params = dict(query) if token is None else {**query, "pageToken": token}
        page = await _get_json(client, url, params)
        pages.append(page)
        token = _next_page_token(page)
        if token is None:
            return pages
    raise UpstreamFormatChanged("Too many transaction pages for one account")


async def _fetch_pages(client: httpx.AsyncClient, base_url: str, account_id: str) -> list:
    query = {
        "businessType": "UserProfile",
        "include": "Merchant",
        "take": str(PAGE_SIZE),
        "includeDisabledAccounts": "true",
        "accountIds": account_id,
        "orderBy": "ByParsedData",
        "parsedDataNameToOrderBy": "accountingDate",
        "includeSummary": "true",
        "useAndSearchForParsedData": "false",
        "parsedData": json.dumps(TRANSACTIONS_PARSED_DATA, separators=(",", ":")),
    }
    return await _paginate(client, base_url + TRANSACTIONS_PATH, query)


async def _fetch_card_pages(client: httpx.AsyncClient, base_url: str, card_id: str) -> list:
    # Verified live 2026-10-07: `accountIds=<cardPfmId>` returns the card's
    # operations (accountId == cardPfmId, the card's own `pfmTransactions` link
    # uses it). The account `parsedData` filter (status AC, granularity ST/IN)
    # must NOT be sent: card operations are status UP / granularity XT and the
    # filter returns zero rows.
    return await _paginate(client, base_url + TRANSACTIONS_PATH, {**CARD_QUERY, "accountIds": card_id})


# --- Serialisation ---------------------------------------------------------------


def _dec(value: Decimal) -> str:
    return format(value, "f")  # never exponent notation, never a float


def _money(money: Optional[dict]) -> Optional[str]:
    return None if money is None else _dec(money["value"])


def _iso(value: Optional[date]) -> Optional[str]:
    return None if value is None else value.isoformat()


def _serialise_loan(loan: Optional[dict]) -> Optional[dict]:
    if loan is None:
        return None
    return {
        "borrowedAmount": _dec(loan["borrowedAmount"]),
        "interestRate": _dec(loan["interestRate"]),
        "monthlyPayment": _dec(loan["monthlyPayment"]),
        "insuranceMonthly": None
        if loan["insuranceMonthly"] is None
        else _dec(loan["insuranceMonthly"]),
        "signatureDate": _iso(loan["signatureDate"]),
        "firstDueDate": _iso(loan["firstDueDate"]),
        "lastDueDate": _iso(loan["lastDueDate"]),
        "durationMonths": loan["durationMonths"],
        "linkedIban": loan["linkedIban"],
    }


def _serialise_per(per: dict) -> dict:
    return {
        "balance": _dec(per["balance"]),
        "euroFundBalance": _dec(per["euroFundBalance"]),
        "valuationDate": per["valuationDate"],
        "positions": [
            {
                **{key: position[key] for key in ("isin", "name", "priceDate", "compartmentId")},
                **{key: _dec(position[key]) for key in ("quantity", "unitPrice", "value")},
            }
            for position in per["positions"]
        ],
    }


def _serialise_account(
    account: dict, transactions: list, loan: Optional[dict] = None, per: Optional[dict] = None
) -> dict:
    balance = account["balance"]
    kind = account["kind"]
    card = kind == "CARD"
    result = {
        "externalId": account["externalId"],
        "kind": kind,
        "name": account.get("name"),
        "balance": _dec(balance["value"]),
        "currency": balance["currency"],
        "iban": account.get("iban"),
        "ibanAmbiguous": account.get("ibanAmbiguous", False),
        "authorizedOverdraft": None if card else _money(account.get("authorizedOverdraft")),
        "ceiling": None if card else _money(account.get("ceiling")),
        "remainingDepositCapacity": None if card else _money(account.get("remainingDepositCapacity")),
        "fillingRatio": None
        if card or account.get("fillingRatio") is None
        else _dec(account["fillingRatio"]),
        "cardNature": account.get("nature") if card else None,
        "parentExternalId": account.get("parentExternalId") if card else None,
        "nextDueDate": _iso(account.get("nextDueDate")) if card else None,
        "transactions": [
            {
                "externalId": t["externalId"],
                "date": t["date"],
                "dueDate": t["dueDate"],
                "amount": _dec(t["amount"]),
                "currency": t["currency"],
                "label": t["label"],
                "typeCode": t.get("typeCode"),
            }
            for t in transactions
        ],
        "snapshotComplete": True,
        "loan": _serialise_loan(loan) if kind == "LOAN" else None,
    }
    if per is not None and kind == "PER":
        # The insurer valuation can be newer than the bank's synthesis total.
        result["balance"] = _dec(per["balance"])
        result["per"] = _serialise_per(per)
    return result


# --- Personal-loan detail -----------------------------------------------------------


LoanTokenProvider = Callable[[], Awaitable[str]]
PerDetailProvider = Callable[[str, str], Awaitable[Any]]

_CURRENT_KIND = "CURRENT_ACCOUNT"


async def _fetch_per_details(accounts: list, provider: Optional[PerDetailProvider]) -> tuple[dict, dict]:
    """`({externalId: parsed PER}, {externalId: code})`: complete positions, or the PER unavailable.

    A missing provider is legacy compatibility for direct callers. Production supplies it
    using the stored session and never substitutes a successful balance-only import on failure.
    `SessionExpired` and `InvalidSessionState` propagate: they are not specific to one
    contract. `HostNotAllowed` does not: the guard has already refused the request before
    anything was sent, and only this PER is left out.
    """
    details: dict = {}
    unavailable: dict = {}
    if provider is None:
        return details, unavailable
    for account in accounts:
        if account["kind"] != "PER":
            continue
        ext = account["externalId"]
        entity_code = account.get("perEntityCode")
        if (
            not isinstance(entity_code, str)
            or len(entity_code) != 5
            or not entity_code.isascii()
            or not entity_code.isdecimal()
        ):
            unavailable[ext] = UpstreamFormatChanged.code
            continue
        try:
            raw = await provider(ext[len(accounts_parser.CONTRACT_ID_PREFIX):], entity_code)
        except (replay.SessionExpired, replay.InvalidSessionState):
            raise
        except replay.ReplayError as exc:
            unavailable[ext] = exc.code
            continue
        except httpx.HTTPError:
            unavailable[ext] = UpstreamUnavailable.code
            continue
        try:
            details[ext] = per_parser.parse_per_detail(raw, currency=account["balance"]["currency"])
        except accounts_parser.ParseError as exc:
            unavailable[ext] = exc.code
    return details, unavailable


async def _fetch_loan_details(
    client: httpx.AsyncClient,
    base_url: str,
    accounts: list,
    loan_token: Optional[LoanTokenProvider],
    home_loan_token: Optional[LoanTokenProvider] = None,
    home_loan_base_url: str = HOME_LOAN_BASE_URL,
) -> tuple[dict, dict]:
    """`({externalId: parsed loan or None}, {externalId: code})` for every LOAN account.

    Personal and home loans each read their own app with its own token. Without a token
    provider a loan is null and imported (legacy). A token that cannot be minted leaves every
    loan of that product unavailable with its code; a detail that cannot be read leaves only
    its own loan unavailable. A `SessionExpired` while minting the personal-loan token, or
    from the personal-loan app, propagates: the session itself is dead, and the caller
    replays or fails. The home-loan app is another OAuth client on BPCE hosts: a refusal
    there, minting its token or reading the detail (401), leaves only the home loans
    unavailable.
    """
    loans = [a for a in accounts if a["kind"] == "LOAN"]
    details: dict = {a["externalId"]: None for a in loans}
    unavailable: dict = {}
    for product, provider in (("PERSONAL", loan_token), ("HOME", home_loan_token)):
        group = [a for a in loans if a.get("loanProduct", "PERSONAL") == product]
        if not group or provider is None:
            continue
        try:
            token = await provider()
        except replay.SessionExpired:
            # The main chain already proved the session in this run, so a refusal from the
            # BPCE home-loan client says nothing about the SSO session. Propagating it would
            # make `_fetch_accounts` replay then report SESSION_EXPIRED, which deactivates a
            # live session (same stance as the PER reader in main.py).
            if product != "HOME":
                raise
            unavailable.update({a["externalId"]: UpstreamUnavailable.code for a in group})
            continue
        except replay.ReplayError as exc:
            unavailable.update({a["externalId"]: exc.code for a in group})
            continue
        for account in group:
            ext = account["externalId"]
            contract_id = ext[len(accounts_parser.CONTRACT_ID_PREFIX):]
            if product == "HOME":
                entity_code = account.get("loanEntityCode")
                if not isinstance(entity_code, str) or not entity_code.isascii() or not entity_code.isalnum():
                    unavailable[ext] = UpstreamFormatChanged.code
                    continue
                url = home_loan_base_url + HOME_LOAN_DETAIL_PATH + urllib.parse.quote(
                    f"{entity_code}-{contract_id}", safe=""
                )
                parse = accounts_parser.parse_home_loan_detail
            else:
                url = base_url + LOAN_DETAIL_PATH + urllib.parse.quote(contract_id, safe="")
                parse = accounts_parser.parse_loan_detail
            # A 401 raises `replay.SessionExpired`. On the personal-loan app it means the
            # session is dead and propagates. The home-loan app is another OAuth client on
            # BPCE hosts, whose token was just minted from the live session: its refusal
            # leaves only this loan unavailable.
            try:
                payload = await _get_json(client, url, {}, bearer=token)
                details[ext] = parse(payload)
            except (FetchError, accounts_parser.ParseError) as exc:
                unavailable[ext] = exc.code
            except replay.SessionExpired:
                if product != "HOME":
                    raise
                unavailable[ext] = UpstreamUnavailable.code
    return details, unavailable


# --- Entry point ---------------------------------------------------------------------


async def fetch_import(
    access_token: str,
    *,
    transport: Optional[httpx.AsyncBaseTransport] = None,
    base_url: str = DATA_BASE_URL,
    today: Optional[date] = None,
    loan_token: Optional[LoanTokenProvider] = None,
    per_detail: Optional[PerDetailProvider] = None,
    home_loan_token: Optional[LoanTokenProvider] = None,
    home_loan_base_url: str = HOME_LOAN_BASE_URL,
) -> dict:
    """The `POST /accounts` response body, or a typed error.

    Only the current account can fail the whole import. Any other account that cannot be
    read or serialised is left out of `accounts` and listed in `unavailable`, in synthesis
    order, with its error code; the rest is returned.

    `loan_token` mints the personal-loan token and `home_loan_token` the home-loan one;
    each is only awaited when the synthesis holds a loan of its kind. Without it, the
    `loan` of those loans is null.
    """
    if not replay.is_allowed_url(base_url) or not replay.is_allowed_url(home_loan_base_url):
        raise replay.HostNotAllowed("Refused a URL outside https *.caisse-epargne.fr")
    base_url = base_url.rstrip("/")
    home_loan_base_url = home_loan_base_url.rstrip("/")

    client = replay.new_client(transport)
    try:
        client.headers["Authorization"] = f"Bearer {access_token}"
        synthesis = await _get_json(client, base_url + SYNTHESIS_PATH, dict(SYNTHESIS_QUERY))
        try:
            accounts, unsupported = accounts_parser._parse_synthesis(copy.deepcopy(synthesis))
        except accounts_parser.ParseError:
            raise UpstreamFormatChanged("Synthesis does not match the observed format") from None

        transactions: dict = {}
        unavailable: dict = {}  # externalId -> code
        for account in accounts:
            external_id = account["externalId"]
            kind = account["kind"]
            try:
                if kind == "CARD":
                    pages = await _fetch_card_pages(client, base_url, external_id)
                elif kind in accounts_parser.PFM_KINDS:
                    pages = await _fetch_pages(client, base_url, external_id)
                else:
                    # PER and loan: no PFM transactions, so no call and no IBAN.
                    pages = []
                transactions[external_id] = accounts_parser.build_account(account, pages, today=today)
            except FetchError as exc:
                if kind == _CURRENT_KIND:
                    raise
                unavailable[external_id] = exc.code
            except accounts_parser.ParseError as exc:
                if kind == _CURRENT_KIND:
                    # Code only: the parser's messages name fields, but stay on the safe side.
                    log.warning("accounts import rejected: %s", exc.code)
                    raise UpstreamFormatChanged("Payload does not match the observed format") from None
                unavailable[external_id] = exc.code

        loans, loan_unavailable = await _fetch_loan_details(
            client, base_url, accounts, loan_token, home_loan_token, home_loan_base_url
        )
        pensions, per_unavailable = await _fetch_per_details(accounts, per_detail)
        unavailable.update(loan_unavailable)
        unavailable.update(per_unavailable)

        result_accounts: list = []
        for account in accounts:
            external_id = account["externalId"]
            if external_id in unavailable:
                continue
            try:
                result_accounts.append(
                    _serialise_account(
                        account, transactions[external_id], loans.get(external_id), pensions.get(external_id)
                    )
                )
            except (KeyError, TypeError, ValueError) as exc:
                if account["kind"] == _CURRENT_KIND:
                    # Type only: the message may embed amounts or identifiers.
                    log.warning("accounts serialisation rejected: %s", type(exc).__name__)
                    raise UpstreamFormatChanged("Payload does not match the observed format") from None
                unavailable[external_id] = UpstreamFormatChanged.code

        unavailable_list = [
            {"externalId": a["externalId"], "kind": a["kind"], "code": unavailable[a["externalId"]]}
            for a in accounts
            if a["externalId"] in unavailable
        ]
        for item in unavailable_list:
            log.warning("account unavailable: kind=%s code=%s", item["kind"], item["code"])
        return {
            "accounts": result_accounts,
            "unsupported": [{"externalId": u["externalId"], "familyCode": u["familyCode"]} for u in unsupported],
            "unavailable": unavailable_list,
        }
    except FetchError as exc:
        log.warning("accounts fetch failed: %s", exc.code)
        raise
    finally:
        await client.aclose()
