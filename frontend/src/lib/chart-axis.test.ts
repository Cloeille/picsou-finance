import { describe, it, expect } from 'vitest'
import { yAxisWidth } from './chart-axis'

const thousandsTwoDecimals = (v: number) => `${(v / 1_000).toFixed(2)}k`
const signedThousands = (v: number) => {
  const abs = Math.abs(v)
  return `${v < 0 ? '-' : ''}${(abs / 1000).toFixed(0)}k`
}

describe('yAxisWidth', () => {
  it('fits the nice tick above the data, not just the data', () => {
    // An investment account peaking at 9 600 € gets a "10.00k" top tick.
    const width = yAxisWidth([2_000, 9_600], thousandsTwoDecimals)
    // Longest tick + the 8px tickMargin + Recharts' default 6px tickSize, which offsets the text
    // even with `tickLine={false}`.
    expect(width).toBe(Math.ceil('10.00k'.length * 7.5) + 8 + 6)
  })

  it('fits a negative tick with its sign', () => {
    const width = yAxisWidth([-180_000, 40_000], signedThousands)
    expect(width).toBeGreaterThanOrEqual(Math.ceil('-200k'.length * 7.5) + 8 + 6)
  })

  it('sizes a masked axis for the mask', () => {
    expect(yAxisWidth([9_600], () => '***')).toBeLessThan(yAxisWidth([9_600], thousandsTwoDecimals))
  })

  it('stays usable with no data', () => {
    expect(yAxisWidth([], (v) => v.toFixed(0))).toBeGreaterThan(8)
  })
})
