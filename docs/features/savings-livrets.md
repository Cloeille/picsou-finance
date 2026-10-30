# Feature: Savings livrets (interest projection)

> Last updated: 2026-06-28
> Status: **Implemented** (shipped 2026-06-28).

## Context

Enable Banking (PSD2) syncs French savings books (*livrets*) as plain `CHECKING` accounts with
no product classification and no interest data. Two concrete problems follow:

1. **No classification.** A Livret A, LDDS, or LEP appears alongside current accounts — there
   is no way to tell them apart in the UI, and the savings allocation metric does not count them
   correctly.
2. **No interest visibility.** The user cannot see how much interest their livrets are accruing
   during the year; the only ground truth is the bank's annual credit, which appears as a
   transaction on Dec 31.

## Scope

**In scope (v1):**
- Products: `LIVRET_A`, `LDDS`, `LEP` (regulated French livrets, always net of tax, rates set by
  government decree) and `COMMERCIAL` (bank-specific livret; user-supplied rate, gross or net of
  the flat-rate PFU withholding tax).
- Name-based detection with a *suggest, never auto-apply* rule (user always confirms).
- Annual interest **projection** computed via the French *règle de la quinzaine*, displayed on the
  account detail page.

**Out of scope (deferred):**
- `PEL` / `CEL` (regulated savings plans with different capitalization mechanics — not in scope for v1).
- Reconciliation against the bank's real annual interest-credit transaction (follow-up feature that
  would automatically identify the Dec 31 interest row and validate the projection).

## How it works

### Detection (`SavingsBookDetector` / `NameBasedSavingsBookDetector`)

`SavingsBookDetector` is a pure interface with a single `suggest(accountName)` method. The sole
production implementation is `NameBasedSavingsBookDetector`, which:

1. Normalises the account name: NFD Unicode decomposition strips diacritics, then converts to
   upper-case (so "Livret A" and "LIVRET A" and "Livret À" all match).
2. Evaluates rules in precedence order — most specific first to avoid `LIVRET A` falling through
   to the generic `LIVRET` rule:
   - `LIVRET A` / `LIVRET_A` → `LIVRET_A` (certain)
   - `LDDS` / `LDD` / `DEVELOPPEMENT DURABLE` → `LDDS` (certain)
   - `LEP` / `EPARGNE POPULAIRE` → `LEP` (certain)
   - `LIVRET` / `CSL` / `COMPTE SUR LIVRET` → `COMMERCIAL` (**uncertain**)
3. Returns an `Optional<SavingsBookSuggestion>` with `(product, defaultAnnualRate, uncertain)`.
   `COMMERCIAL` matches carry `uncertain = true` and `defaultAnnualRate = null` (the user must
   supply their own rate). Regulated defaults come from `RegulatedRates` constants:
   `LIVRET_A = LDDS = 2.40 %`, `LEP = 3.50 %` (rates as of 2025-02-01 per service-public.fr /
   Banque de France).
4. **The detector never writes anything.** Classification is a suggestion only; the user confirms
   before a `SavingsInterestConfig` row is persisted.

### Suggestion flow (`SavingsService.getSuggestions`)

`GET /api/savings/suggestions` returns a list of `SavingsSuggestionResponse` for the current
member. Eligible accounts must be:
- bank-synced (`isManual = false`),
- not yet configured (no `savings_interest_config` row), and
- matching a name pattern.

The frontend surfaces these as a green banner on `AccountsPage` that deep-links into the first
suggested account. The `AccountDetailPage` also shows the `SavingsConfigSection` for any account
flagged by the detector (see `isSavings` gate below).

### Data model (Flyway **V44**)

`savings_interest_config` is a 1:1 companion table on `account` (same pattern as
`real_estate_metadata` and `debt` introduced in V19):

```sql
CREATE TABLE savings_interest_config (
    id           BIGSERIAL PRIMARY KEY,
    account_id   BIGINT         NOT NULL UNIQUE REFERENCES account(id) ON DELETE CASCADE,
    product      savings_product NOT NULL,          -- LIVRET_A | LDDS | LEP | COMMERCIAL
    annual_rate  NUMERIC(6,4)   NOT NULL,            -- percentage (e.g. 2.4000 for 2.40 %)
    rate_basis   rate_basis     NOT NULL DEFAULT 'NET',  -- GROSS | NET
    tax_rate_pct NUMERIC(5,2),                       -- only for COMMERCIAL + GROSS
    ceiling      NUMERIC(20,2),                      -- optional regulatory ceiling (informational)
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
```

Two PostgreSQL enums are created: `savings_product` and `rate_basis`.

### Reclassification on config save (`SavingsService.upsertConfig`)

When the user saves a savings config, `account.type` is immediately updated:
- `LEP` product → `AccountType.LEP`
- All other products → `AccountType.SAVINGS`

The config save *is* the user's ratified classification of the account. Enable Banking creates every
account as `CHECKING` by default; reclassification corrects this so the account groups correctly
across the whole app (allocation, net worth, filters) without requiring a separate rename step.

### Interest computation — French quinzaine rule (`SavingsInterestService`)

The year is divided into **24 quinzaines**: the 1st–15th and the 16th–end of every month.

Rules (per Banque de France regulation):
- **Deposit** starts earning from the **1st of the following quinzaine**.
- **Withdrawal** stops earning from the **1st of the quinzaine in which it occurs**.
- **Capitalisation** on **Dec 31** (annual, as for all French livrets).

Per-quinzaine formula:
```
interest_k = effective_capital_k × net_annual_rate_pct / 2400
```

`effective_capital_k = starting_capital + Σ deposits[Q0…Q(k−1)] − Σ withdrawals[Q0…Qk]`

(Deposits from quinzaine *k* earn starting from *k+1*; withdrawals in quinzaine *k* reduce capital
*immediately* from the start of *k*.)

**Starting capital** is resolved in order of preference:
1. Earliest `BalanceSnapshot` found within the current year.
2. Fallback: `account.current_balance − net transaction flows of the year` (reverse-engineering
   the opening balance from the closing balance).

**Tax model:**
- Regulated products (`LIVRET_A`, `LDDS`, `LEP`): the configured rate is already net of tax
  (French law makes regulated livrets tax-exempt). `GROSS` rate basis is rejected with HTTP 400.
- `COMMERCIAL + NET`: rate used as-is.
- `COMMERCIAL + GROSS`: effective net rate = `annualRate × (1 − taxRatePct / 100)`.
  `taxRatePct` defaults to 30 % (the flat-rate *Prélèvement Forfaitaire Unique* / PFU) when not
  explicitly set.

**Output** (`SavingsInterestProjection`):
| Field | Meaning |
|-------|---------|
| `estimatedInterestYtd` | Accrued interest Jan 1 → `asOf` date |
| `projectedInterestFullYear` | YTD + remaining quinzaines extrapolated at capital level as of `asOf` |
| `nextCapitalizationDate` | Dec 31 of the current year |
| `annualRatePct` | Effective net rate used (after tax conversion if GROSS) |
| `basis` / `netOfTax` | Rate basis and whether tax has already been applied |

### Critical guardrail — projection only, no balance writes

`SavingsInterestService` is annotated `@Transactional(readOnly = true)`. Projected interest
figures are **never written** to `account.current_balance` or `balance_snapshot`. Net worth stays
driven exclusively by the real synced balances.

The bank's real annual interest credit (a transaction on Dec 31) is the ground truth; it flows into
the account balance and net worth automatically via the normal sync path. Writing projected interest
on top of the real balance would double-count it.

### Flow

```
AccountsPage (suggestion banner)
        │  navigate to account detail
        ▼
AccountDetailPage (isSavings gate)
        │  renders SavingsConfigSection
        ▼
SavingsConfigSection
        ├─ product / rate / basis / tax / ceiling form
        │        PUT /api/accounts/{id}/savings-config
        │                │
        │                ▼ SavingsService.upsertConfig
        │                    ├─ validate (SavingsInterestService.validate)
        │                    ├─ save SavingsInterestConfig
        │                    └─ reclassify account.type → SAVINGS | LEP
        │
        └─ projection card (when config is saved)
                 GET /api/accounts/{id}/savings-interest
                         │
                         ▼ SavingsService.getProjection
                             └─ SavingsInterestService.computeProjection
                                 (quinzaine loop, read-only)
```

### API endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/savings/suggestions` | Suggestions for unconfigured, synced accounts |
| `PUT` | `/api/accounts/{id}/savings-config` | Create or update savings config |
| `DELETE` | `/api/accounts/{id}/savings-config` | Remove savings config (idempotent, 204) |
| `GET` | `/api/accounts/{id}/savings-interest` | Compute interest projection (404 if no config) |

`AccountResponse` includes a `savingsConfig` field (type `SavingsConfigDto`, nullable) populated
for any account that has a `savings_interest_config` row.

### Frontend

**`SavingsConfigSection.tsx`** (`frontend/src/features/savings/SavingsConfigSection.tsx`):
- Product selector, annual rate input, gross/net toggle (hidden for regulated products), tax rate
  input (shown only for COMMERCIAL + GROSS), optional ceiling field.
- Projection card below the config form, shown only once a config is saved. Displays YTD estimate,
  full-year projection, and next capitalisation date. Includes a disclaimer tooltip.
- Mobile-responsive grid (single-column on narrow viewports, three-column on `sm+`).

**`isSavings` gate** (`AccountDetailPage.tsx`):
```ts
const isSavings =
  account.type === 'SAVINGS' ||
  account.type === 'LEP' ||
  !!account.savingsConfig ||
  isSavingsSuggested   // detector flagged it but config not yet saved
```
This ensures the config section appears even on a freshly synced livret that is still typed
`CHECKING` (before the user has confirmed the classification).

**Suggestion banner** (`AccountsPage.tsx`):
Rendered when `GET /api/savings/suggestions` returns at least one item. Displays a count and
deep-links to the first suggested account.

**Demo mode** (`frontend/src/demo/index.ts`):
Handlers cover `GET /savings/suggestions`, `PUT /accounts/{id}/savings-config`,
`DELETE /accounts/{id}/savings-config`, and `GET /accounts/{id}/savings-interest` for demo
accounts 1 and 7 (a Livret A and a LEP). The interest handler derives the projection inline from
the stored demo `savingsConfig`.

**i18n**: all labels and messages translated for FR/EN under the `savings.*` namespace.

**`Array.isArray` guards**: `useSavingsSuggestions` response is always guarded with
`Array.isArray(savingsSuggestions)` before consuming — the demo interceptor can briefly return a
non-array during refetch, and `?? []` alone is not sufficient (a truthy `{}` passes that guard
silently).

### Key files

**Backend:**
- `controller/SavingsController.java` — REST entry points
- `service/SavingsService.java` — orchestration (config lifecycle, suggestion filter, projection delegation)
- `service/SavingsInterestService.java` — quinzaine interest engine (read-only)
- `service/SavingsBookDetector.java` — detector interface
- `service/NameBasedSavingsBookDetector.java` — name-pattern implementation
- `service/RegulatedRates.java` — official rate constants (LIVRET_A, LDDS, LEP)
- `model/SavingsInterestConfig.java` — JPA entity
- `model/SavingsProduct.java` — product enum
- `model/RateBasis.java` — GROSS / NET enum
- `dto/SavingsInterestProjection.java` — projection response record
- `dto/SavingsConfigDto.java` — config request/response record
- `dto/SavingsSuggestionResponse.java` — suggestion list item
- `dto/SavingsBookSuggestion.java` — internal suggestion from the detector
- `db/migration/V44__savings_interest_config.sql` — schema migration

**Frontend:**
- `frontend/src/features/savings/api.ts` — Axios wrappers
- `frontend/src/features/savings/hooks.ts` — TanStack Query hooks
- `frontend/src/features/savings/SavingsConfigSection.tsx` — config form + projection card
- `frontend/src/pages/accounts/AccountDetailPage.tsx` — `isSavings` gate
- `frontend/src/pages/accounts/AccountsPage.tsx` — suggestion banner

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Interest as projection, not written to balance | Avoids double-counting the real annual credit transaction; net worth stays authoritative | Accumulate projected interest into `current_balance` (double-counts, breaks net worth) |
| Side table `savings_interest_config` 1:1 on `account` | Same pattern as `real_estate_metadata` / `debt` (V19); extends `account` without schema churn | New `account_type` enum values per product (enum pollution, no room for per-account rate config) |
| Reclassify `account.type` on config save | The config *is* the classification; downstream services (allocation, filters) immediately see the correct type | Separate explicit reclassification step (extra round-trip, inconsistent window) |
| `SavingsBookDetector` interface + name-based impl | Swappable: a future ML-based detector can implement the same interface | Inline name-matching in the service (non-testable, non-swappable) |
| Suggest-never-auto-apply | Name matching is heuristic; uncertain matches (generic "Livret") must never auto-classify | Auto-apply on detection (would mis-classify commercial livrets with wrong product and rate) |
| Starting capital from earliest snapshot, fallback reverse-engineering | Snapshots exist when the account was imported before the current year; fallback handles new accounts | Require a manual opening-balance entry (bad UX, unnecessary if snapshot data is available) |

## Honest limit — projection accuracy

The interest projection is an **estimate**, not an authoritative figure. Sources of imprecision:

- **Transaction completeness**: Enable Banking's PSD2 window is approximately 90 days; movements
  before that window are invisible unless captured in a balance snapshot. Starting capital is
  approximated (earliest snapshot or reverse-engineered from the closing balance).
- **Rate assumption**: the configured rate is assumed constant for the whole year. Regulated rates
  can change mid-year by government decree; the user must update the rate manually.
- **Bank rounding**: the exact per-quinzaine rounding used by individual banks may differ slightly
  from HALF_UP.

The bank's real annual interest credit (Dec 31 transaction) remains the ground truth. A future
reconciliation feature (deferred) would automatically detect that transaction and validate the
projection against it.

## Gotchas / Pitfalls

- **CHECKING → SAVINGS seam.** Enable Banking creates every synced account as `CHECKING`. Until
  the user saves a savings config, the account sits in the CHECKING bucket — cashflow, allocation,
  and filters treat it as a current account. The `isSavings` gate on `AccountDetailPage` also
  surfaces the config section when the detector has flagged the account (before config is saved),
  so the user is nudged immediately after sync.
- **No balance writes — hard rule.** `SavingsInterestService` is `@Transactional(readOnly = true)`.
  Any future change to that service must preserve the read-only guardrail. Writing projected
  interest to the balance would cause the real annual credit to be counted twice in net worth.
- **Suggest-never-auto-apply is intentional.** The detector is heuristic; uncertain matches
  (generic "Livret", "CSL", "Compte sur Livret") must always be reviewed. Never add auto-apply
  logic to `NameBasedSavingsBookDetector` or `SavingsService.getSuggestions`.
- **Regulated products reject GROSS rate basis.** `SavingsInterestService.validate` throws
  `IllegalArgumentException` (→ HTTP 400) if `LIVRET_A / LDDS / LEP` is combined with
  `RateBasis.GROSS`. These products are tax-exempt by law; a gross/net distinction does not apply.
- **`isSavingsSuggested` must use `Array.isArray`.** `savingsSuggestions` from TanStack Query can
  momentarily be a non-array during a refetch (e.g. `{}`). `?? []` is not sufficient — `{}` is
  truthy. Use `Array.isArray(savingsSuggestions) && savingsSuggestions.some(…)`.

## Tests

**Backend:**
- `SavingsInterestServiceTest` — quinzaine engine: deposit timing (earns from next Q), withdrawal
  timing (stops at current Q), starting-capital resolution (snapshot path and fallback), GROSS→NET
  conversion, DEFAULT_PFU default, YTD vs. full-year split, capitalization date.
- `SavingsBookDetectorTest` — detection rules: positive matches for each product, uncertain flag
  for generic livret, word-boundary check (LEP does not match "TELEPORT"), accent-insensitive
  normalisation, null/blank input.
- `SavingsConfigValidationTest` — regulated + GROSS rejected; regulated + NET accepted; COMMERCIAL
  + GROSS + null taxRatePct defaults to 30 %.
- `SavingsControllerTest` — all four endpoints, member-scope enforcement, 400 on invalid config,
  404 when no config for projection.

**Frontend:**
- `frontend/src/features/savings/savings.test.tsx` — component tests for `SavingsConfigSection`.

## Links

- ADR: [`docs/decisions/2026-06-28-savings-livrets-interest-projection.md`](../decisions/2026-06-28-savings-livrets-interest-projection.md)
- Related feature: [`docs/features/revolut-pockets.md`](revolut-pockets.md) (similar suggest-never-auto-apply pattern)
- Related: `docs/features/bank-sync.md` — Enable Banking ingest pipeline
- Related: `docs/decisions/2026-06-28-revolut-pockets-reconstruction.md` — side-table pattern for account metadata
