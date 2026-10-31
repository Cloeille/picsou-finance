import type { DashboardData } from '@/types/api'
import { LIABILITY_ACCOUNT_TYPES } from '@/lib/constants'
import { mockAccounts } from './accounts'
import { mockGoals } from './goals'
import { netWorthHistory } from './history'

// Derived from the accounts with DashboardService's rule, so the dashboard and the Accounts page
// report one net worth: pockets are skipped (their wallet already counts them), and a liability
// is the positive amount owed.
const topLevel = mockAccounts.filter(a => a.parentAccountId == null)
const assets = topLevel.filter(a => !LIABILITY_ACCOUNT_TYPES.includes(a.type))
const liabilities = topLevel.filter(a => LIABILITY_ACCOUNT_TYPES.includes(a.type))

const round2 = (n: number) => Math.round(n * 100) / 100
const totalAssets = round2(assets.reduce((sum, a) => sum + a.currentBalanceEur, 0))
const totalLiabilities = round2(liabilities.reduce((sum, a) => sum + Math.abs(a.currentBalanceEur), 0))

export const mockDashboard: DashboardData = {
  totalNetWorth: round2(totalAssets - totalLiabilities),
  totalLiabilities,
  totalMonthlyPayment: liabilities.reduce((sum, a) => sum + (a.debt?.monthlyPayment ?? 0), 0),
  netWorthHistory: netWorthHistory(topLevel.map(a => a.id), 12, false),
  distribution: assets.map(a => ({
    accountId: a.id,
    name: a.name,
    color: a.color,
    balanceEur: a.currentBalanceEur,
    percentage: Math.round((a.currentBalanceEur / totalAssets) * 1000) / 10,
    accountType: a.type,
    hasHoldings: ['PEA', 'COMPTE_TITRES', 'CRYPTO'].includes(a.type),
  })),
  // A configured loan and the demo card, so demo mode exercises both liability rows
  // (issues #18, #197). Optional fields are omitted, as the backend does for nulls.
  liabilities: liabilities.map(a => {
    const owed = Math.abs(a.currentBalanceEur)
    const row = {
      accountId: a.id,
      name: a.name,
      color: a.color,
      balanceEur: owed,
      percentage: round2((owed / totalLiabilities) * 100),
      accountType: a.type,
      hasHoldings: false,
    }
    if (a.debt) {
      return {
        ...row,
        monthlyPayment: a.debt.monthlyPayment ?? undefined,
        percentPaid: Math.round(((a.debt.borrowedAmount - owed) / a.debt.borrowedAmount) * 100),
      }
    }
    return {
      ...row,
      paymentDueAmountEur: a.paymentDueAmount ?? undefined,
      paymentDueDate: a.paymentDueDate ?? undefined,
    }
  }),
  goalSummaries: mockGoals,
}
