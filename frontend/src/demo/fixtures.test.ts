import { AxiosHeaders } from 'axios'
import { describe, expect, it, vi } from 'vitest'
import type { FamilyDashboard } from '@/features/family/api'
import type { Account, BudgetSettings, DashboardData, GoalMonthEntry, GoalProgress } from '@/types/api'

// East of UTC, where a local-midnight date run through toISOString() slips to the day before.
// Set before the fixtures load, since they are built at import time.
vi.stubEnv('TZ', 'Europe/Paris')
const { createDemoAdapter } = await import('./index')
const { mockBalanceHistory } = await import('./data/history')

const adapter = createDemoAdapter()
async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await adapter({ method: 'GET', url, params, headers: new AxiosHeaders() })
  return response.data as T
}

// sv-SE formats a local date as YYYY-MM-DD.
const today = new Date().toLocaleDateString('sv-SE')
const currentMonth = today.slice(0, 7)

describe('demo fixtures', () => {
  it('opens the budget on the current month', async () => {
    const settings = await get<BudgetSettings>('/budget/settings')
    expect(settings.currentCycleStart).toBe(`${currentMonth}-01`)
    expect(settings.currentCycleEnd.slice(0, 7)).toBe(currentMonth)
  })

  it('reports the net worth the accounts page adds up', async () => {
    const [dashboard, accounts] = await Promise.all([get<DashboardData>('/dashboard'), get<Account[]>('/accounts')])
    // The Accounts page total: pockets skipped (already in their wallet), loans subtracted.
    const accountsTotal = accounts
      .filter((a) => a.parentAccountId == null)
      .reduce((sum, a) => (a.type === 'LOAN' ? sum - a.currentBalanceEur : sum + a.currentBalanceEur), 0)
    expect(dashboard.totalNetWorth).toBeCloseTo(accountsTotal, 2)
    const pnl = await get<{ total: number }>('/history/pnl')
    expect(pnl.total).toBeCloseTo(accountsTotal, 2)
  })

  it('lists only real accounts as liabilities, under their own name and balance', async () => {
    const [dashboard, accounts] = await Promise.all([get<DashboardData>('/dashboard'), get<Account[]>('/accounts')])
    for (const liability of dashboard.liabilities) {
      const account = accounts.find((a) => a.id === liability.accountId)
      expect(account?.name).toBe(liability.name)
      expect(Math.abs(account!.currentBalanceEur)).toBeCloseTo(liability.balanceEur, 2)
      if (liability.paymentDueDate) expect(liability.paymentDueDate >= today).toBe(true)
    }
  })

  it('values every account at its current balance on the latest history point', async () => {
    const accounts = await get<Account[]>('/accounts')
    const history = await get<{ accounts: Record<string, { total: number }> }[]>('/history', {
      accountIds: accounts.map((a) => a.id).join(','), months: 12, split: true,
    })
    const latest = history[history.length - 1]
    for (const account of accounts) {
      // HistoryService negates a loan's outstanding capital.
      const expected = account.type === 'LOAN' ? -account.currentBalanceEur : account.currentBalanceEur
      expect(latest.accounts[String(account.id)]?.total, account.name).toBeCloseTo(expected, 2)
    }
  })

  it('shows the family the same accounts and goals as everywhere else', async () => {
    const [family, accounts, goals] = await Promise.all([
      get<FamilyDashboard>('/family/dashboard'), get<Account[]>('/accounts'), get<GoalProgress[]>('/goals'),
    ])
    for (const shared of family.sharedAccounts) {
      const account = accounts.find((a) => a.id === shared.id)
      expect(shared.name).toBe(account?.name)
      expect(shared.balanceEur).toBe(account?.currentBalanceEur)
    }
    for (const shared of family.sharedGoals) {
      const goal = goals.find((g) => g.id === shared.id)
      expect(shared.name).toBe(goal?.name)
      expect(shared.currentTotal).toBe(goal?.currentTotal)
    }
  })

  describe.each([1, 2, 3])('savings goal %i', (id) => {
    it('totals its linked accounts and has a deadline ahead', async () => {
      const goal = await get<GoalProgress>(`/goals/${id}`)
      const linked = goal.accounts.reduce((sum, a) => sum + a.currentBalanceEur, 0)
      expect(goal.currentTotal).toBeCloseTo(linked, 2)
      expect(goal.deadline! > today).toBe(true)
    })

    it('returns the same calendar on every load', async () => {
      expect(await get(`/goals/${id}/months`)).toEqual(await get(`/goals/${id}/months`))
    })

    it('agrees with its calendar on whether it is on track', async () => {
      const goal = await get<GoalProgress>(`/goals/${id}`)
      const past = (await get<GoalMonthEntry[]>(`/goals/${id}/months`))
        .filter((m) => m.yearMonth < currentMonth && m.effective != null)
      // GoalService.isOnTrackFromPastMonths: saved so far against what the past months asked for.
      const saved = past.reduce((sum, m) => sum + m.effective!, 0)
      const asked = past.reduce((sum, m) => sum + m.objective, 0)
      expect(goal.isOnTrack).toBe(saved >= asked)
      expect(goal.avgMonthlyContribution! >= goal.monthlyNeeded!).toBe(goal.isOnTrack)
      // GoalService.calculateActualForMonth: each past month is the linked accounts' balance delta.
      expect(past.length).toBeGreaterThan(0)
      const [cy, cm] = currentMonth.split('-').map(Number)
      for (const m of past) {
        const [y, mo] = m.yearMonth.split('-').map(Number)
        const monthsAgo = (cy - y) * 12 + (cm - mo)
        const linkedDelta = goal.accounts.reduce(
          (sum, a) => sum + mockBalanceHistory[a.id][12 - monthsAgo] - mockBalanceHistory[a.id][11 - monthsAgo], 0)
        expect(m.effective).toBeCloseTo(linkedDelta, 2)
      }
    })
  })

  it('answers a path with a malformed escape like any unhandled call', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const response = await adapter({ method: 'GET', url: '/prices/BAD%/history', headers: new AxiosHeaders() })
      expect(response.status).toBe(200)
      expect(response.data).toEqual({})
    } finally {
      warn.mockRestore()
    }
  })

  it('signs the visitor in as the demo admin', async () => {
    const response = await adapter({ method: 'POST', url: '/auth/refresh', headers: new AxiosHeaders() })
    expect(response.data).toMatchObject({ username: 'demo', role: 'ADMIN', memberId: 1, displayName: 'Demo' })
  })
})
