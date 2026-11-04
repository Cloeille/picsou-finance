import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, useLocation } from 'react-router-dom'
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
    i18n: { language: 'en', resolvedLanguage: 'en' },
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
// jsdom lays nothing out: Radix Switch measures itself and the section scrolls a notice into view.
vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
Element.prototype.scrollIntoView = vi.fn()

const api = vi.hoisted(() => ({
  getSignInMethods: vi.fn(),
  reauth: vi.fn(),
  removePassword: vi.fn(),
  setPassword: vi.fn(),
  unlinkIdentity: vi.fn(),
  refresh: vi.fn(),
  submitSsoStart: vi.fn(),
  download: vi.fn(),
}))
vi.mock('@/features/sso/api', () => ({ ssoApi: api }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh: api.refresh } }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart: api.submitSsoStart }))
vi.mock('@/features/export/api', () => ({ exportApi: { download: api.download } }))
vi.mock('@/lib/download', () => ({ triggerBlobDownload: vi.fn() }))
vi.mock('@/features/mfa/api', () => ({
  mfaApi: {
    getStatus: () => Promise.resolve({ enabled: false, enrolledAt: null, remainingRecoveryCodes: 0 }),
    listSessions: () => Promise.resolve([]),
  },
}))

const { SecuritySection } = await import('./SecuritySection')

const IDENTITY = {
  id: 7,
  email: 'alice@example.com',
  displayName: 'Alice',
  createdAt: '2026-10-01T10:00:00Z',
  lastLoginAt: '2026-10-02T10:00:00Z',
  currentIssuer: true,
}
const PASSWORDLESS = {
  hasPassword: false,
  mfaEnabled: false,
  identities: [IDENTITY],
  ssoEnabled: true,
  providerName: 'Zitadel',
  adminGroupManaged: false,
}
const WITH_PASSWORD = { ...PASSWORDLESS, hasPassword: true }

function LocationProbe() {
  const location = useLocation()
  // A div, not <output>: that element carries the implicit role=status, which collides with the notices.
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderSection(entry: string, methods = PASSWORDLESS) {
  api.getSignInMethods.mockResolvedValue(methods)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={[entry]}>
          <SecuritySection />
          <LocationProbe />
        </MemoryRouter>
      </QueryClientProvider>
    </StrictMode>,
  )
}

/** The section has handled the arrival once the one-shot parameters are gone from the URL. */
async function expectCleanUrl() {
  await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/settings$/))
}

function apiError(status: number, code?: string) {
  return { response: { status, data: code ? { code } : {} } }
}

function deferred() {
  let resolve: (value: unknown) => void = () => {}
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

describe('SecuritySection', () => {
  beforeEach(() => {
    Object.values(api).forEach(fn => fn.mockReset())
    api.download.mockResolvedValue({ blob: new Blob(['zip']), filename: 'picsou-export.zip' })
  })

  it.each([
    ['hides', PASSWORDLESS, false],
    ['shows', WITH_PASSWORD, true],
  ])('%s the 2FA block according to the password', async (_, methods, shown) => {
    renderSection('/settings', methods)

    await screen.findByText(en.settings.signInMethods.title)
    expect(screen.queryByText(en.settings.mfaStatus) !== null).toBe(shown)
  })

  it('reopens the export confirmed after the IdP round trip and downloads with the proof alone', async () => {
    renderSection('/settings?resume=export')

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(await within(dialog).findByRole('button', { name: en.settings.exportDownload }))

    await waitFor(() => expect(api.download).toHaveBeenCalledTimes(1))
    expect(api.download.mock.calls[0][0]).toStrictEqual({ includeBalanceSnapshots: false })
    expect(within(dialog).queryByLabelText(en.settings.currentPassword)).not.toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await expectCleanUrl()
  })

  it('brings the confirmation back when the export refuses an expired proof', async () => {
    api.download.mockRejectedValue(apiError(401))
    renderSection('/settings?resume=export')

    const dialog = await screen.findByRole('dialog')
    fireEvent.click(await within(dialog).findByRole('button', { name: en.settings.exportDownload }))

    expect(await within(dialog).findByText(en.reauth.expired)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Confirm with Zitadel' })).toBeInTheDocument()
  })

  it('asks a password-less user to confirm with the IdP before an export', async () => {
    api.refresh.mockResolvedValue({ username: 'alice' })
    renderSection('/settings')

    fireEvent.click(await screen.findByRole('button', { name: en.settings.exportButton }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Confirm with Zitadel' }))

    await waitFor(() => expect(api.submitSsoStart).toHaveBeenCalledWith({
      intent: 'reauth',
      redirect: '/settings?resume=export',
    }))
    expect(api.download).not.toHaveBeenCalled()
  })

  it('keeps the inline password for an export by a password user', async () => {
    renderSection('/settings', WITH_PASSWORD)

    fireEvent.click(await screen.findByRole('button', { name: en.settings.exportButton }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(await within(dialog).findByLabelText(en.settings.currentPassword), { target: { value: 's3cret' } })
    fireEvent.click(within(dialog).getByRole('button', { name: en.settings.exportDownload }))

    await waitFor(() => expect(api.download).toHaveBeenCalledTimes(1))
    expect(api.download.mock.calls[0][0]).toStrictEqual({
      reAuth: { password: 's3cret' },
      includeBalanceSnapshots: false,
    })
  })

  it('shows the IdP refusal and resumes nothing', async () => {
    renderSection('/settings?resume=export&ssoError=REAUTH_TOO_OLD')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(en.ssoErrors.REAUTH_TOO_OLD)
    expect(alert).not.toHaveTextContent('REAUTH_TOO_OLD')
    await expectCleanUrl()
    await screen.findByText(en.settings.signInMethods.title)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(api.download).not.toHaveBeenCalled()
  })

  it('confirms a freshly linked identity', async () => {
    renderSection('/settings?sso=linked')

    expect(await screen.findByRole('status')).toHaveTextContent(en.settings.signInMethods.linked)
    await expectCleanUrl()
  })

  it.each(['bogus', 'unlink-abc', 'unlink-999'])('ignores the resume value %s', async (resume) => {
    renderSection(`/settings?resume=${resume}`)

    await expectCleanUrl()
    await screen.findByText(en.settings.signInMethods.title)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('starts linking once, right after the session refresh, when it comes back from the IdP', async () => {
    const refresh = deferred()
    api.refresh.mockReturnValue(refresh.promise)
    renderSection('/settings?resume=link')

    await waitFor(() => expect(api.refresh).toHaveBeenCalledTimes(1))
    expect(api.submitSsoStart).not.toHaveBeenCalled()
    refresh.resolve({ username: 'alice' })

    await waitFor(() => expect(api.submitSsoStart).toHaveBeenCalledTimes(1))
    expect(api.submitSsoStart).toHaveBeenCalledWith({ intent: 'link', redirect: '/settings' })
    expect(api.refresh).toHaveBeenCalledTimes(1)
  })

  it('links for a password user: password first, then the session refresh, then the IdP', async () => {
    api.reauth.mockResolvedValue({})
    api.refresh.mockResolvedValue({ username: 'alice' })
    renderSection('/settings', WITH_PASSWORD)

    fireEvent.click(await screen.findByRole('button', { name: 'Link Zitadel account' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(await within(dialog).findByLabelText(en.settings.currentPassword), { target: { value: 's3cret' } })
    fireEvent.click(within(dialog).getByRole('button', { name: en.reauth.confirm }))

    await waitFor(() => expect(api.submitSsoStart).toHaveBeenCalledWith({ intent: 'link', redirect: '/settings' }))
    const [reauthAt] = api.reauth.mock.invocationCallOrder
    const [refreshAt] = api.refresh.mock.invocationCallOrder
    expect(reauthAt).toBeLessThan(refreshAt)
    expect(refreshAt).toBeLessThan(api.submitSsoStart.mock.invocationCallOrder[0])
  })

  it('reopens an unlink confirmed and unlinks that identity', async () => {
    api.unlinkIdentity.mockResolvedValue({})
    renderSection('/settings?resume=unlink-7', {
      ...PASSWORDLESS,
      identities: [IDENTITY, { ...IDENTITY, id: 8, email: 'alice@work.example' }],
    })

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('alice@example.com')).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: en.settings.signInMethods.unlink }))

    await waitFor(() => expect(api.unlinkIdentity).toHaveBeenCalledTimes(1))
    expect(api.unlinkIdentity.mock.calls[0][0]).toBe(7)
  })

  it('brings the confirmation back with a notice when the final action finds the proof expired', async () => {
    api.setPassword.mockRejectedValue(apiError(401, 'REAUTH_FAILED'))
    renderSection('/settings?resume=set-password')

    const dialog = await screen.findByRole('dialog')
    fireEvent.change(await within(dialog).findByLabelText(en.settings.newPassword), { target: { value: 'new-password-1' } })
    fireEvent.change(within(dialog).getByLabelText(en.settings.confirmPassword), { target: { value: 'new-password-1' } })
    fireEvent.click(within(dialog).getByRole('button', { name: en.settings.signInMethods.setPassword }))

    expect(await within(dialog).findByText(en.reauth.expired)).toBeInTheDocument()
    expect(api.setPassword.mock.calls[0][0]).toBe('new-password-1')
    expect(within(dialog).getByRole('button', { name: 'Confirm with Zitadel' })).toBeInTheDocument()
    expect(within(dialog).queryByLabelText(en.settings.newPassword)).not.toBeInTheDocument()
  })
})
