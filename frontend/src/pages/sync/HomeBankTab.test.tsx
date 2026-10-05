import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiPost } = vi.hoisted(() => ({ apiPost: vi.fn() }))

vi.mock('@/lib/api-client', () => ({
  api: { post: apiPost },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string, options?: { count?: number }) => key === 'sync.homebank.forecastsSkipped' ? `${key} ${options?.count}` : key }),
}))

vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ amount: (value: number, currency: string) => `${value} ${currency}` }),
}))

const { HomeBankTab } = await import('./HomeBankTab')

const preview = {
  fileToken: 'token-1',
  accounts: [{
    sourceId: 'account-1', name: 'Everyday', institution: 'Bank', sourceType: 'checking',
    suggestedType: 'CHECKING', currency: 'EUR', initialBalance: 10, balance: 20,
    transactionCount: 1, closed: false,
  }],
  categories: [{ sourceId: 'food', name: 'Food', income: false, transactionCount: 1 }],
  existingAccounts: [],
  existingCategories: [],
  sampleTransactions: [{
    sourceId: 'tx-1', accountSourceId: 'account-1', date: '2025-01-02', amount: -3.25,
    currency: 'EUR', payee: 'Market', notes: 'Fresh produce', categorySourceId: 'food', transfer: false,
  }],
  totalTransactions: 1,
  forecastTransactions: 2,
}

function renderTab(onClient?: (client: QueryClient) => void) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  onClient?.(client)
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<HomeBankTab />, { wrapper: Wrapper })
}

async function uploadPreview() {
  const file = new File(['encrypted hbk data'], 'archive.hbk')
  fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [file] } })
  fireEvent.change(screen.getByLabelText('sync.homebank.password'), { target: { value: 'open sesame' } })
  fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))
  await screen.findByText('Fresh produce')
  return file
}

describe('HomeBankTab', () => {
  beforeEach(() => {
    apiPost.mockReset()
    apiPost.mockResolvedValueOnce({ data: preview })
  })

  it('uploads the password only with preview, shows source transaction details, and executes only after confirmation', async () => {
    renderTab()
    const file = await uploadPreview()

    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/homebank/import/preview')
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
    expect(body.get('password')).toBe('open sesame')
    expect(body.get('currency')).toBeNull()
    expect(screen.getByText('Market')).toBeInTheDocument()
    expect(screen.getByText('Fresh produce')).toBeInTheDocument()
    expect(screen.getByText('-3.25 EUR')).toBeInTheDocument()
    expect(screen.getByText(/2025/)).toBeInTheDocument()
    expect(screen.getByText('sync.homebank.forecastsSkipped 2')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'sync.homebank.createCategory' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'sync.homebank.mapExistingCategory' })).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.homebank.password')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.import' }))
    expect(screen.getByText('sync.homebank.confirmDescription')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)

    apiPost.mockResolvedValueOnce({ data: {
      accountsCreated: 1, accountsMapped: 0, accountsSkipped: 0, categoriesCreated: 1,
      transactionsImported: 1, transactionsSkipped: 0,
    } })
    fireEvent.click(screen.getByRole('button', { name: 'common.confirm' }))

    await screen.findByText('sync.homebank.transactionsImported')
    const [executeUrl, request] = apiPost.mock.calls[1]
    expect(executeUrl).toBe('/homebank/import')
    expect(request.fileToken).toBe('token-1')
    expect(request.accountMappings[0]).toMatchObject({
      sourceId: 'account-1', action: 'CREATE_NEW',
      newAccount: { name: 'Everyday', type: 'CHECKING', provider: 'Bank', currency: 'EUR' },
    })
    expect(request.categoryMappings[0]).toEqual({ sourceId: 'food', action: 'CREATE_NEW', name: 'Food' })
    expect(JSON.stringify(request)).not.toContain('open sesame')
  })

  it('keeps the file and password available to retry after a failed preview', async () => {
    apiPost.mockReset()
    apiPost.mockRejectedValueOnce({ response: { status: 400, data: { detail: 'Wrong file password' } } })
      .mockResolvedValueOnce({ data: preview })
    renderTab()
    const file = new File(['encrypted hbk data'], 'archive.hbk')
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [file] } })
    fireEvent.change(screen.getByLabelText('sync.homebank.password'), { target: { value: 'open sesame' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong file password')
    expect(screen.getByLabelText('sync.homebank.file')).toHaveValue('')
    expect(screen.getByLabelText('sync.homebank.password')).toHaveValue('open sesame')
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))

    expect(await screen.findByText('Fresh produce')).toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(2)
    expect(apiPost.mock.calls[0][1].get('file')).toBe(file)
    expect(apiPost.mock.calls[1][1].get('file')).toBe(file)
  })

  it('requires an explicit ISO currency for QIF and sends it with preview', async () => {
    renderTab()
    const file = new File(['qif content'], 'history.qif')
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [file] } })
    expect(screen.getByLabelText('sync.homebank.file')).toHaveAttribute('accept', '.hbk,.hbexport,.qif')

    const currency = screen.getByLabelText('sync.homebank.currency')
    expect(currency).toBeRequired()
    expect(screen.queryByLabelText('sync.homebank.password')).not.toBeInTheDocument()
    const previewButton = screen.getByRole('button', { name: 'sync.homebank.preview' })
    expect(previewButton).toBeDisabled()
    fireEvent.change(currency, { target: { value: 'EU' } })
    expect(previewButton).toBeDisabled()
    fireEvent.change(currency, { target: { value: 'ABC' } })
    expect(previewButton).toBeDisabled()
    expect(apiPost).not.toHaveBeenCalled()
    fireEvent.change(currency, { target: { value: 'eur' } })
    expect(currency).toHaveValue('EUR')
    expect(previewButton).toBeEnabled()
    fireEvent.click(previewButton)

    await screen.findByText('Fresh produce')
    const body = apiPost.mock.calls[0][1] as FormData
    expect(body.get('file')).toBe(file)
    expect(body.get('currency')).toBe('EUR')
    expect(body.get('password')).toBeNull()
  })

  it('clears QIF currency when switching to an iOS file', () => {
    renderTab()
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['qif'], 'history.qif')] } })
    fireEvent.change(screen.getByLabelText('sync.homebank.currency'), { target: { value: 'USD' } })
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['ios'], 'archive.hbk')] } })
    expect(screen.queryByLabelText('sync.homebank.currency')).not.toBeInTheDocument()
    expect(screen.getByLabelText('sync.homebank.password')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['qif'], 'again.qif')] } })
    expect(screen.getByLabelText('sync.homebank.currency')).toHaveValue('')
  })

  it.each(['success', 'error'] as const)('removes the password from TanStack mutation variables after %s', async outcome => {
    apiPost.mockReset()
    if (outcome === 'error') apiPost.mockRejectedValueOnce(new Error('failed'))
    else apiPost.mockResolvedValueOnce({ data: preview })
    let client: QueryClient | undefined
    renderTab(value => { client = value })
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['encrypted'], 'archive.hbk')] } })
    fireEvent.change(screen.getByLabelText('sync.homebank.password'), { target: { value: 'cache-secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))
    if (outcome === 'success') await screen.findByText('Fresh produce')
    else await screen.findByRole('alert')
    const variables = client!.getMutationCache().getAll().map(mutation => mutation.state.variables)
    expect(JSON.stringify(variables)).not.toContain('cache-secret')
  })

  it('preserves a suggested LOAN type in the create mapping', async () => {
    apiPost.mockReset().mockResolvedValueOnce({ data: {
      ...preview,
      accounts: [{ ...preview.accounts[0], suggestedType: 'LOAN' }],
    } })
    renderTab()
    await uploadPreview()
    const accountType = screen.getAllByRole('combobox')[0]
    expect(accountType.querySelector('option[value="LOAN"]')).toBeInTheDocument()
    expect(accountType).toHaveValue('LOAN')
    apiPost.mockResolvedValueOnce({ data: {
      accountsCreated: 1, accountsMapped: 0, accountsSkipped: 0, categoriesCreated: 1,
      transactionsImported: 1, transactionsSkipped: 0,
    } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.import' }))
    fireEvent.click(screen.getByRole('button', { name: 'common.confirm' }))
    await screen.findByText('sync.homebank.transactionsImported')
    expect(apiPost.mock.calls[1][1].accountMappings[0].newAccount.type).toBe('LOAN')
  })

  it('allows restarting a preview to upload a new file', async () => {
    renderTab()
    await uploadPreview()
    apiPost.mockResolvedValueOnce({ data: { ...preview, fileToken: 'token-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.restart' }))
    expect(screen.getByLabelText('sync.homebank.file')).toBeInTheDocument()
    await uploadPreview()
    expect(apiPost).toHaveBeenCalledTimes(2)
  })

  it('ignores a file change while preview is in flight', async () => {
    apiPost.mockReset()
    let resolvePreview!: (value: { data: typeof preview }) => void
    apiPost.mockImplementationOnce(() => new Promise(resolve => { resolvePreview = resolve }))
    renderTab()
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['one'], 'one.hbk')] } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['two'], 'two.hbk')] } })
    await waitFor(() => expect(resolvePreview).toBeTypeOf('function'))
    resolvePreview({ data: preview })
    await screen.findByText('Fresh produce')
    expect(screen.queryByText('two.hbk')).not.toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)
  })

  it('rejects an empty demo preview response without rendering it as valid data', async () => {
    apiPost.mockReset().mockResolvedValueOnce({ data: {} })
    renderTab()
    fireEvent.change(screen.getByLabelText('sync.homebank.file'), { target: { files: [new File(['x'], 'empty.hbk')] } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.homebank.preview' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('sync.homebank.errors.invalidPreview')
  })

  it('suggests exact compatible account/category matches and excludes incompatible category targets', async () => {
    apiPost.mockReset().mockResolvedValueOnce({ data: {
      ...preview,
      existingAccounts: [
        { id: 31, name: 'Everyday', currency: 'EUR', type: 'CHECKING' },
        { id: 32, name: 'Everyday', currency: 'USD', type: 'CHECKING' },
        { id: 33, name: 'Everyday', currency: 'EUR', type: 'PEA' },
      ],
      existingCategories: [
        { id: 41, name: 'Food', kind: 'EXPENSE', archived: false },
        { id: 42, name: 'Food', kind: 'INCOME', archived: false },
        { id: 43, name: 'Food', kind: 'EXPENSE', archived: true },
        { id: 44, name: 'Food', kind: 'TRANSFER', archived: false },
      ],
    } })
    renderTab()
    await uploadPreview()

    expect(screen.getByLabelText('sync.homebank.targetAccount')).toHaveValue('31')
    expect(screen.getByLabelText('sync.homebank.targetCategory')).toHaveValue('41')
    fireEvent.change(screen.getByLabelText('sync.homebank.categoryAction'), { target: { value: 'MAP_EXISTING' } })
    const categoryOptions = screen.getByLabelText('sync.homebank.targetCategory').querySelectorAll('option')
    expect(Array.from(categoryOptions).map(option => option.value)).toEqual(['', '41'])
  })

  it('warns on a parent kind mismatch and requires an explicit compatible category mapping', async () => {
    apiPost.mockReset().mockResolvedValueOnce({ data: {
      ...preview,
      categories: [
        { sourceId: 'parent', name: 'Parent', income: false, transactionCount: 0 },
        { sourceId: 'child-income', name: 'Child income', parentSourceId: 'parent', income: true, transactionCount: 1 },
      ],
      existingCategories: [
        { id: 51, name: 'Income target', kind: 'INCOME', archived: false },
        { id: 52, name: 'Expense target', kind: 'EXPENSE', archived: false },
      ],
    } })
    renderTab()
    await uploadPreview()

    expect(screen.getByText('sync.homebank.categoryParentKindMismatch')).toBeInTheDocument()
    const importButton = screen.getByRole('button', { name: 'sync.homebank.import' })
    expect(importButton).toBeDisabled()
    const action = screen.getAllByLabelText('sync.homebank.categoryAction')[1]
    expect(action.querySelector('option[value="UNCATEGORIZED"]')).toBeInTheDocument()

    fireEvent.change(action, { target: { value: 'UNCATEGORIZED' } })
    expect(importButton).toBeEnabled()
    fireEvent.change(action, { target: { value: 'MAP_EXISTING' } })
    expect(importButton).toBeDisabled()
    const target = screen.getByLabelText('sync.homebank.targetCategory')
    expect(Array.from(target.querySelectorAll('option')).map(option => option.value)).toEqual(['', '51'])
    fireEvent.change(target, { target: { value: '51' } })
    expect(importButton).toBeEnabled()

    apiPost.mockResolvedValueOnce({ data: {
      accountsCreated: 1, accountsMapped: 0, accountsSkipped: 0, categoriesCreated: 0,
      transactionsImported: 1, transactionsSkipped: 0,
    } })
    fireEvent.click(importButton)
    fireEvent.click(screen.getByRole('button', { name: 'common.confirm' }))
    await screen.findByText('sync.homebank.transactionsImported')
    expect(apiPost.mock.calls[1][1].categoryMappings).toEqual([
      { sourceId: 'parent', action: 'CREATE_NEW', name: 'Parent' },
      { sourceId: 'child-income', action: 'MAP_EXISTING', targetCategoryId: 51 },
    ])
  })
})
