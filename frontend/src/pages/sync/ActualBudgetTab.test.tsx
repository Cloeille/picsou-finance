import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiPost } = vi.hoisted(() => ({ apiPost: vi.fn() }))

vi.mock('@/lib/api-client', () => ({
  api: { post: apiPost },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ amount: (value: number, currency: string) => `${value} ${currency}` }),
}))

const { ActualBudgetTab } = await import('./ActualBudgetTab')

const preview = {
  fileToken: 'token-1',
  currency: 'EUR',
  accounts: [{
    sourceId: 'acc-1', name: 'Everyday', offBudget: false, closed: false,
    suggestedType: 'CHECKING', balance: 2915.66, transactionCount: 7, importedAccountId: null,
  }],
  categories: [{ sourceId: 'cat-1', name: 'Groceries', groupName: 'Food', income: false, transactionCount: 2 }],
  existingAccounts: [],
  existingCategories: [],
  sampleTransactions: [
    {
      sourceId: 'tx-1', accountSourceId: 'acc-1', date: '2024-01-02', amount: -12.34,
      payee: 'Market', notes: 'Weekly shop', categorySourceId: 'cat-1', kind: 'REGULAR',
    },
    {
      sourceId: 'tx-2', accountSourceId: 'acc-1', date: '2024-01-15', amount: -500,
      payee: 'Rainy day', notes: null, categorySourceId: null, kind: 'TRANSFER',
    },
  ],
  totalTransactions: 8,
  transferTransactions: 3,
}

const result = {
  accountsCreated: 1, accountsMapped: 0, accountsSkipped: 0, categoriesCreated: 2,
  transactionsImported: 8, transactionsSkipped: 0,
}

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<ActualBudgetTab />, { wrapper: Wrapper })
}

async function uploadPreview(response: unknown = preview) {
  apiPost.mockResolvedValueOnce({ data: response })
  const file = new File(['PK'], 'My Budget.zip', { type: 'application/zip' })
  fireEvent.change(screen.getByLabelText('sync.actual.file'), { target: { files: [file] } })
  fireEvent.click(screen.getByRole('button', { name: 'sync.actual.preview' }))
  return file
}

async function confirmImport() {
  apiPost.mockResolvedValueOnce({ data: result })
  fireEvent.click(screen.getByRole('button', { name: 'sync.actual.import' }))
  fireEvent.click(screen.getByRole('button', { name: 'common.confirm' }))
  await screen.findByText('sync.actual.transactionsImported')
  return apiPost.mock.calls[1]
}

describe('ActualBudgetTab', () => {
  beforeEach(() => {
    apiPost.mockReset()
  })

  it('previews the file, then imports only after confirmation with the chosen mappings', async () => {
    renderTab()
    const file = await uploadPreview()
    await screen.findByText('Weekly shop')

    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/actual/import/preview')
    expect(body.get('file')).toBe(file)
    expect(screen.getByText('-12.34 EUR')).toBeInTheDocument()
    expect(screen.getByText('2915.66 EUR')).toBeInTheDocument()
    expect(screen.getByText('sync.actual.transfer')).toBeInTheDocument()
    expect(screen.getByText('sync.actual.transfersNeutral')).toBeInTheDocument()
    expect(screen.queryByRole('combobox', { name: 'sync.actual.currency' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.actual.import' }))
    expect(screen.getByText('sync.actual.confirmDescription')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)

    apiPost.mockResolvedValueOnce({ data: result })
    fireEvent.click(screen.getByRole('button', { name: 'common.confirm' }))
    await screen.findByText('sync.actual.transactionsImported')
    const [executeUrl, request] = apiPost.mock.calls[1]
    expect(executeUrl).toBe('/actual/import')
    expect(request).toEqual({
      fileToken: 'token-1',
      currency: 'EUR',
      accountMappings: [{
        sourceId: 'acc-1', action: 'CREATE_NEW',
        newAccount: { name: 'Everyday', type: 'CHECKING', currency: 'EUR', color: '#6366f1' },
      }],
      categoryMappings: [{ sourceId: 'cat-1', action: 'CREATE_NEW', name: 'Groceries' }],
    })
  })

  it('asks for the currency when the budget does not record one', async () => {
    renderTab()
    await uploadPreview({ ...preview, currency: null })
    const currency = await screen.findByLabelText('sync.actual.currency')

    fireEvent.change(currency, { target: { value: 'USD' } })
    const [, request] = await confirmImport()

    expect(request.currency).toBe('USD')
    expect(request.accountMappings[0].newAccount.currency).toBe('USD')
  })

  it('pre-maps exact matches and only offers compatible targets', async () => {
    renderTab()
    await uploadPreview({
      ...preview,
      existingAccounts: [
        { id: 31, name: 'Everyday', currency: 'EUR', type: 'CHECKING' },
        { id: 32, name: 'Everyday', currency: 'USD', type: 'CHECKING' },
        { id: 33, name: 'PEA', currency: 'EUR', type: 'PEA' },
      ],
      existingCategories: [
        { id: 41, name: 'Groceries', kind: 'EXPENSE', archived: false },
        { id: 42, name: 'Groceries', kind: 'INCOME', archived: false },
        { id: 43, name: 'Old groceries', kind: 'EXPENSE', archived: true },
      ],
    })

    const account = await screen.findByLabelText('sync.actual.targetAccount')
    expect(account).toHaveValue('31')
    expect(Array.from(account.querySelectorAll('option')).map((option) => option.value)).toEqual(['', '31'])
    const category = screen.getByLabelText('sync.actual.targetCategory')
    expect(category).toHaveValue('41')
    expect(Array.from(category.querySelectorAll('option')).map((option) => option.value)).toEqual(['', '41'])

    const [, request] = await confirmImport()
    expect(request.accountMappings).toEqual([{ sourceId: 'acc-1', action: 'MAP_EXISTING', targetAccountId: 31 }])
    expect(request.categoryMappings).toEqual([{ sourceId: 'cat-1', action: 'MAP_EXISTING', targetCategoryId: 41 }])
  })

  it('targets the account an earlier import created over a same-name account', async () => {
    renderTab()
    await uploadPreview({
      ...preview,
      accounts: [{ ...preview.accounts[0], importedAccountId: 35 }],
      existingAccounts: [
        { id: 31, name: 'Everyday', currency: 'EUR', type: 'CHECKING' },
        { id: 35, name: 'Everyday (Actual)', currency: 'EUR', type: 'CHECKING' },
      ],
    })

    const account = await screen.findByLabelText('sync.actual.targetAccount')
    expect(account).toHaveValue('35')
    expect(screen.getByText('sync.actual.previouslyImported')).toBeInTheDocument()
    fireEvent.change(account, { target: { value: '31' } })
    expect(screen.queryByText('sync.actual.previouslyImported')).not.toBeInTheDocument()
    fireEvent.change(account, { target: { value: '35' } })

    const [, request] = await confirmImport()
    expect(request.accountMappings).toEqual([{ sourceId: 'acc-1', action: 'MAP_EXISTING', targetAccountId: 35 }])
  })

  it('blocks the import until a mapped category has a target', async () => {
    renderTab()
    await uploadPreview()
    const action = await screen.findByLabelText('sync.actual.categoryAction')

    fireEvent.change(action, { target: { value: 'MAP_EXISTING' } })
    expect(screen.getByRole('button', { name: 'sync.actual.import' })).toBeDisabled()
    fireEvent.change(action, { target: { value: 'UNCATEGORIZED' } })
    expect(screen.getByRole('button', { name: 'sync.actual.import' })).toBeEnabled()

    const [, request] = await confirmImport()
    expect(request.categoryMappings).toEqual([{ sourceId: 'cat-1', action: 'UNCATEGORIZED' }])
  })

  it('shows the backend reason when a file is rejected', async () => {
    renderTab()
    apiPost.mockRejectedValueOnce({
      response: { status: 400, data: { detail: 'The Actual Budget archive contains an unsafe path' } },
    })
    fireEvent.change(screen.getByLabelText('sync.actual.file'), { target: { files: [new File(['PK'], 'evil.zip')] } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.actual.preview' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('The Actual Budget archive contains an unsafe path')
  })

  it('refuses an empty demo-mode response instead of rendering it', async () => {
    renderTab()
    await uploadPreview({})

    expect(await screen.findByRole('alert')).toHaveTextContent('sync.actual.errors.invalidPreview')
  })
})
