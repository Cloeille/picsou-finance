# ADR: Parallel CI legs that prove their own completeness

> Date: 2026-11-06
> Status: Active (backend shards first; the other legs land in their own pull requests)

## Context

A pull request waited about 7.5 minutes for CI (median of 19 full runs), and 11 to 12 minutes in
about one run in ten. The wall clock is the slowest job:

| job | median | where the time went |
|---|---|---|
| backend | 422 s | one surefire fork: 336 s of test classes plus ~80 s of JVM, Docker and Spring start-up |
| release-deployment | 356 s | image build ~80 s, a 20-60 s cache export on every run, then the two smoke cases one after the other (92 s + 105 s) |
| frontend | 194 s | ESLint, tsc and Vitest one after the other |
| e2e | 122 s, 634-683 s on slow runs | `playwright install --with-deps` ran apt every time; a slow mirror took nine minutes |

Runner minutes and job slots are free on this public repository. What costs is the time a
contributor waits.

## Decision

Split the long jobs into parallel legs under their **existing job keys**, so `ci-ok`'s
skipped-versus-selected logic and the publish gate keep their meaning. Each split proves it ran
everything:

- **backend**: a three-leg matrix. `.github/scripts/backend_shards.py` balances the test classes
  on the last main run's class times (longest first onto the least-loaded leg; a hash split on a
  cache miss). Legs 1 and 2 get an includes file; leg 3 runs everything they do not select, so a
  new class always runs. `ci-ok` downloads every leg's reports into its own folder and fails when
  a class ran in two legs or a test class has no report, then checks the PostgreSQL
  required-classes list as before. On main pushes it saves the class times for the next split.
- **release-deployment**: a `unit` leg (shellcheck, `test_deploy.py`, and a guard that every
  collected smoke case has a leg) plus one leg per smoke case. The case ids come from the test
  module (`release_cases.py`). A leg fails unless its one case passed, so a skip is red. Only the
  `http` leg exports the build cache, on pushes and on pull requests that change the image's
  dependencies.
- **frontend**: a lint leg and two Vitest shards. `ci-ok` checks the shards' JSON reports together
  ran every test file exactly once.
- **e2e**: install Chromium, probe a headless launch, and run `install-deps` only when the probe
  fails, with an `e2e-deps-fallback` notice.
- Every CI job has a `timeout-minutes`; CodeQL analyses only the languages a pull request touches
  (all four on push and on the weekly schedule).

## Alternatives considered

### Surefire `forkCount=2` in one backend job

- **Pros**: a pom change, no extra job, faster local runs.
- **Cons**: 255-315 s on four shared vCPUs, still the slowest job; two JVMs on one runner share
  `/tmp` and Docker, which exposed a test counting files in the shared temporary folder.

### Two backend shards

- **Pros**: one job slot fewer.
- **Cons**: 215-255 s, still above the release floor.

### Running the two smoke cases with pytest-xdist in one job

- **Pros**: one image build.
- **Cons**: both cases create a Docker network on the same fixed subnet, so they cannot run side
  by side; four vCPUs make each case 20-50 % slower.

### Building the release image once and passing it to the case legs

- **Pros**: one cold build when dependencies change (fewer Maven Central requests).
- **Cons**: ~210 s per leg against ~200 s: the hand-off (upload, download, load) costs more than
  the build it saves on a warm cache.

### Larger runners

- **Pros**: the backend could stay one job.
- **Cons**: billed per minute even on a public repository; the owner's decision, not the code's.

### Testing less (path filters on release-deployment, a shared PostgreSQL container)

- **Cons**: drops coverage the [release migration ADR](./2026-10-08-rehearse-release-migrations.md)
  and the per-class container convention require.

## Reasoning

Each leg lands at or under the release-deployment floor (~200 s: one warm image build plus the
slowest case), which nothing short of testing less removes. More legs than that buy no wall
clock. Keeping the job keys stable avoids a silent green: `ci-ok` accepts a skipped job only when
the path filter did not select it, which a renamed job would never match.

## Trade-offs accepted

- A typical pull request runs about 12 jobs at once instead of 8, against the free plan's
  per-account limit of about 20; wide pull requests (every sidecar) queue a little more.
- More runner minutes per pull request.
- New scripts to maintain: `backend_shards.py` and `release_cases.py`; `vitest_coverage.py` checks
  the frontend shards.
- A main push that changes dependencies builds the release image cold in both case legs.
- `changes` looks the class times up once and every backend leg restores that exact key, so the
  legs split on the same times, and a re-run splits as its first attempt did. When the cache is
  evicted between the lookup and a leg's restore, that leg falls back to the hash split and can
  disagree with the others; the exactly-once check then fails loudly and a full re-run fixes it.

## Consequences

- Target pull-request wall clock: about 4 minutes (the ~200 s release floor plus set-up) once every
  leg has landed; not measured yet, each leg's pull request reports its own timings.
- `make test-backend` takes `SHARD_INCLUDES` / `SHARD_EXCLUDES`; the plain target is unchanged.
  `make lint-frontend` and `make unit-frontend [SHARD=i/n]` run the frontend legs.
- A test must not count entries in a shared folder such as `java.io.tmpdir`: inject the folder.
