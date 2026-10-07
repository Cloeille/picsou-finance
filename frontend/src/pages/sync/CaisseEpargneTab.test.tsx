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

const PAD = Array.from({ length: 10 }, (_, i) => `data:image/png;base64,PAD${i}`)

const INITIATED = {
  processId: 'p1',
  keypad: { images: PAD, columns: 5 },
  expiresInSeconds: 90,
}

const KEYPAD_DONE = { processId: 'p1', status: 'SECURPASS_PENDING' }

let queryClient: QueryClient

function renderTab(props: { onConnected?: () => void } = {}) {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  render(<CaisseEpargneTab {...props} />, { wrapper: Wrapper })
}

function problem(status: number, code: string, headers: Record<string, string> = {}) {
  return {
    response: { status, headers, data: { code, detail: 'raw upstream detail 10.0.0.1' } },
  }
}

async function typeIdentifier(customerId = '12345678') {
  fireEvent.change(await screen.findByLabelText('sync.caisseEpargne.customerId'), {
    target: { value: customerId },
  })
}

async function signIn(customerId?: string) {
  await typeIdentifier(customerId)
  fireEvent.click(screen.getByText('sync.caisseEpargne.connect'))
}

const keyButtons = () =>
  within(screen.getByTestId('caisse-epargne-keypad-grid')).getAllByRole('button')
const dots = () => screen.getByTestId('caisse-epargne-keypad-dots')
const validateButton = () => screen.getByText('sync.caisseEpargne.keypad.validate').closest('button')!

async function openKeypad() {
  apiPost.mockResolvedValueOnce({ data: INITIATED })
  renderTab()
  await signIn()
  await screen.findByTestId('caisse-epargne-keypad-grid')
}

function pressKeys(...indexes: number[]) {
  for (const index of indexes) fireEvent.click(keyButtons()[index])
}

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
    it('asks for the identifier only, never a password', async () => {
      renderTab()
      await typeIdentifier()

      expect(screen.getByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()
      expect(screen.queryByLabelText('sync.caisseEpargne.password')).not.toBeInTheDocument()
      expect(document.querySelector('input[type="password"]')).toBeNull()
    })

    it('keeps the identifier numeric and sends it alone to initiate', async () => {
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn('12ab34')

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/initiate', {
          customerId: '1234',
        }),
      )
    })

    it('does not offer the keypad before initiate answered', async () => {
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn()

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument()
    })
  })

  describe('keypad pop-up', () => {
    it('shows the 10 images as plain <img src> buttons in DOM order, 5 columns', async () => {
      await openKeypad()

      const buttons = keyButtons()
      expect(buttons).toHaveLength(10)
      buttons.forEach((button, index) => {
        const img = button.querySelector('img')
        expect(img).not.toBeNull()
        expect(img).toHaveAttribute('src', PAD[index])
        expect(button.children).toHaveLength(1)
      })
      expect(screen.getByTestId('caisse-epargne-keypad-grid').style.gridTemplateColumns).toBe(
        'repeat(5, minmax(0, 1fr))',
      )
    })

    it('labels keys by rank only: no accessible name reveals a digit', async () => {
      await openKeypad()

      keyButtons().forEach((button, index) => {
        expect(button).toHaveAccessibleName(`sync.caisseEpargne.keypad.key {"n":${index + 1}}`)
        expect(button.querySelector('img')).toHaveAttribute('alt', '')
      })
    })

    it('shows one dot per click and no digit', async () => {
      await openKeypad()
      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(0)

      pressKeys(3, 3, 7)

      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(3)
      expect(dots().textContent).toBe('')
    })

    it('Clear empties the entry', async () => {
      await openKeypad()
      pressKeys(1, 2, 3, 4, 5, 6)
      expect(validateButton()).toBeEnabled()

      fireEvent.click(screen.getByText('sync.caisseEpargne.keypad.clear'))

      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(0)
      expect(validateButton()).toBeDisabled()
    })

    it('enables Validate only for 6 to 12 clicks', async () => {
      await openKeypad()
      expect(validateButton()).toBeDisabled()

      pressKeys(0, 1, 2, 3, 4)
      expect(validateButton()).toBeDisabled()
      pressKeys(5)
      expect(validateButton()).toBeEnabled()
      pressKeys(6, 7, 8, 9, 0, 1)
      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(12)
      expect(validateButton()).toBeEnabled()

      pressKeys(2)
      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(12)
      expect(validateButton()).toBeEnabled()
    })

    it('counts down from expiresInSeconds', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      await openKeypad()

      expect(screen.getByTestId('caisse-epargne-keypad-countdown')).toHaveTextContent('1:30')
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_000)
      })
      expect(screen.getByTestId('caisse-epargne-keypad-countdown')).toHaveTextContent('1:25')
    })

    it('Cancel closes the pop-up, resets, and sends no keypad call', async () => {
      await openKeypad()
      pressKeys(1, 2, 3, 4, 5, 6)

      fireEvent.click(screen.getByText('sync.caisseEpargne.keypad.cancel'))

      await waitFor(() =>
        expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument(),
      )
      expect(await screen.findByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).not.toHaveBeenCalledWith('/caisse-epargne/auth/keypad', expect.anything())
    })

    it('a cancelled pad is not kept: the next one starts empty', async () => {
      await openKeypad()
      pressKeys(1, 2, 3)
      fireEvent.click(screen.getByText('sync.caisseEpargne.keypad.cancel'))
      await waitFor(() =>
        expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument(),
      )

      apiPost.mockResolvedValueOnce({ data: INITIATED })
      fireEvent.click(await screen.findByText('sync.caisseEpargne.connect'))
      await screen.findByTestId('caisse-epargne-keypad-grid')

      expect(dots().querySelectorAll('[data-dot]')).toHaveLength(0)
    })

    it('expiry closes the pop-up, resets, explains, and sends nothing', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      await openKeypad()
      pressKeys(1, 2, 3, 4, 5, 6)

      await act(async () => {
        await vi.advanceTimersByTimeAsync(91_000)
      })

      expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument()
      expect(screen.getByText('sync.caisseEpargne.errors.keypadExpired')).toBeInTheDocument()
      expect(screen.getByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('Validate sends the clicked indexes once, then waits for Sécur’Pass and completes', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(4, 0, 9, 9, 2, 7)

      fireEvent.click(validateButton())

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/keypad', {
          processId: 'p1',
          positions: [4, 0, 9, 9, 2, 7],
        }),
      )
      expect(await screen.findByText('sync.caisseEpargne.securPassPrompt')).toBeInTheDocument()
      expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument()
      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/complete', {
          processId: 'p1',
        }),
      )
      expect(apiPost.mock.calls.map(c => c[0])).toEqual([
        '/caisse-epargne/auth/initiate',
        '/caisse-epargne/auth/keypad',
        '/caisse-epargne/auth/complete',
      ])
    })

    it('ignores a second Validate click while the keypad call is out', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(1, 2, 3, 4, 5, 6)
      const validate = validateButton()

      fireEvent.click(validate)
      fireEvent.click(validate)

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2))
    })

    it('does not leave positions in the mutation cache', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(401, 'INVALID_CREDENTIALS'))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(8, 8, 8, 7, 7, 7)
      fireEvent.click(validateButton())

      await screen.findByText('sync.caisseEpargne.errors.invalidCredentials')
      const cached = JSON.stringify(
        queryClient
          .getMutationCache()
          .getAll()
          .map(m => m.state.variables),
      )
      expect(cached).not.toContain('888777')
      expect(cached).not.toContain('[8,8,8,7,7,7]')
    })

    it('never writes positions to the console', async () => {
      const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map(name =>
        vi.spyOn(console, name).mockImplementation(() => {}),
      )
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(409, 'KEYPAD_CHANGED'))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(8, 8, 8, 7, 7, 7)
      fireEvent.click(validateButton())
      await screen.findByText('sync.caisseEpargne.errors.keypadChanged')

      for (const spy of spies) {
        expect(JSON.stringify(spy.mock.calls)).not.toMatch(/888777|8,8,8,7,7,7/)
        spy.mockRestore()
      }
    })
  })

  describe('no retry', () => {
    it('sends initiate once after a rejected initiate and shows the form again', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost.mockRejectedValueOnce(problem(429, 'RATE_LIMITED'))
      renderTab()
      await signIn()

      await screen.findByText('sync.caisseEpargne.errors.rateLimited')
      expect(screen.getByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10 * 60_000)
      })
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('a rejected keypad is never replayed, and complete is never called', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(401, 'INVALID_CREDENTIALS'))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(1, 2, 3, 4, 5, 6)
      fireEvent.click(validateButton())

      expect(
        await screen.findByText('sync.caisseEpargne.errors.invalidCredentials'),
      ).toBeInTheDocument()
      expect(screen.queryByTestId('caisse-epargne-keypad-grid')).not.toBeInTheDocument()
      expect(screen.getByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()

      await act(async () => {
        await vi.advanceTimersByTimeAsync(10 * 60_000)
      })
      expect(apiPost).toHaveBeenCalledTimes(2)
      expect(apiPost).not.toHaveBeenCalledWith('/caisse-epargne/auth/complete', expect.anything())
    })

    it.each([
      ['KEYPAD_CHANGED', 409, 'keypadChanged'],
      ['KEYPAD_EXPIRED', 408, 'keypadExpired'],
      ['INVALID_POSITIONS', 400, 'invalidPositions'],
    ])('explains %s from the keypad call without retrying', async (code, status, suffix) => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(status, code))
      renderTab()
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(1, 2, 3, 4, 5, 6)
      fireEvent.click(validateButton())

      expect(await screen.findByText(`sync.caisseEpargne.errors.${suffix}`)).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(2)
    })
  })

  describe('Sécur’Pass wait', () => {
    const toSecurPass = async () => {
      await signIn()
      await screen.findByTestId('caisse-epargne-keypad-grid')
      pressKeys(1, 2, 3, 4, 5, 6)
      fireEvent.click(validateButton())
    }

    it('asks for the phone approval and counts down from the real 150 s human wait, not the 90 s keypad TTL', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockReturnValueOnce(new Promise(() => {}))

      renderTab()
      await toSecurPass()

      expect(await screen.findByText('sync.caisseEpargne.securPassPrompt')).toBeInTheDocument()
      expect(screen.getByTestId('caisse-epargne-countdown')).toHaveTextContent('2:30')

      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000)
      })
      expect(screen.getByTestId('caisse-epargne-countdown')).toHaveTextContent('2:28')
    })

    it('makes one long complete call and never polls or retries it', async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true })
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockReturnValueOnce(new Promise(() => {}))

      renderTab()
      await toSecurPass()

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/auth/complete', {
          processId: 'p1',
        }),
      )
      await act(async () => {
        await vi.advanceTimersByTimeAsync(5 * 60_000)
      })

      expect(apiPost).toHaveBeenCalledTimes(3)
    })

    it('shows the connected state once complete answers connected', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockResolvedValueOnce({ data: { connected: true } })
        .mockResolvedValueOnce({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })

      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED })
        .mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
      renderTab()
      await toSecurPass()

      expect(await screen.findByText('sync.caisseEpargne.sessionActive')).toBeInTheDocument()
      expect(screen.queryByText('sync.caisseEpargne.securPassPrompt')).not.toBeInTheDocument()
    })

    it('queues the first sync once right after the login, so the accounts get created', async () => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockResolvedValueOnce({ data: { connected: true } })
        .mockResolvedValueOnce({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })
      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED })
        .mockResolvedValue({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'RUNNING' } })
      renderTab()
      await toSecurPass()

      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/sync'))
      expect(apiPost.mock.calls.map(c => c[0])).toEqual([
        '/caisse-epargne/auth/initiate',
        '/caisse-epargne/auth/keypad',
        '/caisse-epargne/auth/complete',
        '/caisse-epargne/sync',
      ])
    })

    it('calls onConnected only once that first sync succeeded, not right after Sécur’Pass', async () => {
      const onConnected = vi.fn()
      let syncStatus = 'RUNNING'
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockResolvedValueOnce({ data: { connected: true } })
        .mockResolvedValueOnce({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } })
      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED })
        .mockImplementation(async () => ({ data: { ...DISCONNECTED, isActive: true, syncStatus } }))
      renderTab({ onConnected })
      await toSecurPass()

      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/sync'))
      expect(onConnected).not.toHaveBeenCalled()

      syncStatus = 'SUCCESS'
      await act(async () => {
        await queryClient.invalidateQueries()
      })

      await waitFor(() => expect(onConnected).toHaveBeenCalledTimes(1))
    })

    it('keeps the panel open and explains a failed first sync, without calling onConnected', async () => {
      const onConnected = vi.fn()
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockResolvedValueOnce({ data: { connected: true } })
        .mockRejectedValueOnce(problem(503, 'UPSTREAM_UNAVAILABLE'))
      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED })
        .mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
      renderTab({ onConnected })
      await toSecurPass()

      expect(await screen.findByText('sync.caisseEpargne.errors.serverError')).toBeInTheDocument()
      expect(onConnected).not.toHaveBeenCalled()
      expect(apiPost.mock.calls.filter(c => c[0] === '/caisse-epargne/sync')).toHaveLength(1)
    })

    it.each([
      ['APP_VALIDATION_TIMEOUT', 408, 'appValidationTimeout'],
      ['AUTH_ATTEMPT_EXPIRED', 410, 'authAttemptExpired'],
    ])('explains %s and offers the form again without retrying', async (code, status, suffix) => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockResolvedValueOnce({ data: KEYPAD_DONE })
        .mockRejectedValueOnce(problem(status, code))

      renderTab()
      await toSecurPass()

      expect(await screen.findByText(`sync.caisseEpargne.errors.${suffix}`)).toBeInTheDocument()
      expect(await screen.findByLabelText('sync.caisseEpargne.customerId')).toBeInTheDocument()
      expect(screen.queryByText('sync.caisseEpargne.securPassPrompt')).not.toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(3)
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
      expect(screen.queryByLabelText('sync.caisseEpargne.customerId')).not.toBeInTheDocument()
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
