import '@testing-library/jest-dom'
import { render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { UpcomingDividend } from '@/types/api'
import fr from '@/i18n/locales/fr.json'

const state = vi.hoisted(() => ({
  cashflowTo: '2099-12-31',
  coreError: false,
  coreLoading: false,
  dividends: {
    data: [] as UpcomingDividend[],
    isLoading: false,
    isError: false,
  },
}))

const useUpcomingDividends = vi.hoisted(() => vi.fn())

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }))
vi.mock('./PeriodNavigator', () => ({ PeriodNavigator: () => null }))
vi.mock('./BudgetPeriodContext', () => ({
  useBudgetPeriod: () => ({ anchor: undefined, setAnchor: vi.fn() }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value, showSign }: { value: number; showSign?: boolean }) => (
    <span>{showSign && value > 0 ? `+${value}` : String(value)}</span>
  ),
}))
vi.mock('@/features/budget/hooks', () => ({
  useCashflow: () => ({
    data: state.coreError || state.coreLoading ? undefined : {
      period: 'CYCLE', from: '2026-10-01', to: state.cashflowTo, income: 100, expense: 40, net: 60, series: [],
    },
    isLoading: state.coreLoading,
    isError: state.coreError,
    refetch: vi.fn(),
  }),
  useBudgets: () => ({ data: [], isLoading: state.coreLoading, isError: state.coreError, refetch: vi.fn() }),
  useRecurringCalendar: () => ({ data: [], isLoading: state.coreLoading, isError: state.coreError, refetch: vi.fn() }),
  useAllocation: () => ({
    data: { totalStock: 0, totalContributions: 0 },
    isLoading: state.coreLoading,
    isError: state.coreError,
    refetch: vi.fn(),
  }),
  useUncategorized: () => ({ data: [], isLoading: false, isError: false, refetch: vi.fn() }),
}))
vi.mock('@/features/dividends/hooks', () => ({ useUpcomingDividends }))

const { OverviewTab } = await import('./OverviewTab')

function line(overrides: Partial<UpcomingDividend> & Pick<UpcomingDividend, 'name' | 'amountEur' | 'expectedDate'>): UpcomingDividend {
  return {
    id: overrides.id ?? overrides.name,
    source: 'HOLDING',
    accountId: 3,
    accountName: 'Compte Titres',
    ticker: 'TTE.PA',
    cadence: 'QUARTERLY',
    basis: 'HISTORY_CADENCE',
    quantity: 1,
    amountPerShare: overrides.amountEur,
    amountPerShareEur: overrides.amountEur,
    currency: 'EUR',
    reinvested: false,
    accountType: 'COMPTE_TITRES',
    countsInBudget: true,
    exDate: overrides.expectedDate,
    paymentDate: overrides.expectedDate,
    paymentDateEstimated: false,
    ...overrides,
  }
}

describe('OverviewTab capital income', () => {
  beforeEach(() => {
    state.cashflowTo = '2099-12-31'
    state.coreError = false
    state.coreLoading = false
    state.dividends = { data: [], isLoading: false, isError: false }
    useUpcomingDividends.mockReset().mockImplementation(() => state.dividends)
  })

  it('uses the French wording from the contract', () => {
    expect(fr.budget.overview.capitalIncome).toBe('Revenus du capital à venir')
    expect(fr.budget.overview.capitalIncomeGross).toBe('Montants bruts estimés')
    expect(fr.budget.overview.reinvested).toBe('Réinvesti')
    expect(fr.budget.overview.staysInEnvelope).toBe("Reste dans l'enveloppe")
    expect(fr.budget.overview.capitalizedOutside).toBe('capitalisés, hors budget')
  })

  it('sums only countsInBudget, lists the next 3, and badges wrappers and reinvested lines', () => {
    state.dividends.data = [
      line({ name: 'TotalEnergies', amountEur: 34, expectedDate: '2026-11-01', countsInBudget: true }),
      line({ name: 'Airbus', amountEur: 18, expectedDate: '2026-11-02', accountType: 'PEA', countsInBudget: false }),
      line({
        name: 'Pierre-papier exemple', amountEur: 30, expectedDate: '2026-11-03',
        source: 'SCPI', accountType: 'SCPI', reinvested: true, countsInBudget: false, ticker: null,
      }),
      line({ name: 'Sanofi', amountEur: 6, expectedDate: '2026-11-20', countsInBudget: true }),
      // A wrapper the flag still counts: the card must not override the backend.
      line({ name: 'FlaggedIn', amountEur: 7, expectedDate: '2026-11-21', accountType: 'PER', countsInBudget: true }),
    ]
    render(<OverviewTab />)

    expect(useUpcomingDividends).toHaveBeenCalledWith(60)
    const card = screen.getByRole('region', { name: 'budget.overview.capitalIncome' })
    expect(within(card).getByText('+47')).toBeInTheDocument()
    expect(within(card).queryByText('+95')).not.toBeInTheDocument()
    expect(within(card).getByText('budget.overview.capitalIncomeGross')).toBeInTheDocument()
    const aside = within(card).getByText('budget.overview.capitalizedOutside').closest('p')
    expect(aside).toHaveTextContent('+48')
    expect(within(card).getByText('TotalEnergies')).toBeInTheDocument()
    expect(within(card).getByText('Airbus')).toBeInTheDocument()
    expect(within(card).getByText('Pierre-papier exemple')).toBeInTheDocument()
    expect(within(card).queryByText('Sanofi')).not.toBeInTheDocument()
    expect(within(card).queryByText('FlaggedIn')).not.toBeInTheDocument()
    expect(within(card).getByText('budget.overview.staysInEnvelope')).toBeInTheDocument()
    expect(within(card).getByText('budget.overview.reinvested')).toBeInTheDocument()
    expect(within(card).getAllByText('budget.overview.staysInEnvelope')).toHaveLength(1)
    expect(screen.getByText('budget.overview.netThisCycle')).toBeInTheDocument()
  })

  it('hides the capitalized aside when every line counts in the budget', () => {
    state.dividends.data = [
      line({ name: 'TotalEnergies', amountEur: 34, expectedDate: '2026-11-01' }),
    ]
    render(<OverviewTab />)
    const card = screen.getByRole('region', { name: 'budget.overview.capitalIncome' })
    expect(within(card).getByText('+34')).toBeInTheDocument()
    expect(within(card).queryByText('budget.overview.capitalizedOutside')).not.toBeInTheDocument()
    expect(within(card).queryByText('budget.overview.staysInEnvelope')).not.toBeInTheDocument()
  })

  it('shows a quiet empty state without blocking the recap', () => {
    render(<OverviewTab />)
    expect(screen.getByText('budget.overview.capitalIncomeEmpty')).toBeInTheDocument()
    expect(screen.queryByText('budget.overview.error')).not.toBeInTheDocument()
    expect(screen.getByText('budget.overview.netThisCycle')).toBeInTheDocument()
  })

  it('shows the dividend error string instead of the empty sentence', () => {
    state.dividends = { data: undefined as unknown as UpcomingDividend[], isLoading: false, isError: true }
    render(<OverviewTab />)
    expect(screen.getByText('accounts.dividends.error')).toBeInTheDocument()
    expect(screen.queryByText('budget.overview.capitalIncomeEmpty')).not.toBeInTheDocument()
    expect(screen.queryByText('budget.overview.error')).not.toBeInTheDocument()
    expect(screen.getByText('budget.overview.netThisCycle')).toBeInTheDocument()
  })

  it('does not block the page while the forecast is still loading', () => {
    state.dividends = { data: undefined as unknown as UpcomingDividend[], isLoading: true, isError: false }
    render(<OverviewTab />)
    expect(screen.queryByText('budget.overview.capitalIncomeEmpty')).not.toBeInTheDocument()
    expect(screen.queryByText('budget.overview.error')).not.toBeInTheDocument()
    expect(screen.getByText('budget.overview.netThisCycle')).toBeInTheDocument()
    expect(screen.getByText('budget.overview.capitalIncome')).toBeInTheDocument()
  })

  it('hides the card when viewing a past period', () => {
    state.cashflowTo = '2000-01-31'
    state.dividends.data = [
      line({ name: 'TotalEnergies', amountEur: 34, expectedDate: '2026-11-01' }),
    ]
    render(<OverviewTab />)
    expect(screen.queryByText('budget.overview.capitalIncome')).not.toBeInTheDocument()
    expect(screen.getByText('budget.overview.pastPeriodNote')).toBeInTheDocument()
  })
})
