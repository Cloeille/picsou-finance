import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { LinkedIdentity, SignInMethods } from '@/features/sso/api'
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

const { getSignInMethods } = vi.hoisted(() => ({ getSignInMethods: vi.fn() }))
vi.mock('@/features/sso/api', () => ({ ssoApi: { getSignInMethods } }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh: vi.fn() } }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart: vi.fn() }))

const { LinkIdentityDialog, SignInMethodsCard } = await import('./SignInMethodsCard')
const { ssoKeys } = await import('@/features/sso/hooks')
const { submitSsoStart } = await import('@/features/sso/start')
const { authApi } = await import('@/features/auth/api')

// The server omits null fields: an identity that never signed in arrives without `lastLoginAt`.
const NEVER_USED: LinkedIdentity = {
  id: 1,
  email: 'alice@example.com',
  displayName: 'Alice',
  createdAt: '2026-10-01T10:00:00Z',
  currentIssuer: true,
}
const USED: LinkedIdentity = { ...NEVER_USED, lastLoginAt: '2026-10-02T10:00:00Z' }
const METHODS: SignInMethods = {
  hasPassword: true,
  mfaEnabled: false,
  identities: [USED],
  ssoEnabled: true,
  providerName: 'Zitadel',
  adminGroupManaged: false,
}

function text(key: keyof typeof en.settings.signInMethods) {
  return en.settings.signInMethods[key].replace('{{provider}}', 'Zitadel')
}

function renderCard(methods: Partial<SignInMethods>) {
  getSignInMethods.mockResolvedValue({ ...METHODS, ...methods })
  const onAction = vi.fn()
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <SignInMethodsCard onAction={onAction} />
    </QueryClientProvider>,
  )
  return { ...view, onAction, queryClient }
}

describe('SignInMethodsCard', () => {
  beforeEach(() => getSignInMethods.mockReset())

  it('lets a password account with a used identity turn password login off, unlink and link', async () => {
    const { onAction } = renderCard({})

    fireEvent.click(await screen.findByRole('button', { name: text('turnOff') }))
    fireEvent.click(screen.getByRole('button', { name: text('unlink') }))
    fireEvent.click(screen.getByRole('button', { name: 'Link Zitadel account' }))

    expect(onAction.mock.calls).toEqual([['remove-password'], ['unlink-1'], ['link']])
    expect(screen.getByText('alice@example.com')).toBeInTheDocument()
    expect(screen.getByText(new RegExp(`^${text('lastSignIn')}:`))).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: text('setPassword') })).not.toBeInTheDocument()
    expect(screen.queryByText(text('turnOffHint'))).not.toBeInTheDocument()
    expect(screen.queryByText(text('adminGroupNote'))).not.toBeInTheDocument()
  })

  it.each([
    ['the identity never signed in', { identities: [NEVER_USED] }],
    ['the identity has a null last sign-in', { identities: [{ ...USED, lastLoginAt: null }] }],
    ['the identity belongs to a previous issuer', { identities: [{ ...USED, currentIssuer: false }] }],
    ['SSO is off on the instance', { ssoEnabled: false }],
  ])('keeps password login on, with the hint, while %s', async (_, methods) => {
    renderCard(methods)

    const turnOff = await screen.findByRole('button', { name: text('turnOff') })
    expect(turnOff).toBeDisabled()
    expect(turnOff).toHaveAttribute('aria-describedby', screen.getByText(text('turnOffHint')).id)
  })

  it('names an identity without an email by its display name and shows it was never used', async () => {
    renderCard({ identities: [{ ...USED, email: null, lastLoginAt: null }] })

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText(text('neverUsed'))).toBeInTheDocument()
  })

  it('offers a password to a password-less account and keeps its last identity linked', async () => {
    const { onAction } = renderCard({ hasPassword: false })

    fireEvent.click(await screen.findByRole('button', { name: text('setPassword') }))
    expect(onAction).toHaveBeenCalledWith('set-password')
    expect(screen.queryByRole('button', { name: text('turnOff') })).not.toBeInTheDocument()
    const unlink = screen.getByRole('button', { name: text('unlink') })
    expect(unlink).toBeDisabled()
    expect(unlink).toHaveAttribute('aria-describedby', screen.getByText(text('unlinkHint')).id)
  })

  it('lets a password-less account unlink one of two identities of the current issuer', async () => {
    renderCard({ hasPassword: false, identities: [USED, { ...USED, id: 2, email: 'alice@work.example' }] })

    const buttons = await screen.findAllByRole('button', { name: text('unlink') })
    expect(buttons).toHaveLength(2)
    buttons.forEach(button => expect(button).toBeEnabled())
    expect(screen.queryByText(text('unlinkHint'))).not.toBeInTheDocument()
  })

  it('keeps the current-issuer identity of a password-less account but lets it drop a previous issuer\'s', async () => {
    renderCard({ hasPassword: false, identities: [USED, { ...USED, id: 2, email: 'alice@old.example', currentIssuer: false }] })

    const [current, previous] = await screen.findAllByRole('button', { name: text('unlink') })
    expect(current).toBeDisabled()
    expect(previous).toBeEnabled()
  })

  it('notes that admin rights follow the IdP group when the instance manages them there', async () => {
    renderCard({ adminGroupManaged: true })

    expect(await screen.findByText('Admin rights follow your group in Zitadel.')).toBeInTheDocument()
  })

  it('stays hidden for a password-only account on an instance without SSO', async () => {
    const { container, queryClient } = renderCard({ ssoEnabled: false, identities: [] })

    await waitFor(() => expect(queryClient.getQueryState(ssoKeys.methods)?.status).toBe('success'))
    expect(container).toBeEmptyDOMElement()
  })
})

describe('LinkIdentityDialog', () => {
  beforeEach(() => getSignInMethods.mockReset())

  it('shows the confirmation again when Back from the IdP restores the page from the back/forward cache', async () => {
    getSignInMethods.mockResolvedValue(METHODS)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <LinkIdentityDialog open resumed onOpenChange={() => {}} providerName="Zitadel" mfaEnabled={false} />
      </QueryClientProvider>,
    )

    expect(screen.getByText(en.auth.sso.redirecting)).toBeInTheDocument()
    await waitFor(() => expect(submitSsoStart).toHaveBeenCalledWith({ intent: 'link', redirect: '/settings' }))

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })

    expect(screen.queryByText(en.auth.sso.redirecting)).not.toBeInTheDocument()
    expect(await screen.findByLabelText(en.settings.currentPassword)).toBeInTheDocument()
  })

  it('does not leave for the IdP when the dialog closes while the session refresh runs', async () => {
    getSignInMethods.mockResolvedValue(METHODS)
    vi.mocked(submitSsoStart).mockClear()
    let finishRefresh: (user: Awaited<ReturnType<typeof authApi.refresh>>) => void = () => {}
    vi.mocked(authApi.refresh).mockReset().mockReturnValue(new Promise(resolve => { finishRefresh = resolve }))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const { unmount } = render(
      <QueryClientProvider client={queryClient}>
        <LinkIdentityDialog open resumed onOpenChange={() => {}} providerName="Zitadel" mfaEnabled={false} />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(authApi.refresh).toHaveBeenCalledTimes(1))

    unmount()
    await act(async () => { finishRefresh({ username: 'alice', role: 'MEMBER', memberId: 1, displayName: 'Alice' }) })

    expect(submitSsoStart).not.toHaveBeenCalled()
  })

  it('leaves the session alone when the dialog closed before the refresh asked for re-validation', async () => {
    const original = window.location
    const assign = vi.fn()
    Object.defineProperty(window, 'location', { value: { assign }, configurable: true, writable: true })
    try {
      getSignInMethods.mockResolvedValue(METHODS)
      let failRefresh: (reason: unknown) => void = () => {}
      vi.mocked(authApi.refresh).mockReset().mockReturnValue(new Promise((_, reject) => { failRefresh = reject }))
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
      const { unmount } = render(
        <QueryClientProvider client={queryClient}>
          <LinkIdentityDialog open resumed onOpenChange={() => {}} providerName="Zitadel" mfaEnabled={false} />
        </QueryClientProvider>,
      )
      await waitFor(() => expect(authApi.refresh).toHaveBeenCalledTimes(1))

      unmount()
      await act(async () => { failRefresh({ response: { status: 401, data: { code: 'SSO_REVALIDATE' } } }) })

      expect(assign).not.toHaveBeenCalled()
    } finally {
      Object.defineProperty(window, 'location', { value: original, configurable: true })
    }
  })
})
