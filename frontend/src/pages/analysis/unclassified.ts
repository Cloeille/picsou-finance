import type { AxisUnclassifiedItem, Diversification } from '@/types/api'

export type DiversificationAxis = 'sectors' | 'countries'

export function axisUnclassifiedValue(data: Diversification, axis: DiversificationAxis): number {
  const breakdown = data[axis]
  if (breakdown.unclassifiedValueEur != null) {
    return breakdown.unclassifiedValueEur
  }
  return Math.max(0, Math.round((data.totalValueEur - breakdown.classifiedValueEur) * 100) / 100)
}

export function axisUnclassifiedItems(
  data: Diversification,
  axis: DiversificationAxis,
): AxisUnclassifiedItem[] {
  const breakdown = data[axis]
  if (breakdown.unclassified != null) {
    return breakdown.unclassified
  }
  return data.unclassified
    .filter((line) => (axis === 'sectors' ? line.sectorMissing : line.countryMissing))
    .map((line) => ({
      ticker: line.ticker,
      isin: line.isin ?? null,
      name: line.name,
      accountId: line.accountId ?? null,
      accountName: line.accountName ?? null,
      totalValueEur: line.valueEur,
      unclassifiedValueEur: line.valueEur,
      unclassifiedPercent: 100,
      reason: line.profileLooked ? 'NO_DATA' : 'NO_PROFILE',
    }))
}

/**
 * Picks the axis that backs the headline coverage and overall unclassified badge.
 * Uses the exact same comparison on the rounded axis totals as the backend so the
 * overall badge amount and default dialog tab always refer to the same axis.
 */
export function headlineAxis(data: Diversification): DiversificationAxis {
  if (data.countries.classifiedValueEur > data.sectors.classifiedValueEur) {
    return 'countries'
  }
  if (data.countries.classifiedValueEur < data.sectors.classifiedValueEur) {
    return 'sectors'
  }
  return axisUnclassifiedValue(data, 'countries') < axisUnclassifiedValue(data, 'sectors')
    ? 'countries'
    : 'sectors'
}

/**
 * Returns true when every unclassified axis entry for this ticker has reason
 * `INCOMPLETE_COMPOSITION` — meaning its ETF composition is known but only partly published, so a
 * single-sector/country manual override would overwrite the published breakdown.
 *
 * When another axis is missing for an actionable reason (`NO_PROFILE`, `LOOKUP_FAILED`,
 * `NO_DATA`), returns false so the user can still classify that axis.
 */
export function isIncompleteCompositionOnly(data: Diversification, ticker: string): boolean {
  const key = ticker.toUpperCase()
  const entries = [
    ...axisUnclassifiedItems(data, 'sectors'),
    ...axisUnclassifiedItems(data, 'countries'),
  ].filter((item) => item.ticker.toUpperCase() === key)
  return entries.length > 0 && entries.every((item) => item.reason === 'INCOMPLETE_COMPOSITION')
}
