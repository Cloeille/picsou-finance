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
  `V142__sidecar_session.sql`. V142 copies the ten tables row for row,
  including the encrypted state as stored, and drops them in the same
  transaction. One entity `SidecarSession`, one enum `SidecarSyncStatus`, one
  `SidecarSessionRepository`.
- **The database enforces each bank's own rules.** The CHECK on
  `(bank, last_sync_error)` has one branch per `SidecarBank`, generated from the
  enum's declared codes. `encrypted_credentials` is allowed only for BOURSO, CIC,
  CMES and CORUM. FAILED requires an error code. CORUM keeps the CORUM Life
  ambiguous count V124 added to `corum_session`.
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

Degiro and Revolut keep their own tables. Their schemas differ, and they are
left to a follow-up. Trade Republic joined `sidecar_session` later, in V149.

**Banque Populaire joined in V147 (#478).** Its table (V134) is created after
V142 on a fresh database, so V142 could not take it. V147 adds the bank to the
bank and error-code CHECKs, adds a `region` column (set, and one of
`BanquePopulaireRegion`, for `BANQUE_POPULAIRE` rows; null for every other
bank, `ck_sidecar_session_region`), allows `unsupported_contracts` for it as for
Caisse d'Epargne, copies every `banque_populaire_session` row as stored and
drops that table. Both BPCE banks keep the `BpceSyncEngine` cycle rather than
`SidecarSyncTemplate`; `SidecarSession` is now the only `BpceSession`.

## Alternatives considered

### Keep ten tables, share only the Java code

- **Pros**: no data migration.
- **Cons**: ten entities with the same fields keep the generic code behind an
  interface with ten implementations, and every new rule still needs ten
  migrations.

### A generic table with a JSON column for bank-specific data

- **Pros**: no column is null for most banks.
- **Cons**: only a few columns are bank-specific (`encrypted_credentials`,
  `unsupported_contracts`, then `last_sync_life_ambiguous` and `region`), and
  JSON would move their constraints out of the database.

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
  (Sofidy, Caisse d'Epargne) is listed in the contract suite, so a change
  to either list fails a test.

## Consequences

- Adding a sidecar bank means one `SidecarBank` constant, one strategy, and
  one CHECK branch generated from the enum.
- Rolling back past V142 needs a database restore. V142 drops the old tables.
  It is a destructive change, so it needs the planned offline upgrade of the
  [safe release deployment](../features/release-deployment.md): stop the old app
  first, or a session it writes after the copy is dropped with its table.
  V147 drops `banque_populaire_session` the same way: no rollback past it
  without a restore, and the same offline upgrade.
- The CORUM and Sofidy tables never enforced "FAILED requires an error code".
  V142 repairs a row that breaks it before the copy (stale error cleared,
  missing one set to `INTERNAL_ERROR`) instead of failing the boot.
