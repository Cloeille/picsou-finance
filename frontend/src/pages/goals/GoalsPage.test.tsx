import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import type { GoalProgress } from '@/types/api'

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

const mutation = { mutate: vi.fn(), mutateAsync: vi.fn(), isPending: false }
vi.mock('@/features/goals/hooks', () => ({
  useGoals: () => ({ data: [behindGoal], isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateGoal: () => mutation,
  useUpdateGoal: () => mutation,
  useDeleteGoal: () => mutation,
}))
vi.mock('@/features/accounts/hooks', () => ({ useAccounts: () => ({ data: [] }) }))
vi.mock('@/features/profile/hooks', () => ({ useMemberProfile: () => ({ data: undefined }) }))
vi.mock('./SavingsRateCard', () => ({ SavingsRateCard: () => null }))
vi.mock('./GoalDetailModal', () => ({ GoalDetailModal: () => null }))

const { GoalsPage } = await import('./GoalsPage')

describe('GoalsPage goal cards', () => {
  it('shows the figures of a goal that is behind, without an on-track or behind tag', () => {
    render(<MemoryRouter><GoalsPage /></MemoryRouter>)

    expect(screen.getByText('New car')).toBeInTheDocument()
    expect(screen.queryByText(/goals\.(behind|onTrack|waiting)/)).not.toBeInTheDocument()
  })
})
