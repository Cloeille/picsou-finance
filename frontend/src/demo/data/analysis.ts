import type {
  AllocationTargets,
  Diversification,
  EssentialExpenseEstimate,
  Projection,
  WealthPyramid,
} from '@/types/api'

/**
 * A deliberately imperfect portfolio: the emergency fund overshoots, equity is well under
 * target and alternatives are almost absent. A demo that scores 100 shows none of the UI that
 * matters — the gap badges, the excess-cash line, the "money to move" figure.
 */
export const mockAllocationTargets: AllocationTargets = {
  monthlyEssentialExpenses: 1850,
  safetyNetMonths: 6,
  realEstatePct: 30,
  equityPct: 50,
  cryptoPct: 10,
  alternativePct: 10,
}

export const mockWealthPyramid: WealthPyramid = {
  // Every figure below is what WealthPyramidService would actually produce from these accounts:
  // total assets include the current-account cash, allocatable removes both the cushion and that
  // cash, and each tier's percentages divide allocatable. A fixture whose arithmetic disagrees
  // with the service teaches the UI to render an impossible payload.
  totalAssetsEur: 341700,
  allocatableEur: 319200,
  safetyNet: {
    valueEur: 18200,
    dailyCashEur: 4300,
    targetEur: 11100,
    coverage: 1.6396,
    excessEur: 7100,
    known: true,
    score: 87,
  },
  tiers: [
    {
      tier: 'REAL_ESTATE',
      targetEur: 95760,
      valueEur: 138000,
      actualPercent: 43.23,
      targetPercent: 30,
      gapPercent: 13.23,
      accounts: [{ accountId: 8, name: 'Appartement Lyon', color: '#a855f7', valueEur: 138000 }],
    },
    {
      tier: 'EQUITY',
      targetEur: 159600,
      valueEur: 142400,
      actualPercent: 44.61,
      targetPercent: 50,
      gapPercent: -5.39,
      accounts: [
        { accountId: 2, name: 'PEA', color: '#6366f1', valueEur: 96400 },
        { accountId: 5, name: 'Assurance vie', color: '#8b5cf6', valueEur: 46000 },
      ],
    },
    {
      tier: 'CRYPTO',
      targetEur: 31920,
      valueEur: 32800,
      actualPercent: 10.28,
      targetPercent: 10,
      gapPercent: 0.28,
      accounts: [{ accountId: 3, name: 'Binance', color: '#f97316', valueEur: 32800 }],
    },
    {
      tier: 'ALTERNATIVE',
      targetEur: 31920,
      valueEur: 6000,
      actualPercent: 1.88,
      targetPercent: 10,
      gapPercent: -8.12,
      accounts: [{ accountId: 7, name: 'Or physique', color: '#eab308', valueEur: 6000 }],
    },
  ],
  // The demo portfolio is 41 % property in one line, so the concentration observation fires —
  // which is the point of shipping it: the score alone says the allocation is fine.
  alerts: [
    { code: 'SINGLE_ASSET_CONCENTRATION', label: 'Appartement Lyon', valueEur: 138000, percent: 40.39 },
  ],
  score: {
    global: 91,
    allocation: 86,
    misplacedPercent: 13.51,
    cryptoPenalty: 0.1,
    leverageBonus: 4.28,
    cryptoTopTenShare: 72.5,
    loanToValue: 51.4,
  },
}

export const mockExpenseEstimate: EssentialExpenseEstimate = {
  estimate: 1912.4,
  monthsObserved: 6,
  excludedTransferCount: 11,
}

/**
 * Deliberately imperfect too: technology-heavy, US-heavy, and with lines the profiles cannot
 * place — so the coverage line, the correction list and both of its states are all exercised.
 * One was never looked up (a refresh may still fix it), the other was and still has no domicile,
 * which is the case only a hand-made override can close.
 */
export const mockDiversification: Diversification = {
  totalValueEur: 142400,
  classifiedValueEur: 131800,
  unclassifiedValueEur: 10600,
  coveragePercent: 92.56,
  unclassified: [
    {
      ticker: 'FCPE-DEMO',
      name: 'Actions Monde (FCPE)',
      accountId: 4,
      valueEur: 8200,
      sectorMissing: true,
      countryMissing: true,
      profileLooked: false,
    },
    {
      ticker: 'MC.PA',
      name: 'LVMH',
      accountId: 3,
      valueEur: 2400,
      sectorMissing: false,
      countryMissing: true,
      profileLooked: true,
    },
  ],
  sectors: {
    score: 78,
    effectiveCount: 4.68,
    targetCount: 6,
    basis: 'MIXED',
    // Deliberately below the country axis: a fund routinely discloses its countries more fully
    // than its sectors, and the headline coverage reports only the better of the two.
    classifiedValueEur: 118600,
    coveragePercent: 83.29,
    slices: [
      { label: 'technology', percent: 31.4, valueEur: 37240.40, contributorCount: 3,
        contributors: [{ ticker: 'IWDA', valueEur: 26813.09, sharePercent: 72.0 }, { ticker: 'AAPL', valueEur: 6703.27, sharePercent: 18.0 }, { ticker: 'MSFT', valueEur: 3724.04, sharePercent: 10.0 }] },
      { label: 'financial_services', percent: 18.2, valueEur: 21585.20, contributorCount: 2,
        contributors: [{ ticker: 'IWDA', valueEur: 13166.97, sharePercent: 61.0 }, { ticker: 'AXP', valueEur: 8418.23, sharePercent: 39.0 }] },
      { label: 'healthcare', percent: 13.7, valueEur: 16248.20, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 16248.20, sharePercent: 100.0 }] },
      { label: 'consumer_cyclical', percent: 11.1, valueEur: 13164.60, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 13164.60, sharePercent: 100.0 }] },
      { label: 'industrials', percent: 9.8, valueEur: 11622.80, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 11622.80, sharePercent: 100.0 }] },
      { label: 'energy', percent: 6.3, valueEur: 7471.80, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 7471.80, sharePercent: 100.0 }] },
      { label: 'basic_materials', percent: 4.2, valueEur: 4981.20, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 4981.20, sharePercent: 100.0 }] },
      { label: 'utilities', percent: 3.1, valueEur: 3676.60, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 3676.60, sharePercent: 100.0 }] },
      { label: 'real_estate', percent: 2.2, valueEur: 2609.20, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 2609.20, sharePercent: 100.0 }] }
    ],
  },
  securities: [
    { ticker: 'IWDA', name: 'iShares Core MSCI World', accountId: 2, valueEur: 84200 },
    { ticker: 'AAPL', name: 'Apple Inc.', accountId: 3, valueEur: 21400 },
    { ticker: 'MSFT', name: 'Microsoft Corp', accountId: 3, valueEur: 11900 },
    { ticker: 'AXP', name: 'American Express', accountId: 3, valueEur: 8300 },
    { ticker: 'MC.PA', name: 'LVMH', accountId: 3, valueEur: 6100 },
    { ticker: 'AI.PA', name: 'Air Liquide', accountId: 3, valueEur: 4400 },
  ],
  countries: {
    score: 71,
    effectiveCount: 2.14,
    targetCount: 3,
    basis: 'MIXED',
    classifiedValueEur: 131800,
    coveragePercent: 92.56,
    slices: [
      { label: 'US', percent: 62.8, valueEur: 82770.40, contributorCount: 4,
        contributors: [{ ticker: 'IWDA', valueEur: 54628.46, sharePercent: 66.0 }, { ticker: 'AAPL', valueEur: 16554.08, sharePercent: 20.0 }, { ticker: 'MSFT', valueEur: 7449.34, sharePercent: 9.0 }, { ticker: null, valueEur: 4138.52, sharePercent: 5.0 }] },
      { label: 'FR', percent: 14.3, valueEur: 18847.40, contributorCount: 2,
        contributors: [{ ticker: 'MC.PA', valueEur: 10931.49, sharePercent: 58.0 }, { ticker: 'AI.PA', valueEur: 7915.91, sharePercent: 42.0 }] },
      { label: 'JP', percent: 7.1, valueEur: 9357.80, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 9357.80, sharePercent: 100.0 }] },
      { label: 'GB', percent: 5.4, valueEur: 7117.20, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 7117.20, sharePercent: 100.0 }] },
      { label: 'DE', percent: 4.6, valueEur: 6062.80, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 6062.80, sharePercent: 100.0 }] },
      { label: 'NL', percent: 3.2, valueEur: 4217.60, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 4217.60, sharePercent: 100.0 }] },
      { label: 'CH', percent: 2.6, valueEur: 3426.80, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 3426.80, sharePercent: 100.0 }] }
    ],
  },
}

/**
 * Generated rather than hand-written: twenty years x four scenarios is 84 points, and the shape
 * that matters (each curve above the previous, contributions growing linearly) is the arithmetic
 * itself.
 */
export function mockProjection(years: number): Projection {
  const base = 96400
  const monthly = 300
  // Blended rates, not headline ones: the demo portfolio is part equity part cushion, so the
  // optimistic curve is nowhere near the equity assumption — which is the point of reporting it.
  const rates: {
    key: Projection['scenarios'][number]['key']
    annualPercent: number
    riskyDelta: number
  }[] = [
    { key: 'PESSIMISTIC', annualPercent: 4.4, riskyDelta: -2.5 },
    { key: 'CAUTIOUS', annualPercent: 5.6, riskyDelta: -1 },
    { key: 'REFERENCE', annualPercent: 6.4, riskyDelta: 0 },
    { key: 'OPTIMISTIC', annualPercent: 8.2, riskyDelta: 2.5 },
  ]

  return {
    baseValueEur: base,
    monthlyInflowEur: monthly,
    years,
    scenarios: rates.map(({ key, annualPercent, riskyDelta }) => {
      const monthlyRate = Math.pow(1 + annualPercent / 100, 1 / 12) - 1
      let value = base
      let contributed = base
      const points = [{ date: isoMonthEnd(0), valueEur: base, contributedEur: base }]
      for (let i = 1; i <= years * 12; i++) {
        value = value * (1 + monthlyRate) + monthly
        contributed += monthly
        if (i % 12 === 0) {
          points.push({
            date: isoMonthEnd(i),
            valueEur: Math.round(value * 100) / 100,
            contributedEur: Math.round(contributed * 100) / 100,
          })
        }
      }
      return { key, annualPercent, riskyDelta, points }
    }),
    // Property earns nothing here and receives nothing, so its share falls as the plans feed
    // equity — the trajectory the wealth curve alone could never show.
    allocation: Array.from({ length: years + 1 }, (_, y) => {
      const property = 138000
      const equity = 96400 + monthly * 12 * y * 1.2
      const crypto = 32800 * Math.pow(1.05, y)
      const cushion = 18200
      const total = property + equity + crypto + cushion
      const share = (v: number) => Math.round((v / total) * 10000) / 100
      return {
        date: isoMonthEnd(y * 12),
        tiers: [
          { tier: 'REAL_ESTATE' as const, valueEur: property, percent: share(property), targetPercent: 30 },
          { tier: 'EQUITY' as const, valueEur: equity, percent: share(equity), targetPercent: 50 },
          { tier: 'CRYPTO' as const, valueEur: crypto, percent: share(crypto), targetPercent: 10 },
          { tier: 'ALTERNATIVE' as const, valueEur: 0, percent: 0, targetPercent: 10 },
          { tier: 'SAFETY_NET' as const, valueEur: cushion, percent: share(cushion), targetPercent: null },
        ],
      }
    }),
  }
}

function isoMonthEnd(monthsFromNow: number): string {
  const now = new Date()
  const d = new Date(now.getFullYear(), now.getMonth() + monthsFromNow + 1, 0)
  return d.toISOString().slice(0, 10)
}
