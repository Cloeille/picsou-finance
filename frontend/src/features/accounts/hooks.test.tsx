import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { holdings, prices, list } = vi.hoisted(() => ({
  holdings: vi.fn(),
  prices: vi.fn(),
  list: vi.fn(),
}))

vi.mock('./api', () => ({
  accountsApi: { holdings, prices, list },
}))

const { useHoldingsWithLivePrices, usePortfolio } = await import('./hooks')

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useHoldingsWithLivePrices', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
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
    })
    expect(result.current.data?.[0].pnlPercent).toBeCloseTo(35.714, 3)
  })
})

describe('usePortfolio', () => {
  beforeEach(() => {
    holdings.mockReset()
    prices.mockReset()
    list.mockReset()
  })

  const brokerage = {
    id: 1,
    name: 'PEA',
    type: 'PEA',
    color: '#000',
    currentBalanceEur: 1000,
  }

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
})
