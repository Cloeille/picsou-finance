import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'

const actualBudgetApi = vi.hoisted(() => ({ preview: vi.fn(), execute: vi.fn() }))

vi.mock('./api', () => ({ actualBudgetApi }))

const { useImportActualBudget } = await import('./hooks')

describe('useImportActualBudget', () => {
  it('refreshes every view an import can change, categories included', async () => {
    actualBudgetApi.execute.mockResolvedValue({})
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const invalidations = vi.spyOn(client, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    )
    const { result } = renderHook(() => useImportActualBudget(), { wrapper })

    await act(() => result.current.mutateAsync({ fileToken: 't', currency: 'EUR', accountMappings: [], categoryMappings: [] }))

    expect(invalidations.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      ['accounts'], ['categories'], ['budget'], ['dashboard'], ['history'], ['analysis'],
    ])
  })
})
