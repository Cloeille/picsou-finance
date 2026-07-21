import { describe, it, expect } from 'vitest'
import { totalFromAvg, avgFromTotal } from './cost-basis'

describe('cost-basis derivation', () => {
  it('derives total invested from average buy-in', () => {
    expect(totalFromAvg(30000, 0.5)).toBe(15000)
  })

  it('derives average buy-in from total invested', () => {
    expect(avgFromTotal(15000, 0.5)).toBe(30000)
  })

  it('round-trips', () => {
    const qty = 1.23456789
    const total = 4567.89
    const avg = avgFromTotal(total, qty)!
    expect(totalFromAvg(avg, qty)).toBeCloseTo(total, 6)
  })

  it('returns null when quantity is zero or negative (no division by zero)', () => {
    expect(avgFromTotal(1000, 0)).toBeNull()
    expect(totalFromAvg(1000, 0)).toBeNull()
    expect(avgFromTotal(1000, -1)).toBeNull()
  })

  it('returns null for null or non-finite inputs', () => {
    expect(totalFromAvg(null, 2)).toBeNull()
    expect(avgFromTotal(null, 2)).toBeNull()
    expect(avgFromTotal(Number.NaN, 2)).toBeNull()
  })
})
