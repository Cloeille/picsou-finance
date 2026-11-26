import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AllocationTargetsModal } from './AllocationTargetsModal'
import { makeMoneyFormatter } from '@/lib/money'
import { useAppStore } from '@/stores/app-store'
import type { AllocationTargets, RecurringSeries } from '@/types/api'

const useEssentialExpenseEstimate = vi.fn()
const useSaveAllocationTargets = vi.fn()
const useRecurring = vi.fn()

vi.mock('@/features/analysis/hooks', () => ({
  useEssentialExpenseEstimate: (enabled: boolean) => useEssentialExpenseEstimate(enabled),
  useSaveAllocationTargets: () => useSaveAllocationTargets(),
}))

vi.mock('@/features/budget/hooks', () => ({
  useRecurring: () => useRecurring(),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const baseTargets: AllocationTargets = {
  monthlyEssentialExpenses: null,
  safetyNetMonths: 6,
  realEstatePct: 30,
  equityPct: 50,
  cryptoPct: 10,
  alternativePct: 10,
}

function makeSeries(overrides: Partial<RecurringSeries>): RecurringSeries {
  return {
    id: 1,
    label: 'Loyer',
    counterparty: null,
    expectedAmount: -1000,
    cadence: 'MONTHLY',
    status: 'CONFIRMED',
    nextDueDate: '2026-12-01',
    lastSeenDate: '2026-11-01',
    categoryId: null,
    categoryName: null,
    categoryColor: null,
    categoryIcon: null,
    confidence: 0.9,
    amountMin: -1000,
    amountMax: -1000,
    variable: false,
    previousAmount: null,
    priceChangedAt: null,
    autoConfirmed: false,
    runtimeStatus: 'SCHEDULED',
    ...overrides,
  } as RecurringSeries
}

describe('AllocationTargetsModal', () => {
  const mutate = vi.fn()

  beforeEach(() => {
    mutate.mockReset()
    useSaveAllocationTargets.mockReturnValue({ mutate, isPending: false })
    useEssentialExpenseEstimate.mockReturnValue({ data: undefined })
    useRecurring.mockReturnValue({ data: undefined, isLoading: false })
    useAppStore.setState({ hideAmounts: false })
  })

  afterEach(() => {
    useAppStore.setState({ hideAmounts: false })
  })

  it('prefills empty expenses from active recurring series rounded to the euro without saving', () => {
    useRecurring.mockReturnValue({
      data: [
        makeSeries({ id: 1, expectedAmount: -1100, cadence: 'MONTHLY' }),
        makeSeries({ id: 2, expectedAmount: -120, cadence: 'YEARLY' }), // 10 / month
        makeSeries({ id: 3, expectedAmount: -40.4, cadence: 'MONTHLY' }),
        // Excluded by recurringSummary: suggested, ignored, stale, and income.
        makeSeries({ id: 4, expectedAmount: -200, status: 'SUGGESTED' }),
        makeSeries({ id: 5, expectedAmount: -200, status: 'IGNORED' }),
        makeSeries({ id: 6, expectedAmount: -200, runtimeStatus: 'STALE' }),
        makeSeries({ id: 7, expectedAmount: 3200, cadence: 'MONTHLY' }),
      ],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('1150')
    expect(screen.getByText('analysis.targets.recurringPrefillHint')).toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringSuggestion')).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({ monthlyEssentialExpenses: 1150 }),
      expect.any(Object),
    )
  })

  it('never overwrites a saved value and offers a Use link when recurring expenses differ', () => {
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234.4, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1850 }}
      />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('1850')
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.getByText('analysis.targets.recurringSuggestion')).toBeInTheDocument()
    const formatted = makeMoneyFormatter({ hidden: false, locale: 'fr-FR' }).amount(1234, 'EUR', {
      maximumFractionDigits: 0,
    })
    expect(screen.getByText(formatted, { normalizer: (s) => s })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'analysis.targets.useSuggestion' }))
    expect(input.value).toBe('1234')
    expect(mutate).not.toHaveBeenCalled()
    expect(screen.queryByText('analysis.targets.recurringSuggestion')).not.toBeInTheDocument()
  })

  it('hides the recurring suggestion when the saved value already matches the rounded total', () => {
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234.2, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1234 }}
      />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('1234')
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringSuggestion')).not.toBeInTheDocument()
  })

  it('leaves the field unchanged when there are no active recurring expenses', () => {
    useRecurring.mockReturnValue({
      data: [
        makeSeries({ id: 1, expectedAmount: -50, status: 'SUGGESTED' }),
        makeSeries({ id: 2, expectedAmount: -80, runtimeStatus: 'STALE' }),
        makeSeries({ id: 3, expectedAmount: 3000, status: 'CONFIRMED' }),
      ],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('')
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringSuggestion')).not.toBeInTheDocument()
  })

  it('prefills once recurring data finishes loading when the user has not typed yet', () => {
    useRecurring.mockReturnValue({ data: undefined, isLoading: true })

    const { rerender } = render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('')

    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234, cadence: 'MONTHLY' })],
      isLoading: false,
    })
    rerender(<AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />)

    expect(input.value).toBe('1234')
    expect(screen.getByText('analysis.targets.recurringPrefillHint')).toBeInTheDocument()
  })

  it('does not overwrite what the user typed before recurring data arrived', () => {
    useRecurring.mockReturnValue({ data: undefined, isLoading: true })

    const { rerender } = render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    fireEvent.change(input, { target: { value: '900' } })
    expect(input.value).toBe('900')

    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234, cadence: 'MONTHLY' })],
      isLoading: false,
    })
    rerender(<AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />)

    expect(input.value).toBe('900')
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
  })

  it('masks the recurring suggestion amount in privacy mode', () => {
    useAppStore.setState({ hideAmounts: true })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1850 }}
      />,
    )

    const masked = makeMoneyFormatter({ hidden: true, locale: 'fr-FR' }).amount(1234, 'EUR', {
      maximumFractionDigits: 0,
    })
    expect(screen.getByText('analysis.targets.recurringSuggestion')).toBeInTheDocument()
    expect(screen.getByText(masked, { normalizer: (s) => s })).toBeInTheDocument()
    expect(screen.queryByText(/1\s234/)).not.toBeInTheDocument()
  })
})
