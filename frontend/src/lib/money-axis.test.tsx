import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, screen } from '@testing-library/react'
import { AXIS_MASK } from '@/lib/money'
import { useAppStore } from '@/stores/app-store'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

/**
 * Recharts does not lay out in jsdom — it measures its container, which reports 0x0 — so the tick
 * labels never reach the DOM and cannot be asserted on directly.
 *
 * The probe stands in for the axis and renders whatever formatter the chart handed it. That makes
 * the assertion below a real one in two ways: it proves the chart masks its scale, and it proves
 * the masking *propagates on toggle*, since the probe only re-renders when the chart passes it a
 * new tickFormatter. If a stale useMemo dependency or a memoised axis broke that chain, this is
 * where it would show.
 */
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>()
  return {
    ...actual,
    YAxis: ({ tickFormatter }: { tickFormatter?: (value: never) => string }) => (
      <div data-testid="y-ticks">{tickFormatter?.(350_000 as never)}</div>
    ),
  }
})

const { BalanceHistoryChart } = await import('@/components/shared/BalanceHistoryChart')

describe('a chart scale', () => {
  beforeEach(() => {
    useAppStore.getState().setHideAmounts(false)
  })

  it('is masked when amounts are hidden, so no magnitude can be read off the grid', () => {
    render(<BalanceHistoryChart data={[
      { date: '2026-01-31', balance: 350_000 },
      { date: '2026-02-28', balance: 360_000 },
    ]} />)

    // 350 000 renders as "350k" — the tick carries no currency symbol, but the order of magnitude
    // is exactly what a curve plus its axis discloses.
    expect(screen.getByTestId('y-ticks')).toHaveTextContent('350k')

    act(() => { useAppStore.getState().setHideAmounts(true) })

    expect(screen.getByTestId('y-ticks')).toHaveTextContent(AXIS_MASK)
    expect(screen.getByTestId('y-ticks').textContent).not.toMatch(/\d/)
  })
})
