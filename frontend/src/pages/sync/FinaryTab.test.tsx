import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { formatCurrency, localeFromLanguage } from '@/lib/utils'
import type { FinaryAccountMapping, FinaryPreviewResponse } from '@/types/api'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { FinaryTab } = await import('./FinaryTab')

const TOTP_REQUIRED_STATUS = {
  connected: true,
  sessionId: 1,
  status: 'TOTP_REQUIRED',
  lastSyncedAt: null,
  maskedEmail: 'u***@example.com',
}

const SUGGESTED: FinaryAccountMapping[] = [
  {
    finaryId: 'w1',
    finaryName: 'Ledger',
    finaryCategory: 'cryptos',
    action: 'MAP_EXISTING',
    targetAccountId: 10,
  },
]

function previewResponse(partial: Partial<FinaryPreviewResponse>): FinaryPreviewResponse {
  return {
    accounts: [
      {
        finaryId: 'w1',
        finaryName: 'Ledger',
        finaryInstitution: 'Ledger',
        finaryCategory: 'cryptos',
        suggestedType: 'CRYPTO',
        currentBalance: 1234.5,
        nativeCurrency: 'USD',
        transactionCount: 0,
      },
      {
        finaryId: 'c1',
        finaryName: 'Compte courant',
        finaryInstitution: 'LBP',
        finaryCategory: 'checkings',
        suggestedType: 'CHECKING',
        currentBalance: 50,
        nativeCurrency: 'EUR',
        transactionCount: 0,
      },
    ],
    existingPicsouAccounts: [
      { id: 10, name: 'Ledger', type: 'CRYPTO', currency: 'EUR', currentBalance: 0 },
      { id: 20, name: 'Courant', type: 'CHECKING', currency: 'EUR', currentBalance: 0 },
    ] as FinaryPreviewResponse['existingPicsouAccounts'],
    totalTransactionCount: 0,
    fileToken: 'sync-token',
    suggestedMappings: SUGGESTED,
    ...partial,
  }
}

const IMPORT_RESULT = {
  accountsCreated: 0,
  accountsMapped: 1,
  accountsSkipped: 0,
  transactionsImported: 0,
  importedAccounts: [],
}

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<FinaryTab />, { wrapper: Wrapper })
}

async function submitTotp() {
  const totpInput = await screen.findByLabelText('sync.finary.totp')
  fireEvent.change(totpInput, { target: { value: '123456' } })
  const submit = totpInput.parentElement?.parentElement?.querySelector('button')
  if (!submit) throw new Error('TOTP submit button not found')
  fireEvent.click(submit)
}

function servePreview(preview: FinaryPreviewResponse) {
  apiPost.mockImplementation((url: string) => {
    if (url.startsWith('/finary/api-sync/preview')) return Promise.resolve({ data: preview })
    if (url === '/finary/api-sync/execute') return Promise.resolve({ data: IMPORT_RESULT })
    return Promise.reject(new Error(`unexpected POST ${url}`))
  })
}

describe('FinaryTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: TOTP_REQUIRED_STATUS })
  })

  it('executes an auto-mapped API sync through the API endpoint, not the XLSX import', async () => {
    servePreview(previewResponse({ autoMapped: true }))

    renderTab()
    await submitTotp()

    expect(await screen.findByText('sync.finary.accountsMapped')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledWith('/finary/api-sync/execute', {
      syncToken: 'sync-token',
      mappings: SUGGESTED,
    })
    expect(apiPost).not.toHaveBeenCalledWith('/finary/import', expect.anything())
  })

  it('shows each Finary balance in the currency the preview reports', async () => {
    servePreview(previewResponse({ autoMapped: false }))

    renderTab()
    await submitTotp()

    expect(
      await screen.findByText(formatCurrency(1234.5, 'USD', localeFromLanguage('en'))),
    ).toBeInTheDocument()
    expect(
      screen.queryByText(formatCurrency(1234.5, 'EUR', localeFromLanguage('en'))),
    ).not.toBeInTheDocument()
  })

  it('does not offer a Picsou account that another Finary row already maps onto', async () => {
    servePreview(previewResponse({ autoMapped: false }))

    renderTab()
    await submitTotp()

    const mapButtons = await screen.findAllByRole('button', { name: 'sync.finary.mapExisting' })
    fireEvent.click(mapButtons[1])

    const [ledgerPicker, courantPicker] = screen.getAllByRole('combobox') as HTMLSelectElement[]
    const offered = (select: HTMLSelectElement) =>
      Array.from(select.options).map((o) => o.value).filter(Boolean)
    expect(offered(ledgerPicker)).toEqual(['10', '20'])
    expect(offered(courantPicker)).toEqual(['20'])
  })
})
