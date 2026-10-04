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
})
