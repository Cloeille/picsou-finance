import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PortfolioLine } from '@/features/accounts/hooks'
import { StubImage } from '@/test/stubImage'
const { modalRender } = vi.hoisted(() => ({ modalRender: vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) => {
      if (key === 'accounts.perEuroFundBalance') return 'PER euro fund (last sync)'
      return options ? `${key} ${Object.values(options).join(' ')}` : key
    },
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

vi.mock('@/features/accounts/hooks', () => ({ usePortfolio: vi.fn() }))
vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ quantity: (value: string) => value }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))
vi.mock('@/lib/utils', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/utils')>(),
  formatDate: (value: string) => value,
  formatTimeAgo: (value: string) => value,
}))
vi.mock('@/components/shared/HoldingDetailModal', () => ({
  HoldingDetailModal: ({ line }: { line: PortfolioLine | null }) => {
    modalRender(line)
    return line ? <div data-testid="holding-detail-modal">{line.ticker}</div> : null
  },
}))

const { usePortfolio } = await import('@/features/accounts/hooks')
const { HoldingsCard } = await import('./HoldingsCard')

const perEuroLine: PortfolioLine = {
  id: 'per-euro-fund-7',
  name: 'PER Caisse d’Epargne',
  nameKey: 'accounts.perEuroFundBalance',
  ticker: 'EUR',
  isCash: true,
  quantity: 0,
  accountId: 7,
  accountName: 'PER Caisse d’Epargne',
  accountType: 'PER',
  accountColor: '#000000',
  valueEur: 350,
  costBasisEur: null,
  averageBuyIn: null,
  quoteCurrency: 'EUR',
  pnlEur: null,
  pnlPercent: null,
  priceUpdatedAt: null,
  priceAsOf: null,
  priceStale: false,
}

const normalLine: PortfolioLine = {
  ...perEuroLine,
  id: 'air-liquide',
  name: 'Air Liquide',
  nameKey: undefined,
  ticker: 'AI.PA',
  quantity: 2,
  isCash: false,
  valueEur: 200,
  accountName: 'PEA',
  accountType: 'PEA',
}

const aggregatedCashLine: PortfolioLine = {
  ...perEuroLine,
  id: 'cash-aggregated',
  name: 'Euros',
  nameKey: undefined,
  accountId: null,
  accountName: 'Checking',
  accountType: 'CHECKING',
  valueEur: 1500,
}

const staleNavLine: PortfolioLine = {
  ...normalLine,
  id: 'stale-nav',
  name: 'PER unit-linked fund',
  ticker: 'EURO-FUND',
  accountName: 'PER Caisse d’Epargne',
  accountType: 'PER',
  valueEur: 950,
  priceUpdatedAt: '2026-08-19T11:59:00Z',
  priceAsOf: '2026-08-18',
  priceStale: true,
}

const freshQuoteLine: PortfolioLine = {
  ...staleNavLine,
  id: 'fresh-quote',
  priceAsOf: null,
  priceStale: false,
}

function setPortfolioData(data: PortfolioLine[]) {
  vi.mocked(usePortfolio).mockReturnValue({
    data,
    isLoading: false,
    isError: false,
    error: null,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof usePortfolio>)
}

beforeEach(() => {
  vi.mocked(usePortfolio).mockReset()
  modalRender.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('HoldingsCard', () => {
  it('renders and searches a PER fund by its translated label', () => {
    setPortfolioData([perEuroLine, normalLine])

    render(<HoldingsCard />)

    expect(screen.getByText('PER euro fund (last sync)')).toBeInTheDocument()
    expect(screen.getByText('Air Liquide')).toBeInTheDocument()

    fireEvent.change(screen.getByPlaceholderText('dashboard.searchHoldings'), {
      target: { value: 'last sync' },
    })

    expect(screen.getByText('PER euro fund (last sync)')).toBeInTheDocument()
    expect(screen.queryByText('Air Liquide')).not.toBeInTheDocument()
  })

  it('keeps normal holding labels searchable by their original names', () => {
    setPortfolioData([normalLine])

    render(<HoldingsCard />)

    expect(screen.getByText('Air Liquide')).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('dashboard.searchHoldings'), {
      target: { value: 'air liquide' },
    })

    expect(screen.getByText('Air Liquide')).toBeInTheDocument()
  })

  it('keeps synthetic cash rows out of the detail modal while opening real holdings', () => {
    setPortfolioData([perEuroLine, aggregatedCashLine, normalLine])

    render(<HoldingsCard />)

    fireEvent.click(screen.getByText('PER euro fund (last sync)'))
    fireEvent.click(screen.getByText('Euros'))
    expect(modalRender).not.toHaveBeenCalled()
    expect(screen.queryByTestId('holding-detail-modal')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('Air Liquide'))
    expect(screen.getByTestId('holding-detail-modal')).toHaveTextContent('AI.PA')
  })

  it('shows the stale bank NAV date in amber on the portfolio value', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-19T12:00:00Z'))
    setPortfolioData([staleNavLine])

    render(<HoldingsCard />)

    expect(screen.getByText('950')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'accounts.priceAsOf 2026-08-18' }))
      .toHaveClass('bg-amber-500')
  })

  it('shows a fresh live quote in green', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-19T12:00:00Z'))
    setPortfolioData([freshQuoteLine])

    render(<HoldingsCard />)

    expect(screen.getByRole('img', { name: 'accounts.priceLive' }))
      .toHaveClass('bg-emerald-500')
  })

  it('writes the quantity in the interface language, not the browser one', () => {
    document.documentElement.lang = 'fr'
    setPortfolioData([{ ...normalLine, quantity: 1234.5 }])

    try {
      render(<HoldingsCard />)
      // Testing Library folds the U+00A0 into a plain space, so the exact text is read off the node.
      expect(screen.getByText(/dashboard\.shares/).textContent).toBe('1 234,5 dashboard.shares · PEA')
    } finally {
      document.documentElement.lang = ''
    }
  })

  it('does not mark a cash balance as a fresh live quote', () => {
    setPortfolioData([perEuroLine])

    render(<HoldingsCard />)

    expect(screen.getByText('PER euro fund (last sync)')).toBeInTheDocument()
    expect(screen.getByText('350')).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'accounts.priceLive' })).not.toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'accounts.priceAsOf 2026-08-18' })).not.toBeInTheDocument()
  })
  it("shows the holding's logo in place of the ticker box when one was resolved", async () => {
    vi.stubGlobal('Image', StubImage)
    const logo = '/api/instrument-logos/AI.PA'
    setPortfolioData([{ ...normalLine, logoUrl: logo }])

    const { container } = render(<HoldingsCard />)

    await waitFor(() => {
      expect(container.querySelector('img')).toHaveAttribute('src', logo)
    })
    expect(container.querySelector('img')).toHaveAttribute('alt', 'AI.PA')
    expect(screen.queryByText('AI.P')).not.toBeInTheDocument()
    expect(screen.getByText(/dashboard\.shares/).textContent).toBe('AI.PA · 2 dashboard.shares · PEA')
  })

  it('keeps the ticker in the row when the logo fails to load', async () => {
    vi.stubGlobal('Image', StubImage)
    setPortfolioData([{ ...normalLine, logoUrl: '/api/instrument-logos/broken' }])

    const { container } = render(<HoldingsCard />)

    await waitFor(() => {
      expect(container.querySelector('[data-slot="avatar-fallback"]')).toHaveTextContent('AL')
    })
    expect(container.querySelector('img')).toBeNull()
    expect(screen.getByText(/dashboard\.shares/).textContent).toBe('AI.PA · 2 dashboard.shares · PEA')
  })

  it('uses the dark mark under the dark palette', async () => {
    vi.stubGlobal('Image', StubImage)
    document.documentElement.classList.add('dark')
    setPortfolioData([{ ...normalLine, logoUrl: '/api/instrument-logos/AI.PA', logoUrlDark: '/api/instrument-logos/AI.PA?variant=dark' }])

    try {
      const { container } = render(<HoldingsCard />)
      await waitFor(() => {
        expect(container.querySelector('img')).toHaveAttribute('src', '/api/instrument-logos/AI.PA?variant=dark')
      })
    } finally {
      document.documentElement.classList.remove('dark')
    }
  })

  it('keeps the ticker box on a cash row even if it carries a logo', () => {
    setPortfolioData([{ ...perEuroLine, logoUrl: '/api/instrument-logos/EUR' }])

    const { container } = render(<HoldingsCard />)

    expect(screen.getByText('EUR')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="avatar"]')).toBeNull()
  })

  it('keeps the ticker box for a holding without a logo', () => {
    setPortfolioData([{ ...normalLine, logoUrl: null }])

    const { container } = render(<HoldingsCard />)

    expect(screen.getByText('AI.P')).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })
})
