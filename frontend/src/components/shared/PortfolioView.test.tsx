import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PortfolioLine } from '@/features/accounts/hooks'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    i18n: { resolvedLanguage: 'en' },
    t: (key: string, options?: Record<string, unknown>) => {
      if (key === 'accounts.perEuroFundBalance') return 'PER euro fund (last sync)'
      return options ? `${key} ${Object.values(options).join(' ')}` : key
    },
  }),
}))

vi.mock('@/features/accounts/hooks', () => ({ usePortfolio: vi.fn() }))

vi.mock('@/lib/utils', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/utils')>(),
  formatDate: (value: string) => value,
  formatTimeAgo: (value: string) => value,
}))

const { usePortfolio } = await import('@/features/accounts/hooks')
const { PortfolioView } = await import('./PortfolioView')

const staleLine: PortfolioLine = {
  id: '8-EURO-FUND',
  name: 'PER euro fund',
  ticker: 'EURO-FUND',
  quantity: 10,
  accountId: 8,
  accountName: 'PER Caisse d’Epargne',
  accountType: 'PER',
  accountColor: '#000',
  valueEur: 950,
  costBasisEur: null,
  averageBuyIn: null,
  quoteCurrency: 'EUR',
  pnlEur: null,
  pnlPercent: null,
  priceUpdatedAt: '2026-08-19T11:59:00Z',
  priceAsOf: '2026-08-18',
  priceStale: true,
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
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-08-19T12:00:00Z'))
  vi.mocked(usePortfolio).mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('PortfolioView', () => {
  it('marks a recently synced stale NAV row amber using its NAV date', () => {
    setPortfolioData([staleLine])

    render(<PortfolioView />)

    expect(screen.getByRole('img', { name: 'accounts.priceAsOf 2026-08-18' }))
      .toHaveClass('bg-amber-500')
    expect(screen.queryByRole('img', { name: 'accounts.priceLive' })).not.toBeInTheDocument()
  })

  it('renders the translated PER euro fund label', () => {
    const perEuroLine = {
      ...staleLine,
      id: 'per-euro-fund-7',
      name: 'PER Caisse d’Epargne',
      nameKey: 'accounts.perEuroFundBalance',
      ticker: 'EUR',
      accountId: 7,
      valueEur: 350,
      priceUpdatedAt: null,
      priceAsOf: null,
      priceStale: false,
    }
    setPortfolioData([perEuroLine])

    render(<PortfolioView />)

    expect(screen.getByText('PER euro fund (last sync)')).toBeInTheDocument()
  })
})
