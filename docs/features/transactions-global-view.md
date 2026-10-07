# Global Transaction View & Transaction Component

## Summary

Three related improvements shipped together:

1. **Fix** — wire up inline category editing in `CategoryDetailPage` (`/budget/spending/:id`)
2. **Shared components** — `TransactionRow` (atomic row) + `TransactionDetailSheet` (detail modal) used everywhere
3. **Global view** — new `/budget/transactions` page with cross-account search, date range, account and category filters

## Shared Components

### `TransactionRow` (`components/shared/TransactionRow.tsx`)

Pure presentational row. No state, no fetching.

Props:
- `transaction: Transaction`
- `logoUrlFor?: (brandId: number | null | undefined) => string | null`
- `onClick?: (tx: Transaction) => void`

Displays: merchant avatar, name, category badge (plain text, not interactive), amount, "Manuel" badge. Full row is clickable when `onClick` is provided.

### `TransactionDetailSheet` (`components/shared/TransactionDetailSheet.tsx`)

Sheet (mobile bottom) / Dialog (desktop) — same responsive pattern as the rest of the app.

Props:
- `transaction: Transaction | null`
- `account?: string` — account display name, shown when in cross-account context
- `categories?: Category[]`
- `onCategorize?: (txId: number, categoryId: number) => void`
- `onEdit?: (tx: Transaction) => void`
- `onDelete?: (txId: number) => void`
- `open: boolean`
- `onClose: () => void`

Content: merchant avatar + name, raw description, amount (+ native currency if different), date, account name (if provided), category picker (select + Confirm, pre-filled with current category), edit/delete buttons for manual transactions.

### `TransactionsList` (refactored)

Refactored to use `TransactionRow` + `TransactionDetailSheet`. Removes the inline category picker that was previously embedded. Retains: date grouping, search bar, Card wrapper.

## Fix — CategoryDetailPage

`CategoryDetailPage` was missing `categories` and `onCategorize` props on `TransactionsList`. Fix: import `useCategories` + `useCategorize` from `features/budget/hooks` and pass them, mirroring `AccountDetailPage`.

## Backend Endpoint

`GET /api/transactions`

Query params:
- `from` (ISO date, required)
- `to` (ISO date, required)
- `accountId` (integer, optional)
- `categoryId` (integer, optional)

Returns `Transaction[]` sorted by date DESC. Scoped to the authenticated user — no cross-user access. New method in `TransactionCategorizationController` (already mapped to `/api/transactions`).

## Frontend — Hook

`useTransactions({ from, to, accountId?, categoryId? })` in `features/budget/hooks.ts`. React Query key includes all four params. Enabled only when `from` and `to` are set.

## Page — `/budget/transactions`

New page `BudgetTransactionsPage`.

**Filters bar:**
- Date from / Date to (date inputs)
- Account select (all accounts, optional filter)
- Category select (all categories, optional filter)
- Default: current month, all accounts, all categories

**Results:** `TransactionsList` with `onCategorize` wired. `TransactionRow` shows account name as a sub-text line (absent in per-account views, useful here to identify origin).

**Navigation:**
- New "Transactions" tab in budget nav (`budget-nav.ts`), icon `Receipt`, route `/budget/transactions`
- `SpendingPage` gets a "View all transactions" link that navigates to `/budget/transactions` pre-filled with the current period

## Consumers After Refactor

| Page | categories | onCategorize | account shown |
|------|-----------|--------------|---------------|
| `AccountDetailPage` | ✓ (via `useCategories`) | ✓ (via `useCategorize`) | no |
| `CategoryDetailPage` | ✓ (fix) | ✓ (fix) | no |
| `BudgetTransactionsPage` | ✓ | ✓ | yes |
