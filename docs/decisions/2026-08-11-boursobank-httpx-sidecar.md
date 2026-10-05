# ADR: BoursoBank through a browserless sidecar, app-push only

> Date: 2026-08-11
> Status: ✅ Active

## Context

Enable Banking reaches BoursoBank's payment accounts but not its **PEA or
compte-titres**: PSD2 covers payment accounts, and a securities envelope is not
one. For a BoursoBank customer that is routinely the largest single line in net
worth, and it was invisible.

A BoursoBank connector already existed in the tree, dormant since 1.0.0. Auditing
it before reviving it settled the question of whether to repair or rewrite: it had
been written against markup that does not exist. The virtual keyboard was parsed
as if the digit were the button's text — it is a base64 SVG, deliberately, so that
reading it requires knowing the ten images. The account list was parsed from
`data-account-id`/`-label`/`-balance` attributes BoursoBank has never served. The
trading endpoint was `_user__{hash}__/trading` rather than `_user_/_{hash}_/trading`,
against a hardcoded host instead of the one `window.BRS_CONFIG` publishes. And
every failure was swallowed with `return []`, which is precisely the pattern the
project spent 2026 removing. None of it could have worked; there was nothing to
repair.

That left three real decisions: browser or not, which second factors to support,
and what to do about BoursoBank's own aggregation of other banks' accounts.

## Decision

1. A dedicated internal-only **FastAPI + httpx** sidecar, `services/bourso-auth`,
   with no browser. BoursoBank's one anti-bot token, `__brs_mit`, is emitted in the
   page body and echoed back as a cookie; there is no JavaScript challenge to
   execute.
2. The virtual keyboard is decoded by **hashing each button's SVG** and looking the
   digit up. A pad that does not yield all ten distinct digits raises rather than
   falling back to DOM order.
3. **App-push validation is the only second factor.** SMS and e-mail surface as a
   typed `MFA_TYPE_UNSUPPORTED`.
4. Credentials are ephemeral. Only the session cookies are persisted, encrypted
   through `CryptoEncryption`, with each cookie's own domain and path.
   `API_URL` and `USER_HASH` are re-read from `BRS_CONFIG` on every sync.
5. **Accounts BoursoBank aggregates from other banks are not imported**, and the
   number skipped is logged.
6. Loans and transactions are out of scope.
7. Authentication only queues the import. The job state
   (`IDLE → QUEUED → RUNNING → SUCCESS/FAILED`) is persisted, jobs are fenced by
   session id, and jobs interrupted by a restart are failed at startup.
8. Upstream I/O happens outside any transaction; one short transaction then
   replaces holdings and writes the snapshot atomically. A securities account whose
   total disagrees with `cash + Σ lines` is rejected wholesale — in the sidecar and
   again in the service.
9. An unresolvable ISIN is **not** an error: the position keeps BoursoBank's symbol
   as its ticker, and `BoursoBank` joins `AccountService.PROVIDER_VALUED` so the
   account is valued from the broker's own figures.

## Alternatives considered

### Repair the existing connector in place
- **Pros**: smaller diff; keeps the V23 table.
- **Cons**: every scraping rule, the port shape, the error handling and the
  persistence model were wrong. What survived was the file names. Keeping the
  synchronous, non-reconciling shape would also have left BoursoBank as the only
  connector outside the pattern every sibling now follows.

### Drive it with Playwright, like Bourse Direct and Amundi
- **Pros**: uniform with the two newest sidecars; immune to a future JavaScript
  challenge.
- **Cons**: a Chromium runtime and roughly ten times the image for a site that
  answers plain HTTPS today. DEGIRO already set the browserless precedent for an
  API that does not need one.

### Support SMS and e-mail second factors best-effort
- **Pros**: covers users who have not enabled app validation.
- **Cons**: the reference implementation knows the endpoint paths but explicitly
  refuses those flows, so we would be shipping untested code down a path where
  **each failed attempt counts toward an account lockout**. A clear, translated
  "switch to app validation" costs the user one settings change; a half-working
  OTP path costs them their account access.

### Import the aggregated third-party accounts too
- **Pros**: one connection covers everything the user sees in BoursoBank.
- **Cons**: they duplicate an Enable Banking connection with data of unknown
  freshness, and Picsou has no cross-provider account merging to resolve the
  duplicate. Excluding them is the smaller surprise.

### Import transactions
- **Pros**: makes a current account genuinely useful rather than a balance.
- **Cons**: the export moved behind a CSRF-protected POST in July 2026 and its
  columns shift depending on whether anything in the range is tagged — reading by
  position silently mis-parses amounts. That is a connector of its own, and Bourse
  Direct, Amundi and DEGIRO all drew the same line. The generic CSV importer still
  accepts BoursoBank's export.

## Reasoning

The sidecar is an anti-corruption boundary: BoursoBank's HTML, its virtual
keyboard and its push choreography stay in three Python files that can be
rewritten without touching a line of Java, while `BoursoPort` gives the domain a
typed financial contract that does not change when BoursoBank redesigns.

Fail-closed beats fresh-but-partial, for the same reason as every connector ADR
before this one: a PEA silently losing a line would corrupt the net-worth series
permanently, whereas a refused sync costs a retry. The novelty here is that the
completeness check is *cross-cutting* — every account link on the dashboard must
be accounted for, not just those inside a section that parsed — because the
section patterns are the fragile part.

Treating an unresolvable ISIN as normal rather than exceptional is the one place
this connector is deliberately more permissive than its siblings. Refusing a sync
because a *label* could not be resolved would make it unusable for the exact
accounts it exists to import, and the provider-valued fallback already exists for
precisely this shape of gap. (The first revision believed BoursoBank published no
ISIN at all and added a speculative lookup endpoint for it. The live run showed
every position ships its own `isin`; the endpoint was removed. The permissive rule
stays — it is now cheap insurance rather than the main path.)

## Trade-offs accepted

- An unofficial integration that needs maintenance whenever BoursoBank ships a
  front-end change. Failures are typed (`UPSTREAM_FORMAT_CHANGED`) so that is
  diagnosable rather than mysterious.
- If BoursoBank turns `__brs_mit` into a real JavaScript challenge, this connector
  needs Playwright. That risk is not eliminable; it is written down here so the
  next person does not rediscover it.
- Users on SMS second factor cannot connect until they switch to app validation.
- A user who also syncs BoursoBank through Enable Banking gets duplicate current
  accounts and prunes one side by hand.
- No public CI can prove the live login or the push polling loop. The live
  validation of 2026-08-11 ran on a trusted device, so the app-push path is still
  only inferred.
- Single replica: pending validations live in the sidecar's process memory.

## Consequences

- Rewritten: `services/bourso-auth/` (now `main.py`, `virtual_pad.py`,
  `accounts_parser.py`, four test modules), `BoursoPort`, `BoursoAdapter`,
  `BoursoSyncService`, `BoursoController`, `BoursoSession`.
- New: `BoursoErrorCode`, `BoursoSyncStatus`, `BoursoSyncConfig`,
  `BoursoSyncRecovery`, migration `V78__bourso_session.sql` (which drops the
  never-used V23 table), `BoursoPanel.tsx`.
- `AccountService.PROVIDER_VALUED` and `AccountConnectionService.Kind` each gain
  BoursoBank; `IntegrationsService` gains its detection signal. `SchedulerService`
  and `RateLimitConfig` already carried it.
- `docker-compose.yml`, `docker/docker-compose.yml`, `.env.example` and both CI
  workflows gain the sidecar; the setup-wizard catalog, the admin toggle and the
  Sync tab are un-hidden, and BoursoBank leaves the disabled list in `SECURITY.md`
  and `ARCHITECTURE.md`.

## Validation

Confirmed end-to-end against a live account on 2026-08-11: `__brs_mit` bootstrap,
virtual keyboard decoded on the first attempt, dashboard, and a PEA of 9 positions
reconciling to the cent on both checks. Two things this ADR originally asserted
were corrected by that run — the trading summary splits `account` and `positions`
across separate view sections, and the ISIN ships with each position rather than
needing a lookup. Both had been *inferred* from the reference implementation's
type definitions rather than observed, which is precisely the class of assumption
the fail-closed design exists to catch: the connector refused to write anything
until they were right.

## Links

- Feature note: [bourso-bank.md](../features/bourso-bank.md)
- Precedent: [Bourse Direct isolated atomic sync](./2026-07-21-bourse-direct-isolated-atomic-sync.md)
- Precedent: [DEGIRO session-only, no stored TOTP](./2026-08-05-degiro-session-only-no-stored-totp.md)
- Related: [Ports and adapters](./2026-01-01-ports-and-adapters.md)
- Upstream reference: [azerpas/bourso-api](https://github.com/azerpas/bourso-api)
