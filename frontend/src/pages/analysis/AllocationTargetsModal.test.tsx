import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { AllocationTargetsModal } from './AllocationTargetsModal'
import { makeMoneyFormatter } from '@/lib/money'
import { useAppStore } from '@/stores/app-store'
import type { AllocationTargets, EssentialExpenseEstimate, RecurringSeries } from '@/types/api'

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
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
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

function makeEstimate(overrides: Partial<EssentialExpenseEstimate> = {}): EssentialExpenseEstimate {
  return {
    estimate: 1205.54,
    monthsObserved: 6,
    excludedTransferCount: 4,
    ...overrides,
  }
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
    useEssentialExpenseEstimate.mockReturnValue({ data: undefined, isPending: false })
    useRecurring.mockReturnValue({ data: undefined, isLoading: false })
    useAppStore.setState({ hideAmounts: false })
  })

  afterEach(() => {
    useAppStore.setState({ hideAmounts: false })
  })

  it('shows a single suggestion using the observed-months estimate when both sources exist', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -50, cadence: 'MONTHLY' })],
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
    expect(screen.getByText(/analysis\.targets\.suggestion\b/)).toBeInTheDocument()
    expect(screen.getByText(/analysis\.targets\.suggestionBasis:6/)).toBeInTheDocument()
    expect(screen.queryByText(/analysis\.targets\.recurringSuggestion/)).not.toBeInTheDocument()

    const formatted = makeMoneyFormatter({ hidden: false, locale: 'fr-FR' }).amount(1206, 'EUR', {
      maximumFractionDigits: 0,
    })
    expect(screen.getByText(formatted, { normalizer: (s) => s })).toBeInTheDocument()

    const useButtons = screen.getAllByRole('button', { name: 'analysis.targets.useSuggestion' })
    expect(useButtons).toHaveLength(1)

    fireEvent.click(useButtons[0])
    expect(input.value).toBe('1206')
    expect(mutate).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()
  })

  it('falls back to recurring expenses when no estimate exists and a saved value differs', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: null, monthsObserved: 0 }),
      isPending: false,
    })
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
    expect(screen.queryByText(/analysis\.targets\.recurringPrefillHint/)).not.toBeInTheDocument()
    expect(screen.getByText(/analysis\.targets\.recurringSuggestion/)).toBeInTheDocument()
    expect(screen.queryByText(/analysis\.targets\.suggestionBasis/)).not.toBeInTheDocument()

    const formatted = makeMoneyFormatter({ hidden: false, locale: 'fr-FR' }).amount(1234, 'EUR', {
      maximumFractionDigits: 0,
    })
    expect(screen.getByText(formatted, { normalizer: (s) => s })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'analysis.targets.useSuggestion' }))
    expect(input.value).toBe('1234')
    expect(mutate).not.toHaveBeenCalled()
    expect(screen.queryByText(/analysis\.targets\.recurringSuggestion/)).not.toBeInTheDocument()
  })

  it('prefills an empty field from the estimate first when both sources exist without saving', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -50, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('1206')
    expect(screen.getByText('analysis.targets.estimatePrefillHint:6')).toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({ monthlyEssentialExpenses: 1206 }),
      expect.any(Object),
    )
  })

  it('prefills an empty field from active recurring series as fallback when there is no estimate', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: null, monthsObserved: 0 }),
      isPending: false,
    })
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
    expect(screen.queryByText(/analysis\.targets\.estimatePrefillHint/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()
    expect(mutate).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    expect(mutate).toHaveBeenCalledWith(
      expect.objectContaining({ monthlyEssentialExpenses: 1150 }),
      expect.any(Object),
    )
  })

  it('hides the suggestion when the saved value matches to the nearest euro', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -50, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    const { rerender } = render(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1206 }}
      />,
    )

    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()

    // Fractional saved value that still rounds to 1206 also hides the suggestion.
    rerender(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1205.6 }}
      />,
    )
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()

    // Same euro-rounded check for the recurring fallback.
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: null, monthsObserved: 0 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234.2, cadence: 'MONTHLY' })],
      isLoading: false,
    })
    rerender(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1234 }}
      />,
    )
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()
  })

  it('leaves the field unchanged when neither estimate nor active recurring expenses exist', () => {
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: null, monthsObserved: 0 }),
      isPending: false,
    })
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
    expect(screen.queryByText(/analysis\.targets\.estimatePrefillHint/)).not.toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'analysis.targets.useSuggestion' })).not.toBeInTheDocument()
  })

  it('waits for the estimate query before falling back to recurring and prefills once resolved', () => {
    useEssentialExpenseEstimate.mockReturnValue({ data: undefined, isPending: true })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -50, cadence: 'MONTHLY' })],
      isLoading: false,
    })

    const { rerender } = render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    expect(input.value).toBe('')
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()

    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })
    rerender(<AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />)

    expect(input.value).toBe('1206')
    expect(screen.getByText('analysis.targets.estimatePrefillHint:6')).toBeInTheDocument()
  })

  it('does not overwrite what the user typed before estimate or recurring data arrives', () => {
    useEssentialExpenseEstimate.mockReturnValue({ data: undefined, isPending: true })
    useRecurring.mockReturnValue({ data: undefined, isLoading: true })

    const { rerender } = render(
      <AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />,
    )

    const input = screen.getByLabelText('analysis.targets.expenses') as HTMLInputElement
    fireEvent.change(input, { target: { value: '900' } })
    expect(input.value).toBe('900')

    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234, cadence: 'MONTHLY' })],
      isLoading: false,
    })
    rerender(<AllocationTargetsModal open onOpenChange={vi.fn()} targets={baseTargets} />)

    expect(input.value).toBe('900')
    expect(screen.queryByText(/analysis\.targets\.estimatePrefillHint/)).not.toBeInTheDocument()
    expect(screen.queryByText('analysis.targets.recurringPrefillHint')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'analysis.targets.useSuggestion' })).toBeInTheDocument()
  })

  it('masks the suggestion amount in privacy mode', () => {
    useAppStore.setState({ hideAmounts: true })
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: 1205.54, monthsObserved: 6 }),
      isPending: false,
    })

    const { rerender } = render(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1850 }}
      />,
    )

    const masked = makeMoneyFormatter({ hidden: true, locale: 'fr-FR' }).amount(1206, 'EUR', {
      maximumFractionDigits: 0,
    })
    expect(screen.getByText(masked, { normalizer: (s) => s })).toBeInTheDocument()
    expect(screen.queryByText(/1\s206/)).not.toBeInTheDocument()

    // Also masked when falling back to recurring.
    useEssentialExpenseEstimate.mockReturnValue({
      data: makeEstimate({ estimate: null, monthsObserved: 0 }),
      isPending: false,
    })
    useRecurring.mockReturnValue({
      data: [makeSeries({ id: 1, expectedAmount: -1234, cadence: 'MONTHLY' })],
      isLoading: false,
    })
    rerender(
      <AllocationTargetsModal
        open
        onOpenChange={vi.fn()}
        targets={{ ...baseTargets, monthlyEssentialExpenses: 1850 }}
      />,
    )

    expect(screen.getByText(/analysis\.targets\.recurringSuggestion/)).toBeInTheDocument()
    expect(screen.getByText(masked, { normalizer: (s) => s })).toBeInTheDocument()
    expect(screen.queryByText(/1\s234/)).not.toBeInTheDocument()
  })
})
