import '@testing-library/jest-dom/vitest'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { CategorizeTab } from './CategorizeTab'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
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

function LocationProbe() {
  const { pathname, search, hash } = useLocation()
  return <div data-testid="location">{`${pathname}${search}${hash}`}</div>
}

function renderTab() {
  return render(
    <MemoryRouter>
      <CategorizeTab />
    </MemoryRouter>,
  )
}

function renderTabWithRoutes() {
  return render(
    <MemoryRouter initialEntries={['/budget/review']}>
      <Routes>
        <Route path="/budget/review" element={<CategorizeTab />} />
        <Route path="/budget/settings" element={<LocationProbe />} />
        <Route path="/admin" element={<LocationProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CategorizeTab — AI suggestions', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { username: 'alice', role: 'ADMIN', memberId: 1, displayName: 'Alice' }, isAuthenticated: true })
    useAppStore.setState({ desktopMode: false, advancedMode: false })
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
      tx({ id: 1, merchantLabel: 'Incoming Transfer From De Montclair (fr76000000000000000000000000)', amount: 200 }),
      tx({ id: 2, merchantLabel: 'Outgoing Transfer For Camille De Montclair', amount: -28.67 }),
      tx({ id: 3, merchantLabel: 'Interest Payment For Payout Collection 00000000-0000-7000-8000-000000000001', amount: 0.2 }),
      tx({ id: 4, merchantLabel: '00000000-0000-4000-8000-000000000002', amount: -222.25 }),
    ]
    renderTab()

    expect(screen.getByText('Virement entrant de De Montclair')).toBeInTheDocument()
    expect(screen.getByText('Virement sortant vers Camille De Montclair')).toBeInTheDocument()
    expect(screen.getByText("Versement d'intérêts")).toBeInTheDocument()
    expect(screen.getByText('Opération sans libellé')).toBeInTheDocument()
  })
})

describe('CategorizeTab — AI progress + resume', () => {
  beforeEach(() => {
    useAuthStore.setState({ user: { username: 'alice', role: 'ADMIN', memberId: 1, displayName: 'Alice' }, isAuthenticated: true })
    useAppStore.setState({ desktopMode: false, advancedMode: false })
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

describe('CategorizeTab — Desktop advanced mode prompt', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: { username: 'camille', role: 'ADMIN', memberId: 1, displayName: 'Camille' },
      isAuthenticated: true,
    })
    useAppStore.setState({ desktopMode: true, advancedMode: false })
    state.settings = { aiCategorizationEnabled: false }
    state.adminSettings = { ai: { provider: 'none' } }
    state.txs = [tx()]
    state.categories = [TRANSPORT]
    state.aiData = null
  })

  it('offers to enable advanced mode on Desktop when admin AI is not set up, then enables it and navigates to /admin?tab=ai', async () => {
    renderTabWithRoutes()
    openAutomateMenu()

    const item = await screen.findByRole('menuitem', { name: /budget\.categorize\.enableAdvancedMode/ })
    expect(item).toBeInTheDocument()
    expect(item).toHaveTextContent('budget.categorize.enableAdvancedModeHint')
    expect(screen.queryByRole('menuitem', { name: 'budget.categorize.configureAi' })).not.toBeInTheDocument()

    fireEvent.click(item)

    expect(useAppStore.getState().advancedMode).toBe(true)
    expect(await screen.findByTestId('location')).toHaveTextContent('/admin?tab=ai')
  })

  it('hides the advanced mode prompt when a provider is set up and only the member switch is off', async () => {
    state.adminSettings = { ai: { provider: 'openai' } }
    renderTab()
    openAutomateMenu()

    expect(await screen.findByRole('menuitem', { name: 'budget.categorize.recategorize' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /budget\.categorize\.enableAdvancedMode/ })).not.toBeInTheDocument()
  })

  it('shows Configure AI instead of the advanced mode prompt when advanced mode is on', async () => {
    useAppStore.setState({ desktopMode: true, advancedMode: true })
    renderTab()
    openAutomateMenu()

    expect(await screen.findByRole('menuitem', { name: 'budget.categorize.configureAi' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /budget\.categorize\.enableAdvancedMode/ })).not.toBeInTheDocument()
  })

  it('hides the advanced mode prompt for non-admin members', async () => {
    useAuthStore.setState({
      user: { username: 'sam', role: 'MEMBER', memberId: 2, displayName: 'Sam' },
      isAuthenticated: true,
    })
    renderTab()
    openAutomateMenu()

    expect(await screen.findByRole('menuitem', { name: 'budget.categorize.recategorize' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /budget\.categorize\.enableAdvancedMode/ })).not.toBeInTheDocument()
  })
})

describe('CategorizeTab — Manage rules shortcut', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: { username: 'sam', role: 'MEMBER', memberId: 2, displayName: 'Sam' },
      isAuthenticated: true,
    })
    useAppStore.setState({ desktopMode: false, advancedMode: false })
    state.settings = { aiCategorizationEnabled: false }
    state.adminSettings = { ai: { provider: 'none' } }
    state.txs = [tx()]
    state.categories = [TRANSPORT]
    state.aiData = null
  })

  it('places Manage rules right after Recategorize for every member and navigates to /budget/settings#rules', async () => {
    renderTabWithRoutes()
    openAutomateMenu()

    const items = await screen.findAllByRole('menuitem')
    expect(items.map((el) => el.textContent)).toEqual([
      'budget.categorize.recategorize',
      'budget.categorize.manageRules',
    ])

    fireEvent.click(screen.getByRole('menuitem', { name: 'budget.categorize.manageRules' }))

    expect(await screen.findByTestId('location')).toHaveTextContent('/budget/settings#rules')
  })
})
