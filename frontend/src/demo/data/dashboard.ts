import type { DashboardData } from '@/types/api'
import { LIABILITY_ACCOUNT_TYPES } from '@/lib/constants'
import { mockAccounts } from './accounts'
import { mockGoals } from './goals'

function generateNetWorthHistory(): { date: string; total: number; invested: number; pnl: number }[] {
  const baseValues = [32000, 33500, 34200, 35100, 34800, 36400, 37200, 38100, 39500, 40200, 41000, 41862]
  const investedValues = [30000, 31500, 32000, 32800, 32800, 34000, 34500, 35000, 36000, 36500, 37000, 37500]
  const now = new Date()
  const months: { date: string; total: number; invested: number; pnl: number }[] = []

  for (let i = 11; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const total = baseValues[11 - i]
    const invested = investedValues[11 - i]
    months.push({
      date: d.toISOString().split('T')[0],
      total,
      invested,
      pnl: total - invested,
    })
  }

  return months
}

export const mockDashboard: DashboardData = {
  totalNetWorth: 41862.35,
  totalLiabilities: 9784.6,
  totalMonthlyPayment: 250,
  netWorthHistory: generateNetWorthHistory(),
  distribution: mockAccounts.filter(a => !LIABILITY_ACCOUNT_TYPES.includes(a.type)).map(a => ({
    accountId: a.id,
    name: a.name,
    color: a.color,
    balanceEur: a.currentBalanceEur,
    percentage: Math.round((a.currentBalanceEur / 41862.35) * 1000) / 10,
    accountType: a.type,
    hasHoldings: ['PEA', 'COMPTE_TITRES', 'CRYPTO'].includes(a.type),
  })),
  // A configured loan and the demo card, so demo mode exercises both liability rows
  // (issues #18, #197). Optional fields are omitted, as the backend does for nulls.
  liabilities: [
    {
      accountId: 100,
      name: 'Car loan',
      color: '#ef4444',
      balanceEur: 8500,
      percentage: 86.87,
      accountType: 'LOAN',
      hasHoldings: false,
      monthlyPayment: 250,
      percentPaid: 55,
    },
    {
      accountId: 12,
      name: 'Carte Air France KLM',
      color: '#2e77bc',
      balanceEur: 1284.6,
      percentage: 13.13,
      accountType: 'CREDIT_CARD',
      hasHoldings: false,
      paymentDueAmountEur: 912.4,
      paymentDueDate: '2025-04-05',
    },
  ],
  goalSummaries: mockGoals,
}
