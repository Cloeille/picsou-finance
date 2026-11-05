import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'

// Real-English lookup mock: resolves t() against en.json so a wrong key path fails
// the assertion instead of echoing the key (interpolation mirrors i18next's {{x}}).
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      const value = key
        .split('.')
        .reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      if (!opts) return str
      return Object.entries(opts).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
  }),
}))

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

const { useSsoInfo } = vi.hoisted(() => ({ useSsoInfo: vi.fn() }))
vi.mock('@/features/sso/hooks', () => ({ useSsoInfo }))

const { submitSsoStart } = vi.hoisted(() => ({ submitSsoStart: vi.fn() }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart }))

const { loginMutation } = vi.hoisted(() => ({
  loginMutation: { mutateAsync: vi.fn(), isPending: false },
}))
vi.mock('@/features/mfa/hooks', () => ({ useLoginWithRememberMe: () => loginMutation }))

const { LoginPage } = await import('./LoginPage')
const { useAppStore } = await import('@/stores/app-store')
const { consumeSsoRevalidate, markSsoRevalidate, rememberSsoPreference, ssoRememberPreference } =
  await import('@/lib/sso-revalidate')

const SSO_ENABLED = { data: { enabled: true, providerName: 'SSO' }, isSuccess: true, isError: false }
const SSO_DISABLED = { data: { enabled: false, providerName: 'SSO' }, isSuccess: true, isError: false }

function renderLogin(entry = '/login', opts: { strict?: boolean } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const ui = () => {
    const tree = (
      <QueryClientProvider client={qc}>
        <MemoryRouter initialEntries={[entry]}>
          <LoginPage />
        </MemoryRouter>
      </QueryClientProvider>
    )
    return opts.strict ? <StrictMode>{tree}</StrictMode> : tree
  }
  const view = render(ui())
  return { ...view, rerenderSame: () => view.rerender(ui()) }
}

describe('LoginPage SSO', () => {
  beforeEach(() => {
    useSsoInfo.mockReset()
    submitSsoStart.mockReset()
    loginMutation.mutateAsync.mockReset()
    loginMutation.isPending = false
    sessionStorage.clear()
    localStorage.clear()
    useAppStore.setState({ demoMode: false })
  })

  it('shows the SSO button with a divider when SSO is enabled', () => {
    useSsoInfo.mockReturnValue(SSO_ENABLED)

    renderLogin()

    expect(screen.getByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument()
    expect(screen.getByText('or')).toBeInTheDocument()
    expect(useSsoInfo).toHaveBeenCalledWith(true)
    // The password form stays: SSO is an alternative, not a replacement.
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })

  it('hides the SSO button when SSO is disabled on the instance', () => {
    useSsoInfo.mockReturnValue(SSO_DISABLED)

    renderLogin()

    expect(screen.queryByRole('button', { name: /Sign in with/ })).not.toBeInTheDocument()
  })

  it('hides the SSO button in demo mode and disables the SSO info query', () => {
    useAppStore.setState({ demoMode: true })
    useSsoInfo.mockReturnValue(SSO_ENABLED)

    renderLogin()

    expect(screen.queryByRole('button', { name: /Sign in with/ })).not.toBeInTheDocument()
    expect(useSsoInfo).toHaveBeenCalledWith(false)
  })

  it('starts SSO with the sanitized redirect and stores the Remember me choice', () => {
    useSsoInfo.mockReturnValue(SSO_ENABLED)

    renderLogin('/login?redirect=%2F%2Fevil.example')
    fireEvent.click(screen.getByLabelText('Remember me'))
    fireEvent.click(screen.getByRole('button', { name: 'Sign in with SSO' }))

    expect(submitSsoStart).toHaveBeenCalledTimes(1)
    expect(submitSsoStart).toHaveBeenCalledWith({
      intent: 'login',
      redirect: '/',
      rememberMe: true,
    })
    expect(ssoRememberPreference()).toBe(true)
  })

  it('says when the sign-in options fail to load and retries on request', () => {
    const refetch = vi.fn()
    useSsoInfo.mockReturnValue({ data: undefined, isSuccess: false, isError: true, refetch })

    const view = renderLogin()
    fireEvent.click(screen.getByRole('button', { name: en.common.retry }))

    expect(screen.getByText(en.auth.sso.infoError)).toBeInTheDocument()
    expect(refetch).toHaveBeenCalledTimes(1)
    useSsoInfo.mockReturnValue(SSO_ENABLED)
    view.rerenderSame()
    expect(screen.queryByText(en.auth.sso.infoError)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument()
  })

  it('shows the translated SSO error from the query parameter, never the raw code', () => {
    useSsoInfo.mockReturnValue(SSO_DISABLED)

    renderLogin('/login?ssoError=NOT_LINKED')

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(en.ssoErrors.NOT_LINKED)
    expect(alert).not.toHaveTextContent('NOT_LINKED')
  })

  it('falls back to the generic SSO error for an unknown code', () => {
    useSsoInfo.mockReturnValue(SSO_DISABLED)

    renderLogin('/login?ssoError=BOGUS_CODE')

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(en.ssoErrors.generic)
    expect(alert).not.toHaveTextContent('BOGUS_CODE')
  })

  describe('silent re-validation', () => {
    it('restarts SSO exactly once when the flag is set and SSO is enabled', async () => {
      markSsoRevalidate()
      rememberSsoPreference(true)
      useSsoInfo.mockReturnValue(SSO_ENABLED)

      renderLogin('/login?redirect=%2Foauth2%2Fauthorize%3Fclient_id%3Dx', { strict: true })

      await waitFor(() => expect(submitSsoStart).toHaveBeenCalledTimes(1))
      expect(submitSsoStart).toHaveBeenCalledWith({
        intent: 'login',
        redirect: '/oauth2/authorize?client_id=x',
        rememberMe: true,
      })
      expect(screen.getByText(en.auth.sso.redirecting)).toBeInTheDocument()
      // The page consumed the flag: a later mount must not restart again.
      expect(consumeSsoRevalidate()).toBe(false)
    })

    it('does not restart when the revalidation flag is not set', () => {
      useSsoInfo.mockReturnValue(SSO_ENABLED)

      renderLogin()

      expect(submitSsoStart).not.toHaveBeenCalled()
      expect(screen.queryByText(en.auth.sso.redirecting)).not.toBeInTheDocument()
    })

    it('consumes the flag without restarting when SSO is disabled', () => {
      markSsoRevalidate()
      useSsoInfo.mockReturnValue(SSO_DISABLED)

      renderLogin()

      expect(consumeSsoRevalidate()).toBe(false)
      expect(submitSsoStart).not.toHaveBeenCalled()
    })

    it('consumes the flag without restarting when the SSO info query fails', () => {
      markSsoRevalidate()
      useSsoInfo.mockReturnValue({ data: undefined, isSuccess: false, isError: true })

      renderLogin()

      expect(consumeSsoRevalidate()).toBe(false)
      expect(submitSsoStart).not.toHaveBeenCalled()
    })

    it('keeps the flag until the SSO info has answered', () => {
      markSsoRevalidate()
      useSsoInfo.mockReturnValue({ data: undefined, isSuccess: false, isError: false })

      const view = renderLogin()
      expect(submitSsoStart).not.toHaveBeenCalled()

      useSsoInfo.mockReturnValue(SSO_ENABLED)
      view.rerenderSame()

      expect(submitSsoStart).toHaveBeenCalledTimes(1)
      expect(consumeSsoRevalidate()).toBe(false)
    })

    it('shows the form again when the browser restores the page from the back/forward cache', () => {
      markSsoRevalidate()
      useSsoInfo.mockReturnValue(SSO_ENABLED)

      renderLogin()
      expect(screen.getByText(en.auth.sso.redirecting)).toBeInTheDocument()

      act(() => {
        window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
      })

      expect(screen.queryByText(en.auth.sso.redirecting)).not.toBeInTheDocument()
      expect(screen.getByLabelText('Username')).toBeInTheDocument()
    })
  })
})
