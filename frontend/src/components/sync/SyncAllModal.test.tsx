import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { TooltipProvider } from '@/components/ui/tooltip'

const { apiGet, apiPost, apiDelete, navigate } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  navigate: vi.fn(),
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

vi.mock('react-router-dom', () => ({
  useNavigate: () => navigate,
}))

const { SyncAllModal } = await import('./SyncAllModal')

const TR_ACCOUNT = {
  id: 1,
  name: 'TR Titres',
  type: 'COMPTE_TITRES',
  provider: 'Trade Republic',
  currency: 'EUR',
  currentBalance: 1000,
  currentBalanceEur: 1000,
  lastSyncedAt: '2026-07-07T08:00:00Z',
  isManual: false,
  color: '#3b82f6',
  ticker: null,
  createdAt: '2026-01-01T00:00:00Z',
}

/** Routes every GET the modal's status hooks fire; TR session is inactive. */
function mockStatusEndpoints() {
  apiGet.mockImplementation((url: string) => {
    switch (url) {
      case '/sync/status':
        return Promise.resolve({ data: [] })
      case '/crypto/exchange/status':
        return Promise.resolve({ data: [] })
      case '/crypto/wallet':
        return Promise.resolve({ data: [] })
      case '/tr/status':
        return Promise.resolve({ data: { isActive: false, expiresAt: null } })
      case '/bourso/status':
        return Promise.resolve({ data: { isActive: false, syncStatus: 'IDLE', lastSyncError: null, lastSyncStartedAt: null, lastSyncCompletedAt: null } })
      case '/finary/status':
        return Promise.resolve({ data: { connected: false, status: null, lastSyncedAt: null } })
      case '/amundi/status':
        return Promise.resolve({ data: amundiStatus })
      case '/bourse-direct/status':
        return Promise.resolve({ data: { isActive: false, syncStatus: 'IDLE', lastSyncError: null, expiresAt: null, lastSyncStartedAt: null, lastSyncCompletedAt: null } })
      case '/degiro/status':
        return Promise.resolve({ data: { isActive: false, status: null, lastSyncedAt: null } })
      case '/ibkr/status':
        return Promise.resolve({ data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null } })
      case '/accounts':
        return Promise.resolve({ data: accountsFixture })
      default:
        return Promise.reject(new Error(`Unexpected GET ${url}`))
    }
  })
}

/** Overridden per test to drive the session-provider rows. */
let amundiStatus: Record<string, unknown>
let accountsFixture: unknown[]

const AMUNDI_INACTIVE = {
  isActive: false, syncStatus: 'IDLE', lastSyncError: null,
  lastSyncStartedAt: null, lastSyncCompletedAt: null,
}

const AMUNDI_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 2,
  name: 'PEG — GROUPE ORANGE',
  type: 'EMPLOYEE_SAVINGS',
  provider: 'Amundi Épargne Salariale',
}

/** Resets the per-test fixtures to "only Trade Republic exists". */
function resetFixtures() {
  amundiStatus = AMUNDI_INACTIVE
  accountsFixture = [TR_ACCOUNT]
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderModal() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        <TooltipProvider>{children}</TooltipProvider>
      </QueryClientProvider>
    )
  }

  render(<SyncAllModal open onOpenChange={() => {}} />, { wrapper: Wrapper })
}

/** Opens the TR inline auth form and fills phone + PIN. */
async function openTrFormAndFillCredentials() {
  // The TR row's sync button opens the inline auth form when no session is active.
  const trRow = (await screen.findByText('Trade Republic')).closest('[data-slot="card"]') as HTMLElement
  expect(trRow).not.toBeNull()
  const trSyncButton = within(trRow).getByRole('button')
  fireEvent.click(trSyncButton)

  fireEvent.change(await screen.findByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  fireEvent.change(screen.getByLabelText('sync.tr.pin'), {
    target: { value: '1234' },
  })
}

describe('SyncAllModal Trade Republic inline auth', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    resetFixtures()
    mockStatusEndpoints()
  })

  it('shows the mapped error and stays on the phone/PIN step when initiation fails', async () => {
    apiPost.mockRejectedValue({
      response: { status: 422, data: { detail: 'PIN_INVALID' } },
    })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.tr.tan')).not.toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/initiate', {
      phoneNumber: '+33612345678',
      pin: '1234',
    })
  })

  it('shows the mapped error, keeps the TAN step and clears the code when completion fails', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') {
        return Promise.resolve({ data: { processId: 'process-123' } })
      }
      if (url === '/tr/auth/complete') {
        return Promise.reject({
          response: { status: 422, data: { detail: 'VALIDATION_CODE_INVALID' } },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    fireEvent.change(await screen.findByLabelText('sync.tr.tan'), {
      target: { value: '9876' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    const tanInput = screen.getByLabelText('sync.tr.tan')
    expect(tanInput).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/complete', {
      processId: 'process-123',
      tan: '9876',
    })
  })

  it('moves to the TAN step without an error message when initiation succeeds', async () => {
    apiPost.mockResolvedValue({ data: { processId: 'process-123' } })

    renderModal()
    await openTrFormAndFillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByLabelText('sync.tr.tan')).toBeInTheDocument()
    expect(screen.queryByText(/sync\.tr\.errors\./)).not.toBeInTheDocument()
  })
})

/**
 * The modal knew six provider types and the Sync page had nine tabs, so Amundi, Bourse Direct,
 * DEGIRO and IBKR were simply absent from "Sync accounts" — invisible to anyone who only ever
 * opens the dashboard. These pin the two halves of the rule that decides a row's presence.
 */
describe('SyncAllModal session providers', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    navigate.mockReset()
    resetFixtures()
    mockStatusEndpoints()
  })

  it('omits a provider that is neither connected nor holding accounts', async () => {
    renderModal()

    // Trade Republic proves the list rendered before asserting an absence.
    expect(await screen.findByText('Trade Republic')).toBeInTheDocument()
    expect(screen.queryByText('Amundi')).not.toBeInTheDocument()
    expect(screen.queryByText('Bourse Direct')).not.toBeInTheDocument()
    expect(screen.queryByText('DEGIRO')).not.toBeInTheDocument()
    expect(screen.queryByText('Interactive Brokers')).not.toBeInTheDocument()
  })

  it('lists a provider whose session is live', async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS', lastSyncCompletedAt: '2026-08-10T08:00:00Z' }
    renderModal()

    expect(await screen.findByText('Amundi')).toBeInTheDocument()
  })

  /**
   * The case that made this a bug rather than a missing feature: the user had two live Amundi
   * accounts and no way to sync them from the dashboard. A dead session must not hide the row,
   * or there is nowhere to notice it needs reconnecting.
   */
  it('lists a provider with accounts even when its session has expired', async () => {
    accountsFixture = [TR_ACCOUNT, AMUNDI_ACCOUNT]
    renderModal()

    const amundiRow = (await screen.findByText('Amundi')).closest('[data-slot="card"]') as HTMLElement
    expect(amundiRow).not.toBeNull()
    expect(within(amundiRow).getByText('sync.all.sessionExpired')).toBeInTheDocument()
  })

  it('syncs through the provider endpoint when the session is live', async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'IDLE' }
    apiPost.mockResolvedValue({ data: amundiStatus })
    renderModal()

    const amundiRow = (await screen.findByText('Amundi')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(amundiRow).getByRole('button'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amundi/sync'))
  })

  /**
   * An expired session cannot be repaired from this modal -- its provider needs credentials,
   * an MFA code or a Flex token. Firing the sync would only return a 401, so the row hands the
   * user to the tab that owns that form instead.
   */
  it('opens the provider tab instead of firing a doomed sync when the session has expired', async () => {
    accountsFixture = [TR_ACCOUNT, AMUNDI_ACCOUNT]
    renderModal()

    const amundiRow = (await screen.findByText('Amundi')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(amundiRow).getByRole('button'))

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=amundi'))
    expect(apiPost).not.toHaveBeenCalledWith('/amundi/sync')
  })

  /** An enabled button that quietly does nothing reads as a broken one. */
  it('disables "Sync all" when every row needs attention rather than a sync', async () => {
    accountsFixture = [AMUNDI_ACCOUNT]
    renderModal()

    await screen.findByText('Amundi')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /sync\.all\.syncAll/ })).toBeDisabled())
  })

  it('enables "Sync all" as soon as one row can actually be synced', async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true }
    accountsFixture = [AMUNDI_ACCOUNT]
    renderModal()

    await screen.findByText('Amundi')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /sync\.all\.syncAll/ })).toBeEnabled())
  })

  /**
   * A live session whose last run errored used to render as "active". The failure is the one
   * thing worth surfacing there -- it is the state nobody thinks to go looking for.
   */
  it('reports a failed last run instead of showing the row as active', async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'FAILED', lastSyncError: 'INTERNAL_ERROR' }
    renderModal()

    const row = (await screen.findByText('Amundi')).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('FAILED')).toBeInTheDocument()
    expect(within(row).queryByText('active')).not.toBeInTheDocument()
  })

  /** A Flex outage or a rate limit clears on its own, so a failed-but-live row keeps its retry. */
  it('still allows a retry when the session is live and only the run failed', async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'FAILED', lastSyncError: 'INTERNAL_ERROR' }
    apiPost.mockResolvedValue({ data: amundiStatus })
    renderModal()

    const row = (await screen.findByText('Amundi')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amundi/sync'))
    expect(navigate).not.toHaveBeenCalled()
  })
})
