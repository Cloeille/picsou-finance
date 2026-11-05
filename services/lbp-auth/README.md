# lbp-auth — La Banque Postale sidecar

> Status: **scaffold**. The HTTP contract and its validation are real. The
> bank-specific layer (`bank.py`) is a stub: every call answers
> `503 {"detail": "BANK_FLOW_UNVERIFIED"}` until a live session has been
> captured. No request has ever reached La Banque Postale from this service.
>
> Sign-in is identifier → virtual keypad → **approval in the bank's mobile app**
> (`/complete` waits for the human, like Caisse d'Epargne's Sécur'Pass). There is
> no one-time code in the flow: Certicode is no longer used by a modern account.

## Why this exists

Enable Banking is PSD2-scoped: it reaches La Banque Postale payment accounts but
not the regulated savings products (Livret A, LDDS, LEP, Compte sur Livret,
Livret Jeune, PEL/CEL). The bank's own web client *Comptes & Contrats → Épargne
et placements* shows them. This sidecar is the only surface that can.

The connector replaces Enable Banking for La Banque Postale rather than sitting
beside it, so one source owns every account of the bank. See
[the ADR](../../docs/decisions/2026-11-05-la-banque-postale-sidecar.md) and
[the feature note](../../docs/features/la-banque-postale.md).

## Contract (backend-facing, final)

| Endpoint | Body | Answer |
|---|---|---|
| `POST /initiate` | `{customerId}` | `{processId, keypad: {images[10], columns}, expiresInSeconds}` |
| `POST /keypad` | `{processId, positions}` | `{processId, status: "APPROVAL_PENDING"}` |
| `POST /complete` | `{processId}` | `{sessionState}` |
| `POST /token-check` | `{sessionState}` | `{ok, expiresIn}` |
| `POST /accounts` | `{sessionState}` | `{accounts, unsupported}` |
| `GET /health` | — | `{status: "ok", base?}` |

`/accounts` returns the normalized snapshot:

```json
{
  "accounts": [{
    "externalId": "string",
    "kind": "CURRENT_ACCOUNT | LIVRET_A | LDDS | LEP | CSL | LIVRET_JEUNE | PEL | CEL | CARD",
    "name": "string",
    "balance": "0.00",
    "currency": "EUR",
    "iban": "string|null",
    "authorizedOverdraft": "0.00|null",
    "ceiling": "0.00|null",
    "remainingDepositCapacity": "0.00|null",
    "fillingRatio": "0.00|null",
    "cardNature": "CREDIT | DEFERRED_DEBIT | IMMEDIATE_DEBIT|null",
    "parentExternalId": "string|null",
    "nextDueDate": "YYYY-MM-DD|null",
    "transactions": [{
      "externalId": "string",
      "date": "YYYY-MM-DD",
      "dueDate": "YYYY-MM-DD|null",
      "amount": "-12.34",
      "currency": "EUR",
      "label": "string",
      "typeCode": "string|null"
    }],
    "snapshotComplete": true
  }],
  "unsupported": [{"externalId": "string", "familyCode": "string"}]
}
```

Money is a decimal string, never a float. `snapshotComplete` is `true` only when
the whole account was read: the backend refuses the entire import otherwise.
A product the bank shows but this sidecar does not import goes in `unsupported`
and never becomes an account.

## What the stub does

`bank.py` defines `BankClient` (five async methods) and `UnverifiedBankClient`,
which raises `BankFlowUnverified` for every call. `main.py` maps that to
`503 BANK_FLOW_UNVERIFIED` without contacting anything. The backend maps an
unknown 5xx to its retryable `UPSTREAM_UNAVAILABLE` code.

To make the connector live, implement `BankClient` and bind it to
`main.bank_client` (or replace `UnverifiedBankClient`). Keep the five method
signatures; the routes and the backend do not change.

## Security rules

- Every request but `/health` requires `APP_SIDECAR_API_KEY`, sent as
  `X-Picsou-Sidecar-Key`. The service refuses to start without one.
- The identifier, the keypad positions, cookies, tokens and raw bank payloads
  are **never logged, echoed or stored**.
- No login step is retried: a wrong password or code can lock the bank account.
- Pending login state, when implemented, lives in memory only. A restart drops
  it and the user starts again.

## Recon checklist (blocking)

Capture one real session (with `Cookie`, `Authorization` and tokens redacted)
and answer:

1. Is the password entered on a virtual keypad or in a text field? Are the key
   images shuffled per connection? Is the keypad value computed by JavaScript?
2. Is the sign-in approved in the bank's mobile app on every login, or only on a
   new device? Is there a trusted-device cookie? Does the bank still fall back
   to an SMS code for some accounts (Certicode)?
3. Is account data fetched as JSON XHR or rendered server-side? Which calls
   return the account list, a livret's balance and its transactions?
4. Is there an anti-bot layer (TLS fingerprint, JavaScript challenge, CAPTCHA,
   rate limiting)?
5. How long does a replayed session stay usable?
6. Does the bank expose transaction history for livrets, or only balances?

The answers choose between an `httpx` client (light, cookie replay) and a
Camoufox client (drives the page itself). The contract above does not change.

## Tests

```bash
PYTHONPATH=../base python -m unittest discover -v
```

`test_sidecar_auth.py` binds the shared key contract. `test_main.py` covers
request validation, the stub's typed refusal, the absence of secrets in
responses, and the normalized passthrough with a fake client.
