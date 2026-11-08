import { AxiosHeaders } from 'axios'
import { afterAll, describe, expect, it, vi } from 'vitest'
import type { Account, PeerLoanDetail, PeerLoanOpen, PeerLoansOverview } from '@/types/api'

// One instant for every fixture, before they load: the peer-loan terms and their ledger are
// relative to today. Mid-month keeps the curve clear of a month boundary.
vi.useFakeTimers({ toFake: ['Date'] })
vi.setSystemTime(new Date('2026-06-15T10:00:00Z'))
const { createDemoAdapter } = await import('./index')

afterAll(() => {
  vi.useRealTimers()
})

const adapter = createDemoAdapter()
async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await adapter({ method: 'GET', url, params, headers: new AxiosHeaders() })
  return response.data as T
}

describe('demo peer loan', () => {
  it('exposes a configured loan whose figures agree with its ledger', async () => {
    const accounts = await get<Account[]>('/accounts')
    const account = accounts.find((a) => a.type === 'PEER_LOAN' && a.name === 'Jean')
    expect(account, 'the Jean demo account carries the PEER_LOAN type').toBeDefined()

    const detail = await get<PeerLoanDetail>(`/accounts/${account!.id}/peer-loan`)

    expect(detail.configured).toBe(true)
    expect(detail.accountId).toBe(account!.id)
    expect(detail.accountName).toBe('Jean')
    expect(detail.repayments.length).toBeGreaterThan(0)
    expect(detail.curve.length).toBeGreaterThan(1)
    expect(detail.curve.some((point) => point.date === '2026-06-15')).toBe(true)

    // Dated figures pinned from Jean's ledger at 2026-06-15: the identities below cannot
    // catch a dropped accrual or a zeroed interest split, these exact values can.
    expect(detail.principalRemaining).toBeCloseTo(1482.58, 2)
    expect(detail.interestAccrued).toBeCloseTo(3.66, 2)
    expect(detail.interestReceived).toBeCloseTo(32.58, 2)
    expect(detail.principalRepaid).toBeCloseTo(1117.42, 2)

    // The summary is derived from the same rows the journal shows.
    expect(detail.principalRepaid).toBeCloseTo(detail.principal - detail.principalRemaining, 2)
    expect(detail.totalRepaid).toBeCloseTo(detail.principalRepaid + detail.interestReceived, 2)
    expect(detail.repaidPct).toBeCloseTo(detail.principalRepaid * 100 / detail.principal, 1)
    for (const row of detail.repayments) {
      expect(row.interestPart! + row.capitalPart!).toBeCloseTo(row.amount, 1)
    }
  })

  it('reports one remaining balance across the account, the detail and the open list', async () => {
    const accounts = await get<Account[]>('/accounts')
    const account = accounts.find((a) => a.type === 'PEER_LOAN')!
    const detail = await get<PeerLoanDetail>(`/accounts/${account.id}/peer-loan`)
    const open = await get<PeerLoanOpen[]>('/peer-loans/open')

    expect(account.currentBalanceEur).toBeCloseTo(detail.principalRemaining, 2)
    expect(open).toContainEqual({
      accountId: account.id,
      name: account.name,
      remaining: detail.principalRemaining,
    })
  })

  it('summarises both loans and names the one behind', async () => {
    const overview = await get<PeerLoansOverview>('/peer-loans/overview')

    expect(overview.loans).toHaveLength(2)
    expect(overview.openCount).toBe(2)
    expect(overview.closedCount).toBe(0)
    expect(overview.totalOutstanding).toBeCloseTo(
      overview.loans.reduce((sum, loan) => sum + loan.principalRemaining, 0), 2)
    // Assert Sophie's own row: Jean's status must not be able to satisfy this check.
    const sophie = overview.loans.find((loan) => loan.name === 'Sophie')
    expect(sophie, 'the demo overview carries Sophie').toBeDefined()
    expect(sophie!.installmentsBehind ?? 0).toBeGreaterThan(0)
    expect(overview.history.length).toBeGreaterThan(1)
    expect(overview.history.at(-1)?.outstanding).toBeCloseTo(overview.totalOutstanding, 2)
  })

  it('ends its history on the live balance and keeps the receivable out of invested and PnL', async () => {
    const accounts = await get<Account[]>('/accounts')
    const account = accounts.find((a) => a.type === 'PEER_LOAN')!
    const history = await get<{ date: string; balance: number }[]>(`/accounts/${account.id}/history`)

    expect(history.at(-1)?.balance).toBeCloseTo(account.currentBalanceEur, 2)

    const netWorth = await get<{ accounts: Record<string, { total: number; invested: number; pnl: number }> }[]>(
      '/history',
      { accountIds: account.id, months: 12, split: true },
    )
    const latest = netWorth.at(-1)!.accounts[String(account.id)]
    expect(latest.total).toBeCloseTo(account.currentBalanceEur, 2)
    expect(latest.invested).toBe(0)
    expect(latest.pnl).toBe(0)
  })
})
