import '@testing-library/jest-dom'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { HoldingResponse } from '@/types/api'

vi.mock('react-i18next', () => ({
  // CurrencyDisplay reads i18n.resolvedLanguage to pick its number format.
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key} ${Object.values(options).join(' ')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { HoldingsTable } = await import('./HoldingsTable')

function holding(partial: Partial<HoldingResponse> & { ticker: string }): HoldingResponse {
  return {
    name: null,
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
    ...partial,
  }
}

const HOLDINGS: HoldingResponse[] = [
  holding({ ticker: 'ESE', name: 'Emerging', quantity: 4, currentValueEur: 200, pnlEur: -30, pnlPercent: -13 }),
  holding({ ticker: 'CW8', name: 'Élan Monde', quantity: 2, currentValueEur: 300, pnlEur: 50, pnlPercent: 20 }),
  // Never priced: every numeric column is null. It must never lead an ascending sort.
  holding({ ticker: 'ZZZ', name: 'Zeta', quantity: 9 }),
  holding({ ticker: 'AAPL', name: 'Apple', quantity: 1, currentValueEur: 100, pnlEur: 10, pnlPercent: 11 }),
]

/** The tickers in render order — the first cell of every body row. */
function renderedTickers(): string[] {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map(row => row.querySelector('td')!.textContent!.trim())
}

/** Exact match, not a regex: `holdings.pnl` is a prefix of `holdings.pnlPercent`. */
function header(key: string) {
  return screen.getByRole('button', { name: key })
}

describe('HoldingsTable', () => {
  it('opens on the largest position, without being asked', () => {
    render(<HoldingsTable holdings={HOLDINGS} />)
    expect(renderedTickers()).toEqual(['CW8', 'ESE', 'AAPL', 'ZZZ'])
  })

  it('flips a column between descending and ascending', () => {
    render(<HoldingsTable holdings={HOLDINGS} />)

    fireEvent.click(header('portfolio.value'))
    expect(renderedTickers()).toEqual(['AAPL', 'ESE', 'CW8', 'ZZZ'])

    fireEvent.click(header('portfolio.value'))
    expect(renderedTickers()).toEqual(['CW8', 'ESE', 'AAPL', 'ZZZ'])
  })

  it('sorts the ticker column alphabetically and the P&L column numerically', () => {
    render(<HoldingsTable holdings={HOLDINGS} />)

    fireEvent.click(header('holdings.ticker'))
    expect(renderedTickers()).toEqual(['AAPL', 'CW8', 'ESE', 'ZZZ'])

    fireEvent.click(header('holdings.pnlPercent'))
    expect(renderedTickers()).toEqual(['CW8', 'AAPL', 'ESE', 'ZZZ'])
  })

  // The unpriced line is the reason the comparator treats null as "unknown" rather than as zero:
  // sorted ascending on a loss column it would otherwise sit above a real loss.
  it('leaves an unpriced line at the bottom whichever way a column points', () => {
    render(<HoldingsTable holdings={HOLDINGS} />)

    fireEvent.click(header('holdings.pnl'))
    expect(renderedTickers().at(-1)).toBe('ZZZ')

    fireEvent.click(header('holdings.pnl'))
    expect(renderedTickers().at(-1)).toBe('ZZZ')
  })

  it('reports the active column and direction to assistive technology', () => {
    render(<HoldingsTable holdings={HOLDINGS} />)

    const valueHead = screen.getByRole('columnheader', { name: 'portfolio.value' })
    expect(valueHead).toHaveAttribute('aria-sort', 'descending')
    expect(screen.getByRole('columnheader', { name: 'holdings.ticker' })).toHaveAttribute('aria-sort', 'none')

    fireEvent.click(header('portfolio.value'))
    expect(valueHead).toHaveAttribute('aria-sort', 'ascending')
  })
})
