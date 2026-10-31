import type { DebtInfo, LoanScheduleResponse } from '@/types/api'
import { isoDate } from './dates'

const TODAY = isoDate(new Date())
const r2 = (n: number): number => Math.round(n * 100) / 100

// Share LoanAmortizationService's calculation so loan history cannot drift from its schedule.
export function demoLoanSchedule(debt: DebtInfo | undefined, asOf: string = TODAY): LoanScheduleResponse {
  const principal = debt?.borrowedAmount ?? 0
  const monthlyRate = (debt?.interestRate ?? 0) / 12
  const insurance = debt?.insuranceMonthly ?? 0
  const fileFees = debt?.fileFees ?? 0
  const startDate = debt?.startDate ?? null
  const endDate = debt?.endDate ?? null
  const monthIndex = (iso: string) => Number(iso.slice(0, 4)) * 12 + Number(iso.slice(5, 7)) - 1
  const total = startDate && endDate && endDate > startDate
    ? Math.max(monthIndex(endDate) - monthIndex(startDate), 0)
    : 0
  const paid = startDate && total > 0 && asOf > startDate
    ? Math.min(Math.max(monthIndex(asOf) - monthIndex(startDate), 0), total)
    : 0
  const annuity = total <= 0 ? 0
    : monthlyRate === 0 ? principal / total
    : principal * monthlyRate / (1 - (1 + monthlyRate) ** -total)
  const monthlyPayment = debt?.monthlyPayment ?? annuity + insurance

  const schedule: LoanScheduleResponse['schedule'] = []
  let remaining = principal
  let totalInterest = 0
  let capitalRepaid = 0
  let interestRepaid = 0
  for (let i = 1; i <= total; i++) {
    const interest = r2(remaining * monthlyRate)
    let capital = monthlyPayment - insurance - interest
    if (i === total || capital > remaining) capital = remaining
    remaining = Math.max(remaining - capital, 0)
    const [y, m, d] = startDate!.split('-').map(Number)
    schedule.push({
      number: i,
      date: isoDate(new Date(y, m - 1 + i, d)),
      capital: r2(capital),
      interest,
      insurance: r2(insurance),
      totalPayment: r2(capital + interest + insurance),
      remainingBalance: r2(remaining),
    })
    totalInterest += interest
    if (i <= paid) {
      capitalRepaid += capital
      interestRepaid += interest
    }
  }

  const reference = schedule[Math.max(paid, 1) - 1]
  return {
    summary: {
      totalInstallments: total,
      paidInstallments: paid,
      remainingInstallments: Math.max(total - paid, 0),
      endDate,
      monthlyPayment: r2(monthlyPayment),
      monthlyCapital: reference?.capital ?? 0,
      monthlyInterest: reference?.interest ?? 0,
      monthlyInsurance: r2(insurance),
      totalCost: r2(principal + totalInterest + insurance * total + fileFees),
      totalCapitalCost: r2(principal),
      totalInterestCost: r2(totalInterest),
      totalInsuranceCost: r2(insurance * total),
      fileFees: r2(fileFees),
      totalRepaid: r2(r2(capitalRepaid) + r2(interestRepaid) + r2(insurance * paid)),
      capitalRepaid: r2(capitalRepaid),
      interestRepaid: r2(interestRepaid),
      insuranceRepaid: r2(insurance * paid),
      remainingBalance: paid === 0 ? principal : paid >= total ? 0 : schedule[paid - 1].remainingBalance,
      capitalRepaidPct: principal === 0 ? 0 : r2(r2(capitalRepaid) * 100 / principal),
    },
    schedule,
  }
}
