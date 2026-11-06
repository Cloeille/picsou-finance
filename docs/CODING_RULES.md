# Coding Rules — non-negotiable charter

> Last updated: 2026-07-12

The **conventions** in [`docs/conventions/`](./conventions/) describe *how* we write code in each
area. This file is shorter and stricter: it holds the **non-negotiables** — the rules whose
violation has actually cost us, and the one meta-rule that keeps the conventions trustworthy. Read
it before a large refactor or before reviewing one.

For area detail always defer to the convention file; this charter never contradicts them, it ranks
what must never slip.

## 0. Convention integrity (the meta-rule)

**A change must never weaken a convention in the same diff that introduces the deviation the
weakening would legitimise.** Conventions are guardrails; they only work if moving one is *harder*
than following it. Loosening a rule is a deliberate, standalone decision — not a side effect of a
feature.

In review, this is a hard gate:

- **Any diff that edits `docs/conventions/` (or this file) together with code is a smell.** Stop and
  justify it explicitly.
- Ask: *does this convention edit exist to make a deviation elsewhere in the same diff look
  compliant?* If yes → **reject**. Split it: land the rule change on its own, ratified for its own
  sake, or drop the deviation.
- This applies with extra force to agent-generated PRs, which will "resolve" a lint/convention
  mismatch by editing whichever side is cheaper — often the rule.

> Origin: commit `716228e` (PR #29) turned every control into a pill **and** rewrote
> `docs/conventions/frontend.md` to bless pills and forbid the old shape — so review passed. The
> visual regression was caught by a human weeks later, not by the process.

## 1. Never hand-edit `components/ui/`

shadcn/ui primitives are generated. Customize via **theme tokens** (`--radius`, color tokens in
`index.css`) or the shadcn CLI — never by editing the primitive off its scale. In particular, never
inflate a control's radius (e.g. `rounded-md` → `rounded-full` on `Button`). See
[`docs/features/ui-control-shape-system.md`](./features/ui-control-shape-system.md) and
[`docs/conventions/frontend.md`](./conventions/frontend.md).

## 2. Follow the theme, not per-component styling

Radius, color, and control sizing come from `--radius` and the semantic color tokens. Never hardcode
`rounded-full`/`rounded-xl` on interactive text controls, and never use raw palette classes
(`text-gray-*` — they render blue here). Detail: [`docs/conventions/frontend.md`](./conventions/frontend.md).

## 3. Layers stay separated

- API calls only in `features/*/api.ts`; domain hooks only in `features/*/hooks.ts`. No `fetch` in
  components, no server state in Zustand/Context (TanStack Query only). — [frontend.md](./conventions/frontend.md)
- Business logic only in the framework-free core; adapters are 1:1 delegation. — [api-rest.md](./conventions/api-rest.md)

## 4. Migrations and versioning

- Flyway owns the schema; migrations are append-only and never edited once merged.
- Migration numbering spans branches: take the next free `V<n>` on an up-to-date `main`, and when
  another pull request merges first, renumber your own *unmerged* file after syncing
  (`MigrationVersionsTest` fails on a duplicate). Never renumber one already on `main`.
  — [database.md](./conventions/database.md)

## 5. Tests are ground truth

Don't claim work is done on assertion — run the verification and read the output. `make test-frontend`
(lint at zero warnings, typecheck, Vitest) must stay green. — [testing.md](./conventions/testing.md)

## 6. i18n and docs

- Never hardcode user-visible strings — always `useTranslation()`. — [i18n.md](./features/i18n.md)
- All files in `docs/` are written in English. Update the relevant doc in the **same** change as the
  code it describes.

---

*This charter is intentionally short. If a rule here needs nuance, that nuance lives in the linked
convention — add it there, deliberately, per rule 0.*
