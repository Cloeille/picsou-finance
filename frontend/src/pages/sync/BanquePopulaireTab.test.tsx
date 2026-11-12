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

const { BanquePopulaireTab } = await import('./BanquePopulaireTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
  remembered: false,
}

const DISCONNECTED_REMEMBERED = { ...DISCONNECTED, remembered: true }

const PAD = Array.from({ length: 10 }, (_, i) => `data:image/png;base64,PAD${i}`)
const INITIATED = { processId: 'p1', keypad: { images: PAD, columns: 5 }, expiresInSeconds: 90 }
const KEYPAD_DONE = { processId: 'p1', status: 'SECURPASS_PENDING' }
const NETWORK_ERROR = Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' })

function renderTab(props: { onConnected?: () => void } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return render(<BanquePopulaireTab {...props} />, { wrapper: Wrapper })
}

function problem(status: number, code: string) {
  return { response: { status, headers: {}, data: { code, detail: 'raw upstream detail 10.0.0.1' } } }
}

const regionSelect = () => screen.findByLabelText('sync.banquePopulaire.region')
const connectButton = () => screen.getByText('sync.banquePopulaire.connect').closest('button')!

async function fillForm({ region = 'RIVES_DE_PARIS', customerId = '12345678' } = {}) {
  fireEvent.change(await regionSelect(), { target: { value: region } })
  fireEvent.change(screen.getByLabelText('sync.banquePopulaire.customerId'), {
    target: { value: customerId },
  })
}

async function signIn(values?: { region?: string; customerId?: string }) {
  await fillForm(values)
  fireEvent.click(connectButton())
}

const keyButtons = () =>
  within(screen.getByTestId('banque-populaire-keypad-grid')).getAllByRole('button')
const validateButton = () =>
  screen.getByText('sync.banquePopulaire.keypad.validate').closest('button')!

function pressKeys(...indexes: number[]) {
  for (const index of indexes) fireEvent.click(keyButtons()[index])
}

describe('BanquePopulaireTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  describe('region step', () => {
    it('asks for the regional bank before the identifier, with an empty first choice', async () => {
      renderTab()
      const select = await regionSelect()
      const identifier = screen.getByLabelText('sync.banquePopulaire.customerId')

      expect(select.tagName).toBe('SELECT')
      expect(select).toBeRequired()
      expect(select).toHaveValue('')
      expect(select.compareDocumentPosition(identifier) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      const options = within(select).getAllByRole('option')
      expect(options[0]).toHaveTextContent('sync.banquePopulaire.regionPlaceholder')
      expect(options[0]).toBeDisabled()
      expect(options.slice(1).map(o => (o as HTMLOptionElement).value)).toEqual([
        'ALSACE_LORRAINE_CHAMPAGNE', 'AQUITAINE_CENTRE_ATLANTIQUE', 'AUVERGNE_RHONE_ALPES',
        'BOURGOGNE_FRANCHE_COMTE', 'BRED', 'GRAND_OUEST', 'MEDITERRANEE', 'NORD', 'OCCITANE',
        'RIVES_DE_PARIS', 'SUD', 'VAL_DE_FRANCE',
      ])
      expect(within(select).getByRole('option', { name: 'Auvergne Rhône-Alpes' })).toBeInTheDocument()
      expect(document.querySelector('input[type="password"]')).toBeNull()
    })

    it('keeps Connect disabled until a region and an identifier are given', async () => {
      renderTab()
      await regionSelect()
      expect(connectButton()).toBeDisabled()

      fireEvent.change(screen.getByLabelText('sync.banquePopulaire.customerId'), {
        target: { value: '1234' },
      })
      expect(connectButton()).toBeDisabled()

      fireEvent.change(await regionSelect(), { target: { value: 'BRED' } })
      expect(connectButton()).toBeEnabled()

      fireEvent.change(screen.getByLabelText('sync.banquePopulaire.customerId'), {
        target: { value: '' },
      })
      expect(connectButton()).toBeDisabled()
      fireEvent.click(connectButton())
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('sends the region with the identifier, letters kept, to initiate', async () => {
      // Banque Populaire identifiers start with a letter: stripping it would send someone
      // else's identifier to the bank.
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()
      await signIn({ region: 'GRAND_OUEST', customerId: 'A12-34 56' })

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/initiate', {
          customerId: 'A123456',
          region: 'GRAND_OUEST',
        }),
      )
    })

    it('reuses the remembered identifier and region without asking for them', async () => {
      apiGet.mockResolvedValue({ data: DISCONNECTED_REMEMBERED })
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()

      expect(await screen.findByText('sync.banquePopulaire.rememberedIdentifier')).toBeInTheDocument()
      expect(screen.queryByLabelText('sync.banquePopulaire.customerId')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('sync.banquePopulaire.region')).not.toBeInTheDocument()
      expect(connectButton()).toBeEnabled()
      fireEvent.click(connectButton())

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/initiate', {
          customerId: '',
          region: '',
        }),
      )
    })

    it('shows the identifier and region fields again when the bank rejects the remembered one', async () => {
      apiGet
        .mockResolvedValueOnce({ data: DISCONNECTED_REMEMBERED })
        .mockResolvedValue({ data: DISCONNECTED })
      apiPost.mockRejectedValueOnce(problem(401, 'INVALID_CREDENTIALS'))
      renderTab()

      expect(await screen.findByText('sync.banquePopulaire.rememberedIdentifier')).toBeInTheDocument()
      fireEvent.click(connectButton())

      expect(await screen.findByLabelText('sync.banquePopulaire.customerId')).toBeInTheDocument()
      expect(await screen.findByLabelText('sync.banquePopulaire.region')).toBeInTheDocument()
      expect(screen.queryByText('sync.banquePopulaire.rememberedIdentifier')).not.toBeInTheDocument()
    })

    it('lets the user type another identifier and choose a region instead of the remembered one', async () => {
      apiGet.mockResolvedValue({ data: DISCONNECTED_REMEMBERED })
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()

      fireEvent.click(await screen.findByText('sync.banquePopulaire.useAnotherIdentifier'))
      expect(await screen.findByLabelText('sync.banquePopulaire.region')).toBeInTheDocument()
      expect(screen.getByLabelText('sync.banquePopulaire.customerId')).toBeInTheDocument()
      await signIn({ region: 'GRAND_OUEST', customerId: '87654321' })

      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/initiate', {
          customerId: '87654321',
          region: 'GRAND_OUEST',
        }),
      )
    })

    it('lets the user go back to the remembered identifier', async () => {
      apiGet.mockResolvedValue({ data: DISCONNECTED_REMEMBERED })
      apiPost.mockReturnValueOnce(new Promise(() => {}))
      renderTab()

      fireEvent.click(await screen.findByText('sync.banquePopulaire.useAnotherIdentifier'))
      expect(await screen.findByLabelText('sync.banquePopulaire.region')).toBeInTheDocument()
      fireEvent.click(screen.getByText('sync.banquePopulaire.backToRememberedIdentifier'))

      expect(screen.queryByLabelText('sync.banquePopulaire.customerId')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('sync.banquePopulaire.region')).not.toBeInTheDocument()
      fireEvent.click(connectButton())
      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/initiate', {
          customerId: '',
          region: '',
        }),
      )
    })
  })

  it('goes from region and identifier to the keypad, Sécur’Pass, then one first sync', async () => {
    let syncStatus = 'IDLE'
    apiGet.mockImplementation(async () => ({
      data: syncStatus === 'IDLE' ? DISCONNECTED : { ...DISCONNECTED, isActive: true, syncStatus },
    }))
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/banque-populaire/auth/initiate') return { data: INITIATED }
      if (url === '/banque-populaire/auth/keypad') return { data: KEYPAD_DONE }
      if (url === '/banque-populaire/auth/complete') return { data: { connected: true } }
      syncStatus = 'SUCCESS'
      return { data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } }
    })
    const onConnected = vi.fn()
    renderTab({ onConnected })
    await signIn()

    await screen.findByTestId('banque-populaire-keypad-grid')
    expect(keyButtons()).toHaveLength(10)
    keyButtons().forEach((button, index) => {
      expect(button).toHaveAccessibleName(`sync.banquePopulaire.keypad.key {"n":${index + 1}}`)
    })
    pressKeys(4, 0, 9, 9, 2, 7)
    fireEvent.click(validateButton())

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/keypad', {
        processId: 'p1',
        positions: [4, 0, 9, 9, 2, 7],
      }),
    )
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/complete', { processId: 'p1' }, { timeout: 180_000 }),
    )
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/banque-populaire/sync'))
    await waitFor(() => expect(onConnected).toHaveBeenCalledTimes(1))
    expect(apiPost.mock.calls.map(c => c[0])).toEqual([
      '/banque-populaire/auth/initiate',
      '/banque-populaire/auth/keypad',
      '/banque-populaire/auth/complete',
      '/banque-populaire/sync',
    ])
    expect(apiPost.mock.calls.some(c => String(c[0]).startsWith('/caisse-epargne'))).toBe(false)
  })

  it('shows the Sécur’Pass waiting card between the keypad and the approval', async () => {
    apiPost
      .mockResolvedValueOnce({ data: INITIATED })
      .mockResolvedValueOnce({ data: KEYPAD_DONE })
      .mockReturnValueOnce(new Promise(() => {}))
    renderTab()
    await signIn()
    await screen.findByTestId('banque-populaire-keypad-grid')
    pressKeys(1, 2, 3, 4, 5, 6)
    fireEvent.click(validateButton())

    expect(await screen.findByText('sync.banquePopulaire.securPassPrompt')).toBeInTheDocument()
    expect(screen.getByTestId('banque-populaire-countdown')).toHaveTextContent('2:30')
    expect(screen.queryByLabelText('sync.banquePopulaire.region')).not.toBeInTheDocument()
  })

  describe('errors', () => {
    it.each([
      ['INVALID_CREDENTIALS', 401, 'invalidCredentials'],
      ['KEYPAD_CHANGED', 409, 'keypadChanged'],
      ['UPSTREAM_FORMAT_CHANGED', 502, 'formatChanged'],
      ['UPSTREAM_UNAVAILABLE', 503, 'serverError'],
    ])('explains %s from the keypad call without retrying, never the raw detail', async (code, status, suffix) => {
      apiPost
        .mockResolvedValueOnce({ data: INITIATED })
        .mockRejectedValueOnce(problem(status, code))
      renderTab()
      await signIn()
      await screen.findByTestId('banque-populaire-keypad-grid')
      pressKeys(1, 2, 3, 4, 5, 6)
      fireEvent.click(validateButton())

      expect(await screen.findByText(`sync.banquePopulaire.errors.${suffix}`)).toBeInTheDocument()
      expect(screen.queryByText(/10\.0\.0\.1/)).not.toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(2)
    })

    it('explains a rate-limited initiate and keeps the chosen region', async () => {
      apiPost.mockRejectedValueOnce(problem(429, 'RATE_LIMITED'))
      renderTab()
      await signIn({ region: 'NORD' })

      expect(await screen.findByText('sync.banquePopulaire.errors.rateLimited')).toBeInTheDocument()
      expect(await regionSelect()).toHaveValue('NORD')
      expect(apiPost).toHaveBeenCalledTimes(1)
    })

    it('maps a failed background sync to its own copy', async () => {
      apiGet.mockResolvedValue({
        data: { ...DISCONNECTED, isActive: true, syncStatus: 'FAILED', lastSyncError: 'SESSION_EXPIRED' },
      })
      renderTab()

      expect(await screen.findByText('sync.banquePopulaire.errors.sessionExpired')).toBeInTheDocument()
    })
  })

  it('keeps waiting when the completion is lost, then queues the first sync once the session is live', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let live = false
    apiGet.mockImplementation(async () => ({
      data: live ? { ...DISCONNECTED, isActive: true, syncStatus: 'RUNNING' } : DISCONNECTED,
    }))
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/banque-populaire/auth/initiate') return { data: INITIATED }
      if (url === '/banque-populaire/auth/keypad') return { data: KEYPAD_DONE }
      if (url === '/banque-populaire/auth/complete') throw NETWORK_ERROR
      return { data: { ...DISCONNECTED, isActive: true, syncStatus: 'QUEUED' } }
    })
    renderTab()
    await signIn()
    await screen.findByTestId('banque-populaire-keypad-grid')
    pressKeys(4, 0, 9, 9, 2, 7)
    fireEvent.click(validateButton())
    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/banque-populaire/auth/complete', { processId: 'p1' }, { timeout: 180_000 }),
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    expect(screen.getByText('sync.banquePopulaire.securPassPrompt')).toBeInTheDocument()
    expect(apiGet).toHaveBeenCalledWith('/banque-populaire/status', {
      skipGlobalErrorRedirect: true,
      timeout: 10_000,
    })
    expect(apiPost).not.toHaveBeenCalledWith('/banque-populaire/sync')

    live = true
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/banque-populaire/sync'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9_000)
    })
    expect(apiPost.mock.calls.filter(c => c[0] === '/banque-populaire/sync')).toHaveLength(1)
    expect(apiPost.mock.calls.filter(c => c[0] === '/banque-populaire/auth/complete')).toHaveLength(1)
  })

  it('reports a timeout when a lost completion never brings a session', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/banque-populaire/auth/initiate') return { data: INITIATED }
      if (url === '/banque-populaire/auth/keypad') return { data: KEYPAD_DONE }
      throw NETWORK_ERROR
    })
    renderTab()
    await signIn()
    await screen.findByTestId('banque-populaire-keypad-grid')
    pressKeys(4, 0, 9, 9, 2, 7)
    fireEvent.click(validateButton())
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(3))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(183_000)
    })

    expect(await screen.findByText('sync.banquePopulaire.errors.appValidationTimeout')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/banque-populaire/sync')
  })

  it('says the bank session itself is not revoked before deleting the stored one', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true, syncStatus: 'SUCCESS' } })
    apiDelete.mockResolvedValue({ data: { removed: true, bankSessionRevoked: false, message: '' } })
    renderTab()

    fireEvent.click(await screen.findByText('sync.banquePopulaire.clearSession'))
    expect(screen.getByText('sync.banquePopulaire.disconnectConfirm')).toBeInTheDocument()
    fireEvent.click(screen.getByText('sync.banquePopulaire.disconnectConfirmAction'))

    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/banque-populaire/session'))
  })
})
