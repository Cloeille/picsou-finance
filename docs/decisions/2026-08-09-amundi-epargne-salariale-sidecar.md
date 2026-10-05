# ADR: Amundi Épargne Salariale through an isolated browser sidecar

> Date: 2026-08-09
> Status: ✅ Active

## Context

French employee savings (PEE/PEG, PERCO, PER Collectif) is invisible to every
source Picsou already has: it is not a payment account, so PSD2 and Enable
Banking cannot reach it, and the disabled Powens adapter collapses PERCO into a
lineless `savings` product. Amundi is the largest provider in that market.

Amundi exposes no public API. Its espace épargnant is a SPA calling endpoints
that return exactly what is needed, but reaching them means getting past a
two-screen login carrying an anti-robot check and, since July 2024, a
**mandatory second factor on every login** — an app push or an SMS code. There
is no long-lived credential a user can paste, and no unattended path at all.

The anti-robot check turned out to be **FriendlyCaptcha**, a proof-of-work
widget that solves itself in about a second under headless Chromium. That is a
materially easier problem than the reCAPTCHA v2 the woob module documents, which
would have needed a paid solving service; the portals differ, and the live site
was checked rather than trusted.

That last point is the one that decides the design. Every earlier connector ADR
in this repo has preferred whatever survives unattended operation; here nothing
does, so the question is not "how do we sync in the background" but "what is the
least fragile way to run an inherently interactive sign-in".

## Decision

1. A dedicated internal-only FastAPI + Playwright sidecar, `services/amundi-auth`,
   built on the Chromium-only non-root image the sidecar ADR already mandates. It
   is the only component that knows Amundi's HTML, its captcha, or its 2FA.
2. Credentials are ephemeral. Only the sidecar's opaque session blob — Playwright
   storage state plus the harvested bearer — is persisted, encrypted through
   `CryptoEncryption`. Passwords, OTP values and the bearer are never logged.
3. The bearer is harvested by watching the requests the SPA itself makes, not by
   reading an Amundi-internal storage key. Amundi keeps it in memory, so storage
   state alone does not re-authenticate.
4. Credentials are entered as real keystrokes, never `fill()`: the inputs are
   masked and register per keystroke, so a bulk fill leaves the form invalid and
   the submit button disabled. The consent overlay is answered first, because it
   intercepts pointer events. Should the captcha ever stop producing a token, it
   surfaces as its own `CAPTCHA_BLOCKED` code rather than as bad credentials.
5. Authentication only *queues* the import. The job state
   (`IDLE → QUEUED → RUNNING → SUCCESS/FAILED`) is persisted, jobs are fenced by
   session id, and jobs interrupted by a restart are failed at startup.
6. Upstream I/O happens outside any database transaction; the snapshot is then
   validated, reconciled and written in one short transaction that replaces the
   plan's holdings atomically.
7. A plan total that disagrees with the sum of its lines is rejected wholesale —
   in the sidecar and again in the service — and the last known-good data is kept.
8. Valuation comes from Amundi, never from a price provider, and no OpenFIGI
   lookup is attempted.

## Alternatives considered

### Backend HTTP client with a user-pasted bearer (IBKR-style)
- **Pros**: no sidecar, no browser, no captcha to clear; smallest surface.
- **Cons**: the `X-noee-authorization` bearer is short-lived, so the user would
  re-paste it from browser devtools before every single sync. That is worse than
  the 2FA prompt it avoids, and it asks a non-technical user to open devtools.

### Plain HTTP client plus a captcha-solving service
- **Pros**: no browser; this is what woob does for the portal it targets.
- **Cons**: sends credentials-adjacent traffic through a third party, costs money
  per solve, and puts a paid external dependency in the path of a self-hosted app.
  Non-starter for this project — and moot here, since FriendlyCaptcha needs no
  solver at all.

### Import the downloadable "relevé de situation"
- **Pros**: no automation against Amundi at all; nothing to break when they
  redesign; trivially auditable.
- **Cons**: entirely manual, and the document is a formatted statement rather
  than a data feed — parsing it is more fragile than the JSON endpoint, not less.

### Wait for Powens to cover it
- **Pros**: no unofficial integration to maintain.
- **Cons**: Powens is experimental and disabled in 1.0.0, and even its existing
  mapping discards the fund lines that are the whole point here.

## Reasoning

The sidecar is an anti-corruption boundary: Amundi's DOM, its captcha and its
2FA choreography stay in one Python file that can be rewritten without touching
a line of Java, while `AmundiPort` gives the domain a typed financial contract
that does not change when Amundi redesigns.

Fail-closed beats fresh-but-partial. Épargne salariale is long-horizon money
whose history matters more than intraday accuracy; a plan silently losing a line
would corrupt the net-worth series permanently, whereas a refused sync costs
nothing but a retry.

Choosing the browser over the paste-a-token shortcut is a deliberate trade of
implementation cost for the only user experience that is actually repeatable.

## Trade-offs accepted

- An unofficial integration that will need maintenance whenever Amundi ships a
  front-end change; selector lists are redundant and failures are typed to make
  that diagnosable rather than mysterious.
- Amundi could swap FriendlyCaptcha for something interactive and lock the
  connector out entirely. That risk is not eliminable, only reported honestly
  through `CAPTCHA_BLOCKED`.
- One more container, and a single replica, because pending authentication
  attempts live in the sidecar's process memory.
- No unattended first sync: the daily scheduler can only refresh a session that
  a human already established.
- Public CI cannot prove the live login.

## Consequences

- New: `services/amundi-auth/`, `AmundiPort` / `AmundiErrorCode` /
  `AmundiAdapter`, `AmundiSyncService`, `AmundiController`, `AmundiSession`,
  migrations `V69`/`V70`, and the `EMPLOYEE_SAVINGS` account type across backend
  and frontend.
- `AccountService`'s Bourse-Direct-specific "prefer the provider total when a
  holding cannot be priced" rule is generalised to a `PROVIDER_VALUED` set, since
  an FCPE is never quotable at all.
- `SchedulerService` gains one more per-member call; `RateLimitConfig` one more
  bucket; `docker-compose.yml`, `docker/docker-compose.yml`, `.env.example` and
  both CI workflows gain the new sidecar.
