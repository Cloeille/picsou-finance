# Project: Picsou

Self-hosted personal-finance dashboard for an individual or a small family — bank sync, crypto, goals, net-worth tracking, multi-member sharing, 2FA.

## Stack

- Backend: Java 21 / Spring Boot 3.5.16 / Maven
- Frontend: React 19 / TypeScript 5.9 / Vite 7 / Tailwind v4
- DB: PostgreSQL 16 / Flyway
- Build: Maven (backend), bun (frontend)
- Deployment: Docker Compose; Kubernetes with the Helm chart in `charts/picsou`

## Essential commands

See [`CONTRIBUTING.md`](CONTRIBUTING.md#commands): one `make` target per task, run from the repository root.

## Code conventions

See [`docs/conventions/`](docs/conventions/) and module-specific CLAUDE.md files (`backend/CLAUDE.md`, `frontend/CLAUDE.md`).

## Project architecture

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full architecture overview, data flows, and external dependencies.

For module-specific details, see:
- [`backend/CLAUDE.md`](backend/CLAUDE.md) -- package structure, ports & adapters, auth, configuration
- [`frontend/CLAUDE.md`](frontend/CLAUDE.md) -- component hierarchy, API layer, demo mode, i18n

## Technical documentation

All documentation in `docs/` must be written in **English** — feature notes, ADRs, conventions, templates, and `INDEX.md`.

Before coding, check the relevant docs in `docs/`.

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) -- project macro view
- [`docs/decisions/`](docs/decisions/) -- technical decisions (ADR). Check them BEFORE proposing an alternative that was already evaluated.
- [`docs/features/`](docs/features/) -- technical notes per feature. Read the relevant note before touching an existing feature.
- [`docs/conventions/`](docs/conventions/) -- project-specific patterns and conventions
- [`docs/INDEX.md`](docs/INDEX.md) -- full documentation index

## Development workflow

**Bugfix / small change:**
1. Read the relevant feature doc in [`docs/features/`](docs/features/) (if one exists)
2. Follow conventions from [`docs/conventions/`](docs/conventions/)
3. Update the feature doc if behavior changed

**New feature / cross-cutting change:**
1. Read [`docs/INDEX.md`](docs/INDEX.md), identify all relevant docs
2. Check [`docs/decisions/`](docs/decisions/) for prior ADRs on the topic
3. Follow conventions from [`docs/conventions/`](docs/conventions/)
4. After: create/update feature doc ([`docs/templates/FEATURE.md`](docs/templates/FEATURE.md)) and ADR if architectural ([`docs/templates/DECISION.md`](docs/templates/DECISION.md))

## Git

- Branches: `feature/xxx`, `fix/xxx`, `refactor/xxx`
- Conventional commits: `feat(scope):`, `fix(scope):`, `refactor(scope):`, `docs:`, `test:`
- Always commit `docs/` updates alongside the related code

## Contributing with an agent

Before opening or updating a pull request, read [`CONTRIBUTING.md`](CONTRIBUTING.md#before-you-open-a-pull-request) and follow it; the pull request template mirrors it. The rules agents break most:

- One concern per pull request, about 600 changed lines at most (lockfiles, `CHANGELOG.md`, snapshots, fixtures excluded). Split bigger work: a small refactor first, then the feature in slices. Do not stack pull requests.
- Sync with `main` (merge it in) and have CI green before asking for review. Never weaken a lint, test or convention to pass.
- Every behaviour change has a test that fails without it. No test may depend on the current date: inject a `Clock`.
- A new migration takes the next free `V<n>` on an up-to-date `main`; after another pull request merges first, renumber your own unmerged file. `MigrationVersionsTest` enforces unique versions. Never renumber one already on `main`.
- Add one `CHANGELOG.md` entry under `[Unreleased]` as the last step, after your final sync. On a conflict there, keep both entries.
- Bank connectors: synthetic fixtures only. Never put real account data, sessions, tokens, screenshots or logs in code, tests, fixtures or the pull request.
- Before the first push and after every later one, review your whole diff against `main` with a reviewer agent (for example Claude Code's `/code-review`) and iterate until it reports no blocking issue. Do the impact analysis first (callers, docs, tests, migrations, i18n) and fix the whole bug class, not only the reported line. Put what the review found, and the design decisions, in the pull request description.
- Recommended, not required: the open-source pstack playbooks (`poteto-mode` skill) and the `grilling` skill to settle design decisions before coding; links in [`CONTRIBUTING.md`](CONTRIBUTING.md#contributing-with-a-coding-agent).

## Design Context

Authoritative design files: [`PRODUCT.md`](PRODUCT.md) (strategy: register, users, positioning, personality, anti-references, principles) and [`DESIGN.md`](DESIGN.md) (visual system: palette, typography, elevation, components, do's/don'ts). Sidecar with tonal ramps and component snippets lives at `.impeccable/design.json`.

- **Register**: product (dashboard serves the product). **Platform**: web (iOS companion under evaluation).
- **North Star**: "Le Coffre Lumineux" — a strongbox that doesn't need to look austere; credible without being cold, calm, private.
- **Brand personality**: credible, warm, composed. Rejects generic fintech flash, cold technical dashboards, hollow marketing, and traditional banking apps.
- **Palette**: restrained — **Coffre Blue** (oklch 0.488 0.243 264.376) is the only saturated color on interactive chrome, ≤10% of any screen. **Growth Green** family is reserved for charts/positive movement only.
- **Critical figures** (amounts, balances, alerts) render in **Ledger Ink** (oklch 0.145 0 0), never Quiet Slate — this is how the system meets AAA on the content the user came to read. AA everywhere else.
- **Elevation**: flat by default; hairline ring (`ring-1 ring-foreground/10`) separates cards from canvas, no shadows at rest. Shadows only on floating elements (dialogs, sheets, popovers).
- **Typography**: single family (Geist Variable) — hierarchy from weight and size, never a second face. Card titles small-bold (0.875rem / 700).
- **Motion**: feedback where it carries meaning (press-scale, sync), silent elsewhere. `prefers-reduced-motion` enforced globally.

When building UI, read `DESIGN.md` first; for non-visual product questions, read `PRODUCT.md` first.
