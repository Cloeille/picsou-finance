import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { chooseSelectOption, getSelectOptions } from '@/test/radix-select'
import { AddTransactionModal } from './AddTransactionModal'

// jsdom lacks matchMedia, which DateInput probes for the touch/native date picker.
beforeAll(() => {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: false, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    }),
  })
})

vi.mock('@tanstack/react-query', () => ({
  useQuery: () => ({ data: undefined }),
}))

type CatalogueQuery = { data: unknown; isError?: boolean; refetch?: () => unknown }
const usePreciousMetals = vi.fn<(enabled: boolean) => CatalogueQuery>(() => ({ data: undefined }))
vi.mock('@/features/accounts/hooks', () => ({
  usePreciousMetals: (enabled: boolean) => usePreciousMetals(enabled),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => (typeof opts === 'string' ? opts : key),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

function fillInvestment({ qty, price, fees }: { qty: string; price: string; fees: string }) {
  fireEvent.change(screen.getByPlaceholderText('accounts.tickerOrIsinPlaceholder'), { target: { value: 'AAPL' } })
  // NumericInput renders type="text" inputMode="decimal": quantity, unit price, fees in order.
  const numeric = document.querySelectorAll('input[inputmode="decimal"]')
  fireEvent.change(numeric[0], { target: { value: qty } })
  fireEvent.change(numeric[1], { target: { value: price } })
  fireEvent.change(numeric[2], { target: { value: fees } })
}

describe('AddTransactionModal fees', () => {
  it('BUY amount includes fees: -(qty*price + fees)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="PEA" onSubmit={onSubmit} />)

    fillInvestment({ qty: '10', price: '100', fees: '5' })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'BUY', ticker: 'AAPL', description: 'AAPL',
      quantity: 10, pricePerUnit: 100, fees: 5, amount: -1005,
    }))
  })

  it('SELL amount nets fees: +(qty*price - fees)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="PEA" onSubmit={onSubmit} />)

    fireEvent.click(screen.getByRole('button', { name: 'accounts.sell' }))
    fillInvestment({ qty: '10', price: '100', fees: '5' })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'SELL', ticker: 'AAPL', description: 'AAPL', amount: 995, fees: 5,
    }))
  })
})

describe('AddTransactionModal precious metals', () => {
  beforeEach(() => usePreciousMetals.mockClear())

  const catalogue = [
    { ticker: 'GOLD:GRAM', name: 'Gold (fine gram)', unit: 'GRAM' },
    { ticker: 'GOLD:NAPOLEON-20F', name: 'Napoléon 20 F', unit: 'COIN' },
  ]

  it('offers the catalogue instead of a free ticker, and submits the picked coin', async () => {
    usePreciousMetals.mockReturnValue({ data: catalogue })
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={onSubmit} />)

    expect(usePreciousMetals).toHaveBeenCalledWith(true)
    expect(screen.queryByPlaceholderText('accounts.tickerOrIsinPlaceholder')).not.toBeInTheDocument()
    expect(screen.getByText('holdings.quantity')).toBeInTheDocument()

    chooseSelectOption(screen.getByLabelText('accounts.preciousMetalProduct'), 'GOLD:NAPOLEON-20F')
    expect(screen.getByText('accounts.quantityCoins')).toBeInTheDocument()

    const numeric = document.querySelectorAll('input[inputmode="decimal"]')
    fireEvent.change(numeric[0], { target: { value: '3' } })
    fireEvent.change(numeric[1], { target: { value: '600' } })
    fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'BUY', ticker: 'GOLD:NAPOLEON-20F', name: 'Napoléon 20 F',
      description: 'Napoléon 20 F', quantity: 3, pricePerUnit: 600, amount: -1800,
    }))
  })

  it('labels the quantity in grams for the fine-gold product', () => {
    usePreciousMetals.mockReturnValue({ data: catalogue })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={vi.fn()} />)

    chooseSelectOption(screen.getByLabelText('accounts.preciousMetalProduct'), 'GOLD:GRAM')
    expect(screen.getByText('accounts.quantityGrams')).toBeInTheDocument()
  })

  it('shows a stored product under the catalogue spelling, whatever its case or padding', () => {
    usePreciousMetals.mockReturnValue({ data: catalogue })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={vi.fn()}
      initialValues={{ id: 1, date: '2026-10-01', description: 'Buy', amount: -710, txType: 'BUY',
        ticker: ' gold:napoleon-20f ', quantity: 1, pricePerUnit: 710 }} />)

    const select = screen.getByLabelText('accounts.preciousMetalProduct')
    expect(select).toHaveAttribute('data-value', 'GOLD:NAPOLEON-20F')
    expect(screen.getByText('accounts.quantityCoins')).toBeInTheDocument()
    expect(getSelectOptions(select)).toHaveLength(3)
  })

  it('keeps a stored product the catalogue no longer lists visible, as an extra option', async () => {
    usePreciousMetals.mockReturnValue({ data: catalogue })
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={onSubmit}
      initialValues={{ id: 1, date: '2026-10-01', description: 'Buy', amount: -710, txType: 'BUY',
        ticker: 'AAPL', quantity: 1, pricePerUnit: 710 }} />)

    const select = screen.getByLabelText('accounts.preciousMetalProduct')
    expect(select).toHaveAttribute('data-value', 'AAPL')
    const options = getSelectOptions(select)
    expect(options.map(o => o.textContent)).toContain('AAPL')
    expect(options).toHaveLength(4)
    fireEvent.keyDown(options[0] ?? select, { key: 'Escape' })

    // The form still submits what it shows: the backend refuses it, and that error is displayed.
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ ticker: 'AAPL' })))
  })

  it('adds no extra option on a blank new transaction', () => {
    usePreciousMetals.mockReturnValue({ data: catalogue })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={vi.fn()} />)

    expect(getSelectOptions(screen.getByLabelText('accounts.preciousMetalProduct'))).toHaveLength(3)
  })

  it('says the catalogue failed to load instead of "Loading", and retries it', () => {
    const refetch = vi.fn()
    usePreciousMetals.mockReturnValue({ data: undefined, isError: true, refetch })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={vi.fn()} />)

    expect(screen.getByText('accounts.preciousMetalCatalogueError')).toBeInTheDocument()
    expect(screen.queryByText('accounts.preciousMetalCatalogueLoading')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'common.retry' }))
    expect(refetch).toHaveBeenCalledOnce()
  })

  it('still says Loading while the catalogue is on its way', () => {
    usePreciousMetals.mockReturnValue({ data: undefined, isError: false })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={9} accountType="PRECIOUS_METALS" onSubmit={vi.fn()} />)

    expect(screen.getByLabelText('accounts.preciousMetalProduct')).toHaveTextContent('accounts.preciousMetalCatalogueLoading')
    expect(screen.queryByText('accounts.preciousMetalCatalogueError')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'common.retry' })).not.toBeInTheDocument()
  })

  it('keeps the free ticker input on a securities account and skips the catalogue fetch', () => {
    usePreciousMetals.mockReturnValue({ data: undefined })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="COMPTE_TITRES" onSubmit={vi.fn()} />)

    expect(usePreciousMetals).toHaveBeenCalledWith(false)
    expect(screen.getByPlaceholderText('accounts.tickerOrIsinPlaceholder')).toBeInTheDocument()
    expect(screen.queryByLabelText('accounts.preciousMetalProduct')).not.toBeInTheDocument()
  })
})

describe('AddTransactionModal account type forms', () => {
  it.each(['PER', 'ASSURANCE_VIE'] as const)(
    'shows the buy/sell form and submits a BUY payload on %s accounts',
    async (accountType) => {
      const onSubmit = vi.fn().mockResolvedValue(undefined)
      render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={5} accountType={accountType} onSubmit={onSubmit} />)

      expect(screen.getByRole('button', { name: 'accounts.buy' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'accounts.sell' })).toBeInTheDocument()
      expect(screen.getByText('accounts.tickerOrIsin')).toBeInTheDocument()
      expect(screen.getByText('holdings.quantity')).toBeInTheDocument()
      expect(screen.getByText('holdings.unitPrice')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '+ accounts.deposit' })).not.toBeInTheDocument()

      fireEvent.change(screen.getByPlaceholderText('accounts.tickerOrIsinPlaceholder'), { target: { value: 'FR0010315770' } })
      const numeric = document.querySelectorAll('input[inputmode="decimal"]')
      fireEvent.change(numeric[0], { target: { value: '4' } })
      fireEvent.change(numeric[1], { target: { value: '125' } })
      fireEvent.click(screen.getByRole('button', { name: 'common.create' }))

      await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
      expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
        txType: 'BUY',
        ticker: 'FR0010315770',
        description: 'FR0010315770',
        quantity: 4,
        pricePerUnit: 125,
        amount: -500,
      }))
    },
  )

  it.each(['EMPLOYEE_SAVINGS', 'CHECKING'] as const)(
    'keeps the cash form on %s accounts',
    (accountType) => {
      render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={5} accountType={accountType} onSubmit={vi.fn()} />)

      expect(screen.getByRole('button', { name: '+ accounts.deposit' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '− accounts.withdrawal' })).toBeInTheDocument()
      expect(screen.getByText('accounts.description')).toBeInTheDocument()
      expect(screen.getByText('accounts.amount')).toBeInTheDocument()
      expect(screen.queryByPlaceholderText('accounts.tickerOrIsinPlaceholder')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'accounts.buy' })).not.toBeInTheDocument()
    },
  )

  it('edits a deposit already recorded on a PER account with the cash form', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined)
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={5} accountType="PER" onSubmit={onSubmit}
      initialValues={{ id: 1, date: '2026-10-01', description: 'Versement', amount: 200, txType: 'DEPOSIT' }} />)

    expect(screen.getByRole('button', { name: '+ accounts.deposit' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'accounts.buy' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))

    await waitFor(() => expect(onSubmit).toHaveBeenCalledOnce())
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({
      txType: 'DEPOSIT',
      description: 'Versement',
      amount: 200,
    }))
  })

  it.each(['DIVIDEND', 'FEE'] as const)(
    'opens the cash form to edit a %s line on a PER account',
    (txType) => {
      render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={5} accountType="PER" onSubmit={vi.fn()}
        initialValues={{ id: 1, date: '2026-10-01', description: 'Ligne', amount: 12, txType }} />)

      expect(screen.getByRole('button', { name: '+ accounts.deposit' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'accounts.buy' })).not.toBeInTheDocument()
    },
  )
})
