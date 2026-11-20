"""Reads the accounts and their transactions from a BPCE bank's data host.

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
from datetime import date, timedelta
from decimal import Decimal
from typing import Any, Awaitable, Callable, Optional

import httpx

from . import accounts_parser, per_client, per_parser, replay, shape
from .site import Site

log = logging.getLogger("bpce")

# The data host is the site's (`Site.data_base_url`, `Site.home_loan.base_url`).
SYNTHESIS_PATH = "/bapi/contract/v2/augmentedSynthesisViews"
TRANSACTIONS_PATH = "/pfm/user/v1.1/transactions"
# Observed live 2026-10-13, read with the personal-loan token (the main token
# gets 403). The last segment is the synthesis `contractId.id`, blanks stripped.
LOAN_DETAIL_PATH = "/bapi/personalLoanPrd/v1/personalLoanPrdSynthesisView/"
# Observed live 2026-10-25, read with the home-loan token on the BPCE host.
# The last segment is `<contractId.entityCode>-<contractId.id>`.
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
# Accounts and savings are queried in `accountingDate` order and read back to this
# many days before today, then paging stops: a long-held current account has far
# more history than one import can page through before the deadline (issue #397).
# The direction is not confirmed live: the early stop only applies once rows are
# observed newest first (see `_paginate`).
HISTORY_DAYS = 365
# More pages than this for one account, before the history window is reached,
# fails the import: never a silent truncation. 60 pages of 50 for one account take
# ~54 s at the ~0.9 s per call observed live, but all accounts and cards are read in
# sequence under ONE shared 100 s `/accounts` deadline (main.py
# ACCOUNTS_DEADLINE_SECONDS). Two accounts near the cap (e.g. a dense card, which has
# no history window, plus a current account paged to the end because its order was
# not newest first) can exceed it first: the import then fails with
# UPSTREAM_UNAVAILABLE and the log says `accounts deadline exceeded` instead of
# `page-cap`. Nothing partial is returned either way.
MAX_PAGES_PER_ACCOUNT = 60


class FetchError(Exception):
    """Base class. `code` is the stable, caller-facing error code; messages are fixed."""

    code = "UPSTREAM_UNAVAILABLE"
    # Logged when the import fails: a fixed token, the HTTP status or the
    # transport exception's class name. Never a URL, a body or a message.
    # Each subclass names its own default, so a raise without one never
    # reports another class's cause.
    cause = "unknown"

    def __init__(self, message: Optional[str] = None, *, cause: Optional[str] = None):
        super().__init__(message or self.code)
        if cause is not None:
            self.cause = cause


class UpstreamUnavailable(FetchError):
    code = "UPSTREAM_UNAVAILABLE"


class UpstreamFormatChanged(FetchError):
    code = "UPSTREAM_FORMAT_CHANGED"
    cause = "format"


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
                    raise UpstreamUnavailable("Upstream response too large", cause="too-large")
                chunks.append(chunk)
            status = response.status_code
            body = b"".join(chunks)
    except httpx.HTTPError as exc:
        # Fixed message, no chained cause: httpx errors can embed URLs. The class
        # name alone (ReadTimeout, ConnectError...) is safe to log.
        raise UpstreamUnavailable("Upstream request failed", cause=type(exc).__name__) from None
    if status == 401:
        raise replay.SessionExpired("Token no longer accepted")
    if status != 200:
        raise UpstreamUnavailable("Upstream answered an unexpected status", cause=f"status-{status}")
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


def _accounting_dates(page: dict) -> list:
    """The page's `parsedData.accountingDate` values, in row order; rows without one are skipped."""
    found: list = []
    for row in page["data"]:
        parsed = row.get("parsedData") if isinstance(row, dict) else None
        value = parsed.get("accountingDate") if isinstance(parsed, dict) else None
        if value is None:
            continue
        try:
            found.append(accounts_parser._iso_date(value, accounts_parser.INVALID_TRANSACTIONS, "accountingDate"))
        except accounts_parser.ParseError:
            raise UpstreamFormatChanged("Transactions carry an unreadable accounting date") from None
    return found


async def _paginate(client: httpx.AsyncClient, url: str, query: dict, *, since: Optional[date] = None) -> list:
    """Every page, or with `since` the pages down to the first row booked before it.

    The early stop needs newest-first rows. The sort direction was never observed
    live, so it applies only once a row booked strictly earlier than the one before
    it has been read; a row newer than one already read turns it off for good. One
    old row, or rows all booked on one day, prove no order: the account is paged on.
    """
    pages: list = []
    token: Optional[str] = None
    oldest: Optional[date] = None
    descending = False
    for _ in range(MAX_PAGES_PER_ACCOUNT):
        params = dict(query) if token is None else {**query, "pageToken": token}
        page = await _get_json(client, url, params)
        pages.append(page)
        token = _next_page_token(page)
        if since is not None:
            for booked in _accounting_dates(page):
                if oldest is not None:
                    if booked > oldest:
                        since = None
                        break
                    descending = descending or booked < oldest
                oldest = booked
            if since is not None and descending and oldest is not None and oldest < since:
                return pages
        if token is None:
            return pages
    raise UpstreamFormatChanged("Too many transaction pages for one account", cause="page-cap")


async def _fetch_pages(client: httpx.AsyncClient, base_url: str, account_id: str, *, since: date) -> list:
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
    return await _paginate(client, base_url + TRANSACTIONS_PATH, query, since=since)


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


_mask = shape.mask  # one masking rule for every diagnostic log field (see `shape`)
_log_details = shape.log_details


def _id_shape(external_id: str) -> str:
    """The contract id, masked, without its `contract:` prefix."""
    return _mask(
        external_id.removeprefix(accounts_parser.CONTRACT_ID_PREFIX), cap=32, letters=_log_details()
    )


def _product_label(label: Optional[str]) -> str:
    """The bank's catalogue `productLabel`, masked and cut at 40 characters."""
    return _mask(label, cap=40, letters=True)


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
    if per is not None and kind in accounts_parser.INSURER_KINDS:
        # The insurer valuation can be newer than the bank's synthesis total.
        result["balance"] = _dec(per["balance"])
        result["per"] = _serialise_per(per)
    return result


# --- Personal-loan detail -----------------------------------------------------------


LoanTokenProvider = Callable[[], Awaitable[str]]
PerDetailProvider = Callable[[str, str], Awaitable[Any]]

_CURRENT_KIND = "CURRENT_ACCOUNT"


async def _fetch_per_details(accounts: list, provider: Optional[PerDetailProvider]) -> tuple[dict, dict, dict]:
    """`({externalId: parsed insurer detail}, {externalId: code})` for every PER and assurance vie.

    Complete positions, or that contract unavailable.

    A missing provider is legacy compatibility for direct callers. Production supplies it
    using the stored session and never substitutes a successful balance-only import on failure.
    `SessionExpired` and `InvalidSessionState` propagate: they are not specific to one
    contract. `HostNotAllowed` does not: the guard has already refused the request before
    anything was sent, and only this PER is left out.
    """
    details: dict = {}
    unavailable: dict = {}
    # Contracts to list as unsupported besides `unavailable`, with the reason: a product the
    # insurer reader does not know, or an answer whose shape changed (not a transport failure).
    listed: dict = {}
    if provider is None:
        return details, unavailable, listed
    for account in accounts:
        if account["kind"] not in accounts_parser.INSURER_KINDS:
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
            if isinstance(exc, per_client.UnknownPerContract):
                # Refused before any request: the contract id is not one the insurer
                # reader knows. Its masked shape tells a new insurer product (one filed
                # under the PER code) apart, without any number.
                log.info("insurer request refused: idShape=%s", _id_shape(ext))
                listed[ext] = "unknown insurer product"
            elif isinstance(exc, per_client.IncompleteInsurerDetail) and account["kind"] == "ASSURANCE_VIE":
                listed[ext] = "insurer detail"
            unavailable[ext] = exc.code
            continue
        except httpx.HTTPError:
            unavailable[ext] = UpstreamUnavailable.code
            continue
        try:
            details[ext] = per_parser.parse_per_detail(
                raw,
                currency=account["balance"]["currency"],
                undated_prices_ok=account["kind"] == "ASSURANCE_VIE",
            )
        except accounts_parser.ParseError as exc:
            # Field path only (the parser names fields and positions, never a value): which
            # part of an insurer answer changed, for a product seen in few live runs.
            log.warning(
                "insurer detail unreadable: kind=%s rejected=%s", account["kind"], accounts_parser._reason(exc)
            )
            if account["kind"] == "ASSURANCE_VIE":
                listed[ext] = "insurer detail"
            unavailable[ext] = exc.code
    return details, unavailable, listed


async def _fetch_loan_details(
    client: httpx.AsyncClient,
    base_url: str,
    accounts: list,
    loan_token: Optional[LoanTokenProvider],
    home_loan_token: Optional[LoanTokenProvider] = None,
    home_loan_base_url: Optional[str] = None,
    *,
    provisional: bool = False,
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

    `provisional` (`"LOAN" in Site.provisional_kinds`) is for a site whose loan clients were
    read from its public web app and never observed live. Then nothing a loan app answers
    can cost more than its own detail: a `SessionExpired` never propagates (an unverified
    client must not deactivate a live session), and a loan whose token cannot be minted or
    whose detail cannot be read is still imported, with a null `loan`, instead of unavailable.
    """
    loans = [a for a in accounts if a["kind"] == "LOAN"]
    details: dict = {a["externalId"]: None for a in loans}
    unavailable: dict = {}

    def leave_out(account: dict, code: str, product: str) -> None:
        if provisional:
            log.warning("loan imported without detail: product=%s", product)
        else:
            unavailable[account["externalId"]] = code

    def unavailable_loan(account: dict, code: str, product: str) -> None:
        log.warning("loan detail unavailable: product=%s code=%s", product, code)
        leave_out(account, code, product)

    for product, provider in (("PERSONAL", loan_token), ("HOME", home_loan_token)):
        group = [a for a in loans if a.get("loanProduct", "PERSONAL") == product]
        if not group or provider is None:
            continue
        if product == "HOME" and home_loan_base_url is None:
            # A home-loan token without the site's home-loan host: nothing to read it from.
            for account in group:
                unavailable_loan(account, UpstreamUnavailable.code, product)
            continue
        try:
            token = await provider()
        except replay.SessionExpired:
            # The main chain already proved the session in this run, so a refusal from the
            # BPCE home-loan client says nothing about the SSO session. Propagating it would
            # make `_fetch_accounts` replay then report SESSION_EXPIRED, which deactivates a
            # live session (same stance as the PER reader in main.py).
            if product != "HOME" and not provisional:
                raise
            for account in group:
                unavailable_loan(account, UpstreamUnavailable.code, product)
            continue
        except replay.ReplayError as exc:
            for account in group:
                unavailable_loan(account, exc.code, product)
            continue
        for account in group:
            ext = account["externalId"]
            contract_id = ext[len(accounts_parser.CONTRACT_ID_PREFIX):]
            if product == "HOME":
                entity_code = account.get("loanEntityCode")
                if not isinstance(entity_code, str) or not entity_code.isascii() or not entity_code.isalnum():
                    unavailable_loan(account, UpstreamFormatChanged.code, product)
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
            except accounts_parser.ParseError as exc:
                # Field path only (the parser names fields and positions, never a value):
                # which part of a loan answer changed.
                log.warning(
                    "loan detail unreadable: product=%s code=%s rejected=%s",
                    product,
                    exc.code,
                    accounts_parser._reason(exc),
                )
                leave_out(account, exc.code, product)
            except FetchError as exc:
                unavailable_loan(account, exc.code, product)
            except replay.SessionExpired:
                if product != "HOME" and not provisional:
                    raise
                unavailable_loan(account, UpstreamUnavailable.code, product)
    return details, unavailable


# --- Entry point ---------------------------------------------------------------------


async def fetch_import(
    site: Site,
    access_token: str,
    *,
    transport: Optional[httpx.AsyncBaseTransport] = None,
    base_url: Optional[str] = None,
    today: Optional[date] = None,
    loan_token: Optional[LoanTokenProvider] = None,
    per_detail: Optional[PerDetailProvider] = None,
    home_loan_token: Optional[LoanTokenProvider] = None,
    home_loan_base_url: Optional[str] = None,
) -> dict:
    """The `POST /accounts` response body, or a typed error.

    Only the current account can fail the whole import. Any other account that cannot be
    read or serialised is left out of `accounts` and listed in `unavailable`, in synthesis
    order, with its error code; the rest is returned.

    `loan_token` mints the personal-loan token and `home_loan_token` the home-loan one;
    each is only awaited when the synthesis holds a loan of its kind. Without it, the
    `loan` of those loans is null. A family the site has no app for (`Site.unsupported_families`)
    is listed in `unsupported` and never read.
    """
    base_url = site.data_base_url if base_url is None else base_url
    if home_loan_base_url is None and site.home_loan is not None:
        home_loan_base_url = site.home_loan.base_url
    if not replay.is_allowed_url(site, base_url) or (
        home_loan_base_url is not None and not replay.is_allowed_url(site, home_loan_base_url)
    ):
        raise replay.HostNotAllowed(f"Refused a URL outside https *.{site.domain}")
    base_url = base_url.rstrip("/")
    if home_loan_base_url is not None:
        home_loan_base_url = home_loan_base_url.rstrip("/")

    client = replay.new_client(transport)
    stage = "synthesis"  # which step failed, logged with the code
    try:
        client.headers["Authorization"] = f"Bearer {access_token}"
        synthesis = await _get_json(client, base_url + SYNTHESIS_PATH, dict(SYNTHESIS_QUERY))
        try:
            accounts, unsupported = accounts_parser._parse_synthesis(
                copy.deepcopy(synthesis),
                skipped_families=site.unsupported_families,
                import_cards=site.cards,
                provisional_kinds=site.provisional_kinds,
            )
        except accounts_parser.ParseError:
            raise UpstreamFormatChanged("Synthesis does not match the observed format") from None

        since = accounts_parser._today(today) - timedelta(days=HISTORY_DAYS)
        transactions: dict = {}
        unavailable: dict = {}  # externalId -> code
        stage = "transactions"  # only the current account's failure gets past the loop
        for account in accounts:
            external_id = account["externalId"]
            kind = account["kind"]
            try:
                if kind == "CARD":
                    pages = await _fetch_card_pages(client, base_url, external_id)
                elif kind in accounts_parser.PFM_KINDS:
                    pages = await _fetch_pages(client, base_url, external_id, since=since)
                else:
                    # PER and loan: no PFM transactions, so no call and no IBAN.
                    pages = []
                transactions[external_id] = accounts_parser.build_account(
                    account, pages, today=today, provisional_kinds=site.provisional_kinds
                )
            except (FetchError, accounts_parser.ParseError) as exc:
                if kind == _CURRENT_KIND:
                    if isinstance(exc, FetchError):
                        raise
                    # Code only: the parser's messages name fields, but stay on the safe side.
                    log.warning("accounts import rejected: %s", exc.code)
                    raise UpstreamFormatChanged("Payload does not match the observed format") from None
                if kind in site.provisional_kinds:
                    # Seen in one live run only: imported from its synthesis entry (balance and
                    # name) with no transactions, rather than dropped into `unavailable`, which
                    # the backend ignores and which would leave it missing with no trace.
                    log.warning("account imported without transactions: kind=%s code=%s", kind, exc.code)
                    transactions[external_id] = []
                    continue
                unavailable[external_id] = exc.code

        loans, loan_unavailable = await _fetch_loan_details(
            client,
            base_url,
            accounts,
            loan_token,
            home_loan_token,
            home_loan_base_url,
            provisional="LOAN" in site.provisional_kinds,
        )
        pensions, per_unavailable, per_listed = await _fetch_per_details(accounts, per_detail)
        unavailable.update(loan_unavailable)
        unavailable.update(per_unavailable)
        for account in accounts:
            # An assurance vie whose insurer answer changed shape (unparseable detail, or no detail
            # object) and a `4/05` contract whose product the insurer reader does not know are also
            # listed as unsupported, the one list the Sync tab shows (`unavailable` is not shown).
            # A network or session failure is not a format change and is not listed.
            reason = per_listed.get(account["externalId"])
            if reason:
                unsupported.append(
                    {
                        "externalId": account["externalId"],
                        "hasPfmId": False,
                        "familyCode": "4",
                        "subFamilyCode": "05",
                        "productLabel": None,
                        "rejected": reason,
                    }
                )

        stage = "serialisation"
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
        for item in unsupported:
            # Codes and a digit-masked id shape only: enough to tell a new product
            # family apart (its letters, e.g. `PERIC`), never a contract number.
            # The codes are already normalised by the parser (short and plain, or `?`).
            fields = "family=%s sub=%s pfmId=%s idShape=%s"
            args = [
                item["familyCode"],
                item["subFamilyCode"],
                "present" if item["hasPfmId"] else "absent",
                _id_shape(item["externalId"]),
            ]
            if _log_details():
                fields += " product=%s"
                args.append(_product_label(item["productLabel"]))
            if item.get("rejected"):
                # Field path only (e.g. `items[3].balance: missing`): a provisional kind
                # demoted for a field it could not read, not a product never supported.
                fields += " rejected=%s"
                args.append(item["rejected"])
            log.info("unsupported contract: " + fields, *args)
        return {
            "accounts": result_accounts,
            "unsupported": [
                {
                    "externalId": u["externalId"],
                    "familyCode": u["familyCode"],
                    "subFamilyCode": u["subFamilyCode"],
                }
                for u in unsupported
            ],
            "unavailable": unavailable_list,
        }
    except FetchError as exc:
        log.warning("accounts fetch failed: %s stage=%s cause=%s", exc.code, stage, exc.cause)
        raise
    finally:
        await client.aclose()
