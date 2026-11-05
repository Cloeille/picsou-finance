# ADR: La Banque Postale through a dedicated sidecar, replacing Enable Banking

> Date: 2026-11-05
> Status: 🟡 Proposed

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

The bank's web client is the only known surface that exposes the livrets. No
live capture of it has been taken yet: the login flow, the anti-bot posture and
whether the account data is JSON or server-rendered HTML are all unverified.

## Decision

1. **One dedicated Python sidecar, `services/lbp-auth`**, built on the shared
   `services/base` image and the `picsou_sidecar` package. It is the only code
   that knows the bank's web client.
2. **The sidecar is the single source for La Banque Postale accounts.** It
   imports the current account and its transactions, the regulated livrets
   (Livret A, LDDS, LEP), the commercial livret, and — as the parsers become
   available — the card and loans. Enable Banking is not used for La Banque
   Postale any more; the user deletes that connection once the sidecar syncs.
3. **The account product is explicit.** The normalized snapshot carries a
   `kind` (`CURRENT_ACCOUNT`, `LIVRET_A`, `LDDS`, `LEP`, `CSL`,
   `LIVRET_JEUNE`, `PEL`, `CEL`, `CARD`, `LOAN`) and the backend maps it to an
   `AccountType` directly. Livrets are not guessed from their name, unlike the
   Enable Banking path.
4. **Cookies only, encrypted.** The backend stores the session state through
   `CryptoEncryption`; the password and the mobile approval are never stored.
   A rejected session becomes inactive and can only be renewed by the user.
5. **On-demand sync only.** No scheduler, no startup sync, no auto-resync hook
   initially: if the bank asks for a mobile approval on every login, a scheduled
   sync cannot run unattended. A scheduled sync is reconsidered only after the
   session lifetime has been measured.
6. **The login layer is chosen from the recon, not guessed.** The sidecar's
   backend-facing contract (a normalized account snapshot) does not depend on
   it. If the web client can be replayed with cookies, the transport is
   `httpx`, like Caisse d'Epargne. If it is protected by JavaScript or an
   anti-bot challenge, the transport moves to Camoufox. This choice is
   deliberately deferred.
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
  that may not need a browser at all. Kept as the fallback rather than the
  starting point.

## Reasoning

The data path of a bank web client is usually a small set of read-only JSON
calls once a session exists; the login is the fragile part. Separating the two
lets the backend contract, the session storage and the normalized account model
be built and tested before the unknown part is resolved. This is the same
sequence that produced the BoursoBank and Caisse d'Epargne sidecars.

Explicit product kinds are the main functional win over Enable Banking:
`savings_interest_config` gets the right product from the first import, and the
account never sits in the `CHECKING` bucket waiting for the name-based detector.

## Trade-offs accepted

- **Unofficial integration.** It breaks when La Banque Postale changes its web
  client, and the current account breaks with it — the sidecar is not limited
  to the savings products.
- **Human presence at login** if the bank asks for a mobile approval every time.
  The sync stays on demand.
- **No PSD2 guarantee.** The bank may rate-limit, challenge or block automated
  access. Failures must be typed so they are diagnosable.
- **A live recon is still required.** No endpoint, selector or payload is
  confirmed yet; the first version must not claim otherwise.

## Consequences

- New service `services/lbp-auth/` (shared base, Dockerfile, GHCR image, CI
  job) and a `lbp` Compose profile.
- New backend pieces mirroring Caisse d'Epargne: `LbpPort`, `LbpAdapter`,
  `LbpSyncService`, `LbpController`, `LbpSession`, `LbpSyncStatus`,
  `LbpErrorCode`, `LbpSyncConfig`, `LbpSessionRepository`, migration adding the
  `lbp_session` table.
- New frontend Sync tab and login panel once the login flow is known.
- La Banque Postale accounts created by the sidecar are prefixed `lbp_`; the
  existing Enable Banking connection for this bank is deleted by the user.
- New feature note: [docs/features/la-banque-postale.md](../features/la-banque-postale.md).

## Open questions (blocking the live implementation)

1. Is the password entered on a virtual keypad (as at Caisse d'Epargne) or in a
   text field? Are the key images shuffled per connection?
2. Is a mobile approval requested on every login, only on a new device, or only
   for sensitive operations? Is there a "trusted device" cookie? Does the bank
   still fall back to an SMS code (Certicode) for some accounts?
3. Is the account data fetched as JSON XHR or rendered server-side?
4. Is there an anti-bot layer (TLS fingerprint, JavaScript challenge, CAPTCHA)?
5. How long does a session stay usable, and can it be refreshed without the
   user?
6. Does the web client expose transaction history for livrets, or only
   balances?
