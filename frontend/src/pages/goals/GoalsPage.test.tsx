import '@testing-library/jest-dom/vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { GoalProgress } from '@/types/api'

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  })
})

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

const behindGoal: GoalProgress = {
  id: 1,
  name: 'New car',
  type: 'SAVINGS_TARGET',
  createdAt: '2026-01-01T00:00:00Z',
  historyStartMonth: null,
  accounts: [],
  currentTotal: 1000,
  targetAmount: 10000,
  deadline: '2027-12-31',
  percentComplete: 10,
  monthlyNeeded: 600,
  avgMonthlyContribution: -3,
  surplus: null,
  monthsLeft: 15,
  isOnTrack: false,
  monthlyAmount: null,
  expectedReturn: null,
  startDate: null,
  endDate: null,
  allocations: [],
}

const defaultAccounts = [
  {
    id: 2,
    name: 'PEA Synthetic',
    type: 'PEA',
    color: '#6366f1',
    currentBalanceEur: 5000,
  },
]

const useAccounts = vi.fn<() => { data: typeof defaultAccounts | undefined }>()
const allocationPicker = vi.fn()
const mutation = { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false }

vi.mock('@/features/goals/hooks', () => ({
  useGoals: () => ({ data: [behindGoal], isLoading: false, isError: false, refetch: vi.fn() }),
  useGoal: (id: number) => ({
    data: id === behindGoal.id ? behindGoal : undefined,
    isLoading: false,
  }),
  useCreateGoal: () => mutation,
  useUpdateGoal: () => mutation,
  useDeleteGoal: () => mutation,
}))
vi.mock('@/features/accounts/hooks', () => ({
  useAccounts: () => useAccounts(),
}))
vi.mock('@/features/profile/hooks', () => ({ useMemberProfile: () => ({ data: undefined }) }))
vi.mock('@/features/history/hooks', () => ({ useHistory: () => ({ data: [], isLoading: false }) }))
vi.mock('@/components/shared/NetWorthChart', () => ({ NetWorthChart: () => null }))
vi.mock('./SavingsRateCard', () => ({ SavingsRateCard: () => null }))
vi.mock('./AllocationPicker', () => ({
  AllocationPicker: (props: { accountId: number | null }) => {
    allocationPicker(props)
    return null
  },
}))

const { GoalsPage } = await import('./GoalsPage')

describe('GoalsPage goal cards', () => {
  beforeEach(() => {
    useAccounts.mockReturnValue({ data: defaultAccounts })
    allocationPicker.mockClear()
  })

  it('shows the goal summary without pacing figures or an on-track/behind tag', () => {
    render(<MemoryRouter><GoalsPage /></MemoryRouter>)

    const card = screen.getByRole('button', { name: /New car/ })
    expect(within(card).getByText('New car')).toBeInTheDocument()
    expect(within(card).getByText(/1[,\s\u202f]000/)).toBeInTheDocument()
    expect(within(card).getByRole('progressbar')).toBeInTheDocument()
    expect(within(card).getByText(/10% dashboard\.achieved/)).toBeInTheDocument()
    expect(within(card).getByText(/10[,\s\u202f]000/)).toBeInTheDocument()
    expect(within(card).queryByText(/goals\.(behind|onTrack|waiting)/)).not.toBeInTheDocument()
    expect(within(card).queryByText('goals.monthsLeft')).not.toBeInTheDocument()
    expect(within(card).queryByText('goals.monthlyNeeded')).not.toBeInTheDocument()
    expect(within(card).queryByText('goals.avgContribution')).not.toBeInTheDocument()
  })

  it('shows the pacing figures in the detail dialog opened from the card', () => {
    render(<MemoryRouter><GoalsPage /></MemoryRouter>)

    fireEvent.click(screen.getByRole('button', { name: /New car/ }))

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('goals.monthsLeft')).toBeInTheDocument()
    expect(within(dialog).getByText('15')).toBeInTheDocument()
    expect(within(dialog).getByText('goals.monthlyNeeded')).toBeInTheDocument()
    expect(within(dialog).getByText(/600/)).toBeInTheDocument()
    expect(within(dialog).getByText('goals.avgContribution')).toBeInTheDocument()
    expect(within(dialog).getByText(/-.*3/)).toBeInTheDocument()
  })

  it('opens a prefilled recurring investment creation dialog from query parameters', () => {
    render(
      <MemoryRouter initialEntries={['/goals?create=recurring&amount=200&accountId=2']}>
        <GoalsPage />
      </MemoryRouter>,
    )

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('goals.monthlyAmount')).toHaveValue('200')
    const accountCheckbox = within(dialog).getByRole('checkbox', { name: /PEA Synthetic/ })
    expect(accountCheckbox).toBeChecked()
    expect(within(dialog).getByRole('button', { name: 'common.save' })).toBeEnabled()
    expect(allocationPicker).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: 2 }))
  })

  it('drops an unknown accountId from URL prefill so AllocationPicker stays idle and Save stays disabled', () => {
    render(
      <MemoryRouter initialEntries={['/goals?create=recurring&amount=200&accountId=999']}>
        <GoalsPage />
      </MemoryRouter>,
    )

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('goals.monthlyAmount')).toHaveValue('200')
    expect(within(dialog).getByRole('checkbox', { name: /PEA Synthetic/ })).not.toBeChecked()
    expect(within(dialog).getByRole('button', { name: 'common.save' })).toBeDisabled()
    expect(allocationPicker).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: null }))
  })

  it('ignores invalid query parameters and keeps prefilled accountId empty until accounts load', () => {
    useAccounts.mockReturnValue({ data: undefined })

    render(
      <MemoryRouter initialEntries={['/goals?create=recurring&amount=999999999&accountId=2']}>
        <GoalsPage />
      </MemoryRouter>,
    )

    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByLabelText('goals.monthlyAmount')).toHaveValue('')
    expect(within(dialog).getByRole('button', { name: 'common.save' })).toBeDisabled()
    expect(allocationPicker).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: null }))
  })
})
