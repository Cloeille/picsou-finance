# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev   # Run locally (needs PostgreSQL on :5432)
mvn test                                              # Run all tests
mvn test -Dtest=GoalServiceTest                       # Run a single test class
mvn package -DskipTests                               # Build JAR
```

Most tests are pure Mockito unit tests — no database needed. Several tests spin up a real
PostgreSQL 16 via Testcontainers (Docker Engine ≥ 25.0) where H2 can't reproduce the behavior:
`BudgetSeedWriteOnReadPostgresTest` (budget seed-on-read in a read-only transaction),
`OAuth2SchemaMigrationTest` / `WalletEvmMigrationTest` (Flyway migration fidelity), and the
OAuth2/MCP authorization-server suite (`AuthorizationServerConfigTest` and friends). They all
self-skip when no Docker daemon is present, so the rest of the suite still runs.

That skip is invisible in a green build, so CI sets `PICSOU_REQUIRE_DOCKER_TESTS=true`,
which turns "no Docker" into a hard failure instead — a red build there means the daemon
was unreachable, not that a migration broke. Watch the **Skipped** count locally: one is
normal, a handful means the migration tests silently sat out.

## Package structure

```
com.picsou/
├── model/        JPA entities
├── repository/   Spring Data JPA interfaces
├── service/      Business logic
├── controller/   REST controllers (all routes under /api/)
├── dto/          Request/response records
├── port/         Abstractions for external providers
├── adapter/      Port implementations (Enable Banking, CoinGecko, Yahoo Finance)
├── config/       Spring beans: security, JWT, rate limiting, properties
├── exception/    GlobalExceptionHandler + custom exceptions
├── imports/      CSV transaction import (dialect detection, row mapping, parsing)
├── finary/       Finary API sync + persistence helpers
├── export/       Transaction export
├── mcp/          Embedded MCP server (tools, scopes, OAuth)
└── validation/   Custom bean-validation constraints
```

## Key patterns

**Ports & adapters:** External integrations hide behind `BankConnectorPort` and `PriceProviderPort`. To swap a provider, implement the port and swap the `@Primary` bean — controllers/services never import adapters directly.

**Auth flow:** `JwtAuthenticationFilter` reads the `access_token` HttpOnly cookie, validates the `tv` (token-version) claim against `AppUser.tokenVersion`, and sets the `SecurityContext`. `AuthController` issues and rotates access/refresh tokens; `MfaController` issues `mfa_challenge` JWTs and verifies TOTP; `PersistentTokenAuthFilter` re-issues access tokens from rotating "Remember Me" tokens. CSRF is disabled — `SameSite=Lax` cookies + JSON-only API surface provide equivalent protection (`Lax` rather than `Strict` for Safari iOS compatibility).

**Member-scoped authorization:** every controller resolves `UserContext.currentMemberId()` (or `currentMemberIdOverride()` for admin impersonation), and every service/repository scopes queries by `member_id`. Family-shared access goes through `SharingSettings` + `SharedResource`. Never query a repo without a member filter — the sole exception is a lookup whose key is itself an unguessable single-use credential (e.g. `RequisitionRepository.findByOauthState`, see the OAuth-state ADR), where the member is derived from the resolved row.

**Rate limiting:** `RateLimitConfig` exposes named `Map<String, Bucket>` beans (Bucket4j); controllers inject the relevant bucket map via `@Qualifier` and enforce manually with `tryConsume(1)` before doing rate-limited work (there is no annotation-based limiter — every controller uses this same explicit check). Login: 5 attempts/15 min. Sync endpoints are also throttled.

**Scheduled tasks** (`SchedulerService`): daily balance snapshots and price cache refresh. `PriceService` holds a 15-minute in-memory cache to avoid hammering external APIs.

**Flyway owns the schema** — never use `ddl-auto: create/update`. Migration details and entity conventions: see [`docs/conventions/database.md`](../docs/conventions/database.md).

## Configuration

`application.yml` (prod), `application-dev.yml` (dev, enables SQL logging). All secrets from env vars — see `.env.example` at project root.

## Testing

Mockito unit tests (`@ExtendWith(MockitoExtension.class)`) for business logic. For the one case
where database fidelity matters — the budget seed-on-read in a read-only transaction — a
Testcontainers-backed `@SpringBootTest` runs against real PostgreSQL, because H2 silently allows
INSERTs in a read-only transaction whereas PostgreSQL rejects them (SQLSTATE 25006). Full
conventions: see [`docs/conventions/testing.md`](../docs/conventions/testing.md).
