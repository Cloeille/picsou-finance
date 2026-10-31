import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
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

const { PublicOnly, RequireAuth, RequireAdmin } = await import('./guards')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const USER = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

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

  it('shows a centred spinner, not a page skeleton, while the probe is pending', async () => {
    refresh.mockReturnValue(new Promise(() => {}))

    const { container } = renderPublicOnly()

    expect(container.querySelector('[data-slot="skeleton"]')).toBeNull()
    expect(await screen.findByRole('status')).toHaveAccessibleName('common.loading')
    expect(container.querySelector('[data-slot="skeleton"]')).toBeNull()
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

  it('redirects in demo mode without probing', () => {
    useAppStore.setState({ demoMode: true })

    renderPublicOnly()

    expect(screen.queryByText('login-form')).not.toBeInTheDocument()
    expect(refresh).not.toHaveBeenCalled()
  })
})

describe('RequireAuth in demo mode', () => {
  beforeEach(() => {
    refresh.mockReset()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    useAppStore.setState({ demoMode: true })
  })

  it('opens the app at once and signs the demo user in from the session probe', async () => {
    refresh.mockResolvedValue(USER)
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/settings']}>
          <RequireAuth><div>app</div></RequireAuth>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.getByText('app')).toBeInTheDocument()
    await waitFor(() => expect(useAuthStore.getState().user).toEqual(USER))
  })
})

describe.each([true, false])('fresh admin navigation with demoMode=%s', (demoMode) => {
  beforeEach(() => {
    refresh.mockReset()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    useAppStore.setState({ demoMode })
  })

  function renderAdmin() {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    return render(
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={['/admin']}>
          <Routes>
            <Route path="/admin" element={<RequireAuth><RequireAdmin><div>admin-page</div></RequireAdmin></RequireAuth>} />
            <Route path="/login" element={<PublicOnly><div>login-form</div></PublicOnly>} />
            <Route path="/" element={<div>dashboard-page</div>} />
            <Route path="/error/403" element={<div>forbidden-page</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
  }

  it('waits for the session and keeps an admin on the requested page', async () => {
    let resolve!: (user: typeof USER) => void
    refresh.mockReturnValue(new Promise<typeof USER>((done) => { resolve = done }))

    renderAdmin()

    expect(await screen.findByRole('status')).toHaveAccessibleName('common.loading')
    expect(screen.queryByText('admin-page')).not.toBeInTheDocument()
    expect(screen.queryByText('dashboard-page')).not.toBeInTheDocument()
    await act(async () => { resolve(USER) })
    expect(await screen.findByText('admin-page')).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().user).toEqual(USER)
  })

  it('denies admin access when the restored user is a member', async () => {
    refresh.mockResolvedValue({ ...USER, role: 'MEMBER' })

    renderAdmin()

    expect(await screen.findByText('forbidden-page')).toBeInTheDocument()
    expect(screen.queryByText('admin-page')).not.toBeInTheDocument()
  })

  it('leaves the admin route if the session probe fails', async () => {
    refresh.mockRejectedValue(new Error('401'))

    renderAdmin()

    expect(await screen.findByText(demoMode ? 'dashboard-page' : 'login-form')).toBeInTheDocument()
    expect(screen.queryByText('admin-page')).not.toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })
})
