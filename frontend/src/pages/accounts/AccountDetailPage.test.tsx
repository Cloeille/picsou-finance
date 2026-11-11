import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Account } from '@/types/api'

const hooks = vi.hoisted(() => ({
  useAccount: vi.fn(),
  useAccountHistory: vi.fn(),
  useHoldingsWithLivePrices: vi.fn(),
  useAccountPositions: vi.fn(),
  useAccountTransactions: vi.fn(),
  useAddTransaction: vi.fn(),
  useDeleteTransaction: vi.fn(),
  useUpdateTransaction: vi.fn(),
  useUpdateHolding: vi.fn(),
  useDeleteHolding: vi.fn(),
  useImportTRTransactions: vi.fn(),
  useHistory: vi.fn(),
  useSavingsSuggestions: vi.fn(),
  useCategories: vi.fn(),
  useCategorize: vi.fn(),
  useLoanSummary: vi.fn(),
  useOwnership: vi.fn(),
  useUpdateOwnership: vi.fn(),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useAccount: hooks.useAccount,
  useAccountHistory: hooks.useAccountHistory,
  useHoldingsWithLivePrices: hooks.useHoldingsWithLivePrices,
  useAccountPositions: hooks.useAccountPositions,
  useAccountTransactions: hooks.useAccountTransactions,
  useAddTransaction: hooks.useAddTransaction,
  useDeleteTransaction: hooks.useDeleteTransaction,
  useUpdateTransaction: hooks.useUpdateTransaction,
  useUpdateHolding: hooks.useUpdateHolding,
  useDeleteHolding: hooks.useDeleteHolding,
  useImportTRTransactions: hooks.useImportTRTransactions,
  useLoanSummary: hooks.useLoanSummary,
  useOwnership: hooks.useOwnership,
  useUpdateOwnership: hooks.useUpdateOwnership,
}))

vi.mock('@/features/history/hooks', () => ({ useHistory: hooks.useHistory }))
vi.mock('@/features/family/hooks', () => ({
  useFamilyMembers: () => ({ data: [] }),
}))
vi.mock('@/features/savings/hooks', () => ({ useSavingsSuggestions: hooks.useSavingsSuggestions }))
vi.mock('@/features/budget/hooks', () => ({
  useCategories: hooks.useCategories,
  useCategorize: hooks.useCategorize,
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: { label?: string }) =>
      opts?.label ? `${opts.label}:` : key === 'accounts.perEuroFundBalance' ? 'Fonds euro' : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

vi.mock('@/components/shared/PageHeader', () => ({
  PageHeader: ({ surtitle, title }: { surtitle?: string; title: string }) => (
    <header><p>{surtitle}</p><h1>{title}</h1></header>
  ),
}))
vi.mock('@/components/shared/AccountTypeBadge', () => ({ AccountTypeBadge: () => null }))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))
vi.mock('@/components/shared/BalanceHistoryChart', () => ({ BalanceHistoryChart: () => null }))
vi.mock('@/components/shared/NetWorthChart', () => ({ NetWorthChart: () => null }))
vi.mock('@/components/shared/HoldingsTable', () => ({ HoldingsTable: () => null }))
vi.mock('@/components/shared/PositionsByProduct', () => ({ PositionsByProduct: () => null }))
vi.mock('@/components/shared/RealizedPnlSection', () => ({ RealizedPnlSection: () => null }))
const transactionsListProps = vi.hoisted(() => ({ last: null as { accountProvider?: string | null } | null }))
vi.mock('@/components/shared/TransactionsList', () => ({
  TransactionsList: (props: { accountProvider?: string | null }) => {
    transactionsListProps.last = props
    return null
  },
}))
vi.mock('@/components/shared/AddTransactionModal', () => ({ AddTransactionModal: () => null }))
vi.mock('@/components/shared/ImportTransactionsModal', () => ({ ImportTransactionsModal: () => null }))
vi.mock('@/components/shared/EditHoldingModal', () => ({ EditHoldingModal: () => null }))
vi.mock('@/components/shared/MonthEndBalanceModal', () => ({ MonthEndBalanceModal: () => null }))

const { AccountDetailPage } = await import('./AccountDetailPage')

const PER_ACCOUNT: Account = {
  id: 42,
  name: 'My PER',
  type: 'PER',
  provider: 'Example insurer',
  currency: 'EUR',
  currentBalance: 9987,
  currentBalanceEur: 9987,
  cashBalance: 1234,
  lastSyncedAt: null,
  isManual: false,
  color: '#000000',
  ticker: null,
  logoUrl: null,
  logoKey: null,
  createdAt: '2026-08-01T00:00:00Z',
  hidden: false,
}

function mutation() {
  return { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false }
}

describe('AccountDetailPage PER balance', () => {
  beforeEach(() => {
    hooks.useAccount.mockReturnValue({ data: PER_ACCOUNT, isLoading: false })
    hooks.useAccountHistory.mockReturnValue({ data: [] })
    hooks.useHoldingsWithLivePrices.mockReturnValue({ data: [] })
    hooks.useAccountPositions.mockReturnValue({ data: [] })
    hooks.useAccountTransactions.mockReturnValue({ data: [] })
    hooks.useAddTransaction.mockReturnValue(mutation())
    hooks.useDeleteTransaction.mockReturnValue(mutation())
    hooks.useUpdateTransaction.mockReturnValue(mutation())
    hooks.useUpdateHolding.mockReturnValue(mutation())
    hooks.useDeleteHolding.mockReturnValue(mutation())
    hooks.useImportTRTransactions.mockReturnValue(mutation())
    hooks.useHistory.mockReturnValue({
      data: [{ date: '2026-08-19', total: 9987, invested: null, pnl: null }],
    })
    hooks.useSavingsSuggestions.mockReturnValue({ data: [] })
    hooks.useCategories.mockReturnValue({ data: [] })
    hooks.useCategorize.mockReturnValue(mutation())
  })

  it('renders the euro-fund balance without implying zero PnL when basis is unknown', () => {
    hooks.useHistory.mockReturnValue({
      data: [{ date: '2026-08-19', total: 9987, invested: 10000, pnl: null }],
    })
    const client = new QueryClient()
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/accounts/42']}>
          <Routes>
            <Route path="/accounts/:id" element={<AccountDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByText('Fonds euro:')).toBeInTheDocument()
    expect(screen.getByText('1234')).toBeInTheDocument()
    expect(screen.queryByText('dashboard.portfolioPerformance')).not.toBeInTheDocument()
    expect(screen.queryByText('0')).not.toBeInTheDocument()
  })

  it('shows the insurer-reported contract figures on a synced assurance-vie', () => {
    hooks.useAccount.mockReturnValue({
      data: {
        ...PER_ACCOUNT,
        type: 'ASSURANCE_VIE',
        provider: 'CORUM',
        cashBalance: null,
        openedAt: '2020-03-12',
        contract: { paidInEur: 10200, netInvestedEur: 9000, scheduledPaymentEur: 150, scheduledPaymentCadence: 'MONTHLY' },
      } satisfies Account,
      isLoading: false,
    })
    const client = new QueryClient()
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/accounts/42']}>
          <Routes>
            <Route path="/accounts/:id" element={<AccountDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByText('accounts.lifeContract.title')).toBeInTheDocument()
    expect(screen.getByText('accounts.lifeContract.taxAdvantageFrom')).toBeInTheDocument()
    expect(screen.getByText('accounts.lifeContract.scheduledPayment')).toBeInTheDocument()
    // The ledger knows its account is CORUM's, so it can label the contract's payments.
    expect(transactionsListProps.last?.accountProvider).toBe('CORUM')
  })

  it('leaves the contract section off an account without contract figures', () => {
    const client = new QueryClient()
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={['/accounts/42']}>
          <Routes>
            <Route path="/accounts/:id" element={<AccountDetailPage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.queryByText('accounts.lifeContract.title')).not.toBeInTheDocument()
  })
})

const LOAN_ACCOUNT: Account = {
  ...PER_ACCOUNT,
  id: 7,
  name: 'Mortgage',
  type: 'LOAN',
  isOwner: true,
}

const LOAN_OWNERSHIP = {
  shares: [
    { memberId: 1, displayName: 'Alice', avatarColor: '#6366f1', sharePercent: 50, isOwner: true },
  ],
  totalAssigned: 50,
  unassigned: 50,
}

function renderLoanPage() {
  const client = new QueryClient()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/accounts/7']}>
        <Routes>
          <Route path="/accounts/:id" element={<AccountDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccountDetailPage loan ownership editor', () => {
  beforeEach(() => {
    hooks.useAccount.mockReturnValue({ data: LOAN_ACCOUNT, isLoading: false })
    hooks.useAccountHistory.mockReturnValue({ data: [] })
    hooks.useHoldingsWithLivePrices.mockReturnValue({ data: [] })
    hooks.useAccountPositions.mockReturnValue({ data: [] })
    hooks.useAccountTransactions.mockReturnValue({ data: [] })
    hooks.useAddTransaction.mockReturnValue(mutation())
    hooks.useDeleteTransaction.mockReturnValue(mutation())
    hooks.useUpdateTransaction.mockReturnValue(mutation())
    hooks.useUpdateHolding.mockReturnValue(mutation())
    hooks.useDeleteHolding.mockReturnValue(mutation())
    hooks.useImportTRTransactions.mockReturnValue(mutation())
    hooks.useHistory.mockReturnValue({ data: [] })
    hooks.useSavingsSuggestions.mockReturnValue({ data: [] })
    hooks.useCategories.mockReturnValue({ data: [] })
    hooks.useCategorize.mockReturnValue(mutation())
    // No debt details: the summary call answers 404 (issue #550).
    hooks.useLoanSummary.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    hooks.useOwnership.mockReturnValue({ data: LOAN_OWNERSHIP, isLoading: false })
    hooks.useUpdateOwnership.mockReturnValue({ mutate: vi.fn(), isPending: false, isError: false })
  })

  it('still renders the editor for an owner when the debt details fail', () => {
    renderLoanPage()

    expect(hooks.useOwnership).toHaveBeenCalledWith(7)
    expect(screen.getByText('property.ownership.title')).toBeInTheDocument()
    expect(screen.getByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('common.save')).toBeInTheDocument()
  })

  it('renders the editor read-only for a co-owner when the debt details fail', () => {
    hooks.useAccount.mockReturnValue({ data: { ...LOAN_ACCOUNT, isOwner: false }, isLoading: false })
    renderLoanPage()

    expect(screen.getByText('property.ownership.title')).toBeInTheDocument()
    expect(screen.queryByText('common.save')).not.toBeInTheDocument()
  })
})
