import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Account, PeerLoansOverview, RealEstateMetadata } from '@/types/api'
import type { NetWorthPoint } from '@/features/history/api'

const useAccounts = vi.fn()
const useHistory = vi.fn()
const usePeerLoansOverview = vi.fn<() => { data: PeerLoansOverview | undefined }>(() => ({ data: undefined }))

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

// The closed-loan filter is a query of its own; it has nothing to say about gain/loss.
vi.mock('@/features/peer-loans/hooks', () => ({
  usePeerLoansOverview: () => usePeerLoansOverview(),
}))

// The form and the two add-account modals drag in zod and the whole connector catalog. They
// are never opened here, so stubbing them keeps this suite about the summary card.
vi.mock('@/components/shared/AccountForm', () => ({ AccountForm: () => null }))
vi.mock('@/components/shared/AddAccountModal', () => ({ AddAccountModal: () => null }))
vi.mock('@/components/property/AddPropertyModal', () => ({ AddPropertyModal: () => null }))
vi.mock('@/components/shared/AccountsStackedChart', () => ({
  AccountsStackedChart: ({ data }: { data: { date: string; [key: string]: string | number }[] }) => (
    <div data-testid="pnl-chart" data-chart={JSON.stringify(data)} />
  ),
}))

vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

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

function investmentAccount(id: number, type: 'PEA' | 'PER', balanceEur: number): Account {
  return {
    id,
    name: `Compte ${id}`,
    type,
    provider: 'Example',
    currency: 'EUR',
    currentBalance: balanceEur,
    currentBalanceEur: balanceEur,
    lastSyncedAt: null,
    isManual: true,
    color: '#6366f1',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
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

describe('AccountsPage - Immobilier gain / loss', () => {
  beforeEach(() => {
    useAccounts.mockReset()
    useHistory.mockReset()
    usePeerLoansOverview.mockReturnValue({ data: undefined })
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

  it('does not publish a partial group PnL when an investment account has unknown basis', () => {
    const accounts = [investmentAccount(1, 'PEA', 12000), investmentAccount(2, 'PER', 9987)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({
      data: [{
        date: '2026-08-01',
        total: 21987,
        invested: null,
        pnl: null,
        accounts: {
          '1': { total: 12000, invested: 10000, pnl: 2000 },
          '2': { total: 9987, invested: null, pnl: null },
        },
      }],
      isLoading: false,
    })

    render(<AccountsPage />)

    expect(screen.queryByText('dashboard.portfolioPerformance')).not.toBeInTheDocument()
    const chartData = JSON.parse(screen.getByTestId('pnl-chart').getAttribute('data-chart') ?? '[]') as Array<Record<string, string | number>>
    expect(chartData[0]).not.toHaveProperty('STOCKS')
  })

  it('leaves a missing historical account observation out of its PnL chart', () => {
    const accounts = [investmentAccount(1, 'PEA', 10000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({
      data: [{
        date: '2026-08-01',
        total: 0,
        invested: null,
        pnl: null,
        accounts: {},
      }],
      isLoading: false,
    })

    render(<AccountsPage />)
    fireEvent.click(screen.getByRole('button', { name: 'accounts.filters.STOCKS' }))

    const chartData = JSON.parse(screen.getByTestId('pnl-chart').getAttribute('data-chart') ?? '[]') as Array<Record<string, string | number>>
    expect(chartData[0]).not.toHaveProperty('1')
  })
})

describe('AccountsPage - card actions on touch screens', () => {
  beforeEach(() => {
    useAccounts.mockReset()
    useHistory.mockReset()
    usePeerLoansOverview.mockReturnValue({ data: undefined })
  })

  it('keeps the edit and delete buttons visible without hover', () => {
    // A touch screen has no hover: the buttons must not depend on it alone.
    const accounts = [investmentAccount(1, 'PEA', 1000)]
    useAccounts.mockReturnValue({ data: accounts, isLoading: false })
    useHistory.mockReturnValue({ data: history(accounts), isLoading: false })

    render(<AccountsPage />)

    for (const name of ['common.edit', 'common.delete']) {
      const actions = screen.getByRole('button', { name }).parentElement
      expect(actions).toHaveClass('pointer-coarse:opacity-100', 'group-focus-within:opacity-100')
    }
  })
})

describe('AccountsPage - closed peer loans', () => {
  beforeEach(() => {
    useAccounts.mockReset()
    useHistory.mockReset()
    usePeerLoansOverview.mockReturnValue({ data: undefined })
  })

  it('hides a closed loan by default and reveals it with the Receivables toggle', () => {
    const closedLoan: Account = {
      id: 99,
      name: 'Sfd',
      type: 'PEER_LOAN',
      provider: null,
      currency: 'EUR',
      currentBalance: 0,
      currentBalanceEur: 0,
      lastSyncedAt: null,
      isManual: true,
      color: '#0d9488',
      ticker: null,
      logoUrl: null,
      logoKey: null,
      createdAt: '2026-01-01T00:00:00Z',
      hidden: false,
    }
    useAccounts.mockReturnValue({ data: [closedLoan], isLoading: false })
    useHistory.mockReturnValue({ data: history([closedLoan]), isLoading: false })
    usePeerLoansOverview.mockReturnValue({
      data: {
        totalOutstanding: 0,
        openCount: 0,
        closedCount: 1,
        interestAccrued: 0,
        loans: [{
          accountId: 99,
          name: 'Sfd',
          principal: 300,
          principalRemaining: 0,
          repaidPct: 100,
          interestAccrued: 0,
          interestReceived: 0,
          closedAt: '2026-11-05',
        }],
        history: [],
      },
    })

    render(<AccountsPage />)

    // Not an active account: absent from the default view.
    expect(screen.queryByText('Sfd')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'accounts.filters.RECEIVABLES' }))
    expect(screen.queryByText('Sfd')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('checkbox', { name: 'accounts.showClosedLoans' }))
    expect(screen.getByText('Sfd')).toBeInTheDocument()
    expect(screen.getByText('peerLoan.summary.closed')).toBeInTheDocument()
  })
})
