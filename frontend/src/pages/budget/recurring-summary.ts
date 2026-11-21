import type { RecurringCadence, RecurringOccurrence, RecurringSeries } from '@/types/api'

const MONTHLY_FACTOR: Record<RecurringCadence, number> = {
  WEEKLY: 52 / 12,
  BIWEEKLY: 26 / 12,
  MONTHLY: 1,
  QUARTERLY: 1 / 3,
  YEARLY: 1 / 12,
}

export interface RecurringSummary {
  hasSummary: boolean
  monthlyExpenses: number
  expenseCount: number
  monthlyIncome: number
  incomeCount: number
  next30Net: number
  next30Count: number
}

/** `yyyy-MM-dd` of a local calendar day, comparable with the API's `LocalDate` strings. */
function localIsoDate(d: Date): string {
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

/**
 * The KPI strip of the recurring page. Monthly totals count only active confirmed series: a
 * `STALE` one has missed two periods or more, and the backend already leaves it out of the
 * upcoming calendar, so it must not inflate the monthly figure either. The 30-day window ends on
 * the local calendar day, the same frame as the calendar's `dueDate`.
 */
export function recurringSummary(
  series: RecurringSeries[],
  calendar: RecurringOccurrence[],
  today: Date,
): RecurringSummary {
  const active = series.filter((s) => s.status === 'CONFIRMED' && s.runtimeStatus !== 'STALE')
  const expenses = active.filter((s) => s.expectedAmount < 0)
  const income = active.filter((s) => s.expectedAmount > 0)
  const monthly = (s: RecurringSeries) => s.expectedAmount * (MONTHLY_FACTOR[s.cadence] ?? 1)

  const cutoff = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 30)
  const cutoffIso = localIsoDate(cutoff)
  const next30 = calendar.filter((item) => item.dueDate <= cutoffIso)

  return {
    hasSummary: active.length > 0 || next30.length > 0,
    monthlyExpenses: expenses.reduce((sum, s) => sum + Math.abs(monthly(s)), 0),
    expenseCount: expenses.length,
    monthlyIncome: income.reduce((sum, s) => sum + monthly(s), 0),
    incomeCount: income.length,
    next30Net: next30.reduce((sum, item) => sum + item.expectedAmount, 0),
    next30Count: next30.length,
  }
}
