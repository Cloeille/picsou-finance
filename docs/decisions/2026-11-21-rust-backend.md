# ADR: Rewrite the backend in Rust

> Date: 2026-11-21
> Status: 🟡 Proposed

## Context

The Spring Boot backend (837 files, ~98k lines, 336 HTTP endpoints) takes about 6.5 s to start cold and ships a ~150 MB JRE plus a ~132 MB jar. The desktop app (`desktop/src-tauri`, see [desktop-app-tauri](2026-11-15-desktop-app-tauri.md)) spawns that JRE next to a bundled PostgreSQL and shows a waiting page until `/actuator/health/readiness` answers. One native backend should serve both the Docker image and the desktop app, start in well under a second, and stay fully compatible with the existing React frontend and with existing databases.

## Decision

1. **A single Rust crate, `backend-rs/`, beside `backend/` until the switchover.** Library plus binary: `picsou::serve(config, listener)` is what both the server binary and the desktop app call. One module per functional domain, one integration-test binary.
2. **PostgreSQL everywhere, same schema, same history table.** The Rust backend applies the existing Flyway migrations from `backend/src/main/resources/db/migration`, embedded at build time, and records them in `flyway_schema_history` with Flyway's own CRC32 checksum. A database can go from the Java backend to the Rust one and back. The desktop app takes a `pg_dump` before it applies a pending migration.
3. **Stack.** axum 0.8 and tower-http, tokio, sqlx 0.8 with runtime-checked queries (no database at compile time), `rust_decimal` for every amount, quantity, rate and price (no `f32`/`f64`, enforced by a test), chrono, serde with Jackson's `non_null` inclusion reproduced by `serde_with::skip_serializing_none`, jsonwebtoken (HS256, same claims), bcrypt, aes-gcm (byte-compatible with `CryptoEncryption`), totp-rs, reqwest with rustls, rust_xlsxwriter, tracing with a size-bounded rolling file.
4. **The HTTP contract is the Java one.** Same paths, methods, statuses, JSON names, cookies and RFC 7807 bodies. `backend-rs/scripts/java_endpoints.py` lists the 336 Java endpoints and a Rust test checks the router against that list. A differential harness (`backend-rs/contract/`) replays scripted scenarios against the Java jar and the Rust binary and diffs normalized responses.
5. **Bank sidecars keep their HTTP protocol.** The 17 Python sidecars are untouched; one generic Rust client replaces `adapter/sidecar/`.
6. **MCP server and AI categorization are in scope**, built last.
7. **Desktop links the backend as a library.** The Tauri process runs it on its own tokio runtime: no JRE, no backend child process. PostgreSQL stays a child process.

## Alternatives considered

### SQLite on the desktop, PostgreSQL in Docker

- **Pros**: no database process on the desktop; a cold start bounded by the backend alone.
- **Cons**: 151 PostgreSQL-dialect migrations and every query would need a second dialect; existing desktop clusters would need a data migration; two code paths to test for every financial rule.

### SQLite everywhere

- **Pros**: one embedded engine.
- **Cons**: breaks every existing Docker install's data and the Helm chart's external-database option.

### Rewrite the sidecars in Rust

- **Pros**: fewer runtimes in the Compose file.
- **Cons**: the browser-based sidecars need Playwright or Camoufox anyway; the six HTTP-only ones are stable and isolated. Out of scope for this change.

## Reasoning

Data compatibility decides the database question: the switch must be invisible to a user who upgrades, and reversible. Keeping PostgreSQL and the Flyway history makes both true at no cost to the frontend. The cold start that matters on the desktop is the window opening, which no longer waits on a JVM.

## Trade-offs accepted

- The desktop app still starts PostgreSQL (about one second, measured in the program report).
- Queries are checked at test time, not compile time.
- During the transition the migrations stay under `backend/`; they move with the Java removal.

## Consequences

- `backend-rs/` holds the new backend, its tests and the contract harness.
- The Docker image and the desktop bundle drop the JRE.
- The Java backend stays as the reference until every endpoint passes the contract harness, then it is deleted in its own change.

## Update (2026-11-23): the Java backend is removed

- `backend/` is deleted. The migrations moved, unchanged, to `backend-rs/migrations/`, and the
  sidecar URLs to `backend-rs/src/sidecars.tsv`.
- The contract harness no longer starts a Java backend: it checks the Rust backend against the
  goldens recorded from Java, which stay as the HTTP contract.
- The desktop app no longer starts PostgreSQL: it runs on embedded SQLite
  ([dual-engine ADR](./2026-11-22-sqlite-dual-engine.md)).
- The experimental Powens adapter was not ported.
