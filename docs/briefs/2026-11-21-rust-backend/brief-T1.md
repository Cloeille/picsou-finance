# Brief T1: identity, administration and telemetry

GOAL: Port every remaining identity, administration and telemetry endpoint, the SSO sign-in chain and the OAuth2 authorization server (used by the iOS app and by MCP clients) into `backend-rs/src/identity/` and `backend-rs/src/admin/`.

WORKTREE: `<workspace>/picsou-rust-wt/T1`, branch `rust/t1`. Do not create other branches or worktrees. Build with `CARGO_TARGET_DIR=<workspace>/picsou-rust-wt/T1/backend-rs/target` (the default) so your build never shares a lock with another agent.

SCOPE: write only `identity/` and `admin/` under `backend-rs/src/`, your test files under `backend-rs/tests/`, and your contract scenarios under `backend-rs/contract/scenarios/` and `backend-rs/contract/golden/`. Shared modules only per standing order 13.

JAVA CONTROLLERS TO PORT (every endpoint, all listed in `backend-rs/scripts/java_endpoints.tsv`): SignInMethodsController, AccessKeyController, ConnectedAppsController, OAuthConsentController, MeProfileController, AdminMfaController, AdminIdentityController, AdminController, TelemetryController, plus config/DynamicClientRegistrationController and config/ProtectedResourceMetadataController, and the non-controller HTTP surface: the Spring Authorization Server endpoints configured in AuthorizationServerConfig (`/oauth2/authorize`, `/oauth2/token`, `/oauth2/revoke`, `/oauth2/introspect` if exposed, `/.well-known/oauth-authorization-server`, JWKS if any, consent page) and the SSO chain on `/oauth2/sso/**` (SsoSecurityConfig and friends, service/sso/).

CONTEXT (read first):
- `backend-rs/README.md` (how a track adds a domain, the route registry, the auth extractor, `TestApp`), `docs/decisions/2026-11-21-rust-backend.md`, `docs/briefs/2026-11-21-rust-backend/plan.md` and `dependency-map.md` beside it.
- `docs/INDEX.md` and every note in `docs/features/` about your domain; `docs/conventions/`.
- The Java controllers above, every service, adapter, DTO, entity and repository they reach, and their tests under `backend/src/test/java`.
- `frontend/src/types/api.ts` and the `frontend/src/features/*/api.ts` modules that call your endpoints.
- `backend-rs/contract/README.md`: the differential harness. The Java jar is at `<workspace>/picsou-rust/backend/target/picsou-backend-1.1.0.jar` (JAVA_HOME=$(/usr/libexec/java_home -v 21)).
- The authorization server persists to the tables created by the OAuth2 migrations (`oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent` or whatever the migrations name them). Rows written by Java must stay valid for Rust and the reverse where practical; at minimum, tokens Java issued before the switch may be rejected only if Java would reject them too. State any incompatibility in the report.
- SSO: `docs/features/sso-login.md`, `docs/briefs/2026-10-31-sso-login-design.md`, the Java FakeOidcProvider test fixture and SsoLoginIntegrationTest's 20 scenarios: port all 20 against a wiremock-based fake IdP.
- Plug OAuth bearer validation and SSO into the foundation's auth hook; do not edit the foundation's files beyond standing order 13.
- Also port `AdminRecoveryRunner` (startup admin recovery when `ADMIN_RECOVERY_ENABLED`) as a function the app calls at startup; the login branch for it already exists in the foundation.
- Admin settings (CORS origins, AI provider, Enable Banking config, update check, telemetry consent) live in `app_setting`; expose the typed accessors other tracks will need from `admin/`.

ACCEPTANCE:
- Every endpoint of the controllers above is registered and implemented with Java's behavior; the route-coverage test shows them covered.
- Tests port the meaningful Java test cases for your scope (edge cases first) and pass.
- You add contract scenarios exercising your endpoints the way the frontend does, record goldens from Java, and `check` passes against your release build with zero differences. List any difference you kept on purpose, with the reason.
- fmt, clippy -D warnings, tests green (standing order 10).
- The OAuth2 flows (authorization code with PKCE for the iOS public client, dynamic client registration and protected-resource metadata for MCP clients, refresh, revoke) are tested end to end in Rust tests.
TIMEBOX: about 6 hours. On expiry, commit what passes and report partial with the exact list of what remains.

FORBIDDEN: no push, no rebase, no merge of other branches, no edits to the Java code or migrations, no network access from tests.

REPORT: per standing order 15; long version to `<scratchpad>/reports/T1.md`.

STANDING ORDERS (verbatim):
# Standing orders (Rust backend program)

Every agent in this program obeys these lines. They override anything a brief leaves unsaid.

1. Work only in the worktree and branch your brief names. Never push, never fetch-and-merge, never touch a remote, never open a pull request, never rebase or force anything.
2. The Java backend under `backend/` and the notes under `docs/features/` are the specification. Before implementing an endpoint, read its controller, the services and DTOs it uses, and its Java tests. Never guess behavior. When Java and a doc disagree, Java wins; list the disagreement in your report.
3. JSON is Jackson's: camelCase names exactly as the Java DTO, null fields omitted (`#[serde_with::skip_serializing_none]` on every response type), unknown request fields ignored, `LocalDate` as `YYYY-MM-DD`, `Instant` as RFC 3339 UTC with `Z`. Check every response type against `frontend/src/types/api.ts` and the relevant `frontend/src/features/*/api.ts`.
4. Money, quantities, prices, rates and percentages are `rust_decimal::Decimal` end to end, from SQL `numeric` to JSON number. Never `f32`/`f64` for them, not even transiently. Reproduce Java's `BigDecimal` scale and `RoundingMode` exactly where Java sets them.
5. Errors go through `picsou::error::AppError`, which renders the same RFC 7807 bodies as `GlobalExceptionHandler` (status, title, detail, optional stable `code`, `errors` map on validation). Never expose SQL errors, stack traces, internal names or upstream bodies.
6. Every query is scoped to the authenticated member exactly as the Java repository call is. Family sharing and admin impersonation go only through the helpers in `picsou::auth`.
7. Logs never contain a password, token, cookie, TOTP secret, private key, full IBAN or account number, or a bank credential.
8. Tests assert behavior over HTTP through the in-process router (`picsou::testing::TestApp`), on a fresh database the harness creates. Time comes from the injected `Clock`; no test depends on the current date. External HTTP (banks, prices, sidecars, AI) is stubbed with `wiremock`; tests never reach the internet. Port the meaningful cases of the Java tests for your scope, edge cases first (rounding, member isolation, co-ownership, hidden and child accounts, errors).
9. Test database: PostgreSQL 16 at `127.0.0.1:55432`, user `picsou`, no password (trust). The harness creates and drops uniquely named databases. Never drop a database you did not create. Never stop that server.
10. Before reporting done: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings` and `cargo test` all pass in `backend-rs/`. Paste the summary lines in your report.
11. No dead code, no placeholder data, no silent stubs. An endpoint you cannot finish answers 501 with a ProblemDetail `code` of `NOT_IMPLEMENTED` and is listed in your report with the reason.
12. Comments only for a non-obvious why. All code, comments, docs and commit messages in English. Conventional commits (`feat(rust): ...`) on your branch; commit as you go.
13. Edit only the paths your brief puts in scope. If you need a change in a shared module, make the smallest additive change, and list it under "shared changes" in your report.
14. Use `cargo add` for dependencies so versions are current; prefer crates already in `backend-rs/Cargo.toml`.
15. Your final message is the report, at most 60 lines: status (done / partial / blocked), branch, head SHA, endpoints implemented (count and list of any 501), tests added and the verify summaries, shared changes, deviations from Java and why, open follow-ups. Write a longer report to the path your brief gives if you need more room.
