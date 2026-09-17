import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HoldingResponse } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key} ${Object.values(options).join(' ')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

let holdings: HoldingResponse[] | undefined = []
let isLoading = false

vi.mock('@/features/accounts/hooks', () => ({
  useHoldingsWithLivePrices: () => ({ data: holdings, isLoading }),
}))

const { AllocationPicker } = await import('./AllocationPicker')

function holding(ticker: string, name: string): HoldingResponse {
  return {
    ticker,
    name,
    quantity: 1,
    averageBuyIn: null,
    currentPrice: null,
    quoteCurrency: 'EUR',
    currentValueEur: null,
    costBasisEur: null,
    pnlEur: null,
    pnlPercent: null,
    priceUpdatedAt: null,
    priceAsOf: null,
    priceStale: false,
  }
}

beforeEach(() => {
  holdings = [holding('CW8', 'Amundi MSCI World'), holding('ESE', 'BNP S&P 500')]
  isLoading = false
})

describe('AllocationPicker', () => {
  it('offers the account own holdings, never a free-text ticker', () => {
    render(
      <AllocationPicker accountId={2} monthlyAmount={400} allocations={[]} onChange={() => {}} />,
    )

    expect(screen.getByText('CW8')).toBeInTheDocument()
    expect(screen.getByText('Amundi MSCI World')).toBeInTheDocument()
    // Nothing ticked yet, so no amount field is on screen to type a stray value into.
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('adds and removes a line when its checkbox is toggled', () => {
    const onChange = vi.fn()
    const { rerender } = render(
      <AllocationPicker accountId={2} monthlyAmount={400} allocations={[]} onChange={onChange} />,
    )

    fireEvent.click(screen.getAllByRole('checkbox')[0])
    expect(onChange).toHaveBeenCalledWith([{ ticker: 'CW8', amount: '' }])

    rerender(
      <AllocationPicker
        accountId={2}
        monthlyAmount={400}
        allocations={[{ ticker: 'CW8', amount: '150' }]}
        onChange={onChange}
      />,
    )
    fireEvent.click(screen.getAllByRole('checkbox')[0])
    expect(onChange).toHaveBeenLastCalledWith([])
  })

  // A partial split is the normal case, not an error state.
  it('reports what is left when the split covers only part of the amount', () => {
    render(
      <AllocationPicker
        accountId={2}
        monthlyAmount={400}
        allocations={[{ ticker: 'CW8', amount: '150' }]}
        onChange={() => {}}
      />,
    )

    expect(screen.getByText(/goals\.allocation\.remaining/)).toBeInTheDocument()
    expect(screen.queryByText(/goals\.allocation\.over/)).not.toBeInTheDocument()
  })

  it('flags an over-allocation before the backend has to', () => {
    const { container } = render(
      <AllocationPicker
        accountId={2}
        monthlyAmount={400}
        allocations={[{ ticker: 'CW8', amount: '300' }, { ticker: 'ESE', amount: '250' }]}
        onChange={() => {}}
      />,
    )

    expect(screen.getByText(/goals\.allocation\.over/)).toBeInTheDocument()
    expect(container.querySelector('.text-destructive')).not.toBeNull()
  })

  it('asks for an account before offering anything to split', () => {
    render(
      <AllocationPicker accountId={null} monthlyAmount={400} allocations={[]} onChange={() => {}} />,
    )

    expect(screen.getByText('goals.allocation.pickAccountFirst')).toBeInTheDocument()
  })

  it('says so when the account holds nothing to split across', () => {
    holdings = []
    render(
      <AllocationPicker accountId={2} monthlyAmount={400} allocations={[]} onChange={() => {}} />,
    )

    expect(screen.getByText('goals.allocation.noHoldings')).toBeInTheDocument()
  })
})
