import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
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
