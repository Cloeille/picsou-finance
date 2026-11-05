"""La Banque Postale sidecar: session replay and normalized account snapshot.

Status: **scaffold**. The HTTP contract and its validation are real; the
bank-specific layer (`bank.py`) is a stub that answers 503
`BANK_FLOW_UNVERIFIED` until a live session has been captured. No request has
ever reached La Banque Postale from this service.

The backend-facing contract is deliberately shaped like the Caisse d'Epargne
sidecar's:

  POST /initiate    {customerId}            → {processId, keypad, expiresInSeconds}
  POST /keypad      {processId, positions}  → {processId, status: "OTP_PENDING"}
  POST /otp         {processId, code}       → {sessionState}
  POST /token-check {sessionState}          → {ok, expiresIn}
  POST /accounts    {sessionState}          → {accounts, unsupported}

Safety rules, same as the other bank sidecars:
  * every request but /health needs the shared key (`APP_SIDECAR_API_KEY`);
    the service refuses to start without one;
  * the identifier, the keypad positions, the one-time code, cookies, tokens
    and raw bank payloads are never logged and never returned;
  * no login step is retried: a wrong password or code can lock the account.
"""

from typing import Any

from fastapi import HTTPException, Request
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, StrictInt

import bank

log = configure_logging("lbp-auth", quiet=("httpx", "httpcore"))

# Test seam: tests bind a fake client. The production binding stays unverified.
bank_client: bank.BankClient = bank.UnverifiedBankClient()

LOGIN_PATHS = {"/initiate", "/keypad", "/otp"}


def _validation_detail(request: Request, _) -> str:
    # Never echo the validation errors: they quote the offending input.
    return "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"


app = create_app(
    title="picsou-lbp-auth",
    request_log=(log, "La Banque Postale"),
    validation_detail=_validation_detail,
)


# ─── contract ────────────────────────────────────────────────────────────────


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    customerId: str = Field(min_length=1, max_length=200)


class KeypadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    # The positions the user clicked. The password itself never leaves his browser.
    positions: list[StrictInt] = Field(min_length=1, max_length=200)


class OtpRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    # The one-time code is a bank secret: it is never logged or echoed.
    code: str = Field(min_length=1, max_length=32)


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
        return {"processId": req.processId, "status": "OTP_PENDING"}
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("keypad failed unexpectedly")
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/otp")
async def otp(req: OtpRequest) -> dict[str, Any]:
    try:
        return {"sessionState": await bank_client.otp(req.processId, req.code)}
    except bank.BankFlowUnverified:
        raise _unverified() from None
    except HTTPException:
        raise
    except Exception:  # pragma: no cover
        log.error("otp failed unexpectedly")
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
