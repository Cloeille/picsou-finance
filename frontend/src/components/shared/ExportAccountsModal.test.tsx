import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import type { Account, AccountType } from '@/types/api'

const mutate = vi.fn()
const isPending = { value: false }

vi.mock('@/features/export/hooks', () => ({
  useExportAccountsXlsx: () => ({ mutate, isPending: isPending.value }),
}))

const toastSuccess = vi.fn()
const toastError = vi.fn()
vi.mock('sonner', () => ({ toast: { success: toastSuccess, error: toastError } }))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { ExportAccountsModal } = await import('./ExportAccountsModal')

function account(id: number, name: string, type: AccountType = 'PEA'): Account {
  return {
    id,
    name,
    type,
    provider: null,
    currency: 'EUR',
    currentBalance: 1000,
    currentBalanceEur: 1000,
    lastSyncedAt: null,
    isManual: true,
    color: '#6366f1',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-01-01T00:00:00Z',
  } as Account
}

const ACCOUNTS = [account(1, 'PEA Bourso'), account(2, 'Livret A', 'LIVRET_A')]

function renderModal(accounts = ACCOUNTS) {
  return render(<ExportAccountsModal open onOpenChange={vi.fn()} accounts={accounts} />)
}

function checkboxes() {
  return screen.getAllByRole('checkbox')
}

describe('ExportAccountsModal', () => {
  beforeEach(() => {
    mutate.mockReset()
    toastSuccess.mockReset()
    toastError.mockReset()
    isPending.value = false
  })

  it('lists every account, sorted by name, all selected', () => {
    renderModal()

    expect(screen.getByText('Livret A')).toBeInTheDocument()
    expect(screen.getByText('PEA Bourso')).toBeInTheDocument()
    expect(checkboxes()).toHaveLength(2)
    expect(screen.getByText('accounts.export.selected:2')).toBeInTheDocument()
  })

  it('unticking one account keeps the other in the payload', () => {
    renderModal()
    // Sorted by name, so the first row is Livret A (id 2).
    fireEvent.click(checkboxes()[0])

    fireEvent.click(screen.getByRole('button', { name: /accounts.export.submit/ }))

    expect(mutate.mock.calls[0][0].accountIds).toEqual([1])
  })

  it('sends the localized column labels alongside the ids', () => {
    renderModal()
    fireEvent.click(screen.getByRole('button', { name: /accounts.export.submit/ }))

    const payload = mutate.mock.calls[0][0]
    expect(payload.accountIds).toEqual([1, 2])
    // The backend has no message bundle -- the wording has to travel with the request.
    expect(payload.labels.quantity).toBe('export.sheet.quantity')
    expect(payload.labels.amortization).toBe('export.sheet.amortization')
  })

  it('clears and restores the whole selection', () => {
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: 'accounts.export.selectNone' }))
    expect(screen.getByText('accounts.export.selected:0')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /accounts.export.submit/ })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'accounts.export.selectAll' }))
    expect(screen.getByText('accounts.export.selected:2')).toBeInTheDocument()
  })

  it('surfaces a failure as a toast rather than silently doing nothing', () => {
    mutate.mockImplementation((_payload, opts) => opts.onError({ message: 'boom' }))
    renderModal()

    fireEvent.click(screen.getByRole('button', { name: /accounts.export.submit/ }))

    expect(toastError).toHaveBeenCalled()
  })

  it('closes on success and names the downloaded file', () => {
    const onOpenChange = vi.fn()
    mutate.mockImplementation((_payload, opts) =>
      opts.onSuccess({ filename: 'picsou-comptes-20260818-120000.xlsx' })
    )
    render(<ExportAccountsModal open onOpenChange={onOpenChange} accounts={ACCOUNTS} />)

    fireEvent.click(screen.getByRole('button', { name: /accounts.export.submit/ }))

    expect(toastSuccess).toHaveBeenCalledWith(
      'accounts.export.success:picsou-comptes-20260818-120000.xlsx'
    )
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('shows an empty state instead of an export button with nothing to export', () => {
    renderModal([])

    expect(screen.getByText('accounts.export.empty')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /accounts.export.submit/ })).not.toBeInTheDocument()
  })
})
