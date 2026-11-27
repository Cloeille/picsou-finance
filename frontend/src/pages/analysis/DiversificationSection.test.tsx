import '@testing-library/jest-dom/vitest'
import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { DiversificationSection } from './DiversificationSection'
import { applyDemoClassifications, mockDiversification } from '@/demo/data/analysis'
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
  'holdings.insight.sectorNames.other': 'Others',
  'holdings.insight.countryNames.US': 'United States',
  'holdings.insight.countryNames.other': 'Others',
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
    expect(screen.getByText('analysis.diversification.coverage:60%')).toBeInTheDocument()
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

  it('omits verbose mixed-basis footnotes to keep the breakdown uncluttered', () => {
    render(
      <DiversificationSection
        data={data({
          countries: { score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'MIXED', classifiedValueEur: 10000, coveragePercent: 100, slices: [{ label: 'US', percent: 100, valueEur: 10000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 10000, sharePercent: 100 }] }] },
        })}
      />,
    )
    expect(screen.queryByText('analysis.diversification.basisNote')).not.toBeInTheDocument()
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

  it('merges hairline slices into an explicit other slice with matching legend and drill-down percentage', () => {
    render(
      <DiversificationSection
        data={data({
          sectors: {
            score: 50, effectiveCount: 2, targetCount: 6, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
            slices: [
              { label: 'technology', percent: 95, valueEur: 9500, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 9500, sharePercent: 100 }] },
              { label: 'energy', percent: 0.3, valueEur: 30, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 30, sharePercent: 100 }] },
              { label: 'utilities', percent: 0.3, valueEur: 30, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 30, sharePercent: 100 }] },
              { label: 'other', percent: 4.4, valueEur: 440, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 440, sharePercent: 100 }] },
            ],
          },
        })}
      />,
    )

    const othersButtons = screen.getAllByRole('button', { name: /Others/ })
    expect(othersButtons).toHaveLength(1)
    expect(othersButtons[0]).toHaveTextContent('5.0%')

    // Clicking Others opens the drill-down with the exact same merged 5.0% and 500 € total.
    fireEvent.click(othersButtons[0])
    expect(screen.getAllByText('5.0%').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('iShares Core MSCI World')).toBeInTheDocument()
  })

  it('formats untranslated ISO country codes in the user language via Intl.DisplayNames', () => {
    render(
      <DiversificationSection
        data={data({
          countries: {
            score: 70, effectiveCount: 2.1, targetCount: 3, basis: 'EXPOSURE', classifiedValueEur: 10000, coveragePercent: 100,
            slices: [
              { label: 'US', percent: 90, valueEur: 9000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 9000, sharePercent: 100 }] },
              { label: 'RO', percent: 10, valueEur: 1000, contributorCount: 1, contributors: [{ ticker: 'IWDA', valueEur: 1000, sharePercent: 100 }] },
            ],
          },
        })}
      />,
    )

    expect(screen.getByText('United States')).toBeInTheDocument()
    // Mocked i18n language is 'fr': RO is missing from LABELS and resolves via Intl.DisplayNames.
    expect(screen.getByText('Roumanie')).toBeInTheDocument()
    expect(screen.queryByText('RO')).not.toBeInTheDocument()
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

  it('opens the unclassified breakdown from the badge, hides Classify on incomplete composition, and lets the user classify other reasons', () => {
    render(
      <DiversificationSection
        data={data({
          totalValueEur: 10000,
          classifiedValueEur: 9700,
          unclassifiedValueEur: 300,
          coveragePercent: 97,
          unclassified: [],
          sectors: {
            score: 80,
            effectiveCount: 4.8,
            targetCount: 6,
            basis: 'EXPOSURE',
            classifiedValueEur: 4000,
            unclassifiedValueEur: 6000,
            coveragePercent: 40,
            slices: [
              {
                label: 'technology',
                percent: 100,
                valueEur: 4000,
                contributorCount: 1,
                contributors: [{ ticker: 'PARTIAL', valueEur: 4000, sharePercent: 100 }],
              },
            ],
            unclassified: [
              {
                ticker: 'PARTIAL',
                isin: 'LU2090062436',
                name: 'Partial Thematic ETF',
                accountId: 2,
                accountName: 'PEA BoursoBank',
                totalValueEur: 10000,
                unclassifiedValueEur: 6000,
                unclassifiedPercent: 60,
                reason: 'INCOMPLETE_COMPOSITION',
              },
            ],
          },
          countries: {
            score: 70,
            effectiveCount: 2.1,
            targetCount: 3,
            basis: 'EXPOSURE',
            classifiedValueEur: 9700,
            unclassifiedValueEur: 300,
            coveragePercent: 97,
            slices: [
              {
                label: 'US',
                percent: 100,
                valueEur: 9700,
                contributorCount: 1,
                contributors: [{ ticker: 'PARTIAL', valueEur: 9700, sharePercent: 100 }],
              },
            ],
            unclassified: [
              {
                ticker: 'PSOTHER',
                isin: 'FR0000121014',
                name: 'Synthetic Holding',
                accountId: 3,
                accountName: 'Compte Titres',
                totalValueEur: 300,
                unclassifiedValueEur: 300,
                unclassifiedPercent: 100,
                reason: 'NO_DATA',
              },
            ],
          },
        })}
      />,
    )

    // Click the overall unclassified badge — defaults to countries (the higher-coverage axis).
    fireEvent.click(screen.getByRole('button', { name: 'analysis.diversification.unclassifiedAria' }))

    expect(screen.getByText('analysis.diversification.unclassifiedDialogTitle')).toBeInTheDocument()
    expect(screen.getByText('Synthetic Holding')).toBeInTheDocument()
    expect(screen.getByText('FR0000121014')).toBeInTheDocument()
    expect(screen.getByText('Compte Titres')).toBeInTheDocument()
    expect(
      screen.getByText('analysis.diversification.unclassifiedReasons.NO_DATA'),
    ).toBeInTheDocument()

    // Switch to the sectors tab via keyboard arrow navigation to inspect INCOMPLETE_COMPOSITION.
    const countriesTab = screen.getByRole('tab', { name: /analysis.diversification.countries/ })
    fireEvent.keyDown(countriesTab, { key: 'ArrowLeft' })
    expect(screen.getByText('Partial Thematic ETF')).toBeInTheDocument()
    expect(screen.getByText('LU2090062436')).toBeInTheDocument()
    expect(screen.getByText('PEA BoursoBank')).toBeInTheDocument()
    expect(
      screen.getByText('analysis.diversification.unclassifiedReasons.INCOMPLETE_COMPOSITION'),
    ).toBeInTheDocument()
    // INCOMPLETE_COMPOSITION hides "Classify" (which would overwrite the whole ETF) and shows a hint.
    expect(
      screen.getByText('analysis.diversification.incompleteCompositionHint'),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'analysis.classification.editAria:PARTIAL' }),
    ).not.toBeInTheDocument()

    // Switch back to countries and click "Classify" on the NO_DATA holding.
    fireEvent.click(countriesTab)
    fireEvent.click(
      screen.getByRole('button', { name: 'analysis.classification.editAria:PSOTHER' }),
    )
    expect(screen.getByText('analysis.classification.title')).toBeInTheDocument()
  })

  it('breaks equal classifiedValueEur ties by lower unclassifiedValueEur to match the backend', () => {
    render(
      <DiversificationSection
        data={data({
          totalValueEur: 200.01,
          classifiedValueEur: 100,
          unclassifiedValueEur: 99.99,
          coveragePercent: 50,
          unclassified: [],
          sectors: {
            score: 50,
            effectiveCount: 1,
            targetCount: 6,
            basis: 'MIXED',
            classifiedValueEur: 100,
            unclassifiedValueEur: 100,
            coveragePercent: 50,
            slices: [
              {
                label: 'technology',
                percent: 100,
                valueEur: 100,
                contributorCount: 1,
                contributors: [{ ticker: 'A', valueEur: 100, sharePercent: 100 }],
              },
            ],
            unclassified: [
              {
                ticker: 'SINGLE100',
                name: 'Sector Unclassified Holding',
                accountId: 2,
                totalValueEur: 100,
                unclassifiedValueEur: 100,
                unclassifiedPercent: 100,
                reason: 'NO_DATA',
              },
            ],
          },
          countries: {
            score: 50,
            effectiveCount: 1,
            targetCount: 3,
            basis: 'MIXED',
            classifiedValueEur: 100,
            unclassifiedValueEur: 99.99,
            coveragePercent: 50,
            slices: [
              {
                label: 'US',
                percent: 100,
                valueEur: 100,
                contributorCount: 1,
                contributors: [{ ticker: 'B', valueEur: 100, sharePercent: 100 }],
              },
            ],
            unclassified: [
              {
                ticker: 'THIRD1',
                name: 'Country Unclassified Holding',
                accountId: 2,
                totalValueEur: 33.33,
                unclassifiedValueEur: 33.33,
                unclassifiedPercent: 100,
                reason: 'NO_DATA',
              },
            ],
          },
        })}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'analysis.diversification.unclassifiedAria' }))
    // Both axes have classifiedValueEur = 100, so the tie-breaker opens countries (99.99 < 100).
    expect(screen.getByText('Country Unclassified Holding')).toBeInTheDocument()
    expect(screen.queryByText('Sector Unclassified Holding')).not.toBeInTheDocument()
  })

  it('reflects demo classifications on GET /analysis/diversification', () => {
    const overrides = new Map([
      ['FCPE-DEMO', { wealthTier: null, sectorKey: 'technology', countryKey: 'US' }],
    ])
    const updated = applyDemoClassifications(mockDiversification, overrides)

    expect(updated.sectors.unclassified?.some((item) => item.ticker === 'FCPE-DEMO')).toBe(false)
    expect(updated.countries.unclassified?.some((item) => item.ticker === 'FCPE-DEMO')).toBe(false)
    expect(updated.unclassified.some((line) => line.ticker === 'FCPE-DEMO')).toBe(false)
    expect(updated.sectors.unclassifiedValueEur).toBe(3900)
    expect(updated.countries.unclassifiedValueEur).toBe(2400)
    expect(updated.unclassifiedValueEur).toBe(2400)
    expect(updated.classifiedValueEur).toBe(140000)
  })

  it('opens directly on the chosen dimension from a per-axis unclassified badge', () => {
    render(
      <DiversificationSection
        data={data({
          totalValueEur: 10000,
          classifiedValueEur: 10000,
          unclassifiedValueEur: 0,
          coveragePercent: 100,
          unclassified: [],
          sectors: {
            score: 80,
            effectiveCount: 4.8,
            targetCount: 6,
            basis: 'EXPOSURE',
            classifiedValueEur: 4000,
            unclassifiedValueEur: 6000,
            coveragePercent: 40,
            slices: [
              {
                label: 'technology',
                percent: 100,
                valueEur: 4000,
                contributorCount: 1,
                contributors: [{ ticker: 'PARTIAL', valueEur: 4000, sharePercent: 100 }],
              },
            ],
            unclassified: [
              {
                ticker: 'PARTIAL',
                isin: 'LU2090062436',
                name: 'Partial Thematic ETF',
                accountId: 2,
                accountName: 'PEA BoursoBank',
                totalValueEur: 10000,
                unclassifiedValueEur: 6000,
                unclassifiedPercent: 60,
                reason: 'INCOMPLETE_COMPOSITION',
              },
            ],
          },
        })}
      />,
    )

    // Even when headline coverage is 100% (countries fully covered), the sectors axis badge opens its list.
    expect(
      screen.queryByRole('button', { name: 'analysis.diversification.unclassifiedAria' }),
    ).not.toBeInTheDocument()
    fireEvent.click(
      screen.getByRole('button', {
        name: 'analysis.diversification.unclassifiedAxisAria:analysis.diversification.sectors',
      }),
    )

    expect(screen.getByText('Partial Thematic ETF')).toBeInTheDocument()
    expect(screen.getByText('PEA BoursoBank')).toBeInTheDocument()
  })
})
