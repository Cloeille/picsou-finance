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

  // The tests below mutate the demo store, so they run last: the overview, the open
  // list and the detail must move together after a write, like the real API.
  it('reflects a demo repayment in the open list and the overview', async () => {
    async function post<T>(url: string, data: unknown): Promise<T> {
      const response = await adapter({ method: 'POST', url, data: JSON.stringify(data), headers: new AxiosHeaders() })
      return response.data as T
    }
    const before = await get<PeerLoansOverview>('/peer-loans/overview')
    const jeanBefore = before.loans.find((loan) => loan.name === 'Jean')!.principalRemaining

    await post('/accounts/16/peer-loan/repayments', { paidOn: '2026-06-10', amount: 100, withCashLeg: false })

    const after = await get<PeerLoansOverview>('/peer-loans/overview')
    const jeanAfter = after.loans.find((loan) => loan.name === 'Jean')!.principalRemaining
    expect(jeanAfter).toBeCloseTo(jeanBefore - 100, 2)
    expect(after.totalOutstanding).toBeCloseTo(before.totalOutstanding - 100, 2)
    const open = await get<PeerLoanOpen[]>('/peer-loans/open')
    expect(open.find((loan) => loan.accountId === 16)?.remaining).toBeCloseTo(jeanAfter, 2)
  })

  it('recomputes the demo detail when a repayment is edited', async () => {
    async function put<T>(url: string, data: unknown): Promise<T> {
      const response = await adapter({ method: 'PUT', url, data: JSON.stringify(data), headers: new AxiosHeaders() })
      return response.data as T
    }
    const before = await get<PeerLoanDetail>('/accounts/16/peer-loan')
    const row = before.repayments.at(-1)!
    await put(`/accounts/16/peer-loan/repayments/${row.id}`, { paidOn: row.paidOn, amount: row.amount + 50 })

    const after = await get<PeerLoanDetail>('/accounts/16/peer-loan')
    expect(after.principalRemaining).toBeCloseTo(before.principalRemaining - 50, 2)
    expect(after.principalRepaid).toBeCloseTo(after.principal - after.principalRemaining, 2)
  })

  it('creates a demo loan from a transaction whose account resolves', async () => {
    async function post<T>(url: string, data: unknown): Promise<T> {
      const response = await adapter({ method: 'POST', url, data: JSON.stringify(data), headers: new AxiosHeaders() })
      return response.data as T
    }
    // Transaction 10 is an outgoing payment, the only kind a loan can be built from.
    const created = await post<PeerLoanDetail>('/peer-loans/from-transaction', { transactionId: 10 })

    expect(created.configured).toBe(true)
    expect(created.principal).toBeCloseTo(850, 2)
    const account = await get<Account>(`/accounts/${created.accountId}`)
    expect(account.name).toBe(created.accountName)
    const detail = await get<PeerLoanDetail>(`/accounts/${created.accountId}/peer-loan`)
    expect(detail.configured).toBe(true)
    expect(detail.principalRemaining).toBeCloseTo(850, 2)
  })

  it('refuses demo writes the hardened API would refuse', async () => {
    async function post<T>(url: string, data: unknown): Promise<T> {
      const response = await adapter({ method: 'POST', url, data: JSON.stringify(data), headers: new AxiosHeaders() })
      return response.data as T
    }
    const jean = await get<PeerLoanDetail>('/accounts/16/peer-loan')
    const over = jean.interestAccrued + jean.principalRemaining + 100
    await expect(post('/accounts/16/peer-loan/repayments', { paidOn: '2026-06-10', amount: over }))
      .rejects.toThrow(/cannot exceed/)
    await expect(post('/accounts/16/peer-loan/repayments', { paidOn: '2026-06-10', amount: 50, kind: 'WRITE_OFF' }))
      .rejects.toThrow(/closing the loan/)
    // 10 exceeds Jean's ~3.66 accrued interest.
    await expect(post('/accounts/16/peer-loan/repayments', { paidOn: '2026-06-10', amount: 10, kind: 'INTEREST' }))
      .rejects.toThrow(/accrued interest/)
    // A fresh zero-rate loan never accrues: any INTEREST row mislabels capital.
    const fresh = await post<PeerLoanDetail>('/peer-loans/from-transaction', { transactionId: 14 })
    await expect(post(`/accounts/${fresh.accountId}/peer-loan/repayments`, { paidOn: '2026-06-10', amount: 10, kind: 'INTEREST' }))
      .rejects.toThrow(/accrued interest/)
  })

  it('refuses demo linked rows the hardened API would refuse', async () => {
    async function post<T>(url: string, data: unknown): Promise<T> {
      const response = await adapter({ method: 'POST', url, data: JSON.stringify(data), headers: new AxiosHeaders() })
      return response.data as T
    }
    await expect(post('/accounts/16/peer-loan/repayments/from-transaction', { transactionId: 12, kind: 'WRITE_OFF' }))
      .rejects.toThrow(/closing the loan/)
    // Transaction 12 is 200, above Jean's ~3.66 accrued interest.
    await expect(post('/accounts/16/peer-loan/repayments/from-transaction', { transactionId: 12, kind: 'INTEREST' }))
      .rejects.toThrow(/accrued interest/)
  })
})
