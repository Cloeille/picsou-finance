# ADR: One sidecar session table and one sync cycle for ten banks

> Date: 2026-11-02
> Status: ✅ Active

## Context

Ten sidecar banks (Amex, Amundi, Bourse Direct, BoursoBank, Caisse d'Epargne,
CIC, CMES, CORUM, Fortuneo, Sofidy) each had their own session table, entity,
status enum, repository, and a `*SyncService` that contained its own copy of the
same queue, execute, commit and recover cycle. The copies had drifted: only
Fortuneo logged why boot recovery failed before startup stopped, only Caisse d'Epargne
recorded an unreadable stored session, and the tests for those shared scenarios
existed for some banks and not others (issue #337, part of #326).

## Decision

- **One table, `sidecar_session`**, keyed by `(bank, member_id)`, created by
  `V124__sidecar_session.sql`. V124 copies the ten tables row for row,
  including the encrypted state as stored, and drops them in the same
  transaction. One entity `SidecarSession`, one enum `SidecarSyncStatus`, one
  `SidecarSessionRepository`.
- **The database enforces each bank's own rules.** The CHECK on
  `(bank, last_sync_error)` has one branch per `SidecarBank`, generated from the
  enum's declared codes. `encrypted_credentials` is allowed only for BOURSO, CIC
  and CMES. FAILED requires an error code.
- **One sync cycle, `SidecarSyncTemplate`.** It queues, runs, commits or fails a
  job in three short transactions fenced on the session row, recovers
  interrupted jobs at boot, and reports to the daily scheduler. Each bank keeps
  only its `SidecarSyncStrategy`: `fetch` (outside any transaction), `commit`
  (inside it), and optional `afterCommit` and `onFailure`.
- **One boot runner, `SidecarSyncRecovery`.** A recovery that fails stops
  startup, as each per-bank runner already did since none caught the
  exception. What changes: every bank now logs which recovery failed, as only
  Fortuneo did.
- **One contract suite, `SidecarSyncContractTest`.** It runs the shared
  scenarios against every bank's real service.

Degiro, Revolut and Trade Republic keep their own tables. Their schemas differ,
and they are left to a follow-up.

## Alternatives considered

### Keep ten tables, share only the Java code

- **Pros**: no data migration.
- **Cons**: ten entities with the same fields keep the generic code behind an
  interface with ten implementations, and every new rule still needs ten
  migrations.

### A generic table with a JSON column for bank-specific data

- **Pros**: no column is null for most banks.
- **Cons**: only two columns are bank-specific (`encrypted_credentials` and
  `unsupported_contracts`), and JSON would move their constraints out of the
  database.

## Reasoning

The ten tables already had the same columns, so one table removes code without
widening any type. Generating the per-bank CHECK from `SidecarBank` keeps
"which codes may this bank record" in one place, read by both Java and
PostgreSQL.

## Trade-offs accepted

- One migration rewrites ten tables. It runs in one transaction and was
  rehearsed on a copy of a production database: every session came out with
  the same state bytes, status, error and dates, and a real Caisse d'Epargne
  sync ran on the migrated row.
- A bank that does not recover at boot (CORUM, Sofidy) or is not scheduled
  (CORUM, Sofidy, Caisse d'Epargne) is listed in the contract suite, so a change
  to either list fails a test.

## Consequences

- Adding a sidecar bank means one `SidecarBank` constant, one strategy, and
  one CHECK branch generated from the enum.
- Rolling back past V124 needs a database restore. V124 drops the old tables.
  Upgrade with the [safe release deployment](../features/release-deployment.md),
  which takes the backup first.
- The CORUM and Sofidy tables never enforced "FAILED requires an error code".
  V124 repairs a row that breaks it before the copy (stale error cleared,
  missing one set to `INTERNAL_ERROR`) instead of failing the boot.
