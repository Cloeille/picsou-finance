import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render } from '@testing-library/react'
import { HoldingsTable } from '@/components/shared/HoldingsTable'
import { PyramidSection } from '@/pages/analysis/PyramidSection'
import { useAppStore } from '@/stores/app-store'
import type { HoldingResponse, WealthPyramid } from '@/types/api'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, opts?: Record<string, unknown>) =>
      opts && typeof opts === 'object' ? `${key}:${Object.values(opts).join(',')}` : key,
    i18n: { resolvedLanguage: 'fr', language: 'fr' },
  }),
}))

/**
 * Digit runs that appear nowhere else — not in a date, a percentage or a count — so that finding
 * one in the DOM means an amount leaked, and finding none means it did not.
 */
const SENTINELS = ['987654', '123456', '555444', '137137']

/** Grouping separators differ by locale (and are narrow no-break spaces in French), so compare
 *  on the digits alone rather than on the formatted string. */
function digitsOf(container: HTMLElement): string {
  return (container.textContent ?? '').replace(/\D/g, '')
}

/**
 * The test that fails when an amount leaks, rather than restating how masking works.
 *
 * It asserts the *absence of the digits*, so it does not care which path printed them: a cell
 * nobody migrated, a `title` attribute, a sub-component still calling Intl directly. And it flips
 * the flag on an already-mounted tree, which is the scenario the whole design turns on — a test
 * that rendered with the flag already set would pass even if toggling never re-rendered anything.
 */
function expectNoAmountsAfterToggle(ui: React.ReactElement) {
  const { container } = render(ui)
  // The amounts are there to begin with, otherwise the assertion below proves nothing.
  const present = SENTINELS.filter((sentinel) => digitsOf(container).includes(sentinel))
  expect(present.length).toBeGreaterThan(0)

  act(() => { useAppStore.getState().setHideAmounts(true) })

  for (const sentinel of present) expect(digitsOf(container)).not.toContain(sentinel)
  // Note the currency symbol is deliberately *not* asserted absent: a masked amount keeps it
  // (`***** €`) so it still reads as a hidden amount rather than as a rendering fault.
}

function holding(overrides: Partial<HoldingResponse> = {}): HoldingResponse {
  return {
    ticker: 'CW8',
    name: 'Amundi MSCI World',
    quantity: 137137,
    // Unit prices are published and stay legible, so they must not carry a sentinel.
    averageBuyIn: 452.3,
    currentPrice: 452.3,
    currentValueEur: 987654,
    pnlEur: 123456,
    pnlPercent: 12.5,
    quoteCurrency: 'EUR',
    priceUpdatedAt: null,
    priceStale: false,
    priceAsOf: null,
    ...overrides,
  } as HoldingResponse
}

function pyramid(): WealthPyramid {
  return {
    totalAssetsEur: 987654,
    allocatableEur: 987654,
    safetyNet: {
      valueEur: 123456, dailyCashEur: 0, targetEur: 555444,
      coverage: 1, excessEur: 0, known: true, score: 80,
    },
    tiers: [
      { tier: 'EQUITY', valueEur: 987654, actualPercent: 50, targetPercent: 50, targetEur: 987654, gapPercent: 0, accounts: [] },
    ],
    alerts: [{ code: 'CUSHION_OVERFUNDED', label: null, percent: 12, valueEur: 123456 }],
    score: {
      global: 82, allocation: 90, misplacedPercent: 10,
      cryptoPenalty: 0, leverageBonus: 0, cryptoTopTenShare: null, loanToValue: null,
    },
  } as unknown as WealthPyramid
}

describe('no amount survives privacy mode', () => {
  beforeEach(() => {
    useAppStore.getState().setHideAmounts(false)
  })

  it('holdings table — values, P&L and quantities', () => {
    expectNoAmountsAfterToggle(<HoldingsTable holdings={[holding({ pnlEur: 123456, currentValueEur: 987654 })]} />)
  })

  it('wealth pyramid, including the amount interpolated into an alert sentence', () => {
    // The alert reads "{{value}} beyond your target", so the amount has no element of its own to
    // intercept — it has to be masked before it reaches t().
    expectNoAmountsAfterToggle(<PyramidSection pyramid={pyramid()} />)
  })

  it('leaves the published quote legible, which is the one deliberate exception', () => {
    const { container } = render(<HoldingsTable holdings={[holding()]} />)
    act(() => { useAppStore.getState().setHideAmounts(true) })

    // A quote is public and says nothing about how much is held — and the quantity beside it is
    // masked, which is what keeps the position unreconstructible.
    expect(container.textContent).toContain('452')
  })
})
