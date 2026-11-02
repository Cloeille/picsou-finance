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
| `make dev` | PostgreSQL in Docker on `127.0.0.1:5432` (container `picsou-db`, kept between runs), the backend on http://localhost:8080 (`dev` profile, with a `JWT_SECRET` and `CRYPTO_ENCRYPTION_KEY` generated once into the gitignored `backend/.local/dev.env`) and Vite on https://localhost:5173 (proxies `/api/*` to the backend). Ctrl-C stops both processes. |
| `make test` | Every suite below, in order. |
| `make test-backend` | Backend Maven tests. `TEST=GoalServiceTest` or `TEST=GoalServiceTest#method` runs one class or method. The PostgreSQL tests need Docker and skip themselves without it. `SOFIDY_PYTHON=python3` also runs the Java/Python Sofidy contract test (`pip install -r services/base/requirements.txt` first). |
| `make test-frontend` | ESLint (zero warnings), TypeScript, Vitest. |
| `make test-e2e` | Playwright against demo mode; starts its own Vite, so stop `make dev` first (port 5173). |
| `make test-sidecar SIDECAR=tr-auth` | Builds one connector's shared base and image, then runs its tests inside it. |
| `make test-sidecars` | The `services/base` tests, then every connector. |
| `make build` | Every image Compose builds: the app, the connectors and their bases. `SERVICES="app tr-auth"` builds only those. |
| `make deps` | Reinstalls the frontend dependencies (after a `bun.lock` change). |
| `make release VERSION=x.y.z` | Maintainers. Checks that `CHANGELOG.md` has a `## [x.y.z]` section, that the tree is clean and `HEAD` is on `origin/main`, then tags and pushes the tag (`REMOTE=upstream` from a fork). CI publishes the images and the chart once green. |

`bun run build` (`tsc` + Vite) runs inside the app image, so `make build` fails on
a type error.

The `dev` profile points each bank connector at its own `127.0.0.1` port
(`backend/src/main/resources/application-dev.yml`). To use one, start its sidecar
from `services/<name>/` with `APP_SIDECAR_API_KEY=<same key as the backend> uvicorn main:app --port <port>`
(PYTHONPATH must include `services/base`; Revolut and AMEX also need the Xvfb display their
`entrypoint.sh` starts).

The frontend uses HTTPS in development. See the
[local certificate setup](README.md#-https-in-development-hybrid-mode) for a
trusted `mkcert` certificate or the zero-config self-signed fallback.

## Conventions

- **Branches**: `feature/xxx`, `fix/xxx`, `refactor/xxx`.
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) —
  `feat(scope):`, `fix(scope):`, `refactor(scope):`, `docs:`, `test:`, `chore:`.
- **Code style**: read [`docs/conventions/`](docs/conventions/) and the
  module guides ([`backend/CLAUDE.md`](backend/CLAUDE.md),
  [`frontend/CLAUDE.md`](frontend/CLAUDE.md)) before coding. The
  non-negotiables live in [`docs/CODING_RULES.md`](docs/CODING_RULES.md).
- **Database**: schema changes go through Flyway migrations
  (`backend/src/main/resources/db/migration/`) — never edit an applied
  migration; add a new versioned file.
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

## Pull requests

1. Fork, branch from `main`, keep the PR focused on one change.
2. Run the `make test-…` targets your change touches.
3. Describe *what* and *why*; link the issue if one exists.
4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.

## Security issues

**Do not open a public issue.** Follow [SECURITY.md](SECURITY.md) and report
privately via a GitHub Security Advisory.
