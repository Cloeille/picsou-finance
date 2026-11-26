import { describe, expect, it } from 'vitest'
import {
  buildRecurringGoalUrl,
  parseRecurringGoalPrefill,
  shouldSuggestDetectedInflow,
} from './projection-inflow'

describe('projection-inflow helpers', () => {
  it('suggests detected inflow at zero or when significantly higher than configured plans', () => {
    expect(shouldSuggestDetectedInflow(0, 200, 3)).toBe(true)
    expect(shouldSuggestDetectedInflow(0, 20, 3)).toBe(true)
    expect(shouldSuggestDetectedInflow(100, 200, 3)).toBe(true)
    expect(shouldSuggestDetectedInflow(100, 140, 3)).toBe(false)
    expect(shouldSuggestDetectedInflow(200, 220, 3)).toBe(false)
    expect(shouldSuggestDetectedInflow(300, 200, 3)).toBe(false)
    expect(shouldSuggestDetectedInflow(0, null, 3)).toBe(false)
    expect(shouldSuggestDetectedInflow(0, 200, null)).toBe(false)
  })

  it('builds and parses recurring goal prefill URLs cleanly', () => {
    const empty = buildRecurringGoalUrl()
    expect(empty).toBe('/goals?create=recurring')
    expect(parseRecurringGoalPrefill(new URLSearchParams(empty.split('?')[1]))).toEqual({
      open: true,
      monthlyAmount: '',
      accountIds: [],
    })

    const full = buildRecurringGoalUrl({ amount: 200, accountId: 7 })
    expect(full).toBe('/goals?create=recurring&amount=200&accountId=7')
    expect(parseRecurringGoalPrefill(new URLSearchParams(full.split('?')[1]))).toEqual({
      open: true,
      monthlyAmount: '200',
      accountIds: [7],
    })

    expect(parseRecurringGoalPrefill(new URLSearchParams(''))).toEqual({
      open: false,
      monthlyAmount: '',
      accountIds: [],
    })
  })
})
