import { AxiosHeaders } from 'axios'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import type { PnlData, IntradayPoint } from '@/features/dashboard/api'
import type {
  Account,
  BudgetSettings,
  CashflowFlowResponse,
  HoldingResponse,
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
])('demo investment accounts on %s are valued from their holdings', (_label, today) => {
  const investmentIds = [2, 3, 6]
  const from = '2026-10-01'
  const todayIso = today.toLocaleDateString('sv-SE')
  let get: Get
  let holdings: HoldingResponse[][]
  let tickers: string[]
  let quotes: Record<string, number>
  let quotesAtFrom: Record<string, number>

  beforeAll(async () => {
    get = await demoAt(today)
    holdings = await Promise.all(investmentIds.map(id => get<HoldingResponse[]>(`/accounts/${id}/holdings`)))
    tickers = [...new Set(holdings.flat().map(h => h.ticker))]
    quotes = await get<Record<string, number>>('/prices', { tickers: tickers.join(',') })
    const series = await Promise.all(tickers.map(t =>
      get<{ date: string; priceEur: number }[]>(`/prices/${t}/history`, { months: 2 })))
    quotesAtFrom = Object.fromEntries(tickers.map((t, i) => [t, series[i].find(p => p.date === from)!.priceEur]))
  })

  const valueAt = (lines: HoldingResponse[], prices: Record<string, number>) =>
    sum(lines.map(h => h.quantity * prices[h.ticker]))

  it('reports each account balance and its history as its holdings repriced', async () => {
    const accounts = await get<Account[]>('/accounts')
    for (const [i, id] of investmentIds.entries()) {
      const live = valueAt(holdings[i], quotes)
      expect(accounts.find(a => a.id === id)!.currentBalanceEur, `account ${id}`).toBeCloseTo(live, 2)
      const history = await get<{ date: string; balance: number }[]>(`/accounts/${id}/history`)
      expect(history[history.length - 1].date, `account ${id} latest`).toBe(todayIso)
      expect(history[history.length - 1].balance, `account ${id} latest`).toBeCloseTo(live, 2)
      expect(history.find(p => p.date === from)!.balance, `account ${id} on ${from}`)
        .toBeCloseTo(valueAt(holdings[i], quotesAtFrom), 2)
    }
  })

  it('dates the live net worth today and prices each month start on its own date', async () => {
    const history = await get<{ date: string; total: number }[]>('/history', {
      accountIds: investmentIds.join(','), months: 12,
    })
    expect(history[history.length - 1].date).toBe(todayIso)
    expect(history[history.length - 1].total).toBeCloseTo(valueAt(holdings.flat(), quotes), 2)
    expect(history.find(p => p.date === from)!.total).toBeCloseTo(valueAt(holdings.flat(), quotesAtFrom), 2)
  })

  it('reports P&L over the range from the same holdings and quotes', async () => {
    const pnl = await get<PnlData>('/history/pnl', { accountIds: investmentIds.join(','), from })
    const live = valueAt(holdings.flat(), quotes)
    const atFrom = valueAt(holdings.flat(), quotesAtFrom)
    expect(pnl.total).toBeCloseTo(live, 2)
    expect(pnl.invested).toBeCloseTo(sum(holdings.flat().map(h => h.costBasisEur ?? 0)), 2)
    expect(pnl.valueAtFrom).toBeCloseTo(atFrom, 2)
    expect(pnl.rangePnl).toBeCloseTo(live - atFrom, 2)
    expect(pnl.rangePnlPercent).toBe(Math.round(((live - atFrom) / atFrom) * 1000) / 10)
  })

  it('values the holdings at every hour of the 24H chart with that hour\'s quotes', async () => {
    const series = await Promise.all(tickers.map(t =>
      get<{ timestamp: string; priceEur: number }[]>(`/prices/${t}/intraday`)))
    const accounts = await get<Account[]>('/accounts')
    const balance = (id: number) => accounts.find(a => a.id === id)!.currentBalanceEur
    // The checking account and the car loan hold their live balance all day.
    const steady = balance(4) - balance(14)
    const intraday = await get<IntradayPoint[]>('/history/net-worth/intraday', {
      accountIds: [...investmentIds, 4, 14].join(','),
    })
    expect(intraday).toHaveLength(series[0].length)
    for (const [hour, point] of intraday.entries()) {
      const quotesThen = Object.fromEntries(tickers.map((t, i) => [t, series[i][hour].priceEur]))
      expect(series.every(s => s[hour].timestamp === point.timestamp), point.timestamp).toBe(true)
      expect(point.total, point.timestamp).toBeCloseTo(valueAt(holdings.flat(), quotesThen) + steady, 2)
    }
  })
})

describe.each([
  ['the first day of a month', 'CYCLE', new Date(2026, 10, 1, 9)],
  ['the last day of a month', 'CYCLE', new Date(2026, 9, 31, 18)],
  ['the first day of a month', 'YTD', new Date(2026, 10, 1, 9)],
  ['the last day of a month', 'YTD', new Date(2026, 9, 31, 18)],
] as const)('demo budget on %s over the %s period', (_label, period, today) => {
  let get: Get
  let settings: BudgetSettings
  let ledger: Transaction[]
  let spending: SpendingByCategoryResponse

  beforeAll(async () => {
    get = await demoAt(today)
    settings = await get<BudgetSettings>('/budget/settings')
    spending = await get<SpendingByCategoryResponse>('/spending/by-category', { period })
    ledger = await get<Transaction[]>('/transactions', { from: spending.from, to: spending.to })
  })

  it('summarizes the period the budget settings define', () => {
    const yearStart = `${today.getFullYear()}-01-01`
    expect(spending.from).toBe(period === 'CYCLE' ? settings.currentCycleStart : yearStart)
    expect(spending.to).toBe(period === 'CYCLE' ? settings.currentCycleEnd : today.toLocaleDateString('sv-SE'))
  })

  it('lists in the ledger every expense the spending summary and the flow count', async () => {
    const expenses = ledger.filter(tx => tx.amount < 0)
    expect(sum(expenses.map(tx => -tx.amount))).toBe(spending.totalExpense)
    expect(expenses.length).toBe(sum(spending.categories.map(c => c.count)))
    const flow = await get<CashflowFlowResponse>('/cashflow/flow', { period })
    expect(flow.expense).toBe(spending.totalExpense)
    expect(flow.income).toBe(sum(ledger.filter(tx => tx.amount > 0).map(tx => tx.amount)))
  })

  it('drills into the same rows the ledger filters by category', async () => {
    for (const row of spending.categories) {
      if (row.categoryId == null) continue
      const [drill, filtered] = await Promise.all([
        get<SpendingDetailResponse>(`/spending/category/${row.categoryId}`, { period }),
        get<Transaction[]>('/transactions', { from: spending.from, to: spending.to, categoryId: row.categoryId }),
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
