import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import type { DashboardData } from '@/types/api'

const { holdings, prices, list, updateScpiPosition, get, accountHistory } = vi.hoisted(() => ({
  holdings: vi.fn(),
  prices: vi.fn(),
  list: vi.fn(),
  updateScpiPosition: vi.fn(),
  get: vi.fn(),
  accountHistory: vi.fn(),
}))

const { getHistory, getPnl } = vi.hoisted(() => ({
  getHistory: vi.fn(),
  getPnl: vi.fn(),
}))

const { dashboardGet, dashboardGetPnl, dashboardGetIntraday } = vi.hoisted(() => ({
  dashboardGet: vi.fn(),
  dashboardGetPnl: vi.fn(),
  dashboardGetIntraday: vi.fn(),
}))

vi.mock('./api', () => ({
  accountsApi: { holdings, prices, list, updateScpiPosition, get, history: accountHistory },
}))

vi.mock('@/features/history/api', () => ({ historyApi: { getHistory, getPnl } }))
vi.mock('@/features/dashboard/api', () => ({
  dashboardApi: { get: dashboardGet, getPnl: dashboardGetPnl, getIntraday: dashboardGetIntraday },
}))

const { useAccount, useAccountHistory, useHoldingsWithLivePrices, usePortfolio, useUpdateScpiPosition } = await import('./hooks')
const { useHistory, usePnl } = await import('@/features/history/hooks')
const { useDashboard, usePnl: useDashboardPnl } = await import('@/features/dashboard/hooks')

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

afterEach(() => {
  vi.useRealTimers()
})

describe('useHoldingsWithLivePrices', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
    get.mockReset()
    get.mockResolvedValue({ id: 42, name: 'CTO', type: 'COMPTE_TITRES' })
  })

  it('keeps the backend crypto-only valuation of a token sharing a stock symbol', async () => {
    get.mockResolvedValue({ id: 42, name: 'Finary wallet', type: 'CRYPTO' })
    holdings.mockResolvedValue([{
      ticker: 'REG',
      name: 'RealToken',
      quantity: 10,
      averageBuyIn: 1,
      currentPrice: 1,
      quoteCurrency: 'EUR',
      currentValueEur: 10,
      costBasisEur: 10,
      pnlEur: 0,
      pnlPercent: 0,
      priceUpdatedAt: '2026-10-31T08:00:00Z',
      priceAsOf: null,
      priceStale: false,
    }])
    prices.mockResolvedValue({ REG: 60 })

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({
      currentPrice: 1,
      currentValueEur: 10,
      costBasisEur: 10,
      pnlEur: 0,
    })
  })

  it('uses the backend EUR cost basis when enriching a foreign quote', async () => {
    holdings.mockResolvedValue([
      {
        ticker: 'US',
        name: 'US share',
        quantity: 2,
        averageBuyIn: 80,
        currentPrice: 100,
        quoteCurrency: 'USD',
        currentValueEur: 180,
        costBasisEur: 140,
        pnlEur: 40,
        pnlPercent: 28.57,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
        priceAsOf: '2026-07-19',
        priceStale: true,
      },
    ])
    prices.mockResolvedValue({ US: 95 })

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]).toMatchObject({
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 190,
      costBasisEur: 140,
      pnlEur: 50,
      priceAsOf: null,
      priceStale: false,
    })
    expect(result.current.data?.[0].pnlPercent).toBeCloseTo(35.714, 3)
  })

  it('keeps the backend stale NAV value and date when no live quote is returned', async () => {
    holdings.mockResolvedValue([{
      ticker: 'EURO-FUND',
      name: 'PER euro fund',
      quantity: 10,
      averageBuyIn: null,
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 950,
      costBasisEur: null,
      pnlEur: null,
      pnlPercent: null,
      priceUpdatedAt: '2026-08-18T08:00:00Z',
      priceAsOf: '2026-08-18',
      priceStale: true,
    }])
    prices.mockResolvedValue({})

    const { result } = renderHook(() => useHoldingsWithLivePrices(42), {
      wrapper: makeWrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({
      quantity: 10,
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 950,
      priceAsOf: '2026-08-18',
      priceStale: true,
    })
  })
})

describe('useHoldingsWithLivePrices price polling', () => {
  it('refreshes balances, history, and P&L only when the live quote changes', async () => {
    for (const mock of [holdings, prices, get, accountHistory, getHistory, getPnl]) mock.mockReset()
    vi.useFakeTimers()
    const liveQuote = { value: undefined as number | undefined }
    const balance = () => liveQuote.value == null ? 950 : liveQuote.value * 10
    const account = {
      id: 42,
      name: 'PER',
      type: 'PER',
      color: '#000',
      currency: 'EUR',
      currentBalance: 950,
      currentBalanceEur: 950,
      cashBalance: 0,
      lastSyncedAt: null,
      isManual: false,
      provider: null,
      ticker: null,
      logoUrl: null,
      logoKey: null,
      createdAt: '2026-08-01T00:00:00Z',
      hidden: false,
    }

    get.mockImplementation(async () => ({ ...account, currentBalanceEur: balance() }))
    holdings.mockResolvedValue([{
      ticker: 'EURO-FUND',
      name: 'PER euro fund',
      logoUrl: null,
      quantity: 10,
      averageBuyIn: null,
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 950,
      costBasisEur: null,
      pnlEur: null,
      pnlPercent: null,
      priceUpdatedAt: '2026-08-18T08:00:00Z',
      priceAsOf: '2026-08-18',
      priceStale: true,
    }])
    prices.mockImplementation(async () => liveQuote.value == null ? {} : { 'EURO-FUND': liveQuote.value })
    accountHistory.mockImplementation(async () => [{ id: 1, date: '2026-08-19', balance: balance() }])
    getHistory.mockImplementation(async (accountIds: number[]) => {
      const total = accountIds.includes(42) ? balance() : 500
      return [{ date: '2026-08-19', total, invested: 900, pnl: total - 900 }]
    })
    getPnl.mockImplementation(async (accountIds: number[]) => {
      const total = accountIds.includes(42) ? balance() : 500
      return { total, invested: 900, pnl: total - 900, pnlPercent: ((total - 900) / 900) * 100 }
    })

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => {
      const account = useAccount(42)
      const holdings = useHoldingsWithLivePrices(42)
      const accountHistory = useAccountHistory(42)
      const history = useHistory([42])
      const pnl = usePnl([42])
      const otherHistory = useHistory([99])
      const otherPnl = usePnl([99])
      return {
        account: account.data,
        accountIsSuccess: account.isSuccess,
        holdings: holdings.data,
        holdingsIsSuccess: holdings.isSuccess,
        accountHistory: accountHistory.data,
        accountHistoryIsSuccess: accountHistory.isSuccess,
        history: history.data,
        historyIsSuccess: history.isSuccess,
        pnl: pnl.data,
        pnlIsSuccess: pnl.isSuccess,
        otherHistory: otherHistory.data,
        otherHistoryIsSuccess: otherHistory.isSuccess,
        otherPnl: otherPnl.data,
        otherPnlIsSuccess: otherPnl.isSuccess,
      }
    }, { wrapper })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(result.current.holdingsIsSuccess).toBe(true)
    expect(result.current.accountIsSuccess).toBe(true)
    expect(result.current.accountHistoryIsSuccess).toBe(true)
    expect(result.current.historyIsSuccess).toBe(true)
    expect(result.current.pnlIsSuccess).toBe(true)
    expect(result.current.otherHistoryIsSuccess).toBe(true)
    expect(result.current.otherPnlIsSuccess).toBe(true)

    liveQuote.value = 110
    await act(async () => {
      await vi.advanceTimersByTimeAsync(QUERY_STALE_TIMES.accountDetail)
    })
    await vi.waitFor(() => {
      expect(result.current.account?.currentBalanceEur).toBe(1100)
      expect(result.current.accountHistory?.[0].balance).toBe(1100)
      expect(result.current.history?.[0].total).toBe(1100)
      expect(result.current.pnl?.total).toBe(1100)
    })
    expect(result.current.account?.currentBalanceEur).toBe(1100)
    expect(result.current.holdings?.[0]).toMatchObject({
      currentPrice: 110,
      currentValueEur: 1100,
      quoteCurrency: 'EUR',
      priceAsOf: null,
      priceStale: false,
    })
    expect(result.current.accountHistory?.[0].balance).toBe(1100)
    expect(result.current.history?.[0].total).toBe(1100)
    expect(result.current.pnl?.total).toBe(1100)
    expect(result.current.otherHistory?.[0].total).toBe(500)
    expect(result.current.otherPnl?.total).toBe(500)

    expect(holdings).toHaveBeenCalledTimes(2)
    expect(get).toHaveBeenCalledTimes(2)
    expect(accountHistory).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(3)
    expect(getPnl).toHaveBeenCalledTimes(3)
    expect(getHistory.mock.calls.filter(([ids]) => ids.includes(99))).toHaveLength(1)
    expect(getPnl.mock.calls.filter(([ids]) => ids.includes(99))).toHaveLength(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(QUERY_STALE_TIMES.accountDetail)
    })
    expect(holdings).toHaveBeenCalledTimes(3)
    expect(get).toHaveBeenCalledTimes(2)
    expect(accountHistory).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(3)
    expect(getPnl).toHaveBeenCalledTimes(3)
  })
})

describe('usePortfolio', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
    list.mockReset()
    getHistory.mockReset()
    getPnl.mockReset()
    dashboardGet.mockReset()
    dashboardGetPnl.mockReset()
    dashboardGetIntraday.mockReset()
  })

  const brokerage = {
    id: 1,
    name: 'PEA',
    type: 'PEA',
    color: '#000',
    currentBalanceEur: 1000,
  }

  it('quotes a stock live but keeps the crypto-only value of a token with the same symbol', async () => {
    const wallet = { id: 2, name: 'Finary wallet', type: 'CRYPTO', color: '#000', currentBalanceEur: 10 }
    list.mockResolvedValue([brokerage, wallet])
    const line = (name: string, price: number, quantity: number) => ({
      ticker: 'REG',
      name,
      quantity,
      averageBuyIn: price,
      currentPrice: price,
      quoteCurrency: 'EUR',
      currentValueEur: price * quantity,
      costBasisEur: price * quantity,
      pnlEur: 0,
      pnlPercent: 0,
      priceUpdatedAt: '2026-10-31T08:00:00Z',
    })
    holdings.mockImplementation(async (id: number) => id === wallet.id
      ? [line('RealToken', 1, 10)]
      : [line('Regency Centers', 55, 2)])
    prices.mockResolvedValue({ REG: 60 })

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const byAccount = (id: number) => result.current.data?.find(l => l.accountId === id)
    expect(byAccount(brokerage.id)).toMatchObject({ valueEur: 120, pnlEur: 10 })
    expect(byAccount(wallet.id)).toMatchObject({ valueEur: 10, costBasisEur: 10, pnlEur: 0 })
  })

  it('fails the query when an account\'s holdings cannot be loaded', async () => {
    list.mockResolvedValue([brokerage])
    holdings.mockRejectedValue(new Error('boom'))
    prices.mockResolvedValue({})

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.data).toBeUndefined()
  })

  it('keeps backend values when only the live-price call fails', async () => {
    list.mockResolvedValue([brokerage])
    holdings.mockResolvedValue([
      {
        ticker: 'AAA',
        name: 'Fund',
        quantity: 1,
        averageBuyIn: 10,
        currentPrice: 12,
        quoteCurrency: 'EUR',
        currentValueEur: 12,
        costBasisEur: 10,
        pnlEur: 2,
        pnlPercent: 20,
        priceUpdatedAt: '2026-07-20T08:00:00Z',
      },
    ])
    prices.mockRejectedValue(new Error('price provider down'))

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.[0]).toMatchObject({ valueEur: 12, pnlEur: 2 })
  })

  it('keeps the fallback NAV date, value, and currency when no quote is returned', async () => {
    list.mockResolvedValue([{
      id: 8,
      name: 'PER Caisse d’Epargne',
      type: 'PER',
      color: '#000',
      currentBalanceEur: 950,
    }])
    holdings.mockResolvedValue([{
      ticker: 'EURO-FUND',
      name: 'PER euro fund',
      quantity: 10,
      averageBuyIn: null,
      currentPrice: 95,
      quoteCurrency: 'EUR',
      currentValueEur: 950,
      costBasisEur: null,
      pnlEur: null,
      pnlPercent: null,
      priceUpdatedAt: '2026-08-19T08:00:00Z',
      priceAsOf: '2026-08-18',
      priceStale: true,
    }])
    prices.mockResolvedValue({})

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.find(line => line.ticker === 'EURO-FUND')).toMatchObject({
      valueEur: 950,
      quoteCurrency: 'EUR',
      priceUpdatedAt: '2026-08-19T08:00:00Z',
      priceAsOf: '2026-08-18',
      priceStale: true,
    })
  })

  it('clears stale NAV metadata and recomputes the value when a live quote is returned', async () => {
    list.mockResolvedValue([{
      id: 9,
      name: 'PER Caisse d’Epargne',
      type: 'PER',
      color: '#000',
      currentBalanceEur: 950,
    }])
    holdings.mockResolvedValue([{
      ticker: 'EURO-FUND',
      name: 'PER euro fund',
      quantity: 10,
      averageBuyIn: null,
      currentPrice: 95,
      quoteCurrency: 'USD',
      currentValueEur: 950,
      costBasisEur: 800,
      pnlEur: 150,
      pnlPercent: 18.75,
      priceUpdatedAt: '2026-08-19T08:00:00Z',
      priceAsOf: '2026-08-18',
      priceStale: true,
    }])
    prices.mockResolvedValue({ 'EURO-FUND': 110 })

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const line = result.current.data?.find(item => item.ticker === 'EURO-FUND')
    expect(line).toMatchObject({
      valueEur: 1100,
      quoteCurrency: 'EUR',
      pnlEur: 300,
      pnlPercent: 37.5,
      priceAsOf: null,
      priceStale: false,
    })
    expect(line?.priceUpdatedAt).not.toBe('2026-08-19T08:00:00Z')
  })

  it('includes the PER euro fund alongside units without double-counting the account total', async () => {
    list.mockResolvedValue([
      {
        id: 5,
        name: 'PER Caisse d’Epargne',
        type: 'PER',
        color: '#000',
        currentBalanceEur: 820,
        cashBalance: 220,
      },
      { id: 6, name: 'Checking', type: 'CHECKING', color: '#000', currentBalanceEur: 1500 },
    ])
    holdings.mockResolvedValue([
      {
        ticker: 'UC-1',
        name: 'Unit-linked fund',
        quantity: 6,
        averageBuyIn: null,
        currentPrice: 100,
        quoteCurrency: 'EUR',
        currentValueEur: 600,
        costBasisEur: null,
        pnlEur: null,
        pnlPercent: null,
        priceUpdatedAt: '2026-08-19T08:00:00Z',
        priceAsOf: null,
        priceStale: false,
      },
    ])
    prices.mockResolvedValue({})

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const lines = result.current.data ?? []
    expect(lines).toEqual(expect.arrayContaining([
      expect.objectContaining({ id: '5-UC-1', accountId: 5, valueEur: 600 }),
      expect.objectContaining({
        accountId: 5,
        accountName: 'PER Caisse d’Epargne',
        accountType: 'PER',
        ticker: 'EUR',
        isCash: true,
        valueEur: 220,
        costBasisEur: null,
        pnlEur: null,
        pnlPercent: null,
      }),
      expect.objectContaining({
        id: 'cash-aggregated',
        accountId: null,
        accountName: 'Checking',
        accountIds: [6],
        isCash: true,
        valueEur: 1500,
      }),
    ]))
    expect(lines.reduce((sum, line) => sum + line.valueEur, 0)).toBe(2320)
    expect(prices).toHaveBeenCalledWith(['UC-1'])
  })

  it('includes a PER euro fund balance when there are no unit-linked holdings', async () => {
    list.mockResolvedValue([{
      id: 7,
      name: 'PER Caisse d’Epargne',
      type: 'PER',
      color: '#000',
      currentBalanceEur: 350,
      cashBalance: 350,
    }])
    holdings.mockResolvedValue([])

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toHaveLength(1)
    expect(result.current.data?.[0]).toMatchObject({ accountId: 7, ticker: 'EUR', isCash: true, valueEur: 350 })
    expect(prices).not.toHaveBeenCalled()
  })

  it('leaves loans and credit cards out of the Euros cash line', async () => {
    list.mockResolvedValue([
      { id: 2, name: 'Checking', type: 'CHECKING', color: '#000', currentBalanceEur: 1500 },
      { id: 3, name: 'Mortgage', type: 'LOAN', color: '#000', currentBalanceEur: 90000 },
      { id: 4, name: 'Card', type: 'CREDIT_CARD', color: '#000', currentBalanceEur: -800 },
    ])

    const { result } = renderHook(() => usePortfolio(), { wrapper: makeWrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual([
      expect.objectContaining({ id: 'cash-aggregated', accountName: 'Checking', valueEur: 1500 }),
    ])
  })

  it('refreshes mounted dashboard, history and PnL queries only when a polled value changes', async () => {
    vi.useFakeTimers()
    let livePrice = 10
    list.mockResolvedValue([brokerage])
    holdings.mockResolvedValue([{
      ticker: 'AAA',
      name: 'Fund',
      quantity: 1,
      averageBuyIn: 10,
      currentPrice: 10,
      quoteCurrency: 'EUR',
      currentValueEur: 10,
      costBasisEur: 10,
      pnlEur: 0,
      pnlPercent: 0,
      priceUpdatedAt: '2026-08-19T08:00:00Z',
    }])
    prices.mockImplementation(async () => ({ AAA: livePrice }))
    dashboardGet.mockImplementation(async () => ({
      totalNetWorth: livePrice,
      totalLiabilities: 0,
      netWorthHistory: [],
      distribution: [],
      liabilities: [],
      goalSummaries: [],
    } satisfies DashboardData))
    dashboardGetPnl.mockImplementation(async (accountIds: number[]) => ({
      total: accountIds.includes(1) ? livePrice : 1,
      invested: 10,
      pnl: livePrice - 10,
      pnlPercent: 0,
      valueAtFrom: 10,
      rangePnl: livePrice - 10,
      rangePnlPercent: 0,
    }))
    getHistory.mockImplementation(async (accountIds: number[]) => ([{
      date: '2026-08-19',
      total: accountIds.includes(1) ? livePrice : 1,
    }]))
    getPnl.mockImplementation(async (accountIds: number[]) => ({
      total: accountIds.includes(1) ? livePrice : 1,
    }))

    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => ({
      portfolio: usePortfolio(),
      dashboard: useDashboard('1Y'),
      history: useHistory([1]),
      unrelatedHistory: useHistory([99]),
      dashboardPnl: useDashboardPnl([1], '2026-01-01'),
      unrelatedDashboardPnl: useDashboardPnl([99], '2026-01-01'),
      historyPnl: usePnl([1]),
      unrelatedHistoryPnl: usePnl([99]),
    }), { wrapper })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(result.current.portfolio.data?.[0].valueEur).toBe(10)
    expect(result.current.dashboard.data?.totalNetWorth).toBe(10)
    expect(result.current.history.data?.[0].total).toBe(10)
    expect(result.current.dashboardPnl.data?.total).toBe(10)
    expect(result.current.historyPnl.data?.total).toBe(10)
    expect(result.current.unrelatedHistory.data).toBeDefined()
    expect(result.current.unrelatedDashboardPnl.data).toBeDefined()
    expect(result.current.unrelatedHistoryPnl.data).toBeDefined()
    expect(dashboardGet).toHaveBeenCalledTimes(1)
    expect(getHistory).toHaveBeenCalledTimes(2)
    expect(dashboardGetPnl).toHaveBeenCalledTimes(2)
    expect(getPnl).toHaveBeenCalledTimes(2)

    livePrice = 12
    await act(async () => {
      await vi.advanceTimersByTimeAsync(QUERY_STALE_TIMES.accountDetail)
    })
    await vi.waitFor(() => {
      expect(result.current.portfolio.data?.[0].valueEur).toBe(12)
      expect(result.current.dashboard.data?.totalNetWorth).toBe(12)
      expect(result.current.history.data?.[0].total).toBe(12)
      expect(result.current.dashboardPnl.data?.total).toBe(12)
      expect(result.current.historyPnl.data?.total).toBe(12)
    })
    expect(dashboardGet).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(3)
    expect(dashboardGetPnl).toHaveBeenCalledTimes(3)
    expect(getPnl).toHaveBeenCalledTimes(3)
    expect(result.current.unrelatedHistory.data?.[0].total).toBe(1)
    expect(result.current.unrelatedDashboardPnl.data?.total).toBe(1)
    expect(result.current.unrelatedHistoryPnl.data?.total).toBe(1)
    expect(list).toHaveBeenCalledTimes(2)
    expect(holdings).toHaveBeenCalledTimes(2)
    expect(prices).toHaveBeenCalledTimes(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(QUERY_STALE_TIMES.accountDetail)
    })
    await vi.waitFor(() => expect(list).toHaveBeenCalledTimes(3))
    expect(holdings).toHaveBeenCalledTimes(3)
    expect(prices).toHaveBeenCalledTimes(3)
    expect(dashboardGet).toHaveBeenCalledTimes(2)
    expect(getHistory).toHaveBeenCalledTimes(3)
    expect(dashboardGetPnl).toHaveBeenCalledTimes(3)
    expect(getPnl).toHaveBeenCalledTimes(3)
  })
})

describe('useUpdateScpiPosition', () => {
  it('refreshes all account-derived views after a manual SCPI correction', async () => {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const keys = [
      ['accounts'], ['accounts', 42], ['real-estate', 'summary'],
      ['dashboard'], ['analysis', 'wealth-pyramid'], ['dividends', 'upcoming', 60],
      ['accounts', 42, 'history'], ['history', [42], 12, true],
      ['pnl', [42]], ['pnl', [13, 42], '2026-10-01'], ['net-worth-intraday', [42]],
    ]
    const unrelatedKeys = [
      ['history', [13], 12, true], ['pnl', [13]], ['net-worth-intraday', [13]],
    ]
    keys.forEach(key => client.setQueryData(key, { previous: true }))
    unrelatedKeys.forEach(key => client.setQueryData(key, { previous: true }))
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    updateScpiPosition.mockResolvedValue({})
    const { result } = renderHook(() => useUpdateScpiPosition(), { wrapper })
    const data = { shareCount: 12, corumFundCode: '', sofidyFundCode: 'DY' }
    await act(() => result.current.mutateAsync({ id: 42, data }))

    expect(updateScpiPosition).toHaveBeenCalledWith(42, data)
    for (const key of keys) {
      expect(client.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(true)
    }
    for (const key of unrelatedKeys) {
      expect(client.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(false)
    }
  })
})
