import '@testing-library/jest-dom'
import { beforeAll, describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { PeerLoanTermsDialog } from './PeerLoanTermsDialog'
import type { Account, PeerLoanDetail, PeerLoanTermsRequest } from '@/types/api'

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

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const simulate = vi.fn()
vi.mock('@/features/peer-loans/hooks', () => ({
  usePeerLoanSimulation: () => ({ mutateAsync: simulate, isPending: false }),
}))

function openDialog(props: {
  initial?: PeerLoanDetail | null
  cashAccounts?: Account[]
  onSubmit?: (data: PeerLoanTermsRequest) => void
}) {
  const view = render(
    <QueryClientProvider client={new QueryClient()}>
      <PeerLoanTermsDialog
        open={false}
        onOpenChange={() => {}}
        initial={props.initial ?? null}
        cashAccounts={props.cashAccounts ?? []}
        onSubmit={props.onSubmit ?? (() => {})}
      />
    </QueryClientProvider>,
  )
  view.rerender(
    <QueryClientProvider client={new QueryClient()}>
      <PeerLoanTermsDialog
        open
        onOpenChange={() => {}}
        initial={props.initial ?? null}
        cashAccounts={props.cashAccounts ?? []}
        onSubmit={props.onSubmit ?? (() => {})}
      />
    </QueryClientProvider>,
  )
  return view
}

function detail(overrides: Partial<PeerLoanDetail> = {}): PeerLoanDetail {
  return {
    accountId: 16,
    accountName: 'Jean',
    configured: true,
    principal: 500,
    lentAt: '2026-01-01',
    expectedBack: null,
    annualRate: 0.029,
    ratePeriod: 'ANNUAL',
    plannedMonthlyAmount: null,
    plannedMonths: null,
    linkedAccountId: 4,
    linkedAccountName: 'Compte courant',
    note: null,
    closedAt: null,
    principalRemaining: 500,
    interestAccrued: 0,
    interestReceived: 0,
    principalRepaid: 0,
    totalRepaid: 0,
    writtenOff: 0,
    repaidPct: 0,
    scheduledRemaining: null,
    installmentsBehind: null,
    plannedInterest: null,
    repayments: [],
    curve: [],
    ...overrides,
  }
}

const cashAccount = { id: 4, name: 'Compte courant' } as unknown as Account

describe('PeerLoanTermsDialog rate display', () => {
  it('shows a stored rate without floating-point noise', () => {
    openDialog({ initial: detail(), cashAccounts: [cashAccount] })

    // 0.029 * 100 is 2.9000000000000004 in floating point; the dialog must show 2.9.
    const rate = screen.getByDisplayValue('2.9')
    expect(rate).toBeInTheDocument()
  })

  it('converts a monthly-stated rate for display without noise', () => {
    openDialog({ initial: detail({ annualRate: 0.06, ratePeriod: 'MONTHLY' }), cashAccounts: [cashAccount] })

    expect(screen.getByDisplayValue('0.5')).toBeInTheDocument()
  })

  it('submits a rounded annual rate', async () => {
    const onSubmit = vi.fn()
    openDialog({ initial: detail(), cashAccounts: [cashAccount], onSubmit })

    fireEvent.click(screen.getByText('common.save'))
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalled())

    const submitted = onSubmit.mock.calls[0][0] as { annualRate: number }
    expect(submitted.annualRate).toBeCloseTo(0.029, 10)
  })
})
