import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CategorizeTab } from './CategorizeTab'
import type { AiJobStatus } from '@/types/api'

// Mocks that tests can inspect — hoisted so they're available inside vi.mock factories.
const mocks = vi.hoisted(() => ({
  startAiMutate: vi.fn(),
  invalidateQueries: vi.fn(),
}))

// Mutable state the mocked hooks read, so each test can vary settings/inbox/ai status.
const state = vi.hoisted(() => ({
  settings: { aiCategorizationEnabled: false } as { aiCategorizationEnabled: boolean },
  txs: [] as unknown[],
  categories: [] as unknown[],
  aiData: null as AiJobStatus | null,
}))

vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: mocks.invalidateQueries }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

// CurrencyDisplay reads the app store for locale/currency — irrelevant here; render it plainly.
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

vi.mock('@/features/budget/hooks', () => ({
  useUncategorized: () => ({ data: state.txs, isLoading: false, isError: false, refetch: vi.fn() }),
  useCategories: () => ({ data: state.categories }),
  useBudgetSettings: () => ({ data: state.settings }),
  useRecategorize: () => ({ mutate: vi.fn(), isPending: false }),
  useCategorizeAiStatus: () => ({ data: state.aiData }),
  useStartCategorizeAi: () => ({ mutate: mocks.startAiMutate, isPending: false }),
  useCategorize: () => ({ mutate: vi.fn(), isPending: false }),
  useMerchantLogoUrl: () => () => null,
}))

const TRANSPORT = {
  id: 4, name: 'Transport', kind: 'EXPENSE', color: null, icon: null,
  isDefault: true, archived: false, sortOrder: 3, parentId: null,
}

function tx(overrides: Record<string, unknown> = {}) {
  return {
    id: 9002, date: '2026-06-20', description: 'SNCF VOYAGEURS', amount: -68, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: '2026-06-20', isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'SNCF VOYAGEURS',
    merchantLabel: 'SNCF', merchantBrandId: null,
    aiSuggestedCategoryId: null, aiConfidence: null,
    ...overrides,
  }
}

function aiJob(overrides: Partial<AiJobStatus> = {}): AiJobStatus {
  return {
    running: false, processed: 0, total: 0,
    applied: 0, suggested: 0, done: false, error: null,
    ...overrides,
  }
}

describe('CategorizeTab — AI suggestions', () => {
  beforeEach(() => {
    state.settings = { aiCategorizationEnabled: false }
    state.txs = []
    state.categories = [TRANSPORT]
    state.aiData = null
    mocks.startAiMutate.mockReset()
    mocks.invalidateQueries.mockReset()
  })

  it('preselects the AI-suggested category and shows the suggestion chip', () => {
    state.txs = [tx({ aiSuggestedCategoryId: 4, aiConfidence: 92 })]
    render(<CategorizeTab />)

    // The category <select> is preselected to the suggested category id.
    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('4')
    // The suggestion chip is rendered.
    expect(screen.getByText('budget.categorize.aiSuggested')).toBeInTheDocument()
  })

  it('does not preselect or show a chip when there is no suggestion', () => {
    state.txs = [tx()]
    render(<CategorizeTab />)

    expect((screen.getByRole('combobox') as HTMLSelectElement).value).toBe('')
    expect(screen.queryByText('budget.categorize.aiSuggested')).not.toBeInTheDocument()
  })

  it('shows the "Categorize with AI" button only when AI categorization is enabled', () => {
    state.txs = [tx()]

    const { rerender } = render(<CategorizeTab />)
    expect(screen.queryByText('budget.categorize.categorizeAi')).not.toBeInTheDocument()

    state.settings = { aiCategorizationEnabled: true }
    rerender(<CategorizeTab />)
    expect(screen.getByText('budget.categorize.categorizeAi')).toBeInTheDocument()
  })
})

describe('CategorizeTab — AI progress + resume', () => {
  beforeEach(() => {
    state.settings = { aiCategorizationEnabled: true }
    state.txs = []
    state.categories = []
    state.aiData = null
    mocks.startAiMutate.mockReset()
    mocks.invalidateQueries.mockReset()
  })

  it('shows the progress label and disables the button while the job is running', () => {
    state.aiData = aiJob({ running: true, processed: 3, total: 10 })
    render(<CategorizeTab />)

    // The button text comes from the aiProgress i18n key (t() returns the key in tests).
    const btn = screen.getByRole('button', { name: /budget\.categorize\.aiProgress/i })
    expect(btn).toBeDisabled()
  })

  it('calls startAi.mutate when the button is clicked (not running)', () => {
    state.aiData = aiJob({ running: false })
    render(<CategorizeTab />)

    fireEvent.click(screen.getByText('budget.categorize.categorizeAi'))
    expect(mocks.startAiMutate).toHaveBeenCalledTimes(1)
  })

  it('invalidates budget + dashboard queries when the job transitions from running to done', async () => {
    state.aiData = aiJob({ running: true, processed: 5, total: 5 })
    const { rerender } = render(<CategorizeTab />)

    // Simulate job completion.
    state.aiData = aiJob({ running: false, processed: 5, total: 5, applied: 3, suggested: 2, done: true })
    rerender(<CategorizeTab />)

    await waitFor(() => {
      expect(mocks.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['budget'] })
      expect(mocks.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['dashboard'] })
    })
    // The done summary line should also appear.
    expect(screen.getByText('budget.categorize.aiDone')).toBeInTheDocument()
  })
})
