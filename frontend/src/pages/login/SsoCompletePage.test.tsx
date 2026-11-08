import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// zustand stores read localStorage/sessionStorage at module eval (node/jsdom gap).
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { refresh } = vi.hoisted(() => ({ refresh: vi.fn() }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh } }))

const { SsoCompletePage } = await import('./SsoCompletePage')
const { useAuthStore } = await import('@/stores/auth-store')

const USER = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

function LoginMarker() {
  const location = useLocation()
  return <div>login-page {location.search}</div>
}

function renderComplete(entry: string, opts: { strict?: boolean } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  queryClient.setQueryData(['seed'], { cached: true })
  const tree = (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/login/sso-complete" element={<SsoCompletePage />} />
          <Route path="/settings" element={<div>settings-page</div>} />
          <Route path="/login" element={<LoginMarker />} />
          <Route path="/" element={<div>dashboard-page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
  render(opts.strict ? <StrictMode>{tree}</StrictMode> : tree)
  return queryClient
}

/** jsdom implements neither location.assign nor location.replace; swap in spies, restore after. */
function stubLocationNavigation() {
  const assign = vi.fn()
  const replace = vi.fn()
  const original = window.location
  Object.defineProperty(window, 'location', {
    value: { assign, replace, href: 'http://localhost/' },
    configurable: true,
    writable: true,
  })
  return {
    assign,
    replace,
    restore: () => Object.defineProperty(window, 'location', { value: original, configurable: true }),
  }
}

describe('SsoCompletePage', () => {
  beforeEach(() => {
    refresh.mockReset()
    sessionStorage.clear()
    localStorage.clear()
    useAuthStore.setState({ user: null, isAuthenticated: false })
  })

  it('resets the client state, signs the user in and navigates to the redirect target', async () => {
    refresh.mockResolvedValue(USER)

    const queryClient = renderComplete('/login/sso-complete?redirect=%2Fsettings')

    expect(await screen.findByText('settings-page')).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().user).toEqual(USER)
    // resetClientState ran: no pre-login cache may survive into the new session.
    expect(queryClient.getQueryData(['seed'])).toBeUndefined()
  })

  it('hands an /oauth2/ target back to the backend with a full-page navigation that replaces this page', async () => {
    refresh.mockResolvedValue(USER)
    const { assign, replace, restore } = stubLocationNavigation()
    try {
      renderComplete('/login/sso-complete?redirect=%2Foauth2%2Fauthorize%3Fclient_id%3Dx')

      // Replaced, not pushed: Back must not return to the spent progress screen.
      await waitFor(() => expect(replace).toHaveBeenCalledWith('/oauth2/authorize?client_id=x'))
      expect(assign).not.toHaveBeenCalled()
      expect(refresh).toHaveBeenCalledTimes(1)
      expect(useAuthStore.getState().user).toEqual(USER)
      // No SPA navigation: the authorize endpoint is server-handled.
      expect(screen.queryByText('settings-page')).not.toBeInTheDocument()
      expect(screen.queryByText('dashboard-page')).not.toBeInTheDocument()
      expect(screen.queryByText(/login-page/)).not.toBeInTheDocument()
    } finally {
      restore()
    }
  })

  it.each([
    ['%2Fsettings', '?ssoError=SESSION_EXPIRED&redirect=%2Fsettings'],
    ['%2Foauth2%2Fauthorize%3Fclient_id%3Dx', '?ssoError=SESSION_EXPIRED&redirect=%2Foauth2%2Fauthorize%3Fclient_id%3Dx'],
    ['', '?ssoError=SESSION_EXPIRED'],
  ])('lands on /login with SESSION_EXPIRED, keeping the target %s, when the probe fails', async (redirect, search) => {
    useAuthStore.getState().login(USER)
    refresh.mockRejectedValue(new Error('401'))

    renderComplete(`/login/sso-complete${redirect ? `?redirect=${redirect}` : ''}`)

    expect((await screen.findByText(/login-page/)).textContent).toBe(`login-page ${search}`)
    expect(useAuthStore.getState().user).toBeNull()
    expect(sessionStorage.getItem('picsou_user')).toBeNull()
  })

  it('probes the session exactly once under React StrictMode', async () => {
    refresh.mockResolvedValue(USER)

    renderComplete('/login/sso-complete', { strict: true })

    // No redirect parameter: the fallback target is the dashboard.
    expect(await screen.findByText('dashboard-page')).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
  })
})
