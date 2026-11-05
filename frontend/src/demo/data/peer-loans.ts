import type {
  PeerLoanCurvePoint,
  PeerLoanDetail,
  PeerLoanOpen,
  PeerLoanRatePeriod,
  PeerLoanRepayment,
  PeerLoanRepaymentKind,
} from '@/types/api'
import { isoDate, monthStartFromToday } from './dates'

// One peer loan, derived the same way PeerLoanCalculator does it: simple interest on the
// remaining capital (actual/365), each payment applied to interest first and then capital.
// The summary and the curve are computed from the terms and the ledger below, never typed
// twice -- see docs/features/demo-mode.md.

const DAY_MS = 86_400_000

interface Terms {
  readonly principal: number
  readonly lentAt: string
  readonly expectedBack: string
  readonly annualRate: number
  readonly ratePeriod: PeerLoanRatePeriod
  readonly plannedMonthlyAmount: number
  readonly plannedMonths: number
  readonly linkedAccountId: number
  readonly linkedAccountName: string
  readonly note: string
}

interface LedgerRow {
  readonly id: number
  readonly paidOn: string
  readonly amount: number
  readonly kind: PeerLoanRepaymentKind
  readonly note: string
}

const TERMS: Terms = {
  principal: 2600,
  lentAt: monthStartFromToday(-10),
  expectedBack: monthStartFromToday(2),
  annualRate: 0.02,
  ratePeriod: 'ANNUAL',
  plannedMonthlyAmount: 210,
  plannedMonths: 13,
  linkedAccountId: 4,
  linkedAccountName: 'Compte Courant BNP',
  note: 'Prêt pour la voiture',
}

// Irregular on purpose: the feature exists because a friend does not pay on the 5th.
const LEDGER: LedgerRow[] = [
  { id: 1, paidOn: monthStartFromToday(-8), amount: 250, kind: 'PRINCIPAL', note: 'Premier remboursement' },
  { id: 2, paidOn: monthStartFromToday(-6), amount: 200, kind: 'PRINCIPAL', note: '' },
  { id: 3, paidOn: monthStartFromToday(-4), amount: 300, kind: 'PRINCIPAL', note: 'Après les vacances' },
  { id: 4, paidOn: monthStartFromToday(-3), amount: 150, kind: 'PRINCIPAL', note: '' },
  { id: 5, paidOn: monthStartFromToday(-1), amount: 250, kind: 'PRINCIPAL', note: '' },
]

const r2 = (n: number): number => Math.round(n * 100) / 100
const clamp = (n: number, min: number, max: number): number => Math.min(Math.max(n, min), max)

function parse(iso: string): Date {
  const [year, month, day] = iso.split('-').map(Number)
  return new Date(year, month - 1, day)
}

function daysBetween(from: string, to: string): number {
  return Math.max(0, Math.round((parse(to).getTime() - parse(from).getTime()) / DAY_MS))
}

function addMonths(iso: string, months: number): string {
  const d = parse(iso)
  return isoDate(new Date(d.getFullYear(), d.getMonth() + months, d.getDate()))
}

/** Whole months between two calendar dates, like ChronoUnit.MONTHS. */
function monthsBetween(from: string, to: string): number {
  return (Number(to.slice(0, 4)) - Number(from.slice(0, 4))) * 12
    + (Number(to.slice(5, 7)) - Number(from.slice(5, 7)))
}

interface Snapshot {
  remaining: number
  accrued: number
  interestReceived: number
  principalPaid: number
  writtenOff: number
  splits: Map<number, { interest: number; capital: number }>
}

function computeAt(asOf: string): Snapshot {
  let remaining = TERMS.principal
  let accrued = 0
  let interestReceived = 0
  let principalPaid = 0
  let writtenOff = 0
  let cursor = TERMS.lentAt
  const splits = new Map<number, { interest: number; capital: number }>()

  for (const row of LEDGER) {
    if (row.paidOn > asOf) break
    accrued += remaining * TERMS.annualRate * daysBetween(cursor, row.paidOn) / 365
    let interest = 0
    let capital = 0
    if (row.kind === 'WRITE_OFF') {
      capital = Math.min(row.amount, remaining)
      writtenOff += capital
    } else {
      interest = Math.min(row.amount, accrued)
      accrued -= interest
      capital = Math.min(row.amount - interest, remaining)
      interestReceived += interest
    }
    remaining -= capital
    principalPaid += capital
    splits.set(row.id, { interest, capital })
    cursor = row.paidOn
  }
  accrued += remaining * TERMS.annualRate * daysBetween(cursor, asOf) / 365
  return { remaining, accrued, interestReceived, principalPaid, writtenOff, splits }
}

/** What the plan says should be left at {@code asOf}, using the same annuity as the backend. */
function scheduledRemaining(asOf: string): number {
  const monthlyRate = TERMS.annualRate / 12
  const paid = clamp(monthsBetween(TERMS.lentAt, asOf), 0, TERMS.plannedMonths)
  const growth = (1 + monthlyRate) ** paid
  const scheduled = monthlyRate === 0
    ? TERMS.principal - TERMS.plannedMonthlyAmount * paid
    : TERMS.principal * growth
      - TERMS.plannedMonthlyAmount * (growth - 1) / monthlyRate
  return Math.max(0, scheduled)
}

function planInterest(): number {
  return Math.max(0, TERMS.plannedMonthlyAmount * TERMS.plannedMonths - TERMS.principal)
}

function repayments(asOf: string): PeerLoanRepayment[] {
  const { splits } = computeAt(asOf)
  return LEDGER
    .filter((row) => row.paidOn <= asOf)
    .map((row) => ({
      id: row.id,
      paidOn: row.paidOn,
      amount: row.amount,
      kind: row.kind,
      note: row.note || null,
      interestPart: r2(splits.get(row.id)?.interest ?? 0),
      capitalPart: r2(splits.get(row.id)?.capital ?? 0),
    }))
}

function curve(asOf: string): PeerLoanCurvePoint[] {
  const end = addMonths(TERMS.lentAt, TERMS.plannedMonths)
  const dates: string[] = []
  for (let date = TERMS.lentAt; date <= end; date = addMonths(date, 1)) {
    dates.push(date)
  }
  if (!dates.includes(asOf)) {
    dates.push(asOf)
    dates.sort()
  }
  return dates.map((date) => ({
    date,
    actual: r2(computeAt(date).remaining),
    scheduled: r2(scheduledRemaining(date)),
  }))
}

/** The remaining capital on a given day, for the account's balance history. */
export function demoPeerLoanBalanceAt(asOf: string): number {
  return r2(computeAt(asOf).remaining)
}

/** The loan the demo account 16 carries. */
export const demoPeerLoan: PeerLoanDetail = (() => {
  const asOf = isoDate(new Date())
  const snapshot = computeAt(asOf)
  const scheduled = scheduledRemaining(asOf)
  const repaidPct = TERMS.principal === 0 ? 0 : snapshot.principalPaid * 100 / TERMS.principal
  return {
    accountId: 16,
    accountName: 'Jean',
    configured: true,
    principal: TERMS.principal,
    lentAt: TERMS.lentAt,
    expectedBack: TERMS.expectedBack,
    annualRate: TERMS.annualRate,
    ratePeriod: TERMS.ratePeriod,
    plannedMonthlyAmount: TERMS.plannedMonthlyAmount,
    plannedMonths: TERMS.plannedMonths,
    linkedAccountId: TERMS.linkedAccountId,
    linkedAccountName: TERMS.linkedAccountName,
    note: TERMS.note,
    closedAt: null,
    principalRemaining: r2(snapshot.remaining),
    interestAccrued: r2(snapshot.accrued),
    interestReceived: r2(snapshot.interestReceived),
    principalRepaid: r2(snapshot.principalPaid),
    totalRepaid: r2(snapshot.principalPaid + snapshot.interestReceived),
    writtenOff: r2(snapshot.writtenOff),
    repaidPct: r2(repaidPct),
    scheduledRemaining: r2(scheduled),
    installmentsBehind: TERMS.plannedMonthlyAmount > 0
      ? Math.ceil((snapshot.remaining - scheduled) / TERMS.plannedMonthlyAmount)
      : null,
    plannedInterest: r2(planInterest()),
    repayments: repayments(asOf),
    curve: curve(asOf),
  }
})()

export const demoPeerLoansOpen: PeerLoanOpen[] = [
  { accountId: 16, name: 'Jean', remaining: demoPeerLoan.principalRemaining },
]
