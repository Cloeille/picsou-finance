# Convention: Testing

## Backend: structure

```
backend-rs/
├── src/**/*.rs            unit tests, in a `#[cfg(test)] mod tests` at the end of the file they test
├── src/testing/           TestApp, the in-process harness the integration tests use
├── tests/integration/     one test binary; one file or folder per module (accounts.rs, sync/, connectors/, …)
└── contract/              the HTTP contract harness and its goldens (see its README)
```

## Backend: unit tests

For logic with no database and no HTTP: parsers, calculations, mappings, formatting.

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_numerically() {
        assert!(Version::parse("10").unwrap() > Version::parse("9").unwrap());
    }
}
```

- **A name is a sentence** that states the behaviour:
  `a_stored_card_never_takes_a_positive_balance`, not `test_card_3`.
- **One behaviour per test.** Several assertions on the same result are fine; several scenarios
  are several tests, or one loop that names the failing case in the assertion message.
- **A parser of outside content gets a robustness test**: the synthetic page, mangled
  (truncated, a character removed, multi-byte and markup characters inserted), must never panic
  and never hang (`assert_never_panics` in `tests/integration/native_sidecars_a.rs`).

## Backend: integration tests

The default for anything a client can observe. `TestApp` starts the whole backend in-process on
a fresh database, with a fixed clock and cookie-carrying clients:

```rust
#[tokio::test]
async fn a_member_only_sees_their_own_accounts() {
    let app = TestApp::with_admin().await;
    let admin = app.login_admin().await;
    // … create data through the API, then assert on responses or on rows
    let response = admin.get("/api/accounts").send().await;
    assert_eq!(response.status, StatusCode::OK);
}
```

- **Go through the API**, as a client does. Read a row directly only to prove what was stored.
- **Time comes from `app.clock`** (`app.clock.advance(..)`). No test may depend on today's date.
- **An outside service is a `wiremock` server**, passed through the environment:
  `TestApp::builder().env("X_URL", server.uri())`. No test reaches the internet or a real bank.
- **Fixtures are synthetic.** No real account data, session, token, screenshot or log.
- **Both engines.** The suite runs on PostgreSQL 16 (`127.0.0.1:55432`, user `picsou`, trust
  authentication: the harness migrates one template database per run and clones it per test),
  then again on embedded SQLite with `PICSOU_TEST_DB=sqlite`. `make test-backend` and CI run
  both.
- **CI runs the suite in shards.** One leg runs `make lint-backend unit-backend`; the others
  each run `make integration-backend SHARD=k/n`, three on PostgreSQL and two on SQLite
  (`DB=sqlite`). A shard takes every nth test of the binary's sorted list, so a new test needs
  no entry anywhere, and `ci-ok` checks the shards' outputs together ran every test exactly once
  on each engine. A test must therefore not depend on another test having run in its process.

### Tests that guard a rule

Some tests exist to fail a build that breaks a project rule. Keep them, and add one when a
rule can be checked:

| Test | Rule |
|------|------|
| `every_migration_has_a_unique_version` (`src/db/flyway.rs`) | two branches cannot both add the same migration version |
| `a_changed_checksum_fails`, `the_expected_schema_is_the_one_the_migrations_build` (`tests/integration/foundation/migrations.rs`) | a shipped migration is never edited; `schema.tsv` matches the migrations |
| `tests/integration/foundation/no_floats.rs` | no `f32` or `f64` for money |
| the route tests (`tests/integration/foundation/routes.rs`) | every endpoint of `scripts/java_endpoints.tsv` is served |

### The HTTP contract harness

`backend-rs/contract/` replays scripted scenarios against the built binary and diffs every
response with its golden: status, headers, cookies, normalized JSON. Run it after a change to a
response shape (`bun run backend-rs/contract/run.ts check --all`). A golden only changes when
the contract is meant to change.

## Frontend tests

- **Unit tests:** Vitest (`vitest`) with `@testing-library/react` and `jsdom`.
- **E2E tests:** Playwright (`@playwright/test`) against demo mode. CI runs them
  in the `e2e` job (`make test-e2e`). The job probes a headless Chromium launch and
  runs `playwright install-deps chromium` (apt) only if the probe fails.
- **Page readiness:** `waitForURL` only confirms navigation. Lazy routes can still
  render nothing during the delayed `RouteFallback`, and data can still be loading.
  Use retrying assertions on the expected page content before reading the DOM.
  The goals demo always contains savings targets, so its E2E test requires the
  named goal cards and their progress bars. A CSS grid or an immediate `count()`
  does not establish that the goals have loaded.
- Run them with `make test-frontend` and `make test-e2e` (starts Vite with `VITE_DEMO_MODE=true`).
- CI runs `make lint-frontend` and two Vitest shards (`make unit-frontend SHARD=1/2`, `SHARD=2/2`) as
  parallel legs; `ci-ok` checks their JSON reports together ran every test file exactly once.

## Sidecar tests

Every `services/<name>-auth` folder keeps its tests next to `main.py` as `test_*.py`
modules for the standard library's `unittest`: no pytest, no `tests/` subfolder, no
`sys.path` edits. CI discovers them, so adding a connector or a test file touches no
workflow file. The only inputs are the folder and the `ARG SIDECAR_BASE=picsou-sidecar-…`
line of its Dockerfile (`.github/scripts/sidecars.py` lists them). The `sidecar` job
runs `make test-sidecar SIDECAR=<name>-auth`: it builds the base and the image, then
runs `python -m unittest discover` in the image, from the checkout mounted read-only,
with the test-only helpers the image does not ship mounted back in.

The checkout is mounted read-only, so a test must not write next to itself. A test
that needs a live account or a real login is a script, not a `test_*.py` module (e.g.
`services/sofidy-auth/test_live_portal.py` has no test case, so discovery runs nothing
from it). `services/base` tests run the same way in the Camoufox base, which includes
the Python one (`sidecar-base` job). Camoufox sidecars also run on a native ARM64 runner.

The browserless sidecars (base `python`) also run outside a container, on macOS and
Windows, because the desktop app (#235) starts them that way. The `native-sidecars` job
runs `python .github/scripts/native_sidecars.py`: each one's tests from its folder with
`services/base` on `PYTHONPATH`, then a start with uvicorn on `127.0.0.1` and a free
port, and one `GET /health`. So a browserless sidecar must not hardcode a Linux path
(`/tmp`, `/app`, `/data`): use `tempfile.gettempdir()` or an environment variable with
today's Docker value as the default. CI runs it under uv on the latest 3.12 patch (the
runners' own 3.12 stops at 3.12.10, whose `html.parser` differs). Locally, run it with
the base image's Python after `pip install -r services/base/requirements.txt`.

## Release smoke tests

`docker/tests/test_deploy_integration.py` runs one `release-deployment` leg per parametrize
id (`.github/scripts/release_cases.py` reads them), each building the image and requiring
`1 passed`; the `unit` leg runs shellcheck, `test_deploy.py`, and checks the collected ids
match the legs.

## Running tests

`make test-backend`, `make test-frontend`, `make test-e2e` and `make test-sidecar SIDECAR=<name>`,
from the repository root. Options (a name filter, one sidecar) are in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md#commands).

## What to test first

1. **Behaviour a client can observe**: an integration test through the API, on both engines.
2. **Member scoping**: a second member must not read or change the first member's data.
3. **Pure logic with many cases** (a parser, a schedule, a rate): a unit test next to the code.
4. **A migration that rewrites rows**: seed the rows it operates on, apply it, and assert both
   the rows that must change and the rows that must not.

## Don'ts

- **Never depend on the current date or the system clock**: use the test clock.
- **Never call a real bank, exchange or price provider** from a test.
- **Never put real account data in a fixture**: synthetic only.
- **Never weaken or delete a test to make a change pass.** A test that guards a rule fails for
  a reason: fix the change, or change the rule in the open.
- **Never assert on a log line or a private function when an observable result exists.**
