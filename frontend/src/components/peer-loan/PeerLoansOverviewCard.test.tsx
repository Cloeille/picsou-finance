import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { PeerLoansOverviewCard } from './PeerLoansOverviewCard'
import type { PeerLoansOverview } from '@/types/api'

const navigate = vi.fn()
vi.mock('react-router', () => ({ useNavigate: () => navigate }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts.count === 'number' ? `${key}:${opts.count}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const overview: PeerLoansOverview = {
  totalOutstanding: 2400,
  openCount: 2,
  closedCount: 1,
  interestAccrued: 15,
  loans: [
    {
      accountId: 16,
      name: 'Jean',
      principal: 2600,
      principalRemaining: 1400,
      repaidPct: 46,
      interestAccrued: 5,
      interestReceived: 10,
      lentAt: '2026-01-01',
      expectedBack: '2027-01-01',
      installmentsBehind: 2,
      lastPaymentDate: '2026-11-01',
      lastPaymentAmount: 250,
      closedAt: null,
    },
    {
      accountId: 17,
      name: 'Sophie',
      principal: 1800,
      principalRemaining: 1000,
      repaidPct: 44,
      interestAccrued: 10,
      interestReceived: 5,
      lentAt: '2026-03-01',
      expectedBack: '2027-03-01',
      installmentsBehind: 0,
      lastPaymentDate: null,
      lastPaymentAmount: null,
      closedAt: null,
    },
    {
      accountId: 18,
      name: 'Paul',
      principal: 500,
      principalRemaining: 0,
      repaidPct: 100,
      interestAccrued: 0,
      interestReceived: 0,
      lentAt: '2025-01-01',
      expectedBack: null,
      installmentsBehind: null,
      lastPaymentDate: '2025-06-01',
      lastPaymentAmount: 500,
      closedAt: '2025-06-01',
    },
  ],
  history: [
    { date: '2026-10-01', outstanding: 2600 },
    { date: '2026-11-01', outstanding: 2400 },
  ],
}

describe('PeerLoansOverviewCard', () => {
  it('shows the totals and one row per borrower', () => {
    render(<PeerLoansOverviewCard data={overview} />)

    expect(screen.getByText('peerLoan.overview.totalOutstanding')).toBeInTheDocument()
    expect(screen.getByText('peerLoan.overview.openLoans')).toBeInTheDocument()
    expect(screen.getByText('peerLoan.overview.interestAccrued')).toBeInTheDocument()
    expect(screen.getByText('Jean')).toBeInTheDocument()
    expect(screen.getByText('Sophie')).toBeInTheDocument()
    // Closed loans stay in the analysis, at the bottom, so their ledger remains reachable.
    expect(screen.getByText('Paul')).toBeInTheDocument()
    expect(screen.getByText('peerLoan.overview.status.closed')).toBeInTheDocument()
  })

  it('states a late instalment count and an on-time plan as facts', () => {
    render(<PeerLoansOverviewCard data={overview} />)

    expect(screen.getByText('peerLoan.overview.status.behind:2')).toBeInTheDocument()
    expect(screen.getByText('peerLoan.overview.status.onTime')).toBeInTheDocument()
  })

  it('opens the loan from a native button, keyboard included', () => {
    render(<PeerLoansOverviewCard data={overview} />)

    // The borrower cell is a real button, so Enter/Space activation is the browser's own
    // behaviour rather than a hand-rolled key handler on a non-interactive row.
    const sophie = screen.getByRole('button', { name: 'Sophie' })
    const jean = screen.getByRole('button', { name: 'Jean' })
    expect(sophie.tagName).toBe('BUTTON')
    expect(jean.tagName).toBe('BUTTON')

    fireEvent.click(sophie)
    expect(navigate).toHaveBeenCalledWith('/accounts/17')

    jean.focus()
    expect(jean).toHaveFocus()
    fireEvent.click(jean)
    expect(navigate).toHaveBeenCalledWith('/accounts/16')
  })
})
