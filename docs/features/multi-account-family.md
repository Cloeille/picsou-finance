# Feature: Multi-account family system

> Last updated: 2026-11-06 (password-less members, activation with SSO, group-managed admins)

## Context

Allows a single Picsou instance to host multiple family members under one admin account. The admin can create managed profiles (children, spouse without login), and optionally upgrade them to full login accounts via an activation link. Data is scoped per member; sharing is configurable.

## How it works

### Identity model

Two-tier identity: `AppUser` (authentication) → `FamilyMember` (domain identity).

- `FamilyMember` — profile with displayName, avatarColor, `isManaged` flag
- `AppUser` — login credentials, links to exactly one FamilyMember via `member_id`
- `managed=false` → has login (active member)
- `managed=true` → no login, managed by admin (child, shared account)
- An `AppUser` may have **no password** (`password_hash = ''`) when it signs in only through SSO.
  Its linked identities live in `user_identity`, keyed by `(issuer, subject)`. See
  [sso-login.md](./sso-login.md).

Every data entity (Account, Goal, Requisition, etc.) has a `member_id` FK. All service methods take `Long memberId` and use repository methods like `findByIdAndMemberId()`.

### Member scoping (UserContext)

`UserContext.currentMemberId()` is called by every controller to scope queries.

When an admin switches to a managed profile in the UI, the frontend sends `?memberId=X` on every API request (Axios interceptor in `api-client.ts`). The interceptor **only adds the param when the logged-in user is an admin** (`useAuthStore … role === 'ADMIN'`), mirroring the backend guard so a stale persisted `activeMemberId` can never scope a regular member's requests. The backend `UserContext.getMemberIdOverride()` then checks:
1. Is the current user an admin?
2. Is there a `memberId` query param?
3. If both → return the override memberId instead of the admin's own

Non-admin users always use their own memberId (override is ignored server-side, and the client no longer sends it).

### Client-state isolation across the login boundary

`profile-store` persists `activeMemberId` to **localStorage** (`picsou-profile`) and the TanStack Query cache holds user-agnostic keys (`['dashboard', range]`, `['accounts']`, …). On a **shared family browser** both survive a logout, so without an explicit reset the next person's login would carry the previous user's impersonation target and see their cached balance/history (staleTime is 60 s).

A single helper, `resetClientState(queryClient)` in `frontend/src/lib/reset-client-state.ts`, calls `useProfileStore.getState().reset()` **and** `queryClient.clear()`. It runs on **every** auth-boundary crossing:

- **logout** — `useLogout` (`onSettled`, `frontend/src/features/auth/hooks.ts`);
- **a login that establishes a session** — the non-MFA branch of `useLoginWithRememberMe` and the post-verify branch of `useVerifyMfa` (`frontend/src/features/mfa/hooks.ts`), i.e. the exact moment the new identity is written to the auth store.

It is deliberately **not** called on the `mfaRequired` branch — no session exists yet there (the user is mid-challenge). The reset is the authoritative client-side privacy boundary; the `?memberId` admin-gating and backend scoping are defense-in-depth. Its server-side complement — severing a *different* user's leftover cookies during login so they can't silently re-authenticate — lives in [mfa-and-remember-me.md](./mfa-and-remember-me.md#cross-identity-session-bleed-at-login).

### History endpoints reject foreign / unscoped accounts

`HistoryService.buildHistory / buildIntradayHistory / buildPnl` validate that **every** requested account belongs to the caller's member via a shared `assertOwnership(accounts, memberId)` helper. A `null` memberId is treated as a programming error (`IllegalArgumentException`) rather than a "skip validation" signal — closing a latent path that previously returned accounts unscoped. Foreign accounts raise `ResourceNotFoundException` (404, not 403, so existence isn't leaked).

### Sharing system

Members choose what to share via `SharingSettings` per resource type (`ACCOUNT`, `GOAL`):

- `NONE` — private (default)
- `ALL` — share everything of that type
- `MANUAL` — share only specific resources via `shared_resource` table

Manual sharing is validated on both sides of the boundary. `FamilyService.updateSharingSettings`
only accepts `ACCOUNT` and `GOAL`, resolves requested manual ids with member-scoped
repository methods (`findByIdInAndMemberId(...)`), and rejects the request before
deleting prior shares if any id is missing, foreign, or null. `FamilyViewService`
also reads manual shares through member-scoped batch lookups, so stale or manually
corrupted `shared_resource` rows are omitted instead of leaking another member's
account or goal.

The `FamilyViewService` aggregates shared data for the family dashboard. The shared net
worth leaves out a Revolut pocket whose parent wallet is also listed, since the parent's
balance already carries it (see `revolut-sidecar.md`, pocket nesting); the pocket keeps its
own card. A pocket shared without its wallet still counts.

### Profile activation flow

1. Admin creates a managed profile (no login). From the **Admin → Members** section
   the "Create user" button does steps 1–2 in one geste (profile + login), then
   shows the activation link; *Settings → Family* still creates a bare profile.
2. Admin clicks "Create login" → generates an activation token stored on `AppUser`.
   The login **username is derived from the display name** ("Jean Dupont" →
   `jean.dupont`, slugified + made unique with a `.N` suffix) instead of the legacy
   `member_<id>`. See `UsernameDeriver` (extracted from `FamilyService`, also used for SSO
   accounts) and [admin-page.md](./admin-page.md).
3. Admin shares the activation link (`/activate/{token}`)
4. The managed person opens the link, sets a password
5. `AppUser.isManaged` is set to false, `isActivated` to true
6. The person can now log in independently

When SSO is enabled, the activation page also offers **"Activate with SSO"** (after the same
acknowledgement). The invitation token, never the identity's email or username, selects the
account: the IdP identity is bound to it, the account is activated **without a password** (any
old password and local TOTP are removed), and the person lands signed in. Both paths finish in
`ActivationService.complete`, so they bump `tokenVersion` and revoke Remember-Me sessions alike.

Once activated, a managed member becomes **independent** (`isManaged=true && hasLogin && activated`). The admin can no longer regenerate their activation link (`generateActivationToken` rejects activated users) or **impersonate** them. The admin **can** still delete an independent member — see "Member deletion" below.

### Admin access boundary (independent members are private)

Activation also revokes the admin's ability to **impersonate** the member. As soon
as a member has set their own password (`activated=true`), the admin can no longer
switch into their profile and browse their data:

- **Backend (authoritative):** `UserContext.getMemberIdOverride()` honors `?memberId=X`
  only when X is the admin's own member id, or when member X has no activated login
  (a true managed profile: child / no-login / login created but not yet activated).
  Overriding to an **activated** member throws `403 "Cannot access an independent
  member's data"`. This is the single choke point through which every controller
  scopes data (`currentMemberId()`), so all endpoints are covered at once.
- **Frontend (UX):** admin users get a visible sidebar profile switcher. It is
  built from `selectSwitchableMembers()` (`frontend/src/features/family/members.ts` =
  `managed && !activated`) so independent members cannot be impersonated from
  the UI. Non-admin and demo sessions do not call `/family/members`; they keep
  the simple Settings account link. Independent members remain listed in Family
  settings and on the family dashboard.

This is an automatic confidentiality guarantee, not a toggle. **Voluntary sharing is
unaffected** — anything an independent member chooses to share via `SharingSettings`
still reaches the admin through `FamilyViewService` (family dashboard). The admin's
password-reset capability also stays intact and does not re-open access: a reset keeps
`activated=true` (it only issues a fresh token), so the boundary holds.

### Password reset by an admin

`POST /api/family/members/{id}/reset-password` (admin-only) issues a fresh
`activationToken` with a 7-day expiry on an existing `AppUser` row. The token
reuses the `/activate/{token}` flow so the user lands on the same screen and
sets a new password. The current `passwordHash` is **not** cleared — the old
credential keeps working until the user actually completes the reset, so a
mistakenly-issued reset link does not lock anyone out. Distinct from
`POST /members/{id}/activate`, which deliberately rejects already-activated
users (`FamilyService.generateActivationToken` line 92).

`FamilyMemberResponse` now exposes `loginName` (= `AppUser.username` or `null`)
so the admin UI can show both the display name and the login side-by-side.

A reset link is also how a **password-less** (SSO-only) member gets a password back if the IdP
is unavailable: activating it sets a password like any reset. It only ever sets a password: the
SSO activation (`intent=activate`) refuses a token whose account is already activated
(`ACTIVATION_INVALID`), because it would otherwise replace the password and the second factor with
the IdP identity of whoever holds the link. A reset does not unlink SSO identities either; the admin
does that with **Unlink SSO** below.

### Sign-in methods and SSO-managed admins

`FamilyMemberResponse` also carries `hasPassword` and `ssoLinked`; Admin → Members shows them as
badges. **Unlink SSO** (`DELETE /api/admin/members/{memberId}/identities`, admin only, never on
oneself) removes every identity of a member, bumps their `tokenVersion` and revokes their
Remember-Me sessions, e.g. after an IdP account was compromised. A password-less member then
needs a reset link to sign in again.

With `SSO_ADMIN_GROUP` set, the IdP group manages admins: a group member without an account gets
a new admin account at their first SSO sign-in, and every SSO sign-in promotes or demotes
(password sign-ins do not). Deleting such an admin in Picsou deletes their data, but if they are
still in the group they come back as a new, empty admin at their next SSO sign-in: remove people
at the IdP.

### Member deletion

`DELETE /api/family/members/{id}` (admin-only) removes a member **and, by DB
cascade, all owned data** — every owner table's `member_id` FK is declared
`ON DELETE CASCADE` (see `V20`), so deleting the member wipes accounts, goals,
requisitions, bank/crypto sessions, wallets, debts, sharing settings and
contributions.

**Deletion order matters (JPA flush ordering).** `FamilyService.deleteMember`
loads the target's `AppUser` into the persistence context to run the last-admin
guard. `AppUser.member` is a **non-nullable** `@OneToOne` on `member_id`, so if we
deleted the member while that *managed* `AppUser` still referenced it, Hibernate
would throw `TransientObjectException` **at flush — before any SQL runs**, so the
DB `ON DELETE CASCADE` never gets a chance. The method therefore deletes the loaded
`AppUser` explicitly first, *then* the member:

```java
AppUser user = userRepository.findByMemberId(id).orElse(null);
// ... guards ...
if (user != null) userRepository.delete(user);  // the only managed entity referencing the member
memberRepository.delete(member);                // DB ON DELETE CASCADE wipes the rest
```

Other owned rows (accounts, goals, sessions…) stay **unloaded** in this transaction,
so no managed entity references the removed member at flush and the DB cascade
handles them.

**Regression coverage (and why it's not a flush test).** The ideal guard would
persist a member + linked `AppUser`, flush, and assert `deleteMember` succeeds —
reproducing `TransientObjectException` on the pre-fix code. That isn't feasible
here: the schema is PostgreSQL-specific (`TIMESTAMPTZ`, native enum types, Flyway
`ON DELETE CASCADE`) and does not replay on the H2 in-memory database used for
tests, and the project deliberately avoids Testcontainers (see
`docs/conventions/testing.md`) — every test is a Mockito unit test. The regression
is therefore guarded by `FamilyServiceTest.deleteMember_withLogin_deletesUserBeforeMember`,
which uses Mockito `InOrder` to assert `userRepository.delete(user)` is invoked
**before** `memberRepository.delete(member)`. It cannot reproduce the flush
exception (no live `flush()`), but it locks the fix: it fails the instant the two
deletes are reordered back to the buggy sequence.
`deleteMember_managedWithoutLogin_deletesOnlyMember` covers the no-login branch
(only the member is deleted, no `AppUser`).

An activated (independent) member is **no longer protected** from deletion — the
admin who runs the instance may remove anyone. `FamilyService.deleteMember(id,
requesterMemberId)` keeps only two guards so the instance stays usable:

1. **Self-delete** — an admin cannot delete their own member (`id == requesterMemberId`
   → 403 "Cannot delete your own account"), avoiding a lock-out.
2. **Last administrator** — if the target's `AppUser.role == ADMIN` and
   `AppUserRepository.findAllByRoleForUpdate(ADMIN)` returns at most one row, deletion is
   refused (403 "Cannot delete the last administrator"). The admin rows are locked
   (`PESSIMISTIC_WRITE`) while counting, the same lock the SSO role sync takes, so a deletion
   and a concurrent SSO demotion cannot remove the last two admins.

Frontend: both member-management UIs (`frontend/src/pages/admin/sections/MembersSection.tsx` and
`frontend/src/pages/settings/FamilySettingsPage.tsx`) show the delete action for any non-self member.
Because deletion of an **activated** member is irreversible and destroys private
data, the shared `ConfirmDialog` is given a `confirmPhrase` (the member's display
name) that the admin must retype before the destructive call is enabled.

### Username change

`PATCH /api/auth/username` updates the username of the currently authenticated user:
1. Validates format and uniqueness (409 if taken)
2. Updates `AppUser.username`
3. **Re-issues both JWT cookies** (access + refresh) with the new username as subject

Step 3 is critical: without it, the next request would fail because the old JWT still contains the old username, which no longer exists in the DB.

### Key files

**Backend:**
- `backend/src/main/java/com/picsou/model/FamilyMember.java` — member profile entity
- `backend/src/main/java/com/picsou/model/AppUser.java` — login entity (username, passwordHash, role, member FK)
- `backend/src/main/java/com/picsou/model/SharingSettings.java` — per-resource sharing level
- `backend/src/main/java/com/picsou/model/SharedResource.java` — individual resource sharing (MANUAL mode)
- `backend/src/main/java/com/picsou/service/UserContext.java` — request-scoped helper, handles memberId override for admins
- `backend/src/main/java/com/picsou/service/FamilyService.java` — member CRUD, activation tokens, sharing settings
- `backend/src/main/java/com/picsou/service/FamilyViewService.java` — family dashboard aggregation
- `backend/src/main/java/com/picsou/controller/FamilyController.java` — `/api/family/members` (admin-only), `/api/family/sharing`
- `backend/src/main/java/com/picsou/controller/FamilyViewController.java` — `/api/family/dashboard`
- `backend/src/main/java/com/picsou/controller/AuthController.java` — `/api/auth/activate/{token}`

**Frontend:**
- `frontend/src/stores/profile-store.ts` — `activeMemberId`, `viewMode` (own/managed/family)
- `frontend/src/features/family/hooks.ts` — TanStack Query hooks for members, sharing, dashboard
- `frontend/src/features/family/api.ts` — API functions
- `frontend/src/features/family/members.ts` — `selectSwitchableMembers()` helper used by the admin switcher; excludes independent members
- `frontend/src/components/layout/AppSidebar.tsx` — admin profile switcher; non-admin/demo bottom account row links to Settings
- `frontend/src/lib/api-client.ts` — Axios interceptor adds `?memberId=X` only when an **admin** has a managed profile active
- `frontend/src/lib/reset-client-state.ts` — `resetClientState(queryClient)`: resets profile-store + clears the Query cache; the one shared auth-boundary reset
- `frontend/src/features/auth/hooks.ts` — `useLogout` calls `resetClientState` (logout side)
- `frontend/src/features/mfa/hooks.ts` — `useLoginWithRememberMe` / `useVerifyMfa` call `resetClientState` before writing the new identity (login side)
- `frontend/src/pages/settings/FamilySettingsPage.tsx` — member management + sharing config UI
- `frontend/src/pages/family/FamilyDashboardPage.tsx` — shared overview
- `frontend/src/pages/activation/ActivationPage.tsx` — activation flow for new members
- `frontend/src/pages/settings/SettingsPage.tsx` — username edit inline (pencil → input → save)

**Migrations:**
- `V20__create_family_system.sql` — creates family_member, sharing_settings, shared_resource, goal_contributor tables; adds member_id to all owner tables
- `V21__migrate_existing_data.sql` — creates admin member, links existing user, assigns all data to admin
- `V22__make_member_id_not_null.sql` — makes all member_id columns NOT NULL

### Flow: admin switching to managed profile

The app shell exposes a visible profile switcher for admins.

```
Admin selects a managed profile from the sidebar switcher
  → setActiveMember(memberId) in profile-store
  → queryClient.invalidateQueries() marks cached queries stale
  → Axios interceptor reads activeMemberId from store
  → Adds ?memberId=X to every outgoing API request
  → UserContext.currentMemberId() sees admin + memberId param → returns X
  → All queries scoped to member X
  → The switcher UI updates to show the managed profile

Admin selects their own account
  → setActiveMember(null) in profile-store
  → queryClient.invalidateQueries()
  → Axios interceptor stops adding ?memberId
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Two-tier identity (AppUser + FamilyMember) | Separates auth concerns from domain identity; allows managed profiles without login | Single User entity with flags |
| `?memberId` query param for admin override | Zero changes to existing controllers (they all call `userContext.currentMemberId()`) | Custom header, separate endpoint, request-scoped bean |
| `@JsonIgnore` on all lazy entity relations | Prevents LazyInitializationException when entities are serialized directly (open-in-view is disabled) | Open-in-view: true, DTOs for every entity (too many for existing code) |
| `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` on PG native enums | Required for Hibernate to properly cast Java enum → PG enum on writes | Default `@Enumerated(STRING)` sends varchar, PG rejects it |
| TanStack Query invalidation on profile switch | Simple, works for all queries at once | Adding memberId to every query key (invasive, many files to change) |

## Gotchas / Pitfalls

- **LazyInitializationException**: With `open-in-view: false`, any entity with `@ManyToOne(fetch = LAZY)` that gets serialized directly by a controller will 500. All lazy relation fields have `@JsonIgnore` as a safeguard. New entities with lazy relations MUST add `@JsonIgnore`.
- **PG native enum columns**: PostgreSQL enum types (`sharing_level`, `requisition_status`, `account_type`) require `@JdbcTypeCode(SqlTypes.NAMED_ENUM)` + `columnDefinition`. Without it, Hibernate sends a varchar and PG rejects the INSERT/UPDATE.
- **Admin-only endpoints**: `FamilyController` member management methods call `requireAdmin()`. If a non-admin hits these, they get 403, and the global Axios interceptor turns any GET 403 into a full-app redirect to `/error/403`. The frontend must therefore **never call these for non-admins**: `FamilySettingsPage` renders `MemberManagement` only when `isAdmin`, and admin pages sit under `RequireAdmin`. Regression: a non-admin's first login used to fire `/api/family/members` from the sidebar → 403 → `/error/403`.
- **Stale auth store**: The frontend caches user info (including role) at login time. Changing role in DB requires re-login to take effect in the UI.
- **Username change requires token rotation**: `PATCH /api/auth/username` must re-issue the JWT cookies. If you only update the DB row, the existing tokens still carry the old username — the filter can't find the user → immediate 401 on next request.
- **`isIndependent` in frontend must include `managed`**: The display logic for a member's status in `FamilySettingsPage` uses `isIndependent = member.managed && member.hasLogin && member.activated`. Without `managed`, admin users (who are also `hasLogin && activated`) would show "Compte indépendant" instead of "Administrateur".
- **Member deletion cascades everything — but order matters**: `FamilyService.deleteMember(id, requesterMemberId)` must delete the loaded `AppUser` **before** the member. `AppUser.member` is a non-nullable `@OneToOne`; deleting the member while a managed `AppUser` still references it makes Hibernate throw `TransientObjectException` at flush (before any SQL), so the DB `ON DELETE CASCADE` never runs. After the `AppUser` is removed, `memberRepository.delete(member)` cascades the rest (accounts, goals, sessions…) at the DB level. A Mockito `InOrder` test (`FamilyServiceTest.deleteMember_withLogin_deletesUserBeforeMember`) locks the ordering; a true flush test isn't possible because the PG-specific schema (`TIMESTAMPTZ`, native enums) won't replay on H2 (see "Member deletion" and `docs/conventions/testing.md`). Only two 403 guards remain — self-delete and last-admin (see "Member deletion"). Activated members are deletable (the old "cannot delete an activated member" rule was removed). The frontend requires retyping the display name (`ConfirmDialog confirmPhrase`) before deleting an activated member, and surfaces any backend error inside the dialog via `formatApiError` (see `docs/conventions/error-handling.md`).
- **Cannot impersonate an activated member**: `UserContext.getMemberIdOverride()` throws 403 when an admin's `?memberId=X` targets an activated (independent) member other than themselves. The sidebar switcher hides them via `selectSwitchableMembers`, but the backend is the authoritative guard — never rely on the frontend filter alone.
- **Yahoo Finance null closes**: Yahoo can return `null` in historical price arrays for non-trading days. Must check `close == null` before unboxing to avoid NPE.
- **Profile switch cache**: TanStack Query cache is global. The sidebar switcher must invalidate queries on every effective switch; otherwise the old member's data persists visually.
- **Cross-user leak on a shared browser**: query keys are not scoped by user and `activeMemberId` is persisted to localStorage, so every auth-boundary crossing MUST `queryClient.clear()` + `useProfileStore.getState().reset()` — centralised in `resetClientState` (`frontend/src/lib/reset-client-state.ts`), wired into `useLogout` (logout) and `useLoginWithRememberMe`/`useVerifyMfa` (login). Otherwise the next person to log in on the same device briefly sees the previous user's balance/history (and, for an admin re-login, the stale `?memberId` returns another member's real data). Regression that prompted the fix: a member reported seeing "un solde et historique qui n'est pas du tout le sien" on first login. A related **identity** bleed — entering one user's credentials but landing on *another* user's account — is the server-side cookie-severing case in [mfa-and-remember-me.md](./mfa-and-remember-me.md#cross-identity-session-bleed-at-login).

## Tests

- `GoalServiceTest` — goal CRUD scoped by memberId
- `HistoryServiceTest` — history scoped by memberId, incl. `buildHistory_rejectsAccountsOwnedByAnotherMember` and `buildHistory_rejectsNullMemberId`
- `api-client.test.ts` (frontend) — `?memberId` is attached only for admins, never for a non-admin with a stale `activeMemberId`
- `frontend/src/features/mfa/hooks.test.ts` (frontend) — login-side `resetClientState`: wipes the cache + impersonation target on a non-MFA login and on MFA verify, and performs **no** reset on the `mfaRequired` branch
- `UsernameDeriverTest` — username derivation; `ActivationServiceTest` — the shared activation sink
- `AdminIdentityControllerTest` — admin unlink of a member's SSO identities
- `FamilyServiceTest` — activation/reset, `hasPassword`/`ssoLinked`, and **member deletion**:
  `deleteMember_withLogin_deletesUserBeforeMember` (Mockito `InOrder` guard for the
  `TransientObjectException` fix), `deleteMember_managedWithoutLogin_deletesOnlyMember`,
  `deleteMember_self_throwsForbidden`, `deleteMember_lastAdmin_throwsForbidden`,
  `deleteMember_nonLastAdmin_deletes`, `deleteMember_activatedMember_deletes`,
  `deleteMember_notFound_throws`

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md` (extended for multi-member)
