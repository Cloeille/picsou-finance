# Brief D: desktop app and Docker image on the Rust backend

GOAL: Make the Tauri desktop app and the Docker image run the Rust backend (`backend-rs`) instead of the JRE and the jar, with the desktop window opening at once, and wire the Rust backend into the Makefile and CI.

WORKTREE: `<workspace>/picsou-rust-wt/D`, branch `rust/d`. A merge of PR #606 (the macOS desktop app, branch `pr-606-latest`, head 30f3a724, by another contributor, still open against release/1.3.0) into your branch is IN PROGRESS with conflicts. Step 0: resolve it. For `desktop/**` take PR #606's side as the base (it supersedes release/1.3.0's Linux-only version) unless release/1.3.0 has a later fix the PR lacks; for every other file keep both sides' intent (i18n files: keep all keys from both; ci.yml and the workflow tests: keep both sides' jobs and assertions). Commit the merge before anything else, then run the workflow tests: `uv run --no-project --python 3.12 --with pyyaml python -m unittest discover -s .github/tests` (`test_dependabot` may fail locally because it scans `.claude/worktrees/`; that one is not a real failure).

SCOPE: `desktop/**`, `docker/**`, `Makefile`, `.github/**`, `charts/**` (only JVM-specific settings), `CONTRIBUTING.md` and `docs/features/desktop-app.md`, `docs/features/docker-deployment.md` (behavior changes only), and in `backend-rs/` only additive changes the embedding needs (for example a public function that lists pending migrations, or a `serve` variant taking a ready listener and a shutdown signal), per standing order 13. Do not edit domain modules.

CONTEXT: `docs/decisions/2026-11-21-rust-backend.md` (decision 7: the desktop links the backend as a library), `docs/decisions/2026-11-15-desktop-app-tauri.md`, `docs/features/desktop-app.md`, `backend-rs/README.md`, `backend-rs/src/lib.rs` and `config`, PR #606's `desktop/src-tauri/src/{main,supervisor,sidecars,update,upgrade}.rs`, `desktop/scripts/fetch_runtime.py`, `desktop/ui/index.html`, `docker/Dockerfile`, `docker/docker-compose.yml`, `charts/picsou`, `.github/workflows/{ci,docker,desktop-release}.yml`, `.github/scripts/sidecars.py`, `DESIGN.md` (for the loading surface). The Rust backend reads the same environment variables as the Java one, and `/actuator/info` emits `app.desktop` and `app.desktop-unavailable` when `PICSOU_DESKTOP` is set (check what #606's application.yml expects and make the Rust side match it exactly).

DELIVERABLES:
1. Desktop: `desktop/src-tauri` depends on `backend-rs` as a path dependency and runs `picsou::serve` in-process on its own tokio runtime after starting PostgreSQL; no JRE, no `app.jar`, no backend child process, no Java download in `fetch_runtime.py`. Readiness is known in-process, not by polling. Before the backend applies pending migrations, take a `pg_dump` backup into the data folder (keep the last few, bounded). Clean shutdown: stop the server, then PostgreSQL with a fast shutdown. Keep #606's sidecars, updates and upgrade logic working.
2. Window: it opens immediately; the loading page (`desktop/ui/index.html`) is a calm surface per DESIGN.md (no black screen) shown only for the PostgreSQL start, then the app loads. macOS: reopening from the Dock (`RunEvent::Reopen`) shows the window again. Keep Windows and Linux behavior sound.
3. Docker: `docker/Dockerfile` builds the frontend and the Rust binary in build stages and ships a minimal runtime image with the binary and the SPA, same user, ports, volumes, environment, healthcheck and `SPA_LOCATION`; compose and Helm drop JVM options. No Docker daemon is available on this Mac: validate the Dockerfile with `hadolint` if you can install it, run the build stages' commands natively, and list what stayed unverified.
4. Makefile: `dev-backend` and `test-backend` run the Rust backend (`cargo run` with the dev configuration, `cargo test`), the Java targets stay available under `-java` names until the Java code is deleted; `desktop` builds without Maven.
5. CI: a Rust job (fmt, clippy -D warnings, tests against a PostgreSQL 16 service, the route-coverage test) included in the required aggregate; the Docker workflow builds the new image; the desktop release workflow builds without a JRE. Keep the Java jobs for now.

ACCEPTANCE:
- Merge resolved and committed; workflow tests pass.
- `cargo build --release` of `desktop/src-tauri` succeeds on this Mac and `cargo clippy -- -D warnings` is clean there.
- Run the built desktop binary (or `cargo tauri build` bundle) on this Mac with a throwaway data folder under `<scratchpad>/desktop-run`: report the measured time from launch to the window showing the app (log timestamps), idle RSS of the app process, PostgreSQL start time, and the bundle size, versus the Java bundle measured at 842 MB (runtime: jre 150 MB, app.jar 132 MB, postgres 296 MB, python 91 MB, sidecars 158 MB, spa 4.3 MB) at `~/.gemini/antigravity/scratch/picsou-desktop-mac/desktop/src-tauri/target/release/bundle/macos/Picsou.app` (read only, never modify it).
- `backend-rs` still passes fmt, clippy and tests.

TIMEBOX: about 5 hours. FORBIDDEN: no push, no rebase, no edits to `backend/` Java code, no edits inside `~/.gemini/`, no installing system-wide software without it being a user-level tool (uv, bun, cargo install are fine).

REPORT: per standing order 15; long version to `<scratchpad>/reports/D.md`.

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
