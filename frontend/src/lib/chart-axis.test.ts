import { describe, it, expect } from 'vitest'
import { yAxisWidth } from './chart-axis'

const thousandsTwoDecimals = (v: number) => `${(v / 1_000).toFixed(2)}k`
const signedThousands = (v: number) => {
  const abs = Math.abs(v)
  return `${v < 0 ? '-' : ''}${(abs / 1000).toFixed(0)}k`
}

// What Recharts leaves the tick text: the axis width minus `tickMargin` (8) and the default
// `tickSize` (6), which offsets the text even when `tickLine={false}`.
const textRoom = (width: number) => width - 8 - 6
const labelPx = (label: string) => label.length * 7.5

describe('yAxisWidth', () => {
  it('fits the nice tick above the data, not just the data', () => {
    // An investment account peaking at 9 600 € gets a "10.00k" top tick.
    expect(textRoom(yAxisWidth([2_000, 9_600], thousandsTwoDecimals))).toBeGreaterThanOrEqual(labelPx('10.00k'))
  })

  it('leaves room for the tick offset Recharts adds beside the margin', () => {
    // A net worth peaking at 999 € gets a "1000" top tick, which a width without the offset clipped.
    expect(textRoom(yAxisWidth([0, 999], (v) => v.toFixed(0)))).toBeGreaterThanOrEqual(labelPx('1000'))
  })

  it('fits a negative tick with its sign', () => {
    expect(textRoom(yAxisWidth([-180_000, 40_000], signedThousands))).toBeGreaterThanOrEqual(labelPx('-200k'))
  })

  it('sizes a masked axis for the mask', () => {
    expect(yAxisWidth([9_600], () => '***')).toBeLessThan(yAxisWidth([9_600], thousandsTwoDecimals))
  })

  it('stays usable with no data', () => {
    expect(textRoom(yAxisWidth([], (v) => v.toFixed(0)))).toBeGreaterThanOrEqual(labelPx('0'))
  })
})
