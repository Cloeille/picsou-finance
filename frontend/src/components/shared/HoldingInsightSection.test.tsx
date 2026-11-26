import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { HoldingInsightSection } from './HoldingInsightSection'
import type { SecurityInsight } from '@/types/api'

const useSecurityInsight = vi.fn()

vi.mock('@/features/accounts/hooks', () => ({
  useSecurityInsight: (...args: unknown[]) => useSecurityInsight(...args),
}))

// Minimal i18n stub: translate the namespaced keys this component looks up,
// otherwise fall back to the provided default string (mirrors t(key, raw)).
// The second arg is only a fallback when it is a string — t(key, {opts}) keeps returning the key.
const TEST_LABELS: Record<string, string> = {
  'holdings.insight.countryNames.US': 'United States',
  'holdings.insight.countryNames.JP': 'Japan',
  'holdings.insight.sectorNames.technology': 'Technology',
  'holdings.insight.sectorNames.financial_services': 'Financial Services',
}
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: unknown) =>
      TEST_LABELS[key] ?? (typeof fallback === 'string' ? fallback : key),
  }),
}))

function mockInsight(data: SecurityInsight | Record<string, never> | undefined, isLoading = false) {
  useSecurityInsight.mockReturnValue({ data, isLoading })
}

const etfComposition: SecurityInsight = {
  ticker: 'IWDA',
  assetType: 'ETF',
  composition: {
    companies: [{ label: 'Apple', percent: 5.1 }, { label: 'Microsoft', percent: 4.4 }],
    countries: [{ label: 'US', percent: 70.8 }, { label: 'JP', percent: 6.0 }],
    sectors: [{ label: 'technology', percent: 24.1 }, { label: 'financial_services', percent: 16.4 }],
    source: 'iShares',
    asOf: '2026-05-31',
  },
}

describe('HoldingInsightSection', () => {
  it('renders the three composition bars for an ETF', () => {
    mockInsight(etfComposition)
    const { container } = render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)

    // Three PartitionBar instances — one per breakdown (companies/countries/sectors).
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(3)
    expect(screen.getByText('holdings.insight.companies')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.countries')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.sectors')).toBeInTheDocument()
    expect(screen.getByText('Apple')).toBeInTheDocument()
    expect(screen.getByText('United States')).toBeInTheDocument()
    expect(screen.getByText('Technology')).toBeInTheDocument()
    expect(screen.getByText('holdings.insight.assetTypes.ETF')).toBeInTheDocument()
  })

  it('adds an "others" remainder segment when slices sum below 100%', () => {
    mockInsight(etfComposition)
    render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)
    // companies sum to 9.5% → an "Autres" remainder is expected.
    expect(screen.getAllByText('holdings.insight.others').length).toBeGreaterThan(0)
  })

  it('toggles between the line and block composition views', () => {
    mockInsight(etfComposition)
    const { container } = render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)

    // Default is the "line" view: three bars, labels in the legend.
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(3)
    expect(screen.getByText('United States')).toBeInTheDocument()

    // Switch to the "block" view — still three bars, labels now inside the segments.
    fireEvent.click(screen.getByText('holdings.insight.viewBlock'))
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(3)
    expect(screen.getByText('United States')).toBeInTheDocument()
    expect(screen.getByText('Apple')).toBeInTheDocument()
    expect(screen.getByText('Technology')).toBeInTheDocument()
  })

  it('shows only the type badge (no bars) for a stock', () => {
    mockInsight({ ticker: 'AAPL', assetType: 'STOCK', composition: null })
    const { container } = render(<HoldingInsightSection ticker="AAPL" name="Apple Inc." open />)

    expect(screen.getByText('holdings.insight.assetTypes.STOCK')).toBeInTheDocument()
    expect(container.querySelectorAll('[data-slot="partition-bar"]')).toHaveLength(0)
    expect(screen.queryByText('holdings.insight.unavailable')).not.toBeInTheDocument()
  })

  it('shows an unavailable note for an ETF without composition', () => {
    mockInsight({ ticker: 'XYZ', assetType: 'ETF', composition: null })
    render(<HoldingInsightSection ticker="XYZ" name="Some ETF" open />)
    expect(screen.getByText('holdings.insight.unavailable')).toBeInTheDocument()
  })

  it('renders nothing when the response is empty (unknown ticker / demo fallback)', () => {
    mockInsight({})
    const { container } = render(<HoldingInsightSection ticker="ZZZ" name="Mystery" open />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a spinner while loading', () => {
    mockInsight(undefined, true)
    const { container } = render(<HoldingInsightSection ticker="IWDA" name="iShares Core MSCI World" open />)
    expect(container.querySelector('.animate-spin')).toBeInTheDocument()
  })
})
