# backend-rs

The backend: one crate (`picsou`, library and binary). It serves the HTTP contract of the Java backend it replaced in 1.3.0, on the same PostgreSQL schema and Flyway history, or on embedded SQLite. Decision: [docs/decisions/2026-11-21-rust-backend.md](../docs/decisions/2026-11-21-rust-backend.md).

## Build and run

```sh
cargo build --release
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/picsou JWT_SECRET=... CRYPTO_ENCRYPTION_KEY=... \
  SPA_LOCATION=../frontend/dist ./target/release/picsou
```

It reads the environment variables the Java backend read, with the same defaults (`src/config.rs`; `SPRING_PROFILES_ACTIVE=dev` gives the dev profile's). `PICSOU_SECRETS_DIR` generates and keeps `jwt_secret` and `crypto_key`. At start it applies the pending migrations of `backend-rs/migrations` (embedded at build time), seeds `APP_USERNAME`/`APP_PASSWORD_HASH` when set, then serves until SIGINT or SIGTERM. `LOGGING_FILE_NAME` or `LOGGING_FILE_PATH` adds a rolling log file (10 MB, seven kept); `RUST_LOG` sets levels.

## Test

```sh
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
PICSOU_TEST_DB=sqlite cargo test --test integration   # the same integration tests on embedded SQLite
PICSOU_REQUIRE_FULL_COVERAGE=1 cargo test routes      # fails when an endpoint of scripts/java_endpoints.tsv is not served
bun run contract/run.ts check --all                   # the HTTP contract against its goldens (contract/README.md)
```

Tests need PostgreSQL 16 at `127.0.0.1:55432`, user `picsou`, trust auth. The harness migrates a template database once per migration set (`picsou_tpl_<hash>`) and clones a fresh database per `TestApp`, dropped at the end of the test. `scripts/java_endpoints.tsv` is the endpoint list of the last Java release: the route tests hold the router to it.

## Layout

| Path | What |
|---|---|
| `src/config.rs` | `Config` from the environment |
| `migrations/` | the PostgreSQL migrations, embedded at build time |
| `src/db/` | pool, Flyway-compatible migrator, the SQLite engine (`sqlite/`, with its own migrations) |
| `src/sidecars.tsv` | the connector sidecars: service, URL variable, default and dev URL |
| `src/error.rs` | `AppError`, RFC 7807 bodies |
| `src/http/` | forwarded headers and client IP, CORS, CSRF, body limit, setup gate, security headers, rate limits, JSON and path extractors, SPA |
| `src/auth/` | JWT, cookies, remember-me sessions, access keys, the `Auth` extractor |
| `src/crypto/` | `CryptoEncryption` (AES-GCM, reads what the Java backend encrypted), secrets bootstrap |
| `src/setup_auth/` | setup wizard, login, MFA, sessions |
| `src/routes.rs` | the route registry |
| `src/testing/` | `TestApp` (feature `testing`, on for tests) |
| `src/<domain>/` | one module per track (`identity`, `ledger`, `accounts`, ...) |
| `tests/integration/` | the one integration-test binary, one file per module |

## Adding a domain

Work only in your module directory and its test file.

1. Register routes in `src/<module>/mod.rs`: `routes.get("/api/x/{id}", handler)`, with Spring's path syntax. Anonymous routes also call `routes.permit_all(Some(Method::GET), "/api/x/**")`; everything else requires a signed-in user and `/api/admin/**` the admin role.
2. Handlers take `State<AppState>`, `Auth` (user, effective `member_id` with the admin override, auth method and scopes), `picsou::http::json::Json<T>` for bodies (implement `Validate` for bean validation) and `picsou::http::json::Path<T>`, and return `AppResult<...>`. Errors are `AppError` variants; never build a problem body by hand.
3. Response types are `#[serde_with::skip_serializing_none]` + `#[serde(rename_all = "camelCase")]`; money is `rust_decimal::Decimal` (`f32`/`f64` fail a test).
4. Time comes from `state.clock` (`now()`, `today()` in `APP_TIMEZONE`); rate limits from `state.limits` with a policy of `http::rate_limit::policies`; outbound HTTP from `state.http` with base URLs from `state.config`.
5. Tests in `tests/integration/<module>.rs`: `TestApp::with_admin().await`, `app.login_admin()`, `app.create_member(..)`, `app.create_access_key(..)`, `app.clock.advance(..)`, and `TestApp::builder().env("X_URL", wiremock.uri())` for external services.

Track T1 plugs OAuth2 bearer validation in `identity::bearer_authenticators()` and the SSO logout endpoint in `identity::sso_end_session_endpoint`.
