import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach, type MockInstance } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { Account, HoldingResponse } from '@/types/api'

// Regression for #231: the holdings card and the realized P&L section were each rendered
// twice on an investment account -- once inside the overview fragment, once right above it.
// The sections are stubbed to markers so the suite counts them without their own queries.

const account = vi.fn<() => Account>()
const positions = vi.fn<() => unknown[] | undefined>()

const HOLDING: HoldingResponse = { ticker: 'CW8', name: 'MSCI World', quantity: 1 } as HoldingResponse

const idle = { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false }

vi.mock('@/features/accounts/hooks', () => ({
  useAccount: () => ({ data: account(), isLoading: false }),
  useAccountHistory: () => ({ data: [] }),
  useHoldingsWithLivePrices: () => ({ data: [HOLDING] }),
  useAccountPositions: () => ({ data: positions() }),
  useAccountTransactions: () => ({ data: undefined }),
  useAddTransaction: () => idle,
  useDeleteTransaction: () => idle,
  useUpdateTransaction: () => idle,
  useDeleteHolding: () => idle,
  useUpdateHolding: () => idle,
  useImportTRTransactions: () => idle,
}))
vi.mock('@/features/budget/hooks', () => ({
  useCategories: () => ({ data: [] }),
  useCategorize: () => idle,
}))
vi.mock('@/features/history/hooks', () => ({ useHistory: () => ({ data: undefined }) }))
vi.mock('@/features/savings/hooks', () => ({ useSavingsSuggestions: () => ({ data: undefined }) }))
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => ({ invalidateQueries: vi.fn() }) }))

vi.mock('@/components/shared/HoldingsTable', () => ({
  HoldingsTable: () => <div data-testid="holdings-table" />,
}))
vi.mock('@/components/shared/PositionsByProduct', () => ({
  PositionsByProduct: () => <div data-testid="positions-by-product" />,
}))
vi.mock('@/components/shared/RealizedPnlSection', () => ({
  RealizedPnlSection: () => <div data-testid="realized-pnl" />,
}))
vi.mock('@/components/shared/ImportTransactionsModal', () => ({ ImportTransactionsModal: () => null }))
vi.mock('@/components/shared/AddTransactionModal', () => ({ AddTransactionModal: () => null }))
vi.mock('@/components/shared/EditHoldingModal', () => ({ EditHoldingModal: () => null }))
vi.mock('@/components/shared/MonthEndBalanceModal', () => ({ MonthEndBalanceModal: () => null }))
vi.mock('@/components/dividends/AccountDividendsSection', () => ({
  AccountDividendsSection: () => <div data-testid="account-dividends" />,
}))
vi.mock('@/features/savings/SavingsConfigSection', () => ({ SavingsConfigSection: () => null }))

vi.mock('react-router-dom', () => ({
  useParams: () => ({ id: '7' }),
  useNavigate: () => vi.fn(),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { AccountDetailPage } = await import('./AccountDetailPage')

function investmentAccount(overrides: Partial<Account> = {}): Account {
  return {
    id: 7,
    name: 'PER',
    type: 'ASSURANCE_VIE',
    provider: null,
    currency: 'EUR',
    currentBalance: 1000,
    currentBalanceEur: 1000,
    lastSyncedAt: null,
    isManual: true,
    color: '#22c55e',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as Account
}

describe('AccountDetailPage holdings (#231)', () => {
  let consoleError: MockInstance<typeof console.error>
  beforeEach(() => {
    consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    account.mockReturnValue(investmentAccount())
    positions.mockReturnValue(undefined)
  })

  it('renders a single holdings card and a single realized P&L section', () => {
    render(<AccountDetailPage />)

    expect(screen.getAllByTestId('holdings-table')).toHaveLength(1)
    expect(screen.getAllByTestId('realized-pnl')).toHaveLength(1)
  })

  it('gives the holdings and realized P&L siblings distinct keys', () => {
    // Both were keyed on the bare account id; React then warns about a duplicate key and may
    // drop or duplicate one of them.
    render(<AccountDetailPage />)

    const duplicateKey = consoleError.mock.calls.some((args: unknown[]) => String(args[0]).includes('same key'))
    expect(duplicateKey).toBe(false)
  })

  it('renders the by-product view alone when the connector reports positions', () => {
    positions.mockReturnValue([{ product: 'SPOT' }])

    render(<AccountDetailPage />)

    expect(screen.getAllByTestId('positions-by-product')).toHaveLength(1)
    expect(screen.queryByTestId('holdings-table')).not.toBeInTheDocument()
    expect(screen.getAllByTestId('realized-pnl')).toHaveLength(1)
  })

  it('keeps a single holdings card when the savings tabs are shown', () => {
    // A savings suggestion or config switches the overview into tabs; the holdings stay above them.
    account.mockReturnValue(investmentAccount({ type: 'PEA', savingsConfig: {} as Account['savingsConfig'] }))

    render(<AccountDetailPage />)

    expect(screen.getAllByTestId('holdings-table')).toHaveLength(1)
    expect(screen.getAllByTestId('realized-pnl')).toHaveLength(1)
  })

  it('does not mount upcoming dividends on a checking account', () => {
    account.mockReturnValue(investmentAccount({ type: 'CHECKING' }))
    render(<AccountDetailPage />)
    expect(screen.queryByTestId('account-dividends')).not.toBeInTheDocument()
  })

  it('mounts upcoming dividends once on a securities account and a PEA, not on a wrapper', () => {
    account.mockReturnValue(investmentAccount({ type: 'COMPTE_TITRES' }))
    const { rerender } = render(<AccountDetailPage />)
    expect(screen.getAllByTestId('account-dividends')).toHaveLength(1)

    account.mockReturnValue(investmentAccount({ type: 'PEA' }))
    rerender(<AccountDetailPage />)
    expect(screen.getAllByTestId('account-dividends')).toHaveLength(1)

    account.mockReturnValue(investmentAccount({ type: 'ASSURANCE_VIE' }))
    rerender(<AccountDetailPage />)
    expect(screen.queryByTestId('account-dividends')).not.toBeInTheDocument()
  })
})
