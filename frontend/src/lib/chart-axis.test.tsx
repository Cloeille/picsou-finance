import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import { Line, LineChart, YAxis } from 'recharts'
import { yAxisWidth } from './chart-axis'

const thousandsTwoDecimals = (v: number) => `${(v / 1_000).toFixed(2)}k`
const signedThousands = (v: number) => {
  const abs = Math.abs(v)
  return `${v < 0 ? '-' : ''}${(abs / 1000).toFixed(0)}k`
}
// AccountsStackedChart's PnL formatter: one decimal under 10k, none above.
const pnlTick = (v: number) => {
  const abs = Math.abs(v)
  const formatted = abs >= 1000 ? `${(abs / 1000).toFixed(abs >= 10000 ? 0 : 1)}k` : abs.toFixed(0)
  return `${v < 0 ? '-' : ''}${formatted}`
}

// What Recharts leaves the tick text: the axis width minus `tickMargin` (8) and the default
// `tickSize` (6), which offsets the text even when `tickLine={false}`.
const textRoom = (width: number) => width - 8 - 6
const labelPx = (label: string) => label.length * 7.5

/** The Y tick labels Recharts actually draws for a series, through a default axis. */
function renderedTicks(values: number[], format: (value: number) => string): string[] {
  const { container, unmount } = render(
    <LineChart width={400} height={250} data={values.map((v) => ({ v }))}>
      <YAxis tickLine={false} axisLine={false} tickMargin={8} tickFormatter={format} />
      <Line dataKey="v" isAnimationActive={false} />
    </LineChart>,
  )
  const labels = [...container.querySelectorAll('.recharts-cartesian-axis-tick-value')].map((n) => n.textContent ?? '')
  unmount()
  return labels
}

describe('yAxisWidth', () => {
  it.each([
    ['PnL barely below zero', [-1, 10_000], pnlTick],
    ['an all-negative PnL', [-480, -120], pnlTick],
    ['an account peaking at 9 600', [2_000, 9_600], thousandsTwoDecimals],
    ['a net worth peaking at 999', [0, 999], (v: number) => v.toFixed(0)],
    ['a deep loss', [-180_000, 40_000], signedThousands],
  ])('fits every tick Recharts draws for %s', (_, values, format) => {
    const ticks = renderedTicks(values, format)
    expect(ticks.length).toBeGreaterThan(0)
    const room = textRoom(yAxisWidth(values, format))
    for (const tick of ticks) expect(room, tick).toBeGreaterThanOrEqual(labelPx(tick))
  })

  it('sizes a masked axis for the mask', () => {
    expect(yAxisWidth([9_600], () => '***')).toBeLessThan(yAxisWidth([9_600], thousandsTwoDecimals))
  })

  it('stays usable with no data', () => {
    expect(textRoom(yAxisWidth([], (v) => v.toFixed(0)))).toBeGreaterThanOrEqual(labelPx('0'))
  })
})
