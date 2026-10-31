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
    ticker: null, name: null, quantity: null, pricePerUnit: null, fees: null,
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
