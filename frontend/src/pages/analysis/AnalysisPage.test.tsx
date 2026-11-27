import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { AnalysisPage } from './AnalysisPage'
import type { WealthPyramid } from '@/types/api'

const useWealthPyramid = vi.fn()
const useAllocationTargets = vi.fn()

vi.mock('@/features/analysis/hooks', () => ({
  useWealthPyramid: () => useWealthPyramid(),
  useAllocationTargets: () => useAllocationTargets(),
  useDiversification: () => ({ data: undefined }),
  useProjection: () => ({ data: undefined }),
  // Stubbed identity: the chart is mocked out below, so only the hook's presence matters here.
  useProjectionDateLabel: () => (date: string) => date,
  useEssentialExpenseEstimate: () => ({ data: undefined }),
  useSaveAllocationTargets: () => ({ mutate: vi.fn(), isPending: false }),
}))

vi.mock('@/features/budget/hooks', () => ({
  useRecurring: () => ({ data: undefined }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const emptyPyramid: WealthPyramid = {
  totalAssetsEur: 0,
  allocatableEur: 0,
  safetyNet: { valueEur: 0, dailyCashEur: 0, targetEur: null, coverage: null, excessEur: 0, known: false, score: null },
  tiers: [],
  alerts: [],
  score: { global: 0, allocation: 100, misplacedPercent: 0, cryptoPenalty: 0, leverageBonus: 0, cryptoTopTenShare: null, loanToValue: null },
}

describe('AnalysisPage', () => {
  beforeEach(() => {
    useAllocationTargets.mockReturnValue({ data: undefined })
    useWealthPyramid.mockReturnValue({ isLoading: true, isError: false, data: undefined })
  })

  it('shows a skeleton while the pyramid loads', () => {
    const { container } = render(<AnalysisPage />)
    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
  })

  it('offers a retry when the pyramid fails to load', () => {
    useWealthPyramid.mockReturnValue({ isLoading: false, isError: true, data: undefined, refetch: vi.fn() })
    render(<AnalysisPage />)
    expect(screen.getByText('analysis.error')).toBeInTheDocument()
  })

  it('shows an empty state rather than a zero score for a member with no accounts', () => {
    // Scoring an empty portfolio would be arithmetic on nothing, and 0/100 reads as a judgement.
    useWealthPyramid.mockReturnValue({ isLoading: false, isError: false, data: emptyPyramid })
    render(<AnalysisPage />)
    expect(screen.getByText('analysis.empty.title')).toBeInTheDocument()
  })

  it('disables the targets button until the targets have loaded', () => {
    useWealthPyramid.mockReturnValue({ isLoading: false, isError: false, data: emptyPyramid })
    render(<AnalysisPage />)
    expect(screen.getByRole('button', { name: /analysis.editTargets/ })).toBeDisabled()
  })

  it('renders the pyramid once there is wealth to place', () => {
    useAllocationTargets.mockReturnValue({
      data: { monthlyEssentialExpenses: 1850, safetyNetMonths: 6, realEstatePct: 30, equityPct: 50, cryptoPct: 10, alternativePct: 10 },
    })
    useWealthPyramid.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        ...emptyPyramid,
        totalAssetsEur: 106000,
        allocatableEur: 100000,
        safetyNet: { valueEur: 6000, targetEur: 6000, coverage: 1, excessEur: 0, known: true, score: 100 },
        tiers: [{ tier: 'EQUITY', valueEur: 50000, actualPercent: 50, targetPercent: 50, gapPercent: 0, accounts: [] }],
        score: { ...emptyPyramid.score, global: 100 },
      },
    })

    render(<AnalysisPage />)

    expect(screen.getByText('analysis.score.title')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /analysis.editTargets/ })).toBeEnabled()
  })

  it('opens the targets modal and focuses the expenses field when clicking the safety-net CTA', async () => {
    useAllocationTargets.mockReturnValue({
      data: { monthlyEssentialExpenses: null, safetyNetMonths: 6, realEstatePct: 30, equityPct: 50, cryptoPct: 10, alternativePct: 10 },
    })
    useWealthPyramid.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        ...emptyPyramid,
        totalAssetsEur: 56000,
        allocatableEur: 50000,
        safetyNet: { valueEur: 6000, dailyCashEur: 0, targetEur: null, coverage: null, excessEur: 0, known: false, score: null },
        tiers: [{ tier: 'EQUITY', valueEur: 50000, actualPercent: 100, targetPercent: 50, targetEur: 25000, gapPercent: 50, accounts: [] }],
        score: { ...emptyPyramid.score, global: 50 },
      },
    })

    render(<AnalysisPage />)

    expect(screen.queryByRole('link', { name: /analysis\.safetyNet\.configureCta/ })).not.toBeInTheDocument()
    const cta = screen.getByRole('button', { name: /analysis\.safetyNet\.configureCta/ })
    expect(cta).toBeEnabled()

    fireEvent.click(cta)

    expect(await screen.findByText('analysis.targets.title')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByLabelText('analysis.targets.expenses')).toHaveFocus()
    })
  })

  it('disables the safety-net CTA until allocation targets have loaded', () => {
    useWealthPyramid.mockReturnValue({
      isLoading: false,
      isError: false,
      data: {
        ...emptyPyramid,
        totalAssetsEur: 6000,
        allocatableEur: 0,
        safetyNet: { valueEur: 6000, dailyCashEur: 0, targetEur: null, coverage: null, excessEur: 0, known: false, score: null },
        score: { ...emptyPyramid.score, global: null, allocation: null },
      },
    })

    render(<AnalysisPage />)

    expect(screen.getByRole('button', { name: /analysis\.safetyNet\.configureCta/ })).toBeDisabled()
  })
})
