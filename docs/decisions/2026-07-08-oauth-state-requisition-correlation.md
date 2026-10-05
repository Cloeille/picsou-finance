# ADR: OAuth `state` nonce correlates bank callbacks to their requisition

> Date: 2026-07-08
> Status: ✅ Active

## Context

The Enable Banking OAuth callback (`GET /api/sync/complete?code=...`) used to
bind the returned code to **the most recent CREATED requisition of the current
member** — a guess, not a correlation. Four failure modes followed:

- Two connections initiated back-to-back (Revolut then BNP): completing the
  first stores its session on the wrong requisition (wrong institution name,
  logo, and `provider` on every upserted account).
- Abandoned CREATED requisitions accumulate and silently capture future
  callbacks.
- The `state` parameter the adapter already sent to Enable Banking was never
  stored or validated — the standard OAuth anti-CSRF protection was absent.
- Admin impersonation broke: initiating under `?memberId=X` created the
  requisition under X, but the callback resolved the admin's own member.

The `ALREADY_AUTHORIZED` replay fallback additionally refreshed "the latest
LINKED requisition" of **any** institution, so a replayed Revolut callback
could resync BNP and report success.

## Decision

- `SyncService.initiateConnection` (and `reconnect`) generate a random UUID,
  pass it to the connector as OAuth `state`, and persist it on the requisition
  (`requisition.oauth_state`, unique index, migration V51).
- The frontend forwards the `state` query param from the redirect to
  `GET /api/sync/complete`.
- `completeConnection` resolves the requisition **by state** (single-use: the
  nonce is cleared the moment the code exchange succeeds) and derives the
  member from the resolved requisition, not the caller context.
- The `ALREADY_AUTHORIZED` fallback is scoped to the same `institutionId`.
- A callback whose state is missing **or unknown** falls back to the latest
  CREATED requisition **without a stored nonce** (pre-migration rows sent an
  old-format `appId_timestamp` state that was never persisted, so they can't
  match `findByOauthState`). Post-migration rows always carry a nonce and can
  never be captured by a crafted state; the fallback self-retires once legacy
  CREATED rows are gone.

## Alternatives considered

### Keep the latest-CREATED guess (status quo)

- **Pros**: no schema change, no port signature change.
- **Cons**: wrong-bank binding, no CSRF protection, broken impersonation, orphan requisitions capture callbacks.

### Signed JWT as state (stateless)

- **Pros**: no DB column; the state carries the requisition id and member.
- **Cons**: more moving parts (signing key, expiry handling), harder to revoke a single nonce, no uniqueness guarantee against replay without a store anyway.

## Reasoning

A persisted random nonce is the textbook OAuth pattern, fits the existing
requisition table (one live nonce per pending connection), and fixes all four
failure modes with one mechanism. The repository lookup is deliberately not
member-scoped: the unguessable single-use nonce IS the credential, and the
member is derived from the row it resolves to.

## Trade-offs accepted

- One more nullable column + unique index on `requisition`.
- The legacy fallback (nonce-less rows only) preserves the old guessing
  behavior for pre-migration requisitions; it becomes unreachable once those
  rows are completed or deleted, and the code path can then be removed.
- `BankConnectorPort.initiateConnection` gained a `state` parameter (Powens
  adapter forwards it when provided).

## Consequences

- `V51__requisition_oauth_state.sql`; `Requisition.oauthState` (`@JsonIgnore`).
- `RequisitionRepository.findByOauthState` (documented exception to the
  member-scoping rule) and
  `findByStatusAndMemberIdAndInstitutionIdOrderByCreatedAtDesc`.
- `SyncController.complete` accepts an optional `state` request param;
  `BankSyncTab` forwards it from the callback URL.
- Tests: resolution by state, unknown state rejection, same-institution replay
  scoping, impersonation member derivation (`SyncServiceTest`).
