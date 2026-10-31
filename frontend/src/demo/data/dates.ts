// Demo fixtures are anchored on the visitor's today, so the demo never shows a stale date.
// Calendar dates are formatted from local parts: `toISOString()` on a local-midnight Date
// lands on the previous day anywhere east of UTC.

export function isoDate(d: Date): string {
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}

export function daysFromToday(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return isoDate(d)
}

/** The first day of the month `months` away from the current one. */
export function monthStartFromToday(months: number): string {
  const now = new Date()
  return isoDate(new Date(now.getFullYear(), now.getMonth() + months, 1))
}

export function hoursAgo(hours: number): string {
  return new Date(Date.now() - hours * 3_600_000).toISOString()
}
