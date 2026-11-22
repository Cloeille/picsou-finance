/**
 * The Budget module is a nested-route section (design "option C", the 1.1.0
 * redesign): one {@link BudgetLayout} with an `<Outlet/>` and these destinations.
 * `to` is relative to `/budget`; the overview is the index route (`to: ''`).
 *
 * Review is kept reachable here for now but is conceptually a *contextual* surface
 * (a nudge on the overview) rather than a permanent destination — see budget.md.
 */
export type BudgetSection =
  | 'overview'
  | 'spending'
  | 'subscriptions'
  | 'envelopes'
  | 'review'
  | 'transactions'
  | 'settings'

export interface BudgetNavItem {
  section: BudgetSection
  /** Path relative to `/budget`. Empty string = the index (overview) route. */
  to: string
  labelKey: string
  /** NavLink `end` — only the index route needs exact matching. */
  end?: boolean
  /** Whether this item carries the "to review" count badge. */
  badge?: boolean
}

export const BUDGET_NAV: BudgetNavItem[] = [
  { section: 'overview', to: '', end: true, labelKey: 'budget.tab.overview' },
  { section: 'spending', to: 'spending', labelKey: 'budget.tab.cashflow' },
  { section: 'subscriptions', to: 'subscriptions', labelKey: 'budget.tab.recurring' },
  { section: 'envelopes', to: 'envelopes', labelKey: 'budget.tab.envelopes' },
  { section: 'review', to: 'review', labelKey: 'budget.tab.categorize', badge: true },
  { section: 'transactions', to: 'transactions', labelKey: 'budget.tab.transactions' },
  { section: 'settings', to: 'settings', labelKey: 'budget.tab.manage' },
]
