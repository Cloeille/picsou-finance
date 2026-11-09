# ADR: La Banque Postale through a dedicated sidecar, replacing Enable Banking

> Date: 2026-11-05
> Status: 🟢 Accepted — author live run done, maintainer live run pending

## Context

Enable Banking reaches La Banque Postale's **payment accounts** through PSD2. It
cannot read the savings products the same customer sees in the bank's own web
client: Livret A, LDDS, LEP, Compte sur Livret, Livret Jeune, PEL/CEL. This is a
scope limit of PSD2, not a configuration issue — the same limit is already
recorded in [the dual-provider ADR](./2026-03-01-dual-bank-providers.md).

On a La Banque Postale account, the gap is therefore structural: the current
account syncs, the livrets do not. Keeping the livrets up to date by hand is the
exact friction this sidecar removes.

The user asked for a **full replacement** rather than a savings-only sidecar
next to Enable Banking. A savings-only connector avoids touching the current
account, but it produces two sources for the same bank: two sets of external
account ids, an IBAN-matching problem for the current account, and two sync
histories in the UI. One connector for one bank is simpler to reason about.

The bank's web client is the only known surface that exposes the livrets. Two
live captures fixed the login flow, the anti-fraud posture and the server-rendered
XHTML structure, and the author ran the whole connector end to end on an isolated
stack. The maintainer's own live run is still pending.

## Decision

1. **One dedicated Python sidecar, `services/lbp-auth`**, built on the shared
   `services/base` image and the `picsou_sidecar` package. It is the only code
   that knows the bank's web client.
2. **The sidecar is the single source for La Banque Postale accounts.** It
   imports the current account and its transactions, the regulated livrets
   (Livret A, LDDS, LEP), the commercial livret, and — as the parsers become
   available — the card and loans. Enable Banking is not used for La Banque
   Postale any more; the user deletes the old Enable Banking **account(s)** once
   the sidecar syncs. Deleting only the connection would leave the account
   orphaned and still counted (V76 links it with `ON DELETE SET NULL`).
3. **The account product is explicit.** The normalized snapshot carries a
   `kind` (`CURRENT_ACCOUNT`, `LIVRET_A`, `LDDS`, `LEP`, `CSL`,
   `LIVRET_JEUNE`, `PEL`, `CEL`, `CARD`, `LOAN`) and the backend maps it to an
   `AccountType` directly. Livrets are not guessed from their name, unlike the
   Enable Banking path.
4. **Cookies only, encrypted.** The backend stores the session state through
   `CryptoEncryption`; the password and the mobile approval are never stored.
   A rejected session becomes inactive and can only be renewed by the user.
5. **No unattended re-login.** No startup sync, no auto-resync hook: if the bank
   asks for a mobile approval on every login, an expired session cannot be
   renewed without the user. The daily 08:00 resync includes `lbp` last in
   `MemberSyncService.CANONICAL_ORDER`: a live session is queued normally, an
   expired overnight one reports `NEEDS_REAUTH` (not `FAILED`), and the user
   reconnects on the LBP tab.
6. **The login layer is headless Chromium; the data layer is `httpx`.** Two live
   captures showed a normal HTML form, a per-load shuffled keypad that sends
   **positions** (never the digits) and a mobile-approval page polled every 3 s.
   The login page also runs anti-fraud scripts that set a 52-character
   `iscdName` device fingerprint; a real browser is the only reliable way to
   produce it, so the sidecar drives Chromium on the `chromium` base. The user
   clicks the keypad in that browser, so the digits are never typed anywhere. The
   session cookies are then replayed with `httpx` for the server-rendered pages.
7. **No retry on a login step.** A wrong password or second factor can lock the
   bank account. The adapter has no retry filter, the service calls the sidecar
   once per request, and a failed attempt is never replayed.

## Alternatives considered

### Savings-only sidecar beside Enable Banking

- **Pros**: the current account keeps the stability of PSD2; the sidecar covers
  only the gap.
- **Cons**: two sources for one bank, two id spaces, and a duplicate current
  account unless the sidecar deliberately skips it. The user rejected this.

### Powens / Budget Insight

- **Pros**: an aggregator already handles La Banque Postale's savings products
  through screen scraping; no reverse-engineering on our side.
- **Cons**: paid service, the adapter ships experimental and untested in this
  repository, and the whole point of the exercise is to own the connector.
  Rejected by the user.

### Keep Enable Banking and import livrets by hand

- **Pros**: nothing to build or maintain.
- **Cons**: the friction the feature exists to remove. Rejected.

### A browser sidecar from day one (Playwright / Camoufox)

- **Pros**: drives the login itself and survives JavaScript and anti-bot
  challenges.
- **Cons**: a much larger image and a per-user browser state, for a data path
  that may not need a browser at all.
- **Outcome**: chosen for the login after the captures showed that the anti-fraud
  scripts set `iscdName`. The data path stays `httpx`, so the browser is confined
  to the login.

## Reasoning

The data path of a bank web client is usually a small set of read-only JSON
calls once a session exists; the login is the fragile part. Separating the two
lets the backend contract, the session storage and the normalized account model
be built and tested before the unknown part is resolved. This is the same
sequence that produced the BoursoBank and Caisse d'Epargne sidecars.

Explicit product kinds are the main functional win over Enable Banking:
`savings_interest_config` gets the right product from the first import, and the
account never sits in the `CHECKING` bucket waiting for the name-based detector.

That is an explicit exception to the savings feature's *suggest, never
auto-apply* rule, which remains true for the name-based detector: LBP reports
`LIVRET_A`, `LDDS` or `LEP` directly in `kind`, so `SavingsAutoConfigService`
writes the default product, `RegulatedRates` rate and legal ceiling when the
account has no configuration. It never overwrites a user-saved config. The rate in
force in `RegulatedRates` and the legal ceilings are defaults, editable by the
user; they are not a live feed.

## Trade-offs accepted

- **Unofficial integration.** It breaks when La Banque Postale changes its web
  client, and the current account breaks with it — the sidecar is not limited
  to the savings products.
- **Human presence at login** if the bank asks for a mobile approval every time.
  No credentials are stored, so an expired session can only be renewed by the user.
- **A short web session.** An immediate second sync reuses the stored cookies,
  but the bank logs the session out after a few minutes of inactivity: a sync
  after that window needs a new login + A2F. This is the Caisse d'Épargne model
  (cookie-only connector), not the
  BoursoBank/CIC one, which stores encrypted credentials and re-logs in silently.
  The daily 08:00 resync includes LBP last: a live session syncs normally, an
  expired overnight one reports `NEEDS_REAUTH` (not `FAILED`), and the morning UI
  routes to the LBP tab. Keeping the session warm would mean scheduled requests,
  which no cookie-only connector does.
- **No PSD2 guarantee.** The bank may rate-limit, challenge or block automated
  access. Failures must be typed so they are diagnosable.
- **Request allow-list, split in two.** Document destinations (httpx, top-level
  navigations, redirects, websockets, Chromium Document hops) must be the bank
  origin or another `*.labanquepostale.fr` host — never a CDN, since anyone
  can register one. The two live-observed CloudFront distributions are pinned
  by exact hostname and allowed only as non-Document subresources. Everything
  else fails closed. The strict exact-origin version (added for
  the security review) broke the live login - the keypad scripts and
  anti-fraud payloads come from CloudFront distributions and from
  `transverse.labanquepostale.fr`, and blocking them made the bank refuse the
  credentials. The `*.cloudfront.net` interim step proved too broad (anyone
  can create a distribution) and was replaced by pinning; blocked requests
  log the host only, never the URL.
- **A larger sidecar image.** The login needs Chromium, so `lbp-auth` builds on
  the `chromium` base rather than the browserless `python` one.
- **One extra click in the login flow.** After the keypad, the bank shows a
  strong-authentication notice; its **Continuer** button is what sends the
  mobile notification. The sidecar clicks it. Without that click no push is
  sent, which looked like a bank refusal.

## Consequences

- New service `services/lbp-auth/` (shared `chromium` base, Dockerfile, GHCR
  image, CI job) and a `lbp` Compose profile.
- New backend pieces on the shared sidecar architecture: `LbpAdapter` (a
  concrete client with nested records, not a port), the `LBP` entry in
  `SidecarBank`, `LbpSyncService`, `LbpController`, `LbpSession`,
  `LbpSyncStatus`, `LbpSessionRepository`, the `lbpSyncExecutor` bean in
  `SyncExecutorConfig`, and migrations `V143` (session table) and `V144`
  (optional encrypted remembered identifier).
- `SavingsAutoConfigService` fills the regulated-livret configuration on import
  (`LIVRET_A`, `LDDS`, `LEP`), so the accounts no longer arrive "to configure".
- New frontend Sync tab, keypad/mobile-approval panel, Add-account entry and
  first-run setup step. The identifier can be remembered (encrypted); the
  password never can.
- La Banque Postale accounts created by the sidecar are prefixed `lbp_`; the
  user deletes the old Enable Banking account(s) for this bank once the sidecar
  syncs (deleting the last one also removes the connection). Deleting only the
  connection is not enough: the account survives with `requisition_id` NULL and
  its balance still counts.
- New feature note: [docs/features/la-banque-postale.md](../features/la-banque-postale.md).

## Captures answered

1. The password is entered on a **shuffled text keypad**; the bank's script
   writes the DOM positions, not the digits, into `password`.
2. Mobile approval is requested **only on a non-trusted device**; a trusted
   device skips it. No SMS path was observed.
3. Account data is **server-rendered XHTML**, not JSON.
4. The anti-fraud scripts set `iscdName`; no CAPTCHA was observed.
5. Operations are exposed for about **90 days** on `init-releve_ccp`; older
   history is in the export and the monthly PDFs.

## Remaining unknowns

1. The exact idle timeout: an immediate re-sync works, a 9-minute idle gap does
   not. The client no longer assumes a 600 s lifetime.
2. How the bank behaves under repeated automated logins over time (one live run
   passed; headless Chromium was not detected on 2026-11-06).
3. How to parse the `telechargementMouvements.ea` export for history beyond the
   90-day window.
