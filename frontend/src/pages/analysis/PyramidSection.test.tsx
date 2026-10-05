import '@testing-library/jest-dom'
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PyramidSection } from './PyramidSection'
import type { WealthPyramid } from '@/types/api'

// Minimal i18n stub: interpolate {{value}}/{{count}} so the assertions below read the numbers
// the component actually computed, not a key.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object'
        ? `${key}:${Object.values(opts).join(',')}`
        : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

function pyramid(overrides: Partial<WealthPyramid> = {}): WealthPyramid {
  return {
    totalAssetsEur: 106000,
    allocatableEur: 100000,
    safetyNet: {
      valueEur: 6000,
      dailyCashEur: 0,
      targetEur: 6000,
      coverage: 1,
      excessEur: 0,
      known: true,
      score: 100,
    },
    tiers: [
      { tier: 'REAL_ESTATE', valueEur: 30000, actualPercent: 30, targetPercent: 30, targetEur: 30000, gapPercent: 0, accounts: [] },
      { tier: 'EQUITY', valueEur: 50000, actualPercent: 50, targetPercent: 50, targetEur: 50000, gapPercent: 0, accounts: [] },
      { tier: 'CRYPTO', valueEur: 10000, actualPercent: 10, targetPercent: 10, targetEur: 10000, gapPercent: 0, accounts: [] },
      { tier: 'ALTERNATIVE', valueEur: 10000, actualPercent: 10, targetPercent: 10, targetEur: 10000, gapPercent: 0, accounts: [] },
    ],
    alerts: [],
  score: {
      global: 100,
      allocation: 100,
      misplacedPercent: 0,
      cryptoPenalty: 0,
      leverageBonus: 0,
      cryptoTopTenShare: null,
      loanToValue: null,
    },
    ...overrides,
  }
}

describe('PyramidSection', () => {
  it('renders the global score and one row per investment tier', () => {
    render(<PyramidSection pyramid={pyramid()} />)

    expect(screen.getByText('100')).toBeInTheDocument()
    for (const tier of ['REAL_ESTATE', 'EQUITY', 'CRYPTO', 'ALTERNATIVE']) {
      expect(screen.getByText(`analysis.tiers.${tier}`)).toBeInTheDocument()
    }
    // The cushion is measured in euros against an absolute target, not as a share of anything.
    expect(screen.queryByText('analysis.tiers.SAFETY_NET')).not.toBeInTheDocument()
  })

  it('shows the safety net as unrated rather than zero when expenses are unknown', () => {
    render(
      <PyramidSection
        pyramid={pyramid({
          safetyNet: { valueEur: 6000, dailyCashEur: 0, targetEur: null, coverage: null, excessEur: 0, known: false, score: null },
        })}
      />,
    )

    expect(screen.getByText('analysis.score.notRated')).toBeInTheDocument()
    expect(screen.getByText('analysis.safetyNet.unknown')).toBeInTheDocument()
    // The prompt to fill in the figure is the point of the unknown state.
    expect(screen.getByText('analysis.score.safetyNetUnknownHint')).toBeInTheDocument()
  })

  it('survives the payload the API really sends, which omits nulls rather than sending them', () => {
    // Every fixture above writes `coverage: null`, `global: null` -- describing the DTO, not the
    // response. spring.jackson's non_null inclusion drops a null field entirely, so these arrive
    // as undefined, and the same mistake one screen over took the Répartition tab down with a
    // TypeError. Here it was quieter: undefined * 100 is NaN in the coverage bar, and a strict
    // `=== null` would have printed "undefined / 100" for the score.
    render(
      <PyramidSection
        pyramid={pyramid({
          safetyNet: { valueEur: 6000, dailyCashEur: 0, excessEur: 0, known: false },
          score: { misplacedPercent: 0, cryptoPenalty: 0, leverageBonus: 0 },
        } as never)}
      />,
    )

    expect(screen.getByText('analysis.score.notRatedYet')).toBeInTheDocument()
    expect(screen.getAllByText('analysis.score.notRated')).toHaveLength(2)
    expect(screen.queryByText(/NaN|undefined/)).not.toBeInTheDocument()
  })

  it('flags a tier only once its gap is worth acting on', () => {
    render(
      <PyramidSection
        pyramid={pyramid({
          tiers: [
            { tier: 'EQUITY', valueEur: 50000, actualPercent: 30, targetPercent: 50, targetEur: 83333, gapPercent: -20, accounts: [] },
            { tier: 'CRYPTO', valueEur: 10000, actualPercent: 12, targetPercent: 10, targetEur: 8333, gapPercent: 2, accounts: [] },
          ],
        })}
      />,
    )

    expect(screen.getByText(/-20\.0/)).toBeInTheDocument()
    // A 2-point drift is noise, not advice.
    expect(screen.queryByText(/^2\.0$/)).not.toBeInTheDocument()
  })

  it('surfaces the excess cushion as money to redeploy', () => {
    render(
      <PyramidSection
        pyramid={pyramid({
          safetyNet: { valueEur: 10000, dailyCashEur: 0, targetEur: 6000, coverage: 1.667, excessEur: 4000, known: true, score: 87 },
        })}
      />,
    )

    expect(screen.getByText('analysis.safetyNet.excess')).toBeInTheDocument()
    expect(screen.getByText('analysis.safetyNet.excessHint')).toBeInTheDocument()
  })

  it('hides the crypto penalty and leverage bonus when they are zero', () => {
    render(<PyramidSection pyramid={pyramid()} />)

    expect(screen.queryByText('analysis.score.cryptoPenalty')).not.toBeInTheDocument()
    expect(screen.queryByText('analysis.score.leverageBonus')).not.toBeInTheDocument()
  })

  it('shows the modifiers once they bite', () => {
    render(
      <PyramidSection
        pyramid={pyramid({
          score: {
            global: 82, allocation: 90, misplacedPercent: 10,
            cryptoPenalty: 1.5, leverageBonus: 4.2,
            cryptoTopTenShare: 40, loanToValue: 55,
          },
        })}
      />,
    )

    expect(screen.getByText('analysis.score.cryptoPenalty')).toBeInTheDocument()
    expect(screen.getByText('analysis.score.leverageBonus')).toBeInTheDocument()
  })
})
