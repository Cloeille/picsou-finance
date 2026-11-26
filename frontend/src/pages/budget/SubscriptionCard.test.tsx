import '@testing-library/jest-dom/vitest'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { RecurringSeries } from '@/types/api'
import { SubscriptionCard } from './SubscriptionCard'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && 'percent' in opts ? `${key}:${String(opts.percent)}` : key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))
vi.mock('@/features/budget/hooks', () => ({
  useConfirmRecurring: () => ({ mutate: vi.fn(), isPending: false }),
  useIgnoreRecurring: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteRecurring: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('@/hooks/use-money', () => ({ useMoney: () => ({ amount: (v: number) => `${v} EUR` }) }))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value} EUR</span>,
}))

function series(overrides: Partial<RecurringSeries>): RecurringSeries {
  return {
    id: 1, label: 'Netflix', counterparty: 'NETFLIX.COM', expectedAmount: -13.49, cadence: 'MONTHLY',
    status: 'CONFIRMED', nextDueDate: '2026-12-01', lastSeenDate: '2026-11-01', categoryId: null,
    categoryName: null, categoryColor: null, categoryIcon: null, confidence: 0.86, amountMin: -13.49,
    amountMax: -13.49, variable: false, previousAmount: null, priceChangedAt: null, autoConfirmed: false,
    runtimeStatus: 'SCHEDULED',
    ...overrides,
  } as RecurringSeries
}

describe('SubscriptionCard', () => {
  it('says a series was confirmed automatically, with the detector confidence', () => {
    render(<SubscriptionCard series={series({ autoConfirmed: true })} />)
    expect(screen.getByText(/^budget\.recurring\.autoConfirmedConfident:/)).toBeInTheDocument()
  })

  it('stays silent about auto-confirmation for a series the member confirmed', () => {
    render(<SubscriptionCard series={series({ autoConfirmed: false })} />)
    expect(screen.queryByText(/autoConfirmed/)).not.toBeInTheDocument()
  })

  it('uses a neutral gray badge for DUE_SOON instead of amber', () => {
    render(<SubscriptionCard series={series({ runtimeStatus: 'DUE_SOON' })} />)
    const badge = screen.getByText('budget.recurring.runtime.dueSoon')
    expect(badge.className).toContain('bg-secondary')
    expect(badge.className).not.toContain('amber')
  })
})
