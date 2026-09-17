# Feature: Self-service account deletion (GDPR Art. 17)

> Last updated: 2026-09-06

## Context

An authenticated user can erase their login and owned data from **Settings →
Danger zone**. The previous admin-only endpoint refused self-deletion, so a user
could not complete this operation without another administrator.

Picsou applies two outcomes:

- A member or an administrator with another active administrator is fully deleted.
- The final active administrator keeps the remaining active admin login. Picsou erases the
  administrator's member-owned data, revokes every session and access key, and
  requires a new login.

Inactive administrator rows do not count as an alternative login. Both the
advisory preview and the locked write path use this rule.

The final-administrator outcome preserves the `AppUser` id, username, password
hash, `ADMIN` role, activation state, MFA secret, and unused recovery codes. It
does not preserve financial data, connections, or access keys.

## Request flow

```text
Settings → Danger zone → Delete my account
   │
   ├─ GET /api/me/deletion-impact
   │    └─ advisory mode for the dialog copy
   │
   ├─ re-auth: TOTP when MFA is enabled, password otherwise
   ├─ client confirmation: retype the current username
   └─ DELETE /api/me
        ├─ rate limit: 3 attempts per user each hour
        ├─ ReAuthService verifies the current factor
        ├─ FamilyService locks all ADMIN rows in id order
        ├─ FamilyService reloads the authenticated user by user id
        ├─ DELETE_ACCOUNT: delete AppUser, then FamilyMember
        └─ RESET_LAST_ADMIN:
             ├─ create an empty FamilyMember
             ├─ repoint the same AppUser and increment tokenVersion
             ├─ clear activation links
             ├─ revoke persistent sessions and delete created access keys
             └─ delete the old FamilyMember
```

`DELETE /api/me` returns the outcome that committed. The advisory GET never
selects the write path. If two administrators erase their accounts at the same
time, the second transaction reads the administrator set again after it obtains
the lock.

## Why the final administrator is reset

Deleting the final `AppUser` would leave no supported way to create an
administrator. The setup wizard cannot safely solve this problem. Reopening an
unauthenticated setup flow would let the first caller claim the instance.

The reset keeps authentication separate from business ownership. Picsou creates
a new empty `FamilyMember`, points the existing `AppUser` to it, and deletes the
old member. PostgreSQL then applies the existing `ON DELETE CASCADE` graph to
accounts, goals, transactions, provider sessions, wallets, debts, ownership
shares, contributions, and other member-owned rows.

The replacement member keeps the previous display name and avatar color. It has
no financial or integration data.

## Authentication invalidation

The final-administrator reset invalidates every existing authentication path:

- Incrementing `AppUser.tokenVersion` invalidates access and refresh JWTs.
- `PersistentSessionService.revokeAllForUser` revokes Remember Me sessions.
- Each persistent session records the `tokenVersion` that created it. Validation,
  trusted-device checks, and refresh-series checks reject an older generation,
  closing races with a login or silent restoration already in flight.
- `AccessKeyRepository.deleteAllByCreatedBy` removes every access key created by
  the retained user, including an inconsistent cross-member row.
- MFA challenge JWTs carry `tokenVersion`. `/api/auth/mfa/verify` rejects a
  challenge created before the reset.
- The response clears the access, refresh, persistent, and MFA challenge cookies.

MFA remains enabled. The administrator signs in again with the same password and
the same TOTP setup or an unused recovery code.

## Concurrency rule

Both self-deletion and admin member deletion call
`AppUserRepository.findAllByRoleForUpdate(ADMIN)` before they reload or delete a
user. The query uses `PESSIMISTIC_WRITE` and orders rows by id.

With administrators A and B, the first transaction locks both rows and deletes
one account. The second transaction waits. After the first commit, PostgreSQL
returns the remaining administrator to the second transaction, which resets that
identity instead of deleting it. No committed path can remove the final admin.

Any future role promotion, demotion, administrator activation change, or
administrator deletion must use the same lock protocol.

## Pending import data

Both deletion outcomes publish `MemberDataDeletedEvent` for the removed member
inside the database transaction. After commit, the Finary XLSX, Finary API, and
transaction CSV caches remove every preview belonging to that member. A rollback
keeps the previews available.

Preview entries are bound to their originating member, and CSV entries also
retain their account binding. Execute validates ownership and expiry before
processing data. Cache registration checks that the member still exists under
the same monitor used by the deletion listener, so a preview finishing after
deletion cannot repopulate the cache.

## HTTP and frontend contract

`DELETE /api/me` uses a JSON request body because the re-authentication factor is
part of the command. Axios sends the body through the `data` option.

The backend returns RFC 7807 failures with stable codes:

| Status | `code` | Meaning |
|---|---|---|
| `401` | `REAUTH_FAILED` | The password or TOTP code was rejected. |
| `429` | `ACCOUNT_DELETION_RATE_LIMITED` | The user exhausted the deletion bucket. |

The Axios interceptor does not refresh or replay a request that returns
`REAUTH_FAILED`. A generic authentication 401 can still refresh the expired
session before the controller receives the command.

The dialog waits for both MFA status and deletion impact. It does not treat a
missing MFA response as disabled MFA. While the DELETE is pending, the dialog
disables its fields and blocks the close button, Cancel, Escape, and outside
clicks. After success, the frontend clears all local auth, profile, and query
state and opens `/login` without a second logout request.

The dialog scrolls within 90% of the viewport height so its controls remain
reachable on small screens and in landscape orientation. The action buttons are
stacked, and long translated labels wrap within the dialog width.

The dialog warns that an owned account or goal also disappears for family
members who shared or contributed to it. Data owned by other members remains.

## Key files

Backend:

- `backend/src/main/java/com/picsou/controller/MeDeletionController.java`
- `backend/src/main/java/com/picsou/service/FamilyService.java`
- `backend/src/main/java/com/picsou/model/AccountDeletionMode.java`
- `backend/src/main/java/com/picsou/dto/AccountDeletionResponse.java`
- `backend/src/main/java/com/picsou/config/JwtUtil.java`
- `backend/src/main/java/com/picsou/controller/AuthController.java`

Frontend:

- `frontend/src/pages/settings/SettingsPage.tsx`
- `frontend/src/pages/settings/security/DeleteAccountDialog.tsx`
- `frontend/src/features/account-deletion/api.ts`
- `frontend/src/features/account-deletion/hooks.ts`
- `frontend/src/lib/api-client.ts`

## Tests

- `MemberPreviewCacheTest` covers member binding, expiry, selective cleanup, and
  preview registration after deletion. `PreviewCacheDeletionEventTest` checks
  all three Spring listeners on commit and rollback.
- `FamilyServiceTest` covers locked re-authentication, full deletion order, and
  final-admin member replacement, including inactive alternative administrators.
- `AccountDeletionPostgresIntegrationTest` creates a real PostgreSQL delete race.
  It checks the admin invariant, member cascades, MFA retention, session
  revocation, access-key deletion, and survival of unrelated member data.
- `PersistentSessionTokenVersionMigrationTest` migrates a populated PostgreSQL
  schema from V79 to V82 and checks the session-generation backfill and constraint.
- `JwtUtilTest` and `AuthControllerTest` cover stale MFA challenges.
- `MeDeletionControllerTest` covers the typed response, rate-limit problem, and
  principal-derived user id.
- `FamilyControllerTest` prevents `?memberId=` from changing the requester identity.
- `api-client.test.ts` proves that `REAUTH_FAILED` causes one DELETE and no refresh.
- `DeleteAccountDialog.test.tsx` covers both outcomes, MFA loading and error states,
  stable error codes, and pending-dialog behavior.

## Links

- [`data-export.md`](./data-export.md) documents the export-before-delete flow.
- [`multi-account-family.md`](./multi-account-family.md) documents family ownership.
- [`mfa-and-remember-me.md`](./mfa-and-remember-me.md) documents MFA and sessions.
- `Zoeille/picsou-finance#118` tracks the feature request.
