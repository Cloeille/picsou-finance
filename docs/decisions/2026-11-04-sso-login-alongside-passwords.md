# ADR: SSO login through an external OIDC provider, alongside passwords

> Date: 2026-11-04
> Status: ✅ Active

## Context

Picsou signs people in with a local username and password, optional TOTP and a rotating
Remember-Me cookie ([ADR 2026-04-26](./2026-04-26-totp-2fa-and-persistent-sessions.md)). Homelab
operators who already run an identity provider (Zitadel, Authentik, Keycloak, Authelia, Pocket
ID...) asked to sign in with it, and to set up a new instance without ever creating a Picsou
password. Operators without an IdP must see no difference.

Two earlier ADRs set external IdPs aside:

- [2026-01-01](./2026-01-01-single-user-jwt-cookies.md) (superseded) listed OAuth2 / OpenID
  Connect as "requires an external IdP; adds complexity for a single-user app; another service to
  maintain".
- [2026-07-03](./2026-07-03-oauth2-authorization-server-for-native-app.md) rejected an external
  IdP for the iOS app: "wrong identity model ... adds a hard external dependency to a self-hosted
  app".

The design is in the [brief](../briefs/2026-10-31-sso-login-design.md); the implementation is
described in [sso-login.md](../features/sso-login.md).

## Decision

Picsou accepts **one optional OIDC provider**, configured only through `SSO_*` environment
variables, as an additional way in. The locked decisions:

1. **Alongside passwords.** SSO is additive. Without `SSO_ISSUER_URI` and `SSO_CLIENT_ID` there is
   no SSO bean, chain or UI, and the login form behaves as before. There is no instance-wide
   "SSO only" switch.
2. **The IdP only authenticates.** After the callback Picsou issues its own HttpOnly cookies
   through the same `SessionIssuer` as a password login. IdP access, refresh and ID tokens are
   discarded, never stored or forwarded.
3. **OIDC Authorization Code with PKCE S256 always**, plus `state` and `nonce`. Discovery is lazy,
   so Picsou boots while the IdP is down.
4. **Identities are bound by `(iss, sub)` only**, never by email or username.
5. **Password-less accounts exist from the start**: the SSO first admin, admin-group provisioning,
   invitations activated with SSO, and "turn off password login".
6. **First admin from the admin group.** With `SSO_ADMIN_GROUP` set, the setup wizard only offers
   SSO and `POST /api/setup/admin` answers 409 `SSO_ADMIN_REQUIRED`.
7. **The admin group keeps managing admins.** A group member without an account gets an admin
   account at the first sign-in, and every SSO sign-in promotes or demotes. A missing claim
   counts as "no groups". The last admin is never demoted.
8. **Picsou's TOTP is skipped on SSO sign-ins**; MFA is the IdP's job. Local TOTP only protects
   the password.
9. **Password login can be turned off per account**, once an identity of the current issuer has
   completed an SSO sign-in.
10. **Re-authentication through the IdP** (`prompt=login&max_age=0`, then an `auth_time` no older
    than the start of the round trip minus 60 seconds) for credential changes of accounts
    without a password. Its result is the 5-minute `reauth_proof` cookie, which the password
    path (`POST /api/auth/reauth`) also mints.
11. **SSO sessions are re-validated through the IdP** after `SSO_SESSION_REVALIDATE_AFTER`
    (default 24 h), which re-reads the groups.
12. **Groups claim**: a literal claim name (default `groups`) holding an array of strings, a
    single string, or an object whose keys are the groups. Matching is exact.
13. **Logging out at the IdP** (RP-initiated logout) is optional and off by default.
14. **Both ends of the flow are same-origin POSTs.** The SPA starts the flow with a form POST, and
    the IdP returns to a SPA page that re-posts the result. The SSO chain refuses GET, and refuses
    cross-site POSTs even without an auth cookie.

## Alternatives considered

### Keep refusing external IdPs

- **Pros**: no new dependency, no new security surface.
- **Cons**: operators with an IdP keep one more password per person, and cannot manage who is an
  admin from one place.

### Make the IdP the token issuer (resource-server mode)

- **Pros**: no Picsou session to keep in sync with the IdP.
- **Cons**: every request would depend on the IdP, `tv` revocation and the Remember-Me model
  would no longer apply, and the iOS app and MCP clients would need a second token format.

### Match accounts by email or username

- **Pros**: existing users would be linked automatically at their first SSO sign-in.
- **Cons**: both values are controlled by the IdP; a matching email would be an account takeover.

### Several providers, or settings stored in the database

- **Pros**: more flexible, editable from the Admin page.
- **Cons**: before the first admin exists there is nowhere to edit them, and one provider covers
  the homelab case.

## Reasoning

The earlier objections were to an IdP that is **required** and that **replaces** Picsou's
identity model. This decision keeps both properties those ADRs protected:

- **No hard dependency.** SSO is off unless configured. When the IdP is down, discovery fails
  softly (`IDP_UNAVAILABLE`), the password form stays available, and admin recovery and reset
  links always end by setting a password.
- **Same identity model.** The IdP authenticates and supplies claims; Picsou still owns `AppUser`,
  `FamilyMember`, roles, member scoping, the HS256 cookies, `tv` revocation and Remember Me.

The 2026-07-03 reasoning for the iOS app still holds: the app talks to Picsou's own Authorization
Server, never to the IdP. It gains SSO without any change because the Authorization Server sends
the browser to the SPA's `/login?redirect=/oauth2/authorize?...`, where the SSO button now sits.

### How app sessions stay bounded

The iOS app and the remote-MCP connector are public clients. Spring Authorization Server issues
them **no refresh token** (pinned by `SsoLoginIntegrationTest`, scenario 15), so each access
token it issues expires after `OAUTH_ACCESS_TTL_MINUTES` (default 15) and a new one needs a
browser authorization. The SSO window governs that authorization in two places:

- the cookie bridge on `GET /oauth2/authorize` leaves a web session whose `sso_at` is past the
  window (or any SSO session while SSO is off) anonymous, so the authorization redirects to login;
- the SPA restarts the IdP round trip silently on `SSO_REVALIDATE`, which also re-syncs the admin
  group.

A member removed at the IdP therefore cannot start a new app authorization once their SSO window
has passed. Tokens already issued end at most one web access-token lifetime (15 minutes: a consent
page opened inside the window can still be approved while that web access token lives, and it
cannot be renewed after the window), one authorization-code lifetime (Spring Authorization
Server's default, 5 minutes) and one app access-token lifetime after the window ends: about **35
minutes** with the default settings.

A member removed only from the admin group keeps member access and loses `ADMIN` at the next SSO
sign-in, which any further use forces within the window, unless the last-admin guard keeps the
role because they are the only administrator. The role is read from the database on every request;
MCP tokens carry scopes, not a role.

While SSO is enabled, Authorization Server tokens can no longer be converted into web cookies:
`POST /api/auth/refresh` and `PATCH /api/auth/username` do not mint cookies from them.

Enabling refresh tokens for these clients later requires a re-validation window for app
authorizations first; otherwise a removed member's app would keep access indefinitely.

## Trade-offs accepted

- **Password sign-ins never sync roles.** A linked admin who keeps a password keeps their Picsou
  role when signing in with it. Admins whose rights must follow the group turn password login
  off.
- **Removing a person at the IdP does not remove their password.** An account that still has one
  can sign in with it; deactivate or delete it in Picsou as well.
- **A demotion is not instant.** It lands at the next SSO sign-in, at most one re-validation
  window later.
- **Changing `SSO_ISSUER_URI` orphans every link**, because `iss` changes. Admin-group members
  would then be provisioned as new, empty accounts. An issuer-migration helper is a follow-up.
- **The handshake uses a short-lived servlet session** (`JSESSIONID`) between start and callback,
  in an otherwise stateless backend. It is invalidated at the callback, success or failure.

## Consequences

- New schema (`V122__sso_identities.sql`): table `user_identity` with a unique `(issuer, subject)`,
  and `persistent_session.sso_authenticated_at`.
- A third `SecurityFilterChain` at `@Order(0)` matching `/oauth2/sso/**`, disjoint from the
  Authorization Server's endpoints.
- New endpoints under `/api/auth` (`sso`, `sign-in-methods`, `reauth`, `password`, `identities`)
  and `DELETE /api/admin/members/{memberId}/identities`; see
  [backend/docs/API.md](../../backend/docs/API.md).
- The GDPR export gains a `linked_identities` table, and accepts the `reauth_proof` cookie in
  place of the inline password or TOTP.
- Refresh tokens (and the access tokens minted with them) of SSO sessions carry an `sso_at` claim;
  refresh answers 401 `SSO_REVALIDATE` past the window.
- The operator registers the redirect URI `https://<host>/login/sso/callback` at the IdP, and the
  post-logout URI `https://<host>/login` when `SSO_IDP_LOGOUT=true`.

## Supersedes

Nothing is superseded. This ADR amends the external-IdP reasoning of
[2026-01-01](./2026-01-01-single-user-jwt-cookies.md) (Alternatives, "OAuth2 / OpenID Connect")
and [2026-07-03](./2026-07-03-oauth2-authorization-server-for-native-app.md) (Alternatives,
"External IdP"): an IdP the operator already runs, optional and additive, fits Picsou's identity
model; a required or replacing IdP still does not.
