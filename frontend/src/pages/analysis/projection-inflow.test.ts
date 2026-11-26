import { describe, expect, it } from 'vitest'
import {
  MAX_PREFILL_MONTHLY_AMOUNT_EUR,
  buildRecurringGoalUrl,
  parseRecurringGoalPrefill,
  shouldSuggestDetectedInflow,
} from './projection-inflow'

describe('projection-inflow helpers', () => {
  it('suggests detected inflow at zero or when significantly higher than configured plans', () => {
    expect(shouldSuggestDetectedInflow(0, 200, 2)).toBe(true)
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

  it('rejects out-of-bounds amounts and unsafe or non-integer account IDs', () => {
    expect(
      buildRecurringGoalUrl({
        amount: MAX_PREFILL_MONTHLY_AMOUNT_EUR,
        accountId: Number.MAX_SAFE_INTEGER,
      }),
    ).toBe(
      `/goals?create=recurring&amount=${MAX_PREFILL_MONTHLY_AMOUNT_EUR}&accountId=${Number.MAX_SAFE_INTEGER}`,
    )

    expect(
      buildRecurringGoalUrl({
        amount: MAX_PREFILL_MONTHLY_AMOUNT_EUR + 1,
        accountId: Number.MAX_SAFE_INTEGER + 1,
      }),
    ).toBe('/goals?create=recurring')

    expect(buildRecurringGoalUrl({ amount: 0.1, accountId: 1e21 })).toBe('/goals?create=recurring')

    for (const query of [
      'create=recurring&amount=0&accountId=0',
      'create=recurring&amount=-50&accountId=-2',
      'create=recurring&amount=0.1&accountId=2.5',
      'create=recurring&amount=Infinity&accountId=1e5',
      `create=recurring&amount=${MAX_PREFILL_MONTHLY_AMOUNT_EUR + 1}&accountId=9007199254740992`,
      'create=recurring&amount=abc&accountId=xyz',
    ]) {
      expect(parseRecurringGoalPrefill(new URLSearchParams(query)), query).toEqual({
        open: true,
        monthlyAmount: '',
        accountIds: [],
      })
    }
  })
})
