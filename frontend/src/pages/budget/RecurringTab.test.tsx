import '@testing-library/jest-dom'
import { it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RecurringTab } from './RecurringTab'

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: () => 'Miles gagnés ce cycle' }) }))
vi.mock('@/features/budget/hooks', () => ({
  useCategories: () => ({ data: [] }),
  useCreateRecurring: () => ({ mutate: vi.fn(), isPending: false }),
  useDetectRecurring: () => ({ mutate: vi.fn(), isPending: false }),
  useRecurring: () => ({ data: [], isLoading: false, isError: false, refetch: vi.fn() }),
  useRecurringCalendar: () => ({
    data: [{ seriesId: 1, dueDate: '2026-10-01', label: 'American Express',
      expectedAmount: 75, categoryColor: null, creditCardPayment: true, rewardPoints: 1365 }],
    isLoading: false, isError: false, refetch: vi.fn(),
  }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({ CurrencyDisplay: () => <span>75 €</span> }))
vi.mock('./ActivityFeed', () => ({ ActivityFeed: () => null }))
vi.mock('./SubscriptionCard', () => ({ SubscriptionCard: () => null }))

it('exposes the complete AMEX reward label despite visual truncation', () => {
  render(<RecurringTab />)
  const label = screen.getByTitle(/American Express/)
  expect(label).toHaveAttribute('title', 'American Express · 1 365 Miles gagnés ce cycle')
  expect(label).toHaveAttribute('aria-label', 'American Express · 1 365 Miles gagnés ce cycle')
})
