# Feature: Amundi Épargne Salariale sync

> Last updated: 2026-08-24

## Context

French employee savings — PEE/PEG, PERCO, PER Collectif — was a total blind
spot. Enable Banking cannot reach it (it is not a payment account under PSD2),
and the Powens adapter flattens PERCO into a generic `savings` product with no
lines and ships disabled. For anyone with an employer plan that is routinely a
five-figure hole in net worth.

This connector signs in to the Amundi espace épargnant and imports, per plan:
the plan itself with its total valuation, and every FCPE line it holds with
units, unit value and valuation. It is unofficial, read-only, and fails closed.

## How it works

Authentication is interactive and cannot be otherwise: Amundi runs an anti-robot
check on the password screen and, since July 2024, a mandatory second factor on
**every** login — a push validation in the "Mon Épargne" app, or an SMS code.
There is no token to paste and no unattended path. A Playwright sidecar therefore
drives a real Chromium through the login, and the resulting session is persisted
encrypted so later syncs skip the second factor until Amundi invalidates it.

Sign-in is two screens on an Angular SPA at `/#/connexion`: the account number
with a "Suivant" button, then the password with "Connexion". The anti-robot
check is **FriendlyCaptcha**, a proof-of-work widget that starts by itself and
drops its token into a hidden `input[name=captcha]` — no image grid, and it
settles in about a second under headless Chromium. Verified against the live
site on 2026-08-09.

Everything the connector needs comes from one upstream call,
`GET /api/individu/dispositifsMulti?flagUrlFicheFonds=true&codeLangueIso2=fr`,
authenticated with the `X-noee-authorization` bearer. (`positionsFonds`, which
woob calls, belongs to the employee-shareholding portal and 404s here.)

```text
listPositionsSalarieDispositifsDto[]          one entry per plan (dispositif)
  ├─ codeDispositif / idDispositif            stable external id
  ├─ libelleDispositif, typeDispositif        "PEG", "PERCO", "PER", …
  ├─ nomEntreprise                            employer
  ├─ mtBrut                                   plan total, EUR
  └─ positionsSalarieFondsDto[]               the plan's FUND CATALOGUE
       ├─ libelleFonds, codeIsin, codeFonds   (always present -- but codeIsin
       │                                       holds the AMF code on employer
       │                                       funds, hence codeFonds)
       ├─ nbParts, vl                         units, unit value  ─┐ null unless
       ├─ mtBrut                              line valuation      ├ the fund is
       └─ mtPMV                               unrealized P&L     ─┘ actually held
```

### Key files

- `services/amundi-auth/main.py` — FastAPI + Playwright sidecar: `/initiate`,
  `/complete`, `/positions`, `/health`
- `services/amundi-auth/positions_parser.py` — payload normalisation, kept free
  of FastAPI and Playwright so the rules that silently break are unit-testable
- `backend/.../port/AmundiPort.java` — typed contract (`InitiateResult`,
  `PlanData`, `Position`) and `AmundiErrorCode.java` — stable failure codes
- `backend/.../adapter/AmundiAdapter.java` — WebClient onto the sidecar, maps
  its `detail` onto `AmundiErrorCode`, wraps everything in `SyncException`
- `backend/.../service/AmundiSyncService.java` — job state machine, validation,
  reconciliation, atomic persistence
- `backend/.../controller/AmundiController.java` — `/api/amundi/*`
- `backend/.../model/AmundiSession.java`, `AmundiSyncStatus.java`
- `backend/.../config/AmundiSyncConfig.java` (single-thread executor),
  `AmundiSyncRecovery.java` (fails interrupted jobs at boot)
- `backend/src/main/resources/db/migration/V69__account_type_employee_savings.sql`,
  `V70__amundi_session.sql`
- `frontend/src/components/sync/AmundiPanel.tsx` (serves both the Sync tab and
  the Add-account modal), `frontend/src/pages/sync/AmundiTab.tsx`
- `frontend/src/features/sync/{api,hooks}.ts` — `amundiApi`, `useAmundi*`
- i18n namespace `sync.amundi.*` in all four locales

Reuses: `CryptoEncryption`, `AccountService.upsertSnapshot`,
`AccountHolding.providerValueEur` / `providerPnlEur` (added by Bourse Direct),
`SyncException` + `GlobalExceptionHandler`, `RateLimitConfig`, `SchedulerService`.

### Flow

```text
POST /api/amundi/auth/initiate
  └─ sidecar: Chromium → login form → detect second factor
       └─ {processId, mfaRequired, mfaType: APP_PUSH | SMS}

POST /api/amundi/auth/complete   (code omitted for APP_PUSH)
  └─ sidecar: submit SMS code, or hold while the user approves on their phone
       └─ sessionState = {storageState, bearer}
            └─ encrypt → persist → markQueued → 202

executor
  └─ markRunning ──▶ POST sidecar /positions   (outside any transaction)
       └─ validate + reconcile
            └─ one short tx: re-lock session, replace holdings, snapshot,
               markSuccessful
```

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| Playwright sidecar | A mandatory second factor on every login leaves nothing to paste, and the anti-robot token is computed in-page | Backend HTTP client with a user-pasted bearer — short-lived, so every sync would need a fresh paste |
| Harvest the bearer off the SPA's own traffic | Amundi keeps it in memory, so storage state alone does not re-authenticate; watching requests survives their front-end refactors | Reading a hard-coded localStorage key |
| One account per dispositif, new `EMPLOYEE_SAVINGS` type | Each plan has its own balance and lock-up regime; folding them into `SAVINGS` or `COMPTE_TITRES` would misreport both | Single Amundi account with grouped positions |
| Valuation comes from Amundi | Yahoo cannot quote an FCPE, ever | Deriving value from a live price feed |
| No OpenFIGI lookup | FCPEs are not in its universe; a call would burn requests and risk a wrong match | Resolving ISIN → ticker like Bourse Direct does |
| Reconcile in both sidecar and service | A total that disagrees with its lines means a partial read; overwriting good data with it erases real money | Trusting the payload |

See [the ADR](../decisions/2026-08-09-amundi-epargne-salariale-sidecar.md).

## Gotchas / Pitfalls

- **Fields must be typed, not filled.** `_type_into` sends real keystrokes.
  The inputs are masked and only register per keystroke: a bulk `fill()` lands
  as a *single* character, so the form stays invalid and the "Connexion" button
  stays disabled with nothing on screen to explain why. This cost an afternoon
  to find; do not "simplify" it back to `fill()`.
- **The consent overlay swallows clicks.** TrustCommander renders
  `#privacy-overlay` *after* the form paints, and it intercepts pointer events.
  It must be waited for and answered before anything is typed, or Playwright
  burns its entire timeout retrying a click that can never land.
- **The SPA paints after `domcontentloaded`.** A single glance at `#identifiant`
  is too early; `_wait_for_visible` polls instead. The same applies to the
  consent banner, which lands later still.
- **The captcha is proof-of-work, not a challenge.** It needs no interaction and
  no solving service. If it ever fails to produce a token — a network block to
  `friendlycaptcha.eu`, or Amundi swapping providers — that surfaces as
  `CAPTCHA_BLOCKED`, a distinct translated error rather than a
  wrong-credentials accusation.
- **An app push waits on a human.** The sidecar holds `/complete` open for up
  to 120 s while the page polls Amundi; `AmundiAdapter`'s validation timeout is
  150 s so it always outlives the sidecar's, and the frontend shows a waiting
  card rather than a form. Cutting this shorter would fail validations that
  were about to succeed.
- **`liveBalanceEur` needs the provider-valued fallback.** FCPEs are never
  Yahoo-priceable, so without `AccountService.PROVIDER_VALUED` every Amundi
  account reads as 0 € and the dashboard books the whole plan as a loss. That
  check is null-safe on purpose — `Set.of(...).contains(null)` throws, and most
  accounts have no provider.
- **FCPE units have no purchase price.** The cost basis is derived as
  `(mtBrut − mtPMV) / nbParts`. When Amundi reports no `mtPMV` the average buy-in
  is left null and the invested amount falls back to the plan total, rather than
  inventing a gain out of a missing field.
- **`codeIsin` is not always an ISIN.** On employer funds Amundi puts the AMF
  code there (`990000093539`), which fails the `[A-Z]{2}[A-Z0-9]{9}[0-9]` check.
  The ticker therefore falls to `codeFonds`, and only then to a slug of the
  label. Keying straight on the label was a real failure, not a theoretical one:
  the slug is capped at 30 characters and two share classes of one fund differ
  only in their last word, so `... THALES - A` and `... - B` both truncate to
  `EPARGNE-SOLIDAIRE-DYNAMIQUE-TH` and the sync refused the whole account.
  Two different funds still colliding on the label fallback are refused
  (`INVALID_DATA`) rather than merged — that guard sits behind the cascade, not
  in front of it.
- **`positionsSalarieFondsDto` is a catalogue, not a portfolio.** It lists every
  fund the dispositif *offers*; one the employee does not hold carries nulls
  throughout. On the validation account 275 of 283 lines were catalogue entries,
  so treating them as holdings rejects the entire payload. A line with neither a
  valuation nor units is skipped — but a line with units and *no* valuation still
  fails hard, because that is a partial read and silently dropping it would
  understate the account. Do not collapse those two cases.
- **A plan with a balance but no held lines is a partial read, not an empty plan** —
  it fails the sync. A plan with neither is simply skipped: the employee emptied it.
- **Accounts carry a long tail of dead plans.** The account this was validated
  against returned 33 dispositifs, nearly all closed and empty. `MAX_PLANS`
  therefore counts *funded* plans, after the empty ones are dropped; capping the
  raw list rejected the whole sync outright.
- **`mtBrut` (gross) is the valuation, not `mtNet`.** Net is after prélèvements
  sociaux on the gains; gross is the conventional portfolio value and is what
  reconciles against the plan total.
- **Single replica.** Pending authentication attempts live in the sidecar's
  process memory with a 600 s TTL, exactly as for Bourse Direct.

## Deliberately out of scope

- **Transaction history** (`api/individu/operations` — versements, abondement,
  intéressement, participation).
- **Lock-up / déblocage dates.** `positionSalarieFondsEchDto[].dtEcheance` is
  available upstream but `AccountHolding` has no column for it. Same for the VL
  date (`dtVl`): the valuation date is not the sync date, and FCPE unit values
  refresh weekly, so surfacing it properly needs a schema change. Both are the
  natural follow-up.
- **Manual transactions and CSV import** on Amundi accounts — the holdings-capable
  allowlists in `ManualTransactionService` and `TransactionImportService` are
  intentionally untouched, because snapshots are replaced atomically and manual
  edits would fight them.
- **Portals other than Amundi EE** (`epargnant.amundi-ee.com`). The same API
  serves `amundi-tc.com` and the partner-branded fronts; only the base URL differs.
- **Deliberately skipped:** the setup-wizard and admin-toggle registry
  (`SetupService.INTEGRATIONS`, `IntegrationsService`, `SetupStepIntegrations`,
  `setup-flow-store`, `IntegrationsSection`), following the IBKR precedent. The
  connector is reachable from the Sync page and the Add-account modal.

## Tests

- `AmundiSyncServiceTest` — 21 cases: job state machine, stale-session fencing,
  atomic replacement, reconciliation rejection keeping the last good snapshot,
  cost-basis derivation, ISIN-less fallback and its collision refusal, soft-deleted
  accounts not resurrected
- `AmundiAdapterTest` — 13 cases over a fake `ExchangeFunction`: the strict
  sidecar contract, every error-code mapping, the explicit null `code` for an
  app push, and that the validation timeout outlives the auth timeout
- `AmundiAdapterWiringTest` — Spring picks the production constructor
- `AmundiControllerTest` — member scoping, 202 on sync, rate limiting, an app
  push accepted without a code
- `AccountServiceTest` — an Amundi account with unpriceable FCPEs falls back to
  the provider total instead of collapsing to zero
- `services/amundi-auth/test_main.py`, `test_lifecycle.py` — 26 cases run inside
  the built image in CI: parser rules, session encoding, bearer capture, pending
  TTL, and the HTTP contract
- `frontend/src/pages/sync/AmundiTab.test.tsx` — SMS and app-push paths, error-code
  translation, background failure reporting

**CI cannot prove the live login.** No public runner can hold Amundi credentials
or approve a push notification, so the browser navigation itself is only ever
exercised by hand. The parser, the contract and every failure mapping are covered.

**Validated against a live Amundi account (2026-08-09).** End to end: the SPA
route, the consent overlay, the two-step form, keystroke entry, the
FriendlyCaptcha solve (~1 s, 627-char token), the app-push second factor, bearer
capture, and `dispositifsMulti` parsed into accounts. The account held 33
dispositifs of which **2 were funded** (a PEG and a PERCO), across 283 fund lines
of which **8 were held**. Plan totals reconciled against their held lines
**exactly** — `rel_diff = 0.0000000000` on both. A deliberately invalid account
number is correctly reported as `INVALID_CREDENTIALS`.

**Not observed, still defensive rather than proven:**
- the **SMS** second factor — that account uses app validation, so `_otp_inputs`
  remains an informed guess;
- the **ISIN-less fallback** and the **null-`mtPMV`** cost-basis path — every
  *held* line on that account had an ISIN, a unit value and a gain. The lines
  missing `codeIsin`/`vl` were all catalogue entries, which never become holdings.

## Links

- ADR: [2026-08-09 — Amundi via an isolated sidecar](../decisions/2026-08-09-amundi-epargne-salariale-sidecar.md)
- Related: [bourse-direct.md](./bourse-direct.md) — the precedent this follows
- Upstream reference: the `amundi` module in [woob](https://gitlab.com/woob/woob/-/tree/master/modules/amundi)
