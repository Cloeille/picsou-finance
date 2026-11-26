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
      accountId: line.accountId,
      accountName: line.accountName ?? null,
      totalValueEur: line.valueEur,
      unclassifiedValueEur: line.valueEur,
      unclassifiedPercent: 100,
      reason: line.profileLooked ? 'NO_DATA' : 'NO_PROFILE',
    }))
}
