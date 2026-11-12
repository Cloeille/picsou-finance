"""BNP Paribas / Hello bank! sidecar: browser login, then a normalized read-only snapshot.

Status: the login page (hosts, form, keypad, error redirect) was checked on the public pages with a
real browser, nothing typed; one owner-assisted run covered the login without a second factor, the
session and the current and savings accounts. The second factor, Hello bank! and the securities endpoints
are **UNVERIFIED** (docs/features/bnp-paribas.md, "Verification boundary"). The sidecar drives the real page and clicks the keypad buttons; it never builds the login POST.

  POST /initiate  {customerId, brand}      -> {processId, keypad: {keys[10], columns}, expiresInSeconds}
  POST /keypad    {processId, positions}   -> {processId, status: APPROVAL_PENDING | CODE_REQUIRED}
  POST /complete  {processId, code?}       -> {sessionState}
  POST /accounts  {sessionState}           -> {brand, accounts, unsupported, sessionState}

Rules, as for the other sidecars: every route but /health needs the shared key; the customer
number, keypad positions, SMS code, cookies and bank payloads are never logged or returned
(except the cookie jar in `sessionState`, which the backend encrypts); no login step is retried.
"""

from typing import Annotated, Any

from fastapi import HTTPException, Request
from picsou_sidecar import configure_logging, create_app
from pydantic import BaseModel, ConfigDict, Field, StrictInt

import bank

log = configure_logging("bnp-auth", quiet=("httpx", "httpcore"))

# Test seam: tests bind a fake client. Production drives Camoufox.
bank_client = bank.BrowserBankClient()

LOGIN_PATHS = {"/initiate", "/keypad", "/complete"}


def _validation_detail(request: Request, exc) -> str:
    fields = {str(error["loc"][-1]) for error in exc.errors() if error.get("loc")}
    if request.url.path == "/complete" and "code" in fields:
        return "INVALID_OTP"
    # Never echo the validation errors: they quote the offending input.
    return "INVALID_REQUEST" if request.url.path in LOGIN_PATHS else "INVALID_SESSION_STATE"


app = create_app(
    title="picsou-bnp-auth",
    request_log=(log, "BNP Paribas"),
    background=bank_client.pending,
    validation_detail=_validation_detail,
)


class InitiateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    customerId: str = Field(pattern=r"^[0-9]{7,10}$")
    brand: str = Field(pattern=r"^(bnp|hellobank)$")


class KeypadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    positions: list[Annotated[StrictInt, Field(ge=0, le=9)]] = Field(min_length=6, max_length=6)


class CompleteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    processId: str = Field(min_length=1, max_length=200)
    code: str | None = Field(default=None, pattern=r"^[0-9]{4,10}$")


class AccountsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", repr=False)

    sessionState: str = Field(min_length=2, max_length=200_000)


async def _guarded(step: str, call) -> Any:
    try:
        return await call()
    except HTTPException:
        raise
    except Exception:
        # No exception text: it may quote the page, a URL or an input.
        log.error("%s failed unexpectedly", step)
        raise HTTPException(status_code=500, detail="INTERNAL_ERROR") from None


@app.post("/initiate")
async def initiate(req: InitiateRequest) -> dict[str, Any]:
    return await _guarded("initiate", lambda: bank_client.initiate(req.customerId, req.brand))


@app.post("/keypad")
async def keypad(req: KeypadRequest) -> dict[str, Any]:
    status = await _guarded("keypad", lambda: bank_client.keypad(req.processId, req.positions))
    # The positions are never echoed back, here or in an error.
    return {"processId": req.processId, "status": status}


@app.post("/complete")
async def complete(req: CompleteRequest) -> dict[str, Any]:
    return {"sessionState": await _guarded("complete", lambda: bank_client.complete(req.processId, req.code))}


@app.post("/accounts")
async def accounts(req: AccountsRequest) -> dict[str, Any]:
    return await _guarded("accounts", lambda: bank_client.accounts(req.sessionState))
