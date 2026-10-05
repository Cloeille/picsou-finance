import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { LiabilitiesCard } from './LiabilitiesCard'
import type { DashboardData } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: { date?: string }) => (opts?.date ? `${key} ${opts.date}` : key),
  }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))

vi.mock('@/lib/utils', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/utils')>()),
  formatLocalDate: (date: string) => `formatted(${date})`,
}))

const baseLoan = {
  accountId: 1,
  name: 'Mortgage BNP',
  color: '#6366f1',
  balanceEur: -118200,
  percentage: 0,
  accountType: 'LOAN' as const,
  hasHoldings: false,
}

const baseCard = {
  accountId: 2,
  name: 'Amex',
  color: '#2e77bc',
  balanceEur: 2254.9,
  percentage: 100,
  accountType: 'CREDIT_CARD' as const,
  hasHoldings: false,
}

describe('LiabilitiesCard', () => {
  it('renders loan name and balance', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    expect(screen.getByText('Mortgage BNP')).toBeInTheDocument()
  })

  it('renders progress, percentage and monthly payment for a configured loan', () => {
    const { container } = render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32.4 }]}
        totalMonthlyPayment={1050}
      />
    )
    expect(document.querySelector('[role="progressbar"]')).not.toBeNull()
    expect(container).toHaveTextContent('32% · 1050/mo')
  })

  it('shows hint icon when loan has no parameters', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: null, percentPaid: null }]}
        totalMonthlyPayment={null}
      />
    )
    expect(screen.getByLabelText('Parameters not configured')).toBeInTheDocument()
  })

  it('never renders NaN when the JSON omits the loan fields', () => {
    // Shape of the wire format: the backend drops null fields entirely (non_null inclusion).
    const data = JSON.parse(
      '{"totalNetWorth":0,"totalLiabilities":15000,"liabilities":[' +
        '{"accountId":1,"name":"Finary loan","color":"#f97316","balanceEur":15000,' +
        '"percentage":100,"accountType":"LOAN","hasHoldings":false}]}'
    ) as DashboardData
    const { container } = render(
      <LiabilitiesCard
        liabilities={data.liabilities}
        totalMonthlyPayment={data.totalMonthlyPayment}
      />
    )
    expect(container).not.toHaveTextContent('NaN')
    expect(container).not.toHaveTextContent('dashboard.monthlyPayment')
    expect(document.querySelector('[role="progressbar"]')).toBeNull()
    expect(screen.getByText('dashboard.loanParamsUnconfigured')).toBeInTheDocument()
  })

  it('shows the amount due and due date for a credit card instead of loan details', () => {
    const { container } = render(
      <LiabilitiesCard
        liabilities={[{ ...baseCard, paymentDueAmountEur: 912.4, paymentDueDate: '2026-11-05' }]}
      />
    )
    expect(container).toHaveTextContent('dashboard.amountDue 912.4 · dashboard.dueOn formatted(2026-11-05)')
    expect(container).not.toHaveTextContent('NaN')
    expect(screen.queryByText('dashboard.loanParamsUnconfigured')).toBeNull()
    expect(document.querySelector('[role="progressbar"]')).toBeNull()
  })

  it('shows only the known half of a credit card statement', () => {
    const { container } = render(
      <LiabilitiesCard liabilities={[{ ...baseCard, paymentDueDate: '2026-11-05' }]} />
    )
    expect(container).toHaveTextContent('dashboard.dueOn formatted(2026-11-05)')
    expect(container).not.toHaveTextContent('dashboard.amountDue')
    expect(container).not.toHaveTextContent('·')
  })

  it('shows nothing extra for a credit card without statement data', () => {
    render(<LiabilitiesCard liabilities={[baseCard]} />)
    const row = screen.getByText('Amex').closest('.rounded-2xl')
    expect(row).toHaveTextContent(/^Amex2254\.9$/)
    expect(screen.queryByLabelText('Parameters not configured')).toBeNull()
  })

  it('shows monthlyPayment section in header when totalMonthlyPayment is non-null', () => {
    render(
      <LiabilitiesCard
        liabilities={[{ ...baseLoan, monthlyPayment: 1050, percentPaid: 32 }]}
        totalMonthlyPayment={1050}
      />
    )
    expect(screen.getByText(/dashboard\.monthlyPayment/)).toBeInTheDocument()
  })
})
