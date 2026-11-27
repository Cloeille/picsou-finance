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
      isin: null,
      name: 'Actions Monde (FCPE)',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
      valueEur: 8200,
      sectorMissing: true,
      countryMissing: true,
      profileLooked: false,
    },
    {
      ticker: 'ETF-PARTIAL',
      isin: 'LU2090062436',
      name: 'Amundi Private Assets ETF',
      accountId: 2,
      accountName: 'PEA BoursoBank',
      valueEur: 6000,
      sectorMissing: true,
      countryMissing: false,
      profileLooked: true,
    },
    {
      ticker: 'MC.PA',
      isin: 'FR0000121014',
      name: 'LVMH',
      accountId: 3,
      accountName: 'Compte Titres Degiro',
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
    // Under the 50% published-composition floor, IWDA's top-N tail sits in the classified "other"
    // slice while ETF-PARTIAL (35% published sectors) leaves 65% (3 900 €) unclassified.
    classifiedValueEur: 130300,
    unclassifiedValueEur: 12100,
    coveragePercent: 91.5,
    unclassified: [
      {
        ticker: 'FCPE-DEMO',
        isin: null,
        name: 'Actions Monde (FCPE)',
        accountId: 3,
        accountName: 'Compte Titres Degiro',
        totalValueEur: 8200,
        unclassifiedValueEur: 8200,
        unclassifiedPercent: 100,
        reason: 'NO_PROFILE',
      },
      {
        ticker: 'ETF-PARTIAL',
        isin: 'LU2090062436',
        name: 'Amundi Private Assets ETF',
        accountId: 2,
        accountName: 'PEA BoursoBank',
        totalValueEur: 6000,
        unclassifiedValueEur: 3900,
        unclassifiedPercent: 65,
        reason: 'INCOMPLETE_COMPOSITION',
      },
    ],
    slices: [
      { label: 'technology', percent: 28.5, valueEur: 37135.5, contributorCount: 3,
        contributors: [{ ticker: 'IWDA', valueEur: 26737.56, sharePercent: 72.0 }, { ticker: 'AAPL', valueEur: 6684.39, sharePercent: 18.0 }, { ticker: 'MSFT', valueEur: 3713.55, sharePercent: 10.0 }] },
      { label: 'financial_services', percent: 16.5, valueEur: 21499.5, contributorCount: 2,
        contributors: [{ ticker: 'IWDA', valueEur: 13114.7, sharePercent: 61.0 }, { ticker: 'AXP', valueEur: 8384.8, sharePercent: 39.0 }] },
      { label: 'healthcare', percent: 12.5, valueEur: 16287.5, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 16287.5, sharePercent: 100.0 }] },
      { label: 'consumer_cyclical', percent: 10.5, valueEur: 13681.5, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 13681.5, sharePercent: 100.0 }] },
      { label: 'industrials', percent: 9.0, valueEur: 11727.0, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 11727.0, sharePercent: 100.0 }] },
      { label: 'energy', percent: 5.5, valueEur: 7166.5, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 7166.5, sharePercent: 100.0 }] },
      { label: 'basic_materials', percent: 3.8, valueEur: 4951.4, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 4951.4, sharePercent: 100.0 }] },
      { label: 'utilities', percent: 2.8, valueEur: 3648.4, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 3648.4, sharePercent: 100.0 }] },
      { label: 'real_estate', percent: 2.1, valueEur: 2736.3, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 2736.3, sharePercent: 100.0 }] },
      { label: 'other', percent: 8.8, valueEur: 11466.4, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 11466.4, sharePercent: 100.0 }] },
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
    unclassifiedValueEur: 10600,
    coveragePercent: 92.56,
    unclassified: [
      {
        ticker: 'FCPE-DEMO',
        isin: null,
        name: 'Actions Monde (FCPE)',
        accountId: 3,
        accountName: 'Compte Titres Degiro',
        totalValueEur: 8200,
        unclassifiedValueEur: 8200,
        unclassifiedPercent: 100,
        reason: 'NO_PROFILE',
      },
      {
        ticker: 'MC.PA',
        isin: 'FR0000121014',
        name: 'LVMH',
        accountId: 3,
        accountName: 'Compte Titres Degiro',
        totalValueEur: 2400,
        unclassifiedValueEur: 2400,
        unclassifiedPercent: 100,
        reason: 'NO_DATA',
      },
    ],
    slices: [
      { label: 'US', percent: 60.2, valueEur: 79343.6, contributorCount: 4,
        contributors: [{ ticker: 'IWDA', valueEur: 52366.78, sharePercent: 66.0 }, { ticker: 'AAPL', valueEur: 15868.72, sharePercent: 20.0 }, { ticker: 'MSFT', valueEur: 7140.92, sharePercent: 9.0 }, { ticker: null, valueEur: 3967.18, sharePercent: 5.0 }] },
      { label: 'FR', percent: 14.3, valueEur: 18847.4, contributorCount: 2,
        contributors: [{ ticker: 'MC.PA', valueEur: 10931.49, sharePercent: 58.0 }, { ticker: 'AI.PA', valueEur: 7915.91, sharePercent: 42.0 }] },
      { label: 'JP', percent: 6.8, valueEur: 8962.4, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 8962.4, sharePercent: 100.0 }] },
      { label: 'GB', percent: 5.2, valueEur: 6853.6, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 6853.6, sharePercent: 100.0 }] },
      { label: 'DE', percent: 4.4, valueEur: 5799.2, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 5799.2, sharePercent: 100.0 }] },
      { label: 'NL', percent: 3.1, valueEur: 4085.8, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 4085.8, sharePercent: 100.0 }] },
      { label: 'CH', percent: 2.5, valueEur: 3295.0, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 3295.0, sharePercent: 100.0 }] },
      { label: 'other', percent: 3.5, valueEur: 4613.0, contributorCount: 1,
        contributors: [{ ticker: 'IWDA', valueEur: 4613.0, sharePercent: 100.0 }] },
    ],
  },
}

export interface DemoHoldingClassification {
  wealthTier: string | null
  sectorKey: string | null
  countryKey: string | null
}

function round2(n: number): number {
  return Math.round(n * 100) / 100
}

function mergeSliceContribution(
  slices: Diversification['sectors']['slices'],
  label: string,
  ticker: string,
  amount: number,
  classifiedTotal: number,
): Diversification['sectors']['slices'] {
  const next = slices.map((slice) => ({
    ...slice,
    contributors: slice.contributors.map((c) => ({ ...c })),
  }))
  const existing = next.find((s) => s.label === label)
  if (existing) {
    existing.valueEur = round2(existing.valueEur + amount)
    const contrib = existing.contributors.find((c) => c.ticker === ticker)
    if (contrib) {
      contrib.valueEur = round2(contrib.valueEur + amount)
    } else {
      existing.contributors.push({ ticker, valueEur: round2(amount), sharePercent: 0 })
      existing.contributorCount += 1
    }
    for (const c of existing.contributors) {
      c.sharePercent = existing.valueEur > 0 ? round2((c.valueEur / existing.valueEur) * 100) : 0
    }
  } else {
    next.push({
      label,
      percent: 0,
      valueEur: round2(amount),
      contributorCount: 1,
      contributors: [{ ticker, valueEur: round2(amount), sharePercent: 100 }],
    })
  }
  for (const slice of next) {
    slice.percent = classifiedTotal > 0 ? round2((slice.valueEur / classifiedTotal) * 100) : 0
  }
  next.sort((a, b) => {
    const aOther = a.label === 'other' ? 1 : 0
    const bOther = b.label === 'other' ? 1 : 0
    return aOther - bOther || b.percent - a.percent
  })
  return next
}

/**
 * Applies mutable demo holding classifications onto a base diversification fixture so saving
 * a classification in demo mode immediately removes the line from the classified axis and
 * updates amounts and coverage.
 */
export function applyDemoClassifications(
  base: Diversification,
  overrides: ReadonlyMap<string, DemoHoldingClassification>,
): Diversification {
  if (overrides.size === 0) return base

  let totalValueEur = base.totalValueEur
  let sectorUnclassified = [...(base.sectors.unclassified ?? [])]
  let countryUnclassified = [...(base.countries.unclassified ?? [])]
  let sectorSlices = base.sectors.slices.map((s) => ({
    ...s,
    contributors: s.contributors.map((c) => ({ ...c })),
  }))
  let countrySlices = base.countries.slices.map((s) => ({
    ...s,
    contributors: s.contributors.map((c) => ({ ...c })),
  }))
  let securities = base.securities.map((s) => ({ ...s }))
  let topUnclassified = base.unclassified.map((u) => ({ ...u }))

  for (const [rawTicker, override] of overrides) {
    const ticker = rawTicker.toUpperCase()
    const matchTicker = (t: string) => t.toUpperCase() === ticker

    if (override.wealthTier != null && override.wealthTier !== 'EQUITY') {
      const removedValue =
        securities.find((s) => matchTicker(s.ticker))?.valueEur ??
        topUnclassified.find((u) => matchTicker(u.ticker))?.valueEur ??
        sectorUnclassified.find((u) => matchTicker(u.ticker))?.totalValueEur ??
        countryUnclassified.find((u) => matchTicker(u.ticker))?.totalValueEur ??
        0
      totalValueEur = round2(Math.max(0, totalValueEur - removedValue))
      sectorUnclassified = sectorUnclassified.filter((u) => !matchTicker(u.ticker))
      countryUnclassified = countryUnclassified.filter((u) => !matchTicker(u.ticker))
      topUnclassified = topUnclassified.filter((u) => !matchTicker(u.ticker))
      securities = securities.filter((s) => !matchTicker(s.ticker))
      continue
    }

    if (override.sectorKey) {
      const removed = sectorUnclassified.find((u) => matchTicker(u.ticker))
      if (removed) {
        sectorUnclassified = sectorUnclassified.filter((u) => !matchTicker(u.ticker))
        const nextUncl = round2(
          sectorUnclassified.reduce((sum, u) => sum + u.unclassifiedValueEur, 0),
        )
        const nextClassified = round2(Math.max(0, totalValueEur - nextUncl))
        sectorSlices = mergeSliceContribution(
          sectorSlices,
          override.sectorKey,
          removed.ticker,
          removed.unclassifiedValueEur,
          nextClassified,
        )
        if (!securities.some((s) => matchTicker(s.ticker))) {
          securities.push({
            ticker: removed.ticker,
            name: removed.name,
            accountId: removed.accountId ?? null,
            valueEur: removed.totalValueEur,
          })
        }
      }
    }

    if (override.countryKey) {
      const removed = countryUnclassified.find((u) => matchTicker(u.ticker))
      if (removed) {
        countryUnclassified = countryUnclassified.filter((u) => !matchTicker(u.ticker))
        const nextUncl = round2(
          countryUnclassified.reduce((sum, u) => sum + u.unclassifiedValueEur, 0),
        )
        const nextClassified = round2(Math.max(0, totalValueEur - nextUncl))
        countrySlices = mergeSliceContribution(
          countrySlices,
          override.countryKey,
          removed.ticker,
          removed.unclassifiedValueEur,
          nextClassified,
        )
        if (!securities.some((s) => matchTicker(s.ticker))) {
          securities.push({
            ticker: removed.ticker,
            name: removed.name,
            accountId: removed.accountId ?? null,
            valueEur: removed.totalValueEur,
          })
        }
      }
    }

    topUnclassified = topUnclassified
      .map((line) => {
        if (!matchTicker(line.ticker)) return line
        return {
          ...line,
          sectorMissing: line.sectorMissing && !override.sectorKey,
          countryMissing: line.countryMissing && !override.countryKey,
        }
      })
      .filter((line) => line.sectorMissing || line.countryMissing)
  }

  const sectorUnclEur = round2(
    sectorUnclassified.reduce((sum, item) => sum + item.unclassifiedValueEur, 0),
  )
  const sectorClassifiedEur = round2(Math.max(0, totalValueEur - sectorUnclEur))
  const sectorCoverage =
    totalValueEur > 0 ? round2((sectorClassifiedEur / totalValueEur) * 100) : 0

  const countryUnclEur = round2(
    countryUnclassified.reduce((sum, item) => sum + item.unclassifiedValueEur, 0),
  )
  const countryClassifiedEur = round2(Math.max(0, totalValueEur - countryUnclEur))
  const countryCoverage =
    totalValueEur > 0 ? round2((countryClassifiedEur / totalValueEur) * 100) : 0

  const sectors = {
    ...base.sectors,
    classifiedValueEur: sectorClassifiedEur,
    unclassifiedValueEur: sectorUnclEur,
    coveragePercent: sectorCoverage,
    slices: sectorSlices,
    unclassified: sectorUnclassified,
  }
  const countries = {
    ...base.countries,
    classifiedValueEur: countryClassifiedEur,
    unclassifiedValueEur: countryUnclEur,
    coveragePercent: countryCoverage,
    slices: countrySlices,
    unclassified: countryUnclassified,
  }
  const lead =
    countries.classifiedValueEur > sectors.classifiedValueEur ||
    (countries.classifiedValueEur === sectors.classifiedValueEur &&
      countries.unclassifiedValueEur < sectors.unclassifiedValueEur)
      ? countries
      : sectors

  return {
    totalValueEur,
    classifiedValueEur: lead.classifiedValueEur,
    unclassifiedValueEur: lead.unclassifiedValueEur,
    coveragePercent: lead.coveragePercent,
    unclassified: topUnclassified,
    sectors,
    countries,
    securities,
  }
}

/**
 * Generated rather than hand-written: twenty years x four scenarios is 84 points, and the shape
 * that matters (each curve above the previous, contributions growing linearly) is the arithmetic
 * itself.
 */
export function mockProjection(
  years: number,
  options?: {
    monthlyInflowEur?: number
    detectedMonthlyInflowEur?: number | null
    detectedInflowPrefillAmountEur?: number | null
    detectedInflowActiveMonths?: number | null
    detectedInflowAccountId?: number | null
  },
): Projection {
  const base = 96400
  const monthly = options?.monthlyInflowEur ?? 300
  const detectedMonthlyInflowEur =
    options?.detectedMonthlyInflowEur === undefined ? 200 : options.detectedMonthlyInflowEur
  const detectedInflowPrefillAmountEur =
    options?.detectedInflowPrefillAmountEur === undefined
      ? detectedMonthlyInflowEur
      : options.detectedInflowPrefillAmountEur
  const detectedInflowActiveMonths =
    options?.detectedInflowActiveMonths === undefined ? 3 : options.detectedInflowActiveMonths
  const detectedInflowAccountId =
    options?.detectedInflowAccountId === undefined ? 2 : options.detectedInflowAccountId
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
    ...(detectedMonthlyInflowEur != null
      ? {
          detectedMonthlyInflowEur,
          ...(detectedInflowPrefillAmountEur != null ? { detectedInflowPrefillAmountEur } : {}),
          detectedInflowActiveMonths,
          detectedInflowAccountId,
        }
      : {}),
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
