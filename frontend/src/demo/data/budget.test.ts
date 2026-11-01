import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The demo budget data is computed at import time from "now", so each case pins the zone and
// the clock first, then imports a fresh copy of the module. Expected values use UTC arithmetic
// only, which is plain calendar math and cannot drift with the zone under test.

const ZONES = ['Europe/Paris', 'Asia/Tokyo', 'America/Los_Angeles']

const MOMENTS: { label: string; at: [number, number, number, number, number] }[] = [
  { label: 'last day of the month', at: [2026, 10, 31, 12, 0] },
  { label: 'just after local midnight', at: [2026, 10, 31, 0, 30] },
  { label: 'spring DST change', at: [2026, 3, 29, 12, 0] },
  { label: 'autumn DST change', at: [2026, 10, 25, 12, 0] },
  { label: 'last minutes of the year', at: [2026, 12, 31, 23, 30] },
]

function calendarIso(year: number, month: number, day: number): string {
  return new Date(Date.UTC(year, month - 1, day)).toISOString().slice(0, 10)
}

// Every monthly due date from `first` to `last` (inclusive), each one month after the previous,
// with the day clamped to the end of a shorter month like the backend's LocalDate.plusMonths(1).
function expectedMonthlyDueDates(first: string, last: string): string[] {
  const dates: string[] = []
  let [year, month, day] = first.split('-').map(Number)
  for (let due = first; due <= last; ) {
    dates.push(due)
    month += 1
    day = Math.min(day, new Date(Date.UTC(year, month, 0)).getUTCDate())
    due = calendarIso(year, month, day)
    ;[year, month] = due.split('-').map(Number)
  }
  return dates
}

async function loadDemoBudgetAt(tz: string, [y, mo, d, h, mi]: [number, number, number, number, number]) {
  vi.stubEnv('TZ', tz)
  vi.setSystemTime(new Date(y, mo - 1, d, h, mi))
  vi.resetModules()
  return import('./budget')
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllEnvs()
})

describe.each(ZONES)('demo budget dates in %s', (tz) => {
  it.each(MOMENTS)('the current cycle is the local calendar month on the $label', async ({ at }) => {
    const [y, mo] = at
    const { mockBudgetSettings, mockBudgets } = await loadDemoBudgetAt(tz, at)

    expect(mockBudgetSettings.currentCycleStart).toBe(calendarIso(y, mo, 1))
    expect(mockBudgetSettings.currentCycleEnd).toBe(calendarIso(y, mo + 1, 0))
    expect(mockBudgets[0].cycleStart).toBe(mockBudgetSettings.currentCycleStart)
    expect(mockBudgets[0].cycleEnd).toBe(mockBudgetSettings.currentCycleEnd)
  })

  it.each(MOMENTS)('upcoming occurrences fall on the series due dates on the $label', async ({ at }) => {
    const [y, mo, d] = at
    const { mockRecurring, mockCalendar } = await loadDemoBudgetAt(tz, at)

    const netflix = mockRecurring.find((s) => s.label === 'Netflix')
    expect(netflix?.nextDueDate).toBe(calendarIso(y, mo, d + 6))

    const horizonDays = 60
    const horizonEnd = calendarIso(y, mo, d + horizonDays)
    const occurrences = mockCalendar(horizonDays)
    for (const series of mockRecurring.filter((s) => s.status !== 'IGNORED')) {
      const dueDates = occurrences.filter((o) => o.seriesId === series.id).map((o) => o.dueDate)
      expect(dueDates, series.label).toEqual(expectedMonthlyDueDates(series.nextDueDate!, horizonEnd))
    }
  })
})
