import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
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

const usePreciousMetals = vi.fn<(enabled: boolean) => { data: unknown }>(() => ({ data: undefined }))
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

    fireEvent.change(screen.getByLabelText('accounts.preciousMetalProduct'), { target: { value: 'GOLD:NAPOLEON-20F' } })
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

    fireEvent.change(screen.getByLabelText('accounts.preciousMetalProduct'), { target: { value: 'GOLD:GRAM' } })
    expect(screen.getByText('accounts.quantityGrams')).toBeInTheDocument()
  })

  it('keeps the free ticker input on a securities account and skips the catalogue fetch', () => {
    usePreciousMetals.mockReturnValue({ data: undefined })
    render(<AddTransactionModal open onOpenChange={vi.fn()} accountId={2} accountType="COMPTE_TITRES" onSubmit={vi.fn()} />)

    expect(usePreciousMetals).toHaveBeenCalledWith(false)
    expect(screen.getByPlaceholderText('accounts.tickerOrIsinPlaceholder')).toBeInTheDocument()
    expect(screen.queryByLabelText('accounts.preciousMetalProduct')).not.toBeInTheDocument()
  })
})
