import '@testing-library/jest-dom'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ExchangePositionResponse } from '@/types/api'

vi.mock('react-i18next', () => ({
  // CurrencyDisplay reads i18n.resolvedLanguage to pick its number format.
  useTranslation: () => ({
    // Interpolation values are appended rather than dropped: the freshness marker's whole
    // content is the date passed to it, and a mock returning the bare key would assert that a
    // label exists while saying nothing about what it tells the user.
    t: (key: string, options?: Record<string, unknown>) =>
      options ? `${key} ${Object.values(options).join(' ')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

const { PositionsByProduct } = await import('./PositionsByProduct')

const POSITIONS: ExchangePositionResponse[] = [
  { product: 'SPOT', ticker: 'BTC', quantity: 0.5, principal: null, interest: null, averageBuyIn: 80, currentPriceEur: 100, currentValueEur: 50, costBasisEur: 40, pnlEur: 10, pnlPercent: 25, priceAsOf: '2026-08-01', priceStale: false },
  { product: 'STAKING', ticker: 'ATOM', quantity: 33.154, principal: 19.73, interest: 13.424, averageBuyIn: 6, currentPriceEur: 5, currentValueEur: 165.77, costBasisEur: 198.92, pnlEur: -33.15, pnlPercent: -16.7, priceAsOf: '2026-08-01', priceStale: false },
  // Same asset, two products — the split this component exists to show.
  { product: 'STAKING', ticker: 'BTC', quantity: 0.25, principal: 0.2, interest: 0.05, averageBuyIn: 80, currentPriceEur: 100, currentValueEur: 25, costBasisEur: 20, pnlEur: 5, pnlPercent: 25, priceAsOf: '2026-08-01', priceStale: false },
]

/** The figure rendered next to a product's heading, which is the group's subtotal. */
function subtotalFor(product: string): string {
  const heading = screen.getByText(`positions.products.${product}`)
  const header = heading.parentElement
  if (!header) throw new Error(`no header for ${product}`)
  return header.textContent!.replace(`positions.products.${product}`, '').trim()
}

function sectionFor(product: string) {
  const heading = screen.getByText(`positions.products.${product}`)
  const section = heading.closest('div')?.parentElement
  if (!section) throw new Error(`no section for ${product}`)
  return within(section)
}

describe('PositionsByProduct', () => {
  it('groups positions per product and keeps the same asset in each', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    expect(sectionFor('SPOT').getAllByText('BTC')).toHaveLength(1)
    expect(sectionFor('STAKING').getAllByText('BTC')).toHaveLength(1)
    expect(sectionFor('STAKING').getByText('ATOM')).toBeInTheDocument()
    expect(screen.queryByText('positions.products.LENDING')).not.toBeInTheDocument()
  })

  it('shows principal and interest only where the exchange reports yield', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    // Spot has no yield decomposition: no extra columns at all.
    expect(sectionFor('SPOT').queryByText('positions.interest')).not.toBeInTheDocument()

    const staking = sectionFor('STAKING')
    expect(staking.getByText('positions.principal')).toBeInTheDocument()
    expect(staking.getByText('positions.interest')).toBeInTheDocument()
    // The three numbers the user asked for: principal, interest, and the total actually held.
    expect(staking.getByText('19.73')).toBeInTheDocument()
    expect(staking.getByText('13.424')).toBeInTheDocument()
    expect(staking.getByText('33.154')).toBeInTheDocument()
  })

  it('keeps the per-line profit and loss the flat table showed', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    const staking = sectionFor('STAKING')
    expect(staking.getByText('-16.7%')).toBeInTheDocument()
    expect(sectionFor('SPOT').getByText('+25.0%')).toBeInTheDocument()
  })

  it('marks a price that is a recorded one rather than a live quote', () => {
    // The point of the marker: the figure is still shown. Blanking these lines is what made an
    // untouched account read as a large loss the morning the price API rate-limited us.
    render(<PositionsByProduct positions={[{ ...POSITIONS[0], priceStale: true, priceAsOf: '2026-07-31' }]} />)

    expect(screen.getByLabelText(/accounts\.priceAsOf/)).toBeInTheDocument()
    expect(sectionFor('SPOT').getByText('+25.0%')).toBeInTheDocument()
  })

  it('names the recorded price’s own day, in a time zone behind UTC', () => {
    // `priceAsOf` is a LocalDate. Parsed as an instant it lands on UTC midnight, so anywhere west
    // of UTC the marker announced the day before the price was actually recorded — a figure the
    // user is being asked to judge as stale, dated wrong.
    vi.stubEnv('TZ', 'America/New_York')
    try {
      render(<PositionsByProduct positions={[{ ...POSITIONS[0], priceStale: true, priceAsOf: '2026-07-31' }]} />)

      // Matched on the day alone, whichever component the active date format puts first.
      const label = screen.getByLabelText(/accounts\.priceAsOf/).getAttribute('aria-label') ?? ''
      expect(label).toMatch(/(^|\D)31(\D|$)/)
      expect(label).not.toMatch(/(^|\D)30(\D|$)/)
    } finally {
      vi.unstubAllEnvs()
    }
  })

  it('subtotals a product whose lines are all valued', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    expect(subtotalFor('SPOT')).toMatch(/50,00/)
  })

  it('shows no subtotal for a product whose assets could not be valued', () => {
    // EUR 0 would read as "worth nothing" for what is really "we could not price it", in the
    // same typography as a complete total.
    render(<PositionsByProduct positions={[
      { ...POSITIONS[0], currentPriceEur: null, currentValueEur: null, pnlEur: null, pnlPercent: null },
    ]} />)

    expect(subtotalFor('SPOT')).toBe('—')
  })

  it('shows no subtotal for a product only partly valued', () => {
    // The dangerous case: a total that looks complete but silently omits the line that failed.
    // 165,77 alone would be published as the staking total, 25 EUR short of the truth.
    render(<PositionsByProduct positions={[
      POSITIONS[1],
      { ...POSITIONS[2], currentPriceEur: null, currentValueEur: null, pnlEur: null, pnlPercent: null },
    ]} />)

    expect(subtotalFor('STAKING')).toBe('—')
  })

  it('leaves a live price unmarked', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    expect(screen.queryByLabelText('accounts.priceAsOf')).not.toBeInTheDocument()
  })

  it('renders nothing when there is no breakdown', () => {
    const { container } = render(<PositionsByProduct positions={[]} />)

    expect(container).toBeEmptyDOMElement()
  })

  // One sort criterion for the three sections, applied inside each: the SPOT/STAKING/LENDING
  // sequence is editorial and must survive any column the reader picks.
  it('sorts every product section on one criterion without reordering the sections', () => {
    render(<PositionsByProduct positions={POSITIONS} />)

    const staking = sectionFor('STAKING')
    // Value descending by default: ATOM (165,77) before BTC (25).
    expect(staking.getAllByRole('row').slice(1).map(r => r.querySelector('td')!.textContent))
      .toEqual(['ATOM', 'BTC'])

    fireEvent.click(within(staking.getAllByRole('table')[0]).getByRole('button', { name: 'portfolio.value' }))

    expect(sectionFor('STAKING').getAllByRole('row').slice(1).map(r => r.querySelector('td')!.textContent))
      .toEqual(['BTC', 'ATOM'])
    // The headings kept their order, and SPOT is still the first section on screen.
    const headings = screen.getAllByRole('heading', { level: 3 }).map(h => h.textContent)
    expect(headings).toEqual(['positions.products.SPOT', 'positions.products.STAKING'])
  })
})
