# Contributing to Picsou

Thanks for your interest in improving Picsou! This guide covers the practical
side of getting a change merged.

## Prerequisites

- GNU Make, Docker
- Java 21 + Maven (backend). Maven enforces Java 21 during `validate`: point
  `JAVA_HOME` at a JDK 21.
- [Bun](https://bun.sh) (frontend)

## Commands

Every command runs from the repository root. CI calls the same targets, so a
green `make test-…` locally is the CI job. `make help` lists them.

| Command | What it does |
|---|---|
| `make dev` | PostgreSQL in Docker on `127.0.0.1:5432` (container `picsou-db` with user, password and database `picsou`, kept between runs), the backend on http://localhost:8080 (`dev` profile, with the `JWT_SECRET` and `CRYPTO_ENCRYPTION_KEY` generated when `picsou-db` is created and stored in it, so every checkout decrypts the same data; exported values win; a `picsou-db` created by hand before `make dev` holds none, so export the `JWT_SECRET` and `CRYPTO_ENCRYPTION_KEY` you ran it with) and Vite on https://localhost:5173 (proxies `/api/*` to the backend). Ctrl-C stops both processes. |
| `make test` | Every suite below, in order. |
| `make test-backend` | Backend Maven tests. `TEST=GoalServiceTest` or `TEST=GoalServiceTest#method` runs one class or method. The PostgreSQL tests need Docker and skip themselves without it. `SOFIDY_PYTHON=python3` also runs the Java/Python Sofidy contract test (`pip install -r services/base/requirements.txt` first). |
| `make test-frontend` | ESLint (zero warnings), TypeScript, Vitest. |
| `make test-e2e` | Playwright against demo mode; starts its own Vite, so stop `make dev` first (port 5173). |
| `make test-sidecar SIDECAR=tr-auth` | Builds one connector's shared base and image, then runs its tests inside it. |
| `make test-sidecars` | The `services/base` tests, then every connector. |
| `make build` | Every image Compose builds: the app, the connectors and their bases. `SERVICES="app tr-auth"` builds only those. |
| `make format` | Prettier on the frontend sources. |
| `make deps` | Reinstalls the frontend dependencies (after a `bun.lock` change). |
| `make release VERSION=x.y.z` | Maintainers. Checks that `CHANGELOG.md` has a `## [x.y.z]` section, that the tree is clean and `HEAD` is on `origin/main`, then tags and pushes the tag (`REMOTE=upstream` from a fork). CI publishes the images and the chart once green. |

`bun run build` (`tsc` + Vite) runs inside the app image, so `make build` fails on
a type error.

The `dev` profile points each bank connector at its own `127.0.0.1` port
(`backend/src/main/resources/application-dev.yml`). To use one, start its sidecar
from `services/<name>/` with `APP_SIDECAR_API_KEY=<same key as the backend> uvicorn main:app --port <port>`
(PYTHONPATH must include `services/base`; Revolut and AMEX also need the Xvfb display their
`entrypoint.sh` starts).

### HTTPS in development

Some bank integrations require HTTPS, so Vite serves `https://localhost:5173`.
For a certificate the browser trusts, install `mkcert` and run from `frontend/`:

```bash
mkdir -p .local/certs
mkcert -cert-file .local/certs/picsou-local-cert.pem -key-file .local/certs/picsou-local-key.pem localhost 127.0.0.1 ::1
```

Without these files, `@vitejs/plugin-basic-ssl` serves a self-signed certificate:
accept the browser warning once.

## Conventions

- **Branches**: `feature/xxx`, `fix/xxx`, `refactor/xxx`.
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) —
  `feat(scope):`, `fix(scope):`, `refactor(scope):`, `docs:`, `test:`, `chore:`.
- **Code style**: read [`docs/conventions/`](docs/conventions/) and the
  module guides ([`backend/CLAUDE.md`](backend/CLAUDE.md),
  [`frontend/CLAUDE.md`](frontend/CLAUDE.md)) before coding. The
  non-negotiables live in [`docs/CODING_RULES.md`](docs/CODING_RULES.md).
- **Database**: schema changes go through Flyway migrations
  (`backend/src/main/resources/db/migration/`) — never edit a migration that is on
  `main`; add a new versioned file (numbering: see
  [Before you open a pull request](#before-you-open-a-pull-request)).
- **i18n**: user-visible strings go through `useTranslation()`; add every new
  key to all four locale files (`fr`, `en`, `de`, `es`).

## Documentation

Documentation lives in `docs/` and is written in **English**:

- Behavior change → update the matching note in
  [`docs/features/`](docs/features/) (template:
  [`docs/templates/FEATURE.md`](docs/templates/FEATURE.md)).
- Architectural choice → add an ADR in
  [`docs/decisions/`](docs/decisions/) (template:
  [`docs/templates/DECISION.md`](docs/templates/DECISION.md)).
- Commit docs updates together with the related code.

## Before you open a pull request

The pull requests that merge fastest here are small, focused, synced with `main` and green when
review starts. Measured on the 101 pull requests opened between 2026-10-20 and 2026-11-06, those
of at most 600 changed lines averaged 4.6 commits and 2.6 CodeRabbit reviews; the larger ones
averaged 17.8 commits and 7.9 reviews, and 52% of those above 2,000 lines are merged so far against 98%
of those at or below 600. [The pull request template](.github/PULL_REQUEST_TEMPLATE.md) repeats
the checklist below.

1. **One concern per pull request.** A refactor, a feature and a dependency bump are three pull
   requests. Do not mix a mechanical change (rename, move, reformat) with behaviour changes.
2. **Stay under about 600 changed lines**, not counting lockfiles, `CHANGELOG.md`, snapshots and
   fixtures. It is a soft budget: a workflow comments when a pull request is above it and never
   blocks it. If the work is bigger, land a small refactor first, then the feature in slices that
   each build and pass on their own. If it cannot be split, say why in the description.
3. **Discuss first** what is not a bug fix: open an issue for a feature, a new bank connector or a
   schema change, and agree on the approach before writing it. Check
   [`docs/decisions/`](docs/decisions/) for a choice that was already evaluated.
4. **Sync with `main` and get CI green before asking for review.** Merge `main` into your branch
   (a merge, not a rebase, so commits others fetched stay valid) right before you open the pull request and again before you ask for another
   round; run the `make test-…` targets your change touches. Open a draft while it is red.
5. **Test every behaviour change**, including the failure case (a fix starts with a test that
   fails without it). Tests must not depend on the date or the time of day: inject a `Clock` or
   use dates relative to a fixed one, never `LocalDate.now()` against a literal date. A test that
   is green today and red next month blocks every other pull request.
6. **Migrations.** Take the next free `V<n>` on an up-to-date `main`. Another open pull request
   may take the same number: whichever merges second renumbers its own **unmerged** file (and
   anything that names it) after syncing; `MigrationVersionsTest` fails the build on a duplicate.
   Never renumber or edit a migration that is already on `main`
   ([why](docs/conventions/database.md#writing-a-new-migration)). Name the version in the pull
   request description, and recreate your local `picsou-db` after a renumber.
7. **`CHANGELOG.md`**: one entry under `[Unreleased]` for a user-visible change, written as the
   last step, after your final sync with `main`, so it rarely conflicts. When it does (every pull
   request appends to the same place), keep both entries. The same goes for the table in
   [`docs/INDEX.md`](docs/INDEX.md).
8. **No stacked pull requests** (one branch built on another open pull request) unless the
   maintainer agreed. A squash merge rewrites the lower pull request, and the upper one then
   conflicts in every file they share. If you do stack: base the upper pull request on `main`
   and say which one must merge first. After the lower one is squash-merged, move yours onto
   `main` with `git rebase --onto origin/main <last commit of the lower branch> <your branch>`
   and `git push --force-with-lease`.
9. **Bank connectors** (`services/*-auth`, adapters, importers): use synthetic fixtures only.
   Never commit or paste a real account number, IBAN, balance, name, session, cookie, token,
   screenshot, HAR or log from a real account, in the code, the tests, the description or the
   comments. A connector cannot be merged on tests alone: the maintainer runs it once against a
   real account, so open the issue first to agree on who can do that.
10. **Docs and i18n travel with the code** (see [Documentation](#documentation) and
    [Conventions](#conventions)): feature note, ADR, all four locale files.

### Contributing with a coding agent

Most pull requests here are written with a coding agent. The agent reads
[`AGENTS.md`](AGENTS.md), which points back to this page; the loop below is what makes its output
reviewable. You stay responsible for every line you push.

- **Settle the design before the code.** Have the agent interview you about the plan (inputs,
  edge cases, what is out of scope) until no decision is left implicit, then write the decisions
  into the pull request description.
- **Impact analysis first.** Before editing, list callers and consumers (backend, frontend,
  sidecars), tests, docs, migrations and i18n keys the change touches.
- **Fix the whole bug class**, not the reported line: search for the same pattern elsewhere in
  the diff and the codebase, and cover each fixed instance with a test.
- **Self-review loop, before opening and after every push.** Run a reviewer agent on the full
  diff against `main` (for example Claude Code's `/code-review`, or any other reviewer you
  trust), fix what it reports, and run it again until it reports no blocking issue. Treat
  CodeRabbit the same way: answer or fix every thread before requesting the maintainer. Put what
  the loop found in the pull request description.
- **Do not weaken a rule to pass.** If a lint, test or convention gets in the way, say so in the
  description instead of editing the rule in the same diff
  ([`docs/CODING_RULES.md`](docs/CODING_RULES.md), rule 0).

Optional, open-source tools that fit this loop. They are recommendations, not requirements:

- [pstack](https://github.com/cursor/plugins/tree/main/pstack) (rigorous engineering playbooks
  by poteto; [Claude Code port](https://github.com/michael-denyer/pstack-claude), then use its
  `poteto-mode` skill: `/plugin marketplace add michael-denyer/pstack-claude` and
  `/plugin install pstack@pstack-claude`).
- The [`grilling` skill](https://github.com/mattpocock/skills/tree/main/skills/productivity/grilling)
  from `mattpocock/skills`, to settle design decisions before coding.

If you used them, list them in the pull request description.

### Opening the pull request

- Fork, branch from `main` (see [Conventions](#conventions) for names), open it against `main`.
- Fill in the [template](.github/PULL_REQUEST_TEMPLATE.md): *what* and *why*, the issue it
  closes, what you tested, and what a self-review found.

## Security issues

**Do not open a public issue.** Follow [SECURITY.md](SECURITY.md) and report
privately via a GitHub Security Advisory.
