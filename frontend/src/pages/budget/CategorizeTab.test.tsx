import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { CategorizeTab } from './CategorizeTab'
import { useAuthStore } from '@/stores/auth-store'
import type { AiJobStatus } from '@/types/api'

const mocks = vi.hoisted(() => ({
  startAiMutate: vi.fn(),
  invalidateQueries: vi.fn(),
}))

const state = vi.hoisted(() => ({
  settings: { aiCategorizationEnabled: false } as { aiCategorizationEnabled: boolean },
  adminSettings: { ai: { provider: 'openai' } } as { ai: { provider: string } },
  txs: [] as unknown[],
  categories: [] as unknown[],
  aiData: null as AiJobStatus | null,
}))

vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: mocks.invalidateQueries }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

vi.mock('@/features/admin/hooks', () => ({
  useAdminSettings: () => ({ data: state.adminSettings }),
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

function openAutomateMenu() {
  fireEvent.pointerDown(screen.getByRole('button', { name: 'budget.categorize.automate' }), {
    button: 0,
    ctrlKey: false,
  })
}

function renderTab() {
  return render(
    <MemoryRouter>
      <CategorizeTab />
    </MemoryRouter>,
  )
}

describe('CategorizeTab — AI suggestions', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { username: 'alice', role: 'ADMIN', memberId: 1, displayName: 'Alice' }, isAuthenticated: true })
    state.settings = { aiCategorizationEnabled: false }
    state.adminSettings = { ai: { provider: 'openai' } }
    state.txs = []
    state.categories = [TRANSPORT]
    state.aiData = null
    mocks.startAiMutate.mockReset()
    mocks.invalidateQueries.mockReset()
  })

  it('leaves rows clean without AI suggestion tags even when a suggestion exists', () => {
    state.txs = [tx({ aiSuggestedCategoryId: 4, aiConfidence: 92 })]
    renderTab()

    expect(screen.getByRole('combobox')).toHaveAttribute('data-value', '')
    expect(screen.queryByText('budget.categorize.aiSuggested')).not.toBeInTheDocument()
  })

  it('does not preselect or show a chip when there is no suggestion', () => {
    state.txs = [tx()]
    renderTab()

    expect(screen.getByRole('combobox')).toHaveAttribute('data-value', '')
    expect(screen.queryByText('budget.categorize.aiSuggested')).not.toBeInTheDocument()
  })

  it('shows the "Categorize with AI" action in the Automate menu only when enabled and configured', async () => {
    state.txs = [tx()]

    const { rerender } = renderTab()
    openAutomateMenu()
    expect(await screen.findByRole('menuitem', { name: 'budget.categorize.recategorize' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'budget.categorize.categorizeAi' })).not.toBeInTheDocument()

    state.settings = { aiCategorizationEnabled: true }
    rerender(<MemoryRouter><CategorizeTab /></MemoryRouter>)
    expect(await screen.findByRole('menuitem', { name: 'budget.categorize.categorizeAi' })).toBeInTheDocument()

    // When admin has no AI provider configured, show Configure AI instead of a broken run action.
    state.adminSettings = { ai: { provider: 'none' } }
    rerender(<MemoryRouter><CategorizeTab /></MemoryRouter>)
    expect(screen.queryByRole('menuitem', { name: 'budget.categorize.categorizeAi' })).not.toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'budget.categorize.configureAi' })).toBeInTheDocument()
  })

  it('cleans raw English Trade Republic titles and strips UUIDs/IBANs in French', () => {
    state.txs = [
      tx({ id: 1, merchantLabel: 'Incoming Transfer From De Bailliencourt (fr76300040251000000055610334)', amount: 200 }),
      tx({ id: 2, merchantLabel: 'Outgoing Transfer For Louis De Bailliencourt', amount: -28.67 }),
      tx({ id: 3, merchantLabel: 'Interest Payment For Payout Collection 01a194ae-aa5e-7a34-8fcb-d5d7b6c1a451', amount: 0.2 }),
      tx({ id: 4, merchantLabel: '48106c6e-61f3-4db5-8f10-960369d6f5fa', amount: -222.25 }),
    ]
    renderTab()

    expect(screen.getByText('Virement entrant de De Bailliencourt')).toBeInTheDocument()
    expect(screen.getByText('Virement sortant vers Louis De Bailliencourt')).toBeInTheDocument()
    expect(screen.getByText("Versement d'intérêts")).toBeInTheDocument()
    expect(screen.getByText('Opération sans libellé')).toBeInTheDocument()
  })
})

describe('CategorizeTab — AI progress + resume', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { username: 'alice', role: 'ADMIN', memberId: 1, displayName: 'Alice' }, isAuthenticated: true })
    state.settings = { aiCategorizationEnabled: true }
    state.adminSettings = { ai: { provider: 'openai' } }
    state.txs = []
    state.categories = []
    state.aiData = null
    mocks.startAiMutate.mockReset()
    mocks.invalidateQueries.mockReset()
  })

  it('shows the progress label and disables the button while the job is running', () => {
    state.aiData = aiJob({ running: true, processed: 3, total: 10 })
    renderTab()

    const btn = screen.getByRole('button', { name: /budget\.categorize\.aiProgress/i })
    expect(btn).toBeDisabled()
  })

  it('calls startAi.mutate when the AI menu action is clicked (not running)', async () => {
    state.aiData = aiJob({ running: false })
    renderTab()

    openAutomateMenu()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'budget.categorize.categorizeAi' }))
    expect(mocks.startAiMutate).toHaveBeenCalledTimes(1)
  })

  it('invalidates budget + dashboard queries when the job transitions from running to done', async () => {
    state.aiData = aiJob({ running: true, processed: 5, total: 5 })
    const { rerender } = renderTab()

    state.aiData = aiJob({ running: false, processed: 5, total: 5, applied: 3, suggested: 2, done: true })
    rerender(<MemoryRouter><CategorizeTab /></MemoryRouter>)

    await waitFor(() => {
      expect(mocks.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['budget'] })
      expect(mocks.invalidateQueries).toHaveBeenCalledWith({ queryKey: ['dashboard'] })
    })
    expect(screen.queryByText('budget.categorize.aiDone')).not.toBeInTheDocument()
  })
})
