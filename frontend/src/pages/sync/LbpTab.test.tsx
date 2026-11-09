import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
vi.mock('react-i18next', () => {
  const t = (key: string, options?: Record<string, unknown>) =>
    options && Object.keys(options).length > 0 ? `${key} ${JSON.stringify(options)}` : key
  return { useTranslation: () => ({ t }) }
})

const { LbpTab } = await import('./LbpTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
  savedIdentifier: null,
}

const ACTIVE = { ...DISCONNECTED, isActive: true }

const KEYS = ['5', '3', '9', '8', '6', '1', '0', '7', '2', '4']

const INITIATED = {
  processId: 'p1',
  keypad: { keys: KEYS, columns: 5 },
  expiresInSeconds: 300,
}

const KEYPAD_DONE = { processId: 'p1', status: 'APPROVAL_PENDING' }

function renderTab() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return render(<LbpTab />, { wrapper: Wrapper })
}

function problem(status: number, code: string) {
  return { response: { status, headers: {}, data: { code, detail: 'raw upstream detail' } } }
}

async function signIn(customerId = '1234567890') {
  fireEvent.change(await screen.findByLabelText('sync.lbp.customerId'), {
    target: { value: customerId },
  })
  fireEvent.click(screen.getByText('sync.lbp.connect'))
}

const keyButtons = () => within(screen.getByTestId('lbp-keypad-grid')).getAllByRole('button')
const validateButton = () => screen.getByText('sync.lbp.keypad.validate').closest('button')!

describe('LbpTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('asks for the identifier only, never a password', async () => {
    renderTab()
    await signIn('1234567890')

    expect(screen.getByLabelText('sync.lbp.customerId')).toBeInTheDocument()
    expect(document.querySelector('input[type="password"]')).toBeNull()
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/lbp/auth/initiate', {
        customerId: '1234567890',
        rememberIdentifier: false,
      }),
    )
  })

  it('refuses an identifier that is too short before calling the bank', async () => {
    renderTab()
    await signIn('1234')

    expect(screen.getByText('sync.lbp.errors.invalidIdentifier')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/lbp/auth/initiate', expect.anything())
  })

  it('prefills the identifier the user asked to remember', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, savedIdentifier: '9876543210' } })
    renderTab()

    await waitFor(() =>
      expect(screen.getByLabelText('sync.lbp.customerId')).toHaveValue('9876543210'),
    )
    expect(screen.getByLabelText('sync.lbp.rememberIdentifier')).toBeChecked()

    fireEvent.click(screen.getByText('sync.lbp.connect'))
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/lbp/auth/initiate', {
        customerId: '9876543210',
        rememberIdentifier: true,
      }),
    )
  })

  it('keeps the remembered identifier when the session expires after a login', async () => {
    apiGet
      .mockResolvedValueOnce({ data: DISCONNECTED })
      .mockResolvedValue({ data: { ...DISCONNECTED, savedIdentifier: '9876543210' } })
    apiPost.mockImplementation((url: string) => {
      if (url === '/lbp/auth/initiate') return Promise.resolve({ data: INITIATED })
      if (url === '/lbp/auth/keypad') return Promise.resolve({ data: KEYPAD_DONE })
      if (url === '/lbp/auth/complete') {
        return Promise.resolve({ data: { ...ACTIVE, savedIdentifier: '9876543210' } })
      }
      if (url === '/lbp/sync') {
        return Promise.resolve({
          data: { ...ACTIVE, savedIdentifier: '9876543210', syncStatus: 'SUCCESS' },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    await signIn('9876543210')
    await screen.findByTestId('lbp-keypad-grid')
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }
    fireEvent.click(validateButton())

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/lbp/sync'))
    await waitFor(() =>
      expect(screen.getByLabelText('sync.lbp.customerId')).toHaveValue('9876543210'),
    )
  })

  it('renders the shuffled digit labels and sends clicked positions', async () => {
    apiGet.mockResolvedValueOnce({ data: DISCONNECTED }).mockResolvedValue({ data: ACTIVE })
    let resolveComplete!: (value: unknown) => void
    const completePromise = new Promise(resolve => {
      resolveComplete = resolve
    })
    apiPost.mockImplementation((url: string) => {
      if (url === '/lbp/auth/initiate') return Promise.resolve({ data: INITIATED })
      if (url === '/lbp/auth/keypad') return Promise.resolve({ data: KEYPAD_DONE })
      if (url === '/lbp/auth/complete') return completePromise
      if (url === '/lbp/sync') return Promise.resolve({ data: { ...ACTIVE, syncStatus: 'QUEUED' } })
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    await signIn()
    await screen.findByTestId('lbp-keypad-grid')

    expect(keyButtons().map(button => button.textContent)).toEqual(KEYS)
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }
    fireEvent.click(validateButton())

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/lbp/auth/keypad', {
        processId: 'p1',
        positions: [0, 1, 2, 3, 4, 5],
      }),
    )
    expect(await screen.findByText('sync.lbp.securPassPrompt')).toBeInTheDocument()
    resolveComplete({ data: ACTIVE })
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/lbp/sync'))
  })

  it('refuses a keypad that is not ten distinct digits', async () => {
    apiPost.mockResolvedValueOnce({
      data: { ...INITIATED, keypad: { keys: ['1'], columns: 5 } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.lbp.errors.formatChanged')).toBeInTheDocument()
    expect(screen.queryByTestId('lbp-keypad-grid')).not.toBeInTheDocument()
  })

  it('refuses ten duplicate keypad labels', async () => {
    apiPost.mockResolvedValueOnce({
      data: { ...INITIATED, keypad: { keys: Array(10).fill('1'), columns: 5 } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.lbp.errors.formatChanged')).toBeInTheDocument()
    expect(screen.queryByTestId('lbp-keypad-grid')).not.toBeInTheDocument()
  })

  it('refuses ten labels containing a non-digit', async () => {
    apiPost.mockResolvedValueOnce({
      data: { ...INITIATED, keypad: { keys: [...KEYS.slice(0, 9), 'x'], columns: 5 } },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.lbp.errors.formatChanged')).toBeInTheDocument()
    expect(screen.queryByTestId('lbp-keypad-grid')).not.toBeInTheDocument()
  })

  it('maps a changed keypad to its message instead of retrying', async () => {
    apiPost
      .mockResolvedValueOnce({ data: INITIATED })
      .mockRejectedValueOnce(problem(409, 'KEYPAD_CHANGED'))

    renderTab()
    await signIn()
    await screen.findByTestId('lbp-keypad-grid')
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }
    fireEvent.click(validateButton())

    expect(await screen.findByText('sync.lbp.errors.keypadChanged')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(2)
  })

  it('queues the first sync for a stored session that never synced', async () => {
    apiGet.mockResolvedValue({ data: ACTIVE })
    apiPost.mockResolvedValue({ data: { ...ACTIVE, syncStatus: 'QUEUED' } })

    renderTab()

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/lbp/sync'))
  })

  it('does not re-queue a first sync after a failed one', async () => {
    apiGet.mockResolvedValue({
      data: { ...ACTIVE, syncStatus: 'FAILED', lastSyncError: 'UPSTREAM_UNAVAILABLE' },
    })

    renderTab()

    await screen.findByText('sync.lbp.sessionActive')
    expect(apiPost).not.toHaveBeenCalledWith('/lbp/sync')
  })

  it('queues the first sync once when a lost completion later finds a live session', async () => {
    // The completion request got a 502 and never answered, but the backend stored
    // the session: the next status read sees it and queues the first sync.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let live = false
    apiGet.mockImplementation(async () => ({
      data: live ? { ...ACTIVE, syncStatus: 'RUNNING' } : DISCONNECTED,
    }))
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/lbp/auth/initiate') return { data: INITIATED }
      if (url === '/lbp/auth/keypad') return { data: KEYPAD_DONE }
      if (url === '/lbp/auth/complete') throw problem(502, 'UPSTREAM_UNAVAILABLE')
      return { data: { ...ACTIVE, syncStatus: 'QUEUED' } }
    })
    renderTab()
    await signIn()
    await screen.findByTestId('lbp-keypad-grid')
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }
    fireEvent.click(validateButton())
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/lbp/auth/complete', { processId: 'p1' }, { timeout: 180_000 }),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    expect(screen.getByText('sync.lbp.securPassPrompt')).toBeInTheDocument()
    expect(screen.getByTestId('lbp-countdown')).toHaveTextContent(/^2:5\d$/)
    expect(apiGet).toHaveBeenCalledWith('/lbp/status', {
      skipGlobalErrorRedirect: true,
      timeout: 10_000,
    })
    expect(apiPost).not.toHaveBeenCalledWith('/lbp/sync')

    live = true
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/lbp/sync'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9_000)
    })
    expect(apiPost.mock.calls.filter(c => c[0] === '/lbp/sync')).toHaveLength(1)
    expect(apiPost.mock.calls.filter(c => c[0] === '/lbp/auth/complete')).toHaveLength(1)
  })

  it('reports a timeout 180 s after a lost completion that never brings a session', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const NETWORK_ERROR = Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' })
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/lbp/auth/initiate') return { data: INITIATED }
      if (url === '/lbp/auth/keypad') return { data: KEYPAD_DONE }
      throw NETWORK_ERROR
    })
    renderTab()
    await signIn()
    await screen.findByTestId('lbp-keypad-grid')
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }
    fireEvent.click(validateButton())
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(3))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(177_000)
    })
    expect(screen.getByText('sync.lbp.securPassPrompt')).toBeInTheDocument()
    expect(screen.queryByText('sync.lbp.errors.appValidationTimeout')).not.toBeInTheDocument()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })

    expect(await screen.findByText('sync.lbp.errors.appValidationTimeout')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.lbp.customerId')).toBeInTheDocument()
    expect(screen.queryByText('sync.lbp.securPassPrompt')).not.toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/lbp/sync')
  })

  it('drops an expired keypad before submit and never sends it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    apiPost.mockResolvedValueOnce({ data: INITIATED })
    renderTab()
    await signIn()
    await screen.findByTestId('lbp-keypad-grid')
    for (const index of [0, 1, 2, 3, 4, 5]) {
      fireEvent.click(keyButtons()[index])
    }

    await act(async () => {
      await vi.advanceTimersByTimeAsync(301_000)
    })

    expect(screen.queryByTestId('lbp-keypad-grid')).not.toBeInTheDocument()
    expect(screen.getByText('sync.lbp.errors.keypadExpired')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.lbp.customerId')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).not.toHaveBeenCalledWith('/lbp/auth/keypad', expect.anything())
  })

  it('explains a throttled sync without blaming the sign-in', async () => {
    apiGet.mockResolvedValue({ data: { ...ACTIVE, syncStatus: 'SUCCESS' } })
    apiPost.mockRejectedValueOnce(problem(429, 'RATE_LIMITED'))
    renderTab()

    fireEvent.click(await screen.findByText('sync.lbp.sync'))

    expect(await screen.findByText('sync.lbp.errors.tooManySyncs')).toBeInTheDocument()
    expect(screen.queryByText('sync.lbp.errors.rateLimited')).not.toBeInTheDocument()
  })

  it('deletes the stored session only after the disconnect confirmation', async () => {
    apiGet.mockResolvedValue({ data: { ...ACTIVE, syncStatus: 'SUCCESS' } })
    apiDelete.mockResolvedValue({ data: { removed: true, bankSessionRevoked: false, message: '' } })
    renderTab()

    fireEvent.click(await screen.findByText('sync.lbp.clearSession'))

    expect(await screen.findByText('sync.lbp.disconnectConfirm')).toBeInTheDocument()
    expect(apiDelete).not.toHaveBeenCalled()

    fireEvent.click(screen.getByText('sync.lbp.disconnectConfirmAction'))

    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/lbp/session'))
  })
})
