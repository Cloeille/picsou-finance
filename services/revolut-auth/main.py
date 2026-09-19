"""
Revolut Auth Sidecar
--------------------
Owns a logged-in Revolut web session (app.revolut.com, "personal" surface --
Phase 1 of docs/features/revolut-sidecar.md) and exposes a minimal HTTP API
consumed by the Spring backend.

Why Camoufox (stealth Firefox) and not plain Playwright Chromium:
  Revolut's anti-bot fingerprints and blocks a vanilla Playwright Chromium
  (captcha loops, "Mauvais code d'accès"), but accepts a real Firefox engine
  (the user's own Firefox-based browser logs in fine). Camoufox spoofs the
  fingerprint at the C++ level (0% bot-detection) and drives Playwright.

Session model -- a PERSISTENT Camoufox profile per member (user_data_dir under
REVOLUT_PROFILES_DIR), reused across enrolment and every sync. Reusing the same
profile keeps the browser fingerprint AND cookies stable, which is what keeps the
session alive: replaying from fresh per-sync instances (random fingerprint each
time) gets the session invalidated by Revolut. The profile IS the stored session;
Java only tracks metadata (active / expiresAt). See docs §3.5.

Auth model (recon, docs §3): retail API = httpOnly session cookie (in the profile)
+ header x-device-id (= the revo_device_id cookie) + x-browser-application:
WEB_CLIENT + x-client-version. Access token ~4 min, refreshed via PUT
/api/retail/token.

Endpoints:
  POST /enrolment  {phoneNumber, passcode, memberId} -> automated login (Camoufox
                   fills phone+passcode; the user approves the push on their phone),
                   session captured into the member's persistent profile.
  POST /accounts   {memberId} -> headless sync from the member's profile: refresh
                   token, harvest wallets/pockets/money-boxes/IBANs/transactions.
"""

import asyncio
import logging
import os
import re
import shutil
import time
from collections import Counter
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional, Tuple

from camoufox.async_api import AsyncCamoufox
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("revolut-auth")

app = FastAPI()

APP_URL = "https://app.revolut.com/"
HOME_URL = "https://app.revolut.com/home"

PROFILES_ROOT = os.environ.get("REVOLUT_PROFILES_DIR", "/data/revolut-profiles")

ENROLMENT_APPROVE_WAIT_S = 300
POLL_MS = 3000
MAX_TRANSACTION_PAGES = 20
TRANSACTION_WINDOW_DAYS = 90
CAMOUFOX_LAUNCH_TIMEOUT_S = float(os.environ.get("CAMOUFOX_LAUNCH_TIMEOUT_S", "60"))

NOISE_POCKET_TYPES = {"MERCHANT", "REVX_FIAT"}
FIAT_FALLBACK = {"EUR", "USD", "GBP", "CHF", "JPY", "CAD", "AUD", "SEK", "NOK", "DKK",
                 "PLN", "CZK", "HUF", "RON", "BGN", "TRY", "ZAR", "SGD", "HKD", "NZD",
                 "MXN", "ILS", "AED", "THB"}


class BrowserLaunchError(RuntimeError):
    """Raised when Camoufox cannot open a member profile within the bounded launch window."""


def _profile_key(member_id: str) -> str:
    """Canonical per-member key, used for BOTH the profile dir and the sync lock so the
    lock always guards the exact profile it protects. Raw ids that sanitize to the same
    dir (e.g. "1" and "1!") MUST collapse to one key, or two syncs would take different
    locks yet share one Firefox profile -- the collision the lock exists to prevent."""
    return re.sub(r"[^A-Za-z0-9_-]", "", str(member_id)) or "default"


def _profile_dir(member_id: str) -> str:
    path = os.path.join(PROFILES_ROOT, _profile_key(member_id))
    os.makedirs(path, exist_ok=True)
    return path


def _has_profile(member_id: str) -> bool:
    path = os.path.join(PROFILES_ROOT, _profile_key(member_id))
    return os.path.isdir(path) and bool(os.listdir(path))


# Firefox writes these into a profile to guard against two instances opening it at
# once. A browser that exits uncleanly (killed mid-login, container restart, OOM)
# leaves them behind, and Firefox then refuses to open the profile forever after with
# "Firefox is already running, but is not responding". `lock` is a symlink to
# <ip>:+<pid>; `.parentlock` is an fcntl-locked file.
_FIREFOX_LOCK_FILES = ("lock", ".parentlock")


def _clear_stale_locks(member_id: str) -> None:
    """Remove leftover Firefox profile-lock files before launching Camoufox.

    Only ever called while holding the member's async lock (see `_member_lock`), so no
    live in-process browser can own these files -- any present are stale from a browser
    that died, and are safe to delete. This is what lets the next /sync recover instead
    of the profile staying wedged until someone clears the volume by hand."""
    profile = _profile_dir(member_id)
    for name in _FIREFOX_LOCK_FILES:
        try:
            os.remove(os.path.join(profile, name))  # removes the `lock` symlink itself, not its target
        except FileNotFoundError:
            pass
        except OSError as exc:  # noqa: BLE001
            log.warning("could not clear stale lock %s/%s: %s", profile, name, exc)


def _camoufox(member_id: str, headless: bool):
    """A persistent Camoufox context bound to the member's profile dir. os=linux +
    geoip keep the fingerprint consistent with the host; humanize adds human-like
    cursor motion. Camoufox manages UA/navigator/canvas/WebGL itself. Stale profile
    locks are cleared first so a previously-killed browser doesn't wedge this launch."""
    _clear_stale_locks(member_id)
    return AsyncCamoufox(headless=headless, humanize=True, os="linux", geoip=True,
                         persistent_context=True, user_data_dir=_profile_dir(member_id))


def _quarantine_profile(member_id: str) -> Optional[str]:
    """Move a suspect Firefox profile aside and leave a fresh directory in its place."""
    key = _profile_key(member_id)
    profile = os.path.join(PROFILES_ROOT, key)
    if not os.path.isdir(profile) or not os.listdir(profile):
        return None

    quarantine_root = os.path.join(PROFILES_ROOT, ".quarantine")
    os.makedirs(quarantine_root, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    target = os.path.join(quarantine_root, f"{key}-{stamp}")
    suffix = 1
    while os.path.exists(target):
        suffix += 1
        target = os.path.join(quarantine_root, f"{key}-{stamp}-{suffix}")

    shutil.move(profile, target)
    os.makedirs(profile, exist_ok=True)
    log.warning("member %s: quarantined Revolut browser profile at %s", member_id, target)
    return target


@asynccontextmanager
async def _open_camoufox(member_id: str, headless: bool, recover_profile: bool = False):
    """Open Camoufox with a bounded launch time and one profile-recovery retry.

    Playwright's default persistent-context launch timeout is long enough to outlive the
    backend's old sync budget. Bounding it here keeps /sync in control of its own error
    shape, and quarantining a wedged profile gives the member one clean retry before we
    surface a flat sidecar failure to Java.
    """
    attempts = 2 if recover_profile and _has_profile(member_id) else 1
    last_error: Optional[BaseException] = None

    for attempt in range(attempts):
        manager = _camoufox(member_id, headless=headless)
        ctx = None
        try:
            async with asyncio.timeout(CAMOUFOX_LAUNCH_TIMEOUT_S):
                ctx = await manager.__aenter__()
        except asyncio.TimeoutError as exc:
            last_error = exc
            log.warning("member %s: Camoufox launch timed out after %.0fs (headless=%s)",
                        member_id, CAMOUFOX_LAUNCH_TIMEOUT_S, headless)
        except BrowserLaunchError as exc:
            last_error = exc
        except Exception as exc:  # noqa: BLE001
            last_error = exc
            log.warning("member %s: Camoufox launch failed (headless=%s): %s",
                        member_id, headless, exc)
        else:
            try:
                yield ctx
            except BaseException as exc:  # noqa: BLE001
                suppress = await manager.__aexit__(type(exc), exc, exc.__traceback__)
                if not suppress:
                    raise
            else:
                await manager.__aexit__(None, None, None)
            return

        if ctx is not None:
            await manager.__aexit__(None, None, None)
        if recover_profile and attempt == 0 and _has_profile(member_id):
            _quarantine_profile(member_id)
            continue
        break

    raise BrowserLaunchError(str(last_error) if last_error else "Camoufox launch failed")


# ─── Auth / device id ─────────────────────────────────────────────────────────

async def _device_id(ctx) -> str:
    try:
        for c in await ctx.cookies():
            if c.get("name") == "revo_device_id":
                return c.get("value") or ""
    except Exception:  # noqa: BLE001
        pass
    return ""


def _pick(d: Optional[Dict[str, Any]], *keys: str, default: Any = None) -> Any:
    if not isinstance(d, dict):
        return default
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return default


def _to_number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _minor_to_major(value: Any) -> float:
    """Revolut balances are integer MINOR units (cents): 12345 == 123.45."""
    try:
        return float(value) / 100.0
    except (TypeError, ValueError):
        return 0.0


def session_expired() -> JSONResponse:
    """Flat 401 the backend parses (not FastAPI's HTTPException, which wraps `detail`)."""
    return JSONResponse(status_code=401, content={"error": "SESSION_EXPIRED"})


def browser_launch_failed() -> JSONResponse:
    """Flat 503 the backend parses when the browser runtime/profile cannot launch."""
    return JSONResponse(status_code=503, content={"error": "BROWSER_LAUNCH_FAILED"})


# ─── In-page authenticated fetch (below the app's JS, per docs §3.4) ────────────

_JS_FETCH = """
async ({ path, method, deviceId, params }) => {
    try {
        const url = new URL(path, window.location.origin);
        if (params) for (const [k, v] of Object.entries(params)) {
            if (v !== undefined && v !== null) url.searchParams.set(k, v);
        }
        const r = await fetch(url.toString(), {
            method, credentials: 'include',
            headers: { 'x-device-id': deviceId || '', 'x-browser-application': 'WEB_CLIENT',
                       'x-client-version': '100.0' },
        });
        let data = null; try { data = await r.json(); } catch (e) { data = null; }
        return { status: r.status, data };
    } catch (e) { return { status: 0, data: null, error: String(e) }; }
}
"""


async def api_call(page, path, device_id, method="GET", params=None) -> Dict[str, Any]:
    for attempt in range(3):
        try:
            return await page.evaluate(_JS_FETCH, {"path": path, "method": method,
                                                    "deviceId": device_id, "params": params or {}})
        except Exception as e:  # noqa: BLE001
            msg = str(e).lower()
            if attempt < 2 and ("context was destroyed" in msg or "execution context" in msg
                                or "navigation" in msg):
                await page.wait_for_timeout(1500)
                continue
            log.warning("api_call failed for %s %s: %s", method, path, e)
            return {"status": 0, "data": None}
    return {"status": 0, "data": None}


async def refresh_access_token(page, device_id) -> int:
    return (await api_call(page, "/api/retail/token", device_id, method="PUT")).get("status", 0)


async def _settle(page) -> None:
    """Let the SPA finish its SSO re-check / routing before we fire in-page fetches."""
    try:
        await page.goto(HOME_URL, wait_until="domcontentloaded", timeout=30000)
        await page.wait_for_load_state("networkidle", timeout=15000)
    except Exception:  # noqa: BLE001
        await page.wait_for_timeout(3000)
    await page.wait_for_timeout(1500)


# ─── Harvest ────────────────────────────────────────────────────────────────

async def _fetch_wallets(page, device_id) -> List[Tuple[Optional[str], List[Dict[str, Any]]]]:
    """/api/retail/wallets is a dict keyed by account type -- {"PERSONAL": [wallet,...],
    "PERSONAL_JOINT": [...], ...} -- each wallet has a `pockets` list. Prefer it (covers
    joint accounts); fall back to the singular /user/current/wallet."""
    out: List[Tuple[Optional[str], List[Dict[str, Any]]]] = []
    resp = await api_call(page, "/api/retail/wallets", device_id)
    if resp.get("status") == 200 and isinstance(resp.get("data"), dict):
        for group in resp["data"].values():
            if isinstance(group, list):
                for w in group:
                    out.append((_pick(w, "id", "walletId"),
                                (w.get("pockets") or []) + (w.get("sharedPockets") or [])))
    if not out:
        resp = await api_call(page, "/api/retail/user/current/wallet", device_id)
        if resp.get("status") == 200 and isinstance(resp.get("data"), dict) and resp["data"].get("pockets"):
            out.append((_pick(resp["data"], "id", "walletId"), resp["data"].get("pockets") or []))
    if not out:
        log.warning("no wallet found")
    return out


async def _fetch_money_boxes(page, device_id) -> List[Dict[str, Any]]:
    seen, boxes = set(), []
    for params in ({"accountType": "PERSONAL"}, {"accountType": "PERSONAL_JOINT"}, None):
        resp = await api_call(page, "/api/retail/user/current/money-boxes", device_id, params=params)
        if resp.get("status") != 200 or not resp.get("data"):
            continue
        data = resp["data"]
        for mb in (data if isinstance(data, list) else data.get("moneyBoxes", [])) or []:
            mb_id = _pick(mb, "id")
            if mb_id and mb_id not in seen:
                seen.add(mb_id)
                boxes.append(mb)
    return boxes


async def _fetch_fiat_currencies(page, device_id) -> set:
    resp = await api_call(page, "/api/retail/currencies", device_id, params={"type": "fiat"})
    codes: set = set()
    if resp.get("status") == 200 and resp.get("data"):
        data = resp["data"]
        items = data if isinstance(data, list) else (list(data.values()) if isinstance(data, dict) else [])
        for it in items:
            if isinstance(it, str):
                codes.add(it)
            elif isinstance(it, dict):
                c = _pick(it, "code", "currency", "isoCode", "id")
                if isinstance(c, str):
                    codes.add(c)
    return codes or set(FIAT_FALLBACK)


async def _fetch_iban(page, device_id, wallet_id, currency) -> Optional[str]:
    resp = await api_call(page, "/api/retail/bank-accounts/account-details", device_id, params={
        "currency": currency, "pocketType": "CURRENT", "locale": "fr-FR", "walletId": wallet_id})
    if resp.get("status") == 200 and isinstance(resp.get("data"), dict):
        return _pick(resp["data"], "iban", "IBAN")
    return None


async def _fetch_transactions(page, device_id, pocket_id) -> List[Dict[str, Any]]:
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    cutoff = now_ms - TRANSACTION_WINDOW_DAYS * 24 * 3600 * 1000
    out, seen, cursor = [], set(), now_ms
    for _ in range(MAX_TRANSACTION_PAGES):
        resp = await api_call(page, "/api/retail/user/current/transactions/last", device_id,
                              params={"internalPocketId": pocket_id, "to": cursor})
        if resp.get("status") != 200 or not resp.get("data"):
            break
        data = resp["data"]
        batch = data if isinstance(data, list) else data.get("transactions", [])
        if not batch:
            break
        new_count, oldest = 0, cursor
        for t in batch:
            tid = _pick(t, "id")
            if not tid or tid in seen:
                continue
            seen.add(tid)
            new_count += 1
            ts = _pick(t, "completedDate", "startedDate", default=0)
            if ts and ts < oldest:
                oldest = ts
            if ts and ts < cutoff:
                continue
            merchant = _pick(t, "merchant", default={}) or {}
            out.append({
                "externalId": tid,
                "date": datetime.fromtimestamp(ts / 1000, tz=timezone.utc).strftime("%Y-%m-%d") if ts else "",
                "description": _pick(t, "description") or _pick(merchant, "name") or "",
                "amount": _minor_to_major(_pick(t, "amount", default=0)),
                "counterparty": _pick(merchant, "name"),
            })
        if new_count == 0 or oldest <= cutoff or oldest >= cursor:
            break
        cursor = oldest
    return out


def _with_wallet_parents(accounts: List[Dict[str, Any]], wallet_currencies: Dict[str, str],
                          ibans: Dict[str, Optional[str]]) -> List[Dict[str, Any]]:
    """Revolut never hands out an account object for the wallet itself -- only its pockets
    and money-boxes -- so without this step every pocket/vault surfaces as its own
    top-level account instead of nesting under one wallet. Synthesizes that missing
    parent (one per entry in `wallet_currencies`) and re-parents same-currency children
    under it. Pure / no I/O so it's unit-testable without a live `page`; harvest_accounts
    does the awaiting (each wallet's dominant currency + IBAN) before calling this.

    A child only nests if its currency matches the wallet's dominant currency (Python does
    no FX conversion): a different-currency pocket/vault is re-parented to top-level
    instead of being silently folded in and losing its balance. A parentExternalId that
    doesn't match any known wallet at all (money-boxes report theirs via a looser
    accountId/walletId/podId fallback -- see harvest_accounts) is equally untrusted and
    demoted to top-level, with a warning.

    The parent's balance is set to the sum of its same-currency children's balances --
    REQUIRED because the backend/dashboard excludes children from net-worth and counts
    only the parent (see Account.java)."""
    existing_ids = {a["externalId"] for a in accounts}
    sums: Dict[str, float] = {wallet_id: 0.0 for wallet_id in wallet_currencies}
    out: List[Dict[str, Any]] = []
    for acc in accounts:
        wallet_id = acc.get("parentExternalId")
        if wallet_id is not None:
            acc = dict(acc)
            if wallet_id not in wallet_currencies:
                log.warning("account %s has unresolvable parent %s -- keeping it top-level",
                            acc["externalId"], wallet_id)
                acc["parentExternalId"] = None
            elif acc["currency"] != wallet_currencies[wallet_id]:
                acc["parentExternalId"] = None
            else:
                sums[wallet_id] += acc["balance"]
        out.append(acc)
    for wallet_id, ccy in wallet_currencies.items():
        if wallet_id in existing_ids:
            # Revolut sometimes hands a pocket the same id as its own wallet (the pocket
            # loop below already refuses to self-parent it); don't emit a second account
            # under that externalId on top of it.
            log.warning("wallet %s already has an account with that externalId -- "
                        "skipping its synthetic parent", wallet_id)
            continue
        out.append({
            "externalId": wallet_id, "name": "Revolut", "type": "CHECKING",
            "iban": ibans.get(wallet_id), "balance": sums[wallet_id], "currency": ccy,
            "parentExternalId": None, "transactions": [],
        })
    return out


async def harvest_accounts(page, device_id, on_progress: Optional[Callable[[int], None]] = None) -> Dict[str, Any]:
    accounts: List[Dict[str, Any]] = []

    # Money-boxes (vaults) first -- record their pocket ids so those pockets are not
    # also surfaced as current accounts (a vault would otherwise appear twice).
    mb_pocket_ids: set = set()
    for mb in await _fetch_money_boxes(page, device_id):
        mb_id = _pick(mb, "id")
        if not mb_id:
            continue
        mb_pocket = _pick(mb, "pocket", default={})
        if isinstance(mb_pocket, dict) and _pick(mb_pocket, "id"):
            mb_pocket_ids.add(_pick(mb_pocket, "id"))
        bal = _pick(mb, "balance", default={})  # nested {"amount": cents, "currency": "EUR"}
        balance = _minor_to_major(_pick(bal, "amount", default=0)) if isinstance(bal, dict) else _minor_to_major(bal)
        currency = _pick(bal, "currency") if isinstance(bal, dict) else None
        accounts.append({
            "externalId": mb_id, "name": _pick(mb, "name", default="Vault"), "type": "SAVINGS",
            "iban": None, "balance": balance,
            "currency": currency or _pick(mb, "currency", "currencyCode", default="EUR"),
            "parentExternalId": _pick(mb, "accountId", "walletId", "podId"), "transactions": [],
        })
    if on_progress:
        on_progress(len(accounts))

    fiat = await _fetch_fiat_currencies(page, device_id)

    # Current-account pockets: keep only real fiat CURRENT pockets; drop closed ones,
    # money-box pockets (dedup), MERCHANT/REVX_FIAT sleeves, and non-fiat (crypto) pockets.
    # `current_ccy` records each wallet's CURRENT-typed pocket currency -- its "home"
    # currency, and the one its IBAN is issued in (see _with_wallet_parents).
    wallets = await _fetch_wallets(page, device_id)
    current_ccy: Dict[str, str] = {}
    for wallet_id, pockets in wallets:
        for pocket in pockets:
            pid = _pick(pocket, "id")
            ptype = _pick(pocket, "type", default="")
            currency = _pick(pocket, "currency", "currencyCode", default="EUR")
            if (not pid or pocket.get("closed") or pid in mb_pocket_ids
                    or ptype in NOISE_POCKET_TYPES or currency not in fiat):
                continue
            accounts.append({
                "externalId": pid, "name": _pick(pocket, "name", "type", default="Revolut"),
                "type": "CHECKING", "iban": None,
                "balance": _minor_to_major(_pick(pocket, "balance", "amount", default=0)),
                "currency": currency,
                "parentExternalId": wallet_id if wallet_id and wallet_id != pid else None,
                "transactions": [],
            })
            if wallet_id and ptype == "CURRENT":
                current_ccy[wallet_id] = currency
    if on_progress:
        on_progress(len(accounts))

    # Each wallet's dominant currency: its CURRENT pocket's currency, or (no CURRENT
    # pocket found) whichever currency is most common among its own children. One IBAN
    # fetch per wallet, in that currency only -- not the old N x M loop over every
    # currency seen anywhere among CHECKING accounts.
    wallet_ids = {wallet_id for wallet_id, _ in wallets if wallet_id}
    wallet_currencies: Dict[str, str] = {}
    for wallet_id in wallet_ids:
        if wallet_id in current_ccy:
            wallet_currencies[wallet_id] = current_ccy[wallet_id]
        else:
            siblings = [a["currency"] for a in accounts if a["parentExternalId"] == wallet_id]
            wallet_currencies[wallet_id] = Counter(siblings).most_common(1)[0][0] if siblings else "EUR"
    ibans: Dict[str, Optional[str]] = {}
    for wallet_id, ccy in wallet_currencies.items():
        ibans[wallet_id] = await _fetch_iban(page, device_id, wallet_id, ccy)

    accounts = _with_wallet_parents(accounts, wallet_currencies, ibans)

    for acc in accounts:
        if acc["type"] == "CHECKING" and acc["externalId"] not in wallet_ids:
            acc["transactions"] = await _fetch_transactions(page, device_id, acc["externalId"])
            if on_progress:
                on_progress(len(accounts))

    return {"accounts": accounts}


# ─── Automated login (enrolment) ───────────────────────────────────────────────

async def _logged_in(page) -> bool:
    try:
        return await page.evaluate(
            """async () => {
                const c = document.cookie.split(';').map(s=>s.trim())
                          .find(s=>s.startsWith('revo_device_id='));
                const dev = c ? c.split('=').slice(1).join('=') : '';
                const r = await fetch('/api/retail/token/info', {credentials:'include',
                  headers:{'x-device-id':dev,'x-browser-application':'WEB_CLIENT','x-client-version':'100.0'}});
                return r.status === 200;
            }""")
    except Exception:  # noqa: BLE001
        return False


async def _click_continue(page) -> None:
    for name in ("Continuer", "Continue"):
        btn = page.get_by_role("button", name=name)
        if await btn.count():
            for _ in range(25):
                try:
                    if await btn.first.is_enabled():
                        await btn.first.click()
                        return
                except Exception:  # noqa: BLE001
                    pass
                await page.wait_for_timeout(300)
    await page.keyboard.press("Enter")


async def _fill_phone(page, phone: str) -> None:
    num = phone.strip().replace(" ", "")
    for pfx in ("+33", "0033"):
        if num.startswith(pfx):
            num = num[len(pfx):]
    if num.startswith("0"):
        num = num[1:]
    tel = page.locator("input[name='phoneNumber'], input[inputmode='tel'], input[type='tel']").first
    await tel.click()
    await tel.fill("")
    await tel.type(num, delay=70)
    await _click_continue(page)
    await page.wait_for_timeout(5000)


async def _fill_passcode(page, passcode: str) -> None:
    boxes = page.get_by_role("textbox")
    if await boxes.count() >= 6:
        await boxes.first.click()
        await page.keyboard.type(passcode, delay=120)  # 6-digit passcode auto-submits; no click
        return
    for sel in ("input[type='password']", "input[inputmode='numeric']"):
        loc = page.locator(sel)
        if await loc.count():
            await loc.first.fill(passcode)
            await page.keyboard.press("Enter")
            return


# ─── Per-member serialization ───────────────────────────────────────────────
# A persistent Camoufox profile can be opened by only ONE Firefox process at a time.
# Concurrent /sync calls for the same member (a double-click, or the daily scheduler
# overlapping a manual sync) would otherwise launch two browsers on the same profile
# and the second dies with "Firefox is already running". The sidecar runs as a single
# uvicorn worker (one event loop), so an in-process asyncio.Lock per member suffices.
_member_locks: Dict[str, asyncio.Lock] = {}

# Live sync progress, keyed identically to _member_locks (_profile_key(member_id)). Read by
# GET /progress/{member_id} so the backend can poll phase/countdown/accounts-found while the
# blocking POST /sync call is in flight -- same single-uvicorn-worker/single-event-loop safety
# rationale as _member_locks (plain dict, no lock needed). Overwritten (not deleted) each time a
# sync starts, and never cleared on completion: the backend polls it, so a lingering last phase
# is harmless and any stale state is replaced the moment a new sync starts.
_progress: Dict[str, dict] = {}


def _set_progress(member_id: str, phase: str, **extra: Any) -> None:
    _progress[_profile_key(member_id)] = {"phase": phase, "updatedAt": time.time(), **extra}


def _member_lock(member_id: str) -> asyncio.Lock:
    key = _profile_key(member_id)  # SAME key as the profile dir -- guard the resource, not the raw id
    lock = _member_locks.get(key)
    if lock is None:
        lock = asyncio.Lock()  # safe to create lazily: no await between get and set
        _member_locks[key] = lock
    return lock


def sync_in_progress() -> JSONResponse:
    """Flat 409 the backend parses (mirrors session_expired()'s flat 401)."""
    return JSONResponse(status_code=409, content={"error": "SYNC_IN_PROGRESS"})


# ─── Endpoints ────────────────────────────────────────────────────────────────

class SyncRequest(BaseModel):
    phoneNumber: str
    passcode: str
    memberId: str
    allowLogin: bool = True


async def _harvest_from_profile(member_id: str) -> Optional[Dict[str, Any]]:
    """Sync from an existing profile session WITHOUT logging in (headless). Returns the
    accounts if the session is still alive, or None if it's dead/absent -- in which case
    the caller performs a fresh login. This is what makes repeated syncs within a session's
    lifetime need no mobile approval, and lets a future keep-alive make daily sync free."""
    if not _has_profile(member_id):
        return None
    log.info("member %s: launching headless browser (session reuse attempt)", member_id)
    async with _open_camoufox(member_id, headless=True, recover_profile=True) as ctx:
        page = await ctx.new_page()
        await _settle(page)
        device_id = await _device_id(ctx)
        if not device_id:
            return None
        if await refresh_access_token(page, device_id) == 401:
            return None
        if (await api_call(page, "/api/retail/token/info", device_id)).get("status") == 401:
            return None
        _set_progress(member_id, "HARVESTING")
        return await harvest_accounts(
            page, device_id, on_progress=lambda n: _set_progress(member_id, "HARVESTING", accountsFound=n))


@app.post("/sync")
async def sync(req: SyncRequest):
    """On-demand sync. First tries to reuse a still-live profile session (no login, no
    mobile approval). If the session is dead/absent, does an automated login (Camoufox
    fills phone+passcode; the user approves the push on their phone), then harvests.
    Credentials are used for this call only; the sidecar never stores them (Java decides
    whether to remember them, encrypted).

    Serialized per member: a sync already in flight for this member holds the profile,
    so a second concurrent call fast-fails with 409 rather than colliding on the profile
    lock (which is what surfaced as a 500)."""
    lock = _member_lock(req.memberId)
    # locked() + `async with` is atomic here: single event loop, no await in between.
    if lock.locked():
        log.info("sync already in progress for member %s -- rejecting", req.memberId)
        return sync_in_progress()

    async with lock:
        _set_progress(req.memberId, "CHECKING_SESSION")
        log.info("member %s: checking for a reusable session", req.memberId)
        t0 = time.monotonic()
        try:
            reused = await _harvest_from_profile(req.memberId)
        except Exception as exc:  # noqa: BLE001
            # Headless launch/harvest can fail for reasons unrelated to the session itself
            # (e.g. a broken browser environment) -- don't let that take down the whole
            # /sync call, fall back to the fresh (headful) login below just like an
            # absent/dead session would.
            log.warning("member %s: headless session-reuse attempt failed, "
                        "falling back to fresh login: %s", req.memberId, exc)
            reused = None
        if reused is not None:
            log.info("synced %d accounts (reused session) for member %s in %.1fs",
                     len(reused["accounts"]), req.memberId, time.monotonic() - t0)
            return reused
        if not req.allowLogin:
            log.info("member %s: no reusable session (%.1fs), fresh login disabled",
                     req.memberId, time.monotonic() - t0)
            return session_expired()
        log.info("member %s: no reusable session (%.1fs), starting fresh login",
                  req.memberId, time.monotonic() - t0)

        _set_progress(req.memberId, "LOGGING_IN")
        t0 = time.monotonic()
        try:
            async with _open_camoufox(req.memberId, headless=False, recover_profile=True) as ctx:
                page = await ctx.new_page()
                await page.goto(APP_URL, wait_until="domcontentloaded", timeout=45000)
                await page.wait_for_timeout(3000)
                if "passcode" not in page.url:
                    await _fill_phone(page, req.phoneNumber)
                if "passcode" in page.url or await page.get_by_role("textbox").count() >= 6:
                    await _fill_passcode(page, req.passcode)

                log.info("member %s: login form submitted in %.1fs, waiting up to %ss for mobile approval",
                          req.memberId, time.monotonic() - t0, ENROLMENT_APPROVE_WAIT_S)
                t0 = time.monotonic()
                approved = False
                for i in range(ENROLMENT_APPROVE_WAIT_S * 1000 // POLL_MS):
                    _set_progress(req.memberId, "AWAITING_APPROVAL",
                                  elapsedSeconds=i * POLL_MS // 1000,
                                  remainingSeconds=ENROLMENT_APPROVE_WAIT_S - i * POLL_MS // 1000)
                    if await _logged_in(page):
                        approved = True
                        break
                    await page.wait_for_timeout(POLL_MS)
                if not approved:
                    log.warning("member %s: mobile approval timed out after %.1fs",
                                 req.memberId, time.monotonic() - t0)
                    return JSONResponse(status_code=408, content={"error": "APPROVAL_TIMEOUT"})
                log.info("member %s: mobile approval received after %.1fs",
                          req.memberId, time.monotonic() - t0)

                device_id = await _device_id(ctx)
                await _settle(page)
                _set_progress(req.memberId, "HARVESTING")
                t0 = time.monotonic()
                result = await harvest_accounts(
                    page, device_id,
                    on_progress=lambda n: _set_progress(req.memberId, "HARVESTING", accountsFound=n))
                log.info("synced %d accounts (fresh login) for member %s in %.1fs",
                         len(result["accounts"]), req.memberId, time.monotonic() - t0)
                return result
        except BrowserLaunchError as exc:
            log.warning("member %s: fresh-login browser launch failed: %s", req.memberId, exc)
            return browser_launch_failed()


@app.get("/progress/{member_id}")
async def progress(member_id: str):
    return _progress.get(_profile_key(member_id), {"phase": None})


@app.get("/health")
async def health():
    return {"status": "ok"}
