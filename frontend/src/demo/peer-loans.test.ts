import { AxiosHeaders } from 'axios'
import { afterAll, describe, expect, it, vi } from 'vitest'
import type { Account, PeerLoanDetail, PeerLoanOpen } from '@/types/api'

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
    const account = accounts.find((a) => a.type === 'PEER_LOAN')
    expect(account, 'a demo account carries the PEER_LOAN type').toBeDefined()

    const detail = await get<PeerLoanDetail>(`/accounts/${account!.id}/peer-loan`)

    expect(detail.configured).toBe(true)
    expect(detail.accountId).toBe(account!.id)
    expect(detail.accountName).toBe(account!.name)
    expect(detail.repayments.length).toBeGreaterThan(0)
    expect(detail.curve.length).toBeGreaterThan(1)
    expect(detail.curve.some((point) => point.date === '2026-06-15')).toBe(true)

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
