# BNP Paribas / Hello bank! (sidecar)

> Status: **implemented, partly verified against one real account** (see [Verification boundary](#verification-boundary)).
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
3. Second factor, when the bank asks for one (both paths unverified, see below; the live run needed none and
   went straight to the session):
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
| `BnpController` | `/api/bnp/{auth/initiate,auth/keypad,auth/complete,sync,status,session}`, per-IP rate limits (3 logins / 15 min, 5 keypad steps / 15 min, 10 SMS codes / 15 min; each in its own bucket, so a spent keypad budget never refuses a code the bank already sent). |
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
4. **Cookie-consent vendor** (OneTrust, three exact hosts): aborted silently, with or without a body; the
   login goes on. With a new browser profile the bank's pages call `geolocation.onetrust.com` (GET),
   `cookies-data.onetrust.io` (POST) and `bnp-privacy.my.onetrust.com` (POST) on every landing, the
   signed-in one included. Without this rule the two POSTs fell under rule 5 and stopped every login
   right after the password was accepted. The hosts are exact because `my.onetrust.com` hosts every
   OneTrust customer: another tenant, any other OneTrust host and a main-frame navigation to one of the
   three still stop the attempt. The Hello bank! tenant is not known, so its consent calls still stop
   that brand's login until it is seen.
5. **Any other sub-frame or any request with a body or a write verb** to a third-party host (it could
   carry the customer number or the code): aborted and stops the attempt.
6. **Any other third-party sub-resource without a body** (for example the `assets.adobedtm.com` analytics
   script, pixels, fonts): aborted silently; the login goes on. Its host name is logged once at INFO.

Rules 3 and 4 read one table, `VENDORS` in `services/bnp-auth/bank.py`: a host, or a suffix when the key
starts with a dot, -> what its sub-resources get (`allow` or `ignore`). A new vendor is one line there.

The WebRTC beacon of ThreatMetrix stays off (`block_webrtc`), because it would go around this policy.
Whether the bank accepts a login without it is unverified.

Every stop logs a fixed stage name next to the stable code, for example
`BNP attempt stopped: UPSTREAM_FORMAT_CHANGED (stage=page_load)`. Stages: `page_load`, `login_page`,
`login_form`, `customer_number`, `keypad_read`, `keypad_press`, `submit`, `after_submit`,
`after_submit_redirect`, `after_submit_timeout`, `login_error`, `second_factor`, `second_factor_wait`,
`password_expired`, `sms_code`, `approval_wait`, `session_cookies`, `snapshot_load`, `snapshot_fetch`, `snapshot_parse`,
`snapshot`, and for a session found dead `snapshot_off_site`, `snapshot_info_dead`, `snapshot_http_401`. A stage is a constant of the code, never page content, so it is safe to share.

### Session checks (`SESSION_EXPIRED`)

The first snapshot is read **in the browser that logged in**, at the end of `/complete`, and kept in memory
(keyed by a hash of the session state, 120 s, one `/accounts` call) for the `/accounts` call that follows.
A later `/accounts` opens a new browser, restores the cookies and goes to the host the login ended on (recorded
in the session state as `landing`, one of the bank's own hosts; `brand.base` only for a state saved without it),
because the private banking space is on another `.bnpparibas` host and host-only cookies do not travel across
hosts. The session state keeps every cookie of the bank's domains (brand hosts, their sub-domains, the
`.bnpparibas` hosts; never the bare TLD).

A dead session has three distinct stages, each with ONE structure-only line
`BNP session check stopped (stage=... phase=same_browser|fresh_browser) landing=<host> host=... path=<first two segments> query=[keys]
kind=... cookies=N sent_to_landing=N jar=[NAME@domain[flags],...]` (`*` before the domain for a domain cookie,
flags `S` secure, `H` httpOnly, `T` session cookie; names and domains only, never a value or an expiry):
`snapshot_off_site` (the page is not on the bank's site), `snapshot_info_dead` (`InfosClient` answers `codeRetour`
-10 / -30 or answers 0 without a customer block, or a data answer says -10 / -30), `snapshot_http_401` (the bank
refused a request with 401 / 403). If the first snapshot
fails or runs out of time the login still completes and the stage is logged.

`/complete` has one time budget of 190 s for the second-factor wait and the first snapshot together, because
the backend stops waiting at 200 s (`BnpAdapter.COMPLETE_TIMEOUT`) and the web app at 210 s. The snapshot gets
45 s, or what is left of the 190 s when that is less: after an app approval that came late it is cut short or
skipped, and the `/accounts` call that follows reads it in a new browser.

Signed in means `codeRetour` 0 **and** a `data` block. The code alone is not enough: once the
session has idled out, the signed-in site still answers `InfosClient` with `codeRetour: "0"` and `message: "OK"`,
with `data: null`, and its data endpoints answer `codeRetour: 99`. Reading the code alone turned an expired
session into `UPSTREAM_UNAVAILABLE` (stage `snapshot_parse`), which tells the member the bank is down instead of
asking for a new login.

Lifetime seen live (one account, 2026-11-09): the session still answered after 12 idle minutes and was over
after 24. Opening `/auth/login` with the stored cookies then showed the login form, so the PingFederate session
was over too: only a new login renews a dead session. A sync that runs more than a quarter of an hour after the
last one therefore needs the member again.

### After the login submit

The bank answers the single form post with a redirect chain: PingFederate `.../authorization.ping`, an OIDC
callback carrying `code` and `state`, then the site (the open-source woob `bnp` module checks the same chain
one redirect at a time, `modules/bnp/pp/browser.py`, `check_redirections`). `classify_url` tells each page
from its URL alone: `error` (`error_code` or `errorCode` query key), `second_factor` (`authentification-forte`,
`authentForte`), `password` (`mot-de-passe-expire`, `changer-son-mot-de-passe`, `100-connexion`, stage
`password_expired`, `ACTION_REQUIRED`), `login`, `callback` (a hop in flight: never a verdict, never a second
factor), `site`, `other`. A page without a session that stays put is read by its shape (a code field = SMS, the
Cle Digitale wording and no code field = app approval); anything else stops with `MFA_TYPE_UNSUPPORTED`.

Seen live (owner-assisted run, 2026-11-09, on `mabanqueprivee.bnpparibas`): the post to
`/as/<id>/resume/as/authorization.ping` answers 302 to `mabanque.bnpparibas/auth/on-login-ciam` (`code`, `state`),
which sends the customer to `mabanqueprivee.bnpparibas/auth/login`. That host runs its own authorization round
trip on `connexion.mabanque.bnpparibas` without showing a page, goes through `/aiguillage-wspl/redirect/login`
and lands on `/fr/secure/comptes-et-contrats`, about two seconds after the submit. No second factor was asked.
A browser that still holds the PingFederate session goes through the same chain from `/auth/login` without
showing the form.

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
- Operations come from `releveOp` (`listerOperations.compte.operationPassee`). `dateOperation` is written day
  first with dashes (`31-01-2026`); the slash and ISO forms are still read. One operation that cannot be read
  makes the whole history unusable and the account incomplete, so a wrong date format fails the sync instead
  of importing wrong dates.
- The IBAN is joined from `rib-wspl/rpc/comptes` on `ibanCrypte` = the account's `key` and kept only when it is
  a well-formed IBAN. In the live run the `iban` field held 21 digits, not an IBAN, and the list did not
  cover every account: the IBAN then stays empty and the account id falls back to the masked number.
- An account is matched by its external id, then by IBAN across the provider family (BNP Paribas and
  Hello bank! share one platform, and an existing Enable Banking link of the same bank is adopted), so it
  is not duplicated.
- An incomplete snapshot (`snapshotComplete: false`) fails the whole sync and keeps the previous data:
  nothing is imported and nothing is closed.
- On a complete snapshot, an imported account (`bnp_` external id, same provider: BNP Paribas and Hello
  bank! never close each other's) that the bank no longer lists is closed at zero: balance and cash set to
  0, holdings deleted, a zero balance snapshot written. It is not soft-deleted, which would read as removed
  by the member and block an account that reopens. A manual account, an Enable Banking link without the
  `bnp_` id, an account already at zero, and a product listed as unsupported (still held, only not read)
  are left as they were; so is everything when the unsupported list hit its 100-entry cap, as it may then
  hide a product. A closed loan is dropped by the sidecar (the bank sends only nulls), so it is closed at
  zero too.
- Two known limits of these rules:
  - The account id comes from the IBAN when the RIB list gives one, and from the masked number otherwise.
    An account whose IBAN appears or disappears between two syncs therefore gets a new id: it is imported
    as a new account and the old one is closed at zero. Net worth stays right, the history is split. A
    field that stays the same across sessions has not been verified yet.
  - Closing is per provider, so reconnecting under the other brand leaves the first brand's accounts at
    their last balance. Delete them by hand.

## Operating it

Compose: profile `bnp` (or `all-connectors`), service `bnp-auth` on the `sidecar-camoufox` base; Helm:
`bnp-auth` in `charts/picsou/values.yaml` (`BNP_AUTH_URL`).

## Verification boundary

One owner-assisted run on 2026-11-09 exercised the connector against a real BNP Paribas account, on
`mabanqueprivee.bnpparibas`, for product families 1 and 2. The sidecar ran outside Docker (macOS, the
pinned Camoufox release, not a datacenter network). Everything else is either checked on the **public**
pages with a real browser (nothing typed), or **unverified**.

**Verified in the owner-assisted run:**

- The whole login as the sidecar drives it: customer number, the ten key labels, six clicks by position,
  one form post, the redirect chain described in "After the login submit", the session.
- `InfosClient` answers `codeRetour: "0"` (a string) once signed in; `getlstcpt`, `rib-wspl/rpc/comptes` and
  `rop2-wspl/rest/releveOp` answer on the landing host with the page's own cookies and no extra header.
- Families 1 (current account) and 2 (savings, kinds told apart by label), balances and operations, once
  the dash date format was read.
- `/accounts` in a new browser with the restored cookies, on the landing host, three times within four
  minutes of the login, then after 6 and 12 idle minutes.
- The end of the session: over after 24 idle minutes, reported as `SESSION_EXPIRED` (stage `snapshot_info_dead`),
  and not renewed by the stored cookies (see "Session checks").
- The OneTrust calls of rule 4 (also seen on the public page of the private-banking site).
- Camoufox reached the login and the signed-in site from that network.

**Verified on the public login pages** (live recon, 2026-11-09, `recon-live` notes in the PR):

- The login runs on a PingFederate OIDC page: `connexion.mabanque.bnpparibas` (BNP Paribas) and
  `connexion.hellobank.fr` (Hello bank!), path `/as/authorization.oauth2`. The site hosts are
  `mabanque.bnpparibas` and `www.hellobank.fr`; ThreatMetrix first-party hosts are
  `content.connexion.<domain>`. These six hosts are the exact allow-list; the `.bnpparibas` dotBrand TLD and the
  vendor table are the suffix rules (see "Host policy").
- The form is `form#identForm` with `pf.username`, `pf.pass.positions` and `pf.idTMX`. The keypad is ten
  HTML `<button>` elements in a 5x2 grid, shuffled per page load; the value submitted for a key is its
  0-based DOM position. The login is one POST; a refusal comes back as a redirect carrying `error_code`.
- Akamai Bot Manager, ThreatMetrix and a nonce-based CSP are present. For that reason the sidecar drives
  the real page and clicks the keys; it never builds or posts the login request itself.
- `InfosClient` answers `codeRetour: "-10"` (a string) when no session is signed in.

**Unverified** - shipped on the open-source woob `bnp` module and on the public recon only:

- **The second factor.** The run above was not asked for one. Both a Cle Digitale approval (app push,
  `APPROVAL_PENDING`) and an SMS code (`CODE_REQUIRED`) are implemented, because it is not known which one a
  given customer gets, nor which page and host it lives on: a navigation to a host outside the allow-list
  stops the login (`UPSTREAM_FORMAT_CHANGED`, the host name and the stage are logged, never the URL) rather
  than being followed. A second factor the sidecar cannot classify stops with `MFA_TYPE_UNSUPPORTED`.
- **Hello bank!** and a retail customer who stays on `mabanque.bnpparibas`: no such account was used.
- Securities accounts and their positions (`pe-war` endpoints), life insurance, PER, loans, the other
  account families and product labels (`parsers.py`), a real IBAN in `rib-wspl`.
- The exact idle limit (between 12 and 24 minutes) and whether a session has an absolute limit, `codeRetour`
  `-30` as a second "session expired" value, the lock-out counter and the behaviour of
  `ClientNotFoundException201` / `TechnicalException917`.
- The sidecar's Docker image (Linux Camoufox under Xvfb) against the bank, and whether Camoufox gets past
  Akamai from a datacenter network. From the build VPS the BNP and Hello bank! hosts answered 403 to headless
  Chromium; nothing was done to evade that.

Fail-closed rules cover the gap: an unknown account family or kind is listed as unsupported and never
guessed; an incomplete account is marked `snapshotComplete: false` and never replaces what was imported;
an unexpected page stops the attempt with a stable code. Do not describe this connector as fully tested
until the points above have been confirmed.
