# Feature: Admin password recovery

> Last updated: 2026-11-04 (password-less SSO admins)

## Context

Picsou is self-hosted and single-tenant. There is no email-based "forgot password" flow because instances may run on intranets without SMTP. If the operator loses the admin password, the instance becomes inaccessible. This feature provides a break-glass recovery path that requires shell access to the host.

## How it works

The operator sets `ADMIN_RECOVERY_ENABLED=true` in the environment and restarts the application. On boot, `AdminRecoveryRunner` (an `ApplicationRunner`) detects the flag, regenerates an activation token for every `ADMIN`-role user, and prints a WARN-level banner with the activation URL.

The operator opens the URL in a browser and follows the existing `/activate/{token}` flow (the same flow used for inviting new family members) to set a new password. Afterwards, the operator must set `ADMIN_RECOVERY_ENABLED=false` and restart.

While `activated = false`, the account is unusable: **login is rejected with `403`** and **token refresh returns `401`** (the cookies are cleared). The admin's old password technically still matches (recovery does not clear the hash), but login is blocked anyway, so recovery **must** be completed through the activation link — there is no way around it. This consistency is enforced in `AuthController.login` / `AuthController.refresh`, matching the `isActivated()` gate already present in `JwtAuthenticationFilter` and `PersistentTokenAuthFilter`.

The 403 message depends on whether the recovery flag is still set, so a fumbling operator is told exactly what to do:

- **`ADMIN_RECOVERY_ENABLED=true` + the user is a deactivated `ADMIN`** → "Admin recovery mode is active: the password reset link was printed to the server console/logs — open it to set a new password. Remember to set `ADMIN_RECOVERY_ENABLED=false` before the next restart." This check runs **before** the password comparison, so the operator gets the hint even if they mistype the old password, and the response is identical for right and wrong passwords (no password oracle). The reset link itself is never echoed in the HTTP response — only its location (the console) — so nothing secret leaves over HTTP.
- **Any other deactivated account** (flag off, or a non-admin pending activation) → the generic "Account not activated. Use your activation link to set a password."

**Password-less SSO admins.** An admin created through SSO (first admin, admin-group provisioning)
or one who turned password login off has an empty password hash. Recovery works the same way and
is the way back in when the IdP is broken or misconfigured: the activation link sets a **new
password** on the account, after which the password form works again. While recovery is on, an
SSO sign-in of a deactivated admin is refused with `ssoError=ADMIN_RECOVERY_ACTIVE` (the same
message as the password form), so the activation link remains the only path. See
[sso-login.md](./sso-login.md).

### Key files

- `backend/src/main/java/com/picsou/config/AdminRecoveryRunner.java` — the boot-time runner
- `backend/src/main/java/com/picsou/controller/AuthController.java#activate` — consumes the token
- `backend/src/main/java/com/picsou/service/ActivationService.java` — shared activation sink (token cleared, `tokenVersion` bump, Remember-Me revocation, `admin.recovery.completed` audit)
- `backend/src/main/java/com/picsou/service/SetupAuditService.java` — records the audit entries
- `backend/src/main/resources/application.yml` — `app.admin-recovery.enabled` binding
- `.env.example` — `ADMIN_RECOVERY_ENABLED` variable documented

### Flow

```
operator: set ADMIN_RECOVERY_ENABLED=true
operator: restart picsou
   ↓
AdminRecoveryRunner.run()
  ├─ find all UserRole.ADMIN users
  ├─ for each: new SecureRandom token, expires now+1h
  ├─ user.activated = false
  ├─ user.tokenVersion++  (invalidates pre-existing sessions)
  ├─ log WARN banner with URL
  └─ audit "admin.recovery.token-generated"
   ↓
operator: opens URL in browser
   ↓
POST /api/auth/activate/{token}
  ├─ user.passwordHash = bcrypt(newPassword)   (also for a password-less SSO admin)
  ├─ user.activated = true
  ├─ user.tokenVersion++  (revokes any access/refresh JWT minted before the reset)
  ├─ persistentSessionService.revokeAllForUser(user.id)  (wipes Remember-Me cookies)
  └─ audit "admin.recovery.completed"  (only if user.role == ADMIN)
   ↓
operator: set ADMIN_RECOVERY_ENABLED=false, restart
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Boot-time runner only | No runtime API surface → cannot be triggered by a compromised session | Admin endpoint guarded by env flag |
| Reuse existing `/activate/{token}` flow | Zero new endpoints, less attack surface | Dedicated `/recovery/{token}` endpoint |
| Token TTL = 1 h | Short enough to limit exfiltration risk, long enough for slow operators | 15 min (too aggressive) / 24 h (too loose) |
| Bump `tokenVersion` immediately | Invalidates any active session on the admin account, even if token was leaked | Wait until activation completes |
| `SecureRandom` 32 bytes base64url | 256 bits of entropy, URL-safe | UUID (only 122 bits) |

## Gotchas / Pitfalls

- The flag is read **only at boot** (Spring `@Value`). Setting it at runtime has no effect — the operator must restart.
- All ADMIN users get a new token, not just the one whose password was lost. Acceptable for self-hosted (typically 1 admin) but worth knowing.
- The activation URL is logged at `WARN` level. If your log aggregation forwards WARN to alerting, you'll get a notification — that's intentional (visibility).
- `tokenVersion` increment invalidates the admin's existing sessions immediately. If recovery is triggered while the admin is still logged in elsewhere, that session is killed.
- The token URL contains a secret. Be mindful where logs are stored — anyone with read access to logs during the 1-hour window can hijack the recovery.
- **Leave `ADMIN_RECOVERY_ENABLED=true` and every restart re-deactivates the admin** (`activated → false`, `tokenVersion++`). Always set it back to `false` after recovering, or the next container restart locks the admin out again.
- **The refresh→401 loop (fixed 2026-05-29):** before login/refresh enforced `isActivated()`, a deactivated admin could still log in with the old password and refresh tokens (both returned `200`), but every protected route's `JwtAuthenticationFilter` rejected the access token (`401 "Authentication required"`). The frontend then refreshed and retried endlessly. The symptom looked like "I'm logged in but everything 401s." Root cause was always `is_activated = false`; the fix makes login/refresh fail fast instead of feeding the loop. Quick diagnosis: `SELECT username, is_activated, token_version FROM app_user WHERE role='ADMIN';`.

## Tests

- Automated: `AuthControllerTest` (Mockito) — login with a deactivated account ⇒ `403` and no cookies set; login while the recovery flag is on ⇒ `403` whose detail points to the console link and the disable-the-flag reminder, before any password check; refresh with a deactivated account (matching `tv`) ⇒ `401` + cookies cleared; the activated happy paths still issue/rotate tokens; `activate()` bumps `tokenVersion` and calls `PersistentSessionService.revokeAllForUser` so completing a reset/recovery invalidates every pre-existing session (CWE-613/640). `ActivationServiceTest` covers the shared sink; `SsoAuthenticationSuccessHandlerTest` covers `ADMIN_RECOVERY_ACTIVE` for an SSO sign-in.
- Manual:
  1. Set `ADMIN_RECOVERY_ENABLED=true`, boot → expect WARN banner with valid URL.
  2. Before activating, try to log in with the old password **or a wrong one** → expect `403` pointing to the console reset link and the "set `ADMIN_RECOVERY_ENABLED=false`" reminder; no infinite loop.
  3. Open URL → activation form → set new password → expect "Account activated successfully".
  4. Old session: refresh dashboard → 401 → re-login required.
  5. Set `ADMIN_RECOVERY_ENABLED=false`, boot → expect no banner, no token rotation.
