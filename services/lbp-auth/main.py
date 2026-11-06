"""La Banque Postale sidecar: session replay and normalized account snapshot.

Status: **browser-assisted login implemented and live-validated by the author;
the maintainer's live run is still pending.** The login runs in headless Chromium
so the bank's anti-fraud scripts set the `iscdName` device fingerprint naturally;
the keypad is clicked in that browser, and the digits are never typed anywhere. The
session cookies are then replayed with `httpx` for the server-rendered pages.

Sign-in is identifier → shuffled virtual keypad → **approval in the bank's
mobile app** (Certicode is no longer the flow for a modern account). The UI
shows the ten digit labels and sends back the clicked **positions**; the bank's
own script stores those positions, not the digits, in the password field. There
is no code to type, so `/complete` waits for the human.

The backend-facing contract is deliberately shaped like the Caisse d'Epargne
sidecar's:

  POST /initiate    {customerId}            → {processId, keypad, expiresInSeconds}
  POST /keypad      {processId, positions}  → {processId, status: "APPROVAL_PENDING"}
  POST /complete    {processId}             → {sessionState}
  POST /token-check {sessionState}          → {ok, expiresIn}
  POST /accounts    {sessionState}          → {accounts, unsupported}

Safety rules, same as the other bank sidecars:
  * every request but /health needs the shared key (`APP_SIDECAR_API_KEY`);
    the service refuses to start without one;
  * the identifier, the keypad positions, cookies, tokens and raw bank payloads
    are never logged and never returned;
  * no login step is retried: a wrong password can lock the account.
"""

from typing import Annotated, Any

from fastapi import HTTPException, Request
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, StrictInt

import bank

log = configure_logging("lbp-auth", quiet=("httpx", "httpcore"))

# Test seam: tests bind a fake client. The production login drives Chromium.
bank_client: bank.BankClient = bank.BrowserBankClient()

LOGIN_PATHS = {"/initiate", "/keypad", "/complete"}


def _validation_detail(request: Request, _) -> str:
    # Never echo the validation errors: they quote the offending input.
    return "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"


app = create_app(
    title="picsou-lbp-auth",
    request_log=(log, "La Banque Postale"),
    background=getattr(bank_client, "pending", None),
    validation_detail=_validation_detail,
)


# ─── contract ────────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    customerId: str = Field(min_length=1, max_length=200)


class KeypadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    # The six DOM positions the user clicked (0-9, one per keypad key). The password
    # itself never leaves the browser. Rejected here, before any browser action.
    positions: list[Annotated[StrictInt, Field(ge=0, le=9)]] = Field(
        min_length=6, max_length=6
    )


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    processId: str = Field(min_length=1, max_length=200)


class TokenCheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=200_000)


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    sessionState: str = Field(min_length=2, max_length=200_000)


class TokenCheckResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ok: bool
    expiresIn: int


# ─── routes ──────────────────────────────────────────────────────────────────


def _unverified() -> HTTPException:
    # A distinct code so an operator sees "the bank flow is not implemented",
    # not "the bank is down". The backend maps an unknown 5xx to UPSTREAM_UNAVAILABLE.
    return HTTPException(status_code=503, detail="BANK_FLOW_UNVERIFIED")


@app.post("/initiate")
async def initiate(req: InitiateRequest) -> dict[str, Any]:
    try:
        return await bank_client.initiate(req.customerId)
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover -- the stub never gets here
        log.error("initiate failed unexpectedly")  # no exception text: it may quote input
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/keypad")
async def keypad(req: KeypadRequest) -> dict[str, Any]:
    try:
        await bank_client.keypad(req.processId, req.positions)
        # The positions are never echoed back, here or in an error.
        return {"processId": req.processId, "status": "APPROVAL_PENDING"}
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("keypad failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/complete")
async def complete(req: CompleteRequest) -> dict[str, Any]:
    try:
        return {"sessionState": await bank_client.complete(req.processId)}
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("complete failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/token-check", response_model=TokenCheckResponse)
async def token_check(req: TokenCheckRequest) -> dict[str, Any]:
    try:
        expires_in = await bank_client.check(req.sessionState)
        return {"ok": True, "expiresIn": int(expires_in)}
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("token-check failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/accounts")
async def accounts(req: AccountsRequest) -> dict[str, Any]:
    try:
        return await bank_client.accounts(req.sessionState)
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("accounts failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None
