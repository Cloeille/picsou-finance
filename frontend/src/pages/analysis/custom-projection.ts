import type { ProjectionPoint } from '@/types/api'

export const MAX_CUSTOM_RATE_PERCENT = 100

/**
 * Parses a user-entered annual return percentage (accepting both "," and "." decimals).
 * Returns null for empty, non-numeric, negative, or out-of-range inputs (> 100 %).
 */
export function parseCustomRate(raw: string): number | null {
  const trimmed = raw.trim().replace(',', '.')
  if (trimmed === '') return null
  if (!/^\d+(?:\.\d*)?$/.test(trimmed)) return null
  const value = Number(trimmed)
  if (!Number.isFinite(value) || value < 0 || value > MAX_CUSTOM_RATE_PERCENT) return null
  return value
}

/**
 * Projects the investable portfolio at a single user-chosen annual return rate,
 * using the same geometric monthly compounding as backend `ProjectionService`:
 * `monthlyRate = (1 + r)^(1/12) - 1`, applied before each month's contribution.
 *
 * Uses the yearly `contributedEur` schedule returned by the backend so recurring
 * plan start/end dates and joint-account share weights are preserved accurately.
 */
export function projectCustomRate(
  referencePoints: readonly ProjectionPoint[],
  annualPercent: number,
): ProjectionPoint[] {
  if (referencePoints.length === 0) return []

  const monthlyRate = annualPercent === 0 ? 0 : Math.pow(1 + annualPercent / 100, 1 / 12) - 1
  const out: ProjectionPoint[] = [
    {
      date: referencePoints[0].date,
      valueEur: referencePoints[0].contributedEur,
      contributedEur: referencePoints[0].contributedEur,
    },
  ]

  let balance = referencePoints[0].contributedEur
  for (let i = 1; i < referencePoints.length; i++) {
    const prevContributed = referencePoints[i - 1].contributedEur
    const currContributed = referencePoints[i].contributedEur
    const monthlyContribution = Math.max(0, currContributed - prevContributed) / 12

    for (let m = 0; m < 12; m++) {
      balance = balance * (1 + monthlyRate) + monthlyContribution
    }

    out.push({
      date: referencePoints[i].date,
      valueEur: Math.round(balance * 100) / 100,
      contributedEur: currContributed,
    })
  }

  return out
}
