# Design: SSO login through an external OIDC provider, alongside passwords

> Date: 2026-10-31
> Status: 🎨 Design (agreed in discussion — implementation pending)
> Branch: `feature/sso-login`

## Context

Picsou signs people in with a local username and password, optional TOTP 2FA and a rotating
Remember-Me cookie ([ADR 2026-04-26](../decisions/2026-04-26-totp-2fa-and-persistent-sessions.md)).
Homelab operators who already run an identity provider (Zitadel, Authentik, Keycloak, Authelia,
Pocket ID…) want to sign in to Picsou with it, and to provision a new instance without ever
creating a Picsou password. Operators without an IdP must not see any difference.

Two ADRs set external IdPs aside: [2026-01-01](../decisions/2026-01-01-single-user-jwt-cookies.md)
(superseded) for a single-user app, and
[2026-07-03](../decisions/2026-07-03-oauth2-authorization-server-for-native-app.md) for the iOS app
("wrong identity model … adds a hard external dependency to a self-hosted app"). This design
answers both by keeping SSO **optional and additive**: an IdP the operator already runs, never a
requirement, and never a replacement for Picsou's own session model.

The codebase already has the right seam. Every request resolves to an `AppUser` principal built
from Picsou's own HS256 cookies (`JwtTokenAuthenticator`, `UserContext`), and every service scopes
by `member_id`. SSO only needs a new way in that ends in the same cookies as a password login.
The embedded Authorization Server (iOS app, claude.ai connector) gets SSO for free: it already
bounces the browser through the SPA's `/login?redirect=…`.

## Goal / success criteria

- With `SSO_ISSUER_URI` and `SSO_CLIENT_ID` set, the login page offers "Sign in with <provider>".
  A linked user lands in Picsou with exactly the cookies a password login produces, Remember Me
  included.
- A fresh instance can be provisioned **with no password at all**: the first member of the
  declared admin group who signs in from the setup wizard becomes the administrator.
- The admin group **keeps managing admins**: its members get an admin account at their first
  sign-in, and every SSO login promotes or demotes. A demotion takes effect within the
  re-validation window (default 24 h).
- Accounts can live **without a password**: invited members can activate with SSO, and a linked
  account can turn its password login off. Sensitive actions then re-authenticate through the IdP.
- Picsou's own TOTP is never asked on an SSO login; MFA is the IdP's job.
- With SSO not configured, behavior is byte-for-byte today's. Only the schema grows.
- The iOS app and the remote-MCP OAuth flow work with SSO accounts without any change on their
  side.

## Non-goals

- Several IdPs at once. IdP settings stored in the database or edited from the Admin page: they
  come from environment variables only, because before the first admin exists there is nowhere
  else to put them.
- An instance-wide "SSO only" switch. Password login stays available to every account that has a
  password.
- SAML, LDAP, trusted-header (forward-auth) login, SCIM provisioning.
- Plain OAuth2 providers without OIDC: no ID token, `auth_time` or `prompt=login`, so
  re-authentication cannot be done properly.
- OIDC back-channel logout, and keeping or refreshing the IdP's tokens. Picsou discards them at
  the callback.
- Requiring a given `amr`/`acr` value (e.g. "MFA done at the IdP"). Listed as a follow-up.
- A change-password UI (none exists today) and the account-deletion backend: the frontend's
  `DeleteAccountDialog` posts to `/api/me/delete`, which has no backend endpoint on `main`. Open
  PR #355 proposes removing that unshipped frontend slice.

## Locked decisions (from discussion)

1. **Alongside passwords.** SSO is additive; nothing changes when it is not configured.
2. **The IdP only authenticates.** Picsou keeps issuing its own HttpOnly cookies after the
   callback. The IdP never issues the tokens Picsou's API accepts.
3. **OIDC Authorization Code with PKCE S256 always**, plus `state` and `nonce`. One provider,
   configured by environment variables and discovered lazily, so Picsou boots even when the IdP
   is down.
4. **Identities are linked by `(iss, sub)` only**, never by email or username.
5. **Password-less accounts from the start**: SSO first admin, admin-group provisioning, invites
   activated with SSO.
6. **First admin from the admin group.** When `SSO_ADMIN_GROUP` is set, the wizard's admin step
   only offers SSO and the server refuses to create a password admin.
7. **The admin group keeps managing admins**: accounts are created for its members at first
   sign-in, and every SSO login promotes or demotes. A missing claim counts as "no groups". The
   last admin is never demoted.
8. **Picsou's TOTP is skipped on SSO logins.**
9. **Password login can be turned off per account** once an SSO identity is linked and has been
   used. There is no instance-wide switch.
10. **Re-authentication through the IdP** (`prompt=login&max_age=0`, then a fresh `auth_time`)
    for sensitive actions of accounts without a password.
11. **SSO sessions are re-validated through the IdP** after `SSO_SESSION_REVALIDATE_AFTER`
    (default 24 h), which re-reads the groups.
12. **Groups claim**: configurable literal name (default `groups`). Accepted shapes are an array
    of strings, a single string, or an object whose keys are the groups. Matching is exact and
    case-sensitive.
13. **Logging out at the IdP** (RP-initiated logout) is optional and off by default.

## Session and token model

- **IdP role**: authenticate and supply claims. Picsou reads the ID token (`iss`, `sub`,
  `auth_time`; Spring verifies signature and `nonce`) and the userinfo response, which Spring
  merges into the same claim set. Groups usually come from userinfo.
- **Picsou session**: issued through the same code path as a password login (`SessionIssuer`,
  extracted from `AuthController.completeAuthenticatedSession`): `access_token`, `refresh_token`,
  and `persistent_token` when Remember Me is ticked. The IdP's access and refresh tokens are
  discarded.
- **SSO marker**: an SSO session records when the IdP last authenticated the user. It is stored
  as the claim `sso_at` on the refresh token (carried over on every rotation) and in the column
  `persistent_session.sso_authenticated_at` for Remember-Me series. Access tokens do not change.
- **Re-validation**: once `now − sso_at` exceeds `SSO_SESSION_REVALIDATE_AFTER`,
  `POST /api/auth/refresh` answers 401 with `code=SSO_REVALIDATE`, and the Remember-Me filter
  revokes the series instead of restoring it. The SPA then sends the user back through the IdP.
  That round trip is silent while the IdP session is alive, and it re-reads the groups.
- **Unchanged**: `tv` token-version revocation, the cross-site request check, member scoping.
- **Handshake state** (`state`, `nonce`, PKCE verifier, Picsou's intent) lives in a short-lived
  HTTP session that only exists between the redirect and the callback. The SSO filter chain never
  stores a SecurityContext in it, and the session is invalidated at the callback.

## Who gets in: account resolution at the callback

| Situation at the callback                                                              | Result                                                                                              |
| -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| `(iss, sub)` is linked to an account                                                   | That account. Refused if deactivated; the admin-recovery message if recovery mode is on            |
| Not linked, setup pending, intent `setup`, user in the admin group                     | First admin created, without a password                                                             |
| Not linked, setup complete, user in the admin group                                    | New admin account created, without a password                                                       |
| Not linked, intent `activate` with a valid invitation                                  | Identity bound to the invited account, account activated                                            |
| Not linked, intent `link` from a signed-in user with a fresh re-authentication         | Identity bound to the signed-in account                                                             |
| Anything else                                                                          | Refused with `NOT_LINKED`. No account is ever matched by email or username                          |

## Admin group: provisioning and role sync

- Active only when `SSO_ADMIN_GROUP` is set. Without it, roles stay managed in Picsou as today.
- Groups are read from the claim named by `SSO_GROUPS_CLAIM`, taken as a literal name rather than
  a path, because names like Zitadel's `urn:zitadel:iam:org:project:roles` contain colons and
  dots.
- A missing or malformed claim counts as "no groups" and is logged at WARN. Keeping the current
  role instead would leave a user whose grants were all removed still an admin, because Zitadel's
  usual Action omits the claim when there are no grants.
- The sync runs on every SSO callback that resolves an account: in the group means ADMIN,
  otherwise MEMBER.
- **The last admin is never demoted**; this is logged at WARN and audited. Admin rows are locked
  (`PESSIMISTIC_WRITE`) while counting, so two concurrent demotions cannot remove the last two
  admins.
- Every change is written to the audit log: `sso.role.promoted`, `sso.role.demoted`,
  `sso.role.demotion-skipped-last-admin`, `sso.user.provisioned`.
- **Password logins never sync roles.** A linked admin who keeps signing in with a password keeps
  their Picsou role. Admins whose rights must follow the group turn password login off.
- Authorization reads the role from the database on every request
  (`JwtTokenAuthenticator.authenticate`), so a change applies to the user's next request. The SPA
  refreshes its cached role at each login.
- **Removing someone happens at the IdP.** Deleting an SSO admin in Picsou deletes their data; if
  they are still in the group, they come back as a new, empty admin at their next login.
- **Changing `SSO_ISSUER_URI` orphans every link**, because `iss` changes. An admin-group member
  would then be provisioned as a new, empty account. This is documented, and an issuer-migration
  helper is a follow-up.

## Password-less accounts and re-authentication

- A password-less account has `password_hash = ''`. `AuthController.login` already rejects a
  blank hash in constant time (the pending-activation path), so no new column is needed.
- **Created by**: the SSO first admin, admin-group provisioning, "Activate with SSO", and "Turn off
  password login". Activating with SSO also clears any old password, because a reset link may be
  the answer to a compromise.
- **Getting a password back**: from Settings after re-authenticating through the IdP, or through
  an admin reset link or admin recovery, which both already end by setting a password. That is
  also the way back in if the IdP breaks, because the login form stays available.
- **Picsou's TOTP only protects the password.** It is hidden for password-less accounts and
  deleted when the password is removed.
- **Re-authentication proof**: a 5-minute `reauth_proof` cookie, a JWT with `type=reauth`, `uid`
  and `tv`. It is minted either by:
  - `POST /api/auth/reauth` with the password, plus the TOTP code when 2FA is on (rate-limited per
    user), or
  - an SSO round trip with `prompt=login&max_age=0` whose ID-token `auth_time` is no older than
    the start of that round trip, with 60 s of clock-skew tolerance. A missing `auth_time` is
    refused.
- **Re-authentication is required for**:
  - data export, which still accepts the inline password/TOTP it takes today;
  - turning password login off or setting a password;
  - linking or unlinking an SSO identity.

  Linking requires it because, otherwise, a stolen session could link the attacker's own IdP
  account and keep access permanently.
- **Guards**: password login can only be turned off once a linked identity has completed an SSO
  login, and an account without a password cannot unlink its last identity.
- Turning password login off or on increments `tv`, revokes Remember-Me sessions and re-issues
  cookies for the current browser, exactly like `change-password`.

## Architecture & flow

```
Login
 1. SPA /login → "Sign in with <provider>"
    → GET /oauth2/sso/authorization/oidc?intent=login&redirect=/x&rememberMe=1
 2. SsoStartGuardFilter: rate limit, intent checks (setup state, signed-in user, invitation),
    lazy discovery (IdP down → /login?ssoError=IDP_UNAVAILABLE)
 3. SsoAuthorizationRequestResolver: state + nonce + PKCE S256 (+ prompt=login&max_age=0 for
    reauth); the intent is stored with the authorization request
 4. → IdP login (+ IdP MFA) → GET /oauth2/sso/code/oidc?code=…&state=…
 5. Spring: state check, code + verifier exchange, ID token (signature, nonce), userinfo merge
 6. SsoAuthenticationSuccessHandler: resolve account → role sync → SessionIssuer cookies
    → 302 /login/sso-complete?redirect=/x   SPA clears client state, probes the session, navigates
    → 302 /oauth2/authorize?…              iOS / remote-MCP authorize flows resume directly

Re-authentication (password-less user, e.g. before an export)
 … ?intent=reauth&redirect=/settings?resume=export → IdP forced login → auth_time check
 → reauth_proof cookie → /settings?resume=export → the dialog resumes

First admin (fresh instance, SSO_ADMIN_GROUP set)
 /setup/admin → "Continue with <provider>" → intent=setup → admin group required
 → SetupService.seedSsoAdmin (same CAS + SERIALIZABLE guards as seedAdmin) → cookies
 → /setup/security → the rest of the wizard is unchanged
```

Two framework facts shape this flow:

- **`SetupFilter` does not protect the SSO chain.** `SecurityConfig` adds it only to the API
  chain. As a `@Component` filter, Spring Boot also registers it for every request, but after
  Spring Security's filter chain, so it never runs for a request the SSO chain answers itself
  (redirect to the IdP, callback). The setup checks live in `SsoStartGuardFilter` and are
  re-checked in the success handler.
- **The SSO chain is a third `SecurityFilterChain`** at `@Order(0)`, matching `/oauth2/sso/**`
  only. That path is disjoint from the Authorization Server's endpoint matcher (`@Order(1)`) and
  falls under the existing nginx `location /oauth2` block. Its paths are deliberately distinct
  from the Authorization Server's `/oauth2/authorize`.

## Components & files

### Backend: new

- `config/SsoProperties.java`: binds `app.sso.*`. Never fails boot: an invalid value logs an
  ERROR and disables SSO.
- `config/SsoEnabledCondition.java`: gates every SSO bean on the same rules.
- `config/SsoClientRegistrationRepository.java`: lazy OIDC discovery, cached, retried with a
  backoff. Uses `requireProofKey(true)`; the client is public when no secret is set. Redirect URI
  `{baseUrl}/oauth2/sso/code/{registrationId}`.
- `config/SsoSecurityConfig.java`: the `@Order(0)` chain with `oauth2Login`, custom base URIs,
  and an `OidcUserService` that always calls userinfo.
- `config/SsoStartGuardFilter.java`, `config/SsoAuthorizationRequestResolver.java`,
  `config/SsoAuthorizationRequestRepository.java` (session-backed; exposes the removed request to
  the handlers).
- `config/SsoAuthenticationSuccessHandler.java`, `config/SsoAuthenticationFailureHandler.java`.
- `config/SafeRedirect.java`: same-origin relative paths only.
- `service/SessionIssuer.java`: extracted from `AuthController`.
- `service/UsernameDeriver.java`: extracted from `FamilyService.deriveUsername`.
- `service/ActivationService.java`: the shared body of `AuthController.activate`, so the password
  and SSO activations cannot drift apart.
- `service/SignInMethodsService.java`: business logic behind `SignInMethodsController`.
- `service/sso/SsoIdentity.java`, `service/sso/SsoIntent.java`: records.
- `service/sso/SsoFlowException.java`: carries an `ssoError` code. The SSO handlers turn it into a
  redirect; it never reaches `GlobalExceptionHandler`.
- `service/sso/GroupsClaimParser.java`, `service/sso/SsoRoleSyncService.java`,
  `service/sso/SsoAccountService.java`.
- `model/UserIdentity.java`, `repository/UserIdentityRepository.java`.
- `controller/SignInMethodsController.java`, under `/api/auth`: `sso`, `sign-in-methods`,
  `reauth`, `password`, `identities`.
- `controller/AdminIdentityController.java`: `DELETE /api/admin/members/{memberId}/identities`.
- `exception/SignInMethodException.java`, carrying an HTTP status and a stable `code`.
- DTOs: `SsoInfoResponse`, `SignInMethodsResponse`, `LinkedIdentityResponse`,
  `SetPasswordRequest`, `SetupSsoInfo`, `LogoutResponse`.

### Backend: modified

- `AuthController`: delegates to `SessionIssuer`; `refresh` carries `sso_at` over and enforces
  the window; `logout` returns `endSessionUrl` when IdP logout is on.
- `PersistentTokenAuthFilter`: SSO series skip the local 2FA re-check, and are revoked once past
  the window.
- `JwtUtil`: `sso_at` on refresh tokens; the `reauth` proof token.
- `AuthCookieWriter`: the `reauth_proof` cookie.
- `PersistentSessionService.issue` and `PersistentSession`: `ssoAuthenticatedAt`.
- `ReAuthService`, `MeExportController`: accept the proof.
- `SetupService.seedSsoAdmin`, `SetupController`: `sso` in the status, password admin refused
  when the group is set.
- `SetupStatusResponse`.
- `FamilyService` and `FamilyMemberResponse`: `hasPassword`, `ssoLinked`.
- `AppUserRepository.findAllByRoleForUpdate`.
- `RateLimitConfig`: `ssoStartBuckets`, `reauthBuckets`.
- `SecurityConfig`: permit `GET /api/auth/sso`.
- `GlobalExceptionHandler`.
- `application.yml`, `pom.xml` (`spring-boot-starter-oauth2-client`, managed by the BOM).

### Database

- `V122__sso_identities.sql`, the next free number on `main` (to re-verify across branches before
  merging): table `user_identity (id, user_id → app_user ON DELETE CASCADE, issuer, subject, email,
  display_name, last_login_at, created_at, updated_at)` with
  `CONSTRAINT uk_user_identity_issuer_subject UNIQUE (issuer, subject)` and
  `idx_user_identity_user_id`, plus `persistent_session.sso_authenticated_at TIMESTAMPTZ NULL`.

### Frontend

- New:
  - `features/sso/{api,hooks,urls}.ts` and `lib/sso-revalidate.ts`;
  - `pages/login/SsoCompletePage.tsx` (route `/login/sso-complete`, outside the guards, like
    `/consent`);
  - `pages/settings/security/{SignInMethodsCard,ReauthPanel,RemovePasswordDialog,SetPasswordDialog,UnlinkIdentityDialog}.tsx`.
- Modified:
  - `LoginPage`: SSO button, `ssoError`, Remember Me passed through, automatic restart on
    `SSO_REVALIDATE`;
  - `features/auth/{api,hooks}.ts`: `endSessionUrl`;
  - `lib/api-client.ts`;
  - `SecuritySection`: sign-in methods card, MFA card hidden without a password, `resume`
    handling;
  - `ExportDataDialog`: uses `ReauthPanel`;
  - `SetupStepAdmin`: SSO mode;
  - `SetupStepComplete`: session-probe fallback when no credentials are stashed;
  - `ActivationPage`: "Activate with SSO". Its existing hardcoded English copy moves to i18n keys
    in the same change;
  - `MembersSection`: sign-in method badges and "Unlink SSO";
  - `features/family/api.ts`, `features/setup/api.ts`, `app/routes.tsx`, `demo/index.ts`, and the
    four locale files.

### Dev and proxy

- `frontend/vite.config.ts`: proxy `/oauth2` with `xfwd: true`, so Spring builds the dev
  `redirect_uri` on `:5173` rather than `:8080`. This relies on Tomcat honoring the forwarded
  headers, which since #348 it only does for the loopback peers in
  `server.tomcat.remoteip.internal-proxies`. The Vite proxy connects over loopback when the
  backend runs on the same machine.
- nginx: no change; `location /oauth2` already forwards `/oauth2/sso/**` with the forwarded
  headers.

## Configuration

| Variable                       | Default                | Meaning                                                                                                                                                                                                     |
| ------------------------------ | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SSO_ISSUER_URI`               | —                      | OIDC issuer. SSO is on when this and `SSO_CLIENT_ID` are set                                                                                                                                                |
| `SSO_CLIENT_ID`                | —                      | Client ID registered at the IdP                                                                                                                                                                             |
| `SSO_CLIENT_SECRET`            | —                      | Blank → public client (PKCE only); set → `client_secret_basic` + PKCE                                                                                                                                       |
| `SSO_PROVIDER_NAME`            | `SSO`                  | Button label ("Sign in with Zitadel")                                                                                                                                                                       |
| `SSO_SCOPES`                   | `openid,profile,email` | `openid` is always added. Add `groups` for IdPs that need it (Pocket ID, Authelia); do **not** add it with Zitadel and the groups Action                                                                    |
| `SSO_GROUPS_CLAIM`             | `groups`               | Literal claim name, e.g. `urn:zitadel:iam:org:project:roles`                                                                                                                                                |
| `SSO_ADMIN_GROUP`              | —                      | Turns on SSO first admin, admin provisioning and role sync                                                                                                                                                  |
| `SSO_SESSION_REVALIDATE_AFTER` | `24h`                  | Maximum age of an SSO session before it must pass through the IdP again                                                                                                                                     |
| `SSO_IDP_LOGOUT`               | `false`                | Also end the IdP session on logout (RP-initiated logout with `client_id` and `post_logout_redirect_uri`)                                                                                                    |

To register at the IdP: redirect URI `https://<host>/oauth2/sso/code/oidc`, and post-logout URI
`https://<host>/login` when `SSO_IDP_LOGOUT=true`.

## Security properties (must hold)

- **S1. The IdP only authenticates.** Picsou never stores or forwards IdP tokens. Picsou's
  cookies, `tv` revocation, cross-site request check and member scoping are unchanged.
- **S2. Binding by `(iss, sub)` only.** Email and username are display data and never used for
  matching.
- **S3. Handshake integrity.** `state` is bound to the handshake session; `nonce` and PKCE S256
  are always sent; the handshake session is discarded at the callback.
- **S4. No account without a trust path.** An account is only created from an invitation token,
  admin-group membership, or the setup bootstrap. Everything else is refused.
- **S5. Credential changes need a fresh re-authentication**: link, unlink, turning password login
  off or on, export.
- **S6. Fresh means fresh.** SSO re-authentication requires
  `auth_time ≥ round-trip start − 60 s` from the ID token. A missing `auth_time` is refused.
- **S7. Admin rights follow the group within a bounded delay.** They are synced at each SSO login,
  and an SSO session must pass through the IdP again after the window. A missing claim counts as
  no groups, and the last admin is never demoted.
- **S8. Bootstrap only through the group.** When `SSO_ADMIN_GROUP` is set,
  `POST /api/setup/admin` is refused server-side; otherwise whoever reaches a fresh instance first
  could still claim it with a password.
- **S9. No lockout path.** The password form stays. Admin recovery and reset links re-create a
  password. Password login can only be turned off with an identity that has already signed in,
  and an account without a password cannot unlink its last identity.
- **S10. Redirect safety.** Targets are same-origin relative paths only (no `//`, no scheme, no
  CR/LF). `/oauth2/` targets go straight to the Authorization Server.
- **S11. Scoped 2FA bypass.** SSO-born Remember-Me series skip the local 2FA re-check, but never
  count as trusted devices for a later password login on the same browser.
- **S12. Log hygiene.** Every IdP-supplied value (`sub`, `preferred_username`, groups, error
  descriptions) is logged through `LogSanitizer.safe`.
- **S13. Backwards compatibility.** Without `SSO_ISSUER_URI` there are no SSO beans, chain or UI.

## Error handling

- **Callback and start errors** redirect with `ssoError=<CODE>` to the page the flow came from:
  `/login`, `/setup/admin`, `/activate/{token}`, or the `redirect` of a link or reauth. Codes:
  `NOT_LINKED`, `NOT_IN_ADMIN_GROUP`, `IDENTITY_IN_USE`, `REAUTH_REQUIRED`, `REAUTH_TOO_OLD`,
  `REAUTH_WRONG_ACCOUNT`, `ACCOUNT_INACTIVE`, `ADMIN_RECOVERY_ACTIVE`, `ACTIVATION_INVALID`,
  `SETUP_ALREADY_CLAIMED`, `IDP_UNAVAILABLE`, `IDP_ERROR`, `SESSION_EXPIRED` (handshake session
  gone), `RATE_LIMITED`.
- **API errors** are ProblemDetail responses with a stable `code`, per
  [api-rest.md](../conventions/api-rest.md):
  - `SSO_ADMIN_REQUIRED` (409, setup);
  - `IDENTITY_NOT_USED_YET` (409, turning password login off before any SSO login);
  - `LAST_SIGN_IN_METHOD` (409, unlinking the last identity of an account without a password);
  - `PASSWORD_ALREADY_SET` (409);
  - `REAUTH_FAILED` (401), for a missing, expired or wrong proof. This is the existing
    `ReAuthFailedException` title, now also exposed as `code`;
  - `SSO_REVALIDATE` (401 on refresh).
- The frontend translates every code (`ssoErrors.*`) and never shows raw `detail` text, per
  [error-handling.md](../conventions/error-handling.md).

## Zitadel (the target IdP): verified facts

- **Re-authentication.** Zitadel documents `prompt=login` and `max_age`, where `max_age=0` forces
  a new login ([endpoints](https://zitadel.com/docs/apis/openidoauth/endpoints)), and its ID tokens
  carry `auth_time` and `amr` ([claims](https://zitadel.com/docs/apis/openidoauth/claims)). Whether
  a forced login asks for the second factor again depends on the password and second-factor
  "check lifetimes" in the Zitadel login settings.
- **Groups via the Action from the ArgoCD guide.** The grants Zitadel passes to the Action belong
  to the app's own project only: the grants query filters on `project_id = any($3)`
  ([`userinfo_by_id.sql`](https://github.com/zitadel/zitadel/blob/main/internal/query/userinfo_by_id.sql)),
  and `$3` is built from the client's project in
  [`prepareRoles`](https://github.com/zitadel/zitadel/blob/main/internal/api/oidc/userinfo.go).
  Operator rules that follow:
  - Give Picsou **its own project**. A role shared with other apps grants Picsou admin.
  - Enable **"Assert Roles on Authentication"** on that project. Without it the Action receives
    no grants.
  - Attach the Action to **"Pre Userinfo creation"**. Picsou always calls userinfo.
  - **Do not request the `groups` scope.** In recent Zitadel versions it fills `groups` from
    Zitadel's own user groups ([#11009](https://github.com/zitadel/zitadel/pull/11009)), and an
    Action cannot overwrite an existing claim.
- **Actions v1 are deprecated and planned for removal in v5**
  ([migration guide](https://zitadel.com/docs/guides/integrate/actions/migrate-from-v1)). Picsou
  works without any Action through `SSO_GROUPS_CLAIM=urn:zitadel:iam:org:project:roles`: the
  object-keys rule reads the role keys of Picsou's project.

## Testing

Backend (`mvn test`):

- **Mockito unit tests** for `GroupsClaimParser`, `SsoRoleSyncService`, `SsoAccountService`, the
  guard, resolver and handlers, `SessionIssuer`, `ReAuthService`, `SignInMethodsController`,
  `AdminIdentityController`, `SetupService.seedSsoAdmin`, `JwtUtil` and `SafeRedirect`.
- **MockMvc** for HTTP-level behavior: the chain exists only when SSO is configured, routing of
  `/oauth2/sso/**`, and `GET /api/auth/sso` being public.
- **One Testcontainers `@SpringBootTest` integration test** (pattern:
  `Oauth2ConsentHandshakeIntegrationTest`) against an in-process **`FakeOidcProvider`**: a test
  helper built on the JDK `HttpServer` and Nimbus, both already on the classpath. It serves
  discovery, JWKS, authorize, token (checking the PKCE verifier against the challenge) and
  userinfo, echoes the nonce, and lets each test choose `auth_time` and groups.

Frontend (`bunx vitest run`, a clean `bun run build`, `bun run lint` at zero warnings): login
button and errors, the SSO-complete page, the sign-in methods card and dialogs, `ReauthPanel`, the
setup SSO mode, activation with SSO, member badges, the api-client revalidation flag, the logout
redirect, and locale parity.

Manual end-to-end against Zitadel: the runbook at the end of the plan.

## Framework-version caveats (confirm during implementation)

- `ClientRegistration.ClientSettings.requireProofKey` is in Spring Security 6.5 (Boot 3.5.16
  BOM). If it is missing, use `OAuth2AuthorizationRequestCustomizers.withPkce()` in the resolver.
- `OidcUserService#setRetrieveUserInfo` (Spring Security ≥ 6.3) makes Spring call userinfo even
  when the granted scopes do not include `profile` or `email`.
- `ClientRegistrations.fromIssuerLocation` performs network calls, so it is only ever called
  inside the lazy repository, never at startup.
- Spring's back-channel logout support is tied to server-side sessions, so it is not used (it is
  a non-goal).
- The fake IdP is hand-written rather than `no.nav.security:mock-oauth2-server`, whose 6.x line
  brings Jackson 3 and Kotlin onto a Boot 3.5 test classpath.
- Vite 7's proxy must forward `X-Forwarded-Host/Proto` (`xfwd: true`); verify that the dev
  `redirect_uri` reads `https://localhost:5173/…`. With a `VITE_API_TARGET` on another machine,
  Tomcat ignores those headers (non-loopback peer) and the redirect URI names the backend host.

## Verification limits

- The real Zitadel handshake, the "check lifetime" behavior on forced re-login, and the Action's
  output can only be verified manually against the operator's instance.
- The integration test needs Docker. It self-skips locally without Docker, and CI requires it
  (`PICSOU_REQUIRE_DOCKER_TESTS=true`).

## Effort

| Phase                                                  | Developer-days |
| ------------------------------------------------------ | -------------- |
| 1. Groundwork (schema, properties, two extractions)    | 2.5–3.5        |
| 2. SSO login core, role sync, re-validation            | 6–8            |
| 3. Password-less accounts, re-auth, linking, invites   | 4–5.5          |
| 4. First admin through SSO                             | 1.5–2          |
| 5. Frontend                                            | 5–7            |
| 6. Integration test, dev/infra, docs                   | 3–4            |
| **Total**                                                  | **≈22–30**         |

## Follow-ups

- An optional policy flag requiring `amr` to contain `mfa` for SSO logins or re-authentication.
- An instance-status alert when IdP discovery fails, and a read-only view of the SSO settings in
  the Admin page.
- An issuer-migration helper for when `SSO_ISSUER_URI` changes.
- A change-password UI; the account-deletion endpoint with the same re-authentication, unless
  #355 removes the frontend slice.
- The Vite dev config's `/mcp` proxy entry sits outside `server.proxy` (pre-existing).
- ADR (amending the 2026-01-01 and 2026-07-03 external-IdP reasoning) and the feature note
  `docs/features/sso-login.md`: the last task of the plan.
