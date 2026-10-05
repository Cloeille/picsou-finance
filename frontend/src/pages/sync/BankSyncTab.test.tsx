import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

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

function renderTab() {
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

  render(<BankSyncTab />, { wrapper: Wrapper })
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
