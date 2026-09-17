import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Account, RealEstateMetadata } from '@/types/api'
import type { NetWorthPoint } from '@/features/history/api'

const useAccounts = vi.fn()
const useHistory = vi.fn()

vi.mock('@/features/accounts/hooks', async (importActual) => ({
  // useAccountTree is a pure useMemo over the accounts handed in -- it groups Revolut pockets
  // under their wallet. Stubbing it would stub out the grouping these cases render through, so
  // the real one stays and only the query hooks are replaced.
  ...(await importActual<typeof import('@/features/accounts/hooks')>()),
  useAccounts: () => useAccounts(),
  useAccountDeletionImpact: () => ({ data: undefined }),
  useUpdateAccount: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteAccount: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateDebtMetadata: () => ({ mutate: vi.fn(), isPending: false }),
}))

// The savings-suggestion banner is a query of its own and has nothing to say about gain/loss.
vi.mock('@/features/savings/hooks', () => ({
  useSavingsSuggestions: () => ({ data: undefined }),
}))

vi.mock('@/features/history/hooks', () => ({
  useHistory: () => useHistory(),
}))

// The form and the two add-account modals drag in zod and the whole connector catalog. They
// are never opened here, so stubbing them keeps this suite about the summary card.
vi.mock('@/components/shared/AccountForm', () => ({ AccountForm: () => null }))
vi.mock('@/components/shared/AddAccountModal', () => ({ AddAccountModal: () => null }))
vi.mock('@/components/property/AddPropertyModal', () => ({ AddPropertyModal: () => null }))
vi.mock('@/components/shared/AccountsStackedChart', () => ({
  AccountsStackedChart: () => <div data-testid="pnl-chart" />,
}))

vi.mock('react-router-dom', () => ({ useNavigate: () => vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { AccountsPage } = await import('./AccountsPage')

function property(id: number, balanceEur: number, costBasis?: number): Account {
  return {
    id,
    name: `Bien ${id}`,
    type: 'REAL_ESTATE',
    provider: null,
    currency: 'EUR',
    currentBalance: balanceEur,
    currentBalanceEur: balanceEur,
    lastSyncedAt: null,
    isManual: true,
    color: '#a855f7',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
    ...(costBasis == null
      ? {}
      : { realEstate: { costBasis, purchasePrice: costBasis } as RealEstateMetadata }),
  } as Account
}

/** One history point per account: a property reports invested = its own balance. */
function history(accounts: Account[]): NetWorthPoint[] {
  const entries = Object.fromEntries(
    accounts.map(a => [String(a.id), { total: a.currentBalanceEur, invested: a.currentBalanceEur, pnl: 0 }])
  )
  return [{ date: '2026-08-01', total: 0, invested: 0, pnl: 0, accounts: entries }]
}

/** Click the Immobilier filter chip. */
function selectRealEstate() {
  fireEvent.click(screen.getByRole('button', { name: 'accounts.filters.REAL_ESTATE' }))
}

describe('AccountsPage — Immobilier gain / loss', () => {
  beforeEach(() => {
    useAccounts.mockReset()
    useHistory.mockReset()
  })

  it('shows the gain against the property cost basis, not the snapshot invested amount', () => {
    const accounts = [property(1, 300000, 250000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({ data: history(accounts), isLoading: false })

    render(<AccountsPage />)
    selectRealEstate()

    // 300 000 - 250 000 = +50 000, i.e. +20.0 %
    expect(screen.getByText('dashboard.portfolioPerformance')).toBeInTheDocument()
    expect(screen.getByText('(+20.0%)')).toBeInTheDocument()
  })

  it('renders the PnL chart for the Immobilier filter', () => {
    const accounts = [property(1, 300000, 250000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({ data: history(accounts), isLoading: false })

    render(<AccountsPage />)
    selectRealEstate()

    expect(screen.getByTestId('pnl-chart')).toBeInTheDocument()
  })

  it('stays silent for a property described but never given a purchase price', () => {
    // Its balance must not read as pure gain.
    const accounts = [property(1, 300000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({ data: history(accounts), isLoading: false })

    render(<AccountsPage />)
    selectRealEstate()

    expect(screen.queryByText('dashboard.portfolioPerformance')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pnl-chart')).not.toBeInTheDocument()
  })

  it('does not let a basis-less property inflate the gain of the one beside it', () => {
    const accounts = [property(1, 300000, 250000), property(2, 200000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({ data: history(accounts), isLoading: false })

    render(<AccountsPage />)
    selectRealEstate()

    // Gain stays +50 000 over a 450 000 basis (250 000 + the second property's own value),
    // not +50 000 over 250 000 and certainly not +250 000.
    expect(screen.getByText('(+11.1%)')).toBeInTheDocument()
  })
})
