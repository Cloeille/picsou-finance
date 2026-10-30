import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { useUpcomingDividends } from './hooks'

const upcoming = vi.hoisted(() => vi.fn())

vi.mock('./api', () => ({
  dividendsApi: { upcoming },
}))

function clientWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

describe('useUpcomingDividends', () => {
  beforeEach(() => {
    upcoming.mockReset().mockResolvedValue([])
  })

  it('loads the default 60-day horizon under the dividends upcoming key', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useUpcomingDividends(), { wrapper: clientWrapper(client) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(upcoming).toHaveBeenCalledWith(60)
    expect(client.getQueryCache().find({ queryKey: ['dividends', 'upcoming', 60] })?.queryKey)
      .toEqual(['dividends', 'upcoming', 60])
    expect(client.getQueryState(['dividends', 'upcoming', 60])).toMatchObject({ status: 'success' })
    // staleTime lives on the observer options; the query type does not expose it.
    const observed = client.getQueryCache().find({ queryKey: ['dividends', 'upcoming', 60] })
      ?.observers[0]?.options.staleTime
    expect(observed).toBe(QUERY_STALE_TIMES.budget)
  })

  it('forwards a custom horizon in the query key', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(() => useUpcomingDividends(90), { wrapper: clientWrapper(client) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(upcoming).toHaveBeenCalledWith(90)
  })

  it('keeps an account forecast in its own cache entry and forwards the id', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { result } = renderHook(
      () => useUpcomingDividends({ horizonDays: 366, accountId: 12 }),
      { wrapper: clientWrapper(client) },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(upcoming).toHaveBeenCalledWith(366, 12)
    expect(client.getQueryCache().find({ queryKey: ['dividends', 'upcoming', 366, 12] })?.queryKey)
      .toEqual(['dividends', 'upcoming', 366, 12])
    // The household key must stay free of this account, or the budget card would reuse it.
    expect(client.getQueryCache().find({ queryKey: ['dividends', 'upcoming', 366] })).toBeUndefined()
  })
})
