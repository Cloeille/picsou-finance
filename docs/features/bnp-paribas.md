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

Security properties: strict host policy checked on every request and on every redirect hop (see
"Host policy" below); the cookie jar only keeps the brand's own cookies. Sidecar error details never reach
the UI: the frontend maps stable codes to translated messages.

### Host policy

The bank's login page loads ThreatMetrix (LexisNexis) fraud profiling from `h<n>.online-metrix.net` and from
random-label hosts under `aa.online-metrix.net`, and Akamai Bot Manager from `/akam/...` on the bank's own
hosts. The page fills `pf.idTMX` from that profiling, so the sidecar lets those requests load: blocking
them would leave the profile empty and the bank would likely refuse or escalate the login (a blanket "any
off-list host is a site change" rule would stop every login on these tags). The policy is decided per request (`verdict` in `services/bnp-auth/bank.py`), in this order:

1. **Brand hosts** (https, port 443, no credentials): the exact hosts `mabanque.bnpparibas`,
   `connexion.mabanque.bnpparibas`, `content.connexion.mabanque.bnpparibas` (or the three Hello bank! ones),
   **and every host under the `.bnpparibas` TLD** (dot boundary, valid DNS labels; the bare TLD, a trailing dot,
   another port and `mabanque.bnpparibas.evil.example` do not match). `.bnpparibas` is a dotBrand gTLD managed
   by BNP Paribas itself (IANA root zone database), so nobody else can register a name under it; the post-login
   chain goes through such hosts (the private banking site `mabanqueprivee.bnpparibas`). Allowed for
   navigations, sub-resources and requests with a body; the first use of each extra host is logged once
   (host name only). Akamai `/akam` scripts and pixels live on these hosts too. `hellobank.fr` stays on its three
   exact hosts.
2. **Main-frame navigation** to anything else, including every redirect hop and the login form post:
   stops the attempt (`UPSTREAM_FORMAT_CHANGED`, the host name is logged, never the URL). Fail closed.
3. **Known vendor sub-resources** (ThreatMetrix: any strict sub-domain of `online-metrix.net`, matched on a
   dot boundary, https, port 443, no credentials; `evil-online-metrix.net`, `online-metrix.net.evil.example`
   and the bare apex do not match): allowed, for sub-resources and sub-frames of the bank's page only. A
   vendor host can never receive the main-frame navigation, so it never sees the form post.
4. **Any other sub-frame or any request with a body or a write verb** to a third-party host (it could
   carry the customer number or the code): aborted and stops the attempt.
5. **Any other third-party sub-resource without a body** (for example the `assets.adobedtm.com` analytics
   script, pixels, fonts): aborted silently; the login goes on. Its host name is logged once at INFO.

The WebRTC beacon of ThreatMetrix stays off (`block_webrtc`), because it would go around this policy.
Whether the bank accepts a login without it is unverified.

Every stop logs a fixed stage name next to the stable code, for example
`BNP attempt stopped: UPSTREAM_FORMAT_CHANGED (stage=page_load)`. Stages: `page_load`, `login_page`,
`login_form`, `customer_number`, `keypad_read`, `keypad_press`, `submit`, `after_submit`,
`after_submit_redirect`, `after_submit_timeout`, `login_error`, `second_factor`, `second_factor_wait`,
`password_expired`, `sms_code`, `approval_wait`, `session_cookies`, `snapshot_load`, `snapshot_fetch`, `snapshot_parse`,
`snapshot`. A stage is a constant of the code, never page content, so it is safe to share.

### After the login submit

The bank answers the single form post with a redirect chain: PingFederate `.../authorization.ping`, an OIDC
callback carrying `code` and `state`, then the site (the open-source woob `bnp` module checks the same chain
one redirect at a time, `modules/bnp/pp/browser.py`, `check_redirections`). `classify_url` tells each page
from its URL alone: `error` (`error_code` or `errorCode` query key), `second_factor` (`authentification-forte`,
`authentForte`), `password` (`mot-de-passe-expire`, `changer-son-mot-de-passe`, `100-connexion`, stage
`password_expired`, `ACTION_REQUIRED`), `login`, `callback` (a hop in flight: never a verdict, never a second
factor), `site`, `other`. A page without a session that stays put is read by its shape (a code field = SMS, the
Cle Digitale wording and no code field = app approval); anything else stops with `MFA_TYPE_UNSUPPORTED`.

Every stop with a stage after the submit also logs ONE structure-only line,
`BNP page structure (stage=...) host=... path=... query=[keys] kind=... title_len=N ready=... frames=N
forms=[id(field:type,...)] inputs=[type=count] landmarks=[tag#id,...]`: the host and the path with every digit
run replaced by `<N>` and opaque segments by `<ID>`, the query keys only (never a value or the fragment), the
title as a length, form ids and field names and types, a few element ids. Names that look like tokens become
`<X>`. A page that cannot be read logs `page structure unavailable`. It contains no value typed, clicked or shown.

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
  `content.connexion.<domain>`. These six hosts are the exact allow-list; the `.bnpparibas` dotBrand TLD and the
  ThreatMetrix vendor domain are the two suffix rules (see "Host policy").
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
  and which host the second factor lives on is unknown: a navigation to a host outside the allow-list stops the login
  (`UPSTREAM_FORMAT_CHANGED`, the host name and the stage are logged, never the URL) rather than being followed. A second
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
