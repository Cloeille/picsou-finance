# BNP Paribas / Hello bank! (sidecar)

> Status: **implemented, not verified against a real account** (see [Verification boundary](#verification-boundary)).
> Issue #306. Design record: [ADR](../decisions/2026-11-09-bnp-paribas-sidecar.md). Sidecar: `services/bnp-auth`.

## What it does

A member connects BNP Paribas or Hello bank! (one platform, two brands) from the **Sync** page or the
**Add account** modal. Picsou then imports, as far as the bank's site exposes them: current accounts,
savings (Livret A, LDDS, LEP, Livret Jeune, PEL, CEL and other savings), securities accounts (PEA,
compte-titres) with their positions, life insurance (**balance only**, no positions), PER and loans.
Products Picsou does not recognise are listed under "Products not imported" by id and family code and are
never guessed.

## Sign-in

1. The member picks the brand and types the customer number (7 to 10 digits).
2. Picsou shows the bank's shuffled keypad (ten keys, the digit written on each) and the member clicks the
   six digits of the password. Only the **positions** (0..9) go back to Picsou, never the digits.
3. Second factor, whichever the bank asks for (both unverified, see below):
   - **Cle Digitale approval** in the bank's mobile app: Picsou waits (`/complete` without a code, up to
     three minutes) and imports as soon as the member approves;
   - **SMS code**: the member types it in Picsou, it is sent once to the bank and dropped.
4. Only the resulting cookie jar is kept, encrypted (`CryptoEncryption`), in the shared `sidecar_session`
   table (bank `BNP`, `brand` carried in the jar). No password, key position, SMS code or customer number is
   stored. A session that expires is renewed by a new login; there is no unattended re-login.

One attempt only: a refused password is never retried, because a wrong attempt counts against the bank's
lock-out limit.

## Architecture

| Piece | Role |
|---|---|
| `services/bnp-auth` (Camoufox base) | Drives the bank's login page, clicks the keys, reads the data with the page's own session. FastAPI: `/initiate`, `/keypad`, `/complete`, `/accounts`, `/health`. |
| `BnpAdapter` | Typed client of the sidecar; maps sidecar HTTP errors to `SidecarErrorCode`. |
| `BnpSyncService` | `SidecarSyncTemplate` job: queued -> running -> success/failed, startup recovery, IBAN adoption, balance and transaction import, fail-closed rules. |
| `BnpController` | `/api/bnp/{auth/initiate,auth/keypad,auth/complete,sync,status,session}`, per-IP rate limits (3 logins / 15 min, 5 keypad or code steps / 15 min). |
| Frontend | `BnpPanel` (Sync tab and Add account), keypad pop-up with ten key buttons, approval/code panel, `BnpTab`. |
| Migration `V150` | Adds `BNP` and its error codes to the two CHECK constraints of `sidecar_session`, rebuilt from V149 (Banque Populaire and Trade Republic kept). |

Security properties: strict host allow-list checked on every request and on every redirect hop (exact
hosts, https only, no credentials in the URL, no suffix rule); analytics hosts (`assets.adobedtm.com`,
`aa.online-metrix.net`) are refused quietly; any other host stops the login. The cookie jar only keeps the
brand's own cookies. Sidecar error details never reach the UI: the frontend maps stable codes to
translated messages.

## Product mapping and import rules

- Family 1 -> current account, 2 -> savings (Livret A / LDDS / LEP / Livret Jeune / CEL / PEL by label),
  4 -> compte-titres (PEA by label), 5 and 6 -> life insurance (PER by label), 8 and 9 -> loan. Anything
  else is `unsupported`.
- Life insurance imports its balance only; positions are not read.
- An account is matched by its external id, then by IBAN across the provider family (BNP Paribas and
  Hello bank! share one platform, and an existing Enable Banking link of the same bank is adopted), so it
  is not duplicated.
- An incomplete snapshot (`snapshotComplete: false`) keeps the previous data; a vanished account is not
  deleted by one partial answer.

## Operating it

Compose: profile `bnp` (or `all-connectors`), service `bnp-auth` on the `sidecar-camoufox` base; Helm:
`bnp-auth` in `charts/picsou/values.yaml` (`BNP_AUTH_URL`).

## Verification boundary

No real BNP Paribas or Hello bank! account was used to build or test this connector. Everything below is
either checked on the **public** pages with a real browser (nothing typed), or **unverified**.

**Verified on the public login pages** (live recon, 2026-11-09, `recon-live` notes in the PR):

- The login runs on a PingFederate OIDC page: `connexion.mabanque.bnpparibas` (BNP Paribas) and
  `connexion.hellobank.fr` (Hello bank!), path `/as/authorization.oauth2`. The site hosts are
  `mabanque.bnpparibas` and `www.hellobank.fr`; ThreatMetrix first-party hosts are
  `content.connexion.<domain>`. These six hosts are the whole allow-list.
- The form is `form#identForm` with `pf.username`, `pf.pass.positions` and `pf.idTMX`. The keypad is ten
  HTML `<button>` elements in a 5x2 grid, shuffled per page load; the value submitted for a key is its
  0-based DOM position. The login is one POST; a refusal comes back as a redirect carrying `error_code`.
- Akamai Bot Manager, ThreatMetrix and a nonce-based CSP are present. For that reason the sidecar drives
  the real page and clicks the keys; it never builds or posts the login request itself.
- `InfosClient` answers `codeRetour: "-10"` (a string) when no session is signed in.

**Unverified (needs a signed-in session)** - shipped on the open-source woob `bnp` module and on the
public recon only, to be confirmed by the first real login:

- **The second factor.** Both a Cle Digitale approval (app push, `APPROVAL_PENDING`) and an SMS code
  (`CODE_REQUIRED`) are implemented, because it is not known which one a given customer gets. Which page
  and which host the second factor lives on is unknown: any host outside the allow-list stops the login
  (`UPSTREAM_FORMAT_CHANGED`, the host name is logged, never the URL) rather than being followed. A second
  factor the sidecar cannot classify stops with `MFA_TYPE_UNSUPPORTED`.
- Every post-login JSON endpoint, verb, field name and the account-family / product-label mapping
  (`parsers.py`), the session cookies and lifetime, `codeRetour` `-30` as a second "session expired" value,
  the lock-out counter and the behaviour of `ClientNotFoundException201` / `TechnicalException917`.
- Whether Camoufox gets past Akamai from a given network. From the build VPS the BNP and Hello bank! hosts
  answered 403 to headless Chromium; nothing was done to evade that.
- Real transaction, position and IBAN payloads: the tests use synthetic fixtures only.

Fail-closed rules cover the gap: an unknown account family or kind is listed as unsupported and never
guessed; an incomplete account is marked `snapshotComplete: false` and never replaces what was imported;
an unexpected page stops the attempt with a stable code. Do not describe this connector as tested against
a real account until an owner-assisted live run has confirmed the points above.
