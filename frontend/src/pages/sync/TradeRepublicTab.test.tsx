import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

const { TradeRepublicTab } = await import('./TradeRepublicTab')

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderTab() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        {children}
      </QueryClientProvider>
    )
  }

  render(<TradeRepublicTab />, { wrapper: Wrapper })
}

const OTP_INIT = { processId: 'process-123', flow: 'otp', countdownInSeconds: 120 }
const AUTHENTICATOR_INIT = { processId: 'process-123', flow: 'authenticator', countdownInSeconds: 120 }
const APP_INIT = { processId: 'process-123', flow: 'confirmInApp', countdownInSeconds: 120 }

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

/** Answers GET /tr/status with `status` and GET /accounts with `accounts`. */
function serve(status: object, accounts: object[] = []) {
  apiGet.mockImplementation((url: string) => Promise.resolve({ data: url === '/accounts' ? accounts : status }))
}

async function fillCredentials() {
  fireEvent.change(await screen.findByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  fireEvent.change(screen.getByLabelText('sync.tr.pin'), {
    target: { value: '1234' },
  })
}

describe('TradeRepublicTab authentication flow', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    serve(DISCONNECTED)
  })

  it('keeps the phone and PIN form visible when initiation fails', async () => {
    apiPost.mockRejectedValue({
      response: { status: 500, data: { detail: 'PIN_INVALID' } },
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.tr.tan')).not.toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/initiate', {
      phoneNumber: '+33612345678',
      pin: '1234',
    })
  })

  it('moves focus to the verification-code input once the PIN is accepted', async () => {
    apiPost.mockResolvedValue({ data: OTP_INIT })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    const tanInput = await screen.findByLabelText('sync.tr.tan')
    await waitFor(() => expect(tanInput).toHaveFocus())
  })

  it('keeps the verification-code step visible when TAN completion fails', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') {
        return Promise.resolve({ data: OTP_INIT })
      }
      if (url === '/tr/auth/complete') {
        return Promise.reject({
          response: { status: 500, data: { detail: 'VALIDATION_CODE_INVALID' } },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    fireEvent.change(await screen.findByLabelText('sync.tr.tan'), {
      target: { value: '9876' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    const tanInput = screen.getByLabelText('sync.tr.tan')
    expect(tanInput).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(apiPost).toHaveBeenCalledWith(
      '/tr/auth/complete',
      { processId: 'process-123', tan: '9876' },
      expect.any(Object),
    )
  })

  it('shows the code field, and no app prompt, for the otp flow', async () => {
    apiPost.mockResolvedValue({ data: OTP_INIT })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByLabelText('sync.tr.tan')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.appConfirmPrompt')).not.toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/tr/auth/complete', expect.anything(), expect.anything())
  })

  it('shows the app prompt with the countdown and completes once, without a code, for the confirmInApp flow', async () => {
    let finish: (value: unknown) => void = () => {}
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') return Promise.resolve({ data: APP_INIT })
      if (url === '/tr/auth/complete') return new Promise(resolve => { finish = resolve })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.appConfirmPrompt')).toBeInTheDocument()
    expect(screen.getByTestId('tr-countdown')).toHaveTextContent('2:00')
    expect(screen.queryByLabelText('sync.tr.tan')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/tr/auth/complete', { processId: 'process-123' }, expect.any(Object)),
    )
    expect(apiPost.mock.calls.filter(([url]) => url === '/tr/auth/complete')).toHaveLength(1)

    finish({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })
    await waitFor(() => expect(screen.queryByText('sync.tr.appConfirmPrompt')).not.toBeInTheDocument())
  })

  it.each([
    ['APP_VALIDATION_TIMEOUT', 408, 'sync.tr.errors.appValidationTimeout'],
    ['APP_VALIDATION_CANCELLED', 422, 'sync.tr.errors.appValidationCancelled'],
    ['AUTH_ATTEMPT_EXPIRED', 422, 'sync.tr.errors.authAttemptExpired'],
    ['INVALID_CREDENTIALS', 422, 'sync.tr.errors.invalidCredentials'],
    ['RATE_LIMITED', 429, 'sync.tr.errors.tooManyAttempts'],
  ])('maps %s on the in-app confirmation to its message and returns to the credentials form', async (code, status, message) => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') return Promise.resolve({ data: APP_INIT })
      return Promise.reject({ response: { status, data: { code } } })
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.appConfirmPrompt')).not.toBeInTheDocument()
  })

  it('keeps the code field after INVALID_OTP', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') return Promise.resolve({ data: OTP_INIT })
      return Promise.reject({ response: { status: 422, data: { code: 'INVALID_OTP' } } })
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))
    fireEvent.change(await screen.findByLabelText('sync.tr.tan'), { target: { value: '9876' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.tan')).toBeInTheDocument()
  })
})

describe('TradeRepublicTab password and authenticator', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    serve(DISCONNECTED)
  })

  it('sends a password with letters and more than 4 characters verbatim', async () => {
    apiPost.mockResolvedValue({ data: OTP_INIT })

    renderTab()
    fireEvent.change(await screen.findByLabelText('sync.tr.phone'), { target: { value: '+336****5678' } })
    const pin = screen.getByLabelText('sync.tr.pin')
    expect(pin).toHaveAttribute('type', 'password')
    expect(pin).toHaveAttribute('maxlength', '128')
    expect(pin).toHaveAttribute('autocomplete', 'current-password')
    fireEvent.change(pin, { target: { value: 'Sup3r-secret pass' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/tr/auth/initiate', {
        phoneNumber: '+336****5678',
        pin: 'Sup3r-secret pass',
      }),
    )
  })

  it('shows a 6-digit code field for the authenticator flow and completes with the code', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') return Promise.resolve({ data: AUTHENTICATOR_INIT })
      return Promise.resolve({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    const code = await screen.findByLabelText('sync.tr.authenticatorCode')
    expect(screen.getByText('sync.tr.authenticatorPrompt')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.appConfirmPrompt')).not.toBeInTheDocument()
    fireEvent.change(code, { target: { value: '12345678' } })
    expect(code).toHaveValue('123456')
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/tr/auth/complete',
        { processId: 'process-123', tan: '123456' },
        expect.any(Object),
      ),
    )
  })

  it('keeps the authenticator code field after INVALID_OTP', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') return Promise.resolve({ data: AUTHENTICATOR_INIT })
      return Promise.reject({ response: { status: 422, data: { code: 'INVALID_OTP' } } })
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))
    fireEvent.change(await screen.findByLabelText('sync.tr.authenticatorCode'), { target: { value: '654321' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.authenticatorCode')).toBeInTheDocument()
  })
})

// SESSION_EXPIRED ends the session (isActive false) but keeps its row and the stored phone, so
// the login form under the expiry message asks only for the PIN or password.
describe('TradeRepublicTab reconnect', () => {
  const EXPIRED = {
    ...DISCONNECTED,
    isActive: false,
    syncStatus: 'FAILED',
    lastSyncError: 'SESSION_EXPIRED',
    maskedPhone: '+33 * ** ** ** 78',
  }

  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    serve(EXPIRED)
  })

  it('asks for the PIN or password only, shows the masked phone and sends just the pin', async () => {
    let finish: (value: unknown) => void = () => {}
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/reconnect') return Promise.resolve({ data: APP_INIT })
      if (url === '/tr/auth/complete') return new Promise(resolve => { finish = resolve })
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()

    expect(await screen.findByText('sync.tr.errors.sessionExpired')).toBeInTheDocument()
    expect(screen.getByTestId('tr-masked-phone')).toHaveTextContent('+33 * ** ** ** 78')
    expect(screen.queryByLabelText('sync.tr.phone')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('sync.tr.pin'), { target: { value: 'my password' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.appConfirmPrompt')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/reconnect', { pin: 'my password' })
    expect(apiPost).not.toHaveBeenCalledWith('/tr/auth/initiate', expect.anything())
    expect(apiDelete).not.toHaveBeenCalled()

    finish({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED', maskedPhone: EXPIRED.maskedPhone } })
    await waitFor(() => expect(screen.queryByText('sync.tr.appConfirmPrompt')).not.toBeInTheDocument())
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/complete', { processId: 'process-123' }, expect.any(Object))
  })

  it('falls back to the full login form on INVALID_SESSION_STATE', async () => {
    apiPost.mockRejectedValue({ response: { status: 409, data: { code: 'INVALID_SESSION_STATE' } } })

    renderTab()

    fireEvent.change(await screen.findByLabelText('sync.tr.pin'), { target: { value: 'pw' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByTestId('tr-masked-phone')).not.toBeInTheDocument()
  })
})

describe('TradeRepublicTab session status', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
  })

  // The backend ends the session on SESSION_EXPIRED: the status comes back inactive, so the
  // reconnection is the login form itself, under the expiry message.
  it('offers the login form under the expiry message once the session expired', async () => {
    serve({ ...DISCONNECTED, isActive: false, syncStatus: 'FAILED', lastSyncError: 'SESSION_EXPIRED' })

    renderTab()

    expect(await screen.findByText('sync.tr.errors.sessionExpired')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'sync.tr.clearSession' })).not.toBeInTheDocument()
  })

  // An unreadable stored session also ends it (NEEDS_REAUTH in the status report): same advice.
  it('asks to reconnect when the stored session could not be read', async () => {
    serve({ ...DISCONNECTED, isActive: false, syncStatus: 'FAILED', lastSyncError: 'INVALID_SESSION_STATE' })

    renderTab()

    expect(await screen.findByText('sync.tr.errors.sessionExpired')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
  })

  it('says the imported accounts are no longer updated when no session is left', async () => {
    serve(DISCONNECTED, [{ id: 1, provider: 'Trade Republic', isManual: false }])

    renderTab()

    expect(await screen.findByText('sync.tr.staleAccounts')).toBeInTheDocument()
  })

  it('stays quiet about stale accounts without any Trade Republic account', async () => {
    serve(DISCONNECTED, [{ id: 2, provider: 'Trade Republic', isManual: true }])

    renderTab()

    expect(await screen.findByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.staleAccounts')).not.toBeInTheDocument()
  })

  it('stays quiet about accounts only the CSV import created', async () => {
    serve(DISCONNECTED, [{ id: 3, provider: 'Trade Republic', isManual: false, externalAccountId: 'tr_csv_compte_titres' }])

    renderTab()

    expect(await screen.findByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByText('sync.tr.staleAccounts')).not.toBeInTheDocument()
  })

  it('shows the queued state after a sync is requested', async () => {
    serve({ ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' })

    renderTab()

    expect(await screen.findByText('sync.tr.queued')).toBeInTheDocument()
  })
})
