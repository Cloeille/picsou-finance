# ADR: Caisse d'Epargne through a browserless httpx sidecar, on demand

> Date: 2026-10-07
> Status: ✅ Active

## Context

Enable Banking reaches Caisse d'Epargne payment accounts, but the user wants the
current account, the Livret A and the deferred-debit card read directly, with the
card as its own account carrying the outstanding amount to be debited. Four live
connections (2026-10-05 to 2026-10-07) mapped the bank's web client: an SSO
login (image keypad, then Sécur'Pass approval on the user's phone) ends in a
bearer-token session, and the account, transaction and card data are plain
read-only JSON GETs behind it.

Two facts shaped the design:

- The data side needs no browser: once a session exists it is replayed over
  HTTPS.
- The login side is not reproducible without one yet. The keypad `password` field
  is a 295-character value computed by the bank's page script, not the digits
  joined, and how it is built is not identified. The sidecar therefore cannot
  log in on its own today.

The session lifetime is also unknown: replays worked for at least ten minutes,
then the bank asked for a new authentication, and it is not established whether
our replays, the browser cookie rotation or an idle limit caused it.

## Decision

1. A dedicated internal-only **FastAPI + httpx** sidecar, `services/caisse-epargne-auth`,
   with no browser, wired like `bourso-auth` (Compose service, shared
   `APP_SIDECAR_API_KEY`, CI job, GHCR image).
2. It exposes only reads: `/token-check` (replay the session to a bearer token)
   and `/accounts` (current account, Livret A, active deferred-debit card and
   their transactions). A bank response it does not recognise fails the whole
   snapshot (`UPSTREAM_FORMAT_CHANGED`) rather than importing part of it.
3. **Cookies-only session, encrypted.** The backend stores the session cookies
   (each with its domain and path) encrypted through `CryptoEncryption`, bound to
   the member (table `caisse_epargne_session`, migration `V109`). The password and
   the Sécur'Pass approval are never stored. A session the bank rejects becomes
   inactive and must be reconnected, it is never renewed silently.
4. **On-demand sync only.** `POST /api/caisse-epargne/sync` is the only trigger.
   Caisse d'Epargne is not part of the 08:00 member sync, the startup sync or the
   MCP full sync, because a sync needs a live session and the user is present for
   the Sécur'Pass. A scheduled sync waits for a measured session lifetime.
5. **No login in this version.** No endpoint collects a password and no frontend
   exists yet. The session can only be stored by code that already holds one, so
   this version is not usable end to end by a normal user. The browser-assisted
   login stays open as the next step.
6. **Best-effort logout, honestly reported.** Deleting the connection deletes the
   encrypted cookies in Picsou. The logout call is not made in this version, and
   the response states `bankSessionRevoked: false`: the bank session may stay
   active until it expires. The UI must say so and must not claim a logout.
7. The card is a `CREDIT_CARD` liability account whose balance is the outstanding
   amount, and a positive computed outstanding refuses the sync until a live run
   confirms the sign.

## Alternatives considered

### A browser sidecar (Playwright / Chromium), like Fortuneo or Amex
- **Pros**: could drive the login itself, so the keypad `password` computation is
  not our problem; immune to a client-side challenge.
- **Cons**: a Chromium runtime and a much larger image, a per-user browser state
  to isolate, and the data side does not need it. The user would also type a bank
  password into an automated browser.

### Embedded browser where the user types on the bank's keypad
- **Pros**: the password never reaches Picsou; works while the `password`
  computation is unknown.
- **Cons**: heavier UI and infrastructure. Kept as the fallback for login if
  reading the page script does not settle how the value is built.

### Scheduled daily sync, like Bourso
- **Pros**: fresh data without the user.
- **Cons**: the Sécur'Pass cannot be approved unattended and the session lifetime
  is not measured. A silently expiring session would turn every run into a
  failure.

### Store the credentials to log in again
- **Pros**: scheduled sync would be possible.
- **Cons**: rejected: Sécur'Pass is human anyway, and storing a bank password is
  a risk the cookies-only model avoids.

## Reasoning

The data path is a stable, read-only HTTPS contract, so a browserless sidecar is
the smallest thing that works and follows the Bourso and DEGIRO precedent. The
login path is the unknown, so this version ships what is verified offline and
says plainly what is missing, instead of hiding a fragile login behind a polished
screen.

## Trade-offs accepted

- Unofficial integration: it needs maintenance when the bank changes its web
  client. Failures are typed so that is diagnosable.
- Not usable by a normal user yet: no login, no frontend. The first PRs prepare
  the sidecar, the storage and the on-demand sync.
- The live end-to-end `/accounts` run of the fixed sidecar is still to be shown;
  every fix so far is covered by offline tests only.
- The card operations of the current period were not yet visible at the last live
  run, so the computed outstanding was zero. To check again late in a month.
- The bank session may outlive a Picsou disconnect.

## Consequences

- New: `services/caisse-epargne-auth/`, `CaisseEpargnePort`, `CaisseEpargneAdapter`,
  `CaisseEpargneSyncService`, `CaisseEpargneController`, `CaisseEpargneSession`,
  `CaisseEpargneSyncStatus`, `CaisseEpargneErrorCode`, `CaisseEpargneSyncConfig`,
  `CaisseEpargneSyncRecovery`, migration `V109__caisse_epargne_session.sql`.
- New CI job `caisse-epargne-sidecar` and a GHCR image `caisse-epargne-auth`.
- The sidecar tests mix pytest functions and `unittest` classes, so CI runs
  pytest (installed at run time, not in the image).
- Not wired, on purpose: the member sync, `SyncStatusService`, MCP `SyncTools`
  and the setup wizard health check, until login and a frontend exist.
- Details and open gaps: [docs/features/caisse-epargne.md](../features/caisse-epargne.md).
