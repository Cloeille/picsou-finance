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

const { AmundiTab } = await import('./AmundiTab')

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
  render(<AmundiTab />, { wrapper: Wrapper })
}

async function signIn() {
  fireEvent.change(await screen.findByLabelText('sync.amundi.login'), {
    target: { value: 'user-1' },
  })
  fireEvent.change(await screen.findByLabelText('sync.amundi.password'), {
    target: { value: 'secret' },
  })
  fireEvent.click(screen.getByText('sync.amundi.connect'))
}

describe('AmundiTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  it('asks for the SMS code when Amundi sends one', async () => {
    apiPost.mockResolvedValueOnce({
      data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' },
    })

    renderTab()
    await signIn()

    expect(await screen.findByLabelText('sync.amundi.otpCode')).toBeInTheDocument()
    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/amundi/auth/initiate', {
        login: 'user-1',
        password: 'secret',
      }),
    )
  })

  it('submits the typed SMS code against the pending attempt', async () => {
    apiPost
      .mockResolvedValueOnce({ data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' } })
      .mockResolvedValueOnce({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })

    renderTab()
    await signIn()
    fireEvent.change(await screen.findByLabelText('sync.amundi.otpCode'), {
      target: { value: '123456' },
    })
    fireEvent.click(screen.getByText('sync.amundi.validate'))

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/amundi/auth/complete', {
        processId: 'p1',
        code: '123456',
      }),
    )
  })

  /**
   * An app push has nothing to type: the completion call goes out on its own
   * and stays open while the user approves on their phone.
   */
  it('waits on the app without a code when Amundi pushes a notification', async () => {
    apiPost
      .mockResolvedValueOnce({ data: { processId: 'p2', mfaRequired: true, mfaType: 'APP_PUSH' } })
      .mockReturnValueOnce(new Promise(() => {}))

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.amundi.appValidationPrompt')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.amundi.otpCode')).not.toBeInTheDocument()
    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/amundi/auth/complete', {
        processId: 'p2',
        code: undefined,
      }),
    )
  })

  it('translates a backend error code instead of leaking its message', async () => {
    apiPost.mockRejectedValueOnce({
      response: { status: 422, data: { code: 'CAPTCHA_BLOCKED', detail: 'raw upstream detail' } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.amundi.errors.captchaBlocked')).toBeInTheDocument()
    expect(screen.queryByText('raw upstream detail')).not.toBeInTheDocument()
  })

  it('reports a failed background sync from the polled status alone', async () => {
    apiGet.mockResolvedValue({
      data: { ...DISCONNECTED, isActive: true, syncStatus: 'FAILED', lastSyncError: 'PORTFOLIO_INCOMPLETE' },
    })

    renderTab()

    expect(
      await screen.findByText('sync.amundi.errors.portfolioIncomplete'),
    ).toBeInTheDocument()
  })

  it('offers sync and disconnect once a session is active', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiDelete.mockResolvedValue({ data: null })

    renderTab()

    fireEvent.click(await screen.findByText('sync.amundi.clearSession'))

    await vi.waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/amundi/session'))
  })
})
