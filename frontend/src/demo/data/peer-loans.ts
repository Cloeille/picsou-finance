import type {
  PeerLoanCurvePoint,
  PeerLoanDetail,
  PeerLoanOpen,
  PeerLoanRatePeriod,
  PeerLoanRepayment,
  PeerLoanRepaymentKind,
} from '@/types/api'
import { isoDate, monthStartFromToday } from './dates'

// Peer loans are derived the same way PeerLoanCalculator does it: simple interest on the
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

interface LoanFixture {
  readonly accountId: number
  readonly name: string
  readonly terms: Terms
  readonly ledger: LedgerRow[]
}

// Irregular on purpose: the feature exists because a friend does not pay on the 5th.
const JEAN: LoanFixture = {
  accountId: 16,
  name: 'Jean',
  terms: {
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
  },
  ledger: [
    { id: 1, paidOn: monthStartFromToday(-8), amount: 250, kind: 'PRINCIPAL', note: 'Premier remboursement' },
    { id: 2, paidOn: monthStartFromToday(-6), amount: 200, kind: 'PRINCIPAL', note: '' },
    { id: 3, paidOn: monthStartFromToday(-4), amount: 300, kind: 'PRINCIPAL', note: 'Après les vacances' },
    { id: 4, paidOn: monthStartFromToday(-3), amount: 150, kind: 'PRINCIPAL', note: '' },
    { id: 5, paidOn: monthStartFromToday(-1), amount: 250, kind: 'PRINCIPAL', note: '' },
  ],
}

// Stopped paying two months ago: the overview's "behind" state needs a real case in the demo.
const SOPHIE: LoanFixture = {
  accountId: 17,
  name: 'Sophie',
  terms: {
    principal: 1800,
    lentAt: monthStartFromToday(-8),
    expectedBack: monthStartFromToday(4),
    annualRate: 0.05,
    ratePeriod: 'ANNUAL',
    plannedMonthlyAmount: 160,
    plannedMonths: 12,
    linkedAccountId: 4,
    linkedAccountName: 'Compte Courant BNP',
    note: 'Prêt pour les études',
  },
  ledger: [
    { id: 11, paidOn: monthStartFromToday(-6), amount: 160, kind: 'PRINCIPAL', note: '' },
    { id: 12, paidOn: monthStartFromToday(-5), amount: 160, kind: 'PRINCIPAL', note: '' },
    { id: 13, paidOn: monthStartFromToday(-4), amount: 160, kind: 'PRINCIPAL', note: '' },
    { id: 14, paidOn: monthStartFromToday(-3), amount: 160, kind: 'PRINCIPAL', note: '' },
    { id: 15, paidOn: monthStartFromToday(-2), amount: 160, kind: 'PRINCIPAL', note: '' },
  ],
}

const FIXTURES: LoanFixture[] = [JEAN, SOPHIE]

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

function computeAt(fixture: LoanFixture, asOf: string): Snapshot {
  const { terms, ledger } = fixture
  let remaining = terms.principal
  let accrued = 0
  let interestReceived = 0
  let principalPaid = 0
  let writtenOff = 0
  let cursor = terms.lentAt
  const splits = new Map<number, { interest: number; capital: number }>()

  for (const row of ledger) {
    if (row.paidOn > asOf) break
    accrued += remaining * terms.annualRate * daysBetween(cursor, row.paidOn) / 365
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
  accrued += remaining * terms.annualRate * daysBetween(cursor, asOf) / 365
  return { remaining, accrued, interestReceived, principalPaid, writtenOff, splits }
}

/** What the plan says should be left at {@code asOf}, using the same annuity as the backend. */
function scheduledRemaining(fixture: LoanFixture, asOf: string): number {
  const { terms } = fixture
  const monthlyRate = terms.annualRate / 12
  const paid = clamp(monthsBetween(terms.lentAt, asOf), 0, terms.plannedMonths)
  const growth = (1 + monthlyRate) ** paid
  const scheduled = monthlyRate === 0
    ? terms.principal - terms.plannedMonthlyAmount * paid
    : terms.principal * growth
      - terms.plannedMonthlyAmount * (growth - 1) / monthlyRate
  return Math.max(0, scheduled)
}

function planInterest(fixture: LoanFixture): number {
  const { terms } = fixture
  return Math.max(0, terms.plannedMonthlyAmount * terms.plannedMonths - terms.principal)
}

function repayments(fixture: LoanFixture, asOf: string): PeerLoanRepayment[] {
  const { splits } = computeAt(fixture, asOf)
  return fixture.ledger
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

function curve(fixture: LoanFixture, asOf: string): PeerLoanCurvePoint[] {
  const end = addMonths(fixture.terms.lentAt, fixture.terms.plannedMonths)
  const dates: string[] = []
  for (let date = fixture.terms.lentAt; date <= end; date = addMonths(date, 1)) {
    dates.push(date)
  }
  if (!dates.includes(asOf)) {
    dates.push(asOf)
    dates.sort()
  }
  return dates.map((date) => ({
    date,
    actual: r2(computeAt(fixture, date).remaining),
    scheduled: r2(scheduledRemaining(fixture, date)),
  }))
}

function buildPeerLoan(fixture: LoanFixture, asOf: string): PeerLoanDetail {
  const { accountId, name, terms } = fixture
  const snapshot = computeAt(fixture, asOf)
  const scheduled = scheduledRemaining(fixture, asOf)
  const repaidPct = terms.principal === 0 ? 0 : snapshot.principalPaid * 100 / terms.principal
  return {
    accountId,
    accountName: name,
    configured: true,
    principal: terms.principal,
    lentAt: terms.lentAt,
    expectedBack: terms.expectedBack,
    annualRate: terms.annualRate,
    ratePeriod: terms.ratePeriod,
    plannedMonthlyAmount: terms.plannedMonthlyAmount,
    plannedMonths: terms.plannedMonths,
    linkedAccountId: terms.linkedAccountId,
    linkedAccountName: terms.linkedAccountName,
    note: terms.note,
    closedAt: null,
    principalRemaining: r2(snapshot.remaining),
    interestAccrued: r2(snapshot.accrued),
    interestReceived: r2(snapshot.interestReceived),
    principalRepaid: r2(snapshot.principalPaid),
    totalRepaid: r2(snapshot.principalPaid + snapshot.interestReceived),
    writtenOff: r2(snapshot.writtenOff),
    repaidPct: r2(repaidPct),
    scheduledRemaining: r2(scheduled),
    installmentsBehind: terms.plannedMonthlyAmount > 0
      ? Math.ceil((snapshot.remaining - scheduled) / terms.plannedMonthlyAmount)
      : null,
    plannedInterest: r2(planInterest(fixture)),
    repayments: repayments(fixture, asOf),
    curve: curve(fixture, asOf),
  }
}

const TODAY = isoDate(new Date())

/** Every peer loan of the demo, computed against today. */
export const demoPeerLoans: PeerLoanDetail[] = FIXTURES.map((fixture) => buildPeerLoan(fixture, TODAY))

/** The loan the demo account 16 carries (kept for the account fixture). */
export const demoPeerLoan: PeerLoanDetail = demoPeerLoans[0]

export const demoPeerLoansOpen: PeerLoanOpen[] = demoPeerLoans
  .filter((loan) => !loan.closedAt)
  .map((loan) => ({ accountId: loan.accountId, name: loan.accountName, remaining: loan.principalRemaining }))

/** The remaining capital of one demo loan on a given day, for the account's history. */
export function demoPeerLoanBalanceAt(accountId: number, asOf: string): number {
  const fixture = FIXTURES.find((candidate) => candidate.accountId === accountId)
  return fixture ? r2(computeAt(fixture, asOf).remaining) : 0
}
