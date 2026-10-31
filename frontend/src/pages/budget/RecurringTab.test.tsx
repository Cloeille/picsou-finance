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
vi.mock('@/components/shared/CurrencyDisplay', () => ({ CurrencyDisplay: () => <span>75 EUR</span> }))
vi.mock('./ActivityFeed', () => ({ ActivityFeed: () => null }))
vi.mock('./SubscriptionCard', () => ({ SubscriptionCard: () => null }))

it('exposes the complete AMEX reward label despite visual truncation', () => {
  render(<RecurringTab />)
  const label = screen.getByTitle(/American Express/)
  // Points use the runtime's number grouping, so derive them the same way instead of
  // hard-coding a locale-specific separator (fr: narrow no-break space, en: comma).
  const expected = `American Express · ${(1365).toLocaleString()} Miles gagnés ce cycle`
  expect(label).toHaveAttribute('title', expected)
  expect(label).toHaveAttribute('aria-label', expected)
})
