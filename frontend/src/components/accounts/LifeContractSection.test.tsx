import '@testing-library/jest-dom'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { Account, LifeContract } from '@/types/api'
import { LifeContractSection } from './LifeContractSection'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { resolvedLanguage: 'en' } }),
}))
vi.mock('@/components/shared/CurrencyDisplay', () => ({
  CurrencyDisplay: ({ value, showSign }: { value: number; showSign?: boolean }) => (
    <span>{showSign && value >= 0 ? '+' : ''}{value}</span>
  ),
}))

const CONTRACT: LifeContract = {
  paidInEur: 10200,
  netInvestedEur: 10000,
  scheduledPaymentEur: 150,
  scheduledPaymentCadence: 'MONTHLY',
}

/** A synced assurance-vie carrying a full contract block, which each case overrides. */
function account(overrides: Partial<Account> = {}): Account {
  return {
    id: 7,
    name: 'Corum Life',
    type: 'ASSURANCE_VIE',
    provider: 'CORUM',
    currency: 'EUR',
    currentBalance: 11000,
    currentBalanceEur: 11000,
    lastSyncedAt: null,
    isManual: false,
    color: '#000000',
    ticker: null,
    logoUrl: null,
    logoKey: null,
    createdAt: '2026-10-01T00:00:00Z',
    openedAt: '2020-03-12',
    hidden: false,
    contract: CONTRACT,
    ...overrides,
  }
}

describe('LifeContractSection', () => {
  it('dates the tax advantage eight years after the opening', () => {
    render(<LifeContractSection account={account()} />)

    expect(screen.getByText('March 12, 2020')).toBeInTheDocument()
    expect(screen.getByText('accounts.lifeContract.taxAdvantageFrom').nextSibling).toHaveTextContent('March 12, 2028')
  })

  it('lands a 29 February anniversary on the 28th when the year is not a leap year', () => {
    render(<LifeContractSection account={account({ openedAt: '2092-02-29' })} />)

    expect(screen.getByText('accounts.lifeContract.taxAdvantageFrom').nextSibling).toHaveTextContent('February 28, 2100')
  })

  it('shows the payments and the net invested, without repeating the gain', () => {
    render(<LifeContractSection account={account()} />)

    expect(screen.getByText('accounts.lifeContract.paidIn').nextSibling).toHaveTextContent('10200')
    expect(screen.getByText('accounts.lifeContract.netInvested').nextSibling).toHaveTextContent('10000')
    // The page's portfolio performance already shows the gain against the net invested.
    expect(screen.queryByText('accounts.lifeContract.gain')).not.toBeInTheDocument()
  })

  it('shows the scheduled payment with its cadence', () => {
    render(<LifeContractSection account={account()} />)

    expect(screen.getByText('accounts.lifeContract.scheduledPayment').nextSibling)
      .toHaveTextContent('150accounts.lifeContract.perMonth')
  })

  it('hides the rows the insurer does not report', () => {
    render(
      <LifeContractSection
        account={account({
          openedAt: null,
          contract: { paidInEur: null, netInvestedEur: 10000, scheduledPaymentEur: null, scheduledPaymentCadence: null },
        })}
      />,
    )

    expect(screen.queryByText('accounts.lifeContract.scheduledPayment')).not.toBeInTheDocument()
    expect(screen.queryByText('accounts.lifeContract.paidIn')).not.toBeInTheDocument()
    expect(screen.queryByText('accounts.lifeContract.openedAt')).not.toBeInTheDocument()
    expect(screen.queryByText('accounts.lifeContract.taxAdvantageFrom')).not.toBeInTheDocument()
    expect(screen.getByText('accounts.lifeContract.netInvested')).toBeInTheDocument()
  })

  it('renders nothing without a contract', () => {
    const { container } = render(<LifeContractSection account={account({ contract: null })} />)

    expect(container).toBeEmptyDOMElement()
  })
})
