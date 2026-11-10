# bnp-auth - BNP Paribas / Hello bank! sidecar

> Status: **partly verified against one real account** (owner-assisted run, 2026-11-09: keypad login with
> no second factor asked, session, current and savings accounts, operations). The second factor, Hello bank!,
> securities, life insurance and loans are unverified. See
> [Verification boundary](../../docs/features/bnp-paribas.md#verification-boundary) and the
> [ADR](../../docs/decisions/2026-11-09-bnp-paribas-sidecar.md).

Built on `services/base` (`sidecar-camoufox`, like `amex-auth`). The login runs in Camoufox because of
Akamai, ThreatMetrix and a nonce CSP: the sidecar types the customer number, reads the ten digits on the
keypad buttons, and **clicks the buttons the member picked by DOM position**. It never builds the login
POST. No password, key digit or SMS code is stored; a code is typed into the page and dropped.

## Contract (backend-facing)

| Endpoint | Body | Answer |
|---|---|---|
| `POST /initiate` | `{customerId (7-10 digits), brand: "bnp"|"hellobank"}` | `{processId, keypad: {keys[10], columns: 5}, expiresInSeconds}` |
| `POST /keypad` | `{processId, positions[6]}` (each 0..9) | `{processId, status: "APPROVAL_PENDING"|"CODE_REQUIRED"}` |
| `POST /complete` | `{processId, code?}` (code only for `CODE_REQUIRED`) | `{sessionState}` |
| `POST /accounts` | `{sessionState}` | `{brand, accounts, unsupported, sessionState}` |
| `GET /health` | - | `{status: "ok", base}` |

Errors are `{detail: "<CODE>"}` with the codes of `SidecarErrorCode` (`INVALID_CREDENTIALS`, `INVALID_OTP`,
`KEYPAD_CHANGED`, `MFA_TYPE_UNSUPPORTED`, `APP_VALIDATION_TIMEOUT`, `SESSION_EXPIRED`,
`UPSTREAM_FORMAT_CHANGED`, ...). Upstream text is never forwarded.

## Network rules

Exact hosts, https only, checked on every request and every redirect hop:

| Brand | Hosts |
|---|---|
| `bnp` | `mabanque.bnpparibas`, `connexion.mabanque.bnpparibas`, `content.connexion.mabanque.bnpparibas` |
| `hellobank` | `www.hellobank.fr`, `connexion.hellobank.fr`, `content.connexion.hellobank.fr` |

Every host under the `.bnpparibas` TLD is also a brand host for both brands: it is a dotBrand gTLD only BNP
Paribas can register under (the post-login chain goes through `mabanqueprivee.bnpparibas`). ThreatMetrix
sub-resources (`*.online-metrix.net`) load; the calls of the bank's cookie-consent vendor (three exact
OneTrust hosts, POSTs included) and other passive third-party sub-resources (`assets.adobedtm.com`) are
refused and ignored. Any other host aborts the request and stops the login (`UPSTREAM_FORMAT_CHANGED`); only its
name is logged. A stop after the login submit also logs one structure-only line (see the feature doc). Nothing here tries to defeat bot
management: if the bank answers 403 to the sidecar's network, the sync fails with `UPSTREAM_UNAVAILABLE`.

## Session

`/complete` reads the first snapshot in the browser that logged in and keeps it for the next `/accounts` call
(in memory, 120 s). The second-factor wait and that snapshot share 190 s (`COMPLETE_SECONDS`), under the 200 s
the backend waits. Otherwise `/accounts` opens a new browser and restores the cookies on the host the login ended
on (`landing` in the session state). Signed in means `InfosClient` answers `codeRetour` 0 with a `data`
block: an idled-out session still answers 0, with `data: null`. Seen live, a session lasts between 12 and 24
idle minutes and only a new login renews it. A dead session logs one stage (`snapshot_off_site`, `snapshot_info_dead`,
`snapshot_http_401`) with the page host/path and the cookie names and domains, never a value.

## Files

`main.py` (FastAPI), `bank.py` (browser flow, allow-list, session jar), `parsers.py` (pure JSON parsers,
`ParseError`, `snapshotComplete: false` for incomplete accounts), `test_*.py` (synthetic fixtures only; no
network, no browser).

## Tests

```bash
cd services/bnp-auth
PYTHONPATH=../base:. python -m pytest -q
```
