import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (key) => m.get(key) ?? null,
    setItem: (key, value) => void m.set(key, String(value)),
    removeItem: (key) => void m.delete(key),
    clear: () => m.clear(),
    key: (index) => [...m.keys()][index] ?? null,
    get length() { return m.size },
  } as Storage
}

vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const { refresh } = vi.hoisted(() => ({ refresh: vi.fn() }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh } }))

const { PublicOnly } = await import('./guards')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const USER = { username: 'alice', role: 'ADMIN', memberId: 1, displayName: 'Alice' }

function renderPublicOnly(probe = true) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/login']}>
        <PublicOnly probe={probe}>
          <div>login-form</div>
        </PublicOnly>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PublicOnly session probe', () => {
  beforeEach(() => {
    refresh.mockReset()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    useAppStore.setState({ demoMode: false })
  })

  it('shows the login form when no session is restorable', async () => {
    refresh.mockRejectedValue(new Error('401'))

    renderPublicOnly()

    await waitFor(() => expect(screen.getByText('login-form')).toBeInTheDocument())
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('holds the form and rehydrates the session when the cookie is still valid', async () => {
    refresh.mockResolvedValue(USER)

    renderPublicOnly()

    // The login form must never flash while a restorable session is being probed.
    expect(screen.queryByText('login-form')).not.toBeInTheDocument()
    await waitFor(() => expect(useAuthStore.getState().isAuthenticated).toBe(true))
    expect(screen.queryByText('login-form')).not.toBeInTheDocument()
  })

  it('skips the probe on the MFA challenge page (probe=false)', () => {
    renderPublicOnly(false)

    expect(screen.getByText('login-form')).toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()
  })

  it('redirects an already-authenticated visitor without probing', () => {
    useAuthStore.setState({ user: USER, isAuthenticated: true })

    renderPublicOnly()

    expect(screen.queryByText('login-form')).not.toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()
  })
})
