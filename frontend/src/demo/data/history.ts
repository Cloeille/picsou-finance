import { LIABILITY_ACCOUNT_TYPES } from '@/lib/constants'
import { mockAccounts } from './accounts'
import { daysBeforeToday, isoDate, monthStartFromToday } from './dates'
import { holdingsCostBasis, holdingsValue, mockHoldings } from './holdings'

/** An account's holdings repriced on each of the last twelve month starts. */
function holdingsHistory(accountId: number): number[] {
  return Array.from({ length: 12 }, (_, i) =>
    holdingsValue([accountId], daysBeforeToday(monthStartFromToday(i - 11))))
}

// Twelve month-start balances per demo account, oldest first, the last on the current month's
// start. A cash account's last value is also its current balance.
export const mockBalanceHistory: Record<number, number[]> = {
  // LEP: slow steady growth
  1: [6100, 6250, 6400, 6500, 6650, 6800, 6950, 7100, 7200, 7400, 7600, 7800],
  // PEA, Compte Titres, Crypto: their holdings, so the charts, P&L and /prices agree
  2: holdingsHistory(2),
  3: holdingsHistory(3),
  // Checking BNP: fluctuates around the salary cycle
  4: [1200, 2800, 1500, 3100, 1800, 2600, 1400, 2900, 1700, 2500, 2100, 2340.2],
  // Checking BoursoBank: smaller balance, fluctuates
  5: [800, 1100, 950, 1300, 1050, 1200, 900, 1350, 1100, 1250, 1400, 1580.9],
  6: holdingsHistory(6),
  // Livret A: slow steady growth
  7: [4200, 4320, 4440, 4560, 4620, 4740, 4800, 4920, 4980, 5040, 5080, 5120],
  // Revolut wallet
  8: [3000, 3050, 3100, 3200, 3150, 3100, 3200, 3300, 3250, 3200, 3240, 3240.5],
  // Pocket "Vacances": a monthly transfer, which the trip goal reads as its contributions
  9: [0, 0, 0, 90, 175, 270, 350, 440, 520, 610, 690, 774],
  // Pocket "Impôts": inflows only
  10: [0, 0, 0, 100, 200, 200, 300, 300, 300, 300, 300, 300],
  // Property: slow appreciation, revalued monthly rather than daily
  11: [392000, 393500, 395000, 397000, 399500, 401000, 403000, 405500, 407000, 409000, 410500, 412000],
  // AMEX card: debt builds up then resets on each monthly debit
  12: [-640, -910, -720, -1050, -830, -1180, -760, -990, -1120, -870, -1030.5, -1284.6],
  // SCPI: withdrawal price easing
  13: [9100, 9100, 9050, 9050, 9000, 9000, 8950, 8950, 8900, 8900, 8850, 8800],
  // Car loan: outstanding capital repaid month after month
  14: [10700, 10500, 10300, 10100, 9900, 9700, 9500, 9300, 9100, 8900, 8700, 8500],
  // Mortgage: about 600 of capital repaid each month
  15: [174980, 174380, 173780, 173180, 172580, 171980, 171380, 170780, 170180, 169580, 168980, 168400],
}

/**
 * The dates a history spans, oldest first: the last twelve month starts, then today, which
 * HistoryService always ends on with its live point.
 */
function historyDates(): string[] {
  const monthStarts = Array.from({ length: 12 }, (_, i) => monthStartFromToday(i - 11))
  const today = isoDate(new Date())
  return monthStarts[11] === today ? monthStarts : [...monthStarts, today]
}

/** An account's balance on each history date, ending on its live balance. */
export function accountBalanceHistory(accountId: number): { date: string; balance: number }[] {
  const balances = mockBalanceHistory[accountId]
  if (!balances) return []
  const live = accountId in mockHoldings ? holdingsValue([accountId]) : balances[11]
  const dates = historyDates()
  return dates.map((date, i) => ({ date, balance: i === dates.length - 1 ? live : balances[i] }))
}

interface PointValues { total: number; invested: number; pnl: number }

const round2 = (n: number) => Math.round(n * 100) / 100

/**
 * Net-worth points on the last `months` month starts and today, mirroring HistoryService: a
 * loan's outstanding capital is negated, liabilities count nothing towards invested or pnl, and
 * an account with holdings is invested at their cost basis while every other asset is its own
 * basis.
 */
export function netWorthHistory(accountIds: number[], months: number, split: boolean) {
  const first = 12 - Math.min(Math.max(months, 1), 12)
  const histories = new Map(accountIds.map(id => [id, accountBalanceHistory(id)]))
  return historyDates().slice(first).map((date, i) => {
    const sums: PointValues = { total: 0, invested: 0, pnl: 0 }
    const accounts: Record<string, PointValues> = {}
    for (const id of accountIds) {
      const account = mockAccounts.find((a) => a.id === id)
      const balance = histories.get(id)?.[first + i]?.balance ?? 0
      const liability = account != null && LIABILITY_ACCOUNT_TYPES.includes(account.type)
      const total = account?.type === 'LOAN' ? -balance : balance
      const invested = liability ? 0 : id in mockHoldings ? holdingsCostBasis(id) : balance
      const pnl = liability ? 0 : total - invested
      sums.total += total
      sums.invested += invested
      sums.pnl += pnl
      if (split) accounts[String(id)] = { total, invested, pnl }
    }
    return {
      date,
      total: round2(sums.total),
      invested: round2(sums.invested),
      pnl: round2(sums.pnl),
      ...(split ? { accounts } : {}),
    }
  })
}
