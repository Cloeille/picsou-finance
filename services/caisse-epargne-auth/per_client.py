"""PER insurer detail transport (bearer + insurer JWT, no HTML navigation).

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
     `replay.PER_CHAIN`): granted scope `assuranceVieAuth_get_<id> readUser`,
     `expires_in` 3600.
  2. GET https://www.rs-ext-bad-ce.caisse-epargne.fr/natixis/assurance/
         assuranceVieAuth/v1/clientToken/{producerContratId}
     headers: Authorization: Bearer <token>, iw4-user-token: <token>,
              iw4-app-code: IW4, iw4-company-code: <cdetab>,
              iw4-product-code: <first 3 chars of producerContratId>
     -> 200, the insurer JWT in the `iw4-jwt-token` RESPONSE header.
  3. GET https://www.espace-assurances-de-personnes.caisse-epargne.fr/etna-web/
         rest/v2/contratVie/load/{product}/{company}/{number}
     same headers plus `iw4-jwt-token: <JWT>` -> 200 JSON with
     `donneesContrat` and `detailContratVie`.

No cookie is sent
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

import replay


log = logging.getLogger("caisse-epargne-auth")

GATEWAY_HOST = "www.rs-ext-bad-ce.caisse-epargne.fr"
CLIENT_TOKEN_BASE = f"https://{GATEWAY_HOST}/natixis/assurance/assuranceVieAuth/v1/clientToken"
INSURER_HOST = "www.espace-assurances-de-personnes.caisse-epargne.fr"
DETAIL_BASE = f"https://{INSURER_HOST}/etna-web/rest/v2/contratVie/load"

APP_CODE = "IW4"
JWT_HEADER = "iw4-jwt-token"

_ENTITY_CODE_RE = re.compile(r"[0-9]{5}\Z")
_CONTRACT_ID_RE = re.compile(r"[0-9]{5} {1,16}PERIC(?P<number>[0-9]{1,64})\Z")
_PRODUCER_ID_RE = re.compile(r"(?P<product>PER)(?P<company>IC)(?P<number>[0-9]{1,32})\Z")
ROUTING_BASE = f"https://{GATEWAY_HOST}/bapi/contract/v1/contracts"
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


def _contract_number(contract_id: Any) -> str:
    if not isinstance(contract_id, str) or len(contract_id) > 128:
        raise InvalidPerRequest("Invalid PER contract identifier")
    match = _CONTRACT_ID_RE.fullmatch(contract_id)
    if match is None:
        raise InvalidPerRequest("Invalid PER contract identifier")
    return match.group("number")


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


def _guard_url(url: str, expected_host: str) -> str:
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
        or not replay.is_allowed_url(url)
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
        raise replay.UpstreamError("PER insurer returned invalid JSON") from None
    if not isinstance(decoded, dict):
        raise replay.UpstreamError("PER insurer returned an invalid object")
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
    log.warning("PER detail refused: stage=%s status=%d", stage, status)
    if status == 401:
        raise replay.SessionExpired("PER token is no longer accepted")
    # 403 is a denial, not an expired session: it must not trigger a replay.
    raise replay.UpstreamError("PER insurer request failed")


def _producer_id(body: bytes, *, entity_code: str) -> str:
    """The insurer contract id from a prepareRouting answer, bound to this contract."""
    root = _json_object(body)
    # Three shapes: the characteristics object itself (logged live), or wrapped in
    # `characteristics`, or in `prepareRouting.characteristics`.
    wrapped = root.get("prepareRouting")
    holder = wrapped if isinstance(wrapped, dict) else root
    characteristics = holder.get("characteristics", holder if "routingMode" in holder else None)
    if not isinstance(characteristics, dict):
        raise replay.UpstreamError("PER routing response is incomplete")
    href = (characteristics.get("routingUrl") or {}).get("href") if isinstance(
        characteristics.get("routingUrl"), dict) else None
    context = characteristics.get("routingContext")
    value = context.get("value") if isinstance(context, dict) else None
    query = value.get("queryParameters") if isinstance(value, dict) else None
    if not isinstance(href, str) or not isinstance(query, dict):
        log.warning("PER detail refused: stage=routing reason=incomplete")
        raise replay.UpstreamError("PER routing response is incomplete")
    # The routing target is never followed; it must still be the insurer we call.
    if (urllib.parse.urlsplit(href).hostname or "").lower() != INSURER_HOST:
        log.warning("PER detail refused: stage=routing reason=other-host")
        raise replay.HostNotAllowed("Refused a PER URL outside the exact HTTPS host")
    producer_id = query.get("producerContratId")
    if not isinstance(producer_id, str) or (match := _PRODUCER_ID_RE.fullmatch(producer_id)) is None:
        log.warning("PER detail refused: stage=routing reason=producer-id-format")
        raise replay.UpstreamError("PER routing returned an unusable contract id")
    if query.get("cdEtab") != entity_code:
        log.warning("PER detail refused: stage=routing reason=other-establishment")
        raise replay.UpstreamError("PER routing answered for another establishment")
    # Consistency of the answer. Observed live: `contractId` echoes the INSURER id
    # (`PERIC` + 6 digits, 11 chars), not the bank id asked for (23 chars), with the
    # requested entity code. The bank id is bound by the request URL; the answer must
    # agree with itself on the insurer id and with us on the establishment.
    echoed = characteristics.get("contractId")
    if (
        not isinstance(echoed, dict)
        or echoed.get("id") != producer_id
        or echoed.get("entityCode") != entity_code
    ):
        log.warning("PER detail refused: stage=routing reason=other-contract")
        raise replay.UpstreamError("PER routing answered for another contract")
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
    access_token: str,
    contract_id: str,
    *,
    entity_code: str,
    routing_token: str,
    transport: httpx.AsyncBaseTransport | None = None,
) -> dict[str, Any]:
    """Return the raw `contratVie/load` JSON object of one PER contract.

    `routing_token` is the MAIN bearer, sent only to prepareRouting. `access_token`
    is the PER-chain token (`replay.PER_CHAIN`), sent only to clientToken and the
    detail. Raises `InvalidPerRequest` for a malformed request (nothing is sent),
    `SessionExpired` on a 401 with the PER token, `HostNotAllowed` if a URL leaves
    its exact host, and `UpstreamError` for any other failure (a routing 401 too:
    the main token has just read the synthesis, so it is PER-specific).
    """
    _validate_token(access_token)
    _validate_token(routing_token)
    entity_code = _validate_entity_code(entity_code)
    _contract_number(contract_id)
    route_segment = urllib.parse.quote(f"{entity_code}-{contract_id}", safe="")
    routing_url = _guard_url(f"{ROUTING_BASE}/{route_segment}/prepareRouting", GATEWAY_HOST)

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
            log.warning("PER detail refused: stage=routing status=%d", status)
            raise replay.UpstreamError("PER routing request failed")
        producer_id = _producer_id(body, entity_code=entity_code)
        match = _PRODUCER_ID_RE.fullmatch(producer_id)
        token_url = _guard_url(f"{CLIENT_TOKEN_BASE}/{producer_id}", GATEWAY_HOST)
        detail_url = _guard_url(
            f"{DETAIL_BASE}/{match.group('product')}/{match.group('company')}/{match.group('number')}",
            INSURER_HOST,
        )
        headers = build_headers(access_token, entity_code, producer_id)

        client.cookies.clear()
        status, _, response_headers = await _get(client, token_url, headers, stage="client-token")
        _check_status(status, stage="client-token")
        jwt = response_headers.get(JWT_HEADER, "")
        if not jwt or len(jwt) > _MAX_TOKEN_LENGTH or _JWT_RE.fullmatch(jwt) is None:
            log.warning("PER detail refused: stage=client-token reason=no-jwt")
            raise replay.UpstreamError("PER insurer token is missing")

        client.cookies.clear()
        status, body, _ = await _get(
            client, detail_url, {**headers, JWT_HEADER: jwt}, stage="contract-detail"
        )
        _check_status(status, stage="contract-detail")
        result = _json_object(body)
        if not isinstance(result.get("detailContratVie"), dict):
            raise replay.UpstreamError("PER insurer detail response is incomplete")
        try:
            serialized = json.dumps(result, ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError):
            raise replay.UpstreamError("PER insurer detail response is malformed") from None
        if access_token in serialized or routing_token in serialized or jwt in serialized:
            raise replay.UpstreamError("PER insurer detail response contained forbidden data")
        return result
    finally:
        await client.aclose()
