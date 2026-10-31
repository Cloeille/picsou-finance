import { AxiosHeaders } from 'axios'
import { describe, expect, it } from 'vitest'
import type { Transaction } from '@/types/api'
import { createDemoAdapter } from './index'

function iso(d: Date): string {
  return d.toISOString().split('T')[0]
}

describe('demo cross-account transaction search', () => {
  const adapter = createDemoAdapter()
  const search = async (params: Record<string, unknown>) =>
    (await adapter({ method: 'GET', url: '/transactions', headers: new AxiosHeaders(), params }))
      .data as Transaction[]

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
})
