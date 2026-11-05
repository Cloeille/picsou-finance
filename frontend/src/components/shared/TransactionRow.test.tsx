import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { Transaction } from '@/types/api'
import { TransactionRow } from './TransactionRow'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: 'en', language: 'en' },
  }),
}))

function tx(amount: number): Transaction {
  return {
    id: 1, date: '2026-10-01', description: 'SALARY', amount, type: null,
    category: null, nativeCurrency: 'EUR', isManual: false,
    ticker: null, name: null, quantity: null, pricePerUnit: null, fees: null, budgetNeutral: false,
    merchantLabel: 'Acme',
  }
}

const amountText = (amount: number) => {
  render(<TransactionRow transaction={tx(amount)} />)
  return screen.getByText(/\d[.,]\d\d/).textContent
}

describe('TransactionRow amount sign', () => {
  it('signs income with a plus', () => {
    expect(amountText(1250)).toMatch(/^\+/)
  })

  it('signs a debit with a minus', () => {
    expect(amountText(-42)).toMatch(/^-/)
  })

  it('leaves a zero amount unsigned', () => {
    expect(amountText(0)).toMatch(/^[^+-]/)
  })
})

describe('TransactionRow CORUM Life operations', () => {
  const corum = (overrides: Partial<Transaction>): Transaction => ({
    ...tx(100), description: 'RAW CORUM', merchantLabel: null, budgetNeutral: true, ...overrides,
  })

  it('labels a known operation type and shows the neutral badge', () => {
    render(<TransactionRow transaction={corum({ type: 'scheduledPayment' })} />)
    expect(screen.getByText('accounts.corumLifeOperation.scheduledPayment')).toBeInTheDocument()
    const badge = screen.getByText('accounts.inContract')
    expect(badge).toHaveAttribute('title', 'accounts.inContractHint')
  })

  it('appends the fund label to a coupon', () => {
    render(<TransactionRow transaction={corum({ type: 'couponDetachment', ticker: 'CORUM-LIFE-XL', name: 'Corum XL' })} />)
    expect(screen.getByText('accounts.corumLifeOperation.couponDetachment Corum XL')).toBeInTheDocument()
  })

  it('labels a payment, which counts in the budget, without the neutral badge', () => {
    render(<TransactionRow transaction={corum({ type: 'subscription', budgetNeutral: false })} accountProvider="CORUM" />)
    expect(screen.getByText('accounts.corumLifeOperation.subscription')).toBeInTheDocument()
    expect(screen.queryByText('accounts.inContract')).not.toBeInTheDocument()
  })

  it('keeps the description of a payment-typed row outside a CORUM account', () => {
    for (const accountProvider of [undefined, null, 'Boursorama']) {
      const { unmount } = render(
        <TransactionRow transaction={corum({ type: 'subscription', budgetNeutral: false })} accountProvider={accountProvider} />,
      )
      expect(screen.getByText('RAW CORUM')).toBeInTheDocument()
      expect(screen.queryByText('accounts.corumLifeOperation.subscription')).not.toBeInTheDocument()
      unmount()
    }
  })

  it('keeps the description of a manual payment-typed row on a CORUM account', () => {
    render(
      <TransactionRow
        transaction={corum({ type: 'scheduledPayment', budgetNeutral: false, isManual: true })}
        accountProvider="CORUM"
      />,
    )
    expect(screen.getByText('RAW CORUM')).toBeInTheDocument()
  })

  it('falls back to the generic label for an unknown type', () => {
    render(<TransactionRow transaction={corum({ type: 'somethingNew' })} />)
    expect(screen.getByText('accounts.corumLifeOperation.unknown')).toBeInTheDocument()
  })

  it('shows no badge and keeps the bank label for an ordinary row', () => {
    render(<TransactionRow transaction={tx(10)} />)
    expect(screen.getByText('Acme')).toBeInTheDocument()
    expect(screen.queryByText('accounts.inContract')).not.toBeInTheDocument()
  })
})
