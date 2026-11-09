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

const { BnpTab } = await import('./BnpTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
}
const ACTIVE = { ...DISCONNECTED, isActive: true }

// Synthetic keypad: the digit on each key, in the bank's shuffled order.
const KEYS = ['3', '8', '1', '0', '5', '9', '2', '7', '4', '6']
const INITIATED = { processId: 'p1', keypad: { keys: KEYS, columns: 5 }, expiresInSeconds: 300 }

function renderTab() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return render(<BnpTab />, { wrapper: Wrapper })
}

function problem(status: number, code: string) {
  return { response: { status, headers: {}, data: { code, detail: 'raw upstream detail' } } }
}

async function signIn(customerId = '1234567890') {
  fireEvent.change(await screen.findByLabelText(/sync\.bnp\.customerId/), { target: { value: customerId } })
  fireEvent.click(screen.getByText('sync.bnp.connect'))
}

const keyButtons = () => within(screen.getByTestId('bnp-keypad-grid')).getAllByRole('button')
const validateButton = () => screen.getByText('sync.bnp.keypad.validate').closest('button')!

async function reachKeypad() {
  apiPost.mockImplementation((url: string) =>
    url === '/bnp/auth/initiate' ? Promise.resolve({ data: INITIATED }) : Promise.reject(new Error(`unexpected ${url}`)),
  )
  renderTab()
  await signIn()
  await screen.findByTestId('bnp-keypad-grid')
}

describe('BnpTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  it('asks for the customer number and the brand only, never a password', async () => {
    apiPost.mockResolvedValue({ data: INITIATED })
    renderTab()
    fireEvent.click(await screen.findByLabelText('sync.bnp.brands.hellobank'))
    await signIn('1234567890')

    expect(document.querySelector('input[type="password"]')).toBeNull()
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bnp/auth/initiate', { customerId: '1234567890', brand: 'hellobank' }),
    )
  })

  it('refuses a customer number outside 7 to 10 digits before calling the bank', async () => {
    renderTab()
    await signIn('123456')
    expect(screen.getByText('sync.bnp.errors.invalidIdentifier')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('states that the flow is not yet verified against a real account', async () => {
    renderTab()
    expect(await screen.findByText('sync.bnp.notice.unverified')).toBeInTheDocument()
  })

  it('shows the ten bank keys and sends positions, never digits, after six clicks', async () => {
    await reachKeypad()
    expect(keyButtons().map((b) => b.textContent)).toEqual(KEYS)
    expect(validateButton()).toBeDisabled()

    // The member's password is 1-3-5-7-9-0: they click the keys showing those digits.
    for (const digit of ['1', '3', '5', '7', '9', '0']) fireEvent.click(keyButtons()[KEYS.indexOf(digit)])
    expect(validateButton()).toBeEnabled()

    apiPost.mockImplementation((url: string) =>
      url === '/bnp/auth/keypad' ? Promise.resolve({ data: { processId: 'p1', status: 'CODE_REQUIRED' } }) : Promise.reject(new Error(url)),
    )
    fireEvent.click(validateButton())
    await screen.findByLabelText('sync.bnp.code')
    expect(apiPost).toHaveBeenCalledWith('/bnp/auth/keypad', { processId: 'p1', positions: [2, 0, 4, 7, 5, 3] })
    // The digits are neither sent nor kept on screen.
    expect(screen.queryByTestId('bnp-keypad-grid')).toBeNull()
    expect(JSON.stringify(apiPost.mock.calls)).not.toContain('"135790"')
  })

  it('stops accepting clicks after six and lets the member clear', async () => {
    await reachKeypad()
    for (let i = 0; i < 8; i++) fireEvent.click(keyButtons()[i])
    expect(screen.getByTestId('bnp-keypad-dots').querySelectorAll('[data-dot]')).toHaveLength(6)
    fireEvent.click(screen.getByText('sync.bnp.keypad.clear'))
    expect(screen.getByTestId('bnp-keypad-dots').querySelectorAll('[data-dot]')).toHaveLength(0)
  })

  it('refuses a keypad that is not ten distinct digits', async () => {
    apiPost.mockResolvedValue({ data: { ...INITIATED, keypad: { keys: ['1', '1', ...KEYS.slice(2)], columns: 5 } } })
    renderTab()
    await signIn()
    expect(await screen.findByText('sync.bnp.errors.formatChanged')).toBeInTheDocument()
    expect(screen.queryByTestId('bnp-keypad-grid')).toBeNull()
  })

  it('types an SMS code, sends it once to /complete, then queues the first sync', async () => {
    await reachKeypad()
    for (let i = 0; i < 6; i++) fireEvent.click(keyButtons()[i])
    apiPost.mockImplementation((url: string) => {
      if (url === '/bnp/auth/keypad') return Promise.resolve({ data: { processId: 'p1', status: 'CODE_REQUIRED' } })
      if (url === '/bnp/auth/complete') return Promise.resolve({ data: ACTIVE })
      if (url === '/bnp/sync') return Promise.resolve({ data: { ...ACTIVE, syncStatus: 'QUEUED' } })
      return Promise.reject(new Error(url))
    })
    fireEvent.click(validateButton())
    fireEvent.change(await screen.findByLabelText('sync.bnp.code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByText('sync.bnp.sendCode'))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bnp/auth/complete', { processId: 'p1', code: '123456' }, expect.anything()),
    )
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/bnp/sync'))
  })

  it('lets a mistyped SMS code be typed again, without replaying the keypad', async () => {
    await reachKeypad()
    for (let i = 0; i < 6; i++) fireEvent.click(keyButtons()[i])
    apiPost.mockImplementation((url: string) => {
      if (url === '/bnp/auth/keypad') return Promise.resolve({ data: { processId: 'p1', status: 'CODE_REQUIRED' } })
      if (url === '/bnp/auth/complete') return Promise.reject(problem(401, 'INVALID_OTP'))
      return Promise.reject(new Error(url))
    })
    fireEvent.click(validateButton())
    fireEvent.change(await screen.findByLabelText('sync.bnp.code'), { target: { value: '000000' } })
    fireEvent.click(screen.getByText('sync.bnp.sendCode'))

    expect(await screen.findByText('sync.bnp.errors.invalidCode')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.bnp.code')).toBeInTheDocument()
    expect(apiPost.mock.calls.filter(([url]) => url === '/bnp/auth/keypad')).toHaveLength(1)
    expect(apiPost.mock.calls.filter(([url]) => url === '/bnp/auth/initiate')).toHaveLength(1)
  })

  it('waits for the approval in the bank app with a single /complete call, no code', async () => {
    await reachKeypad()
    for (let i = 0; i < 6; i++) fireEvent.click(keyButtons()[i])
    apiPost.mockImplementation((url: string) => {
      if (url === '/bnp/auth/keypad') return Promise.resolve({ data: { processId: 'p1', status: 'APPROVAL_PENDING' } })
      if (url === '/bnp/auth/complete') return Promise.resolve({ data: ACTIVE })
      if (url === '/bnp/sync') return Promise.resolve({ data: { ...ACTIVE, syncStatus: 'QUEUED' } })
      return Promise.reject(new Error(url))
    })
    fireEvent.click(validateButton())
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bnp/auth/complete', { processId: 'p1' }, expect.anything()),
    )
    expect(apiPost.mock.calls.filter(([url]) => url === '/bnp/auth/complete')).toHaveLength(1)
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/bnp/sync'))
  })

  it('never replays the login after a wrong password', async () => {
    await reachKeypad()
    for (let i = 0; i < 6; i++) fireEvent.click(keyButtons()[i])
    apiPost.mockImplementation((url: string) =>
      url === '/bnp/auth/keypad' ? Promise.reject(problem(401, 'INVALID_CREDENTIALS')) : Promise.reject(new Error(url)),
    )
    fireEvent.click(validateButton())

    expect(await screen.findByText('sync.bnp.errors.invalidCredentials')).toBeInTheDocument()
    expect(screen.queryByTestId('bnp-keypad-grid')).toBeNull()
    expect(apiPost.mock.calls.filter(([url]) => url === '/bnp/auth/initiate')).toHaveLength(1)
    expect(apiPost.mock.calls.filter(([url]) => url === '/bnp/auth/keypad')).toHaveLength(1)
  })

  it('never shows a raw backend message', async () => {
    apiPost.mockRejectedValue(problem(500, 'SOMETHING_NEW'))
    renderTab()
    await signIn()
    expect(await screen.findByText('sync.bnp.errors.serverError')).toBeInTheDocument()
    expect(screen.queryByText(/raw upstream detail/)).toBeNull()
  })

  it('syncs and disconnects an active session, only after a confirmation', async () => {
    apiGet.mockResolvedValue({ data: { ...ACTIVE, syncStatus: 'SUCCESS', lastSyncCompletedAt: '2026-11-06T12:00:00Z' } })
    apiPost.mockResolvedValue({ data: ACTIVE })
    apiDelete.mockResolvedValue({ data: { removed: true, bankSessionRevoked: false, message: '' } })
    renderTab()

    fireEvent.click(await screen.findByText('sync.bnp.sync'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/bnp/sync'))

    fireEvent.click(screen.getByText('sync.bnp.clearSession'))
    expect(apiDelete).not.toHaveBeenCalled()
    fireEvent.click(screen.getByText('sync.bnp.disconnectConfirmAction'))
    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/bnp/session'))
  })

  it('lists unsupported products by family code only', async () => {
    apiGet.mockResolvedValue({
      data: {
        ...ACTIVE,
        syncStatus: 'SUCCESS',
        lastSyncCompletedAt: '2026-11-06T12:00:00Z',
        unsupported: [{ externalId: 'secret-id-1', familyCode: 'F42' }],
        unsupportedCount: 1,
      },
    })
    renderTab()
    expect(await screen.findByText('F42')).toBeInTheDocument()
    expect(screen.queryByText(/secret-id-1/)).toBeNull()
  })
})
