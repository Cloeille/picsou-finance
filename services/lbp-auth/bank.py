"""La Banque Postale web client.

The login is a normal HTML form: identifier + a six-position virtual keypad,
where the bank's own script writes the clicked **positions** (not the digits)
into the `password` field. A new device then gets a mobile-approval page that
the page polls every 3 s until the bank redirects. The account pages are
server-rendered XHTML, so a cookie jar is enough to replay them.

Everything here can be replayed with `httpx`. The one unverified risk is
`iscdName`, a 52-character device fingerprint the bank's anti-fraud scripts put
in the form; if the server insists on it, only the login layer needs a browser.
"""

import asyncio
import json
import logging
import time
import uuid
from typing import Any, Protocol
from urllib.parse import urlparse

import httpx
from fastapi import HTTPException
from picsou_sidecar import PendingStore
from playwright.async_api import async_playwright

import parsers

log = logging.getLogger("lbp-auth")

BASE = "https://voscomptesenligne.labanquepostale.fr"
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
            accounts: list[dict[str, Any]] = []
            for path in (SYNTHESE_CCP, SYNTHESE_EP):
                response = await client.get(path, follow_redirects=False)
                if response.status_code in (301, 302, 303, 307, 308):
                    raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
                if response.status_code != 200:
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
                    raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
                account["transactions"] = parsers.parse_transactions(response.text)

            return {
                "accounts": [self._normalized(account) for account in accounts],
                "unsupported": [],
            }
        except parsers.FormatError as exc:
            raise HTTPException(status_code=502, detail=exc.code) from None
        except HTTPException:
            raise
        except httpx.HTTPError:
            raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from None
        finally:
            await client.aclose()

    # ─── plumbing ────────────────────────────────────────────────────────────

    def _new_client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            base_url=BASE,
            timeout=httpx.Timeout(REQUEST_TIMEOUT_SECONDS),
            follow_redirects=False,
            transport=self._transport,
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
            "currency": "EUR",
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
        pw = None
        browser = None
        context = None
        try:
            pw = await async_playwright().start()
            browser = await pw.chromium.launch(
                headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"]
            )
            context = await browser.new_context(
                user_agent=USER_AGENT,
                locale="fr-FR",
                timezone_id="Europe/Paris",
                viewport={"width": 1280, "height": 900},
            )
            page = await context.new_page()
            await page.goto(
                BASE + IDENTIF + "?origin=particuliers",
                wait_until="domcontentloaded",
                timeout=45_000,
            )
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

            process_id = str(uuid.uuid4())
            state = {
                "playwright": pw,
                "browser": browser,
                "context": context,
                "page": page,
                "customer_id": customer_id,
                "created_at": time.time(),
            }
            try:
                await self.pending.put_or_raise(process_id, state, "TOO_MANY_PENDING")
            except Exception:
                await self._close_browser(pw, browser, context)
                raise
            return {
                "processId": process_id,
                "keypad": {"keys": keys, "columns": 5},
                "expiresInSeconds": PENDING_TTL_SECONDS,
            }
        except HTTPException:
            await self._close_browser(pw, browser, context)
            raise
        except Exception as exc:
            log.warning("La Banque Postale browser initiate failed error_type=%s", type(exc).__name__)
            await self._close_browser(pw, browser, context)
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
            count = await buttons.count()
            for position in positions:
                if position < 0 or position >= count:
                    raise HTTPException(status_code=400, detail="INVALID_POSITIONS")
                await buttons.nth(position).click()

            step = "submit"
            await page.click("#btnConfirmer")

            # The page waits 500 ms then submits. Read only the URL here: the DOM
            # is replaced while the page navigates, and touching it throws.
            step = "navigation"
            try:
                await page.wait_for_function(
                    "() => location.href.includes('afficheSynthese')"
                    " || location.href.includes('synthese_ccp')"
                    " || (location.href.includes('loginform')"
                    "     && (location.href.includes('phase=') || location.href.includes('cu=')))"
                    " || location.href.includes('message.html')",
                    timeout=15_000,
                )
            except Exception:
                pass
            url = page.url
            if "loginform" in url and ("phase=" in url or "cu=" in url):
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                log.info("La Banque Postale browser login awaits mobile approval")
                return
            if "afficheSynthese" in url or "synthese_ccp" in url:
                state["session_ready"] = True
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                return
            if "message.html" in url:
                raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")

            # The form may have re-rendered (refused identifier or password) or
            # the page may not have submitted. Read the body only now.
            step = "inspect"
            try:
                body = await page.content()
            except Exception:
                body = ""
            if "appareil de confiance" in body or "URL_AF_POLLING" in body:
                await self.pending.repark(process_id, state, "TOO_MANY_PENDING")
                log.info("La Banque Postale browser login awaits mobile approval")
                return
            if "formAccesCompte" in body:
                raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
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
