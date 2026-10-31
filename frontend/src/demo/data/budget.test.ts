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

    const occurrences = mockCalendar(60)
    for (const series of mockRecurring.filter((s) => s.status !== 'IGNORED')) {
      const first = occurrences.find((o) => o.seriesId === series.id)
      expect(first?.dueDate, series.label).toBe(series.nextDueDate)
    }
  })
})
