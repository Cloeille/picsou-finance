import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: vi.fn(), delete: vi.fn() },
}))

// Interpolation options are appended to the key so tests can assert on the count.
vi.mock('react-i18next', () => {
  const t = (key: string, options?: Record<string, unknown>) =>
    options && Object.keys(options).length > 0 ? `${key} ${JSON.stringify(options)}` : key
  return { useTranslation: () => ({ t }) }
})

const { BanquePopulairePanel } = await import('./BanquePopulairePanel')

const CONNECTED = {
  isActive: true,
  syncStatus: 'SUCCESS',
  lastSyncError: null,
  lastSyncStartedAt: '2026-11-14T08:00:00Z',
  lastSyncCompletedAt: '2026-11-14T08:00:05Z',
  remembered: true,
  unsupported: [] as { externalId: string; familyCode: string; subFamilyCode?: string | null }[],
  unsupportedCount: 0,
}

function withUnsupported(unsupported: typeof CONNECTED.unsupported) {
  apiGet.mockResolvedValue({
    data: { ...CONNECTED, unsupported, unsupportedCount: unsupported.length },
  })
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  return render(<BanquePopulairePanel />, { wrapper: Wrapper })
}

describe('BpceSessionPanel unsupported contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('names the home loan, personal loan and revolving credit next to their code', async () => {
    withUnsupported([
      { externalId: 'SECRET-CONTRACT-ID-1', familyCode: '6', subFamilyCode: '13' },
      { externalId: 'SECRET-CONTRACT-ID-2', familyCode: '6', subFamilyCode: '12' },
      { externalId: 'SECRET-CONTRACT-ID-3', familyCode: '2', subFamilyCode: '11' },
    ])
    renderPanel()

    expect(await screen.findByText('sync.bpceContracts.homeLoan (6/13)')).toBeInTheDocument()
    expect(screen.getByText('sync.bpceContracts.personalLoan (6/12)')).toBeInTheDocument()
    expect(screen.getByText('sync.bpceContracts.revolvingCredit (2/11)')).toBeInTheDocument()
    expect(screen.queryByText(/SECRET-CONTRACT-ID/)).not.toBeInTheDocument()
  })

  it('keeps the bare code of an unknown contract', async () => {
    withUnsupported([
      { externalId: 'SECRET-CONTRACT-ID-4', familyCode: '9', subFamilyCode: '99' },
      { externalId: 'SECRET-CONTRACT-ID-5', familyCode: '6' },
    ])
    renderPanel()

    expect(await screen.findByText('9/99')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
    expect(screen.queryByText(/sync\.bpceContracts\.(?!notImported)/)).not.toBeInTheDocument()
    expect(screen.queryByText(/SECRET-CONTRACT-ID/)).not.toBeInTheDocument()
  })

  it('adds the count of contracts not imported to the success line', async () => {
    withUnsupported([{ externalId: 'SECRET-CONTRACT-ID-1', familyCode: '6', subFamilyCode: '13' }])
    renderPanel()

    expect(await screen.findByText('sync.banquePopulaire.syncSuccess')).toBeInTheDocument()
    expect(screen.getByText('sync.bpceContracts.notImported {"count":1}')).toBeInTheDocument()
  })

  it('passes the plural count when several contracts are not imported', async () => {
    withUnsupported([
      { externalId: 'SECRET-CONTRACT-ID-1', familyCode: '6', subFamilyCode: '13' },
      { externalId: 'SECRET-CONTRACT-ID-2', familyCode: '6', subFamilyCode: '12' },
      { externalId: 'SECRET-CONTRACT-ID-3', familyCode: '9' },
    ])
    renderPanel()

    expect(await screen.findByText('sync.bpceContracts.notImported {"count":3}')).toBeInTheDocument()
  })

  it('shows no count when every contract is imported', async () => {
    withUnsupported([])
    renderPanel()

    expect(await screen.findByText('sync.banquePopulaire.syncSuccess')).toBeInTheDocument()
    expect(screen.queryByText(/sync\.bpceContracts\.notImported/)).not.toBeInTheDocument()
  })
})
