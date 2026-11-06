import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
})
