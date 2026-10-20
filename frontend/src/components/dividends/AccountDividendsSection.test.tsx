import '@testing-library/jest-dom'
import { fireEvent, render, screen, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HoldingResponse, UpcomingDividend } from '@/types/api'
import fr from '@/i18n/locales/fr.json'

const useUpcomingDividends = vi.hoisted(() => vi.fn())
const holdingLogo = vi.hoisted(() => vi.fn())

vi.mock('@/features/dividends/hooks', () => ({ useUpcomingDividends }))
vi.mock('@/components/shared/HoldingLogo', () => ({
  HoldingLogo: (props: { logoUrl?: string | null; ticker: string }) => {
    holdingLogo(props)
    return <span data-testid="holding-logo">{props.ticker}</span>
  },
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value }: { value: number }) => <span>{value}</span>,
}))
vi.mock('@/lib/utils', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/lib/utils')>(),
  formatDate: (value: string) => value,
}))

const { AccountDividendsSection } = await import('./AccountDividendsSection')

function row(overrides: Partial<UpcomingDividend> & Pick<UpcomingDividend, 'name'>): UpcomingDividend {
  return {
    id: overrides.id ?? overrides.name,
    source: 'HOLDING',
    accountId: 3,
    accountName: 'Compte Titres Degiro',
    ticker: 'TTE.PA',
    expectedDate: '2026-11-08',
    exDate: '2026-11-08',
    paymentDate: '2026-11-13',
    paymentDateEstimated: true,
    cadence: 'QUARTERLY',
    basis: 'HISTORY_CADENCE',
    quantity: 40,
    amountPerShare: 0.85,
    amountPerShareEur: 0.85,
    currency: 'EUR',
    amountEur: 34,
    reinvested: false,
    accountType: 'COMPTE_TITRES',
    countsInBudget: true,
    ...overrides,
  }
}

function loaded(data: UpcomingDividend[]) {
  useUpcomingDividends.mockReturnValue({ data, isLoading: false, isError: false })
}

describe('AccountDividendsSection', () => {
  beforeEach(() => {
    useUpcomingDividends.mockReset()
    holdingLogo.mockReset()
  })

  it('uses the French wording from the contract', () => {
    expect(fr.accounts.dividends.title).toBe('Dividendes à venir')
    expect(fr.accounts.dividends.exDate).toBe('Détachement')
    expect(fr.accounts.dividends.paymentDate).toBe('Versement')
    expect(fr.accounts.dividends.paymentUnknown).toBe('Date de versement inconnue')
    expect(fr.accounts.dividends.estimated).toBe('estimée')
    expect(fr.accounts.dividends.announced).toBe('Annoncé')
    expect(fr.accounts.dividends.estimatedBasis).toBe('Estimé')
    expect(fr.accounts.dividends.empty).toBe('Aucun dividende prévu')
    expect(fr.accounts.dividends.refreshWeekly).toContain('semaine')
    expect(fr.budget.overview.staysInEnvelope).toBe("Reste dans l'enveloppe")
  })

  it('renders each payment as a card inside the labelled carousel', () => {
    loaded([
      row({ name: 'TotalEnergies', ticker: 'TTE.PA', exDate: '2026-11-08', paymentDate: '2026-11-13' }),
      row({
        id: 'sanofi', name: 'Sanofi', ticker: 'SAN.PA', amountEur: 6, amountPerShareEur: 1.2,
        exDate: '2026-12-01', paymentDate: '2026-12-08', paymentDateEstimated: false,
      }),
    ])

    render(<AccountDividendsSection accountId={3} />)

    expect(useUpcomingDividends).toHaveBeenCalledWith({ horizonDays: 366, accountId: 3 })
    const carousel = screen.getByRole('region', { name: 'accounts.dividends.carousel' })
    expect(carousel).toHaveAttribute('tabindex', '0')
    expect(carousel.className).toContain('overflow-x-auto')
    expect(carousel.className).toContain('snap-x')
    expect(carousel.className).toContain('snap-mandatory')

    const cards = within(carousel).getAllByRole('article')
    expect(cards).toHaveLength(2)
    expect(cards[0].className).toContain('snap-start')
    expect(cards[0].className).toContain('w-64')
    expect(within(cards[0]).getByText('TotalEnergies')).toBeInTheDocument()
    expect(within(cards[0]).getAllByText('TTE.PA').length).toBeGreaterThan(0)
    expect(within(cards[0]).getByText('2026-11-08')).toBeInTheDocument()
    expect(within(cards[0]).getByText('2026-11-13')).toBeInTheDocument()
    expect(within(cards[0]).getByText('accounts.dividends.exDate')).toBeInTheDocument()
    expect(within(cards[0]).getByText('accounts.dividends.paymentDate')).toBeInTheDocument()
    expect(within(cards[0]).getByText('accounts.dividends.estimated')).toBeInTheDocument()
    expect(within(cards[0]).getByText('34')).toBeInTheDocument()
    expect(within(cards[0]).getByText('0.85')).toBeInTheDocument()
    expect(within(cards[0]).queryByText('budget.overview.staysInEnvelope')).not.toBeInTheDocument()
    expect(within(cards[0]).getByText('accounts.dividends.estimatedBasis')).toBeInTheDocument()
    expect(within(cards[0]).queryByText('accounts.dividends.announced')).not.toBeInTheDocument()

    expect(screen.getByText('40')).toBeInTheDocument()
    expect(screen.getByText('accounts.dividends.yearTotal')).toBeInTheDocument()
  })

  it('labels an unknown payment date and badges a line that stays in the envelope', () => {
    loaded([
      row({
        name: 'Sanofi', ticker: 'SAN.PA', paymentDate: null, paymentDateEstimated: false,
        accountType: 'PEA', countsInBudget: false, amountEur: 18,
      }),
    ])

    render(<AccountDividendsSection accountId={2} />)

    const card = screen.getByRole('article')
    expect(within(card).getByText('accounts.dividends.paymentUnknown')).toBeInTheDocument()
    expect(within(card).queryByText('accounts.dividends.estimated')).not.toBeInTheDocument()
    expect(within(card).getByText('budget.overview.staysInEnvelope')).toBeInTheDocument()
    expect(within(card).getByText('accounts.dividends.estimatedBasis')).toBeInTheDocument()
    expect(within(card).getByText('18')).toBeInTheDocument()
  })

  it('badges an announced US line and does not mark its payment date as estimated', () => {
    loaded([
      row({
        name: 'Microsoft', ticker: 'MSFT', basis: 'ANNOUNCED',
        paymentDate: '2026-12-10', paymentDateEstimated: false, currency: 'USD',
      }),
    ])

    render(<AccountDividendsSection accountId={3} />)

    const card = screen.getByRole('article')
    expect(within(card).getByText('accounts.dividends.announced')).toBeInTheDocument()
    expect(within(card).queryByText('accounts.dividends.estimatedBasis')).not.toBeInTheDocument()
    expect(within(card).queryByText('accounts.dividends.estimated')).not.toBeInTheDocument()
    expect(within(card).getByText('2026-12-10')).toBeInTheDocument()
  })

  it('reuses the holding logo when the account already has one for the ticker', () => {
    loaded([row({ name: 'TotalEnergies', ticker: 'TTE.PA' })])
    const holdings = [{
      ticker: 'tte.pa', name: 'TotalEnergies', logoUrl: 'https://logos.example/tte.png', quantity: 40,
    }] as HoldingResponse[]

    render(<AccountDividendsSection accountId={3} holdings={holdings} />)

    expect(holdingLogo).toHaveBeenCalledWith(expect.objectContaining({
      logoUrl: 'https://logos.example/tte.png',
      ticker: 'TTE.PA',
    }))
    expect(screen.getByTestId('holding-logo')).toHaveTextContent('TTE.PA')
  })

  it('scrolls the carousel by one card from the buttons and the arrow keys', () => {
    loaded([row({ name: 'TotalEnergies' }), row({ id: 'b', name: 'Sanofi', ticker: 'SAN.PA' })])
    Object.defineProperty(HTMLElement.prototype, 'scrollBy', {
      configurable: true,
      writable: true,
      value: () => {},
    })
    const scrollBy = vi.spyOn(HTMLElement.prototype, 'scrollBy').mockImplementation(() => {})

    render(<AccountDividendsSection accountId={3} />)
    fireEvent.click(screen.getByRole('button', { name: 'accounts.dividends.next' }))
    fireEvent.click(screen.getByRole('button', { name: 'accounts.dividends.prev' }))
    const carousel = screen.getByRole('region', { name: 'accounts.dividends.carousel' })
    fireEvent.keyDown(carousel, { key: 'ArrowRight' })

    expect(scrollBy).toHaveBeenNthCalledWith(1, { left: 268, behavior: 'smooth' })
    expect(scrollBy).toHaveBeenNthCalledWith(2, { left: -268, behavior: 'smooth' })
    expect(scrollBy).toHaveBeenNthCalledWith(3, { left: 268, behavior: 'smooth' })
    expect(screen.getByRole('button', { name: 'accounts.dividends.next' }).parentElement?.className)
      .toContain('md:flex')
    scrollBy.mockRestore()
  })

  it('says nothing is expected, and that histories refresh weekly', () => {
    loaded([])
    render(<AccountDividendsSection accountId={3} />)

    expect(screen.getByText('accounts.dividends.empty')).toBeInTheDocument()
    expect(screen.getByText('accounts.dividends.refreshWeekly')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'accounts.dividends.carousel' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'accounts.dividends.next' })).not.toBeInTheDocument()
  })

  it('keeps a failed forecast as a quiet line and does not replace the page', () => {
    useUpcomingDividends.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    render(<AccountDividendsSection accountId={3} />)

    expect(screen.getByText('accounts.dividends.title')).toBeInTheDocument()
    expect(screen.getByText('accounts.dividends.error')).toBeInTheDocument()
    expect(screen.queryByText('accounts.dividends.empty')).not.toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })
})
