import '@testing-library/jest-dom'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { PeerLoanDetailSection } from './PeerLoanDetailSection'
import en from '@/i18n/locales/en.json'
import type { PeerLoanDetail, PeerLoanRepayment } from '@/types/api'

// The real English strings, so a key missing from the locale fails here instead of rendering
// as its own name; {{amount}} is the only placeholder the delete dialog uses.
function lookup(key: string): string | undefined {
  const value = key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    en,
  )
  return typeof value === 'string' ? value : undefined
}

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      (lookup(key) ?? key).replace('{{amount}}', String(opts?.amount ?? '')),
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

const deleteRepayment = vi.fn()
const detail: { current: PeerLoanDetail } = { current: loan([]) }

vi.mock('@/features/peer-loans/hooks', () => ({
  usePeerLoan: () => ({ data: detail.current, isLoading: false, isError: false }),
  useSavePeerLoanTerms: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useAddPeerLoanRepayment: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useUpdatePeerLoanRepayment: () => ({ mutateAsync: vi.fn(), isPending: false }),
  useDeletePeerLoanRepayment: () => ({ mutateAsync: deleteRepayment, isPending: false }),
  useClosePeerLoan: () => ({ mutateAsync: vi.fn(), isPending: false }),
  usePeerLoanSimulation: () => ({ mutateAsync: vi.fn(), isPending: false }),
}))

vi.mock('@/features/accounts/hooks', () => ({
  useAccounts: () => ({ data: [] }),
}))

function loan(repayments: PeerLoanRepayment[]): PeerLoanDetail {
  return {
    accountId: 16,
    accountName: 'Jean',
    configured: true,
    principal: 1000,
    lentAt: '2026-01-01',
    principalRemaining: 500,
    interestAccrued: 0,
    interestReceived: 0,
    principalRepaid: 500,
    totalRepaid: 500,
    writtenOff: 0,
    repaidPct: 50,
    repayments,
    curve: [],
  }
}

function row(overrides: Partial<PeerLoanRepayment>): PeerLoanRepayment {
  return {
    id: 1,
    paidOn: '2026-02-01',
    amount: 500,
    kind: 'PRINCIPAL',
    interestPart: 0,
    capitalPart: 500,
    ...overrides,
  }
}

beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    }),
  })
})

beforeEach(() => {
  deleteRepayment.mockReset()
})

/** Opens the delete confirmation of the only ledger row and returns its dialog. */
function openDeleteDialog(repayment: PeerLoanRepayment) {
  detail.current = loan([repayment])
  render(<PeerLoanDetailSection accountId={16} />)
  fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
  return screen.getByRole('dialog')
}

describe('PeerLoanDetailSection delete confirmation', () => {
  it('says the cash movement goes with a repayment Picsou wrote', () => {
    const dialog = openDeleteDialog(row({ hasCashLeg: true, cashLegOwned: true }))

    expect(within(dialog).getByText(/will be deleted together with its cash movement/)).toBeInTheDocument()
  })

  it('says a linked transaction stays in its account and is only unlinked', () => {
    const dialog = openDeleteDialog(row({ hasCashLeg: true, cashLegOwned: false }))

    expect(within(dialog).getByText(/will be unlinked from the loan/)).toBeInTheDocument()
    expect(within(dialog).getByText(/linked transaction stays in its account/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/together with its cash movement/)).not.toBeInTheDocument()
  })

  it('claims no cash movement for a row that has none', () => {
    const dialog = openDeleteDialog(row({ hasCashLeg: false, cashLegOwned: true }))

    expect(within(dialog).getByText(/will be deleted\. The loan balance/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/cash movement/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/linked transaction/)).not.toBeInTheDocument()
  })
})
