# Brief H: differential contract harness

GOAL: Build `backend-rs/contract/`, a tool that runs scripted HTTP scenarios against the Java backend and the Rust backend, each on a fresh database, and reports every difference in status, headers that matter, cookies and normalized JSON body, so every track can prove its endpoints match Java.

WORKTREE: `<workspace>/picsou-rust-wt/H`, branch `rust/h`. SCOPE: write only `backend-rs/contract/**`. Read anything.

CONTEXT: `docs/decisions/2026-11-21-rust-backend.md`, `docs/briefs/2026-11-21-rust-backend/plan.md`, the Java controllers and DTOs, `frontend/src/types/api.ts` and `frontend/src/features/*/api.ts` (the real request shapes), `backend/src/main/resources/application.yml` (environment), `docs/features/`. Java jar: `<workspace>/picsou-rust/backend/target/picsou-backend-1.1.0.jar` (JAVA_HOME=$(/usr/libexec/java_home -v 21)). PostgreSQL 16 at 127.0.0.1:55432, user picsou, trust auth; binaries in `<scratchpad>/pg/pgserver/pginstall/bin`. The Rust backend does not exist yet: another agent is building it in parallel; it will be a binary `backend-rs/target/release/picsou` reading the same environment variables as the Java app.

DELIVERABLES:
1. Written in TypeScript for bun, no dependencies beyond bun's standard library.
2. A launcher that starts either backend on a free port with a fresh, uniquely named database, the environment a test instance needs (secrets generated per run, `SECURE_COOKIES=false`, startup sync off, update check off, instrument logos off, valuation off, every external base URL that application.yml makes configurable pointed at a local stub server the harness runs), waits for `/actuator/health/readiness`, and stops it and drops its database at the end.
3. A scenario format: a TypeScript module per domain under `contract/scenarios/`, each an ordered list of steps (method, path, body, multipart, headers, which client/cookie jar, captures from earlier responses such as ids, expected status). Several clients per scenario (admin, member, anonymous).
4. Normalization before comparison: generated ids mapped to stable placeholders by first appearance, timestamps and tokens replaced by typed placeholders, dates relative to today written as offsets, cookie values hidden but cookie names and attributes kept, JSON key order ignored, array order kept. Numbers compared exactly as decimal strings (`1.50` differs from `1.5`; report such scale differences distinctly).
5. Modes: `record <scenario>` runs it on Java and stores the normalized result as golden under `contract/golden/`; `check <scenario>` runs it on Rust and diffs against golden; `diff <scenario>` runs both live. A readable diff report, exit code non-zero on any difference. `--all` runs every scenario.
6. Initial scenarios covering the frontend's main flows on Java, recorded as goldens: first-run setup, login, wrong password, MFA enrolment and login with TOTP (the harness computes TOTP codes), refresh, logout, sessions list and revoke, member creation by the admin, manual accounts (create, update, hide, collapse, reorder, sub-account, delete), manual transactions, categories, a budget, a goal, family sharing basics, dashboard and history reads, CSV import, XLSX export (compare status and content type), error cases (404, 400, 401, 422 validation). Read the frontend to send what it sends. Avoid flows that need a real bank or the internet; list them in the README with the reason.
7. `contract/README.md`: how to run, how to add a scenario, the normalization rules.

ACCEPTANCE: `bun run contract/run.ts record --all` succeeds against the Java jar and `diff` of Java against itself (two fresh instances) reports zero differences, which proves the normalization is stable. Paste both outputs' summaries.

TIMEBOX: about 3 hours. FORBIDDEN: no edits outside `backend-rs/contract/`, no push, no rebase.

REPORT: per standing order 15; long version to `<scratchpad>/reports/H.md`.

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
