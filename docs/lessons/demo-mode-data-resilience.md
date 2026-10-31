# Lesson: demo-mode data resilience — truthy empty objects and stale references

> Recorded: 2026-06-28 · Module: frontend (demo mode / TanStack Query)

## What happened

A live smoke test of the Revolut-pockets feature in demo mode crashed `AccountsPage` and
the dashboard, and the pocket-rename save flow failed silently — even though `mvn test`
(600 tests) and `bun run build` were both green. Two distinct root causes, neither
catchable by TypeScript or unit tests.

**Root cause 1 — `{}` is truthy.**
The demo interceptor (`demoFetch`) returns `{}` when no registered handler matches a route.
The `GET /history` aggregated route had no handler, so the PnL memo in `AccountsPage` and
`NetWorthChart.filterByRange` received `{}` instead of an array. Code guarded with
`data ?? []` or `if (!data)` let `{}` through: `{}.length` is `undefined`, `.filter` blows
up at runtime. The crash was invisible until the page actually rendered in a browser.

**Root cause 2 — TanStack Query's `replaceEqualDeep` short-circuits on the same reference.**
The demo `PUT /accounts/{id}` handler mutated the shared mock array **in place**. TanStack
Query compared old vs. new reference after the mutation, saw the same object, and kept the
cached value — so the renamed pocket never appeared in the UI. The save appeared to succeed
(no error thrown) but was silently a no-op from the component's perspective.

## What we learned

- **`Array.isArray(x)` is the only reliable array guard.** `x ?? []`, `!x`, and `x?.length`
  all let a plain `{}` slip through. Anywhere list data flows from an API call or a demo
  interceptor, guard with `Array.isArray(x) ? x : []` before calling `.map`, `.filter`,
  `.length`, or any array method.
- **Demo mutation handlers must return a new reference.** Use `.map(...)` or spread
  `[...arr]` — never `arr.splice(...)` or direct property assignment on the shared array.
  TanStack Query uses structural equality (`replaceEqualDeep`) to decide whether to rerender;
  in-place mutation defeats it.
- **Green unit tests + green typecheck do not imply a working user flow.** Type errors and
  mock-based tests structurally cannot catch: missing demo route handlers returning `{}`,
  reference-identity bugs in query caching, or modal state that only fails after a user
  action (open modal → type → save).

## Why it matters

The two bugs were introduced in otherwise correct-looking code. TypeScript cannot type-check
what a fetch interceptor returns at runtime, and unit tests do not drive the real demo layer.
The pattern recurs every time a new page or feature is wired to demo mode: a missing handler
silently returns `{}`, and a new mutation handler might mutate in place out of habit.

An independent validator (not the author of the fix) caught a broken save flow that the
fixer's own smoke missed — because the fixer only verified initial page load, not the
save action. The same test plan executed in the same order by the same person who wrote
the code has blind spots; a second pass by a different observer finds them.

## Takeaway

- **Array guard:** always `Array.isArray(x) ? x : []`, never `x ?? []`, for any data that
  could come from an API, a demo interceptor, or a TanStack Query cache.
- **Demo mutation handler:** reassign with `.map(...)` or `[...arr]`; never mutate the mock
  in place. Example:
  ```ts
  // Wrong — same reference, query cache stays stale
  mockAccounts.find(a => a.id === id)!.name = newName;

  // Correct — new reference, query cache updates
  mockAccounts = mockAccounts.map(a => a.id === id ? { ...a, name: newName } : a);
  ```
- **When adding a demo handler for a new route:** immediately verify in the browser, not
  just in unit tests. Check the Network tab for `{}` responses (or add a dev warning in
  `demoFetch` for unmatched routes).
- **Smoke test discipline:** the validator must exercise the full user flow (open → interact
  → save → confirm result), not just initial page load. Builder ≠ validator.

## Links

- Feature note: [demo-mode.md](../features/demo-mode.md)
- Feature note: [revolut-pockets.md](../features/revolut-pockets.md)
- Feature note: [accounts-overview.md](../features/accounts-overview.md)
