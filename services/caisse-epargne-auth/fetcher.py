"""Reads the accounts and their transactions from the Caisse d'Epargne data host.

Read-only: GET calls only, with the Bearer token minted by `replay`. The raw
JSON goes to `accounts_parser.build_import`, whose result is serialised to the
`POST /accounts` contract (money as decimal strings, never floats).

All-or-nothing: any parse error, malformed page or exceeded page cap raises
`UpstreamFormatChanged`; a partial list is never returned. Nothing read from
the bank (balance, label, IBAN) or the token is logged or put in an error
message: errors carry a stable code and a fixed text.

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
from typing import Any, Optional

import httpx

import accounts_parser
import replay

log = logging.getLogger("caisse-epargne-auth")

DATA_BASE_URL = "https://www.rs-ext-bad-ce.caisse-epargne.fr"
SYNTHESIS_PATH = "/bapi/contract/v2/augmentedSynthesisViews"
TRANSACTIONS_PATH = "/pfm/user/v1.1/transactions"
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


async def _get_json(client: httpx.AsyncClient, url: str, params: dict) -> Any:
    """GET and decode a JSON body capped at `replay.MAX_RESPONSE_BYTES`."""
    try:
        async with client.stream(
            "GET", url, params=params, headers={"Accept": "application/json"}
        ) as response:
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


def _serialise_account(account: dict, transactions: list) -> dict:
    balance = account["balance"]
    kind = account["kind"]
    card = kind == "CARD"
    return {
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
        "transactions": [
            {
                "externalId": t["externalId"],
                "date": t["date"],
                "dueDate": t["dueDate"],
                "amount": _dec(t["amount"]),
                "currency": t["currency"],
                "label": t["label"],
            }
            for t in transactions
        ],
        "snapshotComplete": True,
    }


# --- Entry point ---------------------------------------------------------------------


async def fetch_import(
    access_token: str,
    *,
    transport: Optional[httpx.AsyncBaseTransport] = None,
    base_url: str = DATA_BASE_URL,
    today: Optional[date] = None,
) -> dict:
    """The `POST /accounts` response body, or a typed error (no partial result)."""
    if not replay.is_allowed_url(base_url):
        raise replay.HostNotAllowed("Refused a URL outside https *.caisse-epargne.fr")
    base_url = base_url.rstrip("/")

    client = replay.new_client(transport)
    try:
        client.headers["Authorization"] = f"Bearer {access_token}"
        synthesis = await _get_json(client, base_url + SYNTHESIS_PATH, dict(SYNTHESIS_QUERY))
        try:
            accounts = accounts_parser.parse_synthesis(synthesis)
        except accounts_parser.ParseError as exc:
            raise UpstreamFormatChanged("Synthesis does not match the observed format") from None

        pages_by_account: dict = {}
        for account in accounts:
            external_id = account["externalId"]
            if account["kind"] == "CARD":
                pages_by_account[external_id] = await _fetch_card_pages(client, base_url, external_id)
            else:
                pages_by_account[external_id] = await _fetch_pages(client, base_url, external_id)

        try:
            built = accounts_parser.build_import(
                copy.deepcopy(synthesis), pages_by_account, today=today
            )
        except accounts_parser.ParseError as exc:
            # Code only: the parser's messages name fields, but stay on the safe side.
            log.warning("accounts import rejected: %s", exc.code)
            raise UpstreamFormatChanged("Payload does not match the observed format") from None
        try:
            return {
                "accounts": [
                    _serialise_account(a, built["transactions"][a["externalId"]])
                    for a in built["accounts"]
                ],
                "unsupported": [
                    {"externalId": u["externalId"], "familyCode": u["familyCode"]}
                    for u in built["unsupported"]
                ],
            }
        except (KeyError, TypeError, ValueError) as exc:
            # Type only: the message may embed amounts or identifiers.
            log.warning("accounts serialisation rejected: %s", type(exc).__name__)
            raise UpstreamFormatChanged("Payload does not match the observed format") from None
    except FetchError as exc:
        log.warning("accounts fetch failed: %s", exc.code)
        raise
    finally:
        await client.aclose()
