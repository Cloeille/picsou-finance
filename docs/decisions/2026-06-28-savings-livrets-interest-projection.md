# ADR: Detect and classify savings books; project interest without writing to balance

> Date: 2026-06-28
> Status: ✅ Active

## Context

Enable Banking (PSD2) syncs French savings books (*livrets*) as plain `CHECKING` accounts.
No product information is returned: a Livret A, LDDS, or LEP is indistinguishable from a current
account in the raw sync data. Two concrete problems follow:

1. **No classification.** The savings allocation metric cannot count livrets correctly; they
   appear in the wrong bucket on every screen that groups by account type.
2. **No interest visibility.** Interest accrues continuously under the French *règle de la
   quinzaine* but the bank surfaces only the annual credit on Dec 31 as a transaction. The user
   has no way to see how much interest has accrued during the year without leaving Picsou.

A solution must not disturb the net-worth figure, because the bank's real annual interest credit
already flows into `account.current_balance` via the normal sync path. Any projections must
therefore be additive and informational, never written to the balance.

## Decision

**Detect livrets by account name and project their interest as an informational display only —
never write projected interest to `account.current_balance` or `balance_snapshot`.**

Concretely:

- A `savings_interest_config` side table (Flyway **V44**) holds product type, annual rate, and
  tax parameters for any account the user identifies as a savings book. The table is 1:1 with
  `account` (same pattern as `real_estate_metadata` and `debt` in V19).
- A `SavingsBookDetector` interface (name-based implementation: `NameBasedSavingsBookDetector`)
  inspects account names and returns suggestions. It never writes anything; the user must confirm
  before a config row is persisted.
- `SavingsInterestService` computes year-to-date and full-year projected interest using the French
  quinzaine rule and the configured rate. The service is `@Transactional(readOnly = true)` and
  may not be changed to perform writes.
- Saving a config reclassifies `account.type` to `SAVINGS` (or `LEP` for the LEP product),
  correcting the Enable Banking default of `CHECKING`.
- `GET /api/savings/suggestions` surfaces unconfirmed suggestions; `PUT / DELETE
  /api/accounts/{id}/savings-config` manage the config lifecycle; `GET
  /api/accounts/{id}/savings-interest` returns the projection.
- The frontend renders the projection as an estimate with an explicit disclaimer; the bank's
  real Dec 31 interest credit remains the authoritative figure.

## Alternatives considered

### Write projected interest into `account.current_balance` (accrual)

- **Pros**: single balance figure reflects both principal and accrued interest; user sees a more
  "live" balance.
- **Cons**: the bank's real annual interest credit (a transaction on Dec 31) flows into
  `current_balance` via the sync path. Writing projected interest on top of the real balance
  double-counts it, corrupting net worth for the entire year until the projection is unwound.
  Unwinding is complex (distinguishing projected from real interest is error-prone). Rejected.

### New `AccountType` enum values per product (LIVRET_A, LDDS, LEP)

- **Pros**: type-level discrimination without a separate config table; enum is already used for
  allocation and net-worth grouping.
- **Cons**: each new livret product requires an enum value, a Flyway `ALTER TYPE`, and updates
  to every switch/if-chain that already handles `AccountType`. The enum carries no room for
  per-account rate or tax parameters. Rejected in favour of a side table that stores all
  rate-related fields without touching the existing type system (except adding `LEP` which had
  a legitimate type-level distinction).

### Maintain a rate-history table (rate changes over time)

- **Pros**: exact interest computed even across mid-year government rate changes.
- **Cons**: regulated rates change at most twice a year (historically once); tracking the full
  history adds schema complexity and a maintenance burden that is disproportionate to the
  imprecision already introduced by approximate starting capital and 90-day PSD2 transaction
  windows. The user can update the rate manually when a change occurs. Rejected; ship editable
  defaults instead.

### Accrue only manually-added livrets (opt-in for manual accounts)

- **Pros**: avoids PSD2 window limitations; the user controls the transaction history.
- **Cons**: the majority of livrets in Picsou are bank-synced. Restricting the feature to manual
  accounts would exclude the primary use case. Deferred as a potential enrichment path, not the
  primary design.

### Inline name matching in `SavingsService` (no interface)

- **Pros**: fewer files.
- **Cons**: makes the detection logic untestable in isolation and non-swappable. A future
  ML-based or server-side classification could not be wired in without rewriting the service.
  Rejected; the `SavingsBookDetector` interface costs one file and buys full replaceability.

## Reasoning

The projection-only approach is the only design that preserves net-worth integrity. The bank's
real annual interest credit is ground truth — projections must never compete with it in the
balance column. A read-only projection that surfaces an estimate during the year is genuinely
useful without creating any reconciliation problem.

The side-table pattern (`savings_interest_config` 1:1 with `account`, ON DELETE CASCADE) directly
mirrors `real_estate_metadata` and `debt` (V19) — an established Picsou convention for per-account
metadata that does not belong in the `account` row itself. It avoids enum pollution while storing
rate, basis, tax rate, and ceiling per account.

Reclassifying `account.type` on config save is deliberate: the config *is* the user's ratified
classification. Without reclassification, the account would stay `CHECKING` in the allocation
service, savings filters, and net-worth grouping, which would defeat the first problem the feature
set out to solve.

The suggest-never-auto-apply rule prevents the heuristic detector from mis-classifying accounts.
Name matching is reliable for exact product names (`LIVRET A`, `LDDS`, `LEP`) but produces
uncertain matches for generic labels (`Livret`, `CSL`, `Compte sur livret`). Uncertain matches
carry `uncertain = true` in `SavingsSuggestionResponse`; the user always confirms.

## Trade-offs accepted

- **Projection is an estimate, not authoritative.** Sources of imprecision: (a) Enable Banking's
  PSD2 window is approximately 90 days — movements before that window are invisible unless
  captured in a balance snapshot; (b) starting capital is approximated from the earliest snapshot
  or reverse-engineered from `current_balance − net flows`; (c) the configured rate is assumed
  constant for the whole year; (d) per-bank rounding may differ. The bank's Dec 31 credit is the
  only ground truth.
- **User must update the rate manually on government decree changes.** Regulated rates change
  once or twice a year; a prompt in the frontend (deferred) could nudge the user, but the rate
  is not auto-updated.
- **Reclassification overrides the Enable Banking default silently.** `account.type` is mutated
  on `upsertConfig` commit; there is no explicit audit trail beyond the `updated_at` timestamp
  on `savings_interest_config`.

## Consequences

- Flyway V44 adds two PostgreSQL enums (`savings_product`, `rate_basis`) and the
  `savings_interest_config` table (BIGSERIAL PK, UNIQUE FK on `account_id`, ON DELETE CASCADE).
  No schema change to the `account` or `transaction` tables.
- `AccountResponse` gains a `savingsConfig` field (nullable `SavingsConfigDto`).
- New endpoints: `GET /api/savings/suggestions`, `PUT /api/accounts/{id}/savings-config`,
  `DELETE /api/accounts/{id}/savings-config`, `GET /api/accounts/{id}/savings-interest`.
- `SavingsInterestService` is permanently `@Transactional(readOnly = true)`. Any future change
  to this class must preserve the read-only guardrail.
- `AccountType.LEP` is added to the existing enum (LEP is the only product with enough
  regulatory distinctiveness to warrant a dedicated type; the others collapse into `SAVINGS`).
- Feature note: [`docs/features/savings-livrets.md`](../features/savings-livrets.md).
