# lbp-auth — La Banque Postale sidecar

> Status: **cookie-replay client implemented, not yet validated against a live
> bank session by the project.** The login form, the mobile-approval polling and
> the XHTML parsers are covered by offline tests built from two real captures.
> The one unverified risk is the bank's `iscdName` device fingerprint: if the
> server enforces it, only the login layer moves to Camoufox.
>
> Sign-in is identifier → shuffled virtual keypad → **approval in the bank's
> mobile app** (`/complete` polls until the human approves). The UI shows the
> ten digit labels and sends back the clicked **positions**; the bank's own
> script stores those positions, not the digits, in the password field.

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
| `POST /initiate` | `{customerId}` | `{processId, keypad: {keys[10], columns}, expiresInSeconds}` |
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

`externalId` is a SHA-1 of the bank's **displayed account number**, not the
`compte.numero` UUID from the statement links: that UUID changes at every login
and would create duplicate accounts on the next sync. The UUID stays internal to
the sidecar, for the statement request of the current session.

## How the login works

1. `POST /initiate` GETs `/wsost/OstBrokerWeb/loginform`, reads the hidden
   fields and the **ten shuffled digit labels**, and parks the HTTP client in
   the pending store.
2. `POST /keypad` POSTs the identifier and the clicked **positions** (the same
   values the bank's script writes into `password`) with the hidden fields.
3. If the device is trusted, the POST redirects into the normal chain and the
   session is ready. Otherwise the bank shows *Votre appareil de confiance* and
   the sidecar reads `URL_AF_POLLING` from that page.
4. `POST /complete` polls that URL every 3 s until the bank redirects, follows
   the chain and returns the serialized cookie jar.

`POST /accounts` then reads the two synthesis pages
(`afficheSyntheseCCP`, `afficheSyntheseEP`) and, for each account, the
`init-releve_ccp` / `init-releve_cne` operations page.

The `iscdName` field is a 52-character device fingerprint set by the bank's
anti-fraud scripts. The client sends the form's value first. If the server
enforces the fingerprint, replace `HttpxBankClient.initiate`/`keypad` with a
Camoufox login and keep the cookie replay below it; the HTTP contract does not
change.

## Security rules

- Every request but `/health` requires `APP_SIDECAR_API_KEY`, sent as
  `X-Picsou-Sidecar-Key`. The service refuses to start without one.
- The identifier, the keypad positions, cookies, tokens and raw bank payloads
  are **never logged, echoed or stored**.
- No login step is retried: a wrong password can lock the bank account.
- Pending login state lives in memory only. A restart drops it and the user
  starts again.

## What the captures answered

1. **Keypad** — virtual, ten text buttons, **shuffled on every page load**. The
   bank's script writes the DOM position, not the digit, into `password`; the
   server maps positions back to digits. The sidecar therefore never needs the
   password.
2. **Second factor** — mobile approval only when the device is not trusted; a
   trusted device skips it. No SMS path was observed.
3. **Account data** — server-rendered XHTML, no account JSON.
4. **Anti-bot** — the `fruprem.js` / `loreo.js` scripts set `iscdName`; no
   CAPTCHA was observed.
5. **Session lifetime** — not measured; `check` returns a conservative 600 s.
6. **History** — `init-releve_ccp.ea` returns roughly 90 days of operations;
   `telechargementMouvements.ea` is the export path, and the `relevePdf`
   history holds monthly statements.

## Tests

```bash
PYTHONPATH=../base python -m unittest discover -v
```

`test_sidecar_auth.py` binds the shared key contract. `test_main.py` covers
request validation and the normalized passthrough. `test_parsers.py` covers the
XHTML structure; `test_bank.py` drives the login, the mobile-approval polling
and the account import through an `httpx.MockTransport`.
