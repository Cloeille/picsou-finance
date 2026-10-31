import type { LucideIcon } from 'lucide-react'
import {
  CalendarClock,
  Inbox,
  LayoutGrid,
  Receipt,
  Settings2,
  TrendingUp,
  Wallet,
} from 'lucide-react'

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
  icon: LucideIcon
  /** NavLink `end` — only the index route needs exact matching. */
  end?: boolean
  /** Whether this item carries the "to review" count badge. */
  badge?: boolean
}

export const BUDGET_NAV: BudgetNavItem[] = [
  { section: 'overview', to: '', end: true, labelKey: 'budget.tab.overview', icon: LayoutGrid },
  { section: 'spending', to: 'spending', labelKey: 'budget.tab.cashflow', icon: TrendingUp },
  { section: 'subscriptions', to: 'subscriptions', labelKey: 'budget.tab.recurring', icon: CalendarClock },
  { section: 'envelopes', to: 'envelopes', labelKey: 'budget.tab.envelopes', icon: Wallet },
  { section: 'review', to: 'review', labelKey: 'budget.tab.categorize', icon: Inbox, badge: true },
  { section: 'transactions', to: 'transactions', labelKey: 'budget.tab.transactions', icon: Receipt },
  { section: 'settings', to: 'settings', labelKey: 'budget.tab.manage', icon: Settings2 },
]
