# Contributing to Picsou

Thanks for your interest in improving Picsou! This guide covers the practical
side of getting a change merged.

## Prerequisites

- Java 21 + Maven (backend)
- [Bun](https://bun.sh) (frontend)
- PostgreSQL 16 on `:5432` (or `docker compose up db`)

## Build & run

Run each process in its own terminal (the backend stays in the foreground):

```bash
# Terminal 1 — backend (http://localhost:8080)
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

```bash
# Terminal 2 — frontend (https://localhost:5173, proxies /api/* to the backend)
cd frontend
bun install
bun run dev
```

The frontend uses HTTPS in development. See the
[local certificate setup](README.md#-https-in-development-hybrid-mode) for a
trusted `mkcert` certificate or the zero-config self-signed fallback.

## Tests

Run the relevant suites before opening a PR:

Each command is wrapped in a subshell, so it runs from the repository root
without leaving you in a subdirectory:

```bash
# Backend
(cd backend && mvn test)

# Frontend
(cd frontend && bun run typecheck)   # TypeScript
(cd frontend && bun run lint)        # ESLint (zero-warning policy)
(cd frontend && bunx vitest run)     # Unit tests
(cd frontend && bun run test:e2e)    # Playwright E2E (needs the dev server)
```

`bun run build` must pass — it runs `tsc` and fails on type errors.

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
2. Make sure tests, lint, and typecheck pass locally.
3. Describe *what* and *why*; link the issue if one exists.
4. Update `CHANGELOG.md` under `[Unreleased]` for user-visible changes.

## Security issues

**Do not open a public issue.** Follow [SECURITY.md](SECURITY.md) and report
privately via a GitHub Security Advisory.
