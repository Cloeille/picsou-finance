# Feature: 2FA (TOTP) and Remember Me

> Last updated: 2026-07-04
> Status: ✅ Implemented (2026-04-26)
>
> Implementation notes vs. original design:
> - `AdminMfaController` lives at `backend/src/main/java/com/picsou/controller/AdminMfaController.java` (flat) — the URL stays `/api/admin/members/{id}/mfa` so the security-config URL pattern is unchanged.
> - `MfaController.regenerate` accepts only TOTP, not recovery codes (rejects `isRecoveryCode=true`) — extra paranoia: don't let one stolen recovery code mint a fresh batch of ten.
> - `FamilyMemberResponse` was extended with `mfaEnabled: boolean` so the admin Members list can show a "2FA on" badge and gate the Reset 2FA button without an extra round-trip.
> - Settings UI: `pages/settings/security/` (`SecuritySection`, `MfaEnrollDialog`, `MfaDisableDialog`, `RecoveryCodesDialog`, `RecoveryCodesView`, `SessionsList`) — split out of the design's flat `features/mfa/` proposal because the dialogs are tightly coupled to the settings page layout.
> - `MfaChallengePage` lives under `<PublicOnly>` (not anonymous-permitted): the user is mid-login (no `access_token`), so `RequireAuth` would loop.

## Context

Picsou stores sensitive financial data (bank balances, holdings, debts) and is exposed to the LAN — sometimes over plain HTTP. Authentication today is a single-factor JWT cookie pair (15 min access, 7 day refresh). This feature adds:

1. **TOTP-based 2FA**, opt-in per user, configurable from `/settings/`.
2. **Remember Me** — a long-lived persistent cookie that keeps the user logged in for 90 days and, when the user explicitly chooses, marks the device as **trusted for 2FA** so subsequent logins from that device skip the TOTP prompt.

Security target: high. Self-hosted with no support team, so the design must be self-recoverable for end users (backup codes) and admin-recoverable for members (admin can wipe a member's 2FA).

## Goals

- Each user (admin or activated member) can independently enroll/disable TOTP from `/settings/`.
- A user with 2FA enabled cannot be authenticated by password alone — TOTP or a recovery code is required.
- Recovery codes (10 × 8-digit) are generated once at enrollment and shown only once.
- "Remember Me" extends session persistence to 90 days using a rotating cookie token (no JWT extension).
- "Trust this device for 30 days" (shown only on the MFA challenge step, after TOTP success) lets a device skip the TOTP step on subsequent logins.
- Sessions can be listed and revoked individually from `/settings/`.
- An admin can force-disable 2FA for any other member from `/admin`.
- All sensitive credentials at rest are encrypted (TOTP secret) or hashed (recovery codes, persistent tokens).

## Non-goals (v1)

- WebAuthn / passkeys.
- Email OTP (no SMTP wired in this project).
- Per-instance "force 2FA for everyone" policy.
- New-device email notifications.
- Geolocation / IP risk scoring.
- A dedicated `mfa_audit` table — logs are written via SLF4J at WARN level only.

## Identity model recap

```
AppUser (id, username, password_hash, role, member_id, ...)
  └── 1-1 FamilyMember (display info, sharing settings, scopes domain data)
```

2FA is bound to **`AppUser`**, not to `FamilyMember`. An admin who switches to a managed profile (via `?memberId=X`) is still authenticated as the same `AppUser` — the 2FA state is unchanged. Managed (non-login) members have no `AppUser` row, hence no 2FA state.

## How it works

### Authentication state machine

```
                ┌─────────────────────────────────────────────────────┐
                │ Anonymous                                            │
                └────────────┬────────────────────────────────────────┘
                             │ POST /api/auth/login (user, pass, rememberMe?)
                             ▼
                  password OK?
                   ├── no ──► 401 (rate-limited by IP)
                   └── yes
                             │
                  mfa enabled for user?
                   ├── no  ──► access+refresh cookies set
                   │            (+ persistent_token if rememberMe)
                   │            └─► Authenticated
                   │
                   └── yes
                             │
                  request has valid persistent_token with trusted_for_2fa=true?
                   ├── yes ──► access+refresh cookies set + rotate persistent_token
                   │            └─► Authenticated
                   │
                   └── no
                             │ set mfa_challenge cookie (5 min JWT) carrying { uid, rememberMe }
                             │ return 200 { requires2fa: true }
                             ▼
                ┌─────────────────────────────────────────────────────┐
                │ Pending MFA                                          │
                └────────────┬────────────────────────────────────────┘
                             │ POST /api/auth/mfa/verify (code, trustDevice?)
                             ▼
                  TOTP or recovery code valid?
                   ├── no ──► 400 Invalid verification code
                   │          (challenge cookie kept — retry in place)
                   └── yes
                             │ clear mfa_challenge
                             │ access+refresh cookies set
                             │ rememberMe → persistent_token (trusted_for_2fa = trustDevice)
                             ▼
                          Authenticated
```

### Cookies

| Cookie | TTL | Purpose | Set by | Cleared by |
|---|---|---|---|---|
| `access_token` | 15 min | API auth (existing) | login, refresh, mfa/verify, persistent-filter | logout; login (severs a pending/foreign session) |
| `refresh_token` | 7 days | Rotate access token (existing) | login, refresh, mfa/verify, persistent-filter | logout, password change, mfa change; login (sever) |
| `mfa_challenge` | 5 min | Single-purpose token to call `/api/auth/mfa/verify` | login (when 2FA on) | mfa/verify success, mfa/verify rate-limit lockout |
| `persistent_token` | 90 days | Remember Me / trusted-device | login, mfa/verify (if rememberMe), persistent-filter rotation | logout, password change, mfa change, session revoke; login (foreign "Remember Me") |

All cookies share the same attributes: `HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` controlled by `SECURE_COOKIES` env (existing).

**`access_token`/`refresh_token` are only written with the TTLs above when the browser has a "Remember Me" session** (i.e. a `persistent_token` cookie owned by the same user is present on the request, or the login/mfa-verify call itself set `rememberMe`/`trustDevice`). Otherwise `AuthCookieWriter.setAccessAndRefresh` omits `Max-Age` entirely, making them **browser-session cookies**: closing the browser deletes them outright, regardless of the 15-minute/7-day validity still encoded in the JWT itself. This matters specifically because `RequireAuth`'s session-probe (below) actively calls `/auth/refresh` on every mount — without this, a non-"Remember Me" login's 7-day `refresh_token` would let the probe silently resurrect the session after the browser was closed, defeating the whole point of not ticking "Remember Me". `AuthController` derives this per request/response via `isPersistentDevice()`; it is never a static "did this login request tick the box" flag, so persistence is re-evaluated on every rotation (login, refresh, mfa/verify, username change).

### `mfa_challenge` JWT

A separate JWT type, distinct from `access`/`refresh`:

```
{ sub: <username>, uid: <id>, type: "mfa_challenge", remember_me: <bool>, exp: now+5min }
```

Only `/api/auth/mfa/verify` reads it; no other endpoint accepts it. Implemented via an explicit cookie read in `AuthController.mfaVerify`, not via the `JwtAuthenticationFilter`. The filter chain leaves `mfaVerify` accessible to anonymous requests in `SecurityConfig`.

### `persistent_token` format

`<series_id>:<token>` where:
- `series_id` — UUID identifying the chain.
- `token` — 64 random bytes (`SecureRandom`), base64url-encoded.

The cookie value is opaque to the client. The server splits on `:` and looks up the series.

**Storage:** `persistent_session.token_hash` = `SHA-256(token)` (hex). The plaintext is never stored.

**Validation:**
1. Parse `series_id`, look up active session (`revoked_at IS NULL AND expires_at > now`).
2. Compare `SHA-256(received_token) == stored token_hash` in constant time.
3. **If series exists but hashes mismatch → token theft suspected** → revoke the entire series (`revoked_at = now`), clear all cookies, log warning. (Improved Persistent Login Cookie pattern, Barry Jaspan.)
4. If match → generate a new `token`, update `token_hash` and `last_used_at`, re-issue the cookie. The previous token is now invalid; if it gets replayed later, step 3 fires.

### TOTP

- Library: `dev.samstevens.totp:totp-spring-boot-starter:1.7.1` (provides secret generation, QR code via ZXing, code verification with discrepancy window).
- Algorithm: HMAC-SHA1, 6 digits, 30 s period (RFC 6238 defaults — universal authenticator compatibility).
- Tolerance: ±1 step (covers small clock drift; rejects wider replay windows).
- **Anti-replay:** `user_mfa.last_used_step` stores the most recently consumed time-step. Verification rejects any code whose step ≤ `last_used_step`. On success, `last_used_step` is updated. This prevents reusing a code within its 90-second tolerance window.
- Secret encoded base32 (RFC 4648), 20 bytes (160 bits) of entropy.
- Encrypted at rest in `user_mfa.totp_secret_enc` via the existing `CryptoService` (AES-GCM, ADR `2026-03-01-aes-gcm-crypto-secrets`).

### Recovery codes

- 10 codes per user, generated at enrollment.
- Format: 8 digits (cryptographically random, `SecureRandom.nextInt(100_000_000)` formatted to 8 digits with leading zeros).
- Stored as `bcrypt(code)` cost 12 in `user_mfa_recovery_code.code_hash`. Same encoder used for passwords.
- One-shot: consumed by setting `used_at = now`. A used code cannot be re-presented.
- Shown to the user **once** at enrollment and once after each regenerate. The plaintext is never recoverable.
- Regenerating wipes all existing codes (used or not).

### Rate limiting

| Bucket | Scope | Limit | Tool |
|---|---|---|---|
| `loginBuckets` (existing) | IP | 5 / 15 min | Bucket4j |
| `mfaVerifyBuckets` (new) | uid | 5 / 15 min | Bucket4j |
| `mfaEnrollBuckets` (new) | uid | 10 / 1 h | Bucket4j |

On `mfaVerifyBuckets` exhaustion, the `mfa_challenge` cookie is **cleared** so the user has to re-enter the password (kills any active challenge after lockout). 429 ProblemDetail returned.

### Step-up reauthentication

These endpoints require the current user's password to be re-submitted in the request body, even though the user is already authenticated:

- `POST /api/auth/mfa/enroll/init`
- `POST /api/auth/mfa/disable`
- `POST /api/auth/mfa/recovery-codes/regenerate`

The reauth check is a `passwordEncoder.matches(request.currentPassword, user.passwordHash)` call inline in the controller (same pattern as `change-password`). No separate "step-up token". This is enough because all three endpoints are state-changing and the attacker would already need a valid session cookie to reach them.

### Cascading invalidations

| Trigger | Effect |
|---|---|
| `POST /api/auth/change-password` | Revoke all persistent sessions of the user, clear cookies. User must log in again on every device. |
| `POST /api/auth/activate/{token}` (activation / admin reset / admin recovery) | Bump `tokenVersion` (revokes all access/refresh JWTs) and revoke all persistent sessions of the user. Mirrors `change-password` so an admin-initiated reset invalidates any pre-existing session. |
| Enable 2FA | Revoke all persistent sessions (no inheritance of "trusted_for_2fa" from before enrollment). |
| Disable 2FA | Revoke all persistent sessions (paranoid wipe — even non-trusted ones, in case the disable was a recovery action). |
| Regenerate recovery codes | No session impact (only revokes the codes themselves). |
| Admin force-disables target's 2FA | Same as user-initiated disable: wipe target's persistent sessions. |
| Logout | Revoke only the current device's persistent session. |

### Cross-identity session bleed at login

On a **shared family browser**, login cookies from a *previous* user can outlive their session and silently re-authenticate that other identity. `PersistentTokenAuthFilter` re-mints an access token from any still-valid `persistent_token`, and it only auto-clears a stale one when the cookie owner has 2FA enabled and the device isn't trusted — so a **no-MFA** account's leftover "Remember Me" cookie always sails through. Concretely: user A (2FA on) types their password on a browser still holding user B's (no-MFA) `persistent_token`. A's login correctly returns `requires2fa` and issues **no** session, so the next request falls back on B's lingering cookie → A is dropped onto **B's** account.

`AuthController.login` closes this at the instant the password is verified:

1. **MFA-required branch** — before issuing the `mfa_challenge`, it calls `AuthCookieWriter.clearSessionCookies` (access + refresh + persistent). The caller has proven a password but is **not** authenticated yet; any session cookies present must not bleed through while the second factor is pending or abandoned. A genuinely **trusted device** is detected first (`PersistentSessionService.isTrustedDeviceFor`, user-scoped) and is exempt — it falls through to a normal session.
2. **Session-completion without Remember Me** — `completeAuthenticatedSession` drops a leftover `persistent_token` whose `series_id` resolves to a **different** `AppUser` (`PersistentSessionService.ownerUserId`, a series-only lookup that never validates the token hash and never grants access). A cookie belonging to the *same* user (a trusted device logging in without re-ticking Remember Me) is left intact so the device stays trusted.

This is the server-side half of the shared-browser fix; the client-side half — resetting the cache + impersonation target when the new identity is written — lives in [multi-account-family.md](./multi-account-family.md#client-state-isolation-across-the-login-boundary).

### Endpoints

```
POST   /api/auth/login                        body: { username, password, rememberMe? }
                                              response (no 2FA): { user info } + cookies
                                              response (2FA): { requires2fa: true } + mfa_challenge cookie
                                              response (trusted device): { user info } + cookies (skips MFA)

POST   /api/auth/mfa/verify                   body: { code, trustDevice?: bool, isRecoveryCode?: bool }
                                              requires: mfa_challenge cookie
                                              response: { user info } + access/refresh cookies (+ persistent_token if rememberMe)

POST   /api/auth/mfa/enroll/init              body: { currentPassword }
                                              response: { qrCodeDataUri, secret (base32) }
                                              side effects: stores totp_secret_enc, enabled=false

POST   /api/auth/mfa/enroll/verify            body: { code }
                                              response: { recoveryCodes: string[] }   // 10 codes, plaintext, ONE TIME
                                              side effects: enabled=true, codes generated, persistent sessions wiped

POST   /api/auth/mfa/disable                  body: { currentPassword, code }   // code can be TOTP or recovery
                                              response: 204
                                              side effects: deletes user_mfa + recovery codes, wipes persistent sessions

POST   /api/auth/mfa/recovery-codes/regenerate
                                              body: { currentPassword, code }   // code = TOTP only (not recovery — paranoid)
                                              response: { recoveryCodes: string[] }

GET    /api/auth/mfa/status                   response: { enabled: bool, enrolledAt?: ISO, remainingRecoveryCodes?: int }

GET    /api/auth/sessions                     response: SessionResponse[]   // own persistent sessions, sorted by last_used_at desc
DELETE /api/auth/sessions/{id}                response: 204                  // revoke own session
DELETE /api/auth/sessions                     response: 204                  // revoke all of own sessions except current

DELETE /api/admin/members/{memberId}/mfa      admin-only; target.id != admin.id
                                              response: 204
                                              side effects: same as user disable on target
```

### Filter chain order

```
CorsFilter
  → JwtAuthenticationFilter (existing)         // sets SecurityContext if access_token cookie valid
  → PersistentTokenAuthFilter (NEW)            // if no SecurityContext set yet AND persistent_token present:
                                               //   validate, rotate, issue new access+refresh, set context
  → Spring Security filter chain
```

The persistent filter runs **after** the JWT filter so an active access_token short-circuits and we don't pay the DB hit on every request. It runs **before** the authorization phase so a request with only a `persistent_token` is still treated as authenticated.

### Schema

```sql
-- V28__mfa_and_persistent_sessions.sql

CREATE TABLE user_mfa (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    totp_secret_enc     TEXT NOT NULL,                         -- AES-GCM ciphertext (base64)
    last_used_step      BIGINT,                                -- anti-replay; NULL until first successful verify
    enrolled_at         TIMESTAMPTZ,                           -- NULL until enabled=true
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE user_mfa_recovery_code (
    id                  BIGSERIAL PRIMARY KEY,
    user_mfa_id         BIGINT NOT NULL REFERENCES user_mfa(id) ON DELETE CASCADE,
    code_hash           TEXT NOT NULL,                         -- bcrypt
    used_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_recovery_code_active ON user_mfa_recovery_code(user_mfa_id) WHERE used_at IS NULL;

CREATE TABLE persistent_session (
    id                  BIGSERIAL PRIMARY KEY,
    series_id           UUID NOT NULL UNIQUE,
    token_hash          TEXT NOT NULL,                         -- SHA-256 hex
    user_id             BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    user_agent          VARCHAR(255),
    ip_prefix           VARCHAR(45),                           -- e.g. "192.168.1." or "2001:db8::/64"
    trusted_for_2fa     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ
);
CREATE INDEX idx_persistent_session_user_active ON persistent_session(user_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_persistent_session_series ON persistent_session(series_id);
```

### Frontend UX

**Settings → Security section:**

```
┌─ Sécurité ─────────────────────────────────────┐
│ Authentification à deux facteurs                │
│   ○ Désactivée  [ Activer la 2FA ]              │
│   ● Activée depuis le 26 avril 2026             │
│      • 8 codes de secours restants              │
│      [ Régénérer les codes ]                    │
│      [ Désactiver la 2FA ]                      │
│                                                  │
│ Appareils connectés                             │
│ ┌──────────────────────────────────────────┐   │
│ │ 💻 Firefox · macOS · 192.168.1.•         │   │
│ │ 🔒 De confiance · vu il y a 2 min        │   │
│ │                            [ Révoquer ]   │   │
│ ├──────────────────────────────────────────┤   │
│ │ 📱 Safari · iOS · 192.168.1.•            │   │
│ │ vu il y a 3 jours                         │   │
│ │                            [ Révoquer ]   │   │
│ └──────────────────────────────────────────┘   │
│                                                  │
│ [ Révoquer toutes les autres sessions ]         │
└──────────────────────────────────────────────────┘
```

**Login page:**
- Add a checkbox `☐ Se souvenir de moi sur cet appareil` below password field.

**MFA challenge page (`/login/mfa`):**
- 6-digit code input, auto-advance, paste support.
- Link "Utiliser un code de secours" toggles to a single-line input.
- Checkbox `☐ Faire confiance à cet appareil pendant 30 jours` (only shown if `requires2fa=true && rememberMe=true` from the original login).
- "Annuler" returns to `/login` (clears `mfa_challenge` cookie via a `POST /api/auth/logout`-equivalent).

**Enroll dialog (4 steps, modal):**
1. Réauth password.
2. Show QR code + secret in base32 with a copy button + instructions ("Scannez avec Google Authenticator, Aegis, 1Password, …").
3. 6-digit input to verify enrollment.
4. Backup codes shown once with [Télécharger .txt] [Imprimer] [Copier]; checkbox "J'ai sauvegardé mes codes" required to close.

All UIs are mobile-responsive (per repo convention).

## Key files

**Backend (new):**
- `backend/src/main/java/com/picsou/model/UserMfa.java`, `backend/src/main/java/com/picsou/model/UserMfaRecoveryCode.java`, `backend/src/main/java/com/picsou/model/PersistentSession.java`
- `backend/src/main/java/com/picsou/repository/UserMfaRepository.java`, `backend/src/main/java/com/picsou/repository/UserMfaRecoveryCodeRepository.java`, `backend/src/main/java/com/picsou/repository/PersistentSessionRepository.java`
- `backend/src/main/java/com/picsou/service/MfaService.java`, `backend/src/main/java/com/picsou/service/PersistentSessionService.java`
- `backend/src/main/java/com/picsou/controller/MfaController.java`, `backend/src/main/java/com/picsou/controller/SessionController.java`, `backend/src/main/java/com/picsou/controller/AdminMfaController.java`
- `backend/src/main/java/com/picsou/config/PersistentTokenAuthFilter.java`
- `backend/src/main/java/com/picsou/dto/MfaDtos.java` — all MFA DTOs as nested records (`MfaStatusResponse`, `EnrollInitRequest`, `EnrollInitResponse`, `EnrollVerifyRequest`, `RecoveryCodesResponse`, `MfaVerifyRequest`, `DisableMfaRequest`, `RegenerateCodesRequest`) — plus `backend/src/main/java/com/picsou/dto/SessionResponse.java`
- `backend/src/main/resources/db/migration/V28__mfa_and_persistent_sessions.sql`

**Backend (modified):**
- `backend/src/main/java/com/picsou/model/AppUser.java` — no change required; relationship is held on `UserMfa.user_id` side.
- `backend/src/main/java/com/picsou/controller/AuthController.java` — `login` branches on MFA + persistent token; new `mfaVerify`; cookie helpers reused; `change-password` wipes sessions.
- `backend/src/main/java/com/picsou/config/SecurityConfig.java` — register `PersistentTokenAuthFilter`; permit `/api/auth/mfa/verify`, `/api/auth/mfa/enroll/init`, `/api/auth/mfa/status` after authentication; permit `/api/auth/mfa/verify` with anonymous (uses challenge cookie).
- `backend/src/main/java/com/picsou/config/JwtUtil.java` — add `generateMfaChallengeToken`, `isMfaChallengeToken`, `getMfaChallengeExpirySeconds`.
- `backend/src/main/java/com/picsou/config/RateLimitConfig.java` — add `mfaVerifyBuckets`, `mfaEnrollBuckets` beans.
- `application.yml` — `app.jwt.mfa-challenge-expiry-minutes: 5`, `app.persistent-session.expiry-days: 90`, `app.persistent-session.trust-days: 30`.
- `pom.xml` — add `dev.samstevens.totp:totp-spring-boot-starter`.

**Frontend (new):**
- `frontend/src/features/mfa/api.ts`, `frontend/src/features/mfa/hooks.ts`
- `frontend/src/pages/settings/security/MfaEnrollDialog.tsx`, `frontend/src/pages/settings/security/MfaDisableDialog.tsx`, `frontend/src/pages/settings/security/RecoveryCodesDialog.tsx`
- `frontend/src/pages/settings/security/SessionsList.tsx`
- `frontend/src/pages/login/MfaChallengePage.tsx`

**Frontend (modified):**
- `frontend/src/pages/login/LoginPage.tsx` — add Remember Me checkbox; on `requires2fa=true` redirect to `/login/mfa`.
- `frontend/src/pages/settings/SettingsPage.tsx` — add Security section.
- `frontend/src/pages/admin/sections/MembersSection.tsx` — "Disable 2FA" action when target has it enabled.
- `App.tsx` / router — add `/login/mfa` route (anonymous-accessible).
- `frontend/src/i18n/locales/{fr,en,de,es}.json` — all MFA strings (every locale kept in sync).

## Technical choices

| Choice | Why | Rejected alternative |
|---|---|---|
| TOTP-only (no email OTP) | No SMTP wired; standard authenticator apps cover the threat model | Email OTP — would force adding SMTP config and an email column on `AppUser` |
| Library `dev.samstevens.totp` | Mature, Spring-friendly, includes QR code generation | Hand-rolled HMAC-SHA1 — error-prone for the time-step boundary handling |
| AES-GCM at rest for `totp_secret` | Reuses `CryptoService` from existing ADR; aligns with bank session secrets | Plaintext — rejected (single key compromise = all 2FA broken) |
| bcrypt for recovery codes | Same encoder as passwords; brute-force resistance | SHA-256 — too cheap if DB leaked |
| SHA-256 for persistent-token hash | One-time, server-only verification, no GPU advantage; bcrypt would be unnecessarily slow on every request | bcrypt — verifying every API call would add ~100 ms per request |
| Token rotation + theft detection (Jaspan) | Industry-standard pattern for "remember me"; gives an early warning on cookie theft | Static long-lived token — no theft detection |
| Separate `mfa_challenge` cookie (JWT, 5 min) | Stateless, expires fast, can't be confused with `access_token` (different `type` claim) | Server-side challenge store — needs a new table for a 5-min state |
| Anti-replay via `last_used_step` | Cheap, deterministic, blocks the ±1 tolerance window | Storing all consumed codes — unbounded growth |
| Skip TOTP if `trusted_for_2fa` cookie present | Standard UX (Google, GitHub); without it 2FA becomes annoying with persistent sessions | Always require TOTP — defeats the point of "remember me" |
| Wipe persistent sessions on disable 2FA | Defensive: if disable is a recovery action, the attacker's trusted devices are blown away | Keep them — would let an attacker who already trusted a device coast through the disable |
| `PATCH` not used here | Sticking to existing convention (POST for state-changing actions, no idempotency requirement) | PATCH `/api/auth/mfa` — would require additional CORS method allowlist |
| Step-up via password in body | Same pattern as `change-password`; simple; sufficient for this threat model | "Step-up token" with short TTL — overkill for a self-hosted single-family app |

## Threat model

| Threat | Mitigation |
|---|---|
| Stolen `access_token` cookie | TTL 15 min; persistent-token rotation invalidates if attacker rotates first |
| Stolen `refresh_token` cookie | TTL 7 days; rotation on each refresh; password change wipes; a Remember-Me refresh carries its persistent-session `series_id` (`sid` claim), so revoking that device (`/auth/sessions`) cuts the refresh chain at the next `/auth/refresh` even while the JWT is still valid |
| User revokes a lost/stolen device (`/auth/sessions`) | `DELETE /api/auth/sessions/{id}` sets `revoked_at`; `/auth/refresh` then refuses any refresh chain bound to that series (`PersistentSessionService.isSeriesActive`), so the device is logged out at its next refresh (≤ one 15-min access-token lifetime) instead of surviving on its independent 7-day `refresh_token` |
| Stolen `persistent_token` cookie | Rotation + theft detection wipes the entire series on replay |
| TOTP code intercepted (network sniff or shoulder-surf) | Anti-replay via `last_used_step`; cookie SameSite=Lax + Secure on HTTPS |
| Phished password | Stopped at MFA step (attacker has no TOTP) |
| Phished password + phished TOTP | One-shot replay by attacker — anti-replay blocks the second use, but the first use lets them in. Mitigated by TLS + user education. Not a v1 concern (PhaaS-grade attacks are out of scope). |
| Lost authenticator | Recovery codes (self-service) + admin disable (for non-admin users) |
| Lost admin authenticator + lost recovery codes | DB-level intervention required (`UPDATE user_mfa SET enabled = FALSE WHERE user_id = ?`). Acceptable for self-hosted. |
| 2FA secret leaked from DB | Encrypted at rest (AES-GCM); leak of DB alone doesn't yield secrets without the encryption key |
| Brute-force TOTP | Rate limit 5/15 min per uid + ±1 tolerance window only |
| Brute-force recovery codes | bcrypt cost 12 (~250 ms/check) + same rate limit |
| User reactivates after admin force-disable | Admin disable wipes persistent sessions; user must log in fresh and re-enroll |
| Username enumeration via login timing (CWE-208, GHSA-ww5m-pxgq-8qq6) | Unknown-user path runs a decoy bcrypt `matches()` so it costs the same as a wrong-password attempt — see [login-timing-attack.md](./login-timing-attack.md) |
| Admin resets a (possibly compromised) member's password | `AuthController.activate` — the shared sink for new-member activation, admin-initiated password reset (`FamilyService.resetPasswordToken`) and admin-recovery completion — bumps `tokenVersion` and calls `PersistentSessionService.revokeAllForUser`, exactly like self-service `change-password`. The member's pre-existing access/refresh JWTs and Remember-Me cookies are all invalidated (CWE-613/640). |
| Account enumeration via login timing on pending-activation members | An invited-but-not-activated member has a blank `password_hash`; `passwordEncoder.matches(pw, "")` short-circuits without bcrypt. `AuthController.login` now runs the same dummy-hash bcrypt round for a blank stored hash and fails like a wrong password, so the unknown-user, wrong-password and pending-activation paths are timing-indistinguishable (CWE-208). |

## Gotchas / Pitfalls

- **The JS-readable "logged in" signal must not be tab-scoped**: the frontend has no read access to the HttpOnly cookies, so `RequireAuth` (`frontend/src/features/auth/guards.tsx`) relies on a client-side flag (`sessionStorage['picsou_user']`, mirrored in `useAuthStore`) to decide whether to render or redirect to `/login`. `sessionStorage` is cleared on every tab/browser close, which is *unrelated* to the 90-day `persistent_token` lifetime — a bug fixed on 2026-07-02 had `RequireAuth` redirect to `/login` on an empty flag without ever giving the cookie-backed session a chance, defeating "Remember Me" and forcing daily re-logins. `RequireAuth` now probes `POST /api/auth/refresh` once when the flag is empty (rehydrating the store on success) before redirecting, so a valid `refresh_token` or `persistent_token` (re-minted by `PersistentTokenAuthFilter`) is honoured.
- **`/auth/refresh` resolves the account by the token's `uid` claim, never by the username in `sub`**, exactly as `JwtAuthenticationFilter` does for access tokens. A username is mutable (`change-username`) and can be freed and given to a new member; resolving by name logged the old refresh token's holder into whoever now carried that name (as long as `tokenVersion` happened to match, and a fresh account starts at the same version) and locked a renamed user out at their next refresh. Every refresh token already carries `uid` (`JwtUtil.generateToken`), so nothing changes for existing sessions.
- **`AuthController.refresh` must fall back to the `PersistentTokenAuthFilter`-set principal, and must honour it over a stale `refresh_token`**: fixed alongside the gotcha above. `refresh` accepts `@AuthenticationPrincipal AppUser` and, whenever no `refresh_token` cookie yields a valid rotation (missing, expired, wrong `tokenVersion`, deactivated user), falls back to that principal instead of an immediate 401 — this is also what lets a `tokenVersion` bump that didn't also revoke persistent sessions (e.g. `AdminRecoveryRunner`) still resolve to a valid session instead of a dead end. The endpoint always (re)mints access/refresh cookies whenever it returns 200 — never a "phantom" 200 with zero `Set-Cookie` — and never calls `clearAuthCookies` in that fallback path, since `PersistentTokenAuthFilter` may have *just* rotated `persistent_token` on the very same response; clearing it there would silently destroy Remember Me for a request that was otherwise fine.
- **`access_token`/`refresh_token` persistence is derived per request, not just "did rememberMe get ticked at login"**: `AuthController.isPersistentDevice()` checks whether the *current* request carries a `persistent_token` owned by the authenticated user. `change-password` always forces `persistent=false` (it also revokes all persistent sessions in the same call), `change-username` preserves whatever persistence the browser already had, and `refresh`/login derive it from the request each time.
- **Revoking a device cuts its `refresh_token` chain, not only its `persistent_token`**: the `refresh_token` is an independent 7-day JWT, so on its own a session revoke (`/auth/sessions`) would not stop a device that keeps rotating it. To close that, a Remember-Me `refresh_token` carries the persistent session's `series_id` in a `sid` claim (`JwtUtil.generateRefreshToken(user, seriesId)`, propagated on every persistent mint: login/mfa-verify, the persistent-token filter, `refresh` rotation, `change-username`). `/auth/refresh` then enforces revocation two ways — a front guard on the request's `persistent_token` series, and a check on the refresh_token's own `sid` — both via `PersistentSessionService.isSeriesActive` (false when `revoked_at` is set or past the 90-day cap). The `sid` check is **decisive** (401 + clear, no fall-through to the access-principal re-mint), otherwise a still-valid `access_token` on the same device would re-establish the session. The `access_token` deliberately stays `sid`-free so its validation remains a pure signature/`tokenVersion` check with no per-request session lookup; the cost is that revocation lands within one access-token lifetime (≤15 min), not instantly. Non-"Remember Me" (session-scoped) refresh tokens carry no `sid` and are unaffected.
- **`persistent_token` and `?memberId=X` are independent**: the persistent token authenticates the `AppUser`; `?memberId=X` is the admin's profile-switch overlay. Don't confuse them.
- **A no-MFA "Remember Me" cookie survives someone else's login**: `PersistentTokenAuthFilter` only auto-clears a stale `persistent_token` for 2FA-enabled owners, so a no-MFA account's leftover cookie would re-authenticate it under the next person. `AuthController.login` therefore severs lingering session cookies on the MFA-required branch (`clearSessionCookies`) and drops a *foreign* `persistent_token` on no-Remember-Me completion (`ownerUserId` series check); a trusted device's **own** cookie is preserved. See "Cross-identity session bleed at login".
- **`SECURE_COOKIES=false` on LAN HTTP**: persistent and challenge cookies must inherit the same `Secure` flag handling as access/refresh, otherwise they'll be silently dropped or sent over plaintext (matching existing behavior).
- **Always log out through `useLogout()`, never the raw store action**: `frontend/src/pages/settings/SettingsPage.tsx` used to call `useAuthStore().logout()` directly, which only clears `sessionStorage` — it never calls `POST /auth/logout`, so the session cookies (and, before the fix above, a `persistent_token`) stay valid server-side. Combined with the session-probe, that meant a "logged out" tab could get silently re-authenticated on the next protected-route mount. `useLogout()` (`frontend/src/features/auth/hooks.ts`) is the only path that revokes the server session (`authApi.logout()`) *and*, only on success, clears local state and the query cache (`resetClientState` → `queryClient.clear()`, which also drops the cached `session-probe` result). If the server call fails, local state is deliberately left untouched — the user stays logged in client-side, matching the still-valid server session, rather than presenting a "logged out" UI that a probe would immediately contradict.
- **`session-probe`'s `gcTime` is bounded (5 min), not `Infinity`**: defense-in-depth so a stale cached probe result can eventually be garbage-collected even if some future logout path forgets to call `resetClientState`. `staleTime` stays `Infinity` since the probe should never spontaneously refetch while the user is authenticated.
- **TOTP clock drift**: with ±1 step tolerance, the server clock must be within ~30 s of the user's device. Document this in the gotchas; rely on host NTP.
- **Backup code collision**: 8-digit codes have ~33 bits — collision risk with 10 codes is negligible, but generation must `SecureRandom`-loop until unique within the user's set to avoid duplicates.
- **`@JsonIgnore` on lazy `AppUser` ref in `UserMfa`**: per project convention with `open-in-view: false`.
- **PostgreSQL UUID column for `series_id`**: use Hibernate's `@JdbcTypeCode(SqlTypes.UUID)` to avoid varchar fallback.
- **MFA challenge cookie cleared on `mfaVerifyBuckets` lockout**: critical — otherwise the user is permanently stuck at the MFA screen until 5-min cookie expires anyway, but explicit clearing makes the UX cleaner (returns straight to `/login`).
- **Demo mode**: enrollment must be rejected with 403 ProblemDetail "MFA is disabled in demo mode" to avoid leaking enrollment state in the shared demo instance.
- **Multi-tab restore grace window (addressed)**: multiple tabs restored at once each present the *same* `persistent_token` to `PersistentTokenAuthFilter`/`validateAndRotate`; the first request rotates it and the rest used to look like a replayed token, tripping theft detection and revoking the whole series (logging the user out everywhere). Two changes fix this: (1) the rotate path is **serialized** with a row-level lock (`PersistentSessionRepository.findBySeriesIdForUpdate`, `@Lock(PESSIMISTIC_WRITE)`), so concurrent restores no longer both read the pre-rotation state and orphan each other's token; (2) `validateAndRotate` remembers the immediately-previous token hash (`previous_token_hash`/`previous_token_at`, migration `V56`) and accepts it for `app.persistent-session.rotation-grace-seconds` (default 30s). The previous slot is **anchored** — armed only when the *current* token is presented (a genuine rotation), and left untouched on a previous-token (grace) acceptance. So it keeps pointing at the one pre-burst token: every tab in the burst is accepted (not just the first two), and replaying the previous token cannot slide the window forward. The persistent cookie is shared across tabs and converges on the latest rotated value; a token presented after the window still trips theft detection.
- **Known follow-up, not yet addressed**: the session-probe (`useSessionProbe`) only runs from `RequireAuth` — `PublicOnly` never probes, so opening `/login` directly after a restart shows the form despite a restorable session, and `RequireAdmin` doesn't either; a single probe at app bootstrap shared by all three guards would be more consistent than probing only from `RequireAuth`.
- **Frontend language**: per project memory, all `docs/` files are English; **UI copy is French** (matching existing pages); all locale files (`frontend/src/i18n/locales/{fr,en,de,es}.json`) must be updated.

## Tests

**Backend unit (Mockito):**
- `MfaServiceTest`
- `PersistentSessionServiceTest`
- `AuthControllerTest` — login severs cross-identity cookies: `login_mfaRequired_seversLingeringSessionCookies_beforeIssuingChallenge`, `login_noMfa_dropsForeignPersistentCookie_whenNotRemembering`, `login_noMfa_keepsOwnPersistentCookie_whenNotRemembering`

**Backend integration (`@SpringBootTest` + H2):**
- `AuthControllerMfaIntegrationTest`
- `PersistentTokenAuthFilterTest`
- `AdminMfaControllerTest`
- `SessionControllerTest`

**Frontend (Vitest + React Testing Library):**
- `MfaEnrollDialog.test.tsx`
- `MfaChallengePage.test.tsx`
- `LoginPage.test.tsx` (Remember Me checkbox + 2FA branch)
- `SessionsList.test.tsx`

**Manual:**
- Scan QR with Google Authenticator + Aegis + 1Password.
- LAN over HTTP (`SECURE_COOKIES=false`).
- Server reboot — persistent sessions survive.
- Admin disabling another member's 2FA.
- Recovery code consumption (single-use).
- Mobile responsiveness on iPhone Safari.

## Migration plan

- Existing users keep `enabled=false` (no `user_mfa` row written until enrollment).
- No backfill required.
- `V28` is forward-compatible with the existing `app_user` schema.

## Links

- Related ADR: `docs/decisions/2026-01-01-single-user-jwt-cookies.md` (extended for MFA)
- Related ADR: `docs/decisions/2026-03-01-aes-gcm-crypto-secrets.md` (reused for `totp_secret_enc`)
- Related feature: `docs/features/security-cors-cookies.md` (cookie semantics)
- Related feature: `docs/features/multi-account-family.md` (admin force-disable on members)
- ADR: `docs/decisions/2026-04-26-totp-2fa-and-persistent-sessions.md` (active).
