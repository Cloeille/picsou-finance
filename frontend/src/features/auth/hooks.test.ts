import { describe, it, expect, beforeEach, vi } from 'vitest'
import { createElement, type ReactNode } from 'react'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// zustand's profile-store uses `persist`, which reads localStorage at module
// eval; jsdom doesn't provide one. Install in-memory shims BEFORE the stores are
// (dynamically) imported below — same constraint as api-client.test.ts.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: k => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: k => void m.delete(k),
    clear: () => m.clear(),
    key: i => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { logout } = vi.hoisted(() => ({ logout: vi.fn() }))
vi.mock('./api', () => ({
  authApi: { logout },
}))

const { useLogout } = await import('./hooks')
const { useAuthStore } = await import('@/stores/auth-store')

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloé' })
  logout.mockReset()
})

describe('useLogout (server-confirmed logout only)', () => {
  it('clears local auth state and the query cache when the server call succeeds', async () => {
    logout.mockResolvedValue(undefined)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    queryClient.setQueryData(['session-probe'], { username: 'chloe' })

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
    await act(async () => {
      await result.current.mutateAsync()
    })

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().user).toBeNull()
    // resetClientState() -> queryClient.clear() also drops the cached session-probe
    // result, so a future RequireAuth mount can't replay a stale "authenticated" probe.
    expect(queryClient.getQueryData(['session-probe'])).toBeUndefined()
  })

  it('does NOT clear local auth state when the server call fails', async () => {
    // The session cookies are still valid server-side if /auth/logout failed --
    // presenting a "logged out" UI here would let RequireAuth's session-probe
    // silently re-authenticate the user right back in on the next mount.
    logout.mockRejectedValue(new Error('network error'))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
    act(() => {
      result.current.mutate()
    })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().user).toMatchObject({ username: 'chloe' })
  })
})
