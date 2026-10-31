import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { DiversificationSection } from './DiversificationSection'
import type { Diversification } from '@/types/api'

// No QueryClient here: the section and the editor it embeds both reach for TanStack Query, and
// this suite is about what the card renders, not about fetching. Same approach as
// AnalysisPage.test.tsx.
vi.mock('@/features/analysis/hooks', () => ({
  useRefreshSecurityProfiles: () => ({ mutate: vi.fn(), isPending: false }),
  useHoldingClassification: () => ({ data: undefined, isPending: true }),
  useClassifyHolding: () => ({ mutate: vi.fn(), isPending: false }),
}))

// Translate the two label namespaces the way the app does, and fall back to the raw value —
// that fallback is the contract for a sector or country key the locales do not map.
const LABELS: Record<string, string> = {
  'holdings.insight.sectorNames.technology': 'Technology',
  'holdings.insight.countryNames.US': 'United States',
  'holdings.insight.others': 'Others',
}
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: unknown) => {
      if (LABELS[key]) return LABELS[key]
      if (typeof opts === 'string') return opts
      if (opts && typeof opts === 'object') return `${key}:${Object.values(opts).join(',')}`
      return key
    },
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

function data(overrides: Partial<Diversification> = {}): Diversification {
  return {
    totalValueEur: 10000,
    classifiedValueEur: 10000,
    unclassifiedValueEur: 0,
    coveragePercent: 100,
    unclassified: [],
    securities: [{ ticker: 'IWDA', name: 'iShares Core MSCI World', accountId: 2, valueEur: 10000 }],
    sectors: {
      score: 80, effectiveCount: 4.8, targetCount: 6, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
      slices: [
        { label: 'technology', percent: 60, valueEur: 6000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 6000, sharePercent: 100 }] },
        { label: 'unmapped_sector', percent: 40, valueEur: 4000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 4000, sharePercent: 100 }] },
      ],
    },
    countries: {
      score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
      slices: [{ label: 'US', percent: 100, valueEur: 10000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 10000, sharePercent: 100 }] }],
    },
    ...overrides,
  }
}

describe('DiversificationSection', () => {
  it('translates known keys and renders an unmapped one verbatim', () => {
    render(<DiversificationSection data={data()} />)

    expect(screen.getByText('Technology')).toBeInTheDocument()
    expect(screen.getByText('United States')).toBeInTheDocument()
    // A key the locales do not carry must still render as something, never as a blank slice.
    expect(screen.getByText('unmapped_sector')).toBeInTheDocument()
  })

  it('shows both scores with their effective position counts', () => {
    render(<DiversificationSection data={data()} />)

    expect(screen.getByText('80 / 100')).toBeInTheDocument()
    expect(screen.getByText('70 / 100')).toBeInTheDocument()
    expect(screen.getByText('analysis.diversification.effective:4.8,6')).toBeInTheDocument()
  })

  it('states the coverage and lists what could not be placed', () => {
    render(
      <DiversificationSection
        data={data({
          classifiedValueEur: 6000,
          unclassifiedValueEur: 4000,
          coveragePercent: 60,
          unclassified: [
            {
              ticker: 'MC.PA',
              name: 'LVMH',
              accountId: 3,
              valueEur: 4000,
              sectorMissing: false,
              countryMissing: true,
              profileLooked: true,
            },
          ],
        })}
      />,
    )

    // A bar computed over 60% of the portfolio must not read as one computed over all of it.
    expect(screen.getByText('analysis.diversification.coverage:60')).toBeInTheDocument()
    // Naming the line is the point: a bare total says the score is wrong without saying why.
    expect(screen.getByText('LVMH')).toBeInTheDocument()
    expect(screen.getByText('MC.PA')).toBeInTheDocument()
  })

  it('offers the lookup only when something has never been looked up', () => {
    const looked = {
      ticker: 'MC.PA', name: 'LVMH', accountId: 3, valueEur: 4000,
      sectorMissing: false, countryMissing: true, profileLooked: true,
    }
    const { rerender } = render(
      <DiversificationSection data={data({ unclassified: [looked] })} />,
    )
    // Already looked up and still unplaced: only a human can fix it, so promising a refresh
    // would promise a fix that cannot come.
    expect(screen.queryByText('analysis.classification.refresh')).not.toBeInTheDocument()

    rerender(
      <DiversificationSection
        data={data({ unclassified: [{ ...looked, profileLooked: false }] })}
      />,
    )
    expect(screen.getByText('analysis.classification.refresh')).toBeInTheDocument()
  })

  it('notes the mixed basis only when a directly held share contributed', () => {
    const { rerender } = render(<DiversificationSection data={data()} />)
    expect(screen.queryByText('analysis.diversification.basisNote')).not.toBeInTheDocument()

    rerender(
      <DiversificationSection
        data={data({
          countries: { score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'MIXED', classifiedValueEur: 10000, coveragePercent: 100, slices: [{ label: 'US', percent: 100, valueEur: 10000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 10000, sharePercent: 100 }] }] },
        })}
      />,
    )
    expect(screen.getByText('analysis.diversification.basisNote')).toBeInTheDocument()
  })

  // Two 0.4% slices are each below the hairline threshold but add up above it, so they become a
  // visible remainder rather than silently disappearing from a bar that claims to total 100%.
  it('folds hairline slices into an Others remainder', () => {
    render(
      <DiversificationSection
        data={data({
          sectors: {
            score: 50, effectiveCount: 2, targetCount: 6, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
            slices: [
              { label: 'technology', percent: 99.2, valueEur: 99.200, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 99.200, sharePercent: 100 }] },
              { label: 'energy', percent: 0.4, valueEur: 0.400, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 0.400, sharePercent: 100 }] },
              { label: 'utilities', percent: 0.4, valueEur: 0.400, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 0.400, sharePercent: 100 }] },
            ],
          },
        })}
      />,
    )

    expect(screen.getByText('Others')).toBeInTheDocument()
    expect(screen.queryByText('energy')).not.toBeInTheDocument()
  })

  it('says so plainly when there is nothing to analyse', () => {
    render(<DiversificationSection data={data({ totalValueEur: 0 })} />)
    expect(screen.getByText('analysis.diversification.noHoldings')).toBeInTheDocument()
  })

  it('names the holdings behind a slice once it is selected', () => {
    render(
      <DiversificationSection
        data={data({
          securities: [
            { ticker: 'IWDA', name: 'iShares Core MSCI World', accountId: 2, valueEur: 6000 },
            { ticker: 'AAPL', name: 'Apple Inc.', accountId: 3, valueEur: 4000 },
          ],
          countries: {
            score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'EXPOSURE',
            classifiedValueEur: 10000, coveragePercent: 100,
            slices: [{
              label: 'US', percent: 100, valueEur: 10000, contributorCount: 2,
              contributors: [
                { ticker: 'IWDA', valueEur: 6000, sharePercent: 60 },
                { ticker: 'AAPL', valueEur: 4000, sharePercent: 40 },
              ],
            }],
          },
        })}
      />,
    )

    // Closed by default: the panel is an answer to a question, not permanent furniture.
    expect(screen.queryByText('Apple Inc.')).not.toBeInTheDocument()

    // The legend entry is a button; the panel header repeats the label, so target the role.
    fireEvent.click(screen.getByRole('button', { name: /United States/ }))

    // "US 100 %" is not an answer until you can see which lines produced it.
    expect(screen.getByText('iShares Core MSCI World')).toBeInTheDocument()
    expect(screen.getByText('Apple Inc.')).toBeInTheDocument()
  })

  it('closes the panel when the same slice is selected again', () => {
    render(<DiversificationSection data={data()} />)

    const legendEntry = () => screen.getByRole('button', { name: /Technology/ })

    fireEvent.click(legendEntry())
    expect(screen.getByText('iShares Core MSCI World')).toBeInTheDocument()

    fireEvent.click(legendEntry())
    expect(screen.queryByText('iShares Core MSCI World')).not.toBeInTheDocument()
  })

  it('offers the three renderings and remembers the choice', () => {
    render(<DiversificationSection data={data()} />)

    // Bars by default, and the legend is part of it — so the labels are present either way.
    expect(screen.getByRole('button', { name: 'analysis.diversification.views.donut' }))
      .toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'analysis.diversification.views.treemap' }))

    expect(window.localStorage.getItem('picsou.diversification.view')).toBe('treemap')
  })

  it('keeps the drill-down working whichever rendering is chosen', () => {
    render(<DiversificationSection data={data()} />)

    fireEvent.click(screen.getByRole('button', { name: 'analysis.diversification.views.treemap' }))
    // The three views share one selection model rather than each holding its own, so a slice
    // opened from the treemap resolves to the same panel the legend would have opened.
    fireEvent.click(screen.getAllByRole('button', { name: /Technology/ })[0])

    expect(screen.getByText('iShares Core MSCI World')).toBeInTheDocument()
  })
})
