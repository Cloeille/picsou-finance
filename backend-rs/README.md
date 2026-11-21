# backend-rs

The Rust backend: one crate (`picsou`, library and binary) serving the Java backend's HTTP contract on the same PostgreSQL schema and Flyway history. Decision: [docs/decisions/2026-11-21-rust-backend.md](../docs/decisions/2026-11-21-rust-backend.md).

## Build and run

```sh
cargo build --release
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/picsou JWT_SECRET=... CRYPTO_ENCRYPTION_KEY=... \
  SPA_LOCATION=../frontend/dist ./target/release/picsou
```

It reads the environment variables of `backend/src/main/resources/application.yml`, with the same defaults (`SPRING_PROFILES_ACTIVE=dev` gives the dev profile's). `PICSOU_SECRETS_DIR` generates and keeps `jwt_secret` and `crypto_key` as the Java `SecretBootstrap` does. At start it applies the pending migrations of `backend/src/main/resources/db/migration` (embedded at build time), seeds `APP_USERNAME`/`APP_PASSWORD_HASH` when set, then serves until SIGINT or SIGTERM. `LOGGING_FILE_NAME` or `LOGGING_FILE_PATH` adds a rolling log file (10 MB, seven kept); `RUST_LOG` sets levels.

## Test

```sh
cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test
PICSOU_REQUIRE_FULL_COVERAGE=1 cargo test routes   # fails until every Java endpoint is served
JAR=../backend/target/picsou-backend-*.jar PGBIN=/path/to/pg16/bin tools/flyway-parity.sh
```

Tests need PostgreSQL 16 at `127.0.0.1:55432`, user `picsou`, trust auth. The harness migrates a template database once per migration set (`picsou_tpl_<hash>`) and clones a fresh database per `TestApp`, dropped at the end of the test.

## Layout

| Path | What |
|---|---|
| `src/config.rs` | `Config` from the environment |
| `src/db/` | pool, Flyway-compatible migrator |
| `src/error.rs` | `AppError`, RFC 7807 bodies |
| `src/http/` | forwarded headers and client IP, CORS, CSRF, body limit, setup gate, security headers, rate limits, JSON and path extractors, SPA |
| `src/auth/` | JWT, cookies, remember-me sessions, access keys, the `Auth` extractor |
| `src/crypto/` | `CryptoEncryption` (AES-GCM, Java-compatible), secrets bootstrap |
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
