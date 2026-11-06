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

const { logout, refresh } = vi.hoisted(() => ({ logout: vi.fn(), refresh: vi.fn() }))
vi.mock('./api', () => ({
  authApi: { logout, refresh },
}))

const { useLogout, useSessionProbe } = await import('./hooks')
const { useAuthStore } = await import('@/stores/auth-store')
const { consumeSsoRevalidate } = await import('@/lib/sso-revalidate')

function makeWrapper(queryClient: QueryClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

beforeEach(() => {
  sessionStorage.clear()
  useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloé' })
  logout.mockReset()
  refresh.mockReset()
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

  it('navigates to the IdP end-session URL when the server returns one', async () => {
    const assign = vi.fn()
    const original = window.location
    Object.defineProperty(window, 'location', {
      value: { assign, href: 'http://localhost/' },
      configurable: true,
      writable: true,
    })
    try {
      logout.mockResolvedValue({ endSessionUrl: 'http://idp.example/endsession?id_token_hint=x' })
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

      const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
      await act(async () => {
        await result.current.mutateAsync()
      })

      // Local logout still happened first: the IdP redirect never strands a live local session.
      expect(useAuthStore.getState().isAuthenticated).toBe(false)
      expect(assign).toHaveBeenCalledWith('http://idp.example/endsession?id_token_hint=x')
    } finally {
      Object.defineProperty(window, 'location', { value: original, configurable: true })
    }
  })

  it('does not navigate when the server returns no end-session URL', async () => {
    const assign = vi.fn()
    const original = window.location
    Object.defineProperty(window, 'location', {
      value: { assign, href: 'http://localhost/' },
      configurable: true,
      writable: true,
    })
    try {
      logout.mockResolvedValue({})
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

      const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
      await act(async () => {
        await result.current.mutateAsync()
      })

      expect(assign).not.toHaveBeenCalled()
    } finally {
      Object.defineProperty(window, 'location', { value: original, configurable: true })
    }
  })

  it.each(['javascript:alert(1)', 'data:text/html,<p>x</p>'])(
    'signs out locally without following the end-session URL %s',
    async (endSessionUrl) => {
      const assign = vi.fn()
      const original = window.location
      Object.defineProperty(window, 'location', {
        value: { assign, href: 'http://localhost/' },
        configurable: true,
        writable: true,
      })
      try {
        logout.mockResolvedValue({ endSessionUrl })
        const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

        const { result } = renderHook(() => useLogout(), { wrapper: makeWrapper(queryClient) })
        await act(async () => {
          await result.current.mutateAsync()
        })

        expect(useAuthStore.getState().isAuthenticated).toBe(false)
        expect(assign).not.toHaveBeenCalled()
      } finally {
        Object.defineProperty(window, 'location', { value: original, configurable: true })
      }
    },
  )
})

describe('useSessionProbe SSO revalidation marking', () => {
  it('marks the revalidation flag when the refresh fails with SSO_REVALIDATE', async () => {
    refresh.mockRejectedValue({ response: { status: 401, data: { code: 'SSO_REVALIDATE' } } })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => useSessionProbe(true), { wrapper: makeWrapper(queryClient) })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(consumeSsoRevalidate()).toBe(true)
  })

  it('does not mark the flag when the refresh fails for another reason', async () => {
    refresh.mockRejectedValue({ response: { status: 401, data: { code: 'SESSION_EXPIRED' } } })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => useSessionProbe(true), { wrapper: makeWrapper(queryClient) })
    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(consumeSsoRevalidate()).toBe(false)
  })

  it('does not mark the flag when the refresh succeeds', async () => {
    refresh.mockResolvedValue({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloé' })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    const { result } = renderHook(() => useSessionProbe(true), { wrapper: makeWrapper(queryClient) })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(consumeSsoRevalidate()).toBe(false)
  })
})
