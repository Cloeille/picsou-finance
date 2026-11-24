import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
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
vi.stubGlobal('matchMedia', () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: vi.fn() },
  }),
}))

const { refresh, desktopAutologin, getLockStatus, updateLock, listMembers, getMfaStatus, getSignInMethods, listAccessKeys } = vi.hoisted(() => ({
  refresh: vi.fn(),
  desktopAutologin: vi.fn(),
  getLockStatus: vi.fn(),
  updateLock: vi.fn(),
  listMembers: vi.fn(),
  getMfaStatus: vi.fn(),
  getSignInMethods: vi.fn(),
  listAccessKeys: vi.fn(),
}))

vi.mock('@/features/auth/api', () => ({
  authApi: { refresh, desktopAutologin, logout: vi.fn() },
}))
vi.mock('@/features/desktop/api', () => ({
  desktopApi: { getLockStatus, updateLock },
}))
vi.mock('@/features/family/api', () => ({
  familyApi: { listMembers },
}))
vi.mock('@/features/mfa/api', () => ({
  mfaApi: {
    getStatus: getMfaStatus,
    listSessions: () => Promise.resolve([]),
  },
}))
vi.mock('@/features/sso/api', () => ({
  ssoApi: {
    getSignInMethods,
  },
}))
vi.mock('@/features/accessKeys/api', () => ({
  accessKeysApi: {
    list: listAccessKeys,
    create: vi.fn(),
    revoke: vi.fn(),
  },
}))
vi.mock('@/components/shared/WhatsNewDialog', () => ({ WhatsNewDialog: () => null }))
vi.mock('@/pages/settings/security/SecuritySection', () => ({
  SecuritySection: () => <div>security-section</div>,
}))
vi.mock('@/pages/settings/sections/AccessKeysSection', () => ({
  AccessKeysSection: () => <div>access-keys-section</div>,
}))
vi.mock('@/pages/settings/sections/ProfileSection', () => ({
  ProfileSection: () => <div>profile-section</div>,
}))
vi.mock('@/pages/settings/sections/ConnectedAppsSection', () => ({
  ConnectedAppsSection: () => <div>connected-apps-section</div>,
}))

const { RequireAuth } = await import('@/features/auth/guards')
const { AppSidebar } = await import('@/components/layout/AppSidebar')
const { SettingsPage } = await import('@/pages/settings/SettingsPage')
const { DesktopLockCard } = await import('@/pages/settings/security/DesktopLockCard')
const { SecuritySection: RealSecuritySection } = await vi.importActual<
  typeof import('@/pages/settings/security/SecuritySection')
>('@/pages/settings/security/SecuritySection')
const { AccessKeysSection: RealAccessKeysSection } = await vi.importActual<
  typeof import('@/pages/settings/sections/AccessKeysSection')
>('@/pages/settings/sections/AccessKeysSection')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore, selectIsAdvancedMode } = await import('@/stores/app-store')

const LOCAL_ADMIN = {
  username: 'camille',
  role: 'ADMIN' as const,
  memberId: 1,
  displayName: 'Camille',
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

describe('Desktop Mode auto-login & Advanced Mode', () => {
  beforeEach(() => {
    refresh.mockReset()
    desktopAutologin.mockReset()
    getLockStatus.mockReset()
    updateLock.mockReset()
    listMembers.mockReset()
    listMembers.mockResolvedValue([])
    getMfaStatus.mockReset()
    getMfaStatus.mockResolvedValue({ enabled: false, remainingRecoveryCodes: 0 })
    listAccessKeys.mockReset()
    listAccessKeys.mockResolvedValue([])
    localStorage.clear()
    sessionStorage.clear()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    useAppStore.setState({
      demoMode: false,
      desktopMode: false,
      desktopLockEnabled: false,
      advancedMode: false,
    })
  })

  it('auto-logs in seamlessly on Desktop when lock is disabled without flashing /login', async () => {
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })
    refresh.mockRejectedValue(new Error('401'))
    desktopAutologin.mockResolvedValue(LOCAL_ADMIN)

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route path="/login" element={<div>login-screen</div>} />
            <Route path="/" element={<RequireAuth><div>desktop-app</div></RequireAuth>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop-app')).toBeInTheDocument()
    expect(screen.queryByText('login-screen')).not.toBeInTheDocument()
    expect(desktopAutologin).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().user).toEqual(LOCAL_ADMIN)
  })

  it('does not auto-login when Desktop password lock is enabled', async () => {
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    refresh.mockRejectedValue(new Error('401'))

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route path="/login" element={<div>login-screen</div>} />
            <Route path="/" element={<RequireAuth><div>desktop-app</div></RequireAuth>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('login-screen')).toBeInTheDocument()
    expect(desktopAutologin).not.toHaveBeenCalled()
  })

  it('always enables effective advanced mode outside Desktop Mode, and gates it inside Desktop Mode', () => {
    expect(selectIsAdvancedMode({ desktopMode: false, advancedMode: false })).toBe(true)
    expect(selectIsAdvancedMode({ desktopMode: true, advancedMode: false })).toBe(false)
    expect(selectIsAdvancedMode({ desktopMode: true, advancedMode: true })).toBe(true)
  })

  it('hides Admin and Logout in AppSidebar on unlocked Desktop Grand Public mode, and reveals them as configured', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false, advancedMode: false })

    const { rerender } = render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter>
          <AppSidebar />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.pointerDown(screen.getByRole('button', { name: 'nav.account' }), { button: 0 })
    expect(await screen.findByRole('menuitem', { name: 'nav.settings' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'nav.admin' })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'settings.logout' })).not.toBeInTheDocument()

    useAppStore.setState({ desktopMode: true, desktopLockEnabled: true, advancedMode: true })
    rerender(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter>
          <AppSidebar />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('menuitem', { name: 'nav.admin' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'settings.logout' })).toBeInTheDocument()
  })

  it('toggles Advanced Mode in SettingsPage and reveals the hidden API tab', () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false, advancedMode: false })

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings']}>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.queryByRole('tab', { name: 'settings.tabs.api' })).not.toBeInTheDocument()
    const toggle = screen.getByRole('switch', { name: 'desktop.advancedMode.title' })
    expect(toggle).toHaveAttribute('aria-checked', 'false')

    fireEvent.click(toggle)

    expect(useAppStore.getState().advancedMode).toBe(true)
    expect(screen.getByRole('tab', { name: 'settings.tabs.api' })).toBeInTheDocument()
  })

  it('enables and disables Desktop password lock from DesktopLockCard', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })
    getLockStatus.mockResolvedValue({ enabled: false })
    updateLock.mockResolvedValueOnce({ enabled: true }).mockResolvedValueOnce({ enabled: false })

    render(
      <QueryClientProvider client={makeClient()}>
        <DesktopLockCard />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop.lock.statusDisabled')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.enableButton' }))

    fireEvent.change(screen.getByLabelText('desktop.lock.newPasswordLabel'), {
      target: { value: 'strong-password-123' },
    })
    fireEvent.change(screen.getByLabelText('desktop.lock.confirmPasswordLabel'), {
      target: { value: 'strong-password-123' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.enableConfirm' }))

    await waitFor(() => expect(updateLock).toHaveBeenCalledWith({
      enabled: true,
      newPassword: 'strong-password-123',
    }))
    expect(useAppStore.getState().desktopLockEnabled).toBe(true)
    expect(await screen.findByText('desktop.lock.statusEnabled')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.disableButton' }))
    fireEvent.change(screen.getByLabelText('desktop.lock.currentPasswordLabel'), {
      target: { value: 'strong-password-123' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.disableConfirm' }))

    await waitFor(() => expect(updateLock).toHaveBeenCalledWith({
      enabled: false,
      currentPassword: 'strong-password-123',
    }))
    expect(useAppStore.getState().desktopLockEnabled).toBe(false)
  })

  it('gates 2FA enrollment on Desktop password lock in SecuritySection', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    getMfaStatus.mockResolvedValue({ enabled: false, remainingRecoveryCodes: 0 })
    getSignInMethods.mockResolvedValue({
      hasPassword: true,
      mfaEnabled: false,
      identities: [],
      ssoEnabled: false,
      providerName: null,
      adminGroupManaged: false,
    })

    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })
    const { rerender } = render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings']}>
          <RealSecuritySection />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop.lock.mfaRequiresLock')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'settings.mfaEnable' })).toBeDisabled()

    act(() => {
      useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    })
    rerender(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings']}>
          <RealSecuritySection />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(screen.queryByText('desktop.lock.mfaRequiresLock')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'settings.mfaEnable' })).toBeEnabled()
  })

  it('disables turning off Desktop password lock while 2FA is active and enables it once 2FA is off', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    getLockStatus.mockResolvedValue({ enabled: true })
    getMfaStatus.mockResolvedValue({ enabled: true, remainingRecoveryCodes: 8 })

    const view = render(
      <QueryClientProvider client={makeClient()}>
        <DesktopLockCard />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop.lock.disableBlockedByMfa')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'desktop.lock.disableButton' })).toBeDisabled()

    view.unmount()
    getMfaStatus.mockResolvedValue({ enabled: false, remainingRecoveryCodes: 0 })

    render(
      <QueryClientProvider client={makeClient()}>
        <DesktopLockCard />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop.lock.statusEnabled')).toBeInTheDocument()
    expect(screen.queryByText('desktop.lock.disableBlockedByMfa')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'desktop.lock.disableButton' })).toBeEnabled()
  })

  it('maps 409 MFA_ACTIVE cleanly when disabling Desktop password lock fails server-side', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    getLockStatus.mockResolvedValue({ enabled: true })
    getMfaStatus.mockResolvedValue({ enabled: false, remainingRecoveryCodes: 0 })
    updateLock.mockRejectedValueOnce({
      response: { status: 409, data: { code: 'MFA_ACTIVE', detail: 'Disable 2FA first.' } },
    })

    render(
      <QueryClientProvider client={makeClient()}>
        <DesktopLockCard />
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'desktop.lock.disableButton' }))
    fireEvent.change(screen.getByLabelText('desktop.lock.currentPasswordLabel'), {
      target: { value: 'strong-password-123' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.disableConfirm' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('desktop.lock.disableBlockedByMfa')
  })

  it('shows invalidPassword only on 401 REAUTH_FAILED and not on an uncoded 401 when disabling lock', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    getLockStatus.mockResolvedValue({ enabled: true })
    getMfaStatus.mockResolvedValue({ enabled: false, remainingRecoveryCodes: 0 })
    updateLock
      .mockRejectedValueOnce({
        response: { status: 401, data: { code: 'REAUTH_FAILED', detail: 'Invalid current password.' } },
      })
      .mockRejectedValueOnce({
        response: { status: 401, data: { detail: 'Authentication required' } },
      })

    render(
      <QueryClientProvider client={makeClient()}>
        <DesktopLockCard />
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'desktop.lock.disableButton' }))
    fireEvent.change(screen.getByLabelText('desktop.lock.currentPasswordLabel'), {
      target: { value: 'wrong-password' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.disableConfirm' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('desktop.lock.invalidPassword')

    fireEvent.click(screen.getByRole('button', { name: 'desktop.lock.disableConfirm' }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('common.errors.unauthorized')
    })
  })

  it('shows the Desktop password lock guidance note in AccessKeysSection only when Desktop is unlocked', async () => {
    useAuthStore.getState().login(LOCAL_ADMIN)
    useAppStore.setState({ desktopMode: true, desktopLockEnabled: false })

    const { rerender } = render(
      <QueryClientProvider client={makeClient()}>
        <RealAccessKeysSection />
      </QueryClientProvider>,
    )

    expect(await screen.findByText('desktop.oauthLogin.apiTabNote')).toBeInTheDocument()

    act(() => {
      useAppStore.setState({ desktopMode: true, desktopLockEnabled: true })
    })
    rerender(
      <QueryClientProvider client={makeClient()}>
        <RealAccessKeysSection />
      </QueryClientProvider>,
    )
    expect(screen.queryByText('desktop.oauthLogin.apiTabNote')).not.toBeInTheDocument()

    act(() => {
      useAppStore.setState({ desktopMode: false, desktopLockEnabled: false })
    })
    rerender(
      <QueryClientProvider client={makeClient()}>
        <RealAccessKeysSection />
      </QueryClientProvider>,
    )
    expect(screen.queryByText('desktop.oauthLogin.apiTabNote')).not.toBeInTheDocument()
  })
})
