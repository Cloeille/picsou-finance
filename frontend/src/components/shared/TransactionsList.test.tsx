import '@testing-library/jest-dom'
import { beforeAll, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TransactionsList } from './TransactionsList'
import type { Transaction } from '@/types/api'

// jsdom lacks matchMedia, which useIsMobile (TransactionDetailSheet) probes.
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

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) =>
      ({
        'accounts.buy': 'Achat',
        'accounts.sell': 'Vente',
        'accounts.dividend': 'Dividende',
        'accounts.fee': 'Frais',
      })[key] ?? key,
    i18n: { language: 'fr', resolvedLanguage: 'fr' },
  }),
}))

function transaction(overrides: Partial<Transaction>): Transaction {
  return {
    id: 1,
    date: '2026-03-04',
    description: 'Transaction',
    amount: 10,
    type: null,
    category: null,
    nativeCurrency: 'EUR',
    isManual: false,
    txType: null,
    ticker: null,
    name: null,
    quantity: null,
    pricePerUnit: null,
    fees: null,
    ...overrides,
  }
}

describe('TransactionsList', () => {
  it('keeps the header actions reachable on an empty account, and renders nothing without them', () => {
    const { container, rerender } = render(<TransactionsList transactions={[]} />)
    expect(container).toBeEmptyDOMElement()

    rerender(<TransactionsList transactions={[]} actions={<button>Add</button>} />)
    expect(screen.getByRole('button', { name: 'Add' })).toBeInTheDocument()
    expect(screen.getByText('accounts.transactions')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('common.search')).not.toBeInTheDocument()
  })

  it('keeps single-year date headings compact', () => {
    render(<TransactionsList transactions={[transaction({ description: 'Single year' })]} />)

    expect(screen.getByText('Single year')).toBeInTheDocument()
    expect(screen.queryByText(/2026/)).not.toBeInTheDocument()
  })

  it('includes the year in every heading when the list spans multiple years', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({ description: 'Recent transaction' }),
          transaction({ id: 2, date: '2025-03-04', description: 'Older transaction' }),
        ]}
      />,
    )

    expect(screen.getByText(/2026/)).toBeInTheDocument()
    expect(screen.getByText(/2025/)).toBeInTheDocument()
  })

  it.each([
    ['BUY', 'Achat'],
    ['SELL', 'Vente'],
    ['DIVIDEND', 'Dividende'],
    ['FEE', 'Frais'],
  ] satisfies [NonNullable<Transaction['txType']>, string][])(
    'renders the %s fallback through frontend i18n',
    (txType, label) => {
      render(
        <TransactionsList
          transactions={[
            transaction({
              isManual: true,
              txType,
              ticker: 'AAPL',
              description: 'AAPL',
            }),
          ]}
        />,
      )

      expect(screen.getByText(`${label} AAPL`)).toBeInTheDocument()
    },
  )

  it('keeps a provider description on synced transactions', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({
            txType: 'DIVIDEND',
            ticker: 'AAPL',
            description: 'Provider dividend payment',
          }),
        ]}
      />,
    )

    expect(screen.getByText('Provider dividend payment')).toBeInTheDocument()
    expect(screen.queryByText('Dividende AAPL')).not.toBeInTheDocument()
  })

  it('searches the localized fallback description', () => {
    render(
      <TransactionsList
        transactions={[
          transaction({
            isManual: true,
            txType: 'DIVIDEND',
            ticker: 'AAPL',
            description: 'AAPL',
          }),
        ]}
      />,
    )

    fireEvent.change(screen.getByPlaceholderText('common.search'), {
      target: { value: 'Dividende' },
    })

    expect(screen.getByText('Dividende AAPL')).toBeInTheDocument()
  })
})
