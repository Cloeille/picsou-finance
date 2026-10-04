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

const { SofidyTab } = await import('./SofidyTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

const PENDING = {
  ...DISCONNECTED,
  processId: 'p-1',
  mfaRequired: true,
  mfaType: 'EMAIL',
}

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<SofidyTab />, { wrapper: Wrapper })
}

async function signIn() {
  fireEvent.change(await screen.findByLabelText('sync.sofidy.login'), {
    target: { value: '000000' },
  })
  fireEvent.change(await screen.findByLabelText('sync.sofidy.password'), {
    target: { value: 'secret' },
  })
  fireEvent.click(screen.getByText('sync.sofidy.connect'))
}

describe('SofidyTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  /**
   * The load-bearing difference from CORUM: Sofidy emails a code and refuses the
   * session until it is typed. A panel that treated the first response as a
   * connection would show a live session the portal has not opened.
   */
  it('asks for the emailed code instead of connecting on the first call', async () => {
    apiPost.mockResolvedValueOnce({ data: PENDING })

    renderTab()
    await signIn()

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/sofidy/auth/initiate', {
        associateCode: '000000',
        password: 'secret',
      }),
    )
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(await screen.findByLabelText('sync.sofidy.otpCode')).toBeInTheDocument()
    // The code comes by e-mail, so there is no app to open and no app prompt.
    expect(screen.queryByText('sync.sofidy.appValidationPrompt')).not.toBeInTheDocument()
  })

  it('opens the session once the code is validated', async () => {
    apiPost.mockResolvedValueOnce({ data: PENDING })
    apiPost.mockResolvedValueOnce({
      data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' },
    })

    renderTab()
    await signIn()
    fireEvent.change(await screen.findByLabelText('sync.sofidy.otpCode'), {
      target: { value: '123456' },
    })
    fireEvent.click(screen.getByText('sync.sofidy.validate'))

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/sofidy/auth/complete', {
        processId: 'p-1',
        code: '123456',
      }),
    )
  })

  it('translates a rejected code instead of leaking the message', async () => {
    apiPost.mockResolvedValueOnce({ data: PENDING })
    apiPost.mockRejectedValueOnce({
      response: { status: 401, data: { code: 'MFA_INVALID', detail: 'raw upstream detail' } },
    })

    renderTab()
    await signIn()
    fireEvent.change(await screen.findByLabelText('sync.sofidy.otpCode'), {
      target: { value: '000000' },
    })
    fireEvent.click(screen.getByText('sync.sofidy.validate'))

    expect(await screen.findByText('sync.sofidy.errors.invalidCode')).toBeInTheDocument()
    expect(screen.queryByText('raw upstream detail')).not.toBeInTheDocument()
  })

  /**
   * A Sofidy account parked on its brute-force counter answers a login attempt
   * with something that looks like a success. That has to read as its own
   * message, or the user waits for a code that was never sent.
   */
  it('explains an account whose login Sofidy is throttling', async () => {
    apiPost.mockRejectedValueOnce({
      response: { status: 429, data: { code: 'RATE_LIMITED', detail: 'raw upstream detail' } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.sofidy.errors.tooManyAttempts')).toBeInTheDocument()
  })

  it('reports a failed background sync from the polled status alone', async () => {
    apiGet.mockResolvedValue({
      data: {
        ...DISCONNECTED,
        isActive: true,
        syncStatus: 'FAILED',
        lastSyncError: 'SESSION_EXPIRED',
      },
    })

    renderTab()

    expect(await screen.findByText('sync.sofidy.errors.sessionExpired')).toBeInTheDocument()
  })

  it('queues a sync on an active session', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiPost.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })

    renderTab()
    fireEvent.click(await screen.findByText('sync.sofidy.sync'))

    await vi.waitFor(() => expect(apiPost).toHaveBeenCalledWith('/sofidy/sync'))
  })

  it('offers disconnect once a session is active', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiDelete.mockResolvedValue({ data: null })

    renderTab()

    fireEvent.click(await screen.findByText('sync.sofidy.clearSession'))

    await vi.waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/sofidy/session'))
  })
})
