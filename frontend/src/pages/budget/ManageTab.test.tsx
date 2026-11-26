import '@testing-library/jest-dom/vitest'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { ManageTab } from './ManageTab'
import { chooseSelectOption } from '@/test/radix-select'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import type { CategorizationRule, Category } from '@/types/api'

const mocks = vi.hoisted(() => ({
  createRuleMutate: vi.fn(),
  updateRuleMutate: vi.fn(),
  deleteRuleMutate: vi.fn(),
}))

const state = vi.hoisted(() => ({
  rules: [] as CategorizationRule[],
  categories: [] as Category[],
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, params?: Record<string, unknown>) =>
      params && 'name' in params ? `${key}:${String(params.name)}` : key,
  }),
}))

vi.mock('@/features/budget/hooks', () => ({
  useBudgetSettings: () => ({ data: undefined, isLoading: false, isError: false, refetch: vi.fn() }),
  useUpdateBudgetSettings: () => ({ mutate: vi.fn(), isPending: false }),
  useCategories: () => ({ data: state.categories, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateCategory: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateCategory: () => ({ mutate: vi.fn(), isPending: false }),
  useArchiveCategory: () => ({ mutate: vi.fn(), isPending: false }),
  useUnarchiveCategory: () => ({ mutate: vi.fn(), isPending: false }),
  useRules: () => ({ data: state.rules, isLoading: false, isError: false, refetch: vi.fn() }),
  useCreateRule: () => ({ mutate: mocks.createRuleMutate, isPending: false }),
  useUpdateRule: () => ({ mutate: mocks.updateRuleMutate, isPending: false }),
  useDeleteRule: () => ({ mutate: mocks.deleteRuleMutate, isPending: false }),
}))

const GROCERIES: Category = {
  id: 2, name: 'Groceries', kind: 'EXPENSE', color: '#22c55e', icon: null,
  isDefault: true, archived: false, sortOrder: 1, parentId: null,
}

const TRANSPORT: Category = {
  id: 4, name: 'Transport', kind: 'EXPENSE', color: '#3b82f6', icon: null,
  isDefault: true, archived: false, sortOrder: 2, parentId: null,
}

const SAMPLE_RULE: CategorizationRule = {
  id: 17,
  matchType: 'COUNTERPARTY',
  pattern: 'ACME MARKET',
  categoryId: 2,
  categoryName: 'Groceries',
  priority: 3,
  source: 'USER',
}

function renderTab(initialEntry = '/budget/settings') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <ManageTab />
    </MemoryRouter>,
  )
}

describe('ManageTab — categorization rules', () => {
  beforeEach(() => {
    useAuthStore.setState({
      user: { username: 'sam', role: 'MEMBER', memberId: 2, displayName: 'Sam' },
      isAuthenticated: true,
    })
    useAppStore.setState({ desktopMode: false, advancedMode: false })
    state.categories = [GROCERIES, TRANSPORT]
    state.rules = [SAMPLE_RULE]
    mocks.createRuleMutate.mockReset()
    mocks.updateRuleMutate.mockReset()
    mocks.deleteRuleMutate.mockReset()
    Element.prototype.scrollIntoView = vi.fn()
  })

  it('pre-fills the rule form from the edit button and saves the edited values with the rule id', () => {
    mocks.updateRuleMutate.mockImplementation((_args, opts?: { onSuccess?: () => void }) => {
      opts?.onSuccess?.()
    })

    renderTab()

    fireEvent.click(screen.getByRole('button', { name: 'budget.a11y.edit:ACME MARKET' }))

    expect(screen.getByRole('heading', { name: 'budget.rule.edit' })).toBeInTheDocument()
    const matchTrigger = screen.getByLabelText('budget.rule.matchType')
    const patternInput = screen.getByLabelText('budget.rule.pattern')
    const categoryTrigger = screen.getByLabelText('budget.rule.category')

    expect(matchTrigger).toHaveAttribute('data-value', 'COUNTERPARTY')
    expect(patternInput).toHaveValue('ACME MARKET')
    expect(categoryTrigger).toHaveAttribute('data-value', '2')

    chooseSelectOption(matchTrigger, 'KEYWORD')
    fireEvent.change(patternInput, { target: { value: '  ALPHA TRANSIT  ' } })
    chooseSelectOption(categoryTrigger, '4')

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    expect(mocks.updateRuleMutate).toHaveBeenCalledTimes(1)
    expect(mocks.updateRuleMutate).toHaveBeenCalledWith(
      {
        id: 17,
        data: {
          matchType: 'KEYWORD',
          pattern: 'ALPHA TRANSIT',
          categoryId: 4,
        },
      },
      expect.objectContaining({ onSuccess: expect.any(Function) }),
    )
    expect(mocks.createRuleMutate).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('restores the empty create form after cancelling an edit', () => {
    renderTab()

    fireEvent.click(screen.getByRole('button', { name: 'budget.a11y.edit:ACME MARKET' }))
    expect(screen.getByLabelText('budget.rule.pattern')).toHaveValue('ACME MARKET')

    fireEvent.click(screen.getByRole('button', { name: 'common.cancel' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'budget.rule.add' }))

    expect(screen.getByRole('heading', { name: 'budget.rule.add' })).toBeInTheDocument()
    expect(screen.getByLabelText('budget.rule.matchType')).toHaveAttribute('data-value', 'COUNTERPARTY')
    expect(screen.getByLabelText('budget.rule.pattern')).toHaveValue('')
    expect(screen.getByLabelText('budget.rule.category')).toHaveAttribute('data-value', '')
  })

  it('pre-fills and edits multi-word rules created from the review picker', () => {
    state.rules = [
      {
        ...SAMPLE_RULE,
        id: 42,
        matchType: 'KEYWORDS_ALL',
        pattern: 'METRO PASS',
        categoryId: 4,
        categoryName: 'Transport',
        source: 'AUTO',
      },
    ]

    renderTab()

    fireEvent.click(screen.getByRole('button', { name: 'budget.a11y.edit:METRO PASS' }))

    expect(screen.getByLabelText('budget.rule.matchType')).toHaveAttribute('data-value', 'KEYWORDS_ALL')
    expect(screen.getByLabelText('budget.rule.pattern')).toHaveValue('METRO PASS')
    expect(screen.getByLabelText('budget.rule.category')).toHaveAttribute('data-value', '4')
  })

  it('scrolls the rules card into view when arriving with the #rules hash', () => {
    renderTab('/budget/settings#rules')

    const rulesCard = document.getElementById('rules')
    expect(rulesCard).not.toBeNull()
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'start' })
  })

  it('does not scroll when arriving without the #rules hash', () => {
    renderTab('/budget/settings')

    expect(document.getElementById('rules')).not.toBeNull()
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled()
  })
})
