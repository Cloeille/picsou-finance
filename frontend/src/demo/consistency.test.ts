import { AxiosHeaders } from 'axios'
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PnlData, IntradayPoint } from '@/features/dashboard/api'
import type {
  Account,
  Budget,
  BudgetSettings,
  CashflowResponse,
  LoanScheduleResponse,
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
  ['the first day of a year', 'CYCLE', new Date(2027, 0, 1, 9)],
  ['the first day of a year', 'YTD', new Date(2027, 0, 1, 9)],
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

  it('derives cashflow totals and every bucket from the dated ledger', async () => {
    const cashflow = await get<CashflowResponse>('/cashflow', { period })
    const income = sum(ledger.filter(tx => tx.amount > 0).map(tx => tx.amount))
    expect(cashflow.from).toBe(spending.from)
    expect(cashflow.to).toBe(spending.to)
    expect(cashflow.income).toBe(income)
    expect(cashflow.expense).toBe(spending.totalExpense)
    expect(cashflow.net).toBe(sum([income, -spending.totalExpense]))
    expect(cashflow.series.length).toBeGreaterThan(0)
    for (const bucket of cashflow.series) {
      expect(bucket.start >= cashflow.from && bucket.end <= cashflow.to).toBe(true)
      const rows = ledger.filter(tx => tx.date >= bucket.start && tx.date <= bucket.end)
      expect(bucket.income, bucket.start).toBe(sum(rows.filter(tx => tx.amount > 0).map(tx => tx.amount)))
      expect(bucket.expense, bucket.start).toBe(sum(rows.filter(tx => tx.amount < 0).map(tx => -tx.amount)))
      expect(bucket.net, bucket.start).toBe(sum([bucket.income, -bucket.expense]))
    }
    for (const tx of ledger) {
      expect(cashflow.series.filter(b => tx.date >= b.start && tx.date <= b.end), String(tx.id)).toHaveLength(1)
    }
    expect(sum(cashflow.series.map(b => b.income))).toBe(cashflow.income)
    expect(sum(cashflow.series.map(b => b.expense))).toBe(cashflow.expense)
  })

  it('serves the same checking-account transactions in global and account views', async () => {
    const range = { from: spending.from, to: spending.to }
    const global = await get<Transaction[]>('/transactions', { ...range, accountId: 4 })
    const account = await get<Transaction[]>('/accounts/4/transactions', range)
    expect(global.length).toBeGreaterThan(0)
    expect(global.some(tx => tx.amount === 3200)).toBe(true)
    expect(account.map(tx => [tx.id, tx.amount, tx.categoryId])).toEqual(global.map(tx => [tx.id, tx.amount, tx.categoryId]))
    expect(account.every(tx => tx.date >= range.from && tx.date <= range.to)).toBe(true)
    expect(await get('/accounts/4/transactions', { from: '2000-01-01', to: '2000-01-31' })).toEqual([])
  })

  it('dates no ledger row after today', () => {
    const todayIso = today.toLocaleDateString('sv-SE')
    for (const tx of ledger) expect(tx.date <= todayIso, tx.description).toBe(true)
  })
})

describe.each([
  ['October 31', new Date(2026, 9, 31, 12)],
  ['November 1', new Date(2026, 10, 1, 12)],
  ['January 1', new Date(2027, 0, 1, 12)],
])('demo loan history on %s', (_label, today) => {
  it('matches every dated installment in the account and net-worth histories', async () => {
    const get = await demoAt(today)
    const loans = (await get<Account[]>('/accounts')).filter(a => a.type === 'LOAN')
    expect(loans.length).toBeGreaterThan(0)
    for (const loan of loans) {
      const { schedule } = await get<LoanScheduleResponse>(`/accounts/${loan.id}/loan-summary`)
      const history = await get<{ date: string; balance: number }[]>(`/accounts/${loan.id}/history`)
      const netWorth = await get<{ date: string; total: number }[]>('/history', { accountIds: String(loan.id), months: 12 })
      expect(history.length).toBeGreaterThan(1)
      for (const point of history) {
        const installment = schedule.filter(row => row.date <= point.date).at(-1)
        expect(installment, point.date).toBeDefined()
        expect(point.balance, `${loan.id} on ${point.date}`).toBe(installment?.remainingBalance)
        expect(netWorth.find(row => row.date === point.date)?.total).toBe(-point.balance)
      }
    }
  })
})

async function categorize(id: number | string, categoryId: unknown) {
  const { createDemoAdapter } = await import('./index')
  await createDemoAdapter()({
    method: 'PUT', url: `/transactions/${id}/category`, headers: new AxiosHeaders(),
    data: JSON.stringify({ categoryId, createRule: false }),
  })
}

describe('demo categorization updates every budget view', () => {
  let get: Get

  beforeEach(async () => {
    get = await demoAt(new Date(2026, 9, 31, 12))
  })

  it.each([7, 12, 3, 8])('files an inbox row under category %i across every endpoint', async categoryId => {
    const before = await get<UncategorizedTransaction[]>('/transactions/uncategorized')
    const row = before.find(tx => tx.id === 9001)
    expect(row).toBeDefined()
    if (!row) throw new Error('Expected demo inbox row 9001')
    const range = { from: '2026-10-01', to: '2026-10-31' }
    const initialLedger = await get<Transaction[]>('/transactions', range)
    const previous = initialLedger.find(tx => tx.id === row.id)
    const category = (await get<{ id: number; name: string }[]>('/categories')).find(c => c.id === categoryId)
    expect(category).toBeDefined()

    await categorize(row.id, categoryId)

    const inbox = await get<UncategorizedTransaction[]>('/transactions/uncategorized')
    expect(inbox).not.toBe(before)
    expect(inbox).toEqual(before.filter(tx => tx.id !== row.id))
    expect(before).toContain(row)
    expect(previous?.categoryId).toBeNull()
    const ledger = await get<Transaction[]>('/transactions', range)
    expect(ledger.find(tx => tx.id === row.id)).toMatchObject({ categoryId, category: category?.name })
    expect((await get<Transaction[]>('/accounts/4/transactions', range)).find(tx => tx.id === row.id))
      .toMatchObject({ categoryId, category: category?.name })

    for (const period of ['CYCLE', 'YTD']) {
      const spending = await get<SpendingByCategoryResponse>('/spending/by-category', { period })
      const transactions = await get<Transaction[]>('/transactions', { from: spending.from, to: spending.to })
      const expenses = transactions.filter(tx => tx.amount < 0 && tx.categoryId !== 8)
      expect(spending.totalExpense).toBe(sum(expenses.map(tx => -tx.amount)))
      expect(spending.categories.find(c => c.categoryId == null)?.amount).toBe(sum(inbox.map(tx => -tx.amount)))
      expect(spending.categories.find(c => c.categoryId == null)?.count).toBe(inbox.length)
      const assigned = expenses.filter(tx => tx.categoryId === categoryId)
      const summary = spending.categories.find(c => c.categoryId === categoryId)
      if (categoryId === 8) {
        expect(summary).toBeUndefined()
      } else {
        expect(summary?.amount).toBe(sum(assigned.map(tx => -tx.amount)))
        expect(summary?.count).toBe(assigned.length)
      }
      const drill = await get<SpendingDetailResponse>(`/spending/category/${categoryId}`, { period })
      const expected = transactions.filter(tx => tx.categoryId === categoryId ||
        (categoryId === 3 && (tx.categoryId === 10 || tx.categoryId === 11)))
      expect(drill.transactions.map(tx => tx.id).sort()).toEqual(expected.map(tx => tx.id).sort())
      expect(drill.count).toBe(expected.length)
      expect(drill.total).toBe(sum(expected.map(tx => tx.amount)))
      const flow = await get<CashflowFlowResponse>('/cashflow/flow', { period })
      const cashflow = await get<CashflowResponse>('/cashflow', { period })
      expect(flow.expense).toBe(spending.totalExpense)
      expect(cashflow.expense).toBe(spending.totalExpense)
      const nodeIndex = flow.nodes.findIndex(n => n.key === `cat:${categoryId}` && n.type === 'EXPENSE')
      if (categoryId === 8) expect(nodeIndex).toBe(-1)
      else expect(sum(flow.links.filter(link => link.target === nodeIndex).map(link => link.value)))
        .toBe(summary?.amount)
    }

    const budgets = await get<Budget[]>('/budgets')
    const cycleExpenses = ledger.filter(tx => tx.amount < 0 && tx.categoryId !== 8)
    for (const budget of budgets) {
      const rows = cycleExpenses.filter(tx => tx.categoryId === budget.categoryId ||
        (budget.categoryId === 3 && (tx.categoryId === 10 || tx.categoryId === 11)))
      expect(budget.spent).toBe(sum(rows.map(tx => -tx.amount)))
      expect(budget.remaining).toBe(sum([budget.monthlyLimit, -budget.spent]))
    }
  }, 15_000)

  it.each([
    [999999, 7], ['invalid', 7], [9001, 999999], [9001, null], [9001, '7'],
  ] as const)('leaves the ledger unchanged for invalid transaction %s or category %s', async (id, categoryId) => {
    const before = await get<Transaction[]>('/transactions')
    const inbox = await get<UncategorizedTransaction[]>('/transactions/uncategorized')
    await categorize(id, categoryId)
    expect(await get('/transactions')).toEqual(before)
    expect(await get('/transactions/uncategorized')).toEqual(inbox)
  })
})
