import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ImportTransactionsModal } from './ImportTransactionsModal'
import type { TransactionImportPreviewResponse } from '@/types/api'

const previewMutate = vi.fn()
const executeMutate = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  usePreviewImport: () => ({ mutateAsync: previewMutate, isPending: false }),
  useExecuteImport: () => ({ mutateAsync: executeMutate, isPending: false }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => (typeof opts === 'string' ? opts : key),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const PREVIEW: TransactionImportPreviewResponse = {
  fileToken: 'tok-1',
  detectedColumns: ['Date', 'Sens', 'ISIN', 'Quantite', 'Cours', 'Frais'],
  sampleRows: [['15/01/2024', 'Achat', 'IE00B4L5Y983', '10', '85,20', '1,00']],
  totalRows: 1,
  hasHeaderRow: true,
  dialect: { delimiter: ';', decimal: 'COMMA', dateFormat: 'dd/MM/yyyy' },
  suggestedMapping: {
    date: 0, side: 1, tickerOrIsin: 2, name: null, quantity: 3, unitPrice: 4, fees: 5, currency: null, amount: null,
    description: null, debit: null, credit: null,
  },
  mode: 'TRADES',
}

const STATEMENT_PREVIEW: TransactionImportPreviewResponse = {
  fileToken: 'tok-2',
  detectedColumns: ['Date', 'Libellé', 'Débit', 'Crédit'],
  sampleRows: [['02/11/2026', 'CB CARREFOUR', '45,30', '']],
  totalRows: 1,
  hasHeaderRow: true,
  dialect: { delimiter: ';', decimal: 'COMMA', dateFormat: 'dd/MM/yyyy' },
  suggestedMapping: {
    date: 0, side: null, tickerOrIsin: null, name: null, quantity: null, unitPrice: null, fees: null, currency: null,
    amount: null, description: 1, debit: 2, credit: 3,
  },
  mode: 'CASH',
}

function uploadFile() {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement
  const file = new File(['csv'], 'trades.csv', { type: 'text/csv' })
  fireEvent.change(input, { target: { files: [file] } })
}

describe('ImportTransactionsModal', () => {
  beforeEach(() => {
    previewMutate.mockReset()
    executeMutate.mockReset()
  })

  it('previews the file then renders the detected columns for mapping', async () => {
    previewMutate.mockResolvedValue(PREVIEW)
    render(<ImportTransactionsModal open onOpenChange={vi.fn()} accountId={2} />)

    uploadFile()

    // Step 2 renders the mapping UI with the detected columns.
    expect(await screen.findByText('import.mapColumns')).toBeInTheDocument()
    expect(screen.getAllByText('ISIN').length).toBeGreaterThan(0)
    expect(previewMutate).toHaveBeenCalledOnce()
  })

  it('sends the assembled import request and shows the result', async () => {
    previewMutate.mockResolvedValue(PREVIEW)
    executeMutate.mockResolvedValue({ imported: 2, skipped: 0, errors: [], duplicates: 0 })
    render(<ImportTransactionsModal open onOpenChange={vi.fn()} accountId={2} />)

    uploadFile()
    await screen.findByText('import.mapColumns')

    fireEvent.click(screen.getByRole('button', { name: 'import.import' }))

    await waitFor(() => expect(executeMutate).toHaveBeenCalledOnce())
    expect(executeMutate).toHaveBeenCalledWith(
      expect.objectContaining({ fileToken: 'tok-1', hasHeaderRow: true, feesIncludedInAmount: false }),
    )
    expect(await screen.findByText('import.rowsImported')).toBeInTheDocument()
  })

  it('maps a bank statement with label, debit and credit and reports the lines already held', async () => {
    previewMutate.mockResolvedValue(STATEMENT_PREVIEW)
    executeMutate.mockResolvedValue({ imported: 1, skipped: 0, errors: [], duplicates: 3 })
    render(<ImportTransactionsModal open onOpenChange={vi.fn()} accountId={9} />)

    uploadFile()
    await screen.findByText('import.mapColumns')

    expect(screen.getByText('import.field.debit')).toBeInTheDocument()
    expect(screen.getByText('import.field.credit')).toBeInTheDocument()
    const cashHint = screen.getByText('import.cashHint')
    expect(cashHint).toHaveClass('text-sm')
    expect(cashHint).not.toHaveClass('text-xs')
    expect(screen.queryByText('import.field.tickerOrIsin')).not.toBeInTheDocument()
    expect(screen.queryByText('import.feesIncluded')).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('import.invertSign'))
    fireEvent.click(screen.getByRole('button', { name: 'import.import' }))

    await waitFor(() => expect(executeMutate).toHaveBeenCalledOnce())
    expect(executeMutate).toHaveBeenCalledWith(expect.objectContaining({
      fileToken: 'tok-2',
      invertSign: true,
      mapping: expect.objectContaining({ date: 0, description: 1, debit: 2, credit: 3 }),
    }))
    expect(await screen.findByText('import.rowsDuplicate')).toBeInTheDocument()
  })
})
