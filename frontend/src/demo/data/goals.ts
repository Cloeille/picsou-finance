import type { Account, GoalMonthEntry, GoalProgress } from '@/types/api'
import { mockAccounts } from './accounts'
import { monthStartFromToday } from './dates'
import { mockBalanceHistory } from './history'

const round2 = (n: number) => Math.round(n * 100) / 100

function accountsById(ids: number[]): Account[] {
  return ids.map(id => mockAccounts.find(a => a.id === id)!)
}

/** ChronoUnit.MONTHS.between(today, date): whole months, as GoalService counts them. */
function wholeMonthsUntil(date: string): number {
  const now = new Date()
  const [y, m, d] = date.split('-').map(Number)
  const months = (y - now.getFullYear()) * 12 + (m - 1 - now.getMonth())
  return Math.max(0, d < now.getDate() ? months - 1 : months)
}

/**
 * What the linked accounts gained during the month `monthsAgo` back: the snapshot delta
 * GoalService.calculateActualForMonth reads. Null for the month in progress and later.
 */
function monthDelta(accounts: Account[], monthsAgo: number): number | null {
  if (monthsAgo < 1 || monthsAgo > 11) return null
  const start = 11 - monthsAgo
  return round2(accounts.reduce((sum, a) => {
    const balances = mockBalanceHistory[a.id]
    return sum + balances[start + 1] - balances[start]
  }, 0))
}

interface SavingsGoalSeed {
  id: number
  name: string
  targetAmount: number
  accountIds: number[]
  createdMonthsAgo: number
  deadlineMonthsAhead: number
}

interface SavingsGoal {
  progress: GoalProgress
  months: GoalMonthEntry[]
}

// Every figure follows from the linked accounts' balances and history the way GoalService
// computes it, so the badge, the average and the calendar always agree with each other.
function savingsGoal(seed: SavingsGoalSeed): SavingsGoal {
  const accounts = accountsById(seed.accountIds)
  const currentTotal = round2(accounts.reduce((sum, a) => sum + a.currentBalanceEur, 0))
  const deadline = monthStartFromToday(seed.deadlineMonthsAhead)
  const monthsLeft = wholeMonthsUntil(deadline)
  const needed = seed.targetAmount - currentTotal
  const monthlyNeeded = round2(monthsLeft > 0 ? needed / monthsLeft : needed)

  const months: GoalMonthEntry[] = []
  for (let offset = -seed.createdMonthsAgo; offset <= seed.deadlineMonthsAhead; offset++) {
    const actual = monthDelta(accounts, -offset)
    months.push({
      yearMonth: monthStartFromToday(offset).slice(0, 7),
      objective: monthlyNeeded,
      actual,
      manualActual: null,
      override: null,
      effective: actual,
    })
  }
  const past = months.filter(m => m.effective != null)
  const saved = past.reduce((sum, m) => sum + m.effective!, 0)
  const lastThree = past.slice(-3)
  const avgMonthlyContribution = round2(lastThree.reduce((sum, m) => sum + m.effective!, 0) / lastThree.length)

  return {
    months,
    progress: {
      id: seed.id,
      type: 'SAVINGS_TARGET',
      name: seed.name,
      targetAmount: seed.targetAmount,
      deadline,
      createdAt: `${monthStartFromToday(-seed.createdMonthsAgo)}T09:00:00Z`,
      historyStartMonth: null,
      monthlyAmount: null,
      expectedReturn: null,
      startDate: null,
      endDate: null,
      accounts,
      currentTotal,
      percentComplete: round2((currentTotal / seed.targetAmount) * 100),
      monthsLeft,
      monthlyNeeded,
      avgMonthlyContribution,
      isOnTrack: saved >= monthlyNeeded * past.length,
      surplus: round2(avgMonthlyContribution - monthlyNeeded),
      allocations: [],
    },
  }
}

const savingsGoals = [
  // The trip pocket fills a little faster than the plan asks: on track.
  savingsGoal({ id: 1, name: 'Voyage au Japon', targetAmount: 1400, accountIds: [9], createdMonthsAgo: 9, deadlineMonthsAhead: 9 }),
  savingsGoal({ id: 2, name: 'Apport immobilier', targetAmount: 19500, accountIds: [1, 7], createdMonthsAgo: 11, deadlineMonthsAhead: 30 }),
  // The wallet barely grows: behind.
  savingsGoal({ id: 3, name: 'Nouvelle voiture', targetAmount: 12000, accountIds: [8], createdMonthsAgo: 9, deadlineMonthsAhead: 16 }),
]

/** The calendar GET /goals/{id}/months answers; empty for a recurring plan, as on the backend. */
export const mockGoalMonths: Record<number, GoalMonthEntry[]> = Object.fromEntries(
  savingsGoals.map(goal => [goal.progress.id, goal.months]),
)

const pea = mockAccounts.find(a => a.id === 2)!

export const mockGoals: GoalProgress[] = [
  ...savingsGoals.map(goal => goal.progress),
  // A recurring plan, so the Goals page's second section and the projection both have content.
  {
    id: 4,
    type: 'RECURRING_INVESTMENT',
    name: 'DCA mensuel PEA',
    targetAmount: null,
    deadline: null,
    createdAt: `${monthStartFromToday(-20)}T09:00:00Z`,
    historyStartMonth: null,
    monthlyAmount: 300,
    expectedReturn: 7.5,
    startDate: monthStartFromToday(-19),
    endDate: null,
    accounts: [pea],
    currentTotal: pea.currentBalanceEur,
    percentComplete: null,
    monthsLeft: 0,
    monthlyNeeded: null,
    avgMonthlyContribution: null,
    isOnTrack: true,
    surplus: null,
    // Detailed on purpose, and only partly: 250 of the 300 is split, so the card's
    // "unallocated" row has something to show.
    allocations: [
      { ticker: 'AAPL', name: 'Apple Inc.', monthlyAmount: 150 },
      { ticker: 'MSFT', name: 'Microsoft Corp.', monthlyAmount: 100 },
    ],
  },
]
