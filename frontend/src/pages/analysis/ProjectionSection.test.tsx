import '@testing-library/jest-dom'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ProjectionSection } from './ProjectionSection'
import type { Projection } from '@/types/api'

const useProjection = vi.fn()
vi.mock('@/features/analysis/hooks', () => ({
  useProjection: (years: number) => useProjection(years),
  // Stubbed identity: the chart is mocked out below, so only the hook's presence matters here.
  useProjectionDateLabel: () => (date: string) => date,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

// Recharts measures its container, which jsdom reports as 0x0; the chart body is not what these
// assertions are about.
vi.mock('@/components/ui/chart', () => ({
  ChartContainer: ({ children }: { children: React.ReactNode }) => <div data-slot="chart">{children}</div>,
  ChartTooltip: () => null,
  ChartTooltipContent: () => null,
}))

function projection(overrides: Partial<Projection> = {}): Projection {
  const points = [
    { date: '2026-12-31', valueEur: 100000, contributedEur: 100000 },
    { date: '2027-12-31', valueEur: 110000, contributedEur: 103600 },
  ]
  return {
    baseValueEur: 96400,
    monthlyInflowEur: 300,
    years: 20,
    allocation: [],
    // Blended rates, not headline assumptions: what a scenario works out to depends on where the
    // money sits, so the payload carries the number and the client never restates it.
    scenarios: [
      { key: 'PESSIMISTIC', annualPercent: 4.4, riskyDelta: -2.5, points },
      { key: 'CAUTIOUS', annualPercent: 5.6, riskyDelta: -1, points },
      { key: 'REFERENCE', annualPercent: 6.4, riskyDelta: 0, points },
      { key: 'OPTIMISTIC', annualPercent: 8.2, riskyDelta: 2.5, points },
    ],
    ...overrides,
  }
}

describe('ProjectionSection', () => {
  beforeEach(() => {
    useProjection.mockReturnValue({ data: projection() })
  })

  it('labels every scenario with the rate the backend sent', () => {
    render(<ProjectionSection />)

    // The rates are never restated client-side: a label that disagrees with the curve behind it
    // is worse than no label.
    expect(screen.getByText('analysis.projection.scenarios.PESSIMISTIC:4.4')).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.scenarios.REFERENCE:6.4')).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.scenarios.OPTIMISTIC:8.2')).toBeInTheDocument()
  })

  it('keeps the legend in the payload order, not alphabetical', () => {
    render(<ProjectionSection />)

    const labels = screen.getAllByRole('listitem').map((li) => li.textContent)
    expect(labels).toEqual([
      // Contributions first: they are the floor the gain sits on, not a scenario.
      'analysis.projection.contributed',
      'analysis.projection.scenarios.PESSIMISTIC:4.4',
      'analysis.projection.scenarios.CAUTIOUS:5.6',
      'analysis.projection.scenarios.REFERENCE:6.4',
      'analysis.projection.scenarios.OPTIMISTIC:8.2',
    ])
  })

  it('states what it is projecting from, because it is not the net worth', () => {
    render(<ProjectionSection />)
    // The label shares its paragraph with the amount, so the text node is split.
    expect(screen.getByText(/analysis\.projection\.basis/)).toBeInTheDocument()
    expect(screen.getByText('analysis.projection.disclaimer')).toBeInTheDocument()
  })

  it('asks for a plan rather than drawing a flat line at zero', () => {
    useProjection.mockReturnValue({ data: projection({ baseValueEur: 0, monthlyInflowEur: 0 }) })
    render(<ProjectionSection />)

    expect(screen.getByText('analysis.projection.nothingToProject')).toBeInTheDocument()
  })

  it('still projects a portfolio with no recurring plan', () => {
    useProjection.mockReturnValue({ data: projection({ monthlyInflowEur: 0 }) })
    render(<ProjectionSection />)

    expect(screen.queryByText('analysis.projection.nothingToProject')).not.toBeInTheDocument()
  })

  it('offers the three horizons and defaults to twenty years', () => {
    render(<ProjectionSection />)

    expect(screen.getByRole('button', { name: 'analysis.projection.years:10' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'analysis.projection.years:30' })).toBeInTheDocument()
    expect(useProjection).toHaveBeenCalledWith(20)
  })

  it('hides a scenario when its legend entry is clicked, and brings it back', () => {
    render(<ProjectionSection />)

    // Four scenarios plus contributions is more than a 320px chart separates, and comparing two
    // of them means removing the other three. The assertion is on the control's pressed state
    // rather than on the curve: the chart body is mocked out here, as everywhere in this file.
    const optimistic = screen.getByRole('button', {
      name: 'analysis.projection.scenarios.OPTIMISTIC:8.2',
    })
    expect(optimistic).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(optimistic)
    expect(optimistic).toHaveAttribute('aria-pressed', 'false')
    // Toggling one leaves the others alone -- the legend is a set of independent switches.
    expect(
      screen.getByRole('button', { name: 'analysis.projection.scenarios.REFERENCE:6.4' }),
    ).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(optimistic)
    expect(optimistic).toHaveAttribute('aria-pressed', 'true')
  })

  it('lets the contributions floor be hidden too', () => {
    render(<ProjectionSection />)

    // It is a series like any other on this chart, and the one a reader most often wants out of
    // the way once they have seen where it sits.
    const contributed = screen.getByRole('button', { name: 'analysis.projection.contributed' })
    fireEvent.click(contributed)

    expect(contributed).toHaveAttribute('aria-pressed', 'false')
  })

  it('switches to the mix and shows where each tier lands against its target', () => {
    useProjection.mockReturnValue({
      data: projection({
        allocation: [
          {
            date: '2026-12-31',
            tiers: [
              { tier: 'REAL_ESTATE', valueEur: 189351, percent: 79.2, targetPercent: 75 },
              { tier: 'EQUITY', valueEur: 42159, percent: 17.6, targetPercent: 18 },
              { tier: 'CRYPTO', valueEur: 7667, percent: 3.2, targetPercent: 5 },
              { tier: 'ALTERNATIVE', valueEur: 0, percent: 0, targetPercent: 2 },
              { tier: 'SAFETY_NET', valueEur: 16101, percent: 0, targetPercent: null },
            ],
          },
          {
            date: '2036-12-31',
            tiers: [
              { tier: 'REAL_ESTATE', valueEur: 189351, percent: 57.0, targetPercent: 75 },
              { tier: 'EQUITY', valueEur: 140000, percent: 37.0, targetPercent: 18 },
              { tier: 'CRYPTO', valueEur: 18000, percent: 5.0, targetPercent: 5 },
              { tier: 'ALTERNATIVE', valueEur: 0, percent: 0, targetPercent: 2 },
              { tier: 'SAFETY_NET', valueEur: 16101, percent: 1.0, targetPercent: null },
            ],
          },
        ],
      }),
    })
    render(<ProjectionSection />)

    fireEvent.click(screen.getByRole('button', { name: 'analysis.projection.views.allocation' }))

    // The answer the wealth curve could not give: property drifts away from its target while
    // alternatives, which no plan funds, stay at zero however long the horizon.
    expect(screen.getByText('79.2%')).toBeInTheDocument()
    expect(screen.getByText('57.0%')).toBeInTheDocument()
    expect(screen.getAllByText('0.0%').length).toBeGreaterThan(0)
    // The cushion is measured in euros against an absolute target, so it has no share to compare.
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('renders the mix when the API omits a null target, as it actually does', () => {
    // The regression. The backend declares targetPercent nullable for the cushion, but
    // spring.jackson's non_null inclusion means a null is not sent as null -- it is not sent at
    // all. Every fixture here wrote `targetPercent: null`, describing the DTO rather than the
    // response, so `=== null` passed in tests and threw in the browser.
    useProjection.mockReturnValue({
      data: projection({
        allocation: [
          {
            date: '2026-12-31',
            tiers: [
              { tier: 'EQUITY', valueEur: 42159, percent: 72.4, targetPercent: 18 },
              // No targetPercent key at all, exactly as the API sends it.
              { tier: 'SAFETY_NET', valueEur: 16101, percent: 27.6 },
            ],
          },
          {
            date: '2036-12-31',
            tiers: [
              { tier: 'EQUITY', valueEur: 140000, percent: 89.7, targetPercent: 18 },
              { tier: 'SAFETY_NET', valueEur: 16101, percent: 10.3 },
            ],
          },
        ] as never,
      }),
    })
    render(<ProjectionSection />)

    fireEvent.click(screen.getByRole('button', { name: 'analysis.projection.views.allocation' }))

    expect(screen.getByText('89.7%')).toBeInTheDocument()
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})
