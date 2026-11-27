import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { PortfolioLine } from '@/features/accounts/hooks'
import { POSITIVE_AMOUNT_CLASS } from '@/lib/money'

const { priceHistory, priceIntraday } = vi.hoisted(() => ({
  priceHistory: vi.fn(),
  priceIntraday: vi.fn(),
}))

vi.mock('@/features/accounts/api', () => ({
  accountsApi: {
    priceHistory,
    priceIntraday,
    securityInsight: vi.fn().mockResolvedValue(null),
  },
  realEstateApi: {},
}))

vi.mock('recharts', async (importOriginal) => {
  const { getNiceTickValues } = await importOriginal<typeof import('recharts')>()
  const Empty = () => null
  return {
    getNiceTickValues,
    ResponsiveContainer: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
    AreaChart: ({ children }: { children?: ReactNode }) => <div data-testid="area-chart">{children}</div>,
    Area: Empty,
    Line: Empty,
    Legend: Empty,
    CartesianGrid: Empty,
    ReferenceLine: Empty,
    XAxis: Empty,
    YAxis: Empty,
    Tooltip: Empty,
  }
})

vi.mock('@/components/shared/HoldingInsightSection', () => ({
  HoldingInsightSection: () => null,
}))

vi.mock('@/pages/analysis/HoldingClassificationModal', () => ({
  HoldingClassificationModal: () => null,
}))

vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({
    amount: (value: number) => `${value} EUR`,
    tick: (value: number) => String(value),
    quantity: (value: string) => value,
  }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

const { HoldingDetailModal } = await import('./HoldingDetailModal')

function recentDate(daysAgo: number): string {
  const date = new Date(Date.now() - daysAgo * 86_400_000)
  return date.toISOString().slice(0, 10)
}

function makeLine(overrides: Partial<PortfolioLine> = {}): PortfolioLine {
  return {
    id: '1-MULYF',
    name: 'Amundi Euro Stoxx Banks',
    ticker: 'MULYF',
    isin: 'LU1829219390',
    quantity: 80,
    accountId: 1,
    accountName: 'PEA',
    accountType: 'PEA',
    accountColor: '#2563eb',
    valueEur: 352.8,
    costBasisEur: 304.8,
    averageBuyIn: 3.81,
    quoteCurrency: 'EUR',
    pnlEur: 48,
    pnlPercent: 15.75,
    priceUpdatedAt: '2026-01-14T08:00:00Z',
    ...overrides,
  }
}

function renderModal(line: PortfolioLine) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <HoldingDetailModal line={line} onClose={vi.fn()} />
    </QueryClientProvider>,
  )
}

describe('HoldingDetailModal', () => {
  beforeEach(() => {
    priceHistory.mockReset()
    priceIntraday.mockReset()
    priceHistory.mockResolvedValue([
      { date: recentDate(2), priceEur: 4.3 },
      { date: recentDate(1), priceEur: 4.41 },
    ])
    priceIntraday.mockResolvedValue([])
  })

  it('requests price history by ISIN when present, and falls back to ticker otherwise', async () => {
    const first = renderModal(makeLine({ isin: 'LU1829219390', ticker: 'MULYF' }))
    await waitFor(() => expect(priceHistory).toHaveBeenCalledWith('LU1829219390', 12))
    first.unmount()

    priceHistory.mockClear()
    renderModal(makeLine({ id: '1-AAPL', name: 'Apple', isin: null, ticker: 'AAPL' }))
    await waitFor(() => expect(priceHistory).toHaveBeenCalledWith('AAPL', 12))
  })

  it('keeps the time-range selector visible after switching to 24H with an empty series', async () => {
    renderModal(makeLine())

    const range24H = await screen.findByRole('button', { name: 'dashboard.ranges.24H' })
    fireEvent.click(range24H)

    await waitFor(() => expect(priceIntraday).toHaveBeenCalledWith('LU1829219390'))
    expect(await screen.findByText('dashboard.marketsClosed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'dashboard.ranges.24H' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'dashboard.ranges.1Y' })).toBeInTheDocument()
  })

  it('shows the signed colored PnL in position mode without the visible gain/loss label', async () => {
    renderModal(makeLine({ pnlEur: 48 }))

    const positionButton = await screen.findByRole('button', { name: 'holdings.myPosition' })
    fireEvent.click(positionButton)

    expect(screen.queryByText('holdings.pnl')).not.toBeInTheDocument()
    expect(screen.getByText('dashboard.portfolioPerformance')).toHaveClass('sr-only')
    const pnlNode = screen.getByText((_content, element) =>
      element?.tagName === 'SPAN' && element.textContent === '+48 EUR',
    )
    expect(pnlNode).toHaveClass(POSITIVE_AMOUNT_CLASS)
  })
})
