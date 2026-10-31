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
      case '/simplefin/status':
        return Promise.resolve({ data: simplefinStatus })
      case '/amex/status':
        return Promise.resolve({ data: amexStatus })
      case '/caisse-epargne/status':
        return Promise.resolve({ data: caisseEpargneStatus })
      case '/corum/status':
        return Promise.resolve({ data: corumStatus })
      case '/cmes/status':
        return Promise.resolve({ data: cmesStatus })
      case '/cic/status':
        return Promise.resolve({ data: cicStatus })
      case '/sofidy/status':
        return Promise.resolve({ data: sofidyStatus })
      case '/accounts':
        return Promise.resolve({ data: accountsFixture })
      case '/revolut/status':
        return Promise.resolve({ data: revolutStatus })
      case '/revolut/sync/progress':
        return Promise.resolve({ data: revolutProgress })
      default:
        return Promise.reject(new Error(`Unexpected GET ${url}`))
    }
  })
}

/** Overridden per test to drive the session-provider rows. */
let amundiStatus: Record<string, unknown>
let simplefinStatus: Record<string, unknown>
let accountsFixture: unknown[]
let revolutStatus: Record<string, unknown>
let revolutProgress: Record<string, unknown>

const REVOLUT_NOT_REMEMBERED = { connected: false, remembered: false, lastSyncedAt: null }
const REVOLUT_REMEMBERED = { connected: false, remembered: true, lastSyncedAt: '2026-08-10T08:00:00Z' }
const REVOLUT_RUNNING = {
  running: true, phase: 'LOGIN', elapsedSeconds: 1, remainingSeconds: null,
  accountsFound: null, done: false, error: null, errorCode: null, discovered: [],
}
const discoveredAccount = (externalId: string) => ({
  externalId, name: externalId, type: 'CHECKING', currency: 'EUR', balance: 10,
  parentExternalId: null, alreadyImported: false, transactionCount: 0,
})
const REVOLUT_DONE = {
  ...REVOLUT_RUNNING, running: false, phase: 'DONE', done: true, accountsFound: 2,
  discovered: [discoveredAccount('acc-1'), discoveredAccount('acc-2')],
}

const SIMPLEFIN_DISCONNECTED = {
  connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null,
}
let amexStatus: Record<string, unknown>
let caisseEpargneStatus: Record<string, unknown>
let corumStatus: Record<string, unknown>
let sofidyStatus: Record<string, unknown>
let cmesStatus: Record<string, unknown>
let cicStatus: Record<string, unknown>

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

/** A credit card created by the Amex sidecar (`AmexSyncService.PROVIDER`). */
const AMEX_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 4,
  name: 'Gold Card',
  type: 'CREDIT_CARD',
  provider: 'American Express',
}

/** A current account created by the Caisse d'Epargne sidecar (`CaisseEpargneSyncService.PROVIDER`). */
const CAISSE_EPARGNE_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 5,
  name: 'Compte joint',
  type: 'CHECKING',
  provider: "Caisse d'Epargne",
}

/** An account created by the Crédit Mutuel / CIC Épargne Salariale sidecar (`CmesSyncService.PROVIDER`). */
const CMES_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 6,
  name: 'PEG — EURO-INFORMATION',
  type: 'EMPLOYEE_SAVINGS',
  provider: 'Crédit Mutuel Épargne Salariale',
}

/** An account created by the CIC (livrets) sidecar (`CicSyncService.PROVIDER`). */
const CIC_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 7,
  name: 'Livret Bleu',
  type: 'SAVINGS',
  provider: 'CIC',
}

/** A SCPI account linked to no provider fund; tests set `provider` and the fund codes. */
const SCPI_ACCOUNT = {
  ...TR_ACCOUNT,
  id: 3,
  name: 'Corum Origin',
  type: 'SCPI',
  provider: null as string | null,
  scpi: { corumFundCode: null, sofidyFundCode: null },
}

/** Resets the per-test fixtures to "only Trade Republic exists". */
function resetFixtures() {
  amundiStatus = AMUNDI_INACTIVE
  simplefinStatus = SIMPLEFIN_DISCONNECTED
  amexStatus = AMUNDI_INACTIVE
  caisseEpargneStatus = { ...AMUNDI_INACTIVE, unsupported: [], unsupportedCount: 0 }
  corumStatus = AMUNDI_INACTIVE
  sofidyStatus = AMUNDI_INACTIVE
  cmesStatus = AMUNDI_INACTIVE
  cicStatus = AMUNDI_INACTIVE
  accountsFixture = [TR_ACCOUNT]
  revolutStatus = REVOLUT_NOT_REMEMBERED
  revolutProgress = REVOLUT_DONE
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

const REVOLUT_ACCOUNT = { ...TR_ACCOUNT, id: 3, name: 'Revolut Main', type: 'CHECKING', provider: 'Revolut' }

function renderModal(onOpenChange: (open: boolean) => void = () => {}) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        <TooltipProvider>{children}</TooltipProvider>
      </QueryClientProvider>
    )
  }

  render(<SyncAllModal open onOpenChange={onOpenChange} />, { wrapper: Wrapper })
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
    expect(screen.queryByText('SimpleFIN')).not.toBeInTheDocument()
  })

  it('lists SimpleFIN when connected and syncs through its endpoint', async () => {
    simplefinStatus = { ...SIMPLEFIN_DISCONNECTED, connected: true, connectionId: 1, status: 'CONNECTED' }
    apiPost.mockResolvedValue({ data: [] })
    renderModal()

    const row = (await screen.findByText('SimpleFIN')).closest('[data-slot="card"]') as HTMLElement
    expect(row).not.toBeNull()
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/simplefin/sync'))
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

  it('lists and syncs a live American Express session', async () => {
    amexStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: amexStatus })
    renderModal()

    const row = (await screen.findByText('American Express')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amex/sync'))
    expect(navigate).not.toHaveBeenCalled()
  })

  it('keeps an expired American Express row with its card and routes to its tab', async () => {
    accountsFixture = [TR_ACCOUNT, AMEX_ACCOUNT]
    renderModal()

    const row = (await screen.findByText('American Express')).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('sync.all.sessionExpired')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=amex'))
    expect(apiPost).not.toHaveBeenCalledWith('/amex/sync')
  })

  it("lists and syncs a live Caisse d'Epargne session", async () => {
    caisseEpargneStatus = { ...caisseEpargneStatus, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: caisseEpargneStatus })
    renderModal()

    const row = (await screen.findByText("Caisse d'Epargne")).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/caisse-epargne/sync'))
    expect(navigate).not.toHaveBeenCalled()
  })

  /** Its login is the bank's keypad plus a Sécur'Pass approval, which only its tab can run. */
  it("keeps an expired Caisse d'Epargne row with its accounts and routes to its tab", async () => {
    accountsFixture = [TR_ACCOUNT, CAISSE_EPARGNE_ACCOUNT]
    renderModal()

    const row = (await screen.findByText("Caisse d'Epargne")).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('sync.all.sessionExpired')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=caisse-epargne'))
    expect(apiPost).not.toHaveBeenCalledWith('/caisse-epargne/sync')
  })

  /** The backend refuses a dead CE session (`SESSION_EXPIRED`), so the batch must skip the row. */
  it("leaves an expired Caisse d'Epargne row out of \"Sync all\"", async () => {
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'IDLE' }
    accountsFixture = [AMUNDI_ACCOUNT, CAISSE_EPARGNE_ACCOUNT]
    apiPost.mockResolvedValue({ data: amundiStatus })
    const onOpenChange = vi.fn()
    renderModal(onOpenChange)

    await screen.findByText("Caisse d'Epargne")
    const button = screen.getByRole('button', { name: /sync\.all\.syncAll/ })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amundi/sync'))
    expect(apiPost).not.toHaveBeenCalledWith('/caisse-epargne/sync')
    expect(navigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('lists and syncs a live Crédit Mutuel / CIC Épargne Salariale session', async () => {
    cmesStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: cmesStatus })
    renderModal()

    const row = (await screen.findByText('Crédit Mutuel / CIC Épargne Salariale')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/cmes/sync'))
    expect(navigate).not.toHaveBeenCalled()
  })

  it('includes a live Crédit Mutuel / CIC Épargne Salariale session in "Sync all"', async () => {
    cmesStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: cmesStatus })
    renderModal()

    await screen.findByText('Crédit Mutuel / CIC Épargne Salariale')
    await screen.findByText('Amundi')
    const syncAll = screen.getByRole('button', { name: /sync\.all\.syncAll/ })
    await waitFor(() => expect(syncAll).toBeEnabled())
    fireEvent.click(syncAll)
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/cmes/sync'))
    expect(apiPost).toHaveBeenCalledWith('/amundi/sync')
  })

  /** A first sync that expired before importing anything leaves a session but no account. */
  it('keeps an expired Crédit Mutuel / CIC Épargne Salariale session that has no account yet', async () => {
    cmesStatus = { ...AMUNDI_INACTIVE, syncStatus: 'FAILED', lastSyncError: 'SESSION_EXPIRED' }
    renderModal()

    const row = (await screen.findByText('Crédit Mutuel / CIC Épargne Salariale')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=cmes'))
    expect(apiPost).not.toHaveBeenCalledWith('/cmes/sync')
  })

  it('hides Crédit Mutuel / CIC Épargne Salariale with no session and no account', async () => {
    renderModal()

    expect(await screen.findByText('Trade Republic')).toBeInTheDocument()
    expect(screen.queryByText('Crédit Mutuel / CIC Épargne Salariale')).not.toBeInTheDocument()
  })

  /** Its login can need a code by SMS or a confirmation in the app, which only its tab can take. */
  it('keeps an expired Crédit Mutuel / CIC Épargne Salariale row with its accounts and routes to its tab', async () => {
    accountsFixture = [TR_ACCOUNT, CMES_ACCOUNT]
    renderModal()

    const row = (await screen.findByText('Crédit Mutuel / CIC Épargne Salariale')).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('sync.all.sessionExpired')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=cmes'))
    expect(apiPost).not.toHaveBeenCalledWith('/cmes/sync')
  })

  it('lists and syncs a live CIC (livrets) session', async () => {
    cicStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: cicStatus })
    renderModal()

    const row = (await screen.findByText('CIC (livrets)')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/cic/sync'))
    expect(navigate).not.toHaveBeenCalled()
  })

  it('keeps an expired CIC session without accounts and reconnects with stored credentials', async () => {
    cicStatus = { ...AMUNDI_INACTIVE, syncStatus: 'FAILED', lastSyncError: 'SESSION_EXPIRED', canReconnect: true }
    renderModal()

    const title = await screen.findByText('CIC (livrets)')
    const row = title.closest('[data-slot="card"]')
    if (!(row instanceof HTMLElement)) throw new Error('Missing CIC connection card')
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=cic&reconnect=1'))
    expect(apiPost).not.toHaveBeenCalledWith('/cic/sync')
  })

  it('hides CIC with no stored session and no account', async () => {
    renderModal()

    expect(await screen.findByText('Trade Republic')).toBeInTheDocument()
    expect(screen.queryByText('CIC (livrets)')).not.toBeInTheDocument()
  })

  /** CIC asks for a confirmation in its app at least once a day, which only its tab can wait for. */
  it('keeps an expired CIC (livrets) row with its accounts and routes to its tab', async () => {
    accountsFixture = [TR_ACCOUNT, CIC_ACCOUNT]
    renderModal()

    const row = (await screen.findByText('CIC (livrets)')).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('sync.all.sessionExpired')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=cic'))
    expect(apiPost).not.toHaveBeenCalledWith('/cic/sync')
  })

  /** Stored credentials only need the phone confirmation, so the tab starts the reconnect itself. */
  it('asks the CIC tab to reconnect at once when credentials are stored', async () => {
    cicStatus = { ...AMUNDI_INACTIVE, canReconnect: true }
    accountsFixture = [TR_ACCOUNT, CIC_ACCOUNT]
    renderModal()

    const row = (await screen.findByText('CIC (livrets)')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=cic&reconnect=1'))
  })

  it('reports a failed American Express run on a live session', async () => {
    amexStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'FAILED', lastSyncError: 'INTERNAL_ERROR' }
    renderModal()

    const row = (await screen.findByText('American Express')).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('FAILED')).toBeInTheDocument()
  })

  it('hides American Express with no session and no card', async () => {
    renderModal()

    expect(await screen.findByText('Trade Republic')).toBeInTheDocument()
    expect(screen.queryByText('American Express')).not.toBeInTheDocument()
  })

  it('lists and syncs a live Sofidy session', async () => {
    sofidyStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'SUCCESS' }
    apiPost.mockResolvedValue({ data: sofidyStatus })
    renderModal()

    const row = (await screen.findByText('Sofidy')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/sofidy/sync'))
  })

  it('hides CORUM and Sofidy when their sessions are inactive and no SCPI account is theirs', async () => {
    accountsFixture = [TR_ACCOUNT, { ...SCPI_ACCOUNT, provider: null }]
    renderModal()

    expect(await screen.findByText('Trade Republic')).toBeInTheDocument()
    expect(screen.queryByText('CORUM')).not.toBeInTheDocument()
    expect(screen.queryByText('Sofidy')).not.toBeInTheDocument()
  })

  it('lists CORUM when its session is live', async () => {
    corumStatus = { ...AMUNDI_INACTIVE, isActive: true }
    renderModal()

    expect(await screen.findByText('CORUM')).toBeInTheDocument()
  })

  /**
   * An expired SCPI session must stay listed and route to its tab, like every other session
   * connector: the reconciler stamps `provider` on the accounts it creates.
   */
  it.each([
    ['CORUM', 'corum'],
    ['Sofidy', 'sofidy'],
  ])('keeps an expired %s row with a reconnect action when it created an account', async (provider, tab) => {
    accountsFixture = [TR_ACCOUNT, { ...SCPI_ACCOUNT, provider }]
    renderModal()

    const row = (await screen.findByText(provider)).closest('[data-slot="card"]') as HTMLElement
    expect(within(row).getByText('sync.all.sessionExpired')).toBeInTheDocument()
    fireEvent.click(within(row).getByRole('button'))
    await waitFor(() => expect(navigate).toHaveBeenCalledWith(`/sync?tab=${tab}`))
    expect(apiPost).not.toHaveBeenCalledWith(`/${tab}/sync`)
  })

  /** A manual SCPI the sync adopted keeps the provider the user typed; its fund code links it. */
  it.each([
    ['CORUM', 'corumFundCode'],
    ['Sofidy', 'sofidyFundCode'],
  ])('keeps an expired %s row for an adopted SCPI account', async (provider, fundCodeKey) => {
    accountsFixture = [TR_ACCOUNT, {
      ...SCPI_ACCOUNT, provider: 'Mon conseiller',
      scpi: { ...SCPI_ACCOUNT.scpi, [fundCodeKey]: 'F1' },
    }]
    renderModal()

    expect(await screen.findByText(provider)).toBeInTheDocument()
  })
})

/**
 * #207: "Sync all" used to reach Revolut's navigate('/sync?tab=revolut') for a remembered
 * session, closing the modal mid-batch. Revolut now syncs in place; only its own row button
 * (without remembered credentials) opens the tab.
 */
describe('SyncAllModal Revolut and "Sync all"', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    navigate.mockReset()
    resetFixtures()
    // A second batch-syncable row next to Revolut.
    amundiStatus = { ...AMUNDI_INACTIVE, isActive: true, syncStatus: 'IDLE' }
    accountsFixture = [TR_ACCOUNT, AMUNDI_ACCOUNT, REVOLUT_ACCOUNT]
    mockStatusEndpoints()
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/sync') return Promise.resolve({ data: REVOLUT_RUNNING })
      if (url === '/revolut/sync/confirm') return Promise.resolve({ data: undefined })
      return Promise.resolve({ data: amundiStatus })
    })
  })

  async function clickSyncAll(onOpenChange: (open: boolean) => void) {
    renderModal(onOpenChange)
    await screen.findByText('Revolut')
    const button = screen.getByRole('button', { name: /sync\.all\.syncAll/ })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
  }

  it('never navigates nor closes the modal when Revolut has a remembered session', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    const onOpenChange = vi.fn()

    await clickSyncAll(onOpenChange)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amundi/sync'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/sync', {}))
    expect(navigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('never navigates nor closes the modal when Revolut has no remembered session', async () => {
    revolutStatus = REVOLUT_NOT_REMEMBERED
    const onOpenChange = vi.fn()

    await clickSyncAll(onOpenChange)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amundi/sync'))
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync', expect.anything())
    expect(navigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('still opens the Revolut tab from the row button when there is no remembered session', async () => {
    revolutStatus = REVOLUT_NOT_REMEMBERED
    const onOpenChange = vi.fn()
    renderModal(onOpenChange)

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('/sync?tab=revolut'))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync', expect.anything())
  })

  it('syncs a remembered Revolut session in place: discover then auto-confirm everything', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    const onOpenChange = vi.fn()
    renderModal(onOpenChange)

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/sync', {}))
    // The poll reports the finished job; remember=true so the stored credentials survive the
    // confirm, voluntary=false so accounts the user deleted stay deleted.
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/sync/confirm', {
      selectedExternalIds: ['acc-1', 'acc-2'],
      remember: true,
      voluntary: false,
    }))
    expect(navigate).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('confirms accounts when discovery finishes before the start request returns', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    const immediateSuccess = { ...REVOLUT_DONE }
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/sync') return Promise.resolve({ data: immediateSuccess })
      if (url === '/revolut/sync/confirm') return Promise.resolve({ data: undefined })
      return Promise.resolve({ data: amundiStatus })
    })
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/sync/confirm', {
      selectedExternalIds: ['acc-1', 'acc-2'],
      remember: true,
      voluntary: false,
    }))
  })

  it('shows an immediate discovery error without confirming accounts', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/sync') {
        return Promise.resolve({ data: { ...REVOLUT_DONE, error: 'REVOLUT_LOGIN_FAILED', discovered: [] } })
      }
      if (url === '/revolut/sync/confirm') return Promise.resolve({ data: undefined })
      return Promise.resolve({ data: amundiStatus })
    })
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    expect(await within(row).findByText('REVOLUT_LOGIN_FAILED')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync/confirm', expect.anything())
    await waitFor(() =>
      expect(apiGet.mock.calls.filter(([url]) => url === '/revolut/status')).toHaveLength(2))
  })

  it('clears the spinner and refreshes Revolut status when starting discovery fails', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/sync') return Promise.reject(new Error('offline'))
      return Promise.resolve({ data: amundiStatus })
    })
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    const button = within(row).getByRole('button')
    fireEvent.click(button)

    await waitFor(() => expect(button).toBeEnabled())
    await waitFor(() =>
      expect(apiGet.mock.calls.filter(([url]) => url === '/revolut/status')).toHaveLength(2))
  })

  it('finishes an immediate discovery with no accounts without confirming', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/sync') {
        return Promise.resolve({ data: { ...REVOLUT_DONE, discovered: [] } })
      }
      if (url === '/revolut/sync/confirm') return Promise.resolve({ data: undefined })
      return Promise.resolve({ data: amundiStatus })
    })
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    const button = within(row).getByRole('button')
    fireEvent.click(button)

    await waitFor(() => expect(button).toBeEnabled())
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync/confirm', expect.anything())
  })

  it('shows a translated captcha message on the Revolut row, never the approval countdown', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    revolutProgress = {
      ...REVOLUT_DONE, phase: 'LOGGING_IN', discovered: [],
      error: 'English sentence', errorCode: 'HUMAN_VERIFICATION_REQUIRED',
    }
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    expect(await within(row).findByText('sync.revolut.errors.humanVerificationRequired')).toBeInTheDocument()
    expect(within(row).queryByText('English sentence')).not.toBeInTheDocument()
    expect(within(row).queryByText(/awaitingApproval/)).not.toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync/confirm', expect.anything())
  })

  it('shows the discovery error on the Revolut row and skips the confirm', async () => {
    revolutStatus = REVOLUT_REMEMBERED
    revolutProgress = { ...REVOLUT_DONE, error: 'REVOLUT_LOGIN_FAILED', discovered: [] }
    renderModal()

    const row = (await screen.findByText('Revolut')).closest('[data-slot="card"]') as HTMLElement
    fireEvent.click(within(row).getByRole('button'))

    expect(await within(row).findByText('REVOLUT_LOGIN_FAILED')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalledWith('/revolut/sync/confirm', expect.anything())
    expect(navigate).not.toHaveBeenCalled()
  })
})
