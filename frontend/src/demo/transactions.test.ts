import { AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Transaction } from '@/types/api'
import { mockCategories, mockSpendingByCategory, mockUncategorized } from './data/budget'
import { createDemoAdapter } from './index'

function iso(d: Date): string {
  return d.toISOString().split('T')[0]
}

function localDay(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

type Adapter = ReturnType<typeof createDemoAdapter>

const searchWith = (adapter: Adapter) => async (params: Record<string, unknown>) =>
  (await adapter({ method: 'GET', url: '/transactions', headers: new AxiosHeaders(), params }))
    .data as Transaction[]

// The default range of BudgetTransactionsPage: the current calendar month, in local time.
function currentMonthRange(now: Date) {
  return {
    from: localDay(new Date(now.getFullYear(), now.getMonth(), 1)),
    to: localDay(new Date(now.getFullYear(), now.getMonth() + 1, 0)),
  }
}

describe('demo cross-account transaction search', () => {
  const adapter = createDemoAdapter()
  const search = searchWith(adapter)

  const now = new Date()
  const from = iso(new Date(now.getFullYear(), now.getMonth() - 1, 1))
  const to = iso(now)

  it('lists the period newest first with accounts, known categories and the review inbox', async () => {
    const rows = await search({ from, to })

    expect(rows.length).toBeGreaterThan(0)
    expect(rows.every((r) => r.date >= from && r.date <= to)).toBe(true)
    expect(rows.map((r) => r.date)).toEqual([...rows.map((r) => r.date)].sort().reverse())
    expect(rows.every((r) => r.accountId != null && r.accountName)).toBe(true)
    expect(rows.every((r) => r.categoryId == null || mockCategories.some((c) => c.id === r.categoryId))).toBe(true)
    expect(rows.filter((r) => r.categoryId == null).map((r) => r.id).sort()).toEqual(
      mockUncategorized().filter((r) => r.date >= from && r.date <= to).map((r) => r.id).sort(),
    )
  })

  it('filters by category, by account, and returns nothing outside the period', async () => {
    const all = await search({ from, to })
    const categoryId = all.find((row) => row.categoryId != null)?.categoryId
    expect(categoryId).toBeDefined()
    const byCategory = await search({ from, to, categoryId })
    expect(byCategory.length).toBeGreaterThan(0)
    expect(byCategory.every((r) => r.categoryId === categoryId)).toBe(true)

    expect(await search({ from, to, accountId: all[0].accountId })).toHaveLength(all.length)
    expect(await search({ from, to, accountId: 99 })).toEqual([])
    expect(await search({ from: '2000-01-01', to: '2000-01-31' })).toEqual([])
  })

  it('lists every spending category, including uncategorized, with the same counts and amounts', async () => {
    const spent = mockSpendingByCategory('CYCLE').categories
    const rows = (await search(currentMonthRange(now))).filter((r) => r.amount < 0)

    expect([...new Set(rows.map((r) => r.categoryId))].sort()).toEqual(spent.map((c) => c.categoryId).sort())
    for (const c of spent) {
      expect(rows.filter((r) => r.categoryId === c.categoryId), c.name!).toHaveLength(c.count)
      expect(-rows.filter((r) => r.categoryId === c.categoryId).reduce((sum, r) => sum + r.amount, 0)).toBeCloseTo(c.amount, 2)
    }
  })

  // The demo module captures "today" at import, so each case loads a fresh copy under a fixed clock.
  describe.each([
    ['the 1st, just after midnight', new Date(2026, 2, 1, 0, 30)],
    ['the 2nd', new Date(2026, 2, 2, 0, 30)],
    ['the last evening of the month', new Date(2026, 0, 31, 23, 30)],
    ['October 31', new Date(2026, 9, 31, 12)],
    ['November 1', new Date(2026, 10, 1, 12)],
    ['January 1', new Date(2027, 0, 1, 12)],
  ])('on %s', (_, clock) => {
    afterEach(() => {
      vi.useRealTimers()
      vi.resetModules()
    })

    it('fills the current month with rows dated between its 1st and today', async () => {
      // Only Date is faked: the adapter's response delay needs the real timers.
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(clock)
      vi.resetModules()
      const { createDemoAdapter: createAtClock } = await import('./index')

      const range = currentMonthRange(clock)
      const rows = await searchWith(createAtClock())(range)

      expect(rows.length).toBeGreaterThan(0)
      expect(rows.every((r) => r.date >= range.from && r.date <= localDay(clock))).toBe(true)
      const categoryId = rows.find((row) => row.categoryId != null)?.categoryId
      expect(categoryId).toBeDefined()
      const filtered = await searchWith(createAtClock())({ ...range, categoryId })
      expect(filtered.length).toBeGreaterThan(0)
      expect(filtered.every((row) => row.categoryId === categoryId)).toBe(true)
    })
  })

  it('keeps a row in its new category after it is categorized', async () => {
    const [row] = (await search({ from, to })).filter((r) => r.categoryId === 2)
    const target = mockCategories.find((c) => c.id === 7)!

    await adapter({
      method: 'PUT',
      url: `/transactions/${row.id}/category`,
      headers: new AxiosHeaders(),
      data: JSON.stringify({ categoryId: target.id, createRule: false }),
    })

    const moved = (await search({ from, to })).find((r) => r.id === row.id)
    expect(moved).toMatchObject({ categoryId: target.id, category: target.name })
    expect((await search({ from, to, categoryId: target.id })).map((r) => r.id)).toContain(row.id)
    expect((await search({ from, to, categoryId: 2 })).map((r) => r.id)).not.toContain(row.id)
  })

  it('keeps a prior cycle searchable and editable alongside current income and the inbox', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 31, 12))
    vi.resetModules()
    try {
      const { createDemoAdapter: createAtClock } = await import('./index')
      const atClock = createAtClock()
      const searchAtClock = searchWith(atClock)
      const range = { from: '2026-09-01', to: '2026-09-30' }
      const before = await searchAtClock(range)
      const row = before.find((r) => r.categoryId === 2)!
      expect(row).toBeDefined()

      await atClock({
        method: 'PUT',
        url: `/transactions/${row.id}/category`,
        headers: new AxiosHeaders(),
        data: JSON.stringify({ categoryId: 7, createRule: false }),
      })

      const after = await searchAtClock(range)
      expect(after.map((r) => r.id)).toEqual(before.map((r) => r.id))
      expect(after.find((r) => r.id === row.id)).toMatchObject({ categoryId: 7, category: 'Restaurants' })
      const current = await searchAtClock({ from: '2026-10-01', to: '2026-10-31' })
      expect(current.some((r) => r.amount > 0 && r.categoryId === 1)).toBe(true)
      expect(current.filter((r) => r.categoryId == null).map((r) => r.id).sort()).toEqual([9001, 9002, 9003])
    } finally {
      vi.useRealTimers()
      vi.resetModules()
    }
  })
})
