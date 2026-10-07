import '@testing-library/jest-dom'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { StubImage } from '@/test/stubImage'
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
    logoUrl: null,
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

const BTC_LOGO = 'https://coin-images.coingecko.com/coins/images/1/small/bitcoin.png'

const BTC: HoldingResponse = holding({
  ticker: 'BTC', name: 'Bitcoin', logoUrl: BTC_LOGO, quantity: 0.5,
  averageBuyIn: 80, currentPrice: 100, currentValueEur: 50, costBasisEur: 40, pnlEur: 10, pnlPercent: 25,
})

// An equity whose mark has not been stored (yet, or ever): exactly the pre-logo rendering.
const AAPL: HoldingResponse = { ...BTC, ticker: 'AAPL', name: 'Apple', logoUrl: null }

const MC_LOGO = '/api/instrument-logos/MC.PA?v=1759400000'

// An equity whose mark the backend stored: the URL is Picsou's own endpoint, never Yahoo's.
const MC: HoldingResponse = { ...BTC, ticker: 'MC.PA', name: 'LVMH', logoUrl: MC_LOGO }

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

/** The body row showing `ticker`, scoped for queries. */
function rowFor(ticker: string) {
  const row = screen.getByText(ticker).closest('tr')
  if (!row) throw new Error(`no row for ${ticker}`)
  return within(row)
}

describe('HoldingsTable sorting', () => {
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

describe('HoldingsTable logos', () => {
  beforeEach(() => {
    vi.stubGlobal('Image', StubImage)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders nothing for an account with no holdings', () => {
    const { container } = render(<HoldingsTable holdings={[]} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('shows the mark beside the ticker when one resolved, and the ticker exactly once', async () => {
    render(<HoldingsTable holdings={[BTC]} />)

    // The count matters as much as the presence: a text fallback here would print "BTC" twice,
    // and this table is the one place a duplicate is visible on a user's own portfolio.
    expect(screen.getAllByText('BTC')).toHaveLength(1)
    // Radix mounts the image only once its probe reports loaded, so the row is asserted on
    // after a tick rather than on first paint.
    await waitFor(() => {
      expect(rowFor('BTC').getByRole('img')).toHaveAttribute('src', BTC_LOGO)
    })
  })

  it('shows a stock row with its stored mark beside the ticker', async () => {
    render(<HoldingsTable holdings={[MC]} />)

    expect(screen.getAllByText('MC.PA')).toHaveLength(1)
    await waitFor(() => {
      expect(rowFor('MC.PA').getByRole('img')).toHaveAttribute('src', MC_LOGO)
    })
    expect(rowFor('MC.PA').getByRole('img')).toHaveAttribute('alt', 'MC.PA')
  })

  it('renders an equity row on its ticker alone, with the empty disc in place of a mark', () => {
    // A share with no stored mark decides what an unresolvable mark looks like, and it must be
    // exactly the rendering from before share logos existed. The disc stays, so the column does
    // not jump when a line with a mark sits next to it.
    render(<HoldingsTable holdings={[AAPL]} />)

    expect(rowFor('AAPL').queryByRole('img')).not.toBeInTheDocument()
    expect(rowFor('AAPL').getByText('AAPL')).toBeInTheDocument()
  })

  it('mixes both without the columns shifting', async () => {
    render(<HoldingsTable holdings={[BTC, AAPL]} />)

    await waitFor(() => {
      expect(rowFor('BTC').getByRole('img')).toBeInTheDocument()
    })
    expect(rowFor('AAPL').queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('accounts.holdings')).toBeInTheDocument()
  })
})
