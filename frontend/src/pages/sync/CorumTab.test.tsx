import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{`EUR ${value}`}</span>,
}))

const { CorumTab } = await import('./CorumTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<CorumTab />, { wrapper: Wrapper })
}

async function signIn() {
  fireEvent.change(await screen.findByLabelText('sync.corum.login'), {
    target: { value: '000000' },
  })
  fireEvent.change(await screen.findByLabelText('sync.corum.password'), {
    target: { value: 'secret' },
  })
  fireEvent.click(screen.getByText('sync.corum.connect'))
}

describe('CorumTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  /**
   * CORUM asks for no second factor, so the sign-in is one call and the panel
   * must never fall through to a code prompt.
   */
  it('connects in a single call and never asks for a code', async () => {
    apiPost.mockResolvedValueOnce({
      data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' },
    })

    renderTab()
    await signIn()

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/corum/auth', {
        login: '000000',
        password: 'secret',
      }),
    )
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(screen.queryByLabelText('sync.corum.otpCode')).not.toBeInTheDocument()
    expect(screen.queryByText('sync.corum.appValidationPrompt')).not.toBeInTheDocument()
  })

  it('translates a backend error code instead of leaking its message', async () => {
    apiPost.mockRejectedValueOnce({
      response: { status: 401, data: { code: 'INVALID_CREDENTIALS', detail: 'raw upstream detail' } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.corum.errors.invalidCredentials')).toBeInTheDocument()
    expect(screen.queryByText('raw upstream detail')).not.toBeInTheDocument()
  })

  /**
   * One CORUM login can hold several real-estate contracts, and the sync
   * refuses to guess which one. That has to read as its own message, not as a
   * generic server error.
   */
  it('explains a login holding more than one contract', async () => {
    apiPost.mockRejectedValueOnce({
      response: { status: 409, data: { code: 'MULTIPLE_CONTRACTS', detail: 'raw upstream detail' } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.corum.errors.multipleContracts')).toBeInTheDocument()
  })

  it('reports a failed background sync from the polled status alone', async () => {
    apiGet.mockResolvedValue({
      data: { ...DISCONNECTED, isActive: true, syncStatus: 'FAILED', lastSyncError: 'PORTFOLIO_INCOMPLETE' },
    })

    renderTab()

    expect(await screen.findByText('sync.corum.errors.portfolioIncomplete')).toBeInTheDocument()
  })

  it('queues a sync on an active session', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiPost.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })

    renderTab()
    fireEvent.click(await screen.findByText('sync.corum.sync'))

    await vi.waitFor(() => expect(apiPost).toHaveBeenCalledWith('/corum/sync'))
  })

  it('offers disconnect once a session is active', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiDelete.mockResolvedValue({ data: null })

    renderTab()

    fireEvent.click(await screen.findByText('sync.corum.clearSession'))

    await vi.waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/corum/session'))
  })

  describe('after a successful sync', () => {
    const RUN = '2026-11-05T08:00:00Z'
    const SUCCESS = { ...DISCONNECTED, isActive: true, syncStatus: 'SUCCESS', lastSyncCompletedAt: RUN }
    const lifeAccount = {
      id: 7, name: 'CORUM Life', type: 'ASSURANCE_VIE', provider: 'CORUM', currency: 'EUR',
      currentBalance: 5000, currentBalanceEur: 5000, lastSyncedAt: RUN, isManual: false,
    }
    const scpiFund = {
      provider: 'CORUM', fundCode: 'US', label: 'CORUM USA', ignored: false, lastOutcome: 'CREATED',
      lastSeenAt: RUN, lastSyncedAt: RUN, accountId: 1, accountName: 'CORUM USA',
    }

    function serve(accounts: unknown[], funds: unknown[]) {
      apiGet.mockImplementation((url: string) => {
        if (url === '/accounts') return Promise.resolve({ data: accounts })
        if (url === '/scpi/funds') return Promise.resolve({ data: funds })
        return Promise.resolve({ data: SUCCESS })
      })
    }

    it('shows no SCPI warning for a login holding only a life-insurance contract', async () => {
      serve([lifeAccount], [])
      renderTab()

      expect(await screen.findByText('sync.corum.life.synced')).toBeInTheDocument()
      expect(screen.getByText('EUR 5000')).toBeInTheDocument()
      expect(screen.queryByText('scpi.sync.noFund')).not.toBeInTheDocument()
    })

    it('waits for the accounts before warning that no fund was found', async () => {
      let resolveAccounts: (value: { data: unknown[] }) => void = () => {}
      apiGet.mockImplementation((url: string) => {
        if (url === '/accounts') return new Promise(resolve => { resolveAccounts = resolve })
        if (url === '/scpi/funds') return Promise.resolve({ data: [] })
        return Promise.resolve({ data: SUCCESS })
      })
      renderTab()

      // The funds have loaded empty, but the accounts may still hold a life contract.
      await vi.waitFor(() => expect(apiGet).toHaveBeenCalledWith('/scpi/funds'))
      await screen.findByText('sync.corum.clearSession')
      await new Promise(resolve => setTimeout(resolve, 20))
      expect(screen.queryByText('scpi.sync.noFund')).not.toBeInTheDocument()

      resolveAccounts({ data: [] })
      expect(await screen.findByText('scpi.sync.noFund')).toBeInTheDocument()
    })

    it('still warns that no fund was found when the accounts cannot be read', async () => {
      apiGet.mockImplementation((url: string) => {
        if (url === '/accounts') return Promise.reject(new Error('accounts unavailable'))
        if (url === '/scpi/funds') return Promise.resolve({ data: [] })
        return Promise.resolve({ data: SUCCESS })
      })
      renderTab()

      expect(await screen.findByText('scpi.sync.noFund')).toBeInTheDocument()
    })

    it('warns about a life contract left unwritten instead of claiming success', async () => {
      apiGet.mockImplementation((url: string) => {
        if (url === '/accounts') return Promise.resolve({ data: [] })
        if (url === '/scpi/funds') return Promise.resolve({ data: [] })
        return Promise.resolve({ data: { ...SUCCESS, lifeAmbiguousCount: 1 } })
      })
      renderTab()

      expect(await screen.findByText('sync.corum.life.ambiguous')).toBeInTheDocument()
      expect(screen.queryByText('sync.corum.life.synced')).not.toBeInTheDocument()
      expect(screen.queryByText('scpi.sync.noFund')).not.toBeInTheDocument()
    })

    it('reports both the SCPI funds and the life-insurance account', async () => {
      serve([lifeAccount], [scpiFund])
      renderTab()

      expect(await screen.findByText('sync.corum.life.synced')).toBeInTheDocument()
      expect(screen.getByText(/scpi.sync.found/)).toBeInTheDocument()
    })
  })
})
