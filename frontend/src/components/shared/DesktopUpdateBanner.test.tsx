import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { DESKTOP_UPDATE_STATUS_EVENT, type DesktopUpdateStatus } from '@/features/desktop/updater'

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
    t: (key: string, params?: Record<string, string>) =>
      params?.version ? `${key}:${params.version}` : key,
    i18n: { language: 'fr', changeLanguage: vi.fn() },
  }),
}))

const mockGetInstanceStatus = vi.hoisted(() => vi.fn())
vi.mock('@/features/admin/api', () => ({
  adminApi: { getInstanceStatus: mockGetInstanceStatus },
}))
vi.mock('@/features/auth/hooks', () => ({
  useLogout: () => ({ mutate: vi.fn(), isPending: false }),
}))
vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ amount: (v: number) => String(v) }),
}))
vi.mock('@/components/shared/WhatsNewDialog', () => ({ WhatsNewDialog: () => null }))
vi.mock('@/pages/settings/security/SecuritySection', () => ({ SecuritySection: () => null }))
vi.mock('@/pages/settings/security/DesktopLockCard', () => ({ DesktopLockCard: () => null }))
vi.mock('@/pages/settings/sections/AccessKeysSection', () => ({ AccessKeysSection: () => null }))
vi.mock('@/pages/settings/sections/ProfileSection', () => ({ ProfileSection: () => null }))
vi.mock('@/pages/settings/sections/ConnectedAppsSection', () => ({ ConnectedAppsSection: () => null }))

const { DesktopUpdateBanner } = await import('./DesktopUpdateBanner')
const { SettingsPage } = await import('@/pages/settings/SettingsPage')
const { useAppStore } = await import('@/stores/app-store')
const { useAuthStore } = await import('@/stores/auth-store')
const { useDesktopUpdateStore } = await import('@/stores/desktop-update-store')

type StatusListener = (event: { payload: DesktopUpdateStatus }) => void

function installMockTauri(invokeImpl: (cmd: string) => Promise<unknown>) {
  const listeners = new Map<string, Set<StatusListener>>()
  const invoke = vi.fn(invokeImpl)
  const listen = vi.fn(async (event: string, handler: StatusListener) => {
    const bucket = listeners.get(event) ?? new Set<StatusListener>()
    bucket.add(handler)
    listeners.set(event, bucket)
    return () => {
      bucket.delete(handler)
    }
  })

  Object.defineProperty(window, 'isTauri', { value: true, configurable: true })
  Object.defineProperty(window, '__TAURI__', {
    value: { core: { invoke }, event: { listen } },
    configurable: true,
  })

  return {
    invoke,
    listen,
    emit(status: DesktopUpdateStatus) {
      for (const fn of listeners.get(DESKTOP_UPDATE_STATUS_EVENT) ?? []) {
        fn({ payload: status })
      }
    },
  }
}

function clearMockTauri() {
  delete (window as Window & { isTauri?: boolean; __TAURI__?: unknown }).isTauri
  delete (window as Window & { isTauri?: boolean; __TAURI__?: unknown }).__TAURI__
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const ADMIN = { username: 'camille', role: 'ADMIN' as const, memberId: 1, displayName: 'Camille' }

describe('DesktopUpdateBanner', () => {
  beforeEach(() => {
    clearMockTauri()
    localStorage.clear()
    mockGetInstanceStatus.mockReset()
    useDesktopUpdateStore.getState().reset()
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({
      demoMode: false,
      desktopMode: true,
      desktopLockEnabled: false,
      advancedMode: false,
      dismissedDesktopUpdateVersion: null,
    })
  })

  afterEach(() => {
    clearMockTauri()
    vi.restoreAllMocks()
  })

  it('stays hidden outside Tauri and when the desktop updater is disabled', async () => {
    const { unmount } = render(<DesktopUpdateBanner />)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    unmount()

    installMockTauri(async () => ({
      enabled: false,
      currentVersion: '1.3.1',
      availableVersion: null,
      checked: false,
      installing: false,
      error: null,
    }))

    render(<DesktopUpdateBanner />)
    await waitFor(() => expect(useDesktopUpdateStore.getState().initialized).toBe(true))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('appears live on watcher event, installs on click, and remembers dismissal per version', async () => {
    const tauri = installMockTauri(async (cmd) => {
      if (cmd === 'get_desktop_update_status') {
        return {
          enabled: true,
          currentVersion: '1.3.1',
          availableVersion: null,
          checked: false,
          installing: false,
          error: null,
        }
      }
      if (cmd === 'install_desktop_update') return undefined
      throw new Error(`unexpected command: ${cmd}`)
    })

    render(<DesktopUpdateBanner />)
    await waitFor(() => expect(tauri.listen).toHaveBeenCalledWith(DESKTOP_UPDATE_STATUS_EVENT, expect.any(Function)))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()

    act(() => {
      tauri.emit({
        enabled: true,
        currentVersion: '1.3.1',
        availableVersion: '1.4.0',
        checked: true,
        installing: false,
        error: null,
      })
    })

    expect(await screen.findByText('desktop.update.bannerTitle:1.4.0')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'desktop.update.install' }))
    await waitFor(() => expect(tauri.invoke).toHaveBeenCalledWith('install_desktop_update'))
    expect(screen.getByRole('button', { name: 'desktop.update.installing' })).toBeDisabled()

    // Dismissing hides 1.4.0 persistently, but a later 1.5.0 reappears.
    fireEvent.click(screen.getByRole('button', { name: 'common.close' }))
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(useAppStore.getState().dismissedDesktopUpdateVersion).toBe('1.4.0')

    act(() => {
      tauri.emit({
        enabled: true,
        currentVersion: '1.3.1',
        availableVersion: '1.5.0',
        checked: true,
        installing: false,
        error: null,
      })
    })
    expect(await screen.findByText('desktop.update.bannerTitle:1.5.0')).toBeInTheDocument()
  })
})

describe('SettingsPage System tab desktop updater', () => {
  beforeEach(() => {
    clearMockTauri()
    localStorage.clear()
    mockGetInstanceStatus.mockReset()
    useDesktopUpdateStore.getState().reset()
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({
      demoMode: false,
      desktopMode: true,
      desktopLockEnabled: false,
      advancedMode: false,
      dismissedDesktopUpdateVersion: null,
    })
  })

  afterEach(() => {
    clearMockTauri()
    vi.restoreAllMocks()
  })

  it('checks via Tauri, shows checking -> up to date -> available with install, and skips backend instance status', async () => {
    let resolveCheck!: (status: DesktopUpdateStatus) => void
    const tauri = installMockTauri((cmd) => {
      if (cmd === 'get_desktop_update_status') {
        return Promise.resolve({
          enabled: true,
          currentVersion: '1.3.1',
          availableVersion: null,
          checked: false,
          installing: false,
          error: null,
        })
      }
      if (cmd === 'check_desktop_update') {
        return new Promise<DesktopUpdateStatus>((resolve) => {
          resolveCheck = resolve
        })
      }
      if (cmd === 'install_desktop_update') {
        return Promise.resolve(undefined)
      }
      return Promise.reject(new Error(`unexpected command: ${cmd}`))
    })

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings?tab=system']}>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    const checkBtn = await screen.findByRole('button', { name: 'whatsNew.checkUpdates' })
    expect(mockGetInstanceStatus).not.toHaveBeenCalled()

    // First click: checking -> up to date
    fireEvent.click(checkBtn)
    expect(await screen.findByRole('button', { name: 'desktop.update.checking' })).toBeDisabled()

    await act(async () => {
      resolveCheck({
        enabled: true,
        currentVersion: '1.3.1',
        availableVersion: null,
        checked: true,
        installing: false,
        error: null,
      })
    })
    expect(await screen.findByText('whatsNew.upToDate')).toBeInTheDocument()

    // Second click: checking -> update available + install button
    fireEvent.click(screen.getByRole('button', { name: 'whatsNew.checkUpdates' }))
    await act(async () => {
      resolveCheck({
        enabled: true,
        currentVersion: '1.3.1',
        availableVersion: '1.4.0',
        checked: true,
        installing: false,
        error: null,
      })
    })
    expect(await screen.findByText('whatsNew.updateAvailable:v1.4.0')).toBeInTheDocument()
    const installBtn = screen.getByRole('button', { name: 'desktop.update.install' })
    fireEvent.click(installBtn)
    await waitFor(() => expect(tauri.invoke).toHaveBeenCalledWith('install_desktop_update'))
  })

  it('surfaces check errors without showing the up-to-date badge', async () => {
    installMockTauri(async (cmd) => {
      if (cmd === 'get_desktop_update_status') {
        return {
          enabled: true,
          currentVersion: '1.3.1',
          availableVersion: null,
          checked: false,
          installing: false,
          error: null,
        }
      }
      if (cmd === 'check_desktop_update') {
        return {
          enabled: true,
          currentVersion: '1.3.1',
          availableVersion: null,
          checked: false,
          installing: false,
          error: 'network timeout',
        }
      }
      throw new Error(`unexpected command: ${cmd}`)
    })

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings?tab=system']}>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    fireEvent.click(await screen.findByRole('button', { name: 'whatsNew.checkUpdates' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('desktop.update.checkFailed')
    expect(screen.queryByText('whatsNew.upToDate')).not.toBeInTheDocument()
  })

  it('keeps the web/Docker instance status update check unchanged outside Desktop', async () => {
    useAppStore.setState({ desktopMode: false, advancedMode: false })
    mockGetInstanceStatus.mockResolvedValue({
      currentVersion: '1.3.1',
      update: null,
      issues: [],
    })

    render(
      <QueryClientProvider client={makeClient()}>
        <MemoryRouter initialEntries={['/settings?tab=system']}>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('whatsNew.upToDate')).toBeInTheDocument()
    expect(mockGetInstanceStatus).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'whatsNew.checkUpdates' })).toBeInTheDocument()
  })
})
