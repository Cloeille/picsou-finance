export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

/** Add/subtract n days from a YYYY-MM-DD string. Uses UTC arithmetic to avoid DST drift. */
export function addDays(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  const date = new Date(Date.UTC(y, m - 1, d + n))
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`
}

/** Extract the 4-digit year from a YYYY-MM-DD string. */
export function yearOf(iso: string): number {
  return Number(iso.slice(0, 4))
}

/**
 * Safe YTD anchor for year y.
 * Past years  → December 31 of that year (the full year is over).
 * Current/future → todayIso (can't navigate beyond today).
 */
export function yearAnchor(y: number, currentYear: number, todayIso: string): string {
  return y < currentYear ? `${y}-12-31` : todayIso
}

/** Cycle start-of-period anchor for year y, month m (1-based), start day. */
export function cycleAnchor(y: number, m: number, cycleStartDay: number): string {
  return `${y}-${pad2(m)}-${pad2(cycleStartDay)}`
}
