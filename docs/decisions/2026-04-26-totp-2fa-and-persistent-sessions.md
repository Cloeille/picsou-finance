# ADR: TOTP 2FA and persistent (Remember-Me) sessions

> Date: 2026-04-26
> Status: ✅ Active

## Context

Picsou stores sensitive financial data and is deployed on private LANs, sometimes over plain HTTP (`SECURE_COOKIES=false`). Authentication used to be single-factor (JWT cookies, 15 min access + 7 day refresh). The threat model that drove this ADR:

- A LAN-scoped attacker who phishes or shoulder-surfs a password.
- A device theft (laptop/phone) where the JWT cookies are still valid.
- A managed family member who loses access to their authenticator.
- An admin who needs to recover any of the above without console/DB access.

The product also wanted "Remember Me" so day-to-day reauthentication after the 7-day refresh window doesn't drop sessions on the user's primary device.

## Decision

Add **TOTP 2FA (RFC 6238)** as an opt-in second factor per user, plus a **rotating persistent-session cookie** ("Remember Me") that can optionally mark the device as **trusted for 2FA** for 30 days.

Concretely:

- TOTP secret encrypted at rest (AES-GCM, reusing `CryptoService` from ADR `2026-03-01-aes-gcm-crypto-secrets`).
- Recovery codes hashed with bcrypt cost 12; one-shot via `used_at`.
- Persistent session = `<series_id>:<token>` cookie (90 days), where `series_id` is a UUID and `token` is 64 random bytes. Server stores `SHA-256(token)` only.
- Theft detection: if the series exists but the hash mismatches, the entire series is revoked (Improved Persistent Login Cookie pattern, Barry Jaspan).
- Anti-replay: `user_mfa.last_used_step` rejects re-use within the ±1 step tolerance window.
- Rate limits via Bucket4j: `mfaVerifyBuckets` (5 / 15 min per uid), `mfaEnrollBuckets` (10 / 1h per uid).
- A short-lived `mfa_challenge` JWT (5 min, distinct `type` claim) carries the MFA-pending state between `/login` and `/mfa/verify`.
- New `PersistentTokenAuthFilter` runs **after** `JwtAuthenticationFilter` (so an active access token short-circuits) and re-issues access+refresh from a valid persistent token, rotating the persistent cookie on every use.
- Admin can force-disable 2FA on any non-admin or non-self target (`DELETE /api/admin/members/{id}/mfa`), wiping their persistent sessions in the same transaction.

Step-up reauthentication (`currentPassword` re-submitted in body) gates `enroll/init`, `disable`, and `recovery-codes/regenerate` — the same pattern used by `change-password`.

## Alternatives considered

### A. WebAuthn / passkeys instead of TOTP

- **Pros**: Phishing-resistant, no shared secret, modern UX.
- **Cons**: Requires per-device enrollment ceremony for a *self-hosted* service that's frequently accessed from multiple devices on a private LAN over plain HTTP; Safari/Firefox quirks on non-HTTPS origins make `navigator.credentials` unreliable in our actual deployment topology. We accept TOTP today and may add passkeys later as an additional factor.

### B. Email OTP

- **Pros**: No authenticator app to install.
- **Cons**: No SMTP wired in this project; adding a mail dependency to a self-hosted single-family app is disproportionate. Rejected.

### C. Static long-lived "Remember Me" token (no rotation)

- **Pros**: Simpler — single cookie, no DB write on every request.
- **Cons**: No theft detection. A copied cookie works forever until the user revokes it manually. Rejected — rotation gives a reliable warning signal at no real performance cost.

### D. Server-side challenge store instead of `mfa_challenge` JWT

- **Pros**: Server-controlled state, no JWT type sprawl.
- **Cons**: Adds a new table for a 5-minute pending state; same security guarantees as a short-lived signed JWT. Rejected — JWT is enough at this TTL.

### E. Always require TOTP, even with persistent session

- **Pros**: Strictest possible posture.
- **Cons**: Makes "Remember Me" useless and trains users to enter TOTP into the login screen on every visit, which is exactly the scenario where phishing wins. Rejected — `trusted_for_2fa` opt-in matches Google/GitHub UX.

## Reasoning

TOTP + persistent session covers the listed threats with off-the-shelf cryptography (`dev.samstevens.totp`), the existing AES-GCM crypto layer, and a single new filter. Rotation gives us theft detection without a per-request bcrypt; SHA-256 is appropriate because the value is server-only and never compared in user space.

The key UX bet is that **2FA must be skippable on a trusted device** — otherwise users disable 2FA after a week of typing six digits twice a day. A separate `trusted_for_2fa` boolean on the persistent session, set explicitly only after a successful TOTP verify (never inherited), preserves the second-factor guarantee while cutting daily friction.

Cascading invalidations were chosen paranoid-by-default: enabling and disabling 2FA both wipe persistent sessions, change-password wipes them, and admin force-disable wipes the target's sessions. Regenerating recovery codes does **not** wipe sessions because that would punish users for routine hygiene.

## Trade-offs accepted

- **One DB hit per request authenticated only by the persistent cookie**. Acceptable: as soon as the 15-minute access cookie is set, the JWT filter short-circuits.
- **Phishing of password + TOTP in real time still grants access once** (anti-replay only blocks the *second* use). Mitigated by SameSite cookies and TLS where possible; PhaaS-grade attacks are explicitly out of scope for v1.
- **Lost admin authenticator + lost recovery codes ⇒ DB-level intervention** (`UPDATE user_mfa SET enabled = FALSE WHERE user_id = ?`). Acceptable for a self-hosted single-family deployment.
- **Persistent cookies inherit `SECURE_COOKIES`** like every other cookie. On HTTPS this is correct; on LAN HTTP the user is already opting into reduced confidentiality.
- **No dedicated `mfa_audit` table** — events are SLF4J `WARN` logs (theft detection, lockout, force-disable). Sufficient for the deployment scale; revisit if multi-tenant.

## Consequences

**Schema (Flyway V28):**
- New tables: `user_mfa`, `user_mfa_recovery_code`, `persistent_session`.
- `app_user` unchanged; relationships sit on the new tables' `user_id`.

**Backend:**
- New: `MfaService`, `PersistentSessionService`, `MfaController`, `SessionController`, `AdminMfaController`, `PersistentTokenAuthFilter`.
- Modified: `AuthController` (login branches on MFA + persistent token; new `/mfa/verify`), `JwtUtil` (`mfa_challenge` claim type), `RateLimitConfig` (two new buckets), `SecurityConfig` (filter ordering, anonymous access for `/mfa/verify`), `application.yml` (`app.persistent-session.expiry-days: 90`, `app.persistent-session.trust-days: 30`, `app.jwt.mfa-challenge-expiry-minutes: 5`).
- Test surface: `MfaServiceTest` (19), `PersistentSessionServiceTest` (14), `MfaControllerTest` (14), `SessionControllerTest` (8), `AdminMfaControllerTest` (3), `JwtUtilTest` (7).

**Frontend:**
- New module `features/mfa/` (`api.ts`, `hooks.ts`).
- New pages: `frontend/src/pages/login/MfaChallengePage.tsx`, `frontend/src/pages/settings/security/{SecuritySection,MfaEnrollDialog,MfaDisableDialog,RecoveryCodesDialog,RecoveryCodesView,SessionsList}.tsx`.
- Modified: `LoginPage.tsx` (Remember Me checkbox + `mfaRequired` branch), `SettingsPage.tsx` (Security section), `frontend/src/pages/admin/sections/MembersSection.tsx` (2FA badge + Reset 2FA button), `frontend/src/app/routes.tsx` (`/login/mfa` under `<PublicOnly>`), `i18n/{fr,en}.json`.
- Backend DTO change visible on the wire: `FamilyMemberResponse` now includes `mfaEnabled: boolean`.

**Operational:**
- TOTP requires host clock within ~30 s of the user's device (rely on host NTP).
- `SECURE_COOKIES=false` on plain-HTTP LAN deployments must remain set, otherwise the new persistent and challenge cookies are silently dropped along with access/refresh.

## Supersedes

Extends — but does not supersede — `2026-01-01-single-user-jwt-cookies.md` (still the source of truth for the access/refresh cookie pair).
