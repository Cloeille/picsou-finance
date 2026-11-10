import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoanDetailSection } from './LoanDetailSection'
import { useOwnership } from '@/features/accounts/hooks'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

const summary = {
  totalInstallments: 180,
  paidInstallments: 24,
  remainingInstallments: 156,
  endDate: '2040-01-01',
  monthlyPayment: 800,
  monthlyCapital: 600,
  monthlyInterest: 180,
  monthlyInsurance: 20,
  totalCost: 200000,
  totalCapitalCost: 180000,
  totalInterestCost: 18000,
  totalInsuranceCost: 2000,
  fileFees: 0,
  totalRepaid: 19200,
  capitalRepaid: 14400,
  interestRepaid: 4320,
  insuranceRepaid: 480,
  remainingBalance: 165600,
  capitalRepaidPct: 8,
}

const ownership = {
  shares: [
    { memberId: 1, displayName: 'Alice', avatarColor: '#6366f1', sharePercent: 50, isOwner: true },
  ],
  totalAssigned: 50,
  unassigned: 50,
}

vi.mock('@/features/accounts/hooks', () => ({
  useLoanSummary: () => ({ data: { summary, schedule: [] }, isLoading: false, isError: false }),
  useOwnership: vi.fn(() => ({ data: ownership, isLoading: false })),
  useUpdateOwnership: () => ({ mutate: vi.fn(), isPending: false, isError: false }),
}))

vi.mock('@/features/family/hooks', () => ({
  useFamilyMembers: () => ({ data: [] }),
}))

describe('LoanDetailSection', () => {
  it('renders the ownership editor for the loan account', () => {
    render(<LoanDetailSection accountId={7} canEdit />)

    // The editor reaches the loan's own split, not the property's (issue #354).
    expect(useOwnership).toHaveBeenCalledWith(7)
    expect(screen.getByText('property.ownership.title')).toBeInTheDocument()
    expect(screen.getByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('common.save')).toBeInTheDocument()
  })

  it('hides the save button when the viewer does not own the loan', () => {
    render(<LoanDetailSection accountId={7} canEdit={false} />)

    expect(screen.getByText('property.ownership.title')).toBeInTheDocument()
    expect(screen.queryByText('common.save')).not.toBeInTheDocument()
  })
})
