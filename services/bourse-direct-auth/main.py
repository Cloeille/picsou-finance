"""Read-only Bourse Direct authentication and portfolio sidecar.

Playwright drives the interactive login/2FA flow and reads Bourse Direct's
read-only portfolio stream. The legacy account endpoint remains a fallback.
Credentials, OTP values, cookies and raw financial responses are never logged.
"""

import asyncio
import json
import logging
import os
import re
import secrets
import time
import uuid
from contextlib import asynccontextmanager
from decimal import Decimal
from typing import Any
from typing import Literal
from urllib.parse import urlencode, urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from playwright.async_api import (
    Browser,
    BrowserContext,
    Error as PlaywrightError,
    Page,
    Playwright,
    TimeoutError as PlaywrightTimeoutError,
    async_playwright,
)
from portfolio_parser import (
    PortfolioFormatError,
    decimal_value,
    optional_decimal_value,
    parse_portfolio,
)

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("bourse-direct-auth")
SIDECAR_API_KEY = os.environ.get("APP_SIDECAR_API_KEY", "")

BASE_URL = "https://www.boursedirect.fr"
LOGIN_URL = f"{BASE_URL}/fr/login"
PORTFOLIO_URL = f"{BASE_URL}/fr/page/portefeuille"
NEWS_PATH = "/fr/actualites"
PENDING_TTL_SECONDS = 600
MAX_ACCOUNTS = 20
MFA_RESULT_TIMEOUT_SECONDS = 12
SESSION_CHECK_TIMEOUT_SECONDS = 15
MODERN_PORTFOLIO_SETTLE_SECONDS = 3
PENDING_SWEEP_SECONDS = 30
RESOURCE_CLOSE_TIMEOUT_SECONDS = 5
PORTFOLIO_ONBOARDING_COOKIE = "portfolio_onboarding_20260316"
MONEY_ABSOLUTE_TOLERANCE = Decimal("0.05")
MONEY_RELATIVE_TOLERANCE = Decimal("0.001")

_pending: dict[str, dict[str, Any]] = {}
_pending_lock = asyncio.Lock()


async def _pending_sweeper() -> None:
    while True:
        await asyncio.sleep(PENDING_SWEEP_SECONDS)
        await _cleanup_expired()


@asynccontextmanager
async def lifespan(_: FastAPI):
    if not SIDECAR_API_KEY.strip():
        raise RuntimeError("APP_SIDECAR_API_KEY must be configured and non-blank")
    sweeper = asyncio.create_task(_pending_sweeper())
    try:
        yield
    finally:
        sweeper.cancel()
        try:
            await sweeper
        except asyncio.CancelledError:
            pass
        await _close_all_pending()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def authenticate_sidecar_request(request: Request, call_next):
    if request.url.path != "/health":
        supplied_key = request.headers.get("X-Picsou-Sidecar-Key", "")
        # Starlette exposes wire header bytes through Latin-1, not UTF-8.
        if (
            not SIDECAR_API_KEY.strip()
            or not secrets.compare_digest(
                supplied_key.encode("latin-1"), SIDECAR_API_KEY.encode("utf-8")
            )
        ):
            return JSONResponse(
                status_code=401,
                content={"detail": "UNAUTHORIZED"},
                headers={"WWW-Authenticate": "Picsou-Sidecar-Key"},
            )
    return await call_next(request)


@app.middleware("http")
async def log_request_duration(request: Request, call_next):
    started_at = time.monotonic()
    try:
        return await call_next(request)
    finally:
        if request.url.path != "/health":
            log.info(
                "Bourse Direct request completed (path=%s; duration=%.2fs)",
                request.url.path,
                time.monotonic() - started_at,
            )


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    login: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=100)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=100)
    code: str = Field(pattern=r"^\d{6}$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
    symbol: str = Field(min_length=1, max_length=100)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    buyingPriceEur: Decimal | None = None
    currentPrice: Decimal | None = None
    quoteCurrency: str | None = Field(default=None, pattern=r"^[A-Z]{3}$")
    currentValueEur: Decimal
    pnlEur: Decimal | None = None


class AccountPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    name: str = Field(min_length=1, max_length=200)
    type: Literal["PEA", "COMPTE_TITRES"]
    balanceEur: Decimal
    cashBalance: Decimal
    positions: list[PositionPayload]
    snapshotComplete: Literal[True]


class InitiateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str | None
    mfaRequired: bool
    mfaType: str | None
    sessionState: str | None


class SessionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    _: Request,
    exc: RequestValidationError,
) -> JSONResponse:
    fields = {
        str(error["loc"][-1])
        for error in exc.errors()
        if error.get("loc")
    }
    detail = "INVALID_OTP" if "code" in fields else "INVALID_DATA"
    return JSONResponse(status_code=400, content={"detail": detail})


def _raw_number(container: Any, *preferred_keys: str) -> Any:
    if not isinstance(container, dict):
        return container
    for key in preferred_keys:
        if container.get(key) is not None:
            return container[key]
    return None


def _optional_position_decimal(position: dict[str, Any], keys: tuple[str, ...], field: str) -> Decimal | None:
    for key in keys:
        if key not in position or position[key] is None:
            continue
        raw = _raw_number(position[key], "current", "number", "value")
        return optional_decimal_value(raw, field)
    return None


def _money_close(actual: Decimal, expected: Decimal) -> bool:
    tolerance = max(
        MONEY_ABSOLUTE_TOLERANCE,
        abs(expected) * MONEY_RELATIVE_TOLERANCE,
    )
    return abs(actual - expected) <= tolerance


def _merge_event(previous: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    """Recursively merge a partial Socket.IO update into its last snapshot."""
    merged = dict(previous)
    for key, value in current.items():
        old_value = merged.get(key)
        if isinstance(old_value, dict) and isinstance(value, dict):
            merged[key] = _merge_event(old_value, value)
        else:
            merged[key] = value
    return merged


def _socketio_events(payload: Any) -> list[tuple[str, dict[str, Any]]]:
    if isinstance(payload, dict):
        payload = payload.get("payload", "")
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8", errors="ignore")
    if not isinstance(payload, str):
        return []

    events: list[tuple[str, dict[str, Any]]] = []
    for packet in payload.split("\x1e"):
        if not packet.startswith("42"):
            continue
        array_start = packet.find("[")
        if array_start < 0:
            continue
        try:
            decoded = json.loads(packet[array_start:])
        except json.JSONDecodeError:
            continue
        if (
            isinstance(decoded, list)
            and len(decoded) >= 2
            and isinstance(decoded[0], str)
            and isinstance(decoded[1], dict)
        ):
            events.append((decoded[0], decoded[1]))
    return events


class ModernPortfolioCollector:
    def __init__(self) -> None:
        self.accounts: dict[str, dict[str, Any]] = {}
        self.balances: dict[str, dict[str, Any]] = {}
        self.positions: dict[str, dict[str, dict[str, Any]]] = {}
        self.last_event_at = 0.0
        self.socket_seen = False
        self.incomplete_reason = "waiting-for-authorization"
        self._seen_pages: set[int] = set()
        self._seen_sockets: set[int] = set()

    def attach(self, page: Page) -> None:
        page_identity = id(page)
        if page_identity in self._seen_pages:
            return
        self._seen_pages.add(page_identity)
        page.on("websocket", self._on_websocket)

    def _on_websocket(self, websocket) -> None:
        if not urlsplit(websocket.url).path.rstrip("/").endswith("/backend/portfolio"):
            return
        socket_identity = id(websocket)
        if socket_identity in self._seen_sockets:
            return
        self._seen_sockets.add(socket_identity)
        self.socket_seen = True
        log.info("Bourse Direct modern portfolio socket connected (path=/backend/portfolio)")
        websocket.on("framereceived", self._on_frame)

    def _on_frame(self, payload: Any) -> None:
        for event, data in _socketio_events(payload):
            if event == "authorized":
                portfolios = data.get("portfolios")
                if not isinstance(portfolios, list):
                    continue
                self.accounts = {
                    str(account["id"]): account
                    for account in portfolios
                    if isinstance(account, dict) and account.get("id")
                }
                account_ids = set(self.accounts)
                self.balances = {
                    key: value for key, value in self.balances.items()
                    if key in account_ids
                }
                self.positions = {
                    key: value for key, value in self.positions.items()
                    if key in account_ids
                }
            elif event in ("balance.snapshot", "balance.update"):
                portfolio_id = data.get("portfolio")
                if portfolio_id:
                    key = str(portfolio_id)
                    self.balances[key] = (
                        data
                        if event == "balance.snapshot"
                        else _merge_event(self.balances.get(key, {}), data)
                    )
            elif event in ("position.snapshot", "position.update"):
                portfolio_id = data.get("portfolio")
                position_key = data.get("key")
                if portfolio_id and position_key:
                    account_positions = self.positions.setdefault(str(portfolio_id), {})
                    key = str(position_key)
                    account_positions[key] = (
                        data
                        if event == "position.snapshot"
                        else _merge_event(account_positions.get(key, {}), data)
                    )
            elif event == "position.destroy":
                portfolio_id = data.get("portfolio")
                position_key = data.get("key")
                if portfolio_id and position_key:
                    self.positions.get(str(portfolio_id), {}).pop(str(position_key), None)
            else:
                continue
            self.last_event_at = time.monotonic()

    def _target_accounts(self) -> list[tuple[str, dict[str, Any]]]:
        target_accounts = []
        for account_id, account in self.accounts.items():
            account_type = str(account.get("type") or "").upper()
            classification = " ".join(
                str(account.get(field) or "")
                for field in ("label", "name", "mode", "kind", "category")
            ).upper()
            if any(marker in classification for marker in ("FICTIF", "DEMO", "SIMULATION", "VIRTUEL")):
                continue
            if "PEA" not in account_type and "CTO" not in account_type and "TITRE" not in account_type:
                continue
            target_accounts.append((account_id, account))
        return target_accounts

    def result_if_ready(self) -> list[dict[str, Any]] | None:
        target_accounts = self._target_accounts()
        if not target_accounts:
            self.incomplete_reason = "no-supported-account"
            return None
        if any(account_id not in self.balances for account_id, _ in target_accounts):
            self.incomplete_reason = "missing-account-balance"
            return None
        if time.monotonic() - self.last_event_at < MODERN_PORTFOLIO_SETTLE_SECONDS:
            self.incomplete_reason = "settling"
            return None

        result: list[dict[str, Any]] = []
        for account_id, account in target_accounts:
            balance = self.balances[account_id]
            cash = decimal_value(
                _raw_number(balance.get("cash_valuation"), "current", "number"),
                "cash balance",
            )
            portfolio_value = decimal_value(
                _raw_number(balance.get("portfolio_valuation"), "current", "number"),
                "portfolio valuation",
            )
            total_raw = _raw_number(balance.get("total"), "current", "number")
            total = (
                decimal_value(total_raw, "account total")
                if total_raw is not None
                else cash + portfolio_value
            )
            if not _money_close(total, cash + portfolio_value):
                raise PortfolioFormatError("Account total does not match cash and portfolio valuation")

            positions = []
            for position in self.positions.get(account_id, {}).values():
                share = position.get("share")
                if not isinstance(share, dict):
                    raise PortfolioFormatError("Missing position instrument")
                quantity = decimal_value(
                    _raw_number(position.get("quantity"), "number", "current"),
                    "position quantity",
                )
                if quantity == 0:
                    continue
                native_average_price = optional_decimal_value(
                    _raw_number(position.get("average_price"), "number", "current"),
                    "position buying price",
                )
                current_raw = share.get("last")
                if current_raw is None:
                    current_raw = share.get("close")
                current_price = optional_decimal_value(current_raw, "position current price")
                quote_currency = str(share.get("currency") or "").strip().upper() or None
                if quote_currency is not None and not re.fullmatch(r"[A-Z]{3}", quote_currency):
                    raise PortfolioFormatError("Invalid position quote currency")

                current_value_eur = _optional_position_decimal(
                    position,
                    ("valuation", "current_value", "currentValue"),
                    "position EUR valuation",
                )
                if current_price is not None and quote_currency is None:
                    current_price = None
                if (
                    current_value_eur is None
                    and current_price is not None
                    and quote_currency == "EUR"
                ):
                    current_value_eur = quantity * current_price
                if current_value_eur is None:
                    # A foreign-currency position cannot be reconciled safely from
                    # its native quote. Wait for the broker's EUR valuation event.
                    self.incomplete_reason = "missing-position-eur-valuation"
                    return None

                pnl_eur = _optional_position_decimal(
                    position,
                    ("plusMinusValue", "plus_minus_value", "pnl"),
                    "position EUR profit and loss",
                )
                buying_price_eur = None
                if quote_currency == "EUR":
                    buying_price_eur = native_average_price
                    if pnl_eur is None and buying_price_eur is not None:
                        pnl_eur = current_value_eur - (quantity * buying_price_eur)
                elif pnl_eur is not None:
                    buying_price_eur = (current_value_eur - pnl_eur) / quantity
                isin = str(share.get("isin") or "").strip() or None
                symbol = str(
                    share.get("ticker")
                    or (share.get("sinara") or {}).get("symbol")
                    or isin
                    or share.get("name")
                    or ""
                ).strip()
                label = str(share.get("name") or symbol).strip()
                positions.append({
                    "isin": isin,
                    "symbol": symbol,
                    "label": label,
                    "quantity": str(quantity),
                    "buyingPriceEur": str(buying_price_eur) if buying_price_eur is not None else None,
                    "currentPrice": str(current_price) if current_price is not None else None,
                    "quoteCurrency": quote_currency,
                    "currentValueEur": str(current_value_eur),
                    "pnlEur": str(pnl_eur) if pnl_eur is not None else None,
                })

            valued_positions = sum(
                (decimal_value(position["currentValueEur"], "position EUR valuation") for position in positions),
                Decimal("0"),
            )
            if not _money_close(valued_positions, portfolio_value):
                # The socket has no explicit end-of-snapshot event. Reconciliation
                # against the broker's account-level valuation is the completion gate.
                self.incomplete_reason = "position-total-mismatch"
                return None

            raw_type = str(account.get("type") or "").upper()
            account_type = "PEA" if "PEA" in raw_type else "COMPTE_TITRES"
            result.append({
                "externalId": account_id,
                "name": str(account.get("label") or f"Bourse Direct {account_type}"),
                "type": account_type,
                "balanceEur": str(total),
                "cashBalance": str(cash),
                "positions": positions,
                "snapshotComplete": True,
            })
        self.incomplete_reason = "complete"
        return result

    def counts(self) -> tuple[int, int, int]:
        return (
            len(self.accounts),
            len(self.balances),
            sum(len(positions) for positions in self.positions.values()),
        )


async def _mark_portfolio_onboarding_complete(context: BrowserContext) -> None:
    await context.add_cookies([{
        "name": PORTFOLIO_ONBOARDING_COOKIE,
        "value": "ok",
        "domain": ".boursedirect.fr",
        "path": "/",
        "secure": True,
        "sameSite": "Lax",
    }])


async def _portfolio_link(page: Page) -> tuple[str | None, list[str]]:
    try:
        links = await page.eval_on_selector_all(
            "a[href]",
            """elements => elements.map(link => ({
                href: link.href || '',
                text: (link.textContent || '').replace(/\\s+/g, ' ').trim()
            }))""",
        )
    except PlaywrightError:
        # A successful login/MFA destroys the old execution context while the
        # browser navigates. The caller polls again against the replacement.
        return None, []
    candidates: list[tuple[int, str]] = []
    safe_paths: set[str] = set()
    for link in links:
        parsed = urlsplit(link["href"])
        hostname = parsed.hostname or ""
        if hostname != "boursedirect.fr" and not hostname.endswith(".boursedirect.fr"):
            continue
        path = parsed.path.lower()
        if any(fragment in path for fragment in (
            "ouvrir-un-compte", "inscription", "register", "fictif", "simulation", "demo",
        )):
            continue
        score = 0
        if path == "/fr/page/portefeuille-tr":
            score = 2000
        elif "/page/portefeuille" in path:
            score = 1000
        elif "portefeuille" in path:
            score = 900
        elif "portfolio" in path:
            score = 800
        elif "position" in path:
            score = 700
        if score:
            safe_paths.add(_safe_observed_path(link["href"]) or "/<redacted>")
            candidates.append((score, link["href"]))
    candidates.sort(reverse=True)
    return (candidates[0][1] if candidates else None, sorted(safe_paths))


async def _click_portfolio_link(page: Page, portfolio_link: str) -> Page:
    links = page.locator("a[href]")
    selected_index = await links.evaluate_all(
        "(elements, href) => elements.findIndex(link => link.href === href)",
        portfolio_link,
    )
    if selected_index < 0:
        raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
    selected = links.nth(selected_index)

    target = await selected.get_attribute("target", timeout=2_000) or "-"
    has_onclick = bool(await selected.get_attribute("onclick", timeout=2_000))
    initially_visible = await selected.is_visible()
    log.info(
        "Bourse Direct portfolio navigation click "
        "(path=%s; target=%s; onclick=%s; visible=%s)",
        _safe_observed_path(portfolio_link) or "/<redacted>",
        target,
        has_onclick,
        initially_visible,
    )
    if not initially_visible:
        # The authenticated link currently lives in a collapsed account menu.
        # Reveal that exact element temporarily so Playwright can emit the
        # trusted pointer event required by Bourse Direct's delegated handler.
        await selected.evaluate(
            """link => {
                for (
                    let element = link;
                    element && element !== document.documentElement;
                    element = element.parentElement
                ) {
                    element.removeAttribute('hidden');
                    element.style.setProperty('visibility', 'visible', 'important');
                    element.style.setProperty('opacity', '1', 'important');
                    element.style.setProperty('pointer-events', 'auto', 'important');
                    element.style.setProperty('transform', 'none', 'important');
                    element.style.setProperty('clip-path', 'none', 'important');
                    element.style.setProperty('overflow', 'visible', 'important');
                    element.style.setProperty('z-index', '2147483647', 'important');
                    if (getComputedStyle(element).display === 'none') {
                        element.style.setProperty('display', 'block', 'important');
                    }
                }
                link.style.setProperty('position', 'fixed', 'important');
                link.style.setProperty('inset', '16px auto auto 16px', 'important');
                link.style.setProperty('width', '240px', 'important');
                link.style.setProperty('height', '48px', 'important');
            }"""
        )
        await selected.wait_for(state="visible", timeout=3_000)

    pages_before = set(page.context.pages)
    # Locator.click() emits trusted pointer events. Bourse Direct ignores a
    # synthetic HTMLElement.click() for this authenticated navigation.
    await selected.click(timeout=15_000, no_wait_after=True)

    for _ in range(20):
        pages = page.context.pages
        new_pages = [candidate for candidate in pages if candidate not in pages_before]
        for candidate in [*reversed(new_pages), page]:
            path = urlsplit(candidate.url).path
            if path and path not in (NEWS_PATH, "/", "about:blank"):
                return candidate
        await asyncio.sleep(0.5)
    new_pages = [candidate for candidate in page.context.pages if candidate not in pages_before]
    return new_pages[-1] if new_pages else page


async def _visible_link_by_text(page: Page, text: str):
    for candidate_page in reversed(page.context.pages):
        for frame in candidate_page.frames:
            locator = frame.locator("a:visible", has_text=text).first
            try:
                if await locator.is_visible(timeout=250):
                    return candidate_page, locator
            except PlaywrightError:
                continue
    return None


async def _open_portfolio_from_account_menu(page: Page) -> tuple[Page, bool]:
    account_menu = await _visible_link_by_text(page, "Mes comptes")
    if not account_menu:
        return page, False

    owner_page, menu_link = account_menu
    pages_before = set(page.context.pages)
    log.info("Bourse Direct account navigation click (label=Mes comptes)")
    await menu_link.click(timeout=10_000, no_wait_after=True)

    for _ in range(40):
        realtime_link = await _visible_link_by_text(page, "Portefeuille temps réel")
        if realtime_link:
            link_page, selected = realtime_link
            pages_before_realtime = set(page.context.pages)
            log.info("Bourse Direct account navigation click (label=Portefeuille temps réel)")
            await selected.click(timeout=15_000, no_wait_after=True)
            await asyncio.sleep(1)
            new_pages = [
                candidate
                for candidate in page.context.pages
                if candidate not in pages_before_realtime
            ]
            selected_page = new_pages[-1] if new_pages else link_page
            log.info(
                "Bourse Direct account navigation completed (path=%s)",
                _safe_observed_path(selected_page.url) or "/<redacted>",
            )
            return selected_page, True
        await asyncio.sleep(0.25)

    new_pages = [
        candidate for candidate in page.context.pages if candidate not in pages_before
    ]
    return (new_pages[-1] if new_pages else owner_page), False


def _safe_observed_path(raw_url: str) -> str | None:
    parsed = urlsplit(raw_url)
    hostname = parsed.hostname or ""
    if hostname != "boursedirect.fr" and not hostname.endswith(".boursedirect.fr"):
        return None
    keywords = ("api", "account", "compte", "portfolio", "portefeuille", "streaming")
    if not any(keyword in parsed.path.lower() for keyword in keywords):
        return None
    segments = []
    previous = ""
    identifier_parents = {
        "account",
        "accounts",
        "compte",
        "comptes",
        "portfolio",
        "portfolios",
        "portefeuille",
        "portefeuilles",
    }
    for segment in parsed.path.split("/"):
        if (
            (previous.lower() in identifier_parents and segment)
            or (len(segment) >= 4 and any(character.isdigit() for character in segment))
        ):
            segments.append("<id>")
        else:
            segments.append(segment)
        previous = segment
    return re.sub(r"/{2,}", "/", "/".join(segments))


async def _observed_portfolio_paths(page: Page) -> list[str]:
    paths: set[str] = set()
    for candidate_page in page.context.pages:
        for frame in candidate_page.frames:
            try:
                urls = await frame.evaluate(
                    "performance.getEntriesByType('resource').map(entry => entry.name)"
                )
                for raw_url in urls:
                    safe_path = _safe_observed_path(raw_url)
                    if safe_path:
                        paths.add(safe_path)
            except PlaywrightError:
                continue
    return sorted(paths)[:80]


async def _portfolio_accounts(
    page: Page,
    modern: ModernPortfolioCollector,
) -> tuple[str, list[dict[str, Any]]]:
    await page.goto(PORTFOLIO_URL, wait_until="domcontentloaded", timeout=30_000)
    # The legacy URL first commits successfully, then the current site performs
    # a client-side redirect to the news page. Let that redirect settle before
    # deciding whether authenticated menu navigation is required.
    await asyncio.sleep(1)
    if "/login" in page.url:
        raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
    navigation_paths: list[str] = []
    if urlsplit(page.url).path != urlsplit(PORTFOLIO_URL).path:
        page, menu_opened = await _open_portfolio_from_account_menu(page)
        if not menu_opened:
            portfolio_link = None
            discovered_paths: set[str] = set()
            for _ in range(20):
                portfolio_link, paths = await _portfolio_link(page)
                discovered_paths.update(paths)
                if portfolio_link:
                    break
                await asyncio.sleep(0.5)
            navigation_paths = sorted(discovered_paths)
            if portfolio_link:
                selected_path = _safe_observed_path(portfolio_link) or "/<redacted>"
                log.info("Bourse Direct portfolio navigation discovered (path=%s)", selected_path)
                page = await _click_portfolio_link(page, portfolio_link)
                if "/login" in page.url:
                    raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            else:
                raise HTTPException(
                    status_code=401,
                    detail="SESSION_EXPIRED",
                )
    for _ in range(90):
        try:
            modern_accounts = modern.result_if_ready()
        except PortfolioFormatError as exc:
            raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
        if modern_accounts is not None:
            log.info(
                "Bourse Direct modern portfolio received "
                "(accounts=%d; positions=%d)",
                len(modern_accounts),
                sum(len(account["positions"]) for account in modern_accounts),
            )
            return "modern", modern_accounts
        pages = list(reversed(page.context.pages))
        for candidate_page in pages:
            for frame in candidate_page.frames:
                try:
                    selects = await frame.eval_on_selector_all(
                        "select",
                        """elements => elements.map(select => ({
                            id: select.id || '',
                            name: select.name || '',
                            options: Array.from(select.options).slice(0, 20).map(option => ({
                                value: (option.value || '').trim(),
                                label: (option.textContent || '').replace(/\\s+/g, ' ').trim()
                            }))
                        }))""",
                    )
                    for select in selects:
                        identity = f"{select['id']} {select['name']}".lower()
                        if not (
                            select["id"].lower() == "nc"
                            or select["name"].lower() == "nc"
                            or "compte" in identity
                        ):
                            continue
                        accounts: list[dict[str, str]] = []
                        for option in select["options"][:MAX_ACCOUNTS]:
                            value = option["value"]
                            label = option["label"]
                            if value and label:
                                accounts.append({"id": value, "name": label})
                        if accounts:
                            log.info(
                                "Bourse Direct account selector discovered "
                                "(id=%s; name=%s; accounts=%d)",
                                select["id"] or "-",
                                select["name"] or "-",
                                len(accounts),
                            )
                            return "legacy", accounts
                except PlaywrightError:
                    # Bourse Direct reloads its legacy portfolio iframe while
                    # the page initializes. Retry against the replacement.
                    continue
        await asyncio.sleep(0.5)
    frame_paths: set[str] = set()
    select_names: set[str] = set()
    frame_count = 0
    for candidate_page in page.context.pages:
        frame_count += len(candidate_page.frames)
        for frame in candidate_page.frames:
            try:
                safe_path = _safe_observed_path(frame.url)
                if safe_path:
                    frame_paths.add(safe_path)
                selects = await frame.eval_on_selector_all(
                    "select",
                    "elements => elements.slice(0, 30).map(select => "
                    "({id: select.id || '-', name: select.name || '-'}))",
                )
                for select in selects:
                    select_names.add(
                        f"id={select['id']},name={select['name']}"
                    )
            except PlaywrightError:
                continue
    log.warning(
        "Bourse Direct account selector not found after portfolio load "
        "(%d frames; paths=%s; selects=%s)",
        frame_count,
        sorted(frame_paths),
        sorted(select_names),
    )
    if navigation_paths:
        log.warning("Bourse Direct account-related navigation paths=%s", navigation_paths)
    observed_paths = await _observed_portfolio_paths(page)
    if observed_paths:
        log.warning("Bourse Direct observed portfolio network paths=%s", observed_paths)
    modern_counts = modern.counts()
    log.warning(
        "Bourse Direct modern portfolio incomplete "
        "(socket=%s; accounts=%d; balances=%d; positions=%d; reason=%s)",
        modern.socket_seen,
        modern_counts[0],
        modern_counts[1],
        modern_counts[2],
        modern.incomplete_reason,
    )
    raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")


async def _close_resources(
    context: BrowserContext | None,
    browser: Browser | None,
    playwright: Playwright | None,
) -> None:
    for resource, close_method in (
        (context, "close"),
        (browser, "close"),
        (playwright, "stop"),
    ):
        if resource is None:
            continue
        try:
            await asyncio.wait_for(
                getattr(resource, close_method)(),
                timeout=RESOURCE_CLOSE_TIMEOUT_SECONDS,
            )
        except Exception:
            log.warning("Bourse Direct browser resource cleanup failed", exc_info=True)


async def _dispose_pending_state(state: dict[str, Any]) -> None:
    await _close_resources(
        state.get("context"),
        state.get("browser"),
        state.get("playwright"),
    )


async def _close_pending(process_id: str) -> None:
    async with _pending_lock:
        state = _pending.pop(process_id, None)
    if state:
        await _dispose_pending_state(state)


async def _take_pending(process_id: str) -> dict[str, Any] | None:
    async with _pending_lock:
        return _pending.pop(process_id, None)


async def _cleanup_expired() -> None:
    cutoff = time.time() - PENDING_TTL_SECONDS
    async with _pending_lock:
        expired = [pid for pid, state in _pending.items() if state["created_at"] < cutoff]
    for pid in expired:
        await _close_pending(pid)


async def _close_all_pending() -> None:
    async with _pending_lock:
        states = list(_pending.values())
        _pending.clear()
    for state in states:
        await _dispose_pending_state(state)


async def _first_visible(page: Page, selectors: list[str]):
    for selector in selectors:
        locator = page.locator(selector).first
        try:
            if await locator.is_visible(timeout=800):
                return locator
        except Exception:
            continue
    return None


async def _otp_visible(page: Page) -> bool:
    try:
        return await page.locator(
            '.code-input.react-code-input input, input[data-id]'
        ).first.is_visible()
    except PlaywrightError:
        return False


async def _wait_for_session_state(context: BrowserContext, page: Page) -> str:
    session_state: dict[str, Any] | None = None
    for _ in range(MFA_RESULT_TIMEOUT_SECONDS * 4):
        state = await context.storage_state()
        if any(cookie.get("name") == "CAPITOL" for cookie in state.get("cookies", [])):
            portfolio_link, _ = await _portfolio_link(page)
            if portfolio_link:
                return json.dumps(state, separators=(",", ":"))
            # CAPITOL can exist before MFA is complete. The OTP form
            # disappearing is the reliable signal that its submission ended.
            if not await _otp_visible(page):
                session_state = state
                break
        await asyncio.sleep(0.25)

    if session_state is not None:
        # Let the final MFA response update cookies/local storage, then validate
        # the session against a fresh authenticated page.
        await asyncio.sleep(1)
        await page.goto(
            f"{BASE_URL}{NEWS_PATH}",
            wait_until="domcontentloaded",
            timeout=SESSION_CHECK_TIMEOUT_SECONDS * 1000,
        )
        for _ in range(SESSION_CHECK_TIMEOUT_SECONDS * 4):
            portfolio_link, _ = await _portfolio_link(page)
            if portfolio_link:
                complete_state = await context.storage_state()
                return json.dumps(complete_state, separators=(",", ":"))
            await asyncio.sleep(0.25)
        if await _otp_visible(page) or "login" in page.url:
            raise HTTPException(status_code=401, detail="INVALID_OTP")
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    if await _otp_visible(page) or "login" in page.url:
        raise HTTPException(status_code=401, detail="INVALID_OTP")
    raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")


def _portfolio_http_exception(status: int) -> HTTPException:
    if status in (401, 403):
        return HTTPException(status_code=401, detail="SESSION_EXPIRED")
    if status == 429 or status >= 500:
        return HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE")
    return HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/initiate", response_model=InitiateResponse)
async def initiate(req: InitiateRequest) -> dict:
    await _cleanup_expired()
    process_id = str(uuid.uuid4())
    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(locale="fr-FR")
        await context.route(
            "**/*",
            lambda route: route.abort()
            if route.request.resource_type in ("image", "media", "font")
            else route.continue_(),
        )
        page = await context.new_page()
        await page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=30_000)

        consent = await _first_visible(page, [
            'button:has-text("Accepter tous les cookies")',
            '#didomi-notice-agree-button',
        ])
        if consent:
            await consent.click()

        login = await _first_visible(page, [
            '[data-testid="input-username"]', '#bd_auth_login_type_login',
            'input[placeholder="Identifiant"]',
        ])
        password = await _first_visible(page, [
            '[data-testid="input-password"]', '#bd_auth_login_type_password',
            'input[type="password"]',
        ])
        submit = await _first_visible(page, [
            '[data-testid="button-submit"]', '#bd_auth_login_type_submit',
            'button[type="submit"]',
        ])
        if not login or not password or not submit:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")

        await login.fill(req.login)
        await password.fill(req.password)
        await submit.click()

        otp = page.locator('.code-input.react-code-input input, input[data-id]').first
        try:
            await otp.wait_for(state="visible", timeout=15_000)
            async with _pending_lock:
                _pending[process_id] = {
                    "playwright": pw, "browser": browser, "context": context,
                    "page": page, "created_at": time.time(),
                }
            return {"processId": process_id, "mfaRequired": True, "mfaType": "OTP", "sessionState": None}
        except PlaywrightTimeoutError:
            if "login" in page.url:
                raise HTTPException(status_code=401, detail="INVALID_CREDENTIALS")
            state = await _wait_for_session_state(context, page)
            await _close_resources(context, browser, pw)
            return {"processId": None, "mfaRequired": False, "mfaType": None, "sessionState": state}
    except HTTPException:
        await _close_resources(context, browser, pw)
        raise
    except PlaywrightError as exc:
        await _close_resources(context, browser, pw)
        log.warning("Bourse Direct authentication initiation failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        await _close_resources(context, browser, pw)
        log.exception("Unexpected Bourse Direct authentication initiation failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc


@app.post("/complete", response_model=SessionResponse)
async def complete(req: CompleteRequest) -> dict:
    await _cleanup_expired()
    state = await _take_pending(req.processId)
    if not state:
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    if state["created_at"] < time.time() - PENDING_TTL_SECONDS:
        await _dispose_pending_state(state)
        raise HTTPException(status_code=410, detail="AUTH_ATTEMPT_EXPIRED")
    page: Page = state["page"]
    try:
        inputs = page.locator('.code-input.react-code-input input, input[data-id]')
        if await inputs.count() < 6:
            raise HTTPException(status_code=502, detail="UPSTREAM_FORMAT_CHANGED")
        for index, digit in enumerate(req.code):
            await inputs.nth(index).fill(digit)

        distrust = await _first_visible(page, [
            '#distrusted', 'input[value="distrusted"]',
            'label:has-text("Non") input',
        ])
        if distrust:
            await distrust.check()
        submit = await _first_visible(page, [
            'button:has-text("Continuer")', 'button:has-text("Confirmer")',
            'button[type="submit"]',
        ])
        if submit:
            await submit.click()
        session_state = await _wait_for_session_state(state["context"], page)
        return {"sessionState": session_state}
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Bourse Direct authentication completion failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Bourse Direct authentication completion failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _dispose_pending_state(state)


@app.post("/accounts", response_model=list[AccountPayload])
async def accounts(req: AccountsRequest) -> list[AccountPayload]:
    try:
        storage_state = json.loads(req.sessionState)
    except (TypeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail="INVALID_DATA") from exc
    if not isinstance(storage_state, dict):
        raise HTTPException(status_code=400, detail="INVALID_DATA")

    pw: Playwright | None = None
    browser: Browser | None = None
    context: BrowserContext | None = None
    try:
        pw = await async_playwright().start()
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(storage_state=storage_state, locale="fr-FR")
        await _mark_portfolio_onboarding_complete(context)
        modern = ModernPortfolioCollector()
        context.on("page", modern.attach)
        page = await context.new_page()
        modern.attach(page)
        portfolio_mode, portfolio_data = await _portfolio_accounts(page, modern)
        if portfolio_mode == "modern":
            return [AccountPayload.model_validate(account) for account in portfolio_data]
        account_options = portfolio_data

        async def fetch_account(index: int, account: dict[str, str]) -> dict:
            query = urlencode({"stream": "0", "nc": account["id"]})
            response = await context.request.get(
                f"{BASE_URL}/streaming/compteTempsReelCK.php?{query}",
                timeout=20_000,
            )
            if response.status in (401, 403) or "/fr/login" in response.url:
                raise HTTPException(status_code=401, detail="SESSION_EXPIRED")
            if not response.ok:
                error = _portfolio_http_exception(response.status)
                log.warning(
                    "Bourse Direct portfolio request failed (status=%d; code=%s)",
                    response.status,
                    error.detail,
                )
                raise error
            parsed = parse_portfolio(
                await response.text(), index, account["id"], account["name"]
            )
            if parsed:
                return parsed
            raise HTTPException(
                status_code=502,
                detail="PORTFOLIO_INCOMPLETE",
            )

        result = await asyncio.gather(*(
            fetch_account(index, account)
            for index, account in enumerate(account_options, start=1)
        ))
        if not result:
            raise HTTPException(status_code=502, detail="PORTFOLIO_INCOMPLETE")
        return [AccountPayload.model_validate(account) for account in result]
    except (PortfolioFormatError, ValidationError) as exc:
        raise HTTPException(status_code=502, detail="INVALID_DATA") from exc
    except HTTPException:
        raise
    except PlaywrightError as exc:
        log.warning("Bourse Direct portfolio browser failed", exc_info=True)
        raise HTTPException(status_code=502, detail="UPSTREAM_UNAVAILABLE") from exc
    except Exception as exc:
        log.exception("Unexpected Bourse Direct portfolio failure")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from exc
    finally:
        await _close_resources(context, browser, pw)
