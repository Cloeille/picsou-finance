import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

const { getSignInMethods, reauth, refresh, submitSsoStart } = vi.hoisted(() => ({
  getSignInMethods: vi.fn(),
  reauth: vi.fn(),
  refresh: vi.fn(),
  submitSsoStart: vi.fn(),
}))
vi.mock('@/features/sso/api', () => ({ ssoApi: { getSignInMethods, reauth } }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh } }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart }))

const { ReauthPanel } = await import('./ReauthPanel')

const METHODS = {
  hasPassword: true,
  mfaEnabled: false,
  identities: [],
  ssoEnabled: true,
  providerName: 'Zitadel',
  adminGroupManaged: false,
}

function renderPanel(methods: Partial<typeof METHODS>, action: Parameters<typeof ReauthPanel>[0]['action']) {
  getSignInMethods.mockResolvedValue({ ...METHODS, ...methods })
  const onConfirmed = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <ReauthPanel action={action} onConfirmed={onConfirmed} />
    </QueryClientProvider>,
  )
  return { onConfirmed }
}

function apiError(status: number, code?: string) {
  return { response: { status, data: code ? { code } : {} } }
}

describe('ReauthPanel', () => {
  beforeEach(() => {
    getSignInMethods.mockReset()
    reauth.mockReset()
    refresh.mockReset()
    submitSsoStart.mockReset()
  })

  it('asks for the password and the 2FA code, and confirms once the proof is minted', async () => {
    reauth.mockResolvedValue({})
    const { onConfirmed } = renderPanel({ mfaEnabled: true }, 'export')

    fireEvent.change(await screen.findByLabelText(en.settings.currentPassword), { target: { value: 's3cret' } })
    fireEvent.change(screen.getByLabelText(en.auth.mfaCodeLabel), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: en.reauth.confirm }))

    await waitFor(() => expect(onConfirmed).toHaveBeenCalledTimes(1))
    // TanStack passes a context as the mutation function's second argument: compare the body only.
    expect(reauth.mock.calls[0][0]).toStrictEqual({ password: 's3cret', totpCode: '123456' })
    expect(reauth.mock.invocationCallOrder[0]).toBeLessThan(onConfirmed.mock.invocationCallOrder[0])
  })

  it('sends the password alone when 2FA is off', async () => {
    reauth.mockResolvedValue({})
    const { onConfirmed } = renderPanel({ mfaEnabled: false }, 'remove-password')

    fireEvent.change(await screen.findByLabelText(en.settings.currentPassword), { target: { value: 's3cret' } })
    expect(screen.queryByLabelText(en.auth.mfaCodeLabel)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: en.reauth.confirm }))

    await waitFor(() => expect(onConfirmed).toHaveBeenCalledTimes(1))
    expect(reauth.mock.calls[0][0]).toStrictEqual({ password: 's3cret' })
  })

  it('shows the translated refusal and stays unconfirmed when the credentials are wrong', async () => {
    reauth.mockRejectedValue(apiError(401, 'REAUTH_FAILED'))
    const { onConfirmed } = renderPanel({ mfaEnabled: false }, 'export')

    fireEvent.change(await screen.findByLabelText(en.settings.currentPassword), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: en.reauth.confirm }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(en.ssoErrors.REAUTH_FAILED)
    expect(alert).not.toHaveTextContent('REAUTH_FAILED')
    expect(onConfirmed).not.toHaveBeenCalled()
  })

  it('without a password, refreshes the session and only then starts the IdP re-authentication', async () => {
    let finishRefresh: (value: unknown) => void = () => {}
    refresh.mockReturnValue(new Promise(resolve => { finishRefresh = resolve }))
    const { onConfirmed } = renderPanel({ hasPassword: false }, 'unlink-7')

    fireEvent.click(await screen.findByRole('button', { name: 'Confirm with Zitadel' }))
    expect(screen.queryByLabelText(en.settings.currentPassword)).not.toBeInTheDocument()

    await waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
    expect(submitSsoStart).not.toHaveBeenCalled()
    finishRefresh({ username: 'alice' })

    await waitFor(() => expect(submitSsoStart).toHaveBeenCalledTimes(1))
    expect(submitSsoStart).toHaveBeenCalledWith({ intent: 'reauth', redirect: '/settings?resume=unlink-7' })
    // The proof arrives with the IdP round trip; the caller resumes from the URL, not from here.
    expect(onConfirmed).not.toHaveBeenCalled()
  })

  it('never starts the round trip when the session refresh fails', async () => {
    refresh.mockRejectedValue(apiError(401, 'SSO_REVALIDATE'))
    renderPanel({ hasPassword: false }, 'export')

    fireEvent.click(await screen.findByRole('button', { name: 'Confirm with Zitadel' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(en.ssoErrors.SSO_REVALIDATE)
    expect(submitSsoStart).not.toHaveBeenCalled()
  })
})
