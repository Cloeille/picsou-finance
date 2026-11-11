import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LoanDetailSection } from './LoanDetailSection'
import { useLoanSummary } from '@/features/accounts/hooks'

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

vi.mock('@/features/accounts/hooks', () => ({
  useLoanSummary: vi.fn(() => ({ data: { summary, schedule: [] }, isLoading: false, isError: false })),
}))

describe('LoanDetailSection', () => {
  beforeEach(() => {
    vi.mocked(useLoanSummary).mockReturnValue({
      data: { summary, schedule: [] },
      isLoading: false,
      isError: false,
    } as never)
  })

  it('renders the summary cards when the debt details load', () => {
    const { container } = render(<LoanDetailSection accountId={7} />)

    expect(screen.getByText('debt.monthlyBreakdown')).toBeInTheDocument()
    expect(container).not.toBeEmptyDOMElement()
  })

  it('renders nothing when the debt details fail — the editor lives on the page (issue #550)', () => {
    vi.mocked(useLoanSummary).mockReturnValue({ data: undefined, isLoading: false, isError: true } as never)
    const { container } = render(<LoanDetailSection accountId={7} />)

    expect(container).toBeEmptyDOMElement()
  })
})
