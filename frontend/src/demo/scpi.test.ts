import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosHeaders } from 'axios'
import { createDemoAdapter } from './index'
import { mockAccounts } from './data/accounts'
import type { Account, DashboardData, RealEstateSummary } from '@/types/api'
import type { IntradayPoint, PnlData } from '@/features/dashboard/api'

describe('SCPI demo integration', () => {
  it('does not reuse the id of a pre-existing account', () => {
    const ids = mockAccounts.map(account => account.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('links the paper-property summary to the SCPI, not a Revolut pocket', async () => {
    const adapter = createDemoAdapter()
    const summary = await adapter({
      method: 'GET', url: '/real-estate/summary', headers: new AxiosHeaders(),
    })
    const paper = (summary.data as RealEstateSummary).paper[0]
    const detail = await adapter({
      method: 'GET', url: `/accounts/${paper.accountId}`, headers: new AxiosHeaders(),
    })
    const account = detail.data as Account
    expect(account.type).toBe('SCPI')
    expect(account.name).toBe(paper.name)
    expect(account.currentBalanceEur).toBe(paper.grossValue)
  })
})

describe.each([
  ['mid-month', '2026-06-15T10:00:00Z'],
  ['the first day of the month', '2026-06-01T10:00:00Z'],
])('SCPI edits on %s', (_label, today) => {
  let adapter: ReturnType<typeof createDemoAdapter>
  type HistoryPoint = { date: string; total: number; invested: number; pnl: number; accounts: Record<string, { total: number }> }

  async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    return (await adapter({ method: 'GET', url, params, headers: new AxiosHeaders() })).data as T
  }

  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(today))
    vi.resetModules()
    adapter = (await import('./index')).createDemoAdapter()
  })

  afterEach(() => { vi.useRealTimers() })

  it('updates the dashboard, history, P&L and intraday values on refetch', async () => {
    const beforeAccount = await get<Account>('/accounts/13')
    const beforeDashboard = await get<DashboardData>('/dashboard')
    const beforeHistory = await get<HistoryPoint[]>('/history', { accountIds: '13', split: true })
    const accounts = await get<Account[]>('/accounts')
    const accountIds = accounts.filter(a => a.parentAccountId == null).map(a => a.id).join(',')

    for (const position of [
      { shareCount: 10, withdrawalPriceEur: 900 },
      { shareCount: 11, withdrawalPriceEur: 850 },
    ]) {
      await adapter({
        method: 'PUT', url: '/accounts/13/scpi', headers: new AxiosHeaders(), data: JSON.stringify(position),
      })
      const balance = position.shareCount * position.withdrawalPriceEur
      const total = beforeDashboard.totalNetWorth + balance - beforeAccount.currentBalanceEur
      const [account, accountHistory, history, netHistory, pnl, scpiPnl, intraday, dashboard] = await Promise.all([
        get<Account>('/accounts/13'),
        get<{ date: string; balance: number }[]>('/accounts/13/history'),
        get<HistoryPoint[]>('/history', { accountIds: '13', split: true }),
        get<HistoryPoint[]>('/history', { accountIds }),
        get<PnlData>('/history/pnl'),
        get<PnlData>('/history/pnl', { accountIds: '13' }),
        get<IntradayPoint[]>('/history/net-worth/intraday', { accountIds: '13' }),
        get<DashboardData>('/dashboard'),
      ])

      expect(account.currentBalanceEur).toBe(balance)
      expect(accountHistory.at(-1)!.balance).toBe(balance)
      expect.soft(history.at(-1)!.total).toBe(balance)
      expect.soft(history.at(-1)!.accounts['13'].total).toBe(balance)
      expect.soft(history.at(-1)!.invested).toBe(balance)
      expect(history.slice(0, -1)).toEqual(beforeHistory.slice(0, -1))
      expect.soft(netHistory.at(-1)!.total).toBeCloseTo(total, 2)
      expect.soft(pnl.total).toBeCloseTo(total, 2)
      expect.soft(scpiPnl).toMatchObject({ total: balance, invested: balance, pnl: 0 })
      expect.soft(intraday.every(point => point.total === balance && point.invested === balance)).toBe(true)
      expect.soft(dashboard.totalNetWorth).toBeCloseTo(total, 2)
      expect.soft(dashboard.netWorthHistory.at(-1)!.total).toBeCloseTo(total, 2)
      expect.soft(dashboard.distribution.find(row => row.accountId === 13)!.balanceEur).toBe(balance)
      const gross = dashboard.distribution.reduce((sum, row) => sum + row.balanceEur, 0)
      expect.soft(dashboard.distribution.find(row => row.accountId === 13)!.percentage)
        .toBe(Math.round(balance / gross * 1000) / 10)
      expect(dashboard.totalLiabilities).toBe(beforeDashboard.totalLiabilities)
    }

    expect(beforeAccount.currentBalanceEur).toBe(8800)
    expect(beforeHistory.at(-1)!.total).toBe(8800)
    expect(beforeDashboard.distribution.find(row => row.accountId === 13)!.balanceEur).toBe(8800)
  })
})
