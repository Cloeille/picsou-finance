import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Budget } from '@/types/api'

const deleteMutate = vi.fn()
const budgetsData: Budget[] = [
  {
    id: 1,
    categoryId: 10,
    categoryName: 'Abonnements',
    categoryKind: 'EXPENSE',
    categoryColor: '#6366f1',
    categoryIcon: null,
    monthlyLimit: 100,
    spent: 120,
    remaining: -20,
    percent: 120,
    overBudget: true,
    rollup: false,
    cycleStart: '2026-11-01',
    cycleEnd: '2026-11-30',
  },
]

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ amount: (v: number) => `${v} €` }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value} €</span>,
}))

vi.mock('@/features/budget/hooks', () => ({
  useBudgets: () => ({ data: budgetsData, isLoading: false, isError: false, refetch: vi.fn() }),
  useCategories: () => ({
    data: [{ id: 10, name: 'Abonnements', kind: 'EXPENSE', archived: false }],
  }),
  useCreateBudget: () => ({ mutate: vi.fn(), isPending: false }),
  useUpdateBudget: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteBudget: () => ({ mutate: deleteMutate, isPending: false }),
}))

const { EnvelopesTab } = await import('./EnvelopesTab')

describe('EnvelopesTab', () => {
  it('opens the edit dialog on card click and exposes delete inside the dialog', () => {
    deleteMutate.mockReset()
    render(<EnvelopesTab />)

    // No separate delete button cluttering the card itself.
    expect(screen.queryByRole('button', { name: 'common.delete' })).not.toBeInTheDocument()

    // Clicking the card opens the edit dialog with a Delete action inside the footer.
    fireEvent.click(screen.getByRole('button', { name: 'budget.a11y.edit' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    const deleteBtn = screen.getByRole('button', { name: 'common.delete' })
    expect(deleteBtn).toBeInTheDocument()

    // Clicking Delete opens the confirmation dialog and confirms deletion.
    fireEvent.click(deleteBtn)
    expect(screen.getByText('budget.envelope.deleteTitle')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'common.delete' }))
    expect(deleteMutate).toHaveBeenCalledWith(1, expect.any(Object))
  })
})
