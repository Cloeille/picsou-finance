import '@testing-library/jest-dom'
import { cloneElement, type ReactElement } from 'react'
import { act, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AXIS_MASK } from '@/lib/money'
import { useAppStore } from '@/stores/app-store'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))
vi.mock('./PeriodNavigator', () => ({ PeriodNavigator: () => null }))
vi.mock('./BudgetPeriodContext', () => ({
  useBudgetPeriod: () => ({ anchor: undefined, setAnchor: vi.fn() }),
}))
vi.mock('@/features/budget/hooks', () => ({
  useCashflow: () => ({
    data: {
      period: 'CYCLE', from: '2026-10-01', to: '2026-10-31', income: 3500, expense: 1200, net: 2300,
      series: [{ label: 'Oct', income: 3500, expense: 1200 }],
    },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }),
}))

// Recharts does not lay out in jsdom, so the axis and the tooltip are probes: the axis renders
// the formatter the chart handed it, the tooltip renders its content as if hovering the bar.
vi.mock('recharts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('recharts')>()
  return {
    ...actual,
    YAxis: ({ tickFormatter }: { tickFormatter?: (value: number) => string }) => (
      <div data-testid="y-ticks">{tickFormatter ? tickFormatter(3500) : 3500}</div>
    ),
  }
})
vi.mock('@/components/ui/chart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/chart')>()
  return {
    ...actual,
    ChartTooltip: ({ content }: { content: ReactElement }) => (
      <div data-testid="tooltip">
        {cloneElement(content as ReactElement<Record<string, unknown>>, {
          active: true,
          payload: [{ name: 'income', dataKey: 'income', value: 3500, color: 'red', payload: { income: 3500 } }],
        })}
      </div>
    ),
  }
})

const { CashflowTab } = await import('./CashflowTab')

describe('CashflowTab chart in privacy mode', () => {
  beforeEach(() => {
    useAppStore.getState().setHideAmounts(false)
  })

  it('masks the axis scale and the hovered amount once the toggle is switched on', () => {
    render(<CashflowTab />)

    expect(screen.getByTestId('y-ticks').textContent).toMatch(/\d/)
    expect(screen.getByTestId('tooltip').textContent).toMatch(/\d/)

    act(() => { useAppStore.getState().setHideAmounts(true) })

    expect(screen.getByTestId('y-ticks')).toHaveTextContent(AXIS_MASK)
    expect(screen.getByTestId('y-ticks').textContent).not.toMatch(/\d/)
    expect(screen.getByTestId('tooltip')).toHaveTextContent('budget.cashflow.income')
    expect(screen.getByTestId('tooltip').textContent).not.toMatch(/\d/)
  })
})
