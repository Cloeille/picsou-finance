# Feature: Admin Page (instance settings)

> Last updated: 2026-05-29 (create-user-from-Admin + login derived from display name)

## Context

After the [first-launch Setup Wizard](./setup-wizard.md) finishes, the user has no way
to change CORS origins, the `Secure` cookie flag, Enable Banking credentials, or
toggle integrations on/off without editing rows in `app_setting` by hand. The Admin
page exposes those same `SetupService` writers behind a role-gated `/admin` route so
a self-host admin can reconfigure the instance from the browser.

The page also surfaces a **Members** section that lets the admin **create a user**
(name field + "Create user" button — provisions a managed profile *and* its login
in one step, then shows the activation link to share), see every family member with
their display name and login (username), invite a managed profile via a one-shot
activation link, reset an active user's password by sending the same
`/activate/{token}` link with a fresh token, and delete a non-activated managed
profile. The actions reuse `FamilyService` writers behind the existing
`/api/family/**` admin gate (`requireAdmin()`).

The login **username is derived from the display name** ("Jean Dupont" →
`jean.dupont`), replacing the legacy `member_<id>` scheme. `FamilyService`
slugifies the name (NFD accent strip, lowercase, non-alphanumeric runs → a single
dot, trimmed) and appends a numeric suffix (`.2`, `.3`, …) until unique. The
"Create user" flow is the two existing endpoints chained client-side
(`POST /api/family/members` then `POST /api/family/members/{id}/activate`) via the
`useCreateUserWithLogin` hook — no new endpoint.

## How it works

A single `AdminController` (`/api/admin/**`) wraps existing `SetupService` writers
(`writeSecurity`, `writeEnableBankingConfig`) and `IntegrationsService.enable / disable`.
URL-level guard: `.requestMatchers("/api/admin/**").hasRole("ADMIN")` in
`SecurityConfig`. The frontend mirrors the gate with a `RequireAdmin` route guard
that redirects non-admins to `/error/403`.

The `GET /settings` endpoint returns one `AdminSettingsResponse` containing the three
sub-sections so the page renders from a single query — no fan-out, single
`adminKeys.settings()` cache key invalidated by every mutation.

### Key files

Backend:
- `backend/src/main/java/com/picsou/controller/AdminController.java` — endpoints:
  `GET /settings`, `PUT /settings/security`, `PUT /settings/enablebanking`,
  `PATCH /settings/integrations/{key}?enabled=...`, plus EB keypair management
  `POST /settings/enablebanking/keypair` (idempotent generate) and
  `/keypair/import` (422 on a bad PEM). `GET /settings` reads EB credentials from
  the *resolved* `EnableBankingConfigProvider` (DB-then-env), not raw DB rows, so
  env-configured installs report their real values rather than blanks.
- `backend/src/main/java/com/picsou/dto/AdminSettingsResponse.java` — record with
  nested `SecuritySettings` and `EnableBankingSettings` (the latter carries a
  `privateKeyPresent` boolean so the UI can flag a missing key-file).
- `backend/src/main/java/com/picsou/dto/AdminSecurityRequest.java` — `@NotNull
  @Size(min=1) List<String> allowedOrigins`, `boolean secureCookies`.
- `backend/src/main/java/com/picsou/dto/AdminEnableBankingRequest.java` — three
  `@NotBlank` fields.
- `backend/src/main/java/com/picsou/config/SecurityConfig.java:57` — the
  `hasRole("ADMIN")` matcher.
- `backend/src/main/java/com/picsou/service/SetupService.java` — re-used writers
  (`writeSecurity` line 151, `writeEnableBankingConfig` line 168, `readSetting`
  line 186, `INTEGRATIONS` constant line 37).

Frontend:
- `frontend/src/pages/admin/AdminPage.tsx` — page shell, single
  `useAdminSettings()` query, sections aligned with the main app column rather
  than centered in a narrow wrapper.
- `frontend/src/pages/admin/sections/SecuritySection.tsx` — RHF +
  `useFieldArray` for CORS origins, `Controller` + `Switch` for the secure-cookie
  flag, Zod schema requires at least one origin.
- `frontend/src/pages/admin/sections/EnableBankingSection.tsx` — RHF over a
  `FIELDS` array, Zod with `.url()` on `redirectUri`.
- `frontend/src/pages/admin/sections/IntegrationsSection.tsx` — five hardcoded keys
  (`enablebanking, boursobank, traderepublic, finary, crypto`) toggled via
  `useToggleIntegration`.
- `frontend/src/pages/admin/sections/MembersSection.tsx` — create-user form (name
  + "Create user" button) on top, then the list of family members with avatar,
  display name, login, derived status; per-member actions: create / regenerate
  activation link, reset password, reset 2FA, delete. Generated/activation link is
  shown inline with a copy button; no email is sent (self-hosted, admin transmits
  manually).
- `frontend/src/features/family/hooks.ts` — `useCreateUserWithLogin` chains
  `createMember` + `generateActivationLink` (one loading/error state, returns the
  activation link); plus `useFamilyMembers`, `useDeleteMember`,
  `useGenerateActivationLink`, `useResetMemberPassword`.
- `backend/src/main/java/com/picsou/service/FamilyService.java` — `deriveUsername`
  slugifier (used by `generateActivationToken`) + member CRUD / token writers.
- `frontend/src/features/admin/api.ts` — `adminApi` (typed endpoints).
- `frontend/src/features/admin/hooks.ts` — `useAdminSettings`,
  `useUpdateSecurity`, `useUpdateEnableBanking`, `useToggleIntegration` (all
  invalidate `adminKeys.settings()`).
- `frontend/src/features/auth/guards.tsx` — appended `RequireAdmin`
  (`user.role !== 'ADMIN'` → `/error/403`).
- `frontend/src/app/routes.tsx` — lazy route
  `{ path: 'admin', element: <SuspensePage><RequireAdmin><AdminPage /></RequireAdmin></SuspensePage> }`.
- `frontend/src/components/layout/AppSidebar.tsx` — admin-only `DropdownMenuItem`
  with the `Shield` icon, before Logout.
- `frontend/src/pages/settings/SettingsPage.tsx` — admin-only "Admin" section
  card after Family.
- `frontend/src/i18n/locales/{fr,en}.json` — `admin.*` namespace + `nav.admin`,
  `settings.adminSection*`.

### Flow

```
User clicks "Admin" (sidebar dropdown OR Settings page card)
    │
    ▼
RequireAdmin guard (user.role === 'ADMIN' ?)  ── no ──► /error/403
    │ yes
    ▼
AdminPage mounts → useAdminSettings() → GET /api/admin/settings
    │
    ▼
Render { SecuritySection, EnableBankingSection, IntegrationsSection }
    │
    ▼
On submit / toggle ──► PUT or PATCH ──► invalidate adminKeys.settings()
                                          ──► sections re-render with fresh data
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| URL-based `hasRole("ADMIN")` in `SecurityConfig` | One line, no annotations on every endpoint, hard to forget. | `@PreAuthorize("hasRole('ADMIN')")` on each method (easy to omit on a new endpoint). |
| Re-use `SetupService` writers | Same persistence path as the wizard → one source of truth for `app_setting` semantics (CSV joining for origins, key naming). | Dedicated `AdminService` duplicating the upsert logic (drift risk). |
| Single `GET /settings` aggregating all three sections | Page renders from one query, one cache key, trivial invalidation. | Three separate endpoints (`/security`, `/enablebanking`, `/integrations`) — more calls, three cache keys to invalidate. |
| `PATCH /settings/integrations/{key}?enabled=bool` | Each toggle is independent; matches the per-integration mental model. | A single `PUT` accepting the full integrations map (a stale UI state could clobber another concurrent change). |
| Frontend `RequireAdmin` mirrors the backend gate | Admin link must not show for non-admins, even if they could be tricked into navigating. | Pure backend gate with a 403 page — UI shows a dead link. |
| `useFieldArray` for CORS origins | The wizard's Security step also takes a list; UX consistency, plus easy add/remove without manual array splice. | Comma-separated text input (parses ambiguously, no per-row validation feedback). |

## Gotchas / Pitfalls

- **`hasRole("ADMIN")` requires the `ROLE_` prefix in the security context.**
  `JwtAuthenticationFilter` builds authorities as `"ROLE_" + user.getRole().name()`,
  so `hasRole("ADMIN")` (which strips the prefix) lines up. If anyone refactors the
  filter to store bare role names, the admin guard silently becomes a no-match and
  every admin user starts getting 403s.
- **CORS origins are stored as a single CSV string** in `app_setting`
  (`cors.allowed-origins`). `getSettings` does `Arrays.asList(s.split(","))` — if the
  value is empty the result is a single empty string, not an empty list. The frontend
  Zod schema (`.min(1)` on each entry) catches the empty-string row, but if you
  bypass the form and POST a single `[""]` you'll write back the literal empty value.
  `SetupService.writeSecurity` is the only safe writer; do not duplicate the CSV
  formatting elsewhere.
- **The integration toggle shows the *effective* state, not the raw flag.**
  `getSettings` reports `integrationsService.isEffectivelyEnabled(key)`, which ORs
  the stored `app_setting` flag with a cheap per-integration config probe: Enable
  Banking → `EnableBankingConfigProvider.isConfiguredLenient()` (creds + key
  present, no parsing); Trade Republic / Finary → a login-session row exists;
  BoursoBank (network-only) / crypto (nothing to detect) → fall back to the flag.
  This is why an install configured purely via `.env` / docker-compose — which
  never ran the wizard, so the flag stayed `false` — still shows its integrations
  *on*. Consequence: an env-configured integration **cannot be turned off** from
  the toggle (the connector reads env directly and ignores the flag, so a `PATCH`
  to `false` snaps back to `on` on the next `GET`). The switch reflects reality
  rather than a stale boolean — to disable, remove the env config / delete the
  session.
- **Toggling an integration off does not delete its credentials** (TR session,
  Bourso session, EB requisitions, encrypted exchange API keys). It only flips the
  `app_setting` flag. Re-enabling restores prior connectivity. By design — flipping
  a sync provider off temporarily must not nuke the user's stored secrets.
- **Changes to `cors.allowed-origins` and `app.secure-cookies` take effect without
  restart** because `DynamicCorsConfigurationSource` reads them from `app_setting`
  on each request and the cookie helper reads `secure-cookies` per response. If
  someone reverts to `@Value`-based static config, the admin page will appear to
  succeed but nothing will actually change until the container restarts.
- **`PATCH` must remain in CORS allowed methods.** The integrations toggle uses
  `PATCH`; if `SecurityConfig.corsConfigurationSource` ever loses `PATCH` from its
  methods list, every toggle will preflight-fail in the browser with no server-side
  log entry.
- **Settings page card and sidebar dropdown both gate on `user?.role === 'ADMIN'`
  client-side.** This is a usability-only check; the real authorization is the
  backend matcher. Don't be tempted to remove the duplicate gate "to DRY it" — they
  guard different things (link visibility vs. endpoint access).
- **The username is derived only when the login is first created**
  (`generateActivationToken`, `existingUser == null` branch), not at managed-profile
  creation — the `AppUser` doesn't exist until then. Renaming a member afterwards
  (`updateDisplayName`) does **not** change an already-issued login username; that's
  intentional (a login identifier is stable). The slug uses only `[a-z0-9.]`, a
  subset of the `[A-Za-z0-9._-]` setup pattern, so it's always a legal username.
- **"Create user" is two chained requests, not atomic.** If the second call
  (`/activate`) fails after the profile is created, you get a managed profile with no
  login — recoverable from the same screen via the per-member "Create login" button.
  No new backend endpoint was added precisely to reuse this idempotent recovery path.

## Tests

- `backend/src/test/java/com/picsou/controller/AdminControllerTest.java` —
  Mockito tests covering the four endpoints: `getSettings` aggregation,
  `updateSecurity` and `updateEnableBanking` delegation to `SetupService`,
  `toggleIntegration` with `enabled=true/false`. URL-level role gate is enforced by
  Spring Security and not exercised by these unit tests; coverage relies on the
  `SecurityConfig` matcher being correct.
- `backend/src/test/java/com/picsou/service/FamilyServiceTest.java` — Mockito tests
  for the username derivation in `generateActivationToken`: simple slug, accent +
  space normalization (`Jean Dupont` → `jean.dupont`, `Élodie` → `elodie`),
  collision suffix (`alice` → `alice.2`), and the blank/non-alphanumeric fallback
  (`!!!` → `user`).
- No dedicated integration test for the role gate (manual verification — same
  approach as `security-cors-cookies.md`). A future MockMvc slice test asserting a
  USER-role request is rejected with 403 would close the gap.
- Frontend coverage is `bun run typecheck` + `bun run build` + manual flow only;
  no Vitest component tests for the admin sections yet.

## Links

- Related ADR:
  [`docs/decisions/2026-04-25-admin-page-reuses-setup-writers.md`](../decisions/2026-04-25-admin-page-reuses-setup-writers.md)
  — why we reuse the wizard's writers behind a URL-level role gate.
- Sister feature (initial config): [`setup-wizard.md`](./setup-wizard.md) —
  same writers, different surface.
- CORS / cookie semantics this page edits:
  [`security-cors-cookies.md`](./security-cors-cookies.md).
- Frontend error display used by every admin section:
  [`frontend-error-display.md`](./frontend-error-display.md).
