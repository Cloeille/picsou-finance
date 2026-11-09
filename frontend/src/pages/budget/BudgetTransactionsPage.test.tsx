import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { BudgetTransactionsPage } from './BudgetTransactionsPage'

vi.mock('@tanstack/react-query', () => ({
  useQueryClient: () => ({ invalidateQueries: vi.fn() }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { resolvedLanguage: 'en', language: 'en' } }),
}))

vi.mock('@/features/budget/hooks', () => ({
  useTransactions: () => ({ data: [], isLoading: false, isError: false, refetch: vi.fn() }),
  useCategories: () => ({ data: [] }),
  useCategorize: () => ({ mutate: vi.fn() }),
  useMerchantLogoUrl: () => () => null,
}))

vi.mock('@/hooks/use-touch-device', () => ({ useIsTouchDevice: () => false }))

vi.mock('@/features/accounts/hooks', () => ({
  useAccounts: () => ({ data: [] }),
}))

describe('BudgetTransactionsPage filters', () => {
  it('names the account and category filters for assistive technology', () => {
    render(<BudgetTransactionsPage />)

    expect(screen.getByRole('combobox', { name: 'transactions.account' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'common.category' })).toBeInTheDocument()
  })
})
