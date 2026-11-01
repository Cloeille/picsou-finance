import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Transaction } from '@/types/api'

let language = 'fr'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { resolvedLanguage: language, language },
  }),
}))

vi.mock('@/hooks/use-mobile', () => ({ useIsMobile: () => false }))

const { TransactionDetailSheet } = await import('./TransactionDetailSheet')

const LOCALES = {
  fr: 'fr-FR',
  en: 'en-US',
  de: 'de-DE',
  es: 'es-ES',
} as const

const dateOptions = {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
} as const

function transaction(date: string): Transaction {
  return {
    id: 1,
    date,
    description: 'Synthetic transaction',
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
  }
}

function detail(date: string) {
  return (
    <TransactionDetailSheet
      transaction={transaction(date)}
      open
      onClose={vi.fn()}
    />
  )
}

describe('TransactionDetailSheet date', () => {
  beforeEach(() => {
    language = 'fr'
    // Deliberately disagree with both the app language and jsdom's en-US browser locale.
    document.documentElement.lang = 'it'
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('follows every supported app locale when the mounted tree rerenders', () => {
    const date = '2026-10-02'
    const view = render(detail(date))

    for (const [appLanguage, locale] of Object.entries(LOCALES)) {
      language = appLanguage
      view.rerender(detail(date))

      const expected = new Intl.DateTimeFormat(locale, dateOptions)
        .format(new Date(`${date}T00:00:00`))
      expect(screen.getByText(expected)).toBeInTheDocument()
    }
  })

  it('keeps a date-only transaction on its calendar day west of UTC', () => {
    vi.stubEnv('TZ', 'America/New_York')
    const date = '2026-07-31'

    render(detail(date))

    const expected = new Intl.DateTimeFormat('fr-FR', dateOptions)
      .format(new Date(`${date}T00:00:00`))
    expect(screen.getByText(expected)).toBeInTheDocument()
  })
})
