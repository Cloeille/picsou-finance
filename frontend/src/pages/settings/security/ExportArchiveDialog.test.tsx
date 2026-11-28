import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import en from '@/i18n/locales/en.json'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) => {
      const value = key.split('.').reduce<unknown>((acc, part) => (acc as Record<string, unknown> | undefined)?.[part], en)
      const str = typeof value === 'string' ? value : key
      return Object.entries(opts ?? {}).reduce((acc, [k, v]) => acc.replace(`{{${k}}}`, String(v)), str)
    },
    i18n: { language: 'en', resolvedLanguage: 'en' },
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
vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })

const api = vi.hoisted(() => ({
  getSignInMethods: vi.fn(),
  reauth: vi.fn(),
  refresh: vi.fn(),
  submitSsoStart: vi.fn(),
  downloadArchive: vi.fn(),
  getMfaStatus: vi.fn(),
  triggerBlobDownload: vi.fn(),
}))
vi.mock('@/features/sso/api', () => ({ ssoApi: { getSignInMethods: api.getSignInMethods, reauth: api.reauth } }))
vi.mock('@/features/auth/api', () => ({ authApi: { refresh: api.refresh } }))
vi.mock('@/features/sso/start', () => ({ submitSsoStart: api.submitSsoStart }))
vi.mock('@/features/export/api', () => ({ exportApi: { downloadArchive: api.downloadArchive } }))
vi.mock('@/lib/download', () => ({ triggerBlobDownload: api.triggerBlobDownload }))
vi.mock('@/features/mfa/api', () => ({ mfaApi: { getStatus: api.getMfaStatus } }))

const { ExportArchiveDialog } = await import('./ExportArchiveDialog')
const { ArchiveExportCard } = await import('./ArchiveExportCard')
const { useAppStore } = await import('@/stores/app-store')
const { useAuthStore } = await import('@/stores/auth-store')

const WITH_PASSWORD = {
  hasPassword: true, mfaEnabled: false, identities: [], ssoEnabled: false, providerName: '', adminGroupManaged: false,
}
const PASSWORDLESS = { ...WITH_PASSWORD, hasPassword: false, ssoEnabled: true, providerName: 'Zitadel' }
const PASSPHRASE = 'a long enough passphrase'
const apiError = (status: number, code?: string) => ({ response: { status, data: code ? { code } : {} } })

function mount(ui: React.ReactNode, entry = '/settings?tab=security') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}
const renderDialog = (props: Partial<React.ComponentProps<typeof ExportArchiveDialog>> = {}) =>
  mount(<ExportArchiveDialog open onOpenChange={vi.fn()} {...props} />)

const dialog = () => screen.getByRole('dialog')
const downloadButton = () => within(dialog()).getByRole('button', { name: en.settings.archive.download }) as HTMLButtonElement

async function fillPassphrases(passphrase = PASSPHRASE, confirmation = passphrase) {
  const d = await screen.findByRole('dialog')
  await within(d).findByLabelText(en.settings.archive.passphraseLabel)
  fireEvent.change(within(d).getByLabelText(en.settings.archive.passphraseLabel), { target: { value: passphrase } })
  fireEvent.change(within(d).getByLabelText(en.settings.archive.confirmationLabel), { target: { value: confirmation } })
}

beforeEach(() => {
  Object.values(api).forEach((fn) => fn.mockReset())
  api.getSignInMethods.mockResolvedValue(WITH_PASSWORD)
  api.getMfaStatus.mockResolvedValue({ enabled: false, enrolledAt: null, remainingRecoveryCodes: 0 })
  api.downloadArchive.mockResolvedValue({ blob: new Blob(['x']), filename: 'picsou-2026-11-28.picsou-archive' })
  useAppStore.setState({ demoMode: false, desktopMode: false, desktopLockEnabled: false })
  useAuthStore.setState({ user: { username: 'admin', role: 'ADMIN', memberId: 1, displayName: 'Admin' }, isAuthenticated: true })
})
afterEach(cleanup)

describe('ExportArchiveDialog', () => {
  it('warns that the file holds the bank credentials key and that the passphrase cannot be recovered', async () => {
    renderDialog()
    const note = await screen.findByRole('note')
    expect(note).toHaveTextContent('bank credentials')
    expect(note).toHaveTextContent('cannot be recovered')
  })

  it('refuses a passphrase under 12 characters', async () => {
    renderDialog()
    await fillPassphrases('too short')
    expect(downloadButton()).toBeDisabled()
    expect(within(dialog()).getByText(en.settings.archive.passphraseTooShort)).toBeInTheDocument()
  })

  it('refuses a confirmation that does not match, and says so', async () => {
    renderDialog()
    await fillPassphrases(PASSPHRASE, 'a long enough passphrasX')
    expect(downloadButton()).toBeDisabled()
    expect(within(dialog()).getByRole('alert')).toHaveTextContent(en.settings.archive.mismatch)
    fireEvent.change(within(dialog()).getByLabelText(en.settings.archive.confirmationLabel), { target: { value: PASSPHRASE } })
    fireEvent.change(within(dialog()).getByLabelText(en.settings.currentPassword), { target: { value: 'pw' } })
    expect(downloadButton()).toBeEnabled()
    expect(downloadApiCalls()).toBe(0)
  })

  it('asks for the current password, sends it with the passphrase and downloads the file under its server name', async () => {
    renderDialog()
    await fillPassphrases()
    fireEvent.change(within(dialog()).getByLabelText(en.settings.currentPassword), { target: { value: 'my-password' } })
    fireEvent.click(downloadButton())

    await waitFor(() =>
      expect(api.downloadArchive).toHaveBeenCalledWith({ passphrase: PASSPHRASE, reAuth: { password: 'my-password' } }),
    )
    await waitFor(() => expect(api.triggerBlobDownload).toHaveBeenCalledTimes(1))
    expect(api.triggerBlobDownload.mock.calls[0][1]).toBe('picsou-2026-11-28.picsou-archive')
  })

  it('asks for the TOTP code instead of the password when 2FA is on', async () => {
    api.getMfaStatus.mockResolvedValue({ enabled: true, enrolledAt: null, remainingRecoveryCodes: 8 })
    renderDialog()
    await fillPassphrases()
    fireEvent.change(await within(dialog()).findByLabelText(en.auth.mfaCodeLabel), { target: { value: '123456' } })
    fireEvent.click(downloadButton())
    await waitFor(() =>
      expect(api.downloadArchive).toHaveBeenCalledWith({ passphrase: PASSPHRASE, reAuth: { totpCode: '123456' } }),
    )
  })

  it('shows a wrong password as such', async () => {
    api.downloadArchive.mockRejectedValue(apiError(401, 'REAUTH_FAILED'))
    renderDialog()
    await fillPassphrases()
    fireEvent.change(within(dialog()).getByLabelText(en.settings.currentPassword), { target: { value: 'nope' } })
    fireEvent.click(downloadButton())
    expect(await within(dialog()).findByText(en.settings.exportInvalidCredentials)).toBeInTheDocument()
  })

  it('shows the server refusing a short passphrase and the rate limit', async () => {
    api.downloadArchive.mockRejectedValueOnce(apiError(400, 'PASSPHRASE_TOO_SHORT'))
    renderDialog()
    await fillPassphrases()
    fireEvent.change(within(dialog()).getByLabelText(en.settings.currentPassword), { target: { value: 'pw' } })
    fireEvent.click(downloadButton())
    expect(await within(dialog()).findByRole('alert')).toHaveTextContent(en.setup.archive.errors.PASSPHRASE_TOO_SHORT)

    api.downloadArchive.mockRejectedValueOnce(apiError(429))
    fireEvent.click(downloadButton())
    expect(await within(dialog()).findByText(en.settings.archive.rateLimited)).toBeInTheDocument()
  })

  it('re-authenticates a password-less account through the identity provider', async () => {
    api.getSignInMethods.mockResolvedValue(PASSWORDLESS)
    api.refresh.mockResolvedValue({})
    renderDialog()

    const button = await within(await screen.findByRole('dialog')).findByRole('button', { name: /Zitadel/ })
    fireEvent.click(button)

    await waitFor(() =>
      expect(api.submitSsoStart).toHaveBeenCalledWith({ intent: 'reauth', redirect: '/settings?resume=archive' }),
    )
  })

  it('reopens confirmed on the way back from the IdP and downloads with the proof alone', async () => {
    api.getSignInMethods.mockResolvedValue(PASSWORDLESS)
    renderDialog({ resumed: true })
    await fillPassphrases()
    expect(within(dialog()).queryByLabelText(en.settings.currentPassword)).not.toBeInTheDocument()
    fireEvent.click(downloadButton())
    await waitFor(() => expect(api.downloadArchive).toHaveBeenCalledWith({ passphrase: PASSPHRASE }))
  })

  it('asks for nothing on the desktop app without its lock, the window standing for the proof', async () => {
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })
    renderDialog()
    await fillPassphrases()
    expect(within(dialog()).queryByLabelText(en.settings.currentPassword)).not.toBeInTheDocument()
    fireEvent.click(downloadButton())
    await waitFor(() => expect(api.downloadArchive).toHaveBeenCalledWith({ passphrase: PASSPHRASE }))
  })

  it('points a quick-start account without a known password to the lock when the window is refused', async () => {
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })
    api.downloadArchive.mockRejectedValueOnce(apiError(401, 'REAUTH_FAILED'))
    renderDialog()
    await fillPassphrases()
    fireEvent.click(downloadButton())

    const hint = await within(dialog()).findByText(en.settings.archive.needsLock)
    expect(hint).toBeInTheDocument()
    expect(en.settings.archive.needsLock).toContain('password lock')
    // No error toast about credentials: nothing was typed yet.
    expect(within(dialog()).queryByText(en.settings.exportInvalidCredentials)).not.toBeInTheDocument()
    // The password is asked after all, for an account that does have one.
    expect(within(dialog()).getByLabelText(en.settings.currentPassword)).toBeInTheDocument()
  })

  it('never keeps or shows the passphrase after closing', async () => {
    const onOpenChange = vi.fn()
    renderDialog({ onOpenChange })
    await fillPassphrases()
    fireEvent.click(within(dialog()).getByRole('button', { name: en.settings.mfaCancel }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(dialog().textContent).not.toContain(PASSPHRASE)
  })
})

function downloadApiCalls() {
  return api.downloadArchive.mock.calls.length
}

describe('ArchiveExportCard', () => {
  it('is offered to administrators only', () => {
    useAuthStore.setState({ user: { username: 'm', role: 'MEMBER', memberId: 2, displayName: 'M' }, isAuthenticated: true })
    mount(<ArchiveExportCard />)
    expect(screen.queryByText(en.settings.archive.cardTitle)).not.toBeInTheDocument()
  })

  it('is hidden in demo mode', () => {
    useAppStore.setState({ demoMode: true })
    mount(<ArchiveExportCard />)
    expect(screen.queryByText(en.settings.archive.cardTitle)).not.toBeInTheDocument()
  })

  it('opens the dialog from its button', async () => {
    mount(<ArchiveExportCard />)
    fireEvent.click(screen.getByRole('button', { name: en.settings.archive.button }))
    const opened = await screen.findByRole('dialog')
    expect(within(opened).getByText(en.settings.archive.title)).toBeInTheDocument()
  })

  it('reopens on the form after the IdP round trip (resume=archive)', async () => {
    api.getSignInMethods.mockResolvedValue(PASSWORDLESS)
    mount(<ArchiveExportCard />, '/settings?resume=archive')
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(await screen.findByLabelText(en.settings.archive.passphraseLabel)).toBeInTheDocument()
  })
})
