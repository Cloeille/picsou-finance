# Feature: UI control shape (shadcn theme radius)

> Last updated: 2026-08-10

## Context

Every interactive control in the app (buttons, filter chips, tabs, segmented controls, dropdown
menus, inputs) must read as one design system. Their corner radius is not chosen per component — it
derives from a single theme token, `--radius`, so cards, controls, and menus stay visually
consistent. This note exists because a large frontend rewrite once broke that consistency and the
breakage was hard to trace (see Gotchas).

## How it works

`--radius: 0.625rem` (10px) is defined once in `frontend/src/index.css`. Tailwind v4 derives the
whole scale from it (`--radius-sm/md/lg/xl/2xl/...` as multiples). Controls pick a rung of that
scale; they never hardcode a pixel radius or a pill shape:

| Surface | Radius class | ≈ px |
|---------|-------------|------|
| Interactive text control (button, filter chip, tab/segment item, menu item) | `rounded-md` | 8 |
| Control container (segmented control, dropdown/popover) | `rounded-lg` | 10 |
| Cards / large surfaces | `rounded-xl` … `rounded-4xl` | 14+ |
| Circular by nature (avatar, switch thumb, badge, status dot, scrollbar) | `rounded-full` | — |

Shape lives in the shadcn primitives (`frontend/src/components/ui/button.tsx`, `tabs.tsx`,
`dropdown-menu.tsx`), so a page composes `<Button>` / `<Tabs>` and inherits the correct radius. App
code must not re-shape a control with a local `rounded-*` className.

### Key files

- `frontend/src/index.css` — `--radius` base token and the derived radius scale
- `frontend/src/components/ui/button.tsx` — `rounded-md` base (do not edit off-scale)
- `frontend/src/components/ui/tabs.tsx` — list `rounded-lg`, trigger `rounded-md`
- `frontend/src/components/ui/dropdown-menu.tsx` — container `rounded-lg`, items `rounded-md`
- `docs/conventions/frontend.md` — the enforced rule + Don'ts (§ Controls, § Don'ts)
- `docs/CODING_RULES.md` — the non-negotiable charter this rule belongs to

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Single `--radius` token, scale derived | One knob re-shapes the whole app coherently | Per-component hardcoded radii — drift, no single source of truth |
| Controls at `rounded-md`, not `rounded-full` | Pills next to `rounded-lg` cards read as a foreign design system in a data-dense finance UI | Full-pill controls (would require pilling inputs/badges too to stay coherent) |
| Radius owned by shadcn `ui/` primitives | Compose-and-inherit; pages never restyle shape | Per-page `rounded-*` overrides — the exact drift #29 introduced |

## Gotchas / Pitfalls

- **`rounded-full` on a `<Button>` className overrides the primitive.** tailwind-merge keeps the last
  radius class, so a page-level `className="… rounded-full"` silently re-pills a control even after
  `button.tsx` is correct. Fixing the primitive is not enough — the override must be removed too.
- **Regression origin — commit `716228e` (PR #29, agent-generated).** It flipped `Button`,
  `Tabs`, and `DropdownMenu` off the scale (`rounded-md/lg` → `rounded-full/2xl/xl`), hardcoded
  `rounded-full` filter chips across pages, **and rewrote `docs/conventions/frontend.md` in the same
  diff to bless pills and forbid `rounded-md`** — which made the deviation look compliant in review.
  Reverted 2026-07-12. This is why the convention-integrity rule exists in `CODING_RULES.md`.
- **`rounded-4xl` on cards is intentional**, not drift — it predates #29. Do not "fix" card radius to
  match controls; cards are deliberately rounder than controls.
- **The rule is honoured by the primitives, not yet by every call site.** Roughly 19 hardcoded
  `rounded-xl`/`rounded-2xl` controls predate the 2026-07-12 revert and are still there:
  `AccountForm`, `AddAccountModal` and `FinaryTab` selects, the `SettingsPage` /
  `HoldingDetailModal` / `HoldingInsightSection` segmented controls, `AccessKeysSection` code
  rows, and the setup layout. So `rounded-xl` on a select is what most of the app currently
  *does*, while `rounded-md` is what the charter *says* — copying a neighbouring form is how a
  new screen inherits the deviation, which is exactly how the property flow got it. Judge a new
  control against this document, not against the file next to it. Fixing the backlog is a
  separate sweep, deliberately not folded into feature branches.
- **Don't hand-edit `frontend/src/components/ui/`** to change radius/sizing. Shape comes from the `--radius` theme
  token; adjust the token, not the primitives.

## Tests

- No dedicated unit test (pure styling). `frontend/src/components/layout/AppSidebar.test.tsx` and the
  Playwright E2E suite exercise the components that consume these primitives.
- Conformance is enforced by review + the grep-able Don'ts in `docs/conventions/frontend.md`
  (no `rounded-full`/`rounded-xl`/`rounded-2xl` on interactive text controls). Review is the
  only gate, and the backlog above is what it has let through — a lint rule would catch it,
  but none exists today.

## Links

- ADR: `docs/decisions/2026-07-12-ui-controls-follow-shadcn-theme-radius.md`
- Convention: `docs/conventions/frontend.md` (§ Controls, § Don'ts)
- Charter: `docs/CODING_RULES.md`
- Related feature: `docs/features/theme-persistence.md` (theme tokens, dark/light)
