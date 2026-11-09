import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'

// Real-English lookup: a wrong key path fails the assertion instead of echoing the key.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      const value = key
        .split('.')
        .reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      return Object.entries(opts ?? {}).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
  }),
}))

// zustand stores read localStorage/sessionStorage at module evaluation (node 26 jsdom gap).
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

const setup = vi.hoisted(() => ({ complete: vi.fn(), getStatus: vi.fn() }))
vi.mock('@/features/setup/api', () => ({ setupApi: setup }))
const auth = vi.hoisted(() => ({ refresh: vi.fn(), login: vi.fn() }))
vi.mock('@/features/auth/api', () => ({ authApi: auth }))

const { SetupStepComplete } = await import('./SetupStepComplete')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')
const { consumeSetupCredentials, stashSetupCredentials } = await import('@/stores/setup-credentials')

const USER = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

function deferred() {
  let resolve: () => void = () => {}
  const promise = new Promise<void>(r => { resolve = r })
  return { promise, resolve }
}

function renderDone() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/setup/done']}>
          <SetupStepComplete />
        </MemoryRouter>
      </QueryClientProvider>
    </StrictMode>,
  )
}

describe('SetupStepComplete', () => {
  beforeEach(() => {
    Object.values(setup).forEach(fn => fn.mockReset())
    Object.values(auth).forEach(fn => fn.mockReset())
    setup.getStatus.mockResolvedValue({ state: 'COMPLETE', needsSetup: false, integrations: {} })
    consumeSetupCredentials()
    sessionStorage.clear()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    // The one-time sidebar prompt is a modal: open, it would hide the page from role queries.
    useAppStore.setState({ hasSeenSidebarStylePrompt: true })
  })

  it('probes the SSO session only once /complete has resolved, then signs in', async () => {
    const completion = deferred()
    setup.complete.mockReturnValue(completion.promise)
    auth.refresh.mockResolvedValue(USER)
    renderDone()
    expect(await screen.findByText(en.setup.complete.completing)).toBeInTheDocument()
    expect(auth.refresh).not.toHaveBeenCalled()

    completion.resolve()

    expect(await screen.findByRole('button', { name: en.setup.complete.cta })).toBeInTheDocument()
    expect(auth.refresh).toHaveBeenCalledTimes(1)
    expect(setup.complete.mock.invocationCallOrder[0]).toBeLessThan(auth.refresh.mock.invocationCallOrder[0])
    expect(useAuthStore.getState().user).toEqual(USER)
    expect(setup.complete).toHaveBeenCalledTimes(1)
  })

  it('still probes the session when another tab already completed the setup (410)', async () => {
    setup.complete.mockRejectedValue({ response: { status: 410 } })
    auth.refresh.mockResolvedValue(USER)
    renderDone()

    expect(await screen.findByRole('button', { name: en.setup.complete.cta })).toBeInTheDocument()
    expect(useAuthStore.getState().user).toEqual(USER)
  })

  it('falls back to the login page without probing when /complete fails otherwise', async () => {
    setup.complete.mockRejectedValue({ response: { status: 500 } })
    renderDone()

    expect(await screen.findByRole('button', { name: en.setup.complete.loginFallback })).toBeInTheDocument()
    expect(auth.refresh).not.toHaveBeenCalled()
  })

  it('falls back to the login page when there is no session to probe', async () => {
    setup.complete.mockResolvedValue(undefined)
    auth.refresh.mockRejectedValue({ response: { status: 401 } })
    renderDone()

    expect(await screen.findByRole('button', { name: en.setup.complete.loginFallback })).toBeInTheDocument()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('signs a password admin in once with the stashed credentials, without probing the session', async () => {
    stashSetupCredentials('alice', 'correct-horse-1')
    setup.complete.mockResolvedValue(undefined)
    auth.login.mockResolvedValue(USER)
    renderDone()

    expect(await screen.findByRole('button', { name: en.setup.complete.cta })).toBeInTheDocument()
    expect(auth.login).toHaveBeenCalledTimes(1)
    expect(auth.login).toHaveBeenCalledWith('alice', 'correct-horse-1')
    expect(auth.refresh).not.toHaveBeenCalled()
  })
})
