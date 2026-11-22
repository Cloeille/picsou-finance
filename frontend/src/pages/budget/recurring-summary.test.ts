import { describe, expect, it } from 'vitest'
import type { RecurringOccurrence, RecurringSeries } from '@/types/api'
import { recurringSummary } from './recurring-summary'

function series(overrides: Partial<RecurringSeries>): RecurringSeries {
  return {
    id: 1, label: 'Netflix', counterparty: null, expectedAmount: -12, cadence: 'MONTHLY',
    status: 'CONFIRMED', nextDueDate: '2026-12-01', lastSeenDate: '2026-11-01', categoryId: null,
    categoryName: null, categoryColor: null, categoryIcon: null, confidence: 0.9, amountMin: -12,
    amountMax: -12, variable: false, previousAmount: null, priceChangedAt: null, autoConfirmed: false,
    runtimeStatus: 'SCHEDULED',
    ...overrides,
  } as RecurringSeries
}

function occurrence(dueDate: string, expectedAmount: number): RecurringOccurrence {
  return { seriesId: 1, label: 'X', expectedAmount, dueDate } as RecurringOccurrence
}

// Local midnight-ish moment, built from parts so the test reads the same in every time zone.
const TODAY = new Date(2026, 10, 20, 0, 30)

describe('recurringSummary', () => {
  it('normalises every cadence to a monthly amount, split into expenses and income', () => {
    const s = recurringSummary([
      series({ id: 1, expectedAmount: -12, cadence: 'MONTHLY' }),
      series({ id: 2, expectedAmount: -120, cadence: 'YEARLY' }),
      series({ id: 3, expectedAmount: -30, cadence: 'QUARTERLY' }),
      series({ id: 4, expectedAmount: 3000, cadence: 'MONTHLY' }),
    ], [], TODAY)
    expect(s.monthlyExpenses).toBeCloseTo(12 + 10 + 10)
    expect(s.expenseCount).toBe(3)
    expect(s.monthlyIncome).toBe(3000)
    expect(s.incomeCount).toBe(1)
  })

  it('leaves suggested, ignored and stale series out of the monthly totals', () => {
    const s = recurringSummary([
      series({ id: 1, expectedAmount: -10 }),
      series({ id: 2, expectedAmount: -50, status: 'SUGGESTED' }),
      series({ id: 3, expectedAmount: -50, status: 'IGNORED' }),
      series({ id: 4, expectedAmount: -50, runtimeStatus: 'STALE' }),
    ], [], TODAY)
    expect(s.monthlyExpenses).toBe(10)
    expect(s.expenseCount).toBe(1)
  })

  it('counts occurrences up to the thirtieth local day, inclusive', () => {
    const s = recurringSummary([], [
      occurrence('2026-11-26', -13),
      occurrence('2026-12-20', -100),
      occurrence('2026-12-21', -999),
    ], TODAY)
    expect(s.next30Count).toBe(2)
    expect(s.next30Net).toBe(-113)
    expect(s.hasSummary).toBe(true)
  })

  it('has nothing to summarise without active series or upcoming occurrences', () => {
    expect(recurringSummary([series({ status: 'SUGGESTED' })], [], TODAY).hasSummary).toBe(false)
  })
})
