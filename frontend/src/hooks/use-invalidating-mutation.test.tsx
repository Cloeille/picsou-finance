import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { useInvalidatingMutation } from './use-invalidating-mutation'

function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { client, wrapper, invalidations: vi.spyOn(client, 'invalidateQueries') }
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>(res => {
    resolve = res
  })
  return { promise, resolve }
}

describe('useInvalidatingMutation', () => {
  it('invalidates the listed keys after success', async () => {
    const { wrapper, invalidations } = setup()
    const { result } = renderHook(
      () => useInvalidatingMutation({ mutationFn: async () => 'ok', invalidates: [['accounts'], ['dashboard']] }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync())

    expect(invalidations.mock.calls.map(([filters]) => filters)).toEqual([
      { queryKey: ['accounts'] },
      { queryKey: ['dashboard'] },
    ])
  })

  it('derives the keys from the result and the variables', async () => {
    const { wrapper, invalidations } = setup()
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: async (id: number) => ({ id }),
          invalidates: (data, id) => [['accounts', data.id], ['history', id]],
        }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync(7))

    expect(invalidations.mock.calls.map(([filters]) => filters)).toEqual([
      { queryKey: ['accounts', 7] },
      { queryKey: ['history', 7] },
    ])
  })

  it('does not invalidate when the call fails', async () => {
    const { wrapper, invalidations } = setup()
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: () => Promise.reject(new Error('boom')),
          invalidates: [['accounts']],
        }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync().catch(() => undefined))

    expect(invalidations).not.toHaveBeenCalled()
  })

  it('writes the response to writesTo before invalidating it', async () => {
    const { client, wrapper } = setup()
    const key = ['sync', 'cic']
    let writtenAtInvalidation: unknown
    vi.spyOn(client, 'invalidateQueries').mockImplementation(() => {
      writtenAtInvalidation = client.getQueryData(key)
      return Promise.resolve()
    })
    const { result } = renderHook(
      () => useInvalidatingMutation({ mutationFn: async () => ({ status: 'QUEUED' }), writesTo: key, invalidates: [key] }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync())

    expect(client.getQueryData(key)).toEqual({ status: 'QUEUED' })
    expect(writtenAtInvalidation).toEqual({ status: 'QUEUED' })
  })

  it("runs the caller's own onSuccess before invalidating", async () => {
    const { wrapper, invalidations } = setup()
    const order: string[] = []
    invalidations.mockImplementation(() => {
      order.push('invalidate')
      return Promise.resolve()
    })
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: async () => 'ok',
          onSuccess: () => {
            order.push('own')
          },
          invalidates: [['accounts']],
        }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync())

    expect(order).toEqual(['own', 'invalidate'])
  })

  it('does not wait for the refetch by default', async () => {
    const { wrapper, invalidations } = setup()
    const refetch = deferred()
    invalidations.mockReturnValue(refetch.promise)
    const { result } = renderHook(
      () => useInvalidatingMutation({ mutationFn: async () => 'ok', invalidates: [['accounts']] }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync())

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    refetch.resolve()
  })

  it('holds the mutation open until the refetch ends with awaitRefresh', async () => {
    const { wrapper, invalidations } = setup()
    const refetch = deferred()
    invalidations.mockReturnValue(refetch.promise)
    const { result } = renderHook(
      () => useInvalidatingMutation({ mutationFn: async () => 'ok', invalidates: [['accounts']], awaitRefresh: true }),
      { wrapper },
    )

    let done = false
    let call!: Promise<unknown>
    act(() => {
      call = result.current.mutateAsync().then(() => {
        done = true
      })
    })
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(done).toBe(false)
    await waitFor(() => expect(result.current.isPending).toBe(true))

    await act(async () => {
      refetch.resolve()
      await call
    })
    expect(done).toBe(true)
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('resolves only once an active query has really refetched with awaitRefresh', async () => {
    const { client, wrapper } = setup()
    const refetch = deferred()
    let fetches = 0
    const { result } = renderHook(
      () => ({
        query: useQuery({
          queryKey: ['accounts'],
          queryFn: async () => {
            fetches += 1
            if (fetches === 1) return 'stale'
            await refetch.promise
            return 'fresh'
          },
        }),
        mutation: useInvalidatingMutation({
          mutationFn: async () => 'ok',
          invalidates: [['accounts']],
          awaitRefresh: true,
        }),
      }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.query.data).toBe('stale'))

    let done = false
    let call!: Promise<unknown>
    act(() => {
      call = result.current.mutation.mutateAsync().then(() => {
        done = true
      })
    })
    await waitFor(() => expect(client.isFetching({ queryKey: ['accounts'] })).toBe(1))
    expect(done).toBe(false)

    await act(async () => {
      refetch.resolve()
      await call
    })

    expect(done).toBe(true)
    expect(client.getQueryData(['accounts'])).toBe('fresh')
    const state = client.getQueryState(['accounts'])
    expect(state?.fetchStatus).toBe('idle')
    expect(state?.isInvalidated).toBe(false)
  })

  it('rejects a key function with when: settled instead of invalidating nothing', () => {
    const { wrapper } = setup()
    const options = {
      mutationFn: () => Promise.resolve(1),
      when: 'settled',
      invalidates: () => [['accounts']],
    } as unknown as Parameters<typeof useInvalidatingMutation>[0]
    vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(() => renderHook(() => useInvalidatingMutation(options), { wrapper })).toThrow(
      "with when: 'settled', invalidates must be a list of keys",
    )
  })

  it('invalidates on error too with when: settled', async () => {
    const { wrapper, invalidations } = setup()
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: () => Promise.reject(new Error('boom')),
          when: 'settled',
          invalidates: [['sync', 'amex']],
        }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync().catch(() => undefined))

    expect(invalidations).toHaveBeenCalledWith({ queryKey: ['sync', 'amex'] })
  })

  it('invalidates once on success with when: settled and keeps the own onSettled', async () => {
    const { wrapper, invalidations } = setup()
    const onSettled = vi.fn()
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: async () => 'ok',
          when: 'settled',
          invalidates: [['sync', 'amex']],
          onSettled,
        }),
      { wrapper },
    )

    await act(() => result.current.mutateAsync(undefined))

    expect(invalidations).toHaveBeenCalledTimes(1)
    expect(onSettled).toHaveBeenCalledOnce()
  })

  it('holds a settled mutation open with awaitRefresh', async () => {
    const { wrapper, invalidations } = setup()
    const refetch = deferred()
    invalidations.mockReturnValue(refetch.promise)
    const { result } = renderHook(
      () =>
        useInvalidatingMutation({
          mutationFn: async () => 'ok',
          when: 'settled',
          invalidates: [['sync', 'amex']],
          awaitRefresh: true,
        }),
      { wrapper },
    )

    let done = false
    let call!: Promise<unknown>
    act(() => {
      call = result.current.mutateAsync().then(() => {
        done = true
      })
    })
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    await waitFor(() => expect(result.current.isPending).toBe(true))
    expect(done).toBe(false)

    await act(async () => {
      refetch.resolve()
      await call
    })
    expect(done).toBe(true)
  })
})
