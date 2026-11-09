"""La Banque Postale web client.

The login is a normal HTML form: identifier + a six-position virtual keypad,
where the bank's own script writes the clicked **positions** (not the digits)
into the `password` field. The page also runs anti-fraud scripts that set a
52-character `iscdName` device fingerprint, so the login is driven in headless
Chromium (the keypad is clicked there and the password never leaves it). A new
device then gets a mobile-approval page the page polls until the bank redirects.
The account pages are server-rendered XHTML, so the resulting cookie jar is
enough to replay them with `httpx`.
"""

import asyncio
import json
import logging
import re
import time
import uuid
from typing import Any, Protocol
from urllib.parse import parse_qs, urljoin, urlparse, urlsplit

import httpx
from fastapi import HTTPException
from picsou_sidecar import PendingStore
from playwright.async_api import async_playwright

import parsers

log = logging.getLogger("lbp-auth")

BASE = "https://voscomptesenligne.labanquepostale.fr"
# Two-rule allow-list. Document URLs (navigations, httpx, websockets and every
# Chromium Document request, including each redirect hop) stay on bank-owned
# domains only: anyone can register a CloudFront distribution, so a bank-side
# redirect to an attacker distribution must never carry the identifier or load
# forged statements. Pinned CDN hosts below are live-observed script/style
# origins and are allowed ONLY as non-Document subresources (Script, Image,
# …), never as a Document. The fixed https origin also excludes loopback and
# private-IP targets such as the instance-metadata address.
ALLOWED_HOST = "voscomptesenligne.labanquepostale.fr"
# The only CloudFront distributions the bank was observed loading subresources
# from. Exact hosts (case-insensitive): no suffix rule, so an attacker
# distribution is never allow-listed. Never used for Document navigations.
PINNED_CDN_HOSTS = {"dqnjn206bwvk2.cloudfront.net", "d2ydsn9mah1r4u.cloudfront.net"}
# Every hostname label must be `[a-z0-9-]+` (casefolded): this rejects the
# apex-as-label (`https://.cloudfront.net/`), empty labels
# (`a..cloudfront.net`), a trailing dot, and any lookalike with an illegal
# character, while plain uppercase stays allowed.
_HOST_LABEL = re.compile(r"[a-z0-9-]+")
# The bank's login chain is two hops at most; a longer one is a loop or a
# hijack, never a login.
MAX_REDIRECTS = 5
# The web client must enter through `identif.ea`: it sets the WAF/SSO cookies
# and redirects to the login form. Hitting `loginform` on a fresh client answers
# 403 and redirects to `transverse…/message.html`.
IDENTIF = "/voscomptes/canalXHTML/identif.ea"
LOGIN_FORM = "/wsost/OstBrokerWeb/loginform"
AUTH = "/wsost/OstBrokerWeb/auth"
SYNTHESE_CCP = "/voscomptes/canalXHTML/comptesCommun/synthese_ccp/afficheSyntheseCCP-synthese_ccp.ea"
SYNTHESE_EP = "/voscomptes/canalXHTML/comptesCommun/synthese_ep/afficheSyntheseEP-synthese_ep.ea"
RELEVE_CCP = "/voscomptes/canalXHTML/CCP/releves_ccp/init-releve_ccp.ea"
RELEVE_CNE = "/voscomptes/canalXHTML/CNE/releveCNE/init-releve_cne.ea"
FRAME_CNE = "/voscomptes/canalXHTML/CNE/releveCNE/frame-releve_cne.ea"

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
)
REQUEST_TIMEOUT_SECONDS = 30.0
# The bank's own approval page polls every 3 s and says the user has "a few minutes".
POLL_INTERVAL_SECONDS = 3.0
APPROVAL_TIMEOUT_SECONDS = 150.0
PENDING_TTL_SECONDS = 300
MAX_PENDING = 8
# Unknown, and the bank may rotate it. A conservative value: the cookies are
# replayed on every sync and a dead session is detected on the next read.
CHECK_EXPIRES_IN_SECONDS = 600


class BankFlowUnverified(Exception):
    """Kept for tests and for the explicit stub binding."""


class BankClient(Protocol):
    async def initiate(self, customer_id: str) -> dict[str, Any]: ...

    async def keypad(self, process_id: str, positions: list[int]) -> None: ...

    async def complete(self, process_id: str) -> str: ...

    async def check(self, session_state: str) -> int: ...

    async def accounts(self, session_state: str) -> dict[str, Any]: ...


class UnverifiedBankClient:
    """Refuses every call; used by tests and as a deliberate stub."""

    async def initiate(self, customer_id: str) -> dict[str, Any]:
        raise BankFlowUnverified()

    async def keypad(self, process_id: str, positions: list[int]) -> None:
        raise BankFlowUnverified()

    async def complete(self, process_id: str) -> str:
        raise BankFlowUnverified()

    async def check(self, session_state: str) -> int:
        raise BankFlowUnverified()

    async def accounts(self, session_state: str) -> dict[str, Any]:
        raise BankFlowUnverified()


def _hostname_labels_valid(host: str | None) -> bool:
    """Every dot-separated label is `[a-z0-9-]+` (case-insensitive)."""
    if not host:
        return False
    try:
        labels = host.casefold().split(".")
    except Exception:
        return False
    return all(_HOST_LABEL.fullmatch(label) for label in labels)


def _is_bank_document_url(url: Any) -> bool:
    """True for an absolute https URL on a bank-owned domain, without credentials.

    `ALLOWED_HOST` or any `*.labanquepostale.fr` subdomain (bank-owned, not
    attacker-registerable). CloudFront is never a document: anyone can
    register a distribution, so a bank-side redirect to one must not leak the
    identifier or import forged statements. The suffix rule keeps the SSRF
    posture: link-local, metadata and arbitrary hosts are still refused, as
    are userinfo, backslashes, control characters and non-443 ports.
    """
    if not isinstance(url, str) or not url or "\\" in url:
        return False
    if any(ord(char) <= 0x20 or ord(char) == 0x7F for char in url):
        return False
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        return False
    if not _hostname_labels_valid(host):
        return False
    assert host is not None
    lowered = host.casefold()
    return (
        parsed.scheme == "https"
        and (lowered == ALLOWED_HOST or lowered.endswith(".labanquepostale.fr"))
        and port in (None, 443)
        and not parsed.username
        and not parsed.password
    )


def _is_allowed_url(url: Any) -> bool:
    """True for a loadable subresource: a document URL or a pinned CDN host.

    Asset rule for Chromium non-Document requests only (Script, XHR, Image,
    …): the two live-observed CloudFront distributions. An attacker
    distribution (`attacker.cloudfront.net`) is refused here too. Document
    navigations, httpx and websockets must use `_is_bank_document_url`
    instead and never call this.
    """
    if _is_bank_document_url(url):
        return True
    if not isinstance(url, str) or not url or "\\" in url:
        return False
    if any(ord(char) <= 0x20 or ord(char) == 0x7F for char in url):
        return False
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError:
        return False
    if not _hostname_labels_valid(host):
        return False
    assert host is not None
    return (
        parsed.scheme == "https"
        and host.casefold() in PINNED_CDN_HOSTS
        and port in (None, 443)
        and not parsed.username
        and not parsed.password
    )


def _require_allowed_url(url: str) -> str:
    """Resolve ``url`` against the bank origin and refuse anything off-origin.

    Relative bank links stay on the origin; an absolute URL elsewhere — a
    ``url_retour`` or a redirect pointing at the metadata address, an
    attacker host or an attacker CloudFront distribution — fails closed.
    Never log the rejected URL: it may carry credentials.
    """
    target = urljoin(BASE + "/", url)
    if not _is_bank_document_url(target):
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    return target


async def _check_request_origin(request: httpx.Request) -> None:
    """Runs before every send, including each automatic redirect hop.

    Document rule only: httpx replays the cookie jar on server-rendered pages,
    so even a pinned CDN host is refused here. Never log the rejected URL: it
    may carry a password, a session cookie or the keypad positions.
    """
    if not _is_bank_document_url(str(request.url)):
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")


def _route_guard() -> Any:
    """First-hop guard: document requests stay bank-only, subresources add pinned CDN.

    Playwright routes see only the initial URL of a request: a bank-origin
    page answering 302 to an off-origin target is followed by Chromium without
    another route callback. The CDP Fetch guard in `_new_guarded_page` sees
    every redirect hop and blocks those, so both stay registered. `data:` URLs
    never hit the network and stay allowed.
    """

    async def guard(route: Any) -> None:
        url = route.request.url
        resource_type = getattr(route.request, "resource_type", "document")
        allowed = (
            _is_bank_document_url(url)
            if resource_type == "document"
            else _is_allowed_url(url)
        )
        if allowed or url.startswith("data:"):
            await route.continue_()
        else:
            # Host only: never the URL, which may carry a session token.
            log.info(
                "La Banque Postale browser request blocked host=%s",
                urlparse(url).hostname,
            )
            await route.abort()

    return guard


async def _new_guarded_page(context: Any) -> Any:
    """A page whose every Chromium request hop is allow-listed before it is sent.

    Playwright routes only see the first hop of a redirected request, so a
    bank-origin page answering 302 to an off-origin target (e.g. the
    instance-metadata address) would be followed by Chromium with no further
    route callback. CDP request-stage interception sees each hop without
    replacing Chromium's TLS fingerprint with an API client's. Document hops
    must be bank-owned (`_is_bank_document_url`); only non-Document
    subresources (`resourceType` Script/XHR/Image/…) may additionally load
    from the pinned CDN hosts (`_is_allowed_url`). Every hop must pass
    (data: URLs never hit the network and stay allowed); an off-origin hop,
    or a chain longer than `MAX_REDIRECTS`, fails with
    `ERR_BLOCKED_BY_CLIENT` before anything is sent.
    """
    page = await context.new_page()
    session = await context.new_cdp_session(page)
    hops: dict[str, int] = {}

    async def guard(event: dict[str, Any]) -> None:
        request_id = event["requestId"]
        previous = event.get("redirectedRequestId")
        count = hops.pop(previous, 0) + 1 if previous else 0
        hops[request_id] = count
        url = event["request"]["url"]
        resource_type = event.get("resourceType", "Document")
        allowed = (
            _is_bank_document_url(url)
            if resource_type == "Document"
            else _is_allowed_url(url)
        )
        if count > MAX_REDIRECTS or not (allowed or url.startswith("data:")):
            # Host only: never the URL, which may carry a session token.
            log.info(
                "La Banque Postale browser hop blocked host=%s reason=%s",
                urlparse(url).hostname,
                "redirect-loop" if count > MAX_REDIRECTS else "off-origin",
            )
            await session.send(
                "Fetch.failRequest", {"requestId": request_id, "errorReason": "BlockedByClient"}
            )
        else:
            await session.send("Fetch.continueRequest", {"requestId": request_id})

    session.on("Fetch.requestPaused", guard)
    await session.send("Fetch.enable", {"patterns": [{"urlPattern": "*", "requestStage": "Request"}]})
    return page


def _ws_as_https(url: str) -> str:
    """``wss://host/...`` -> ``https://host/...`` so the allow-list applies; else unchanged."""
    return "https://" + url[len("wss://") :] if url.startswith("wss://") else url


def _websocket_guard() -> Any:
    """Connect a WebSocket to the real server only if its host is bank-owned."""

    async def guard(ws: Any) -> None:
        if _is_bank_document_url(_ws_as_https(ws.url)):
            ws.connect_to_server()
        else:
            await ws.close()

    return guard


class HttpxBankClient:
    """Cookie-replay client. The pending store pins one HTTP client per login."""

    def __init__(self, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._transport = transport
        self.pending = PendingStore(
            dispose=self._dispose,
            log=log,
            ttl_seconds=PENDING_TTL_SECONDS,
            sweep_seconds=30,
            max_size=MAX_PENDING,
        )

    # ─── login ───────────────────────────────────────────────────────────────

    async def initiate(self, customer_id: str) -> dict[str, Any]:
        client = self._new_client()
        try:
            # Enter through identif.ea: it issues the cookies the login form and
            # the auth POST expect, then redirects to the form.
            response = await client.get(
                IDENTIF, params={"origin": "particuliers"}, follow_redirects=True
            )
            if response.status_code != 200 or "formAccesCompte" not in response.text:
                log.warning(
                    "La Banque Postale login form unavailable status=%s path=%s",
                    response.status_code,
                    urlparse(str(response.url)).path,
                )
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
            form = parsers.parse_login_form(response.text)
            process_id = str(uuid.uuid4())
            state = {
                "client": client,
                "customer_id": customer_id,
                "action": form["action"],
                "hidden": form["hidden"],
                "referer": str(response.url),
                "created_at": time.time(),
            }
            await self.pending.put_or_raise(process_id, state, "TOO_MANY_PENDING")
            return {
                "processId": process_id,
                "keypad": {"keys": form["keys"], "columns": 5},
                "expiresInSeconds": PENDING_TTL_SECONDS,
            }
        except parsers.FormatError as exc:
            await client.aclose()
            raise HTTPException(status_code=502, detail=exc.code) from None
        except HTTPException:
            await client.aclose()
            raise
        except httpx.HTTPError:
            await client.aclose()
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        except Exception:
            await client.aclose()
            raise

    async def keypad(self, process_id: str, positions: list[int]) -> None:
        state = await self.pending.take(process_id)
        if state is None:
            raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
        client: httpx.AsyncClient = state["client"]
        try:
            data = dict(state["hidden"])
            data["username"] = state["customer_id"]
            # The bank's script stores the clicked DOM positions in `password`;
            # the server maps them back to digits with the per-load keypad.
            data["password"] = "".join(str(position) for position in positions)
            # The CVVS script sets clt=1 on a browser session; the captured
            # successful POST carried 1, not the form's initial 0.
            data["cltName"] = "1"
            # Lengths only: the values are the identifier, the keypad positions
            # and the anti-fraud fingerprint. Never log them.
            log.info(
                "La Banque Postale login fields iscd_len=%d clt=%s cv=%s cvvs_len=%d",
                len(data.get("iscdName", "")),
                data.get("cltName"),
                data.get("cv"),
                len(data.get("cvvs", "")),
            )
            response = await client.post(
                state["action"],
                data=data,
                headers={
                    "Origin": BASE,
                    "Referer": state.get("referer", BASE + LOGIN_FORM),
                    "Sec-Fetch-Dest": "document",
                    "Sec-Fetch-Mode": "navigate",
                    "Sec-Fetch-Site": "same-origin",
                    "Sec-Fetch-User": "?1",
                },
                follow_redirects=False,
            )

            if response.status_code in (301, 302, 303, 307, 308):
                location = response.headers.get("location", "")
                if "loginform" in location and ("cu=" in location or "TAM_OP=" in location):
                    await self._park_challenge(process_id, state, client, location)
                    return
                if "identif.ea" in location:
                    await self._park_session(process_id, state, client, location)
                    return
                log.warning(
                    "La Banque Postale login redirected unexpectedly path=%s",
                    urlparse(location).path,
                )
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

            if response.status_code in (401, 403):
                raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
            if response.status_code == 200:
                # The bank re-renders the login form with an error banner.
                raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
            log.warning("La Banque Postale login answered status=%s", response.status_code)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        except parsers.FormatError as exc:
            log.warning("La Banque Postale login payload rejected code=%s", exc.code)
            await self._dispose(state)
            raise HTTPException(status_code=502, detail=exc.code) from None
        except HTTPException:
            await self._dispose(state)
            raise
        except httpx.HTTPError as exc:
            log.warning(
                "La Banque Postale login request failed error_type=%s", type(exc).__name__
            )
            await self._dispose(state)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        except Exception as exc:
            log.error(
                "La Banque Postale login failed unexpectedly error_type=%s",
                type(exc).__name__,
            )
            await self._dispose(state)
            raise

    async def _park_challenge(
        self, process_id: str, state: dict[str, Any], client: httpx.AsyncClient, location: str
    ) -> None:
        challenge = await client.get(location, follow_redirects=True)
        if challenge.status_code != 200:
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        pph, polling = parsers.parse_polling(challenge.text)
        if not polling:
            log.warning(
                "La Banque Postale challenge page has no polling URL path=%s",
                urlparse(str(challenge.url)).path,
            )
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        state["poll_url"] = (pph or BASE + "/wsost").rstrip("/") + polling
        state.pop("hidden", None)
        await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
        log.info("La Banque Postale mobile approval pending")

    async def _park_session(
        self, process_id: str, state: dict[str, Any], client: httpx.AsyncClient, location: str
    ) -> None:
        final = await client.get(location, follow_redirects=True)
        if final.status_code != 200 or "formAccesCompte" in final.text:
            # A challenge can also appear on the direct path; park it if so.
            pph, polling = parsers.parse_polling(final.text)
            if polling:
                state["poll_url"] = (pph or BASE + "/wsost").rstrip("/") + polling
                state.pop("hidden", None)
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                return
            log.warning(
                "La Banque Postale post-login page is not a session status=%s path=%s",
                final.status_code,
                urlparse(str(final.url)).path,
            )
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        state["session_ready"] = True
        state.pop("hidden", None)
        await self.pending.repark(process_id, state, "TOO_MANY_PENDING")

    async def complete(self, process_id: str) -> str:
        state = await self.pending.take(process_id)
        if state is None:
            raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
        client: httpx.AsyncClient = state["client"]
        try:
            if state.get("session_ready"):
                return self._serialize(client)
            poll_url = state.get("poll_url")
            if not poll_url:
                raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")

            deadline = time.monotonic() + APPROVAL_TIMEOUT_SECONDS
            while time.monotonic() < deadline:
                response = await client.get(poll_url, follow_redirects=False)
                if response.status_code in (301, 302, 303, 307, 308):
                    final = await client.get(
                        response.headers.get("location", ""), follow_redirects=True
                    )
                    if final.status_code != 200 or "formAccesCompte" in final.text:
                        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
                    return self._serialize(client)
                if response.status_code in (401, 403):
                    raise HTTPException(status_code=401, detail="APP_VALIDATION_TIMEOUT")
                if response.status_code not in (200, 204, 429) and response.status_code < 500:
                    raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
                await asyncio.sleep(POLL_INTERVAL_SECONDS)
            raise HTTPException(status_code=408, detail="APP_VALIDATION_TIMEOUT")
        except HTTPException:
            raise
        except httpx.HTTPError:
            # A transport failure or a capped redirect chain: the attempt is
            # over, and it is an upstream outage, not an internal error.
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        finally:
            await self._dispose(state)

    # ─── data ────────────────────────────────────────────────────────────────

    async def check(self, session_state: str) -> int:
        client = self._new_client()
        try:
            self._restore(client, session_state)
            response = await client.get(SYNTHESE_CCP, follow_redirects=False)
            if response.status_code in (301, 302, 303, 307, 308):
                raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            if response.status_code != 200 or "formAccesCompte" in response.text:
                raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            return CHECK_EXPIRES_IN_SECONDS
        except HTTPException:
            raise
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        finally:
            await client.aclose()

    async def accounts(self, session_state: str) -> dict[str, Any]:
        client = self._new_client()
        try:
            self._restore(client, session_state)
            try:
                return await self._read_accounts(client)
            except HTTPException as exc:
                if exc.status_code != 502 or exc.detail != "UPSTREAM_UNAVAILABLE":
                    raise
                # A read is idempotent: one transient bank page must not fail the whole
                # sync and force another interactive login. The retry reuses the same
                # cookie jar, so a cookie rotated by the first attempt is kept.
                log.warning("La Banque Postale accounts read hit a transient failure, retrying once")
                await asyncio.sleep(1)
                return await self._read_accounts(client)
            except httpx.HTTPError:
                # A transport error (reset, timeout) is just as transient and must not
                # fail the whole sync either.
                log.warning("La Banque Postale accounts read hit a transient transport failure, retrying once")
                await asyncio.sleep(1)
                return await self._read_accounts(client)
        except parsers.FormatError as exc:
            raise HTTPException(status_code=502, detail=exc.code) from None
        except HTTPException:
            raise
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        finally:
            await client.aclose()

    async def _read_accounts(self, client: httpx.AsyncClient) -> dict[str, Any]:
        accounts: list[dict[str, Any]] = []
        for path in (SYNTHESE_CCP, SYNTHESE_EP):
            response = await client.get(path, follow_redirects=False)
            if response.status_code in (301, 302, 303, 307, 308):
                raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            if response.status_code != 200:
                log.warning(
                    "La Banque Postale synthesis read failed path=%s status=%s",
                    path,
                    response.status_code,
                )
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
            if "formAccesCompte" in response.text:
                raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            accounts.extend(parsers.parse_account_cards(response.text))

        if not accounts:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        for account in accounts:
            path = RELEVE_CCP if account["typeRecherche"] == "5" else RELEVE_CNE
            params = {
                "compte.numero": account["statementId"],
                "typeRecherche": account["typeRecherche"],
            }
            response = await client.get(path, params=params, follow_redirects=True)
            if response.status_code == 404 and path == RELEVE_CNE:
                # The savings statement also exists behind its frame page.
                response = await client.get(FRAME_CNE, params=params, follow_redirects=True)
            if response.status_code != 200 or "formAccesCompte" in response.text:
                log.warning(
                    "La Banque Postale statement read failed path=%s status=%s login_page=%s",
                    path,
                    response.status_code,
                    "formAccesCompte" in response.text,
                )
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
            account["transactions"] = parsers.parse_transactions(response.text)

        return {
            "accounts": [self._normalized(account) for account in accounts],
            "unsupported": [],
        }

    # ─── plumbing ────────────────────────────────────────────────────────────

    def _new_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=BASE,
            timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
            follow_redirects=False,
            max_redirects=MAX_REDIRECTS,
            transport=self._transport,
            # The hook recomputes the document check from the final URL before
            # every send, including each automatic redirect hop: a bank-supplied
            # Location leaving the bank-owned origin (including to an attacker
            # CloudFront distribution) is refused before anything is sent.
            event_hooks={"request": [_check_request_origin]},
            headers={
                "User-Agent": USER_AGENT,
                "Accept-Language": "fr-FR,fr;q=0.9",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Upgrade-Insecure-Requests": "1",
                "Sec-GPC": "1",
            },
        )

    async def _dispose(self, state: dict[str, Any]) -> None:
        client: httpx.AsyncClient | None = state.get("client")
        if client is not None:
            try:
                await client.aclose()
            except Exception:  # pragma: no cover -- teardown must not raise
                log.debug("La Banque Postale pending client close failed", exc_info=True)

    @staticmethod
    def _serialize(client: httpx.AsyncClient) -> str:
        cookies = [
            {
                "name": cookie.name,
                "value": cookie.value or "",
                "domain": cookie.domain or "",
                "path": cookie.path or "/",
            }
            for cookie in client.cookies.jar
        ]
        return json.dumps({"cookies": cookies}, separators=(",", ":"))

    @staticmethod
    def _restore(client: httpx.AsyncClient, raw: str) -> None:
        try:
            payload = json.loads(raw)
        except (TypeError, ValueError) as exc:
            raise HTTPException(status_code=400, detail="INVALID_SESSION_STATE") from exc
        cookies = payload.get("cookies") if isinstance(payload, dict) else None
        if not isinstance(cookies, list) or not cookies:
            raise HTTPException(status_code=400, detail="INVALID_SESSION_STATE")
        for cookie in cookies:
            if not isinstance(cookie, dict) or not cookie.get("name"):
                raise HTTPException(status_code=400, detail="INVALID_SESSION_STATE")
            client.cookies.set(
                cookie["name"],
                cookie.get("value", ""),
                domain=cookie.get("domain", ""),
                path=cookie.get("path", "/"),
            )

    @staticmethod
    def _normalized(account: dict[str, Any]) -> dict[str, Any]:
        return {
            "externalId": account["externalId"],
            "kind": account["kind"],
            "name": account["name"] or account["kind"],
            "balance": str(account["balance"]),
            "currency": account["currency"],
            "iban": None,
            "authorizedOverdraft": None,
            "ceiling": None,
            "remainingDepositCapacity": None,
            "fillingRatio": None,
            "cardNature": None,
            "parentExternalId": None,
            "nextDueDate": None,
            "transactions": account["transactions"],
            "snapshotComplete": True,
        }


class BrowserBankClient(HttpxBankClient):
    """Browser-assisted login, cookie replay for the data pages.

    The login page runs anti-fraud scripts that set a device fingerprint
    (`iscdName`) which `httpx` cannot reproduce. A real Chromium fills it
    naturally; the keypad is clicked in that browser, so the password never
    leaves it. Once the session exists, the cookies are replayed with the
    inherited `httpx` methods for the server-rendered pages.
    """

    async def initiate(self, customer_id: str) -> dict[str, Any]:
        # Reserve the pending slot before opening Chromium: a full store
        # rejects here, without ever leaking a browser process. The slot holds
        # a placeholder until the page is ready; every later failure disposes
        # it through the store, closing whatever was opened so far.
        process_id = str(uuid.uuid4())
        state: dict[str, Any] = {"created_at": time.time()}
        await self.pending.put_or_raise(process_id, state, "TOO_MANY_PENDING")
        try:
            pw = await async_playwright().start()
            state["playwright"] = pw
            browser = await pw.chromium.launch(
                headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"]
            )
            state["browser"] = browser
            context = await browser.new_context(
                user_agent=USER_AGENT,
                locale="fr-FR",
                timezone_id="Europe/Paris",
                viewport={"width": 1280, "height": 900},
                service_workers="block",
            )
            state["context"] = context
            # No service worker (it could answer requests behind the route guard).
            await context.route("**/*", _route_guard())
            await context.route_web_socket("**/*", _websocket_guard())
            page = await _new_guarded_page(context)
            state["page"] = page
            await self._goto(page, BASE + IDENTIF + "?origin=particuliers")
            await page.wait_for_selector("#identifiant", timeout=45_000)
            await page.fill("#identifiant", customer_id)
            # `continuer()` reveals the password panel after checking the identifier.
            continue_button = page.locator("#btnContinuer button")
            if await continue_button.is_visible():
                await continue_button.click()
            await page.wait_for_selector("div[data-tb-cvd-id='password'] button", timeout=15_000)
            keys = [
                key.strip()
                for key in await page.locator("div[data-tb-cvd-id='password'] button").all_text_contents()
            ][:10]
            if len(keys) != 10 or sorted(keys) != list("0123456789"):
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

            state.update(
                {
                    "customer_id": customer_id,
                    "keys": keys,
                    "created_at": time.time(),
                }
            )
            return {
                "processId": process_id,
                "keypad": {"keys": keys, "columns": 5},
                "expiresInSeconds": PENDING_TTL_SECONDS,
            }
        except HTTPException:
            await self.pending.close(process_id)
            raise
        except Exception as exc:
            log.warning("La Banque Postale browser initiate failed error_type=%s", type(exc).__name__)
            await self.pending.close(process_id)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None

    async def keypad(self, process_id: str, positions: list[int]) -> None:
        state = await self.pending.take(process_id)
        if state is None:
            raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
        page = state["page"]
        step = "start"
        try:
            # The identifier was filled and the password panel revealed at
            # /initiate. Do not fill it again: the page may have made it
            # read-only, and the submit would be rejected.
            step = "continue"
            continue_button = page.locator("#btnContinuer button")
            if await continue_button.is_visible():
                await continue_button.click()

            step = "keys"
            buttons = page.locator("div[data-tb-cvd-id='password'] button")
            current_keys = [
                key.strip() for key in await buttons.all_text_contents()
            ][:10]
            if current_keys != state.get("keys"):
                log.warning("La Banque Postale keypad order changed between initiate and keypad")
                raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
            count = await buttons.count()
            for position in positions:
                if position < 0 or position >= count:
                    raise HTTPException(status_code=400, detail="INVALID_POSITIONS")
                await buttons.nth(position).click()

            step = "submit"
            await page.click("#btnConfirmer")

            # The page waits 500 ms then submits. Poll the driver-side URL: a
            # navigation destroys the JS context, so wait_for_function can hang.
            step = "navigation"
            url = await self._wait_for_url_change(page, page.url, 15)

            # A non-trusted device first gets the AuthentInfoAF notice ("Vous
            # allez devoir vous authentifier de façon renforcée"). Its Continuer
            # button opens the cu=attenteAF page, and *that* page sends the
            # mobile notification. Without the click the bank waits forever and
            # no push arrives.
            if "message.html" in url:
                param = parse_qs(urlparse(url).query).get("param", [""])[0]
                if param != "AuthentInfoAF":
                    log.warning("La Banque Postale login refused param=%s", param)
                    self._log_failure_page("login-refused")
                    if param == "0x132120c8":
                        raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
                    raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
                step = "authent-info"
                await self._follow_continue(page, url)
                url = await self._wait_for_challenge(page, 30, previous=url)

            # Let the redirect chain settle, then classify on the page content
            # rather than on the URL alone: a trusted device lands on the client
            # home page, a new one on the challenge, and the bank sometimes
            # bounces to a generic message page.
            step = "classify"
            try:
                await page.wait_for_load_state("domcontentloaded", timeout=10_000)
            except Exception:
                pass
            url = page.url

            if "afficheSynthese" in url or "synthese_ccp" in url:
                state["session_ready"] = True
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                log.info("La Banque Postale browser login ready path=%s", urlparse(url).path)
                return
            if "message.html" in url:
                log.warning(
                    "La Banque Postale approval failed param=%s",
                    parse_qs(urlparse(url).query).get("param", [""])[0],
                )
                self._log_failure_page("approval-error")
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

            # The form may have re-rendered (refused identifier or password), the
            # device may be trusted (home page), or the challenge may be up.
            step = "inspect"
            try:
                body = await page.content()
            except Exception:
                body = ""
            if "formAccesCompte" in body:
                raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
            if "Se déconnecter" in body or "Mon profil" in body:
                state["session_ready"] = True
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                log.info("La Banque Postale browser login ready path=%s", urlparse(url).path)
                return
            if "appareil de confiance" in body or "URL_AF_POLLING" in body:
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                log.info("La Banque Postale browser login awaits mobile approval")
                return
            self._log_failure_page("unexpected")
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        except HTTPException:
            await self._dispose(state)
            raise
        except Exception as exc:
            log.warning(
                "La Banque Postale browser keypad failed step=%s error_type=%s",
                step,
                type(exc).__name__,
            )
            await self._dispose(state)
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None

    async def complete(self, process_id: str) -> str:
        state = await self.pending.take(process_id)
        if state is None:
            raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
        try:
            if state.get("session_ready"):
                return self._serialize_browser(await state["context"].cookies())
            page = state["page"]
            deadline = time.monotonic() + APPROVAL_TIMEOUT_SECONDS
            while time.monotonic() < deadline:
                url = page.url
                if "afficheSynthese" in url or "synthese_ccp" in url:
                    return self._serialize_browser(await state["context"].cookies())
                if "message.html" in url:
                    log.warning(
                        "La Banque Postale approval failed param=%s",
                        parse_qs(urlparse(url).query).get("param", [""])[0],
                    )
                    self._log_failure_page("approval-error")
                    raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
                await asyncio.sleep(2)
            raise HTTPException(status_code=408, detail="APP_VALIDATION_TIMEOUT")
        finally:
            await self._dispose(state)

    @staticmethod
    def _serialize_browser(cookies: list[dict[str, Any]]) -> str:
        return json.dumps(
            {
                "cookies": [
                    {
                        "name": cookie["name"],
                        "value": cookie.get("value", ""),
                        "domain": cookie.get("domain", ""),
                        "path": cookie.get("path", "/"),
                    }
                    for cookie in cookies
                ]
            },
            separators=(",", ":"),
        )

    async def _dispose(self, state: dict[str, Any]) -> None:
        await self._close_browser(
            state.get("playwright"), state.get("browser"), state.get("context")
        )
        await super()._dispose(state)

    @staticmethod
    async def _wait_for_url_change(page: Any, previous: str, timeout: float) -> str:
        """Return the page URL as soon as it leaves ``previous``."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            url = page.url
            if url != previous:
                return url
            await asyncio.sleep(0.5)
        return page.url

    @staticmethod
    async def _goto(page: Any, url: str) -> None:
        """Navigate only within the bank origin; anything else fails closed."""
        await page.goto(
            _require_allowed_url(url),
            wait_until="domcontentloaded",
            timeout=45_000,
        )

    @staticmethod
    async def _follow_continue(page: Any, url: str) -> None:
        """Click the strong-authentication notice's Continuer button.

        The notice page renders its button from JavaScript, so wait for it.
        Falling back to ``url_retour`` covers a page whose script did not run.
        The fallback is resolved against the bank origin and refused when it
        leaves it: the value comes from the bank page, never from the caller.
        """
        try:
            await page.wait_for_selector("#back-link", timeout=20_000)
            await page.click("#back-link")
            return
        except Exception:
            log.debug("La Banque Postale Continuer button not usable", exc_info=True)
        retour = parse_qs(urlparse(url).query).get("url_retour", [""])[0]
        if not retour:
            BrowserBankClient._log_failure_page("no-continue")
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
        await page.goto(
            _require_allowed_url(retour),
            wait_until="domcontentloaded",
            timeout=45_000,
        )

    @staticmethod
    async def _wait_for_challenge(page: Any, timeout: float, previous: str | None = None) -> str:
        """Wait until the page is the approval challenge, the synthesis or an error.

        ``previous`` is the notice URL: a message URL equal to it only means the
        click has not committed yet, not that the bank refused.
        """
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            url = page.url
            if url != previous and (
                "cu=attenteAF" in url
                or "afficheSynthese" in url
                or "synthese_ccp" in url
                or "message.html" in url
            ):
                return url
            await asyncio.sleep(0.5)
        return page.url

    @staticmethod
    def _log_failure_page(label: str) -> None:
        """Record which failure page the login hit, never its text.

        The page body is a raw bank payload; the README forbids logging it.
        """
        log.warning("La Banque Postale failure page %s", label)

    @staticmethod
    async def _close_browser(pw: Any, browser: Any, context: Any) -> None:
        for resource, close in (
            (context, "close"),
            (browser, "close"),
            (pw, "stop"),
        ):
            if resource is not None:
                try:
                    await getattr(resource, close)()
                except Exception:  # pragma: no cover -- teardown must not raise
                    log.debug("La Banque Postale browser resource close failed", exc_info=True)
