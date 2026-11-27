import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RealizedPnlSection } from './RealizedPnlSection'
import { NEGATIVE_AMOUNT_CLASS, POSITIVE_AMOUNT_CLASS } from '@/lib/money'
import type { RealizedPnlResponse } from '@/types/api'

let realizedData: RealizedPnlResponse | undefined

vi.mock('@/features/accounts/hooks', () => ({
  useRealizedPnl: () => ({ data: realizedData }),
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => (typeof opts === 'string' ? opts : key),
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

function withData(partial: Partial<RealizedPnlResponse>): RealizedPnlResponse {
  return { currency: 'EUR', realizedTotal: 0, byTicker: [], lots: [], ...partial }
}

describe('RealizedPnlSection', () => {
  it('renders nothing when there are no closed lots', () => {
    realizedData = withData({ lots: [] })
    const { container } = render(<RealizedPnlSection accountId={2} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when data is missing (demo empty-object fallback)', () => {
    realizedData = undefined
    const { container } = render(<RealizedPnlSection accountId={2} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows a green total and the closed lot when realized gains are positive', () => {
    realizedData = withData({
      realizedTotal: 512,
      lots: [{ ticker: 'AAPL', name: 'Apple', date: '2024-05-14', quantity: 8, avgCost: 125, proceeds: 1512, realized: 512 }],
    })
    const { container } = render(<RealizedPnlSection accountId={2} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    const totalWrap = container.querySelector(`.${POSITIVE_AMOUNT_CLASS.split(' ')[0]}`)
    expect(totalWrap).toBeTruthy()
    expect(container.querySelector(`.${NEGATIVE_AMOUNT_CLASS}`)).toBeFalsy()
  })

  it('shows a red total when realized P&L is negative', () => {
    realizedData = withData({
      realizedTotal: -90,
      lots: [{ ticker: 'TSLA', name: 'Tesla', date: '2024-09-02', quantity: 3, avgCost: 250, proceeds: 660, realized: -90 }],
    })
    const { container } = render(<RealizedPnlSection accountId={2} />)
    expect(container.querySelector(`.${NEGATIVE_AMOUNT_CLASS}`)).toBeTruthy()
  })

  it('keeps a zero realized P&L neutral', () => {
    realizedData = withData({
      realizedTotal: 0,
      lots: [{ ticker: 'CW8', name: 'MSCI World', date: '2024-09-02', quantity: 1, avgCost: 500, proceeds: 500, realized: 0 }],
    })
    const { container } = render(<RealizedPnlSection accountId={2} />)
    expect(container.querySelector(`.${POSITIVE_AMOUNT_CLASS.split(' ')[0]}`)).toBeFalsy()
    expect(container.querySelector(`.${NEGATIVE_AMOUNT_CLASS}`)).toBeFalsy()
  })
})
