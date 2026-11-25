# ADR: A dependency cache and test shards for the Rust CI jobs

> Date: 2026-11-24
> Status: Active

## Context

A pull request that touches the backend waited 24 minutes for `ci-ok` (median of 6 full runs,
19 to 27 minutes) and 26 minutes for its last check. Four jobs compiled every Rust dependency
from nothing on each run:

| job | median | where the time went |
|---|---|---|
| backend-rs | 23 min | clippy checks every dependency (3.5 min), the test build compiles them again (3.5 min) and then the crate (4.5 min); the integration tests run on PostgreSQL (5 to 7 min), then again on SQLite (4 to 5 min) |
| desktop-macos, desktop-windows | 22 to 25 min | a release build of every dependency and of the backend, on one codegen unit |
| desktop | 19 min | the same on Linux, after a debug build for the tests |

The [parallel legs ADR](./2026-11-06-parallel-ci-legs.md) had brought the Java backend under
four minutes; the [Rust backend](./2026-11-21-rust-backend.md) replaced its jobs with one
sequential job and no cache. The repository's Actions cache (10 GB) was already full.

## Decision

- **Cache the compiled dependencies** with `Swatinem/rust-cache`. Every run restores them; only a
  push to `main` or a release branch saves them. The six backend legs share one cache, which the
  lint leg writes; each desktop job has its own.
- **Split `backend-rs` into legs under its job key**: a lint leg (`cargo fmt`, clippy, the unit
  and doc tests), three shards of the integration tests on PostgreSQL and two on SQLite. Each
  leg compiles the crate itself.
- **A shard is every nth test of the binary's sorted list** (`make integration-backend
  SHARD=k/n`, `.github/scripts/backend_shards.py`). `ci-ok` reads two lines of each shard's
  output, the tests it selected and its result counts, and fails unless every leg reported, none
  failed, each accounts for all it selected (passed, or ignored as in an unsharded run), and the
  selections cover every test exactly once on each engine. It does not read the `test … ok` lines: the application's log lines split them.
- **CI-only build settings**: no debug information for the backend's and the desktop shell's
  test builds; 16 codegen units for the desktop release builds, next to the link-time
  optimisation that was already off; the test PostgreSQL keeps its data in memory.

## Alternatives considered

### sccache

- **Pros**: caches each crate on its own, so a changed `Cargo.lock` recompiles less.
- **Cons**: hundreds of cache entries per job against the 10 GB quota and the cache API's rate
  limit; no help for the one crate a backend pull request changes.

### Build the test binary once and pass it to the shards

- **Pros**: about 10 runner minutes less per run.
- **Cons**: no faster for the contributor: the upload and download cost what the two-minute
  crate build saves, and the shards wait for the build job before they start.

### cargo-nextest (archive and `--partition`)

- **Pros**: built-in shards, one process per test.
- **Cons**: another tool to pin and install in every leg; one process per test changes how the
  suite runs (the harness shares its template database through a process-wide cell).

### A list of modules per shard

- **Pros**: no script.
- **Cons**: a list to keep balanced by hand, and a new module must land in the catch-all leg.

### Saving the cache from pull requests

- **Pros**: a pull request that changes `Cargo.lock` is warm from its second run.
- **Cons**: a pull request's cache is visible to that pull request alone and counts against the
  10 GB; a few open pull requests evict the caches every other run needs.

### A self-hosted runner

- **Pros**: a warm disk, no cache to download.
- **Cons**: this repository is public: a pull request from a fork would run its code on the
  machine. One machine also has fewer cores than the hosted runners give in parallel.

## Reasoning

The time went to compiling the same dependencies four times per run, and to two test passes
run one after the other. A cache removes the first, parallel legs the second, and neither
drops a check. Keeping the job key keeps `ci-ok`'s skipped-versus-selected logic and the
publish gate unchanged. Striping the sorted list needs no data from an earlier run, so the
legs cannot disagree on the split, and the shards stay balanced as tests are added.

## Trade-offs accepted

- A backend pull request runs six backend legs instead of one, against the free plan's limit of
  about 20 concurrent jobs.
- About 30 runner minutes per backend run for the backend legs, against 23: each leg compiles
  the crate.
- After the runners' Rust toolchain changes, or on a pull request that changes `Cargo.lock`,
  the legs compile what the cache lacks until the next push to the base branch saves it.
- A failed test in CI prints its file and line, but a backtrace has no line numbers.
- The desktop jobs build with a profile that differs from the release bundles in two settings
  (it was one); `desktop-release.yml` keeps the release profile.

## Consequences

- Measured on a pull request that changes a backend source, caches warm: `ci-ok` after
  7 min 18 s (median of 3 runs, 7 min 04 s to 7 min 38 s), against 24 minutes; the last check
  after 13 minutes, against 26. The release smoke leg now sets the wall clock: it compiles the
  crate with the release profile (4 minutes).
- `make test-backend` runs the same commands as before, through three new targets:
  `make lint-backend`, `make unit-backend` and `make integration-backend [DB=sqlite] [SHARD=k/n]`.
- A test must not depend on another test having run in its process: the shards split modules.
- The first run after a cache miss takes about ten minutes for `ci-ok`.
