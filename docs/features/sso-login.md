# Feature: SSO login (OIDC), alongside passwords

> Last updated: 2026-11-06

## Context

Operators who already run an identity provider can let people sign in to Picsou with it, set up a
new instance without any Picsou password, and manage who is an administrator from an IdP group.
SSO is optional and additive: without `SSO_ISSUER_URI` and `SSO_CLIENT_ID`, nothing changes. The
decision and its limits are recorded in the
[ADR](../decisions/2026-11-04-sso-login-alongside-passwords.md); the design is in the
[brief](../briefs/2026-10-31-sso-login-design.md).

## How it works

The IdP only authenticates. Spring Security's OIDC client runs the handshake (PKCE S256, `state`,
`nonce`, ID-token signature, userinfo merge) on a dedicated filter chain, then Picsou resolves an
account by `(iss, sub)` and issues its own cookies through `SessionIssuer`, the same code a
password login uses. IdP tokens are never kept: the authorized-client repository discards them.

### The SSO chain

`SsoSecurityConfig` adds a third `SecurityFilterChain` at `@Order(0)` matching `/oauth2/sso/**`
only (registration id `oidc`). It is disjoint from the Authorization Server's endpoints
(`@Order(1)`, `/oauth2/authorize`, `/oauth2/token`...) and from the API chain. It:

- answers only two paths, both as form POSTs: `POST /oauth2/sso/authorization/oidc` (start) and
  `POST /oauth2/sso/code/oidc` (callback processing). `SsoStartGuardFilter` redirects any GET, and
  any other path, to `/login`;
- applies the API chain's cross-site check **without** its auth-cookie condition
  (`CrossSiteCookieRequestMatcher` in mode `false`), so a cross-site POST gets 403
  `Cross-site request rejected` even from an anonymous browser. An origin on the CORS allow-list
  passes, as on the API chain;
- stores no `SecurityContext` (request-attribute repository), no saved request, and no IdP token;
- sends the same response headers as the API and authorization-server chains, through
  `SecurityConfig`'s shared customizer (CSP, Permissions-Policy, frame and content-type options,
  referrer policy, the HSTS gate, `no-store`).

### Flows

```
Login
 1. /login "Sign in with <provider>" submits a hidden form (features/sso/start.ts):
    POST /oauth2/sso/authorization/oidc   intent=login, redirect=/x, rememberMe=1
 2. SsoStartGuardFilter: POST only, 30 starts / IP / 15 min, intent checks, lazy discovery
    (IdP unreachable -> /login?ssoError=IDP_UNAVAILABLE)
 3. SsoAuthorizationRequestResolver: state + nonce + PKCE S256; the SsoIntent is stored with the
    authorization request in the handshake session (JSESSIONID)
 4. IdP sign-in (+ IdP MFA) -> GET /login/sso/callback?code=...&state=...
    SsoCallbackPage clears the URL and re-posts every received parameter
 5. POST /oauth2/sso/code/oidc   Spring: state, code + verifier, ID token, userinfo
 6. SsoAuthenticationSuccessHandler: resolve account -> role sync -> SessionIssuer cookies
    -> 302 /login/sso-complete?redirect=/x   (SsoCompletePage resets client state, calls
                                              POST /api/auth/refresh, then navigates)
    -> 302 /oauth2/authorize?...             (iOS / remote-MCP authorizations resume directly)
    The handshake session is invalidated, success or failure.

Re-authentication (account without a password, e.g. before an export)
 ReauthPanel -> POST ... intent=reauth, redirect=/settings?resume=export
 -> IdP with prompt=login&max_age=0 -> callback -> identity linked to the signed-in user?
 -> auth_time >= start - 60 s? -> reauth_proof cookie -> /settings?resume=export
 -> SecuritySection reopens the export dialog, which the proof now authorizes

Linking (Settings -> Sign-in methods)
 reauth_proof first (password + TOTP, or an SSO reauth) -> POST ... intent=link
 -> proof checked again at the callback -> identity bound -> /settings?sso=linked

Activation (invitation link /activate/{token}, never-activated accounts only)
 acknowledgement ticked -> POST ... intent=activate, token=..., ack=1
 -> account activated without a password, identity bound, local TOTP removed
 -> /login/sso-complete

First admin (fresh instance, SSO_ADMIN_GROUP set)
 /setup/admin -> "Continue with <provider>" -> POST ... intent=setup
 -> admin group required -> SetupService.seedSsoAdmin (same CAS + SERIALIZABLE guards as the
    password seed) -> session-scoped cookies -> /setup/security
```

The SPA routes `/login/sso/callback` and `/login/sso-complete` sit outside every guard, like
`/consent`. The callback page changes nothing itself; it only turns the IdP's GET into a
same-origin POST, as the bank OAuth callback does.

### Account resolution at the callback

| Situation | Result |
|-----------|--------|
| `login`: `(iss, sub)` linked to an account | That account. Deactivated: `ACCOUNT_INACTIVE`, or `ADMIN_RECOVERY_ACTIVE` for an admin while admin recovery is on |
| `login`: not linked, setup complete, `SSO_ADMIN_GROUP` set, identity in the group | New password-less ADMIN account (audited `sso.user.provisioned`) |
| `setup`: setup at `PENDING_ADMIN`, identity in the admin group | First admin, password-less. Not in the group: `NOT_IN_ADMIN_GROUP`; lost race: `SETUP_ALREADY_CLAIMED` |
| `activate`: valid, unexpired invitation token of an account that is not activated yet | Identity bound to the invited account; account activated with an empty password hash. Identity owned by another account: `IDENTITY_IN_USE`. The invitation row is locked until commit, so a concurrent activation of the same invitation finds the token gone: `ACTIVATION_INVALID` here, the invalid-token answer on the password path. A token held by an already activated account (an admin's password-reset link) is `ACTIVATION_INVALID` too, see below |
| `link`: signed-in user, valid `reauth_proof` at start and callback | Identity bound to that account (`last_login_at` stays empty until its first SSO sign-in) |
| `reauth`: signed-in user, identity already linked to them, fresh `auth_time` | `reauth_proof` set; no new session |
| Anything else | `NOT_LINKED`. No account is ever matched by email or username |

SSO activation is for invitations, that is accounts that were never activated, and for nothing
else. An admin's password-reset link (`POST /api/family/members/{id}/reset-password`) puts the same
kind of token on an account that is already activated; it only allows setting a new password on
`/activate/{token}`. Honouring it for SSO would let whoever holds the link replace that account's
password and second factor with their own IdP identity, so the start guard and
`SsoAccountService.activateWithIdentity` (on the row locked by `FOR UPDATE`) both refuse it with
`ACTIVATION_INVALID`. An admin-recovery link is different: recovery deactivates the account
first, so it is accepted like an invitation (its holder read the server log).

Every callback that resolves an account syncs the admin role (see below). Errors redirect with
`ssoError=<CODE>` to the page the flow came from (`/login`, `/activate/{token}`, `/setup/admin`,
or the `redirect` of a `link` / `reauth`). The full code list is in
[API.md](../../backend/docs/API.md#sso-error-codes); the SPA translates the codes it can display
(`ssoErrors.*`).

### Admin group and role sync

Active only when SSO is enabled and `SSO_ADMIN_GROUP` is set (`SsoProperties.adminGroupManaged`).

- `GroupsClaimParser` reads the claim named by `SSO_GROUPS_CLAIM` as a literal name: an array of
  strings, a single string, or an object whose keys are the groups. Anything else, or a missing
  claim, counts as "no groups" and logs `sso.groups-claim-missing` at WARN.
- `SsoRoleSyncService.sync`: in the group means ADMIN, otherwise MEMBER. A demotion locks the
  admin rows (`AppUserRepository.findAllByRoleForUpdate`, `PESSIMISTIC_WRITE`) and is skipped for
  the last admin (`sso.role.demotion-skipped-last-admin`). Audit events: `sso.role.promoted`,
  `sso.role.demoted`.
- The role is read from the database on every request, so a change applies to the next request.
- `POST /api/setup/admin` is refused with 409 `SSO_ADMIN_REQUIRED` whenever `SSO_ADMIN_GROUP` is
  set, even if the rest of the SSO configuration is invalid (fail closed:
  `adminGroupConfigured`). `DataSeeder` logs a WARN when `APP_USERNAME`/`APP_PASSWORD_HASH` are
  set together with `SSO_ADMIN_GROUP`; the environment admin wins.

### Session marker and re-validation

- `SessionIssuer.issue(..., ssoAuthenticatedAt, ...)` puts the SSO sign-in time in the `sso_at`
  claim of the refresh token and the access token, and in
  `persistent_session.sso_authenticated_at` for a Remember-Me series. Refresh carries it over
  rather than restarting the window.
- Once `now - sso_at` exceeds `SSO_SESSION_REVALIDATE_AFTER`, or while SSO is disabled,
  `POST /api/auth/refresh` clears the auth cookies and answers 401 `code=SSO_REVALIDATE`, and
  `PersistentTokenAuthFilter` revokes the series instead of restoring it. The SPA marks the flag
  (`lib/sso-revalidate.ts`) and `/login` restarts the IdP round trip on its own, with the
  Remember-Me choice the user made last time. While the IdP session is alive this is silent,
  and it re-reads the groups.
- SSO-born series skip the local 2FA re-check but are never `trusted_for_2fa`, so they never let
  a later password login on the same browser skip TOTP.
- Logout: with `SSO_IDP_LOGOUT=true`, an SSO session and a discovered `end_session_endpoint`,
  `POST /api/auth/logout` answers 200 `{ "endSessionUrl": ... }` (with `client_id` and
  `post_logout_redirect_uri` = the request's origin + `/login`) and the SPA navigates there.
  Otherwise 204, as before.

### Password-less accounts and re-authentication

- A password-less account has `password_hash = ''`; the password login rejects it in constant
  time, as it does for a pending activation. No new column.
- `reauth_proof` (Max-Age 300) is a JWT with `type=reauth`, `uid` and `tv`, minted by
  `POST /api/auth/reauth` (password, plus TOTP when 2FA is on; 5 attempts / user / 15 min) or by
  an SSO `reauth` round trip. It is required by `DELETE /api/auth/password`,
  `PUT /api/auth/password`, `DELETE /api/auth/identities/{id}` and the `link` intent, and
  accepted by `POST /api/me/export` in place of the inline password or TOTP.
- Guards (`SignInMethodsService`): turning password login off needs SSO enabled (409
  `SSO_DISABLED`) and an identity of the **current** issuer with a completed SSO sign-in (409
  `IDENTITY_NOT_USED_YET`); it also removes local TOTP. An account without a password cannot
  unlink its last identity of the current issuer (409 `LAST_SIGN_IN_METHOD`). Setting a password
  on an account that has one: 409 `PASSWORD_ALREADY_SET`.
- Turning password login off or setting a password bumps `tv`, revokes every Remember-Me series
  and re-issues session-scoped cookies for the current browser, like `change-password`.
- An admin can remove every identity of another member
  (`DELETE /api/admin/members/{memberId}/identities`, no proof, not on themselves). It bumps the
  member's `tv` and revokes their series. Self-targeting returns a 403 ProblemDetail with
  `code=SELF_UNLINK_FORBIDDEN`; the admin UI hides this action for the current member.
- A password reset does not remove linked identities: the member can still sign in with the IdP
  after it. If the IdP account is the thing to cut off, the admin removes the identities with
  `DELETE /api/admin/members/{memberId}/identities`.

### Configuration

All values come from environment variables, mapped in `application.yml` under `app.sso.*`.
`SsoProperties` never fails startup: an invalid value logs an ERROR and leaves SSO off.

| Variable | Default | Meaning |
|----------|---------|---------|
| `SSO_ISSUER_URI` | (none) | OIDC issuer, an absolute `http(s)` URI. SSO is on when this and `SSO_CLIENT_ID` are set. A plain `http` issuer is accepted, but logs a WARN at startup unless the host is loopback (`localhost`, `127.0.0.0/8`, `::1`): the client secret and tokens then cross the network unencrypted |
| `SSO_CLIENT_ID` | (none) | Client ID registered at the IdP |
| `SSO_CLIENT_SECRET` | (none) | Blank: public client, PKCE only. Set: `client_secret_basic` plus PKCE |
| `SSO_PROVIDER_NAME` | `SSO` | Button label ("Sign in with Zitadel"), trimmed to 40 characters |
| `SSO_SCOPES` | `openid,profile,email` | Comma-separated; `openid` is always added first |
| `SSO_GROUPS_CLAIM` | `groups` | Literal claim name, e.g. `urn:zitadel:iam:org:project:roles` |
| `SSO_ADMIN_GROUP` | (none) | Turns on the SSO first admin, admin provisioning and role sync |
| `SSO_SESSION_REVALIDATE_AFTER` | `24h` | Maximum age of an SSO session before it passes through the IdP again (Spring duration, must be positive) |
| `SSO_IDP_LOGOUT` | `false` | Also end the IdP session on logout (RP-initiated logout) |

Register at the IdP: the redirect URI `https://<host>/login/sso/callback`, and the post-logout
URI `https://<host>/login` when `SSO_IDP_LOGOUT=true`. The same variables are documented in the
README and `docker/.env.example`.

### Zitadel guide

1. Create a **project dedicated to Picsou**. Zitadel only passes the grants of the client's own
   project to an Action; a role shared with other apps would make their users Picsou admins.
2. In that project, enable **"Assert Roles on Authentication"** and create the admin role (for
   example `picsou-admin`). Grant it to the administrators.
3. Create an application of type **Web**, authentication method **PKCE** (public client) or
   **Basic** (set `SSO_CLIENT_SECRET`). Redirect URI `https://<host>/login/sso/callback`;
   post-logout URI `https://<host>/login` if you use `SSO_IDP_LOGOUT=true`.
4. Pick how the groups reach Picsou:
   - **Without an Action** (recommended, Actions v1 are deprecated):
     `SSO_GROUPS_CLAIM=urn:zitadel:iam:org:project:roles`. The claim is an object whose keys are
     the role keys, which Picsou reads as groups.
   - **With the groups Action** from Zitadel's ArgoCD guide: attach it to **"Pre Userinfo
     creation"** (Picsou always calls userinfo) and keep `SSO_GROUPS_CLAIM=groups`. Do **not**
     add `groups` to `SSO_SCOPES`: recent Zitadel versions then fill `groups` themselves, and an
     Action cannot overwrite an existing claim.
5. Set `SSO_ISSUER_URI` to the instance URL exactly as its discovery document spells `issuer`,
   `SSO_CLIENT_ID`, `SSO_PROVIDER_NAME=Zitadel` and `SSO_ADMIN_GROUP=picsou-admin`.

Whether a forced re-authentication asks for the second factor again depends on the password and
second-factor "check lifetimes" of the Zitadel login settings. Other IdPs (Pocket ID, Authelia)
usually need `groups` in `SSO_SCOPES`.

With `SSO_IDP_LOGOUT=true`, Zitadel shows its own sign-out confirmation page where the user picks
the account to sign out, then returns to Picsou's login page (verified against a real Zitadel
instance).

### Key files

Backend (`backend/src/main/java/com/picsou/`):

- `config/SsoProperties.java`, `config/SsoEnabledCondition.java`: settings and the single
  "is SSO on" rule shared by every SSO bean.
- `config/SsoClientRegistrationRepository.java`: lazy discovery, cached on success, retried
  after 60 s on failure; one discovery at a time and outside any lock, so a lookup arriving while
  it runs answers "unavailable" at once instead of holding a worker; PKCE required; redirect URI
  `{baseUrl}/login/sso/callback`.
- `config/SsoSecurityConfig.java`: the `@Order(0)` chain.
- `config/SsoStartGuardFilter.java`: POST-only, rate limit, intent checks.
- `config/SsoAuthorizationRequestResolver.java`, `config/SsoAuthorizationRequestRepository.java`:
  intent attached to the authorization request; `prompt=login&max_age=0` for `reauth`.
- `config/SsoAuthenticationSuccessHandler.java`, `config/SsoAuthenticationFailureHandler.java`.
- `config/SafeRedirect.java`: same-origin relative paths only.
- `service/sso/{SsoAccountService,SsoRoleSyncService,GroupsClaimParser,SsoIdentity,SsoIntent,SsoFlowException}.java`.
- `service/SessionIssuer.java`, `service/ActivationService.java`, `service/SignInMethodsService.java`,
  `service/ReAuthService.java`, `service/UsernameDeriver.java`.
- `controller/SignInMethodsController.java` (`/api/auth/sso`, `sign-in-methods`, `reauth`,
  `password`, `identities/{id}`), `controller/AdminIdentityController.java`.
- `model/UserIdentity.java`, `repository/UserIdentityRepository.java`,
  `export/LinkedIdentitiesExporter.java`, `db/migration/V128__sso_identities.sql`.

Frontend (`frontend/src/`):

- `features/sso/{api,hooks,start,errors}.ts`, `lib/sso-revalidate.ts`, and `safeRedirect` /
  `isOAuthAuthorizeRedirect` in `lib/utils.ts`.
- `pages/login/{LoginPage,SsoCallbackPage,SsoCompletePage,SsoProgressScreen}.tsx`.
- `pages/settings/security/{SignInMethodsCard,ReauthPanel,RemovePasswordDialog,SetPasswordDialog,UnlinkIdentityDialog,ExportDataDialog,SecuritySection}.tsx`.
- `pages/setup/{SetupStepAdmin,SetupAdminSso,SetupStepComplete}.tsx`,
  `pages/activation/ActivationPage.tsx`,
  `pages/admin/sections/{MembersSection,MemberSummary,UnlinkSsoDialog}.tsx`.
- `features/auth/guards.tsx`: `PublicOnly` resumes an `/oauth2/` authorization after its own
  session probe.

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Picsou issues its own cookies after the callback | Keeps `tv` revocation, Remember Me, member scoping and the iOS/MCP token format unchanged | IdP tokens accepted by the API |
| Binding by `(iss, sub)` only | Email and username are IdP-controlled display data | Matching by email (account takeover) |
| Start and callback as same-origin form POSTs | "Never change state on GET"; a link on another site can neither start nor finish a flow, which matters most for `link` | Spring's default GET endpoints |
| IdP returns to a SPA page, Spring processes at `/oauth2/sso/code/oidc` | Spring compares only `state` and `nonce`, and its token request reuses the authorization request's `redirect_uri` (checked in Spring Security 6.5, pinned by the integration test) | Registering a backend URL as redirect URI (a GET callback) |
| Lazy discovery with a 60 s backoff | Picsou boots and password login works while the IdP is down | `ClientRegistrations.fromIssuerLocation` at startup |
| Userinfo always called | Groups usually come from userinfo; `setRetrieveUserInfo` ignores the granted scopes | Relying on ID-token claims only |
| Missing groups claim = no groups | Zitadel's usual Action omits the claim when the user has no grant | Keeping the current role (a removed admin would stay admin) |
| Hand-written `FakeOidcProvider` in tests | JDK `HttpServer` + Nimbus are already on the classpath | `mock-oauth2-server` 6.x (Jackson 3 and Kotlin on a Boot 3.5 test classpath) |

## Gotchas / Pitfalls

- **`SetupFilter` does not protect the SSO chain.** `SecurityConfig` adds it to the API chain
  only; as a `@Component` it also runs after Spring Security's chains, so it never sees a request
  the SSO chain answers itself. The setup checks therefore live in `SsoStartGuardFilter` (a
  `login` start before setup completes goes to `/setup`; `setup` needs `PENDING_ADMIN`) and are
  re-checked in the success handler (`seedSsoAdmin`'s CAS).
- **Changing `SSO_ISSUER_URI` orphans every link**, because `iss` changes. Old identities show as
  "previous provider" (`currentIssuer=false`) and can never sign in again; admin-group members are
  provisioned as new, empty accounts. Even a trailing slash counts.
- **`SSO_ISSUER_URI` must equal the discovery document's `issuer` exactly.** Spring validates the
  discovered issuer against the configured one, and the ID token's `iss` is what links store. A
  mismatch makes discovery fail, and starts answer `IDP_UNAVAILABLE`.
- **Password sign-ins do not sync roles.** A linked admin removed from the group keeps `ADMIN`
  while they sign in with their password. Turn password login off for admins whose rights must
  follow the group. Removing a person at the IdP does not remove their Picsou password either.
- **A demotion lands within the window, not instantly**: at the next SSO sign-in, which the
  re-validation forces at most `SSO_SESSION_REVALIDATE_AFTER` after the last one.
- **Zitadel's `groups` scope conflicts with the groups Action.** With the scope requested, recent
  Zitadel versions fill `groups` from their own user groups and the Action cannot overwrite it.
- **The IdP returns to a SPA page while Spring processes the result at another URL.** The redirect
  URI registered at the IdP is `/login/sso/callback`, never `/oauth2/sso/code/oidc`. This relies on
  Spring Security 6.5 internals (`OidcAuthorizationCodeAuthenticationProvider` compares only
  `state` and `nonce`; `OAuth2AuthorizationCodeGrantRequest` sends the stored `redirect_uri`);
  `SsoLoginIntegrationTest` pins it, so re-check it on a Spring Security upgrade.
- **One handshake per browser session.** The handshake session holds a single pending
  authorization request. A second start in the same browser (another tab, a double click)
  replaces it, and the first tab's callback then fails with `SESSION_EXPIRED`.
- **The handshake `JSESSIONID`.** The start creates a servlet session holding `state`, `nonce`,
  the PKCE verifier and the intent; the callback invalidates it, success or failure. It is the
  only server-side session in Picsou. The callback response may still carry a new
  `Set-Cookie: JSESSIONID` for an empty session; that is harmless. A cookie-stripping proxy or a
  different browser between start and callback gives `SESSION_EXPIRED`. The stateless API chain
  disables Spring's session-fixation protection (`sessionFixation().none()`): otherwise any
  JWT-authenticated request carrying the handshake `JSESSIONID` (health probe, SPA polling) rotates
  that id, and the callback, sent with the stale id, ends on `SESSION_EXPIRED`.
  `SsoHandshakeSessionRaceIntegrationTest` pins it.
- **App sessions stay bounded without refresh tokens.** The iOS app and the remote-MCP connector
  are public clients and get no refresh token, so each new access token needs a browser
  authorization, which the SSO window governs. A member removed at the IdP keeps app access for
  about 35 minutes at most with the default settings. Enabling refresh tokens for these clients
  needs an app re-validation window first. Details in the
  [ADR](../decisions/2026-11-04-sso-login-alongside-passwords.md#how-app-sessions-stay-bounded).
- **Authorization Server tokens never become web cookies while SSO is on.** Refresh and the
  username change refuse to mint cookies from them, so an app token cannot outlive the window
  through the web session.
- **`link` does not count as a sign-in.** A freshly linked identity has no `last_login_at`, so
  "turn off password login" stays disabled until it has signed in once.
- **Back from the IdP restores the page from the bfcache.** Every "redirecting" state (login
  button, `ReauthPanel`, link dialog) resets on a persisted `pageshow`.
- **Dev proxy.** Vite proxies `/oauth2` and `/api` with `xfwd: true`, so the dev `redirect_uri`
  and the post-logout URI name `:5173`. Tomcat applies those forwarded origin headers from any
  peer (`ForwardedOriginCustomizer`), so this holds with a backend on another machine too.

## Tests

Backend (`mvn test`):

- Unit: `SsoPropertiesTest`, `SsoClientRegistrationRepositoryTest`, `SsoStartGuardFilterTest`,
  `SsoAuthorizationRequestResolverTest`, `SsoAuthorizationRequestRepositoryTest`,
  `SsoAuthenticationSuccessHandlerTest`, `SafeRedirectTest`, `GroupsClaimParserTest`,
  `SsoRoleSyncServiceTest`, `SsoAccountServiceTest`, `SsoIntentTest`, `SessionIssuerTest`,
  `ReAuthServiceTest`, `SignInMethodsServiceTest`, `SignInMethodsControllerTest`,
  `AdminIdentityControllerTest`, `ActivationServiceTest`, `UsernameDeriverTest`,
  `LinkedIdentitiesExporterTest`, plus SSO cases in `AuthControllerTest`, `JwtUtilTest`,
  `PersistentTokenAuthFilterTest`, `CookieBridgeAuthenticationFilterTest`,
  `CrossSiteCookieRequestMatcherTest`, `SetupServiceTest`, `DataSeederTest`.
- Slice: `config/ssoslice/SsoSecurityConfigTest` (the chain exists only when SSO is configured,
  routing, GET and cross-site refusals, allow-listed origins, `GET /api/auth/sso` public).
- Integration: `SsoLoginIntegrationTest`, a Testcontainers `@SpringBootTest` against the in-process
  `FakeOidcProvider` (discovery, JWKS, authorize, token with PKCE check, userinfo). Twenty ordered
  scenarios: first admin, refusals, no auto-link by email, provisioning, demotion and the last
  admin, PKCE and nonce, Remember Me, the window, re-authentication, activation, POST-only
  handshake, discarded IdP tokens, log sanitizing, public app clients receiving no refresh
  token (scenario 15), and a password-reset link that cannot take over an activated account
  (scenario 16), then one rejected callback for each invalid ID token the
  provider can issue: bad signature, wrong audience, wrong issuer, expired (scenarios 17-20).
- **CI skip guard.** The integration test self-skips without Docker, which a green build would
  hide. CI runs with `PICSOU_REQUIRE_DOCKER_TESTS=true` and its "Require the SSO login integration
  tests" step greps the Surefire report for `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0`.

Frontend (`bunx vitest run`): `features/sso/{start,errors}.test.ts`, `lib/sso-revalidate.test.ts`,
`lib/safe-redirect.test.ts`, `lib/api-client.test.ts`, `features/auth/{guards,hooks}.test.*`,
`pages/login/{LoginPage,SsoCallbackPage,SsoCompletePage}.test.tsx`,
`pages/settings/security/{SignInMethodsCard,ReauthPanel}.test.tsx`,
`pages/setup/SetupStepAdmin.test.tsx`, `pages/activation/ActivationPage.test.tsx`,
`pages/admin/sections/MembersSection.test.tsx`.

The real IdP behavior (Zitadel's Action output, check lifetimes on forced re-authentication) can
only be verified manually against the operator's instance.

## Links

- ADR: [SSO login alongside passwords](../decisions/2026-11-04-sso-login-alongside-passwords.md)
- Design brief: [2026-10-31-sso-login-design.md](../briefs/2026-10-31-sso-login-design.md)
- API: [backend/docs/API.md](../../backend/docs/API.md#1b-single-sign-on-oidc--oauth2sso)
- Related: [mfa-and-remember-me.md](./mfa-and-remember-me.md),
  [multi-account-family.md](./multi-account-family.md), [setup-wizard.md](./setup-wizard.md),
  [admin-recovery.md](./admin-recovery.md), [security-cors-cookies.md](./security-cors-cookies.md),
  [data-export.md](./data-export.md)
