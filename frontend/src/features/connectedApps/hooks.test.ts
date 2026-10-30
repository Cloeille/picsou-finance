import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ConnectedApp } from './api'

const { list, revoke } = vi.hoisted(() => ({
  list: vi.fn(),
  revoke: vi.fn(),
}))
vi.mock('./api', () => ({
  connectedAppsApi: { list, revoke },
}))

const { useConnectedApps, useRevokeConnectedApp } = await import('./hooks')

const APPS: ConnectedApp[] = [
  {
    id: 'auth-1',
    clientName: 'claude.ai',
    scopes: ['accounts:read', 'transactions:read'],
    issuedAt: '2026-07-01T10:00:00Z',
    lastUsedAt: null,
  },
]

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

function makeClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } })
}

beforeEach(() => {
  list.mockReset()
  revoke.mockReset()
})

describe('useConnectedApps', () => {
  it('lists the caller\'s connected apps', async () => {
    list.mockResolvedValue(APPS)
    const queryClient = makeClient()

    const { result } = renderHook(() => useConnectedApps(), { wrapper: makeWrapper(queryClient) })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(APPS)
    expect(list).toHaveBeenCalledTimes(1)
  })
})

describe('useRevokeConnectedApp', () => {
  it('calls DELETE and invalidates the connectedApps query', async () => {
    list.mockResolvedValue(APPS)
    revoke.mockResolvedValue(undefined)
    const queryClient = makeClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = makeWrapper(queryClient)

    const listResult = renderHook(() => useConnectedApps(), { wrapper })
    await waitFor(() => expect(listResult.result.current.isSuccess).toBe(true))

    const { result } = renderHook(() => useRevokeConnectedApp(), { wrapper })
    result.current.mutate('auth-1')

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(revoke).toHaveBeenCalledWith('auth-1')
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['connectedApps'] })
  })
})
