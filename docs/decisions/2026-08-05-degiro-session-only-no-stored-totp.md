# ADR: DEGIRO integration stores only the session, never the TOTP secret

> Date: 2026-08-05
> Status: ✅ Active

## Context

DEGIRO is a compte-titres-only broker (no PEA envelope in France), reachable
only through an unofficial, reverse-engineered API — DEGIRO does not publish
one. Unlike Trade Republic, Bourse Direct, and BoursoBank, whose scraped
sessions last days to weeks, DEGIRO's session cookie times out after ~30
minutes of inactivity and there is no refresh token. The daily 08:00 scheduler
that silently re-syncs every other integration (`SchedulerService.dailyBankSync`)
cannot do the same here: replaying yesterday's session simply 401s.

Community DEGIRO clients solve this by storing the account's TOTP *secret*
(the seed behind the 6-digit authenticator code) so a headless login can
generate a fresh code on every scheduled run, with no user interaction.

## Decision

The DEGIRO integration stores only the opaque `{sessionId, intAccount}` blob,
exactly like every other broker session, encrypted at rest via
`CryptoEncryption`. It never stores the account's TOTP secret. When a sync call
finds the session expired, `DegiroSyncService` flips the stored `DegiroSession.status`
to `REAUTH_REQUIRED` instead of retrying, regenerating a code, or failing
silently. There is no `resyncIfSessionActive` entry point and `DegiroSyncService`
is not injected into `SchedulerService` — sync is manual-only, triggered from
the DEGIRO tab.

## Alternatives considered

### Store the TOTP secret for unattended daily re-authentication

- **Pros**: matches the UX of every other integration — a silent daily
  auto-sync, no re-login friction.
- **Cons**: a TOTP secret is a durable second factor, not a revocable token.
  Storing it is a materially bigger trust step than a session cookie: a leak of
  `CRYPTO_ENCRYPTION_KEY` plus the database would let an attacker generate
  valid 2FA codes indefinitely, not just replay a session until it naturally
  expires. It also changes what "clearing a session" means for the user — a
  stored secret needs its own explicit revocation story that a cookie doesn't.

### Prompt the user for a fresh TOTP code on every scheduled sync

- **Pros**: no secret stored, still gets some automation.
- **Cons**: scheduled jobs run unattended; there is no user present to supply a
  code. Not actually automatable — just a more complicated way of doing manual
  sync.

## Reasoning

A trust-model change (storing a durable second factor) is not something to
fold into a feature's first version — `docs/CODING_RULES.md` rule 0 treats
exactly this kind of bundling as a smell: it would let "the integration
supports auto-sync" quietly legitimize "we now store 2FA secrets," a much
larger decision than the feature itself asked for. Session-only storage keeps
DEGIRO's risk profile identical to every other broker integration already in
the codebase; it only trades daily automation for a manual "sync now" button
and an explicit reconnect prompt.

## Trade-offs accepted

- No daily auto-sync for DEGIRO, unlike every other broker/bank integration —
  freshness is "whenever the user opens the DEGIRO tab and clicks sync,"
  bounded above by the ~30-minute session window from the last login.
- The UI must clearly distinguish `REAUTH_REQUIRED` from a hard failure, since
  it is an expected, frequent state rather than an error.
- If unattended daily sync is wanted later, storing the TOTP secret (Option A
  above) needs its own standalone review — updating `SECURITY.md`'s threat
  model explicitly — not a silent addition to this integration.

## Consequences

- `DegiroSession` has a `status` enum (`ACTIVE` / `REAUTH_REQUIRED` / `FAILED`)
  instead of the `expiresAt`-based freshness Bourso/Bourse Direct use.
- `DegiroSyncService` is not wired into `SchedulerService.dailyBankSync`.
- `DegiroPort.fetchPortfolio` treats an upstream 401 as `SESSION_EXPIRED`,
  never as an empty portfolio — same discipline as
  [the Bourse Direct ADR](./2026-07-21-bourse-direct-isolated-atomic-sync.md).
