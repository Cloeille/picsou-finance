import '@testing-library/jest-dom/vitest'
import { Children, cloneElement, isValidElement, type ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { makeMoneyFormatter, NEGATIVE_AMOUNT_CLASS, POSITIVE_AMOUNT_CLASS } from '@/lib/money'
import type { Account } from '@/types/api'

interface TooltipEntry {
  value: number
  dataKey: string
  payload: { date: string }
}

interface TooltipProps {
  active?: boolean
  payload?: TooltipEntry[]
}

let activeTooltipPayload: TooltipEntry[] = []

vi.mock('recharts', async (importOriginal) => {
  const { getNiceTickValues } = await importOriginal<typeof import('recharts')>()
  const LineChart = ({ children }: { children?: ReactNode }) => (
    <div data-testid="line-chart">
      {Children.map(children, child => isValidElement(child) && typeof child.type !== 'string' ? child : null)}
    </div>
  )
  const ResponsiveContainer = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  const Empty = () => null
  return {
    getNiceTickValues,
    ResponsiveContainer,
    LineChart,
    Line: Empty,
    Legend: Empty,
    CartesianGrid: Empty,
    XAxis: Empty,
    YAxis: Empty,
    Tooltip: Empty,
  }
})

vi.mock('@/components/ui/chart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/chart')>()
  return {
    ...actual,
    ChartContainer: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    ChartTooltip: ({ content }: { content?: ReactNode }) => {
      if (!isValidElement<TooltipProps>(content)) return null
      return (
        <div data-testid="active-tooltip">
          {cloneElement(content, { active: true, payload: activeTooltipPayload })}
        </div>
      )
    },
  }
})
vi.mock('@/components/shared/TimeRangeSelector', () => ({ TimeRangeSelector: () => null }))
vi.mock('@/hooks/use-money', () => ({
  useMoney: () => makeMoneyFormatter({ hidden: false, locale: 'fr-FR' }),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { AccountsStackedChart } = await import('./AccountsStackedChart')

function account(id: number, name: string): Account {
  return {
    id,
    name,
    type: 'PEA',
    provider: null,
    currency: 'EUR',
    currentBalance: 1000,
    currentBalanceEur: 1000,
    lastSyncedAt: null,
    isManual: true,
    color: '#2563eb',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
    hidden: false,
    listPosition: null,
    listCollapsed: false,
  }
}

describe('AccountsStackedChart tooltip formatting', () => {
  const formatter = makeMoneyFormatter({ hidden: false, locale: 'fr-FR' })

  it('normalizes -0 to 0 so negative zero never renders with a minus sign', () => {
    // Sanity check: Intl renders -0 with a leading minus unless the caller normalizes it.
    expect(formatter.amount(-0, 'EUR')).not.toBe(formatter.amount(0, 'EUR'))

    const accounts = [account(1, 'PEA'), account(2, 'CTO')]
    activeTooltipPayload = [
      { dataKey: '1', value: -0, payload: { date: '2026-03-01' } },
      { dataKey: '2', value: -0, payload: { date: '2026-03-01' } },
    ]

    render(
      <AccountsStackedChart
        accounts={accounts}
        data={[{ date: '2026-03-01', 1: -0, 2: -0 }]}
      />,
    )

    const tooltip = screen.getByTestId('active-tooltip')
    const amounts = Array.from(tooltip.querySelectorAll('span.font-mono'))
    expect(amounts).toHaveLength(3)
    for (const el of amounts) {
      expect(el.textContent).toBe(formatter.amount(0, 'EUR'))
      expect(el.textContent).not.toMatch(/^-/)
      expect(el).not.toHaveClass(POSITIVE_AMOUNT_CLASS)
      expect(el).not.toHaveClass(NEGATIVE_AMOUNT_CLASS)
    }
  })

  it('colours positive rows green, negative rows red, and zero neutral', () => {
    const accounts = [account(1, 'PEA'), account(2, 'CTO')]
    activeTooltipPayload = [
      { dataKey: '1', value: 120, payload: { date: '2026-03-01' } },
      { dataKey: '2', value: -120, payload: { date: '2026-03-01' } },
    ]

    render(
      <AccountsStackedChart
        accounts={accounts}
        data={[{ date: '2026-03-01', 1: 120, 2: -120 }]}
      />,
    )

    const tooltip = screen.getByTestId('active-tooltip')
    const peaRow = within(tooltip).getByText('PEA').parentElement!
    const ctoRow = within(tooltip).getByText('CTO').parentElement!
    const totalEl = Array.from(tooltip.querySelectorAll('span.font-mono')).at(-1)!

    expect(peaRow.querySelector('span.font-mono')).toHaveClass(POSITIVE_AMOUNT_CLASS)
    expect(peaRow.querySelector('span.font-mono')?.textContent).toBe(`+${formatter.amount(120, 'EUR')}`)
    expect(ctoRow.querySelector('span.font-mono')).toHaveClass(NEGATIVE_AMOUNT_CLASS)
    expect(ctoRow.querySelector('span.font-mono')?.textContent).toBe(formatter.amount(-120, 'EUR'))
    expect(totalEl).not.toHaveClass(POSITIVE_AMOUNT_CLASS)
    expect(totalEl).not.toHaveClass(NEGATIVE_AMOUNT_CLASS)
    expect(totalEl.textContent).toBe(formatter.amount(0, 'EUR'))
  })
})
