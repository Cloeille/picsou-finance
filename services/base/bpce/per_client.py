"""Insurer detail transport (bearer + insurer JWT, no HTML navigation).

Observed live on 2026-10-15 in the bank's own web front, for a Millevie PER:

  0. The bank front calls POST .../bapi/contract/v1/contracts/{entity}-{contractId}/
     prepareRouting with the MAIN bearer -> 201, routing mode 1 to the insurer host and
     query parameters `cdEtab`, `codeTransaction`, `producerContratId`, `urlRetour`.
     The insurer's `producerContratId` (product 3 chars, company 2, then the number) is
     NOT derivable from the bank contract id: a computed `PERIC<suffix>` made
     clientToken answer 500 and the detail 403. Only the id read here works, and the
     routing call itself is kept because the insurer may rely on its server-side context.
     The routing URL is never followed.
  1. The insurer app mints its own OAuth token with the SSO session (see
     `Site.per.chain`): granted scope `assuranceVieAuth_get_<id> readUser`,
     `expires_in` 3600.
  2. GET https://<gateway>/natixis/assurance/
         assuranceVieAuth/v1/clientToken/{producerContratId}
     headers: Authorization: Bearer <token>, iw4-user-token: <token>,
              iw4-app-code: IW4, iw4-company-code: <cdetab>,
              iw4-product-code: <first 3 chars of producerContratId>
     -> 200, the insurer JWT in the `iw4-jwt-token` RESPONSE header.
  3. GET https://<insurer>/etna-web/
         rest/v2/contratVie/load/{product}/{company}/{number}
     same headers plus `iw4-jwt-token: <JWT>` -> 200 JSON with
     `donneesContrat` and `detailContratVie`.

The gateway and insurer hosts are the site's (`Site.per`): Caisse d'Epargne
`www.rs-ext-bad-ce.caisse-epargne.fr` and `www.espace-assurances-de-personnes.caisse-epargne.fr`
(observed live), Banque Populaire `www.rs-ext-bad-ib.banquepopulaire.fr` and
`www.espace-assurances-de-personnes.banquepopulaire.fr` (observed live, issue #524). No cookie is sent
to either host, no redirect is followed, and nothing read from the insurer
(tokens, JWT, amounts) is logged: failures log a stage name and an HTTP status.
"""

from __future__ import annotations

import json
import logging
import re
import urllib.parse
from typing import Any

import httpx

from . import replay, shape
from .site import Site


log = logging.getLogger("bpce")

CLIENT_TOKEN_PATH = "/natixis/assurance/assuranceVieAuth/v1/clientToken"
DETAIL_PATH = "/etna-web/rest/v2/contratVie/load"

APP_CODE = "IW4"
JWT_HEADER = "iw4-jwt-token"

_ENTITY_CODE_RE = re.compile(r"[0-9]{5}\Z")
# The insurer products this reader knows, each pinned to its exact codes (never a
# `[A-Z]{3}` wildcard): a PER (`PERIC` at Caisse d'Epargne, `PERIB` at Banque Populaire)
# and an assurance vie (`ESSEN`, Millevie Essentielle). The bank contract id is
# `<entity> <PRODUCT><number>` (or compact `<PRODUCT><number>` for PERIB, observed live
# on Banque Populaire); the insurer's own id is `<product 3><company 2><number>`
# and starts with the same five letters. The `IB` code is Banque Populaire's (issue #524);
# its number part is kept digits-only like `IC`'s because no live shape says otherwise yet,
# and an id that differs is refused with a masked log (`reason=producer-id-format`).
_CONTRACT_ID_RE = re.compile(r"(?:[0-9]{5} {1,16}|(?:ASV)?(?=PERIB))(?P<product>PERIC|PERIB|ESSEN)(?P<number>[0-9]{1,64})\Z")
_PRODUCER_ID_RE = re.compile(r"(?P<product>PER|ESS)(?P<company>IC|IB|EN)(?P<number>[0-9]{1,32})\Z")
_PRODUCER_PAIRS = frozenset({("PER", "IC"), ("PER", "IB"), ("ESS", "EN")})
ROUTING_PATH = "/bapi/contract/v1/contracts"
_ROUTING_BODY = {
    "characteristics": {
        "contractActionType": {"code": "", "label": ""},
        "productFamilyPFM": {"code": "", "label": ""},
        "returnUrl": {},
        "routingContext": {
            "suppliedName": "routingContext",
            "value": {"queryParameters": {}, "formParameters": {}},
        },
    },
    "response": {"code": "", "interactionId": "", "label": ""},
}
_BEARER_RE = re.compile(r"[A-Za-z0-9._~+/-]+=*\Z")
_JWT_RE = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*\Z")
_MAX_TOKEN_LENGTH = 8192


class InvalidPerRequest(replay.ReplayError):
    """The caller did not supply an unambiguous PER request."""

    code = "INVALID_PER_REQUEST"


class IncompleteInsurerDetail(replay.UpstreamError):
    """The insurer answered with valid JSON that lacks the contract detail object.

    A format change rather than a transport failure: raised apart from the other upstream
    errors so the caller can tell the two apart. Its code is the usual upstream one.
    """


class UnknownPerContract(InvalidPerRequest):
    """The contract id is not one of the insurer products this reader knows."""


class _DuplicateKey(ValueError):
    pass


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKey
        result[key] = value
    return result


def _reject_json_constant(_: str) -> None:
    raise ValueError


def _parse_contract_id(contract_id: Any) -> re.Match:
    if not isinstance(contract_id, str) or len(contract_id) > 128:
        raise UnknownPerContract("Invalid PER contract identifier")
    match = _CONTRACT_ID_RE.fullmatch(contract_id)
    if match is None:
        raise UnknownPerContract("Invalid PER contract identifier")
    return match


def _validate_entity_code(entity_code: Any) -> str:
    if not isinstance(entity_code, str) or _ENTITY_CODE_RE.fullmatch(entity_code) is None:
        raise InvalidPerRequest("Invalid PER entity code")
    return entity_code


def _validate_token(access_token: Any) -> str:
    if (
        not isinstance(access_token, str)
        or not access_token
        or len(access_token) > _MAX_TOKEN_LENGTH
        or _BEARER_RE.fullmatch(access_token) is None
    ):
        raise InvalidPerRequest("Invalid access token")
    return access_token


def _guard_url(site: Site, url: str, expected_host: str) -> str:
    """Belt and braces: the exact https host, default port, no credentials."""
    try:
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except ValueError:
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host") from None
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower() != expected_host
        or parsed.username is not None
        or parsed.password is not None
        or port not in (None, 443)
        or parsed.fragment
        or not replay.is_allowed_url(site, url)
    ):
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    return url


def _json_object(body: bytes) -> dict[str, Any]:
    try:
        decoded = json.loads(
            body,
            object_pairs_hook=_unique_object,
            parse_constant=_reject_json_constant,
        )
    except (ValueError, TypeError, UnicodeError, RecursionError):
        # RecursionError: a deeply nested body under the size cap.
        raise replay.UpstreamError("Insurer returned invalid JSON") from None
    if not isinstance(decoded, dict):
        raise replay.UpstreamError("Insurer returned an invalid object")
    return decoded


async def _get(
    client: httpx.AsyncClient,
    url: str,
    headers: dict[str, str],
    *,
    stage: str,
    method: str = "GET",
    json_body: dict[str, Any] | None = None,
) -> tuple[int, bytes, httpx.Headers]:
    """One request with a capped body. Statuses are checked by the caller."""
    try:
        async with client.stream(method, url, headers=headers, json=json_body) as response:
            declared = response.headers.get("content-length", "")
            if declared.isdecimal() and int(declared) > replay.MAX_RESPONSE_BYTES:
                raise replay.UpstreamError("PER upstream response is too large")
            chunks: list[bytes] = []
            size = 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > replay.MAX_RESPONSE_BYTES:
                    raise replay.UpstreamError("PER upstream response is too large")
                chunks.append(chunk)
            return response.status_code, b"".join(chunks), response.headers
    except replay.ReplayError:
        raise
    except (httpx.HTTPError, UnicodeError, ValueError):
        # Transport exceptions may quote request headers or URLs.
        log.warning("PER detail transport failed: stage=%s", stage)
        raise replay.UpstreamError("PER upstream request failed") from None


def _check_status(status: int, *, stage: str) -> None:
    if 200 <= status < 300:
        return
    log.warning("insurer detail refused: stage=%s status=%d", stage, status)
    if status == 401:
        raise replay.SessionExpired("Insurer token is no longer accepted")
    # 403 is a denial, not an expired session: it must not trigger a replay.
    raise replay.UpstreamError("Insurer request failed")


def _producer_id(body: bytes, *, entity_code: str, insurer_host: str, product: str | None = None) -> str:
    """The insurer contract id from a prepareRouting answer, bound to this contract.

    `product` is the five-letter product code of the bank contract id asked for; the
    answer must be for the same product.
    """
    root = _json_object(body)
    # Three shapes: the characteristics object itself (logged live), or wrapped in
    # `characteristics`, or in `prepareRouting.characteristics`.
    wrapped = root.get("prepareRouting")
    holder = wrapped if isinstance(wrapped, dict) else root
    characteristics = holder.get("characteristics", holder if "routingMode" in holder else None)
    if not isinstance(characteristics, dict):
        raise replay.UpstreamError("insurer routing response is incomplete")
    href = (characteristics.get("routingUrl") or {}).get("href") if isinstance(
        characteristics.get("routingUrl"), dict) else None
    context = characteristics.get("routingContext")
    value = context.get("value") if isinstance(context, dict) else None
    query = value.get("queryParameters") if isinstance(value, dict) else None
    if not isinstance(href, str) or not isinstance(query, dict):
        log.warning("insurer detail refused: stage=routing reason=incomplete")
        raise replay.UpstreamError("insurer routing response is incomplete")
    # The routing target is never followed; it must still be the insurer we call.
    if (urllib.parse.urlsplit(href).hostname or "").lower() != insurer_host:
        log.warning("insurer detail refused: stage=routing reason=other-host")
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    producer_id = query.get("producerContractId") or query.get("producerContratId")
    if (
        not isinstance(producer_id, str)
        or (match := _PRODUCER_ID_RE.fullmatch(producer_id)) is None
        or (match.group("product"), match.group("company")) not in _PRODUCER_PAIRS
    ):
        # Masked shape only (capped, digits never, letters only with
        # `CE_LOG_PRODUCT_DETAILS=1`): what a live run needs to pin a new insurer's id format.
        log.warning(
            "insurer detail refused: stage=routing reason=producer-id-format shape=%s",
            shape.mask(producer_id, cap=32, letters=shape.log_details()),
        )
        raise replay.UpstreamError("insurer routing returned an unusable contract id")
    if product is not None and not producer_id.startswith(product):
        log.warning("insurer detail refused: stage=routing reason=other-product")
        raise replay.UpstreamError("insurer routing answered for another product")
    query_entity = query.get("cdEtab") or query.get("cdetab")
    if query_entity != entity_code:
        log.warning("insurer detail refused: stage=routing reason=other-establishment")
        raise replay.UpstreamError("insurer routing answered for another establishment")
    # Consistency of the answer. Caisse d'Epargne echoes the insurer id (`PERIC` + digits).
    # Banque Populaire echoes the bank contract id (`ASV` + insurer id). Both agree
    # with us on the establishment and identify this contract.
    echoed = characteristics.get("contractId")
    echoed_id = echoed.get("id") if isinstance(echoed, dict) else None
    valid_echoed_ids = {producer_id, f"ASV{producer_id}"} if product == "PERIB" else {producer_id}
    if (
        not isinstance(echoed, dict)
        or echoed_id not in valid_echoed_ids
        or echoed.get("entityCode") != entity_code
    ):
        log.warning("insurer detail refused: stage=routing reason=other-contract")
        raise replay.UpstreamError("insurer routing answered for another contract")
    return producer_id


def build_headers(access_token: str, entity_code: str, producer_id: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {access_token}",
        "iw4-user-token": access_token,
        "iw4-app-code": APP_CODE,
        "iw4-company-code": entity_code,
        "iw4-product-code": producer_id[:3],
        "Accept": "application/json, text/plain, */*",
    }


async def fetch_per_detail(
    site: Site,
    access_token: str,
    contract_id: str,
    *,
    entity_code: str,
    routing_token: str,
    transport: httpx.AsyncBaseTransport | None = None,
) -> dict[str, Any]:
    """Return the raw `contratVie/load` JSON object of one PER contract.

    `routing_token` is the MAIN bearer, sent only to prepareRouting. `access_token`
    is the PER-chain token (`Site.per.chain`), sent only to clientToken and the
    detail. Raises `InvalidPerRequest` for a malformed request (nothing is sent),
    `SessionExpired` on a 401 with the PER token, `HostNotAllowed` if a URL leaves
    its exact host, and `UpstreamError` for any other failure (a routing 401 too:
    the main token has just read the synthesis, so it is PER-specific).
    """
    if site.per is None:
        raise InvalidPerRequest("This site has no PER app")
    gateway_host, insurer_host = site.per.gateway_host, site.per.insurer_host
    _validate_token(access_token)
    _validate_token(routing_token)
    entity_code = _validate_entity_code(entity_code)
    product = _parse_contract_id(contract_id).group("product")
    route_segment = urllib.parse.quote(f"{entity_code}-{contract_id}", safe="")
    routing_url = _guard_url(
        site, f"https://{gateway_host}{ROUTING_PATH}/{route_segment}/prepareRouting", gateway_host
    )

    client = replay.new_client(transport)
    try:
        # No cookie ever goes to the gateway or the insurer: the jar is emptied before
        # each call, so a Set-Cookie on one step is not replayed on the next.
        client.cookies.clear()
        status, body, _ = await _get(
            client, routing_url, {"Authorization": f"Bearer {routing_token}",
                                  "Accept": "application/json, text/plain, */*"},
            stage="routing", method="POST", json_body=_ROUTING_BODY,
        )
        if not 200 <= status < 300:
            log.warning("insurer detail refused: stage=routing status=%d", status)
            raise replay.UpstreamError("insurer routing request failed")
        producer_id = _producer_id(
            body, entity_code=entity_code, insurer_host=insurer_host, product=product
        )
        match = _PRODUCER_ID_RE.fullmatch(producer_id)
        token_url = _guard_url(site, f"https://{gateway_host}{CLIENT_TOKEN_PATH}/{producer_id}", gateway_host)
        detail_url = _guard_url(
            site,
            f"https://{insurer_host}{DETAIL_PATH}/"
            f"{match.group('product')}/{match.group('company')}/{match.group('number')}",
            insurer_host,
        )
        headers = build_headers(access_token, entity_code, producer_id)

        client.cookies.clear()
        status, _, response_headers = await _get(client, token_url, headers, stage="client-token")
        _check_status(status, stage="client-token")
        jwt = response_headers.get(JWT_HEADER, "")
        if not jwt or len(jwt) > _MAX_TOKEN_LENGTH or _JWT_RE.fullmatch(jwt) is None:
            log.warning("insurer detail refused: stage=client-token reason=no-jwt")
            raise replay.UpstreamError("Insurer token is missing")

        client.cookies.clear()
        status, body, _ = await _get(
            client, detail_url, {**headers, JWT_HEADER: jwt}, stage="contract-detail"
        )
        _check_status(status, stage="contract-detail")
        result = _json_object(body)
        if not isinstance(result.get("detailContratVie"), dict):
            raise IncompleteInsurerDetail("Insurer detail response is incomplete")
        try:
            serialized = json.dumps(result, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError):
            raise replay.UpstreamError("Insurer detail response is malformed") from None
        if access_token in serialized or routing_token in serialized or jwt in serialized:
            raise replay.UpstreamError("Insurer detail response contained forbidden data")
        return result
    finally:
        await client.aclose()
