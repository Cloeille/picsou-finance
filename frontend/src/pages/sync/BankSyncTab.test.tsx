import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { useAppStore } from '@/stores/app-store'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import es from '@/i18n/locales/es.json'
import fr from '@/i18n/locales/fr.json'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: {
    get: apiGet,
    post: apiPost,
    delete: apiDelete,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { BankSyncTab } = await import('./BankSyncTab')

const FAILED_CONNECTIONS = [
  {
    id: 1,
    institutionId: 'BANK_A::FR',
    institutionName: 'Bank A',
    status: 'FAILED',
    lastSyncedAt: null,
  },
  {
    id: 2,
    institutionId: 'BANK_B::FR',
    institutionName: 'Bank B',
    status: 'FAILED',
    lastSyncedAt: null,
  },
]

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

function renderTab(props: { onOpenWiseJars?: () => void } = {}) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })

  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <MemoryRouter initialEntries={['/sync?tab=banks']}>
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </MemoryRouter>
    )
  }

  render(<BankSyncTab {...props} />, { wrapper: Wrapper })
}

function retryButton(bankName: string) {
  const row = screen.getByText(bankName).closest('[data-slot="card"]') as HTMLElement
  return within(row).getByTitle('sync.banks.retry')
}

function reconnectButton(bankName: string) {
  const row = screen.getByText(bankName).closest('[data-slot="card"]') as HTMLElement
  return within(row).getByTitle('sync.banks.reconnect')
}

describe('BankSyncTab retries', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockImplementation((url: string) => {
      if (url === '/sync/status') return Promise.resolve({ data: FAILED_CONNECTIONS })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
  })

  it('tracks concurrent retries per connection and blocks reconnect only on the active rows', async () => {
    const retryA = deferred<{ data: unknown[] }>()
    const retryB = deferred<{ data: unknown[] }>()
    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/1/retry') return retryA.promise
      if (url === '/sync/2/retry') return retryB.promise
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    await screen.findByText('Bank A')

    fireEvent.click(retryButton('Bank A'))
    await waitFor(() => {
      expect(retryButton('Bank A')).toBeDisabled()
      expect(reconnectButton('Bank A')).toBeDisabled()
      expect(retryButton('Bank B')).toBeEnabled()
      expect(reconnectButton('Bank B')).toBeEnabled()
    })

    fireEvent.click(retryButton('Bank B'))
    await waitFor(() => {
      expect(retryButton('Bank A')).toBeDisabled()
      expect(retryButton('Bank B')).toBeDisabled()
    })

    await act(async () => retryA.resolve({ data: [] }))
    await waitFor(() => {
      expect(retryButton('Bank A')).toBeEnabled()
      expect(reconnectButton('Bank A')).toBeEnabled()
      expect(retryButton('Bank B')).toBeDisabled()
    })

    await act(async () => retryB.resolve({ data: [] }))
    await waitFor(() => {
      expect(retryButton('Bank B')).toBeEnabled()
      expect(reconnectButton('Bank B')).toBeEnabled()
    })
  })
})

describe('BankSyncTab Wise connection', () => {
  const WISE_CONNECTION = { id: 3, institutionId: 'Wise::BE', institutionName: 'Wise', status: 'LINKED', lastSyncedAt: null }
  const WISE_DISCONNECTED = { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null }

  function givenStatuses(connections: unknown[], wiseStatus: unknown) {
    apiGet.mockImplementation((url: string) => {
      if (url === '/sync/status') return Promise.resolve({ data: connections })
      if (url === '/wise/status') return Promise.resolve({ data: wiseStatus })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
  }

  beforeEach(() => {
    apiGet.mockReset()
  })

  it('shows the Wise Jars hint under a Wise row and opens the Wise Jars connector', async () => {
    const onOpenWiseJars = vi.fn()
    givenStatuses([WISE_CONNECTION], WISE_DISCONNECTED)

    renderTab({ onOpenWiseJars })

    expect(await screen.findByText('sync.banks.wiseJarsMissing')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'sync.banks.wiseJarsConnect' }))
    expect(onOpenWiseJars).toHaveBeenCalledTimes(1)
  })

  it('confirms the Jars once the Wise Jars connector is connected', async () => {
    givenStatuses([WISE_CONNECTION], { ...WISE_DISCONNECTED, connected: true, connectionId: 1, status: 'CONNECTED' })

    renderTab({ onOpenWiseJars: vi.fn() })

    expect(await screen.findByText('sync.banks.wiseJarsConnected')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'sync.banks.wiseJarsConnect' })).not.toBeInTheDocument()
  })

  it.each(['ERROR', 'REAUTH_REQUIRED'])('says the last Jars sync failed when Wise Jars is %s and keeps the way there', async (status) => {
    const onOpenWiseJars = vi.fn()
    givenStatuses([WISE_CONNECTION], { ...WISE_DISCONNECTED, connected: true, connectionId: 1, status })

    renderTab({ onOpenWiseJars })

    expect(await screen.findByText('sync.banks.wiseJarsFailed')).toBeInTheDocument()
    expect(screen.queryByText('sync.banks.wiseJarsConnected')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'sync.banks.wiseJarsOpen' }))
    expect(onOpenWiseJars).toHaveBeenCalledTimes(1)
  })

  it('stays silent under other banks and never asks for the Wise status', async () => {
    givenStatuses(FAILED_CONNECTIONS, WISE_DISCONNECTED)

    renderTab({ onOpenWiseJars: vi.fn() })

    await screen.findByText('Bank A')
    expect(screen.queryByText('sync.banks.wiseJarsMissing')).not.toBeInTheDocument()
    expect(apiGet).not.toHaveBeenCalledWith('/wise/status')
  })
})

describe('Wise Jars copy across locales (#761)', () => {
  const locales = { fr, en, de, es } as const

  // None of these strings may still claim Enable Banking / the bank connection cannot see Wise Jars.
  const categoricalMissing = {
    fr: /ne passent pas par cette connexion|ne les voit pas|non couvertes/i,
    en: /do not come through this connection|cannot see/i,
    de: /kommen nicht über diese Verbindung|sieht sie nicht|nicht sieht/i,
    es: /no llegan por esta conexión|no los ve|no ve/i,
  }

  const doubleCountWarning = {
    fr: /en double/i,
    en: /twice/i,
    de: /doppelt/i,
    es: /por duplicado/i,
  }

  const notBothWarning = {
    fr: /pas les deux/i,
    en: /not connect both/i,
    de: /nicht beide/i,
    es: /no conectes ambos/i,
  }

  it.each(Object.entries(locales))(
    '%s explains Enable Banking Control Panel selection and warns against double counting',
    (lang, locale) => {
      const key = lang as keyof typeof locales
      const hints = [
        locale.sync.banks.wiseJarsMissing,
        locale.sync.banks.wiseJarsConnected,
        locale.sync.wise.intro,
      ]

      for (const text of hints) {
        expect(text).not.toMatch(categoricalMissing[key])
        expect(text).toMatch(/Enable Banking/)
        expect(text).toMatch(/Control Panel/)
        expect(text).toMatch(doubleCountWarning[key])
      }

      expect(locale.addAccount.desc.wise).not.toMatch(categoricalMissing[key])
      expect(locale.addAccount.desc.wise).toMatch(/Enable Banking/)
      expect(locale.addAccount.desc.wise).toMatch(notBothWarning[key])
    },
  )
})

describe('BankSyncTab Enable Banking auth launch (desktop vs web)', () => {
  const AUTH_LINK = 'https://tilisy.enablebanking.com/auth/session-123'
  const originalLocation = window.location
  let assignedHref: string | null = null

  function stubLocation() {
    assignedHref = null
    const loc = {
      ...originalLocation,
      pathname: '/sync',
      search: '?tab=banks',
      get href() {
        return assignedHref ?? 'http://127.0.0.1:41234/sync?tab=banks'
      },
      set href(value: string) {
        assignedHref = value
      },
    }
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: loc,
    })
  }

  function clearTauriGlobals() {
    delete (window as Window & { isTauri?: boolean }).isTauri
    delete (window as Window & { __TAURI__?: unknown }).__TAURI__
  }

  beforeEach(() => {
    stubLocation()
    clearTauriGlobals()
    useAppStore.setState({ desktopMode: false })
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockImplementation((url: string) => {
      if (url === '/sync/status') return Promise.resolve({ data: FAILED_CONNECTIONS })
      if (url === '/sync/countries') return Promise.resolve({ data: ['FR'] })
      if (url === '/sync/institutions') {
        return Promise.resolve({
          data: [{ id: 'BNP::FR::personal', name: 'BNP Paribas', country: 'FR', psuType: 'personal' }],
        })
      }
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
  })

  afterEach(() => {
    clearTauriGlobals()
    useAppStore.setState({ desktopMode: false })
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: originalLocation,
    })
  })

  it('invokes the Tauri command on desktop without leaving the main window and completes the callback', async () => {
    const invoke = vi.fn().mockResolvedValue({
      status: 'callback',
      query: 'code=eb-code-42&state=eb-state-99',
    })
    useAppStore.setState({ desktopMode: true })
    ;(window as Window & { isTauri?: boolean; __TAURI__?: unknown }).isTauri = true
    ;(window as Window & { __TAURI__?: unknown }).__TAURI__ = { core: { invoke } }

    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/initiate') return Promise.resolve({ data: { requisitionId: 'req-1', authLink: AUTH_LINK } })
      if (url === '/sync/complete') return Promise.resolve({ data: [] })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    const searchInput = await screen.findByPlaceholderText('sync.banks.searchPlaceholder')
    fireEvent.change(searchInput, { target: { value: 'BNP' } })
    const connectBtn = await screen.findByRole('button', { name: 'sync.banks.connect' })
    fireEvent.click(connectBtn)

    await waitFor(() => {
      expect(invoke).toHaveBeenCalledWith('open_enable_banking_auth', { authLink: AUTH_LINK })
    })
    expect(assignedHref).toBeNull()
    await waitFor(() => {
      expect(apiPost).toHaveBeenCalledWith('/sync/complete', {
        code: 'eb-code-42',
        state: 'eb-state-99',
      })
    })
  })

  it('re-enables Sync cleanly when the user closes the desktop auth window without completing', async () => {
    const authWindow = deferred<{ status: 'cancelled' }>()
    const invoke = vi.fn().mockReturnValue(authWindow.promise)
    useAppStore.setState({ desktopMode: true })
    ;(window as Window & { isTauri?: boolean; __TAURI__?: unknown }).isTauri = true
    ;(window as Window & { __TAURI__?: unknown }).__TAURI__ = { core: { invoke } }

    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/1/reconnect') return Promise.resolve({ data: { requisitionId: 'req-2', authLink: AUTH_LINK } })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    await screen.findByText('Bank A')
    fireEvent.click(reconnectButton('Bank A'))

    await waitFor(() => {
      expect(invoke).toHaveBeenCalledWith('open_enable_banking_auth', { authLink: AUTH_LINK })
      expect(reconnectButton('Bank A')).toBeDisabled()
    })
    expect(assignedHref).toBeNull()

    await act(async () => authWindow.resolve({ status: 'cancelled' }))
    await waitFor(() => {
      expect(reconnectButton('Bank A')).toBeEnabled()
    })
    expect(apiPost).not.toHaveBeenCalledWith('/sync/complete', expect.anything())
  })

  it('surfaces an OAuth error returned by the desktop callback query', async () => {
    const invoke = vi.fn().mockResolvedValue({
      status: 'callback',
      query: 'error=access_denied&error_description=Bank+cancelled',
    })
    useAppStore.setState({ desktopMode: true })
    ;(window as Window & { isTauri?: boolean; __TAURI__?: unknown }).isTauri = true
    ;(window as Window & { __TAURI__?: unknown }).__TAURI__ = { core: { invoke } }

    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/initiate') return Promise.resolve({ data: { requisitionId: 'req-1', authLink: AUTH_LINK } })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    const searchInput = await screen.findByPlaceholderText('sync.banks.searchPlaceholder')
    fireEvent.change(searchInput, { target: { value: 'BNP' } })
    const connectBtn = await screen.findByRole('button', { name: 'sync.banks.connect' })
    fireEvent.click(connectBtn)

    expect(await screen.findByText('sync.banks.callbackError: Bank cancelled')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/sync/complete', expect.anything())
  })

  it('keeps window.location navigation unchanged outside desktop', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/initiate') return Promise.resolve({ data: { requisitionId: 'req-1', authLink: AUTH_LINK } })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    const searchInput = await screen.findByPlaceholderText('sync.banks.searchPlaceholder')
    fireEvent.change(searchInput, { target: { value: 'BNP' } })
    const connectBtn = await screen.findByRole('button', { name: 'sync.banks.connect' })
    fireEvent.click(connectBtn)

    await waitFor(() => {
      expect(assignedHref).toBe(AUTH_LINK)
    })
  })
})

describe('BankSyncTab authorized empty session (#752)', () => {
  const NO_ACCOUNTS_CONNECTION = {
    id: 7,
    institutionId: 'Wise::BE::personal',
    institutionName: 'Test Bank',
    status: 'FAILED',
    errorCode: 'ENABLE_BANKING_NO_ACCOUNTS',
    lastSyncedAt: null,
  }

  beforeEach(() => {
    useAppStore.setState({ demoMode: false, desktopMode: false })
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockImplementation((url: string) => {
      if (url === '/sync/status') return Promise.resolve({ data: [NO_ACCOUNTS_CONNECTION] })
      if (url === '/sync/countries') return Promise.resolve({ data: ['FR'] })
      return Promise.reject(new Error(`Unexpected GET ${url}`))
    })
  })

  it('explains restricted mode, offers reconnect without retry, and passes ENABLE_BANKING_NO_ACCOUNTS to the report button', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/sync/7/reconnect') return Promise.resolve({ data: { requisitionId: 'auth-9', authLink: '' } })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()

    const label = await screen.findByText('Test Bank')
    const card = label.closest('[data-slot="card"]') as HTMLElement

    expect(within(card).getByText('sync.connectionStatus.NO_ACCOUNTS')).toBeInTheDocument()
    expect(within(card).getByRole('alert')).toHaveTextContent('sync.banks.noAccountsError')
    expect(within(card).queryByTitle('sync.banks.retry')).not.toBeInTheDocument()

    fireEvent.click(within(card).getByRole('button', { name: 'sync.banks.reconnect' }))
    await waitFor(() => {
      expect(apiPost).toHaveBeenCalledWith('/sync/7/reconnect')
    })

    fireEvent.click(within(card).getByRole('button', { name: 'sync.reportProblem.action' }))
    const href = screen.getByRole('link', { name: 'sync.reportProblem.openGithub' }).getAttribute('href') ?? ''
    const parsed = new URL(href)
    expect(parsed.searchParams.get('title')).toBe('Broken bank connector: enable-banking (ENABLE_BANKING_NO_ACCOUNTS)')
    expect(decodeURIComponent(href)).not.toContain('Test Bank')
  })

  it('shows the translated restricted-mode error when the OAuth callback returns ENABLE_BANKING_NO_ACCOUNTS', async () => {
    apiPost.mockRejectedValueOnce({
      response: {
        status: 422,
        data: { code: 'ENABLE_BANKING_NO_ACCOUNTS', detail: 'raw English backend message' },
      },
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <MemoryRouter initialEntries={['/sync/callback?code=eb-code&state=eb-state']}>
        <QueryClientProvider client={client}>
          <BankSyncTab />
        </QueryClientProvider>
      </MemoryRouter>,
    )

    expect(await screen.findByText('sync.banks.callbackError: sync.banks.noAccountsError')).toBeInTheDocument()
    expect(screen.queryByText('sync.banks.callbackDone')).not.toBeInTheDocument()
  })

  it.each(Object.entries({ fr, en, de, es }))(
    '%s mentions the Enable Banking Control Panel for restricted-mode copy',
    (_lang, locale) => {
      expect(locale.sync.connectionStatus.NO_ACCOUNTS.trim().length).toBeGreaterThan(0)
      for (const text of [
        locale.sync.banks.noAccountsError,
        locale.sync.banks.restrictedModeNote,
        locale.admin.enableBanking.restrictedModeNote,
      ]) {
        expect(text).toMatch(/Enable Banking/)
        expect(text).toMatch(/Control Panel/)
      }
    },
  )
})
