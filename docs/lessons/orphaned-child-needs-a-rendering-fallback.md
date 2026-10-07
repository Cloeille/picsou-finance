# Lesson: A child row whose parent is filtered out of a list needs an explicit rendering fallback, or it silently vanishes

> Date: 2026-07-14
> Context: `frontend/src/pages/accounts/AccountsPage.tsx`, branch `1.1.0`. Debugging why Chloé's Revolut wallet and its pocket sub-accounts stopped appearing on `/accounts` despite existing in the database.

## What happened

Chloé reported her Revolut accounts and pockets missing from `/accounts`, certain the data still existed. It did: the pocket sub-accounts (`Prélévements`, `Plaisir`, `Toulouse Chloé/Léo`, …) were untouched in the database. Their **parent** wallet, however, had been soft-deleted (`deleted_at` set) while she was cleaning up duplicate Revolut connections a couple weeks earlier.

Root cause: `Account` carries a class-level `@SQLRestriction("deleted_at IS NULL")`, so every normal query — including the one backing `GET /api/accounts` — silently excludes the soft-deleted wallet. The frontend's grouping logic assumed every pocket's parent would be present in the fetched account list: pockets were excluded from the "standalone" bucket (they have a `parentAccountId`) and excluded from the "wallet-grouped" bucket too (their parent wasn't in the list to group under). The pockets fell into neither rendering path and vanished — despite being alive, non-deleted rows with real balances that were also silently missing from net-worth totals.

This is invisible from either endpoint in isolation: the backend correctly returns the (still-existing) pockets, and the account-deletion endpoint correctly soft-deletes only the one row it was asked to delete. The bug only exists at the seam between them, in the frontend code that assumes referential completeness of the account list it receives.

## What we did

Changed the grouping logic so a pocket whose parent is absent from the fetched account list falls back to standalone rendering instead of being dropped: `a.parentAccountId == null || !accountIds.has(a.parentAccountId)`. No backend change — the soft-delete behavior on the parent stays as-is; only the frontend's assumption was wrong. Later extracted into a shared `useAccountTree` hook (`features/accounts/hooks.ts`) so the invariant is enforced in one place instead of being reimplemented (and potentially re-broken) wherever the account tree is rendered.

## How to apply

When a UI groups a flat list of rows into parent/child buckets by a foreign-key-like field (`parentAccountId`, `parentId`, …), and the parent list can be filtered independently (soft-delete, permissions, pagination), always ask: *what happens to a child whose parent isn't in the fetched set?* If the answer is "it's excluded from both the flat list (has a parent) and the grouped list (parent absent)," it will silently disappear — not error, not warn, just vanish from the render with no trace. The fix is almost always the same: a child with a missing/filtered-out parent should fall back to being rendered as if it had no parent, not be dropped. Build this as a single shared grouping function/hook rather than inlining it at each call site, so the invariant survives refactors.

## References

- Commit: `88075bc` (the fix), later refactored into `useAccountTree` in commit `6a057d4`
- Related: `docs/features/account-visibility.md` (the follow-up feature — a `hidden` flag deliberately kept separate from `deleted_at` precisely because `deleted_at`'s `@SQLRestriction` has this class of side effect)
