import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

// Interpolation options are appended to the key so tests can assert on them.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options && Object.keys(options).length > 0 ? `${key} ${JSON.stringify(options)}` : key,
  }),
}))

const { CaisseEpargneTab } = await import('./CaisseEpargneTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
}

const INITIATED = {
  processId: 'p1',
  mfaRequired: true,
  mfaType: 'SECURPASS',
  expiresInSeconds: 300,
}

let queryClient: QueryClient

function renderTab() {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  render(<CaisseEpargneTab />, { wrapper: Wrapper })
}

function problem(status: number, code: string, headers: Record<string, string> = {}) {
  return {
    response: { status, headers, data: { code, detail: 'raw upstream detail 10.0.0.1' } },
  }
}

async function typeCredentials(customerId = '12345678', password = '654321') {
  fireEvent.change(await screen.findByLabelText('sync.caisseEpargne.customerId'), {
    target: { value: customerId },
  })
  fireEvent.change(await screen.findByLabelText('sync.caisseEpargne.password'), {
    target: { value: password },
  })
}

async function signIn(customerId?: string, password?: string) {
  await typeCredentials(customerId, password)
  fireEvent.click(screen.getByText('sync.caisseEpargne.connect'))
}

const passwordInput = () => screen.getByLabelText('sync.caisseEpargne.password') as HTMLInputElement

describe('CaisseEpargneTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('login form', () => {
    it('uses a numeric, non-autofilled password field', async () => {
      renderTab()
      await typeCredentials()

      const field = passwordInput()
      expect(field).toHaveAttribute('type', 'password')
      expect(field).toHaveAttribute('inputmode', 'numeric')
      expect(field).toHaveAttribute('autocomplete', 'off')
    })

    it('warns that the password is never stored, never retried, and can lock the account', async () => {
      renderTab()
      await screen.findByLabelText('sync.caisseEpargne.customerId')

      expect(screen.getByText('sync.caisseEpargne.notice.neverStored')).toBeInTheDocument()
      expect(screen.getByText('sync.caisseEpargne.notice.noRetry')).toBeInTheDocument()
      expect(screen.getByText('sync.caisseEpargne.notice.lockRisk')).toBeInTheDocument()
    })

    it('keeps the credentials numeric', async () => {
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn('12ab34', '56cd78')

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/initiate', {
          customerId: '1234',
          password: '5678',
        }),
      )
    })

    it('clears the password as soon as the request is sent, before any answer', async () => {
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn()

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(passwordInput().value).toBe(''))
    })

    it('does not keep the password in the mutation cache once the call settled', async () => {
      apiPost.mockRejectedValueOnce(problem(401, 'INVALID_CREDENTIALS'))
      renderTab()
      await signIn('12345678', '987654')

      await screen.findByText('sync.caisseEpargne.errors.invalidCredentials')
      await waitFor(() => {
        const cached = JSON.stringify(
          queryClient
            .getMutationCache()
            .getAll()
            .map(m => m.state.variables),
        )
        expect(cached).not.toContain('987654')
      })
    })
  })

  describe('no retry', () => {
    it('sends initiate once after a rejected login, shows the form again, password empty', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost.mockRejectedValueOnce(problem(401, 'INVALID_CREDENTIALS'))
      renderTab()
      await signIn()

      expect(
        await screen.findByText('sync.caisseEpargne.errors.invalidCredentials'),
      ).toBeInTheDocument()
      expect(screen.queryByText('raw upstream detail 10.0.0.1')).not.toBeInTheDocument()
      expect(passwordInput().value).toBe('')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10 * 60_000)
      })
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('never calls complete when initiate failed', async () => {
      apiPost.mockRejectedValueOnce(problem(409, 'KEYPAD_CHANGED'))
      renderTab()
      await signIn()

      await screen.findByText('sync.caisseEpargne.errors.keypadChanged')
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).not.toHaveBeenCalledWith(
        '/caisse-epargne/auth/complete',
        expect.anything(),
      )
    })
  })

  describe('Sécur’Pass wait', () => {
    it('asks for the phone approval and counts down from the real 150 s human wait, not the 300 s process lifetime', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockReturnValueOnce(new Promise(() => {}))

      renderTab()
      await signIn()

      expect(await screen.findByText('sync.caisseEpargne.securPassPrompt')).toBeInTheDocument()
      expect(screen.getByTestId('caisse-epargne-countdown')).toHaveTextContent('2:30')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      expect(screen.getByTestId('caisse-epargne-countdown')).toHaveTextContent('2:28')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000)
      })
      expect(screen.getByTestId('caisse-epargne-countdown')).toHaveTextContent('2:18')
    })

    it('makes one long complete call with the processId and never polls or retries it', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockReturnValueOnce(new Promise(() => {}))

      renderTab()
      await signIn()

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/complete', {
          processId: 'p1',
        }),
      )
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 60_000)
      })

      expect(apiPost).toHaveBeenCalledTimes(2)
    })

    it('shows the connected state once complete answers connected', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: { connected: true } })

      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED })
        .mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
      renderTab()
      await signIn()

      expect(await screen.findByText('sync.caisseEpargne.sessionActive')).toBeInTheDocument()
      expect(screen.queryByText('sync.caisseEpargne.securPassPrompt')).not.toBeInTheDocument()
    })

    it.each([
      ['APP_VALIDATION_TIMEOUT', 408, 'appValidationTimeout'],
      ['AUTH_ATTEMPT_EXPIRED', 410, 'authAttemptExpired'],
    ])('explains %s and offers the form again without retrying', async (code, status, suffix) => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(status, code))

      renderTab()
      await signIn()

      expect(await screen.findByText(`sync.caisseEpargne.errors.${suffix}`)).toBeInTheDocument()
      expect(await screen.findByLabelText('sync.caisseEpargne.password')).toBeInTheDocument()
      expect(passwordInput().value).toBe('')
      expect(screen.queryByText('sync.caisseEpargne.securPassPrompt')).not.toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(2)
    })
  })

  describe('error mapping', () => {
    it('says the bank changed its login page and nothing was sent', async () => {
      apiPost.mockRejectedValueOnce(problem(409, 'KEYPAD_CHANGED'))
      renderTab()
      await signIn()

      expect(await screen.findByText('sync.caisseEpargne.errors.keypadChanged')).toBeInTheDocument()
    })

    it('shows the Retry-After wait in minutes when rate limited', async () => {
      apiPost.mockRejectedValueOnce(problem(429, 'RATE_LIMITED', { 'retry-after': '600' }))
      renderTab()
      await signIn()

      expect(
        await screen.findByText('sync.caisseEpargne.errors.rateLimitedRetryAfter {"minutes":10}'),
      ).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('falls back to a plain rate-limit message without Retry-After', async () => {
      apiPost.mockRejectedValueOnce({ response: { status: 429, headers: {}, data: {} } })
      renderTab()
      await signIn()

      expect(await screen.findByText('sync.caisseEpargne.errors.rateLimited')).toBeInTheDocument()
    })

    it('reads the code from the problem detail when no code field is sent', async () => {
      apiPost.mockRejectedValueOnce({
        response: { status: 401, headers: {}, data: { detail: 'INVALID_CREDENTIALS' } },
      })
      renderTab()
      await signIn()

      expect(
        await screen.findByText('sync.caisseEpargne.errors.invalidCredentials'),
      ).toBeInTheDocument()
    })

    it('uses a generic message instead of leaking an unknown server error', async () => {
      apiPost.mockRejectedValueOnce({
        response: {
          status: 500,
          headers: {},
          data: { detail: 'java.lang.NullPointerException at com.picsou.X' },
        },
      })
      renderTab()
      await signIn()

      expect(await screen.findByText('sync.caisseEpargne.errors.serverError')).toBeInTheDocument()
      expect(screen.queryByText(/NullPointer/)).not.toBeInTheDocument()
    })

    it('reports a failed background sync from the polled status alone', async () => {
      apiGet.mockResolvedValue({
        data: { ...DISCONNECTED, isActive: true, syncStatus: 'FAILED', lastSyncError: 'SESSION_EXPIRED' },
      })
      renderTab()

      expect(await screen.findByText('sync.caisseEpargne.errors.sessionExpired')).toBeInTheDocument()
    })
  })

  describe('connected', () => {
    const CONNECTED = {
      ...DISCONNECTED,
      isActive: true,
      syncStatus: 'SUCCESS',
      lastSyncCompletedAt: '2026-10-07T08:00:00Z',
      unsupported: [
        { externalId: 'SECRET-CONTRACT-ID-1', familyCode: '0123' },
        { externalId: 'SECRET-CONTRACT-ID-2', familyCode: '0456' },
      ],
      unsupportedCount: 2,
    }

    it('hides the login form and syncs on demand with POST /sync', async () => {
      apiGet.mockResolvedValue({ data: CONNECTED })
      apiPost.mockResolvedValueOnce({ data: { ...CONNECTED, syncStatus: 'QUEUED' } })
      renderTab()

      fireEvent.click(await screen.findByText('sync.caisseEpargne.sync'))

      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/sync'))
      expect(screen.queryByLabelText('sync.caisseEpargne.password')).not.toBeInTheDocument()
    })

    it('lists unsupported contracts by family code only', async () => {
      apiGet.mockResolvedValue({ data: CONNECTED })
      renderTab()

      expect(await screen.findByText('0123')).toBeInTheDocument()
      expect(screen.getByText('0456')).toBeInTheDocument()
      expect(screen.queryByText(/SECRET-CONTRACT-ID/)).not.toBeInTheDocument()
    })

    it('asks for confirmation that the bank session may stay active before disconnecting', async () => {
      apiGet.mockResolvedValue({ data: CONNECTED })
      apiDelete.mockResolvedValue({
        data: { removed: true, bankSessionRevoked: false, message: 'm' },
      })
      renderTab()

      fireEvent.click(await screen.findByText('sync.caisseEpargne.clearSession'))

      expect(
        await screen.findByText('sync.caisseEpargne.disconnectConfirm'),
      ).toBeInTheDocument()
      expect(apiDelete).not.toHaveBeenCalled()

      fireEvent.click(screen.getByText('sync.caisseEpargne.disconnectConfirmAction'))

      await waitFor(() =>
        expect(apiDelete).toHaveBeenCalledWith('/caisse-epargne/session'),
      )
    })

    it('does not delete when the confirmation is cancelled', async () => {
      apiGet.mockResolvedValue({ data: CONNECTED })
      renderTab()

      fireEvent.click(await screen.findByText('sync.caisseEpargne.clearSession'))
      fireEvent.click(await screen.findByText('common.cancel'))

      expect(apiDelete).not.toHaveBeenCalled()
      expect(screen.queryByText('sync.caisseEpargne.disconnectConfirm')).not.toBeInTheDocument()
    })
  })
})
