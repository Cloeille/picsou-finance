import { AxiosHeaders } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Transaction } from '@/types/api'
import { mockCategories, mockSpendingByCategory } from './data/budget'
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

  it('lists the period newest first, each row tied to a category and an account', async () => {
    const rows = await search({ from, to })

    expect(rows.length).toBeGreaterThan(0)
    expect(rows.every((r) => r.date >= from && r.date <= to)).toBe(true)
    expect(rows.map((r) => r.date)).toEqual([...rows.map((r) => r.date)].sort().reverse())
    expect(rows.every((r) => r.categoryId != null && r.accountId != null && r.accountName)).toBe(true)
  })

  it('filters by category, by account, and returns nothing outside the period', async () => {
    const all = await search({ from, to })
    const categoryId = all[0].categoryId!
    const byCategory = await search({ from, to, categoryId })
    expect(byCategory.length).toBeGreaterThan(0)
    expect(byCategory.every((r) => r.categoryId === categoryId)).toBe(true)

    expect(await search({ from, to, accountId: all[0].accountId })).toHaveLength(all.length)
    expect(await search({ from, to, accountId: 99 })).toEqual([])
    expect(await search({ from: '2000-01-01', to: '2000-01-31' })).toEqual([])
  })

  it('only holds the categories the spending breakdown shows, with the same row counts', async () => {
    const spent = mockSpendingByCategory('CYCLE').categories.filter((c) => c.categoryId != null)
    const rows = await search(currentMonthRange(now))

    expect([...new Set(rows.map((r) => r.categoryId))].sort()).toEqual(spent.map((c) => c.categoryId).sort())
    for (const c of spent) {
      expect(rows.filter((r) => r.categoryId === c.categoryId), c.name!).toHaveLength(c.count)
    }
  })

  // The demo module captures "today" at import, so each case loads a fresh copy under a fixed clock.
  describe.each([
    ['the 1st, just after midnight', new Date(2026, 2, 1, 0, 30)],
    ['the 2nd', new Date(2026, 2, 2, 0, 30)],
    ['the last evening of the month', new Date(2026, 0, 31, 23, 30)],
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
})
