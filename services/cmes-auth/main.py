"""Read-only Crédit Mutuel Épargne Salariale authentication and positions sidecar.

The saver's space (www.creditmutuel-ere.fr, also used by CIC savers) has no
public API. Its pages are server-rendered and its login is a plain HTML form
with no captcha, so this service speaks plain HTTPS -- no browser.

Auth flow:
  POST /initiate {login, password, sessionState?}
    → no second factor: {processId: null, mfaRequired: false, sessionState}
    → mobile confirmation: {processId, mfaRequired: true, mfaType: "APP_PUSH"}
    → code by SMS:         {processId, mfaRequired: true, mfaType: "SMS"}
  POST /complete {processId, code?}
    → APP_PUSH: holds open while the user approves in the bank's app
    → SMS: submits the code → {sessionState}
  POST /positions {sessionState}
    → [{externalId, name, planKind, employer, balanceEur, positions[], snapshotComplete}]

Once the second factor is passed, the site sets a remembered-device cookie
(`auth_client_state`) that spares the second factor for a while (90 days on the
banking sites built on the same platform). `/initiate` accepts the previous
session so that cookie is replayed: a stored-credentials re-login after the
session expired then needs no one at the phone.

The mobile-confirmation choreography (`transactionId`, state polling, then the
page form submitted) is the one the platform uses on its banking sites, as
documented by woob's `creditmutuel` module; it is reused here rather than
guessed. Only the session cookies are returned to Java, which encrypts them
before storage. Credentials are held in memory for one request and are never
logged; neither are cookies, field values or financial responses.
"""

import hashlib
import os
import re
from decimal import Decimal
from typing import Annotated, Literal
from urllib.parse import urljoin

import httpx
from euro_information.client import (
    TRUST_COOKIE,
    Site,
    UpstreamError,
    auth_router,
    diagnose,
    fetch_page,
    follow,
    http_errors,
    is_mfa_page,
    path_of,
    pending_store,
    same_origin,
    with_session,
)
from euro_information.forms import find_form
from picsou_sidecar import configure_logging, create_app, otp_field_detail
from pydantic import BaseModel, ConfigDict, Field

from fund_parser import MY_SAVINGS_PATH_MARKER, my_savings_tab, parse_fund_identity, parse_my_savings
from situation_parser import SituationFormatError, money_close, parse_profile, parse_situation

log = configure_logging("cmes-auth", quiet=("httpx",))

BASE_URL = os.environ.get("CMES_BASE_URL", "https://www.creditmutuel-ere.fr").rstrip("/")
LOGIN_PATH = "/fr/identification/authentification.html"
SAVER_SPACE = "/epargne-salariale/fr/epargnants"
SITUATION_PATH = f"{SAVER_SPACE}/mon-epargne/situation-financiere-detaillee/index.html"

SITE = Site(
    name="CMES",
    base_url=BASE_URL,
    login_path=LOGIN_PATH,
    logged_in_marker="deconnexion/deconnexion.html",
    # Notices the site parks a login on until the saver acts on the real website.
    action_needed_path_markers=(
        "premiers-pas/saisir-vos-coordonnees",
        "premiers-pas/vos-services",
        "conditions-generales-d-utilisation",
    ),
    # The cookies of a dead session would only confuse the login: only the
    # remembered device is reused.
    replayed_cookies=frozenset({TRUST_COOKIE}),
    unreadable_poll_expires=True,
)

_pending = pending_store(log)
app = create_app(
    request_log=(log, "CMES"),
    background=_pending,
    validation_detail=otp_field_detail("INVALID_OTP"),
)

app.include_router(auth_router(SITE, _pending))


# ─── Contract ───────────────────────────────────────────────────────────────


class PositionsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=2_000_000)


class PositionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    isin: str | None = Field(default=None, max_length=12)
    fundCode: str | None = Field(default=None, max_length=20)
    label: str = Field(min_length=1, max_length=200)
    quantity: Decimal
    unitValue: Decimal | None = None
    valueEur: Decimal
    pnlEur: Decimal | None = None


class PlanPayload(BaseModel):
    model_config = ConfigDict(extra="forbid")

    externalId: str = Field(min_length=1, max_length=100)
    legacyExternalIds: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list)
    name: str = Field(min_length=1, max_length=200)
    planKind: str | None = Field(default=None, max_length=30)
    employer: str | None = Field(default=None, max_length=200)
    balanceEur: Decimal
    positions: list[PositionPayload]
    snapshotComplete: Literal[True]


# ─── Positions ──────────────────────────────────────────────────────────────


def _slug(label: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-")


def plan_external_id(number: str, label: str) -> str:
    """`cmes_<number>_<slug>-<digest>`, from the saver number and the plan's full
    label alone: a plan keeps its id whatever other plans the page lists beside
    it. The slug only helps a human reading the table; the digest of the exact
    label keeps apart two labels that slug alike ("ACME+A" and "ACME A")."""
    digest = hashlib.sha256(label.encode()).hexdigest()[:16]
    return f"cmes_{number}_{_slug(label)[:40].rstrip('-')}-{digest}"


def legacy_external_ids(number: str, plan: dict) -> list[str]:
    """Every id earlier builds could have given this plan, so the backend can
    move an account still carrying one to its new id with its history.

    Those ids were `cmes_<number>_<KIND>`, with the name added when another plan
    listed at the time shared the kind. Which one a plan got hung on plans that
    may have opened or closed since, so both are named, and the id alone proves
    nothing: another plan of the family may have held `cmes_<number>_<KIND>`.
    The backend moves an account only if it also carries this plan's name.
    """
    name, kind = _slug(plan["name"]), plan["planKind"]
    slugs = [kind, f"{kind}-{name}"] if kind else [name]
    return [f"cmes_{number}_{slug}"[:100] for slug in slugs]


async def _situation(client: httpx.AsyncClient) -> str:
    return await fetch_page(SITE, client, SITUATION_PATH, "Situation")


async def _collect_plans(client: httpx.AsyncClient) -> list[PlanPayload]:
    page = await _situation(client)
    employer, number = parse_profile(page)
    plans = parse_situation(page)
    external_ids = [plan_external_id(number, plan["name"]) for plan in plans]
    if len(set(external_ids)) != len(external_ids):
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "Two plans share an external id")
    payloads = []
    for plan, external_id in zip(plans, external_ids):
        positions = [await fetch_fund(client, fund) for fund in plan["funds"]]
        payloads.append({
            "externalId": external_id,
            "legacyExternalIds": legacy_external_ids(number, plan),
            "name": plan["name"],
            "planKind": plan["planKind"],
            "employer": employer,
            "balanceEur": plan["balanceEur"],
            "positions": positions,
            "snapshotComplete": True,
        })
    return [PlanPayload.model_validate(payload) for payload in payloads]


def _require_session(response: httpx.Response) -> httpx.Response:
    """A page that lands on the login or strong-authentication page means the
    session ran out mid-read: say so, so the backend logs in again."""
    if response.status_code in (401, 403) or LOGIN_PATH in path_of(response.url) or is_mfa_page(response):
        raise UpstreamError("SESSION_EXPIRED", "Session expired")
    return response


async def fetch_fund(client: httpx.AsyncClient, fund: dict) -> dict:
    """Read one fund's units, unit value and code from its own pages.

    The fund button posts the situation page's form, which lands on the fund's
    "Synthèse" tab; its "Mon épargne" tab holds the units. Every fund starts
    from a fresh situation page: the form carries the page state, and one
    posted from a page the server has since moved away from is refused.
    """
    page = await _situation(client)
    form = find_form(page, button=fund["button"])
    if form is None:
        raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No form holds the fund button")
    # The pressed button is replayed with the value it was served with.
    fields = {**form.fields, fund["button"]: form.buttons[fund["button"]]}
    target = same_origin(SITE, urljoin(BASE_URL + SITUATION_PATH, form.action or SITUATION_PATH))
    summary = _require_session(
        await follow(SITE, client, await client.post(target, data=fields, headers={"Origin": BASE_URL}))
    )
    isin, fund_code = parse_fund_identity(summary.text)

    savings = summary
    if MY_SAVINGS_PATH_MARKER not in path_of(summary.url):
        tab = my_savings_tab(summary.text)
        if tab is None:
            diagnose(SITE, "fund summary", summary)
            raise UpstreamError("UPSTREAM_FORMAT_CHANGED", "No 'Mon épargne' tab on the fund page")
        savings = _require_session(
            await follow(SITE, client, await client.get(same_origin(SITE, urljoin(str(summary.url), tab))))
        )
    details = parse_my_savings(savings.text)
    if not money_close(details["valueEur"], fund["valueEur"]):
        # The fund page belongs to another fund, or the two pages were read on
        # either side of a valuation: either way the snapshot is not coherent.
        raise UpstreamError("PORTFOLIO_INCOMPLETE", "Fund page and situation page disagree")
    return {
        "isin": isin,
        "fundCode": fund_code,
        "label": fund["label"],
        "quantity": details["quantity"],
        "unitValue": details["unitValue"],
        "valueEur": fund["valueEur"],
        "pnlEur": details["pnlEur"],
    }


# ─── Routes ─────────────────────────────────────────────────────────────────


@app.post("/positions", response_model=list[PlanPayload])
async def positions(req: PositionsRequest) -> list[PlanPayload]:
    with http_errors(SITE, "positions", SituationFormatError, validated=True):
        return await with_session(SITE, req.sessionState, _collect_plans)
