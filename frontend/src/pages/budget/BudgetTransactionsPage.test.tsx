import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
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

// The page offers peer-loan shortcuts from an outgoing or incoming row. They are not
// under test here, and the react-query mock below only provides useQueryClient.
vi.mock('@/features/peer-loans/hooks', () => ({
  useOpenPeerLoans: () => ({ data: [] }),
  useCreatePeerLoanFromTransaction: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useAssignPeerLoanRepayment: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

describe('BudgetTransactionsPage filters', () => {
  it('names the account and category filters for assistive technology', () => {
    render(
      <MemoryRouter>
        <BudgetTransactionsPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('combobox', { name: 'transactions.account' })).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'common.category' })).toBeInTheDocument()
  })
})
