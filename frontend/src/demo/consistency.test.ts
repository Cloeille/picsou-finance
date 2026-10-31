import { AxiosHeaders } from 'axios'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import type { PnlData, IntradayPoint } from '@/features/dashboard/api'
import type {
  BudgetSettings,
  DashboardData,
  SpendingByCategoryResponse,
  SpendingDetailResponse,
  Transaction,
  UncategorizedTransaction,
} from '@/types/api'

vi.stubEnv('TZ', 'Europe/Paris')

/** A fresh demo adapter whose fixtures were built with `today` as the date. */
async function demoAt(today: Date) {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(today)
  vi.resetModules()
  const { createDemoAdapter } = await import('./index')
  const adapter = createDemoAdapter()
  return async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
    const response = await adapter({ method: 'GET', url, params, headers: new AxiosHeaders() })
    return response.data as T
  }
}

type Get = Awaited<ReturnType<typeof demoAt>>
const sum = (amounts: number[]) => Math.round(amounts.reduce((a, b) => a + b, 0) * 100) / 100

afterAll(() => {
  vi.useRealTimers()
})

describe('demo dashboard figures follow the selected accounts', () => {
  let get: Get

  beforeAll(async () => {
    get = await demoAt(new Date(2026, 10, 1, 9))
  })

  it.each([
    ['the PEA, securities and crypto accounts', '2,3,6'],
    ['the checking account alone', '4'],
  ])('reports the P&L of %s as /history does', async (_label, accountIds) => {
    const [pnl, history] = await Promise.all([
      get<PnlData>('/history/pnl', { accountIds, from: '2026-10-01' }),
      get<{ total: number; invested: number; pnl: number }[]>('/history', { accountIds, months: 1 }),
    ])
    const latest = history[history.length - 1]
    expect(pnl.total).toBeCloseTo(latest.total, 2)
    expect(pnl.invested).toBeCloseTo(latest.invested, 2)
    expect(pnl.pnl).toBeCloseTo(latest.pnl, 2)
    expect(pnl.pnlPercent).toBe(Math.round((latest.pnl / latest.invested) * 1000) / 10)
  })

  it('reports no range move for accounts that hold nothing', async () => {
    const pnl = await get<PnlData>('/history/pnl', { accountIds: '4', from: '2026-10-01' })
    expect(pnl.rangePnl ?? null).toBeNull()
    expect(pnl.rangePnlPercent ?? null).toBeNull()
  })

  it('computes the dashboard P&L over the investment accounts the dashboard asks for', async () => {
    const dashboard = await get<DashboardData>('/dashboard')
    const accountIds = dashboard.distribution.filter(d => d.hasHoldings).map(d => d.accountId).join(',')
    const [pnl, history] = await Promise.all([
      get<PnlData>('/history/pnl', { accountIds, from: '2026-10-01' }),
      get<{ total: number; invested: number }[]>('/history', { accountIds, months: 1 }),
    ])
    expect(pnl.invested).toBeCloseTo(history[0].invested, 2)
    expect(pnl.total).toBeCloseTo(history[0].total, 2)
  })

  it('ends the 24H gross-wealth chart on the gross wealth the header shows', async () => {
    const dashboard = await get<DashboardData>('/dashboard')
    // DashboardPage's gross mode: every distribution row, no liabilities.
    const grossIds = dashboard.distribution.map(d => d.accountId)
    const grossWealth = sum(dashboard.distribution.map(d => d.balanceEur))
    const intraday = await get<IntradayPoint[]>('/history/net-worth/intraday', { accountIds: grossIds.join(',') })
    expect(intraday[intraday.length - 1].total).toBeCloseTo(grossWealth, 2)
  })
})

describe.each([
  ['the first day of a month', new Date(2026, 10, 1, 9)],
  ['the last day of a month', new Date(2026, 9, 31, 18)],
])('demo budget on %s', (_label, today) => {
  let get: Get
  let settings: BudgetSettings
  let ledger: Transaction[]
  let spending: SpendingByCategoryResponse

  beforeAll(async () => {
    get = await demoAt(today)
    settings = await get<BudgetSettings>('/budget/settings')
    const from = settings.currentCycleStart
    const to = settings.currentCycleEnd
    ;[ledger, spending] = await Promise.all([
      get<Transaction[]>('/transactions', { from, to }),
      get<SpendingByCategoryResponse>('/spending/by-category', { period: 'CYCLE' }),
    ])
  })

  it('lists in the cycle ledger every expense the spending summary counts', () => {
    const expenses = ledger.filter(tx => tx.amount < 0)
    expect(sum(expenses.map(tx => -tx.amount))).toBe(spending.totalExpense)
    expect(expenses.length).toBe(sum(spending.categories.map(c => c.count)))
  })

  it('drills into the same rows the ledger filters by category', async () => {
    for (const row of spending.categories) {
      if (row.categoryId == null) continue
      const [drill, filtered] = await Promise.all([
        get<SpendingDetailResponse>(`/spending/category/${row.categoryId}`, { period: 'CYCLE' }),
        get<Transaction[]>('/transactions', {
          from: settings.currentCycleStart, to: settings.currentCycleEnd, categoryId: row.categoryId,
        }),
      ])
      expect(drill.transactions.every(tx => tx.date >= drill.from && tx.date <= drill.to), row.name!).toBe(true)
      expect(filtered.map(tx => tx.id).sort(), row.name!).toEqual(drill.transactions.map(tx => tx.id).sort())
      expect(sum(filtered.map(tx => -tx.amount)), row.name!).toBe(row.amount)
    }
  })

  it('counts the review inbox as the uncategorized spending', async () => {
    const inbox = await get<UncategorizedTransaction[]>('/transactions/uncategorized')
    const uncategorized = spending.categories.find(c => c.categoryId == null)!
    expect(uncategorized.count).toBe(inbox.length)
    expect(uncategorized.amount).toBe(sum(inbox.map(tx => -tx.amount)))
    const ledgerUncategorized = ledger.filter(tx => tx.categoryId == null)
    expect(ledgerUncategorized.map(tx => tx.id).sort()).toEqual(inbox.map(tx => tx.id).sort())
  })

  it('dates no ledger row after today', () => {
    const todayIso = today.toLocaleDateString('sv-SE')
    for (const tx of ledger) expect(tx.date <= todayIso, tx.description).toBe(true)
  })
})
