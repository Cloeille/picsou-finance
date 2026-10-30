import type {
  AssetClass,
  CategoryKind,
  RecurringActivityType,
  RecurringCadence,
  RecurringRuntimeStatus,
  RecurringStatus,
} from '@/types/api'

/**
 * Static lookup tables for the Budget module — kept in a component-free file so
 * Vite Fast Refresh (react-refresh/only-export-components) stays happy. The
 * components that consume them live in `budget-utils.tsx`.
 */

/** Fallback swatch colour when a category has no colour set. */
export const FALLBACK_COLOR = '#6366f1'

/** i18n key + semantic tone for each category kind. */
export const KIND_META: Record<CategoryKind, { labelKey: string; tone: string }> = {
  INCOME: { labelKey: 'budget.kind.income', tone: 'text-emerald-600 dark:text-emerald-400' },
  EXPENSE: { labelKey: 'budget.kind.expense', tone: 'text-foreground' },
  TRANSFER: { labelKey: 'budget.kind.transfer', tone: 'text-sky-600 dark:text-sky-400' },
}

export const CADENCE_LABEL_KEY: Record<RecurringCadence, string> = {
  WEEKLY: 'budget.cadence.weekly',
  BIWEEKLY: 'budget.cadence.biweekly',
  MONTHLY: 'budget.cadence.monthly',
  QUARTERLY: 'budget.cadence.quarterly',
  YEARLY: 'budget.cadence.yearly',
}

export const STATUS_LABEL_KEY: Record<RecurringStatus, string> = {
  SUGGESTED: 'budget.recurring.status.suggested',
  CONFIRMED: 'budget.recurring.status.confirmed',
  IGNORED: 'budget.recurring.status.ignored',
}

/**
 * Runtime urgency badge presentation. SCHEDULED is the quiet default and renders no badge, so it
 * carries no label key — only STALE, LATE and DUE_SOON surface a chip on the subscription card.
 */
export const RUNTIME_STATUS_META: Record<
  Exclude<RecurringRuntimeStatus, 'SCHEDULED'>,
  { labelKey: string; className: string }
> = {
  STALE: {
    labelKey: 'budget.recurring.runtime.stale',
    className: 'border-transparent bg-slate-500/15 text-slate-500 dark:text-slate-400',
  },
  LATE: {
    labelKey: 'budget.recurring.runtime.late',
    className: 'border-transparent bg-rose-500/15 text-rose-600 dark:text-rose-400',
  },
  DUE_SOON: {
    labelKey: 'budget.recurring.runtime.dueSoon',
    className: 'border-transparent bg-amber-500/15 text-amber-600 dark:text-amber-400',
  },
}

export const ACTIVITY_TYPE_LABEL_KEY: Record<RecurringActivityType, string> = {
  AUTO_CONFIRMED: 'budget.recurring.activity.autoConfirmed',
  PRICE_CHANGE: 'budget.recurring.activity.priceChange',
}

export const ASSET_CLASS_LABEL_KEY: Record<AssetClass, string> = {
  CURRENT: 'budget.asset.current',
  SAVINGS: 'budget.asset.savings',
  INVESTMENT: 'budget.asset.investment',
  OTHER: 'budget.asset.other',
}

/** Stable palette for asset-class slices (used by the allocation donut). */
export const ASSET_CLASS_COLOR: Record<AssetClass, string> = {
  CURRENT: '#0ea5e9',
  SAVINGS: '#22c55e',
  INVESTMENT: '#8b5cf6',
  OTHER: '#94a3b8',
}
