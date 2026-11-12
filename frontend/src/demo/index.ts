import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type {
  CicSessionStatus,
  PeerLoanDetail,
  PeerLoanFromTransactionRequest,
  PeerLoanLink,
  PeerLoanRepayment,
  PeerLoanRepaymentFromTransactionRequest,
  PeerLoanRepaymentKind,
  PeerLoanRepaymentRequest,
  PeerLoanSimulationRequest,
  PeerLoanSimulationResponse,
  PeerLoanTermsRequest,
  PreciousMetalProduct,
  ScpiPosition,
  ScpiPositionRequest,
} from '@/types/api'
import { mockAccounts, mockBanquePopulaireAccounts } from './data/accounts'
import {
  mockAllocationTargets,
  mockDiversification,
  mockExpenseEstimate,
  mockProjection,
  mockWealthPyramid,
} from './data/analysis'
import { mockDashboard } from './data/dashboard'
import { mockGoalMonths, mockGoals } from './data/goals'
import { accountBalanceHistory, netWorthHistory } from './data/history'
import { daysBeforeToday, daysFromToday, hoursAgo, monthStartFromToday } from './data/dates'
import { holdingsValue, holdingsValueHoursAgo, mockHoldings, quoteDaysAgo, quoteHoursAgo } from './data/holdings'
import { ageFromBirthDate, mockMemberProfile, netIncome } from './data/profile'
import { mockTransactions } from './data/transactions'
import { mockExchangeStatuses, mockWalletStatuses, mockRequisitions } from './data/sync-status'
import {
  categorizeDemoTransaction,
  mockActivity,
  mockAllocation,
  mockBudgetSettings,
  mockBudgets,
  mockBudgetSummaries,
  mockCalendar,
  mockCashflow,
  mockCategories,
  mockCategoryDetail,
  mockFlow,
  mockLedger,
  mockRecurring,
  mockRules,
  mockSpendingByCategory,
  mockTransactionSearch,
  mockUncategorized,
} from './data/budget'
import { mockUpcomingDividends } from './data/dividends'
import type { CashflowPeriod, CategorizeRequest, Transaction } from '@/types/api'
import { demoLoanSchedule } from './data/loans'
import { demoPeerLoans } from './data/peer-loans'

function randomDelay(): number {
  return 200 + Math.random() * 400
}

type MockHandler = (config: InternalAxiosRequestConfig, params: Record<string, string>) => unknown

// Keys are `METHOD /path`; a `:name` segment matches any value and reaches the handler as
// params.name. An exact key wins over a pattern, so one id can still override the default.
const handlers = new Map<string, MockHandler>()

function findHandler(k: string): { handler: MockHandler; params: Record<string, string> } | undefined {
  const exact = handlers.get(k)
  if (exact) return { handler: exact, params: {} }
  const [method, path] = k.split(' ')
  const segments = path.split('/')
  for (const [pattern, handler] of handlers) {
    const [patternMethod, patternPath] = pattern.split(' ')
    if (patternMethod !== method || !patternPath.includes('/:')) continue
    const patternSegments = patternPath.split('/')
    if (patternSegments.length !== segments.length) continue
    const params: Record<string, string> = {}
    const matches = patternSegments.every((segment, i) => {
      if (segment.startsWith(':')) {
        // A malformed escape (`%` alone) is no match, so it takes the adapter's 200 {} fallback.
        try {
          params[segment.slice(1)] = decodeURIComponent(segments[i])
        } catch {
          return false
        }
        return true
      }
      return segment === segments[i]
    })
    if (matches) return { handler, params }
  }
  return undefined
}

// Mutable demo state for the pocket rename flow (resets on page reload).
// `let` so PUT handlers can reassign to a NEW array reference — TanStack Query
// uses referential equality first (replaceEqualDeep) and will not update React
// state if the same reference is returned after a mutation.
let _demoAccounts = mockAccounts.map(a => ({ ...a }))

function key(method: string, url: string): string {
  const normalized = url.split('?')[0].replace(/\/$/, '')
  return `${method.toUpperCase()} ${normalized}`
}

// Auth
// RequireAuth restores this user through /auth/refresh, as it would from a session cookie.
const demoUser = { username: 'demo', role: 'ADMIN', memberId: 1, displayName: 'Demo' }
handlers.set(key('POST', '/auth/login'), () => demoUser)
handlers.set(key('POST', '/auth/refresh'), () => demoUser)

// Persistent sessions — demo shows one current desktop session
handlers.set(key('GET', '/auth/sessions'), () => [
  {
    id: 1,
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
    ipPrefix: '192.168.1',
    createdAt: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString(),
    lastUsedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
    trustedFor2fa: true,
    current: true,
  },
])
handlers.set(key('DELETE', '/auth/sessions'), () => null)
// SSO is never configured in the demo; the sign-in methods are password-only.
handlers.set(key('GET', '/auth/sso'), () => ({ enabled: false, providerName: 'SSO' }))
handlers.set(key('GET', '/auth/sign-in-methods'), () => ({
  hasPassword: true, mfaEnabled: false, identities: [], ssoEnabled: false, providerName: 'SSO', adminGroupManaged: false,
}))

// Access keys — demo shows one active key (read-only)
handlers.set(key('GET', '/access-keys'), () => [
  {
    id: 1,
    name: 'Demo key',
    keyPrefix: 'psk_demo',
    scopes: ['accounts:read', 'transactions:read'],
    lastUsedAt: new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString(),
    expiresAt: new Date(Date.now() + 365 * 24 * 60 * 60 * 1000).toISOString(),
    revokedAt: null,
    createdAt: new Date(Date.now() - 30 * 24 * 60 * 60 * 1000).toISOString(),
  },
])
handlers.set(key('POST', '/access-keys'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const secret = 'psk_demo_' + Math.random().toString(36).slice(2, 14)
  return {
    secret,
    key: {
      id: Date.now(),
      name: body.name ?? 'New key',
      keyPrefix: secret.slice(0, 12),
      scopes: body.scopes ?? [],
      lastUsedAt: null,
      expiresAt: body.expiresAt ?? null,
      revokedAt: null,
      createdAt: new Date().toISOString(),
    },
  }
})
for (const id of [1]) {
  handlers.set(key('DELETE', `/access-keys/${id}`), () => ({}))
  handlers.set(key('DELETE', `/auth/sessions/${id}`), () => ({}))
}

// Family — the sidebar profile switcher fetches members on every authenticated
// route, so an unhandled call here (which would fall back to `{}`) breaks the
// whole shell via `members.filter`. Return a small, realistic family: the demo
// admin (not switchable) plus one managed member the admin can impersonate.
handlers.set(key('GET', '/family/members'), () => [
  { id: 1, displayName: 'Demo', avatarColor: '#6366f1', managed: false, hasLogin: true, activated: true, loginName: 'demo', mfaEnabled: false, hasPassword: true, ssoLinked: false },
  { id: 2, displayName: 'Léa', avatarColor: '#ec4899', managed: true, hasLogin: false, activated: false, loginName: null, mfaEnabled: false, hasPassword: false, ssoLinked: false },
])

// Dashboard
handlers.set(key('GET', '/dashboard'), () => mockDashboard(_demoAccounts))

// Analysis
handlers.set(key('GET', '/analysis/pyramid'), () => mockWealthPyramid)
handlers.set(key('GET', '/analysis/diversification'), () => mockDiversification)
// Held in a mutable copy, because saving targets invalidates the whole ['analysis'] namespace:
// a PUT that only echoed the merge back would be undone by the refetch that follows it, and the
// demo would show the form silently reverting.
let demoAllocationTargets = { ...mockAllocationTargets }
handlers.set(key('GET', '/analysis/allocation-targets'), () => demoAllocationTargets)
handlers.set(key('PUT', '/analysis/allocation-targets'), (config) => {
  demoAllocationTargets = {
    ...demoAllocationTargets,
    ...(typeof config.data === 'string' ? JSON.parse(config.data) : {}),
  }
  return demoAllocationTargets
})
handlers.set(key('GET', '/analysis/essential-expenses/estimate'), () => mockExpenseEstimate)

// Member profile. Held in a mutable copy for the same reason as the allocation targets above:
// saving invalidates ['me','profile'], and a PUT that only echoed its body back would be undone
// by the refetch that follows -- the form would appear to revert on every save.
let demoMemberProfile = { ...mockMemberProfile }
handlers.set(key('GET', '/me/profile'), () => demoMemberProfile)
handlers.set(key('PUT', '/me/profile'), (config) => {
  const body = typeof config.data === 'string' ? JSON.parse(config.data) : {}
  demoMemberProfile = {
    ...demoMemberProfile,
    ...body,
    // Both are derived server-side in production; the demo has to derive them too, or the
    // savings rate on the Goals page never moves.
    age: body.birthDate == null ? null : ageFromBirthDate(body.birthDate),
    monthlyNetIncome: netIncome(body.monthlyNetBeforeTax ?? null, body.withholdingTaxRate ?? null),
  }
  return demoMemberProfile
})
handlers.set(key('GET', '/analysis/projection'), (config) =>
  mockProjection(Number(config.params?.years) || 20))
// Demo mode has no scheduler and no network, so the refresh reports a plausible queue rather
// than pretending work happened.
handlers.set(key('POST', '/analysis/security-profiles/refresh'), () => ({
  queuedTickers: 2,
  alreadyRunning: false,
}))

// Nothing overridden by default: the demo shows the providers' own answer, which is what a
// real instance looks like before anyone corrects anything.
handlers.set(key('GET', '/accounts/:id/holdings/:ticker/classification'), (_config, { ticker }) => ({
  ticker,
  wealthTier: null,
  sectorKey: null,
  countryKey: null,
  inferredSectorKey: null,
  inferredCountryKey: null,
  profileLooked: true,
}))
handlers.set(key('PUT', '/accounts/:id/holdings/:ticker/classification'), (config, { ticker }) => ({
  ticker,
  ...(typeof config.data === 'string' ? JSON.parse(config.data) : {}),
}))

// Accounts
handlers.set(key('GET', '/accounts'), () => _demoAccounts)
for (let i = 1; i <= mockAccounts.length; i++) {
  handlers.set(key('GET', `/accounts/${i}`), () => mockAccounts[i - 1])
}

// Individual account lookups (extends the loop above for accounts 8–10).
// Uses _demoAccounts so renames are reflected immediately after refetch.
for (let i = 8; i <= 10; i++) {
  handlers.set(key('GET', `/accounts/${i}`), () => _demoAccounts[i - 1])
}

handlers.set(key('GET', '/accounts/:id/deletion-impact'), () => ({
  removesConnection: false, connectionLabel: null,
}))

// SCPI funds seen by a sync: the demo has no CORUM/Sofidy connection.
handlers.set(key('GET', '/scpi/funds'), () => [])

// Account CRUD
for (const account of mockAccounts.filter(account => account.type === 'SCPI')) {
  handlers.set(key('GET', `/accounts/${account.id}`), () => _demoAccounts.find(a => a.id === account.id))
  handlers.set(key('PUT', `/accounts/${account.id}/scpi`), config => {
    const data: ScpiPositionRequest = JSON.parse(config.data || '{}')
    const current = _demoAccounts.find(a => a.id === account.id)!
    const withdrawalValue = data.withdrawalPriceEur == null ? null : data.shareCount * data.withdrawalPriceEur
    const scpi: ScpiPosition = {
      isin: data.isin?.trim() || null,
      managementCompany: data.managementCompany?.trim() || null,
      corumFundCode: data.corumFundCode == null ? current.scpi?.corumFundCode ?? null : data.corumFundCode.trim() || null,
      sofidyFundCode: data.sofidyFundCode == null ? current.scpi?.sofidyFundCode ?? null : data.sofidyFundCode.trim() || null,
      shareCount: data.shareCount,
      subscriptionPriceEur: data.subscriptionPriceEur ?? null,
      withdrawalPriceEur: data.withdrawalPriceEur ?? null,
      withdrawalValueEur: withdrawalValue,
      dividendPolicy: data.dividendPolicy ?? 'CASH',
      jouissanceDate: data.jouissanceDate ?? null,
      // Fund-code rule: a missing field leaves the stored value.
      // Null clears, because a number has no blank string the way a fund code does.
      annualDistributionPerShareEur: data.annualDistributionPerShareEur === undefined
        ? current.scpi?.annualDistributionPerShareEur ?? null
        : data.annualDistributionPerShareEur,
      distributionFrequency: data.distributionFrequency === undefined
        ? current.scpi?.distributionFrequency ?? null
        : data.distributionFrequency,
      valuationStatus: withdrawalValue == null ? 'PRICE_INCOMPLETE' : 'OK',
    }
    _demoAccounts = _demoAccounts.map(a => a.id === account.id ? {
      ...a, scpi,
      currentBalance: withdrawalValue ?? a.currentBalance,
      currentBalanceEur: withdrawalValue ?? a.currentBalanceEur,
    } : a)
    return scpi
  })
}

handlers.set(key('POST', '/accounts'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(),
    name: body.name ?? 'New Account',
    type: body.type ?? 'CHECKING',
    provider: body.provider ?? null,
    currency: body.currency ?? 'EUR',
    currentBalance: body.currentBalance ?? 0,
    currentBalanceEur: body.currentBalance ?? 0,
    lastSyncedAt: null,
    isManual: body.isManual ?? true,
    color: body.color ?? '#6366f1',
    ticker: body.ticker ?? null,
    createdAt: new Date().toISOString(),
  }
})
handlers.set(key('PUT', '/accounts/1'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return { ...mockAccounts[0], ...body }
})
handlers.set(key('DELETE', '/accounts/1'), () => ({}))

// Account details: holdings for PEA (id=2), Compte Titres (id=3), Crypto (id=6)
handlers.set(key('GET', '/accounts/:id/holdings'), (_config, { id }) => mockHoldings[Number(id)] ?? [])

// Per-product breakdown. Only the crypto account (id=6) has one, exactly like a real crypto
// exchange account; every other account falls back to the flat holdings table.
const demoExchangePositions = (() => {
  const today = new Date().toISOString().slice(0, 10)
  return [
    { product: 'SPOT', ticker: 'BTC', quantity: 0.01204, principal: null, interest: null, averageBuyIn: 68000, currentPriceEur: 92100, currentValueEur: 1108.88, costBasisEur: 818.72, pnlEur: 290.16, pnlPercent: 35.4, priceAsOf: today, priceStale: false },
    { product: 'SPOT', ticker: 'ETH', quantity: 0.031906, principal: null, interest: null, averageBuyIn: 3200, currentPriceEur: 4116, currentValueEur: 131.32, costBasisEur: 102.1, pnlEur: 29.22, pnlPercent: 28.6, priceAsOf: today, priceStale: false },
    { product: 'STAKING', ticker: 'ATOM', quantity: 33.154, principal: 19.73, interest: 13.424, averageBuyIn: 6.4, currentPriceEur: 5.65, currentValueEur: 187.32, costBasisEur: 212.19, pnlEur: -24.87, pnlPercent: -11.7, priceAsOf: today, priceStale: false },
    { product: 'LENDING', ticker: 'USDT', quantity: 75.01, principal: 75, interest: 0.01, averageBuyIn: 0.91, currentPriceEur: 0.92, currentValueEur: 69.01, costBasisEur: 68.26, pnlEur: 0.75, pnlPercent: 1.1, priceAsOf: today, priceStale: false },
  ]
})()
handlers.set(key('GET', '/accounts/6/positions'), () => demoExchangePositions)
handlers.set(key('GET', '/accounts/:id/positions'), () => [])

// Peer loans: accounts 16 and 17 carry the configured fixtures. The demo keeps a mutable
// copy so Save, a repayment, Simulate and close behave like the API instead of falling
// through to the permissive 200 {} fallback.
const _peerLoanDetails = new Map<number, PeerLoanDetail>(
  demoPeerLoans.map((loan) => [loan.accountId, JSON.parse(JSON.stringify(loan)) as PeerLoanDetail]),
)

const round2 = (value: number): number => Math.round(value * 100) / 100

// The one place that knows which transaction belongs to which loan. The two list handlers
// annotate from it, like the API does from the loan tables, so a link created by a demo
// write shows up on every list that carries the transaction, and disappears with the row.
// A repayment link remembers its row, so deleting the repayment unlinks the transaction.
const _peerLoanLinks = new Map<number, { link: PeerLoanLink; repaymentId?: number }>()

function withPeerLoanLinks(transactions: Transaction[]): Transaction[] {
  return transactions.map((transaction) => {
    const entry = _peerLoanLinks.get(transaction.id)
    return entry ? { ...transaction, peerLoanLink: entry.link } : transaction
  })
}

/**
 * Mirrors the hardened API: a write-off goes through close(), a repayment cannot exceed
 * what the loan can absorb, and an interest row needs accrued interest to pay. The
 * adapter turns the throw into a rejected promise, like a 4xx from the backend.
 */
function assertDemoRepaymentAllowed(
  current: PeerLoanDetail,
  data: PeerLoanRepaymentRequest,
): void {
  if (data.kind === 'WRITE_OFF') {
    throw new Error('A write-off is recorded by closing the loan, not by adding a repayment')
  }
  const maxAcceptable = current.interestAccrued + current.principalRemaining
  if (data.amount > maxAcceptable) {
    throw new Error(
      `A repayment cannot exceed ${maxAcceptable} (accrued interest plus remaining capital)`)
  }
  if (data.kind === 'INTEREST' && (current.interestAccrued <= 0 || data.amount > current.interestAccrued)) {
    throw new Error('An interest repayment cannot exceed the accrued interest')
  }
}

function demoPeerLoanDetail(accountId: number): PeerLoanDetail {
  const existing = _peerLoanDetails.get(accountId)
  if (existing) return existing
  const account = _demoAccounts.find((candidate) => candidate.id === accountId)
  return {
    accountId,
    accountName: account?.name ?? '',
    configured: false,
    principal: 0,
    principalRemaining: 0,
    interestAccrued: 0,
    interestReceived: 0,
    principalRepaid: 0,
    totalRepaid: 0,
    writtenOff: 0,
    repaidPct: 0,
    repayments: [],
    curve: [],
  }
}

/** Mirrors PeerLoanCalculator's annuity so the dialog gets finite figures in demo mode. */
function demoPeerLoanSimulation(data: PeerLoanSimulationRequest): PeerLoanSimulationResponse {
  const principal = data.principal
  const monthlyRate = (data.annualRate ?? 0) / 12
  if (data.months != null && data.months > 0) {
    const months = data.months
    const monthly = monthlyRate === 0
      ? principal / months
      : principal * monthlyRate / (1 - (1 + monthlyRate) ** -months)
    const total = monthly * months
    return {
      monthlyPayment: round2(monthly),
      months,
      totalInterest: round2(Math.max(0, total - principal)),
      totalCost: round2(total),
    }
  }
  const payment = data.monthlyPayment ?? 0
  if (monthlyRate === 0) {
    const months = Math.max(1, Math.ceil(principal / payment))
    return { monthlyPayment: round2(payment), months, totalInterest: 0, totalCost: round2(principal) }
  }
  const ratio = 1 - principal * monthlyRate / payment
  if (ratio <= 0) throw new Error('The monthly payment does not cover the monthly interest')
  const months = Math.max(1, Math.ceil(-Math.log(ratio) / Math.log(1 + monthlyRate)))
  const growth = (1 + monthlyRate) ** (months - 1)
  const beforeFinal = principal * growth - payment * (growth - 1) / monthlyRate
  const final = Math.max(0, beforeFinal) * (1 + monthlyRate)
  const total = payment * (months - 1) + final
  return {
    monthlyPayment: round2(payment),
    months,
    totalInterest: round2(Math.max(0, total - principal)),
    totalCost: round2(total),
  }
}

function demoNewPeerLoanAccount(accountId: number, name: string): void {
  if (_demoAccounts.some((account) => account.id === accountId)) return
  _demoAccounts = [..._demoAccounts, {
    ...mockAccounts[0],
    id: accountId,
    name,
    type: 'PEER_LOAN',
    currentBalance: 0,
    currentBalanceEur: 0,
    isManual: true,
    color: '#0d9488',
  }]
}

// The account lists and the budget ledger (which serves the main checking account) carry
// distinct ids, so a transaction opened from either list resolves here.
function demoTransactionById(transactionId: number) {
  return [...Object.values(mockTransactions).flat(), ...mockLedger]
    .find((transaction) => transaction.id === transactionId)
}

handlers.set(key('GET', '/accounts/:id'), (_config, { id }) =>
  _demoAccounts.find((account) => account.id === Number(id)) ?? {})

handlers.set(key('GET', '/accounts/:id/peer-loan'), (_config, { id }) =>
  demoPeerLoanDetail(Number(id)))

handlers.set(key('PUT', '/accounts/:id/peer-loan'), (config, { id }) => {
  const accountId = Number(id)
  const data: PeerLoanTermsRequest = JSON.parse(config.data || '{}')
  const current = demoPeerLoanDetail(accountId)
  const linked = _demoAccounts.find((account) => account.id === data.linkedAccountId)
  const detail: PeerLoanDetail = {
    ...current,
    configured: true,
    accountName: current.accountName || linked?.name || 'Prêt',
    principal: data.principal,
    lentAt: data.lentAt,
    expectedBack: data.expectedBack ?? null,
    annualRate: data.annualRate ?? 0,
    ratePeriod: data.ratePeriod ?? 'ANNUAL',
    plannedMonthlyAmount: data.plannedMonthlyAmount ?? null,
    plannedMonths: data.plannedMonths ?? null,
    linkedAccountId: data.linkedAccountId,
    linkedAccountName: linked?.name ?? null,
    note: data.note ?? null,
    principalRemaining: round2(Math.max(0, data.principal - current.principalRepaid)),
    repaidPct: data.principal > 0 ? round2(current.principalRepaid * 100 / data.principal) : 0,
  }
  _peerLoanDetails.set(accountId, detail)
  return detail
})

handlers.set(key('POST', '/accounts/:id/peer-loan/repayments'), (config, { id }) => {
  const accountId = Number(id)
  const data: PeerLoanRepaymentRequest = JSON.parse(config.data || '{}')
  const current = demoPeerLoanDetail(accountId)
  assertDemoRepaymentAllowed(current, data)
  const repayment: PeerLoanRepayment = {
    id: Date.now(),
    paidOn: data.paidOn,
    amount: data.amount,
    kind: data.kind ?? 'PRINCIPAL',
    note: data.note ?? null,
    interestPart: 0,
    capitalPart: data.amount,
    // Picsou writes the deposit unless the member records a compensation.
    hasCashLeg: data.withCashLeg !== false,
    cashLegOwned: true,
  }
  const applied = Math.min(data.amount, current.principalRemaining)
  const repaid = current.principalRepaid + applied
  _peerLoanDetails.set(accountId, {
    ...current,
    principalRemaining: round2(current.principalRemaining - applied),
    principalRepaid: round2(repaid),
    totalRepaid: round2(current.totalRepaid + data.amount),
    repaidPct: current.principal > 0 ? round2(repaid * 100 / current.principal) : 0,
    repayments: [...current.repayments, repayment],
  })
  return repayment
})

handlers.set(key('PUT', '/accounts/:id/peer-loan/repayments/:repaymentId'), (config, { id, repaymentId }) => {
  const accountId = Number(id)
  const target = Number(repaymentId)
  const data: PeerLoanRepaymentRequest = JSON.parse(config.data || '{}')
  const current = demoPeerLoanDetail(accountId)
  const previous = current.repayments.find((repayment) => repayment.id === target)
  // Same guard as the backend, with the row being edited excluded like the service does:
  // the ceiling is what the loan can still absorb without it.
  assertDemoRepaymentAllowed({
    ...current,
    principalRemaining: current.principalRemaining + (previous?.capitalPart ?? previous?.amount ?? 0),
    principalRepaid: current.principalRepaid - (previous?.capitalPart ?? previous?.amount ?? 0),
  }, data)
  const updated = current.repayments.map((repayment) => {
    if (repayment.id !== target) return repayment
    // The split follows the amount: the accrued interest part is unchanged, the rest
    // is capital — the same rule the POST handler applies to a new row.
    const interest = repayment.interestPart ?? 0
    return {
      ...repayment,
      paidOn: data.paidOn,
      amount: data.amount,
      kind: data.kind ?? repayment.kind,
      note: data.note ?? null,
      interestPart: interest,
      capitalPart: Math.max(0, data.amount - interest),
    }
  })
  // An edited amount moves the capital: recompute the derived figures from the rows,
  // like the POST handler does, or the detail contradicts its own journal.
  const principalRepaid = round2(updated.reduce((sum, row) => sum + (row.capitalPart ?? row.amount), 0))
  const totalRepaid = round2(updated.reduce((sum, row) => sum + row.amount, 0))
  _peerLoanDetails.set(accountId, {
    ...current,
    principalRemaining: round2(Math.max(0, current.principal - principalRepaid)),
    principalRepaid,
    totalRepaid,
    repaidPct: current.principal > 0 ? round2(principalRepaid * 100 / current.principal) : 0,
    repayments: updated,
  })
  return updated.find((repayment) => repayment.id === target)
})

handlers.set(key('DELETE', '/accounts/:id/peer-loan/repayments/:repaymentId'), (_config, { id, repaymentId }) => {
  const accountId = Number(id)
  const target = Number(repaymentId)
  const current = demoPeerLoanDetail(accountId)
  const removed = current.repayments.find((repayment) => repayment.id === target)
  if (removed) {
    for (const [transactionId, entry] of _peerLoanLinks) {
      if (entry.repaymentId === target) _peerLoanLinks.delete(transactionId)
    }
    const restored = current.principalRemaining + (removed.capitalPart ?? removed.amount)
    _peerLoanDetails.set(accountId, {
      ...current,
      principalRemaining: round2(Math.min(current.principal, restored)),
      principalRepaid: round2(Math.max(0, current.principalRepaid - (removed.capitalPart ?? removed.amount))),
      repayments: current.repayments.filter((repayment) => repayment.id !== target),
    })
  }
  return {}
})

handlers.set(key('POST', '/accounts/:id/peer-loan/close'), (config, { id }) => {
  const accountId = Number(id)
  const current = demoPeerLoanDetail(accountId)
  const date = (config.params as Record<string, string> | undefined)?.date
    ?? new Date().toISOString().slice(0, 10)
  const detail: PeerLoanDetail = {
    ...current,
    closedAt: date,
    writtenOff: round2(current.writtenOff + current.principalRemaining),
    principalRemaining: 0,
  }
  _peerLoanDetails.set(accountId, detail)
  return detail
})

handlers.set(key('POST', '/peer-loans/simulate'), (config) =>
  demoPeerLoanSimulation(JSON.parse(config.data || '{}')))

handlers.set(key('POST', '/peer-loans/from-transaction'), (config) => {
  const body: PeerLoanFromTransactionRequest = JSON.parse(config.data || '{}')
  const transaction = demoTransactionById(body.transactionId)
  const accountId = 1000 + _peerLoanDetails.size
  const name = body.beneficiary?.trim() || transaction?.merchantLabel || transaction?.description || 'Prêt'
  demoNewPeerLoanAccount(accountId, name)
  const detail: PeerLoanDetail = {
    ...demoPeerLoanDetail(accountId),
    accountName: name,
    configured: true,
    principal: Math.abs(transaction?.amount ?? 0),
    lentAt: transaction?.date ?? new Date().toISOString().slice(0, 10),
    annualRate: 0,
    ratePeriod: 'ANNUAL',
    principalRemaining: Math.abs(transaction?.amount ?? 0),
    repayments: [],
    curve: [],
  }
  _peerLoanDetails.set(accountId, detail)
  _peerLoanLinks.set(body.transactionId, { link: { kind: 'LENDING', accountId, accountName: name } })
  return detail
})

handlers.set(key('POST', '/accounts/:id/peer-loan/repayments/from-transaction'), (config, { id }) => {
  const accountId = Number(id)
  const body: PeerLoanRepaymentFromTransactionRequest = JSON.parse(config.data || '{}')
  const transaction = demoTransactionById(body.transactionId)
  const current = demoPeerLoanDetail(accountId)
  // A forged body can smuggle any string through JSON: read it as the full kind union
  // rather than the narrowed request type, mirroring the backend's validation order.
  const kind = (body.kind ?? 'PRINCIPAL') as PeerLoanRepaymentKind
  // Like the backend: an explicit INTEREST row needs accrued interest to pay, but an
  // overpaying linked transaction links anyway — the row already exists, so linking
  // creates no money and the ledger clamps to what the loan can absorb.
  if (kind === 'WRITE_OFF') {
    throw new Error('A write-off is recorded by closing the loan, not by adding a repayment')
  }
  if (kind === 'INTEREST' && (current.interestAccrued <= 0 || (transaction ? Math.abs(transaction.amount) : 0) > current.interestAccrued)) {
    throw new Error('An interest repayment cannot exceed the accrued interest')
  }
  const amount = Math.abs(transaction?.amount ?? 0)
  const repayment: PeerLoanRepayment = {
    id: Date.now(),
    paidOn: transaction?.date ?? new Date().toISOString().slice(0, 10),
    amount,
    kind,
    note: transaction?.description ?? null,
    interestPart: 0,
    capitalPart: amount,
    // The transaction already exists: it is linked, never written, so deleting only unlinks it.
    hasCashLeg: true,
    cashLegOwned: false,
  }
  const applied = Math.min(amount, current.principalRemaining)
  const repaid = current.principalRepaid + applied
  const detail: PeerLoanDetail = {
    ...current,
    principalRemaining: round2(current.principalRemaining - applied),
    principalRepaid: round2(repaid),
    totalRepaid: round2(current.totalRepaid + amount),
    repaidPct: current.principal > 0 ? round2(repaid * 100 / current.principal) : 0,
    repayments: [...current.repayments, repayment],
  }
  _peerLoanDetails.set(accountId, detail)
  _peerLoanLinks.set(body.transactionId, {
    link: { kind: 'REPAYMENT', accountId, accountName: current.accountName },
    repaymentId: repayment.id,
  })
  return detail
})
// The open list and the overview read the same mutable store as the detail: a demo
// Save, repayment or close must move the totals, the picker and the cards together.
handlers.set(key('GET', '/peer-loans/open'), () => [..._peerLoanDetails.values()]
  .filter((loan) => !loan.closedAt)
  .map((loan) => ({ accountId: loan.accountId, name: loan.accountName, remaining: loan.principalRemaining })))
handlers.set(key('GET', '/peer-loans/overview'), () => {
  const loans = [..._peerLoanDetails.values()].map((loan) => ({
    accountId: loan.accountId,
    name: loan.accountName,
    principal: loan.principal,
    principalRemaining: loan.principalRemaining,
    repaidPct: loan.repaidPct,
    interestAccrued: loan.interestAccrued,
    interestReceived: loan.interestReceived,
    lentAt: loan.lentAt,
    expectedBack: loan.expectedBack,
    installmentsBehind: loan.installmentsBehind,
    lastPaymentDate: loan.repayments.at(-1)?.paidOn ?? null,
    lastPaymentAmount: loan.repayments.at(-1)?.amount ?? null,
    closedAt: loan.closedAt,
  })).sort((left, right) =>
    Number(left.closedAt != null) - Number(right.closedAt != null)
    || left.name.localeCompare(right.name))

  const peerAccounts = mockAccounts.filter((account) => account.type === 'PEER_LOAN')
  const dates = peerAccounts.length > 0 ? accountBalanceHistory(peerAccounts[0]) : []
  const history = dates.map((point, index) => ({
    date: point.date,
    outstanding: Math.round(peerAccounts.reduce(
      (sum, account) => sum + (accountBalanceHistory(account)[index]?.balance ?? 0), 0) * 100) / 100,
  }))

  return {
    totalOutstanding: Math.round(loans
      .filter((loan) => loan.closedAt == null)
      .reduce((sum, loan) => sum + loan.principalRemaining, 0) * 100) / 100,
    openCount: loans.filter((loan) => loan.closedAt == null).length,
    closedCount: loans.filter((loan) => loan.closedAt != null).length,
    interestAccrued: Math.round(loans.reduce((sum, loan) => sum + loan.interestAccrued, 0) * 100) / 100,
    loans,
    history,
  }
})

// Live quotes. The holdings fixtures win over the exchange positions for a ticker both carry, so
// re-pricing a holdings table with these leaves its values exactly as the fixture states them.
const demoPrices: Record<string, number> = {}
for (const line of [...Object.values(mockHoldings).flat(), ...demoExchangePositions]) {
  const price = 'currentPrice' in line ? line.currentPrice : line.currentPriceEur
  if (price != null) demoPrices[line.ticker] ??= price
}

// Same catalogue and order as the backend's PreciousMetal registry.
const demoPreciousMetals: PreciousMetalProduct[] = [
  { ticker: 'GOLD:GRAM', name: 'Gold (fine gram)', unit: 'GRAM' },
  { ticker: 'GOLD:NAPOLEON-20F', name: 'Napoléon 20 F', unit: 'COIN' },
  { ticker: 'GOLD:NAPOLEON-10F', name: 'Napoléon 10 F', unit: 'COIN' },
  { ticker: 'GOLD:SWISS-20F', name: '20 Francs Suisse', unit: 'COIN' },
  { ticker: 'GOLD:LATIN-UNION-20F', name: 'Union latine 20 F', unit: 'COIN' },
  { ticker: 'GOLD:TUNISIA-20F', name: '20 Francs Tunisie', unit: 'COIN' },
  { ticker: 'GOLD:SOVEREIGN', name: 'Souverain', unit: 'COIN' },
  { ticker: 'GOLD:SOVEREIGN-EII', name: 'Souverain Elizabeth II', unit: 'COIN' },
  { ticker: 'GOLD:HALF-SOVEREIGN', name: 'Demi-souverain', unit: 'COIN' },
  { ticker: 'GOLD:KRUGERRAND', name: 'Krugerrand', unit: 'COIN' },
  { ticker: 'GOLD:USD-20', name: '20 Dollars US', unit: 'COIN' },
  { ticker: 'GOLD:USD-10', name: '10 Dollars US', unit: 'COIN' },
  { ticker: 'GOLD:USD-5', name: '5 Dollars US', unit: 'COIN' },
  { ticker: 'GOLD:PESO-50', name: '50 Pesos', unit: 'COIN' },
  { ticker: 'GOLD:FLORIN-10', name: '10 Florins', unit: 'COIN' },
  { ticker: 'GOLD:REICHSMARK-20', name: '20 Reichsmark', unit: 'COIN' },
]
handlers.set(key('GET', '/prices/precious-metals'), () => demoPreciousMetals)

handlers.set(key('GET', '/prices'), (config) => {
  const tickers = String(config.params?.tickers ?? '').split(',').map(t => t.trim())
  return Object.fromEntries(tickers.filter(t => t in demoPrices).map(t => [t, demoPrices[t]]))
})

// Both series end exactly on the live quote, so the chart's last point matches the table.
handlers.set(key('GET', '/prices/:ticker/history'), (config, { ticker }) => {
  const price = demoPrices[ticker]
  if (price == null) return []
  const days = Math.round((Number(config.params?.months) || 12) * 30.4)
  return Array.from({ length: days + 1 }, (_, i) => {
    const back = days - i
    return { date: daysFromToday(-back), priceEur: quoteDaysAgo(price, back) }
  })
})

handlers.set(key('GET', '/prices/:ticker/intraday'), (_config, { ticker }) => {
  const price = demoPrices[ticker]
  if (price == null) return []
  return Array.from({ length: 24 }, (_, i) => {
    const back = 23 - i
    return {
      timestamp: hoursAgo(back),
      priceEur: quoteHoursAgo(price, back),
    }
  })
})

// Account details: transactions for all accounts
for (let i = 1; i <= mockAccounts.length; i++) {
  handlers.set(key('GET', `/accounts/${i}/transactions`), (config) =>
    withPeerLoanLinks(i === mockAccounts.find((account) => account.type === 'CHECKING')?.id
      ? mockTransactionSearch({ ...config.params, accountId: i })
      : mockTransactions[i] ?? []))
}

// Pocket rename (accounts 9 and 10) — re-uses the standard PUT /accounts/:id shape.
// Reassigns _demoAccounts to a NEW array so TanStack Query detects the change
// (replaceEqualDeep bails early on same-reference without inspecting contents).
handlers.set(key('PUT', '/accounts/9'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const updated = { ..._demoAccounts[8], ...body }
  _demoAccounts = _demoAccounts.map((a, i) => i === 8 ? updated : a)
  return updated
})
handlers.set(key('PUT', '/accounts/10'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const updated = { ..._demoAccounts[9], ...body }
  _demoAccounts = _demoAccounts.map((a, i) => i === 9 ? updated : a)
  return updated
})

// ─── Savings livrets ─────────────────────────────────────────────────────────

// GET /savings/suggestions — only accounts without an existing config
handlers.set(key('GET', '/savings/suggestions'), () => {
  const withoutConfig = _demoAccounts.filter(
    a => (a.type === 'SAVINGS' || a.type === 'LEP') && !a.savingsConfig
  )
  return withoutConfig.map(a => ({
    accountId: a.id,
    accountName: a.name,
    suggestedProduct: a.type === 'LEP' ? 'LEP' : 'LIVRET_A',
    defaultAnnualRate: a.type === 'LEP' ? 3.50 : 2.40,
    uncertain: false,
  }))
})

// PUT /accounts/{id}/savings-config — update savingsConfig on the account
// Uses a regex-based approach since handlers.set uses exact string keys
// We need to handle this for savings accounts (ids 1 and 7 in demo)
handlers.set(key('PUT', '/accounts/1/savings-config'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const updated = { ..._demoAccounts[0], savingsConfig: body }
  _demoAccounts = _demoAccounts.map((a, i) => i === 0 ? updated : a)
  return updated
})
handlers.set(key('PUT', '/accounts/7/savings-config'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const updated = { ..._demoAccounts[6], savingsConfig: body }
  _demoAccounts = _demoAccounts.map((a, i) => i === 6 ? updated : a)
  return updated
})

// DELETE /accounts/{id}/savings-config — remove savingsConfig
handlers.set(key('DELETE', '/accounts/1/savings-config'), () => {
  const updated = { ..._demoAccounts[0], savingsConfig: null }
  _demoAccounts = _demoAccounts.map((a, i) => i === 0 ? updated : a)
  return null
})
handlers.set(key('DELETE', '/accounts/7/savings-config'), () => {
  const updated = { ..._demoAccounts[6], savingsConfig: null }
  _demoAccounts = _demoAccounts.map((a, i) => i === 6 ? updated : a)
  return null
})

// GET /accounts/{id}/savings-interest — projection data
function generateSavingsInterest(accountId: number) {
  const account = _demoAccounts.find(a => a.id === accountId)
  const rate = account?.savingsConfig?.annualRate ?? (account?.type === 'LEP' ? 3.50 : 2.40)
  const balance = account?.currentBalance ?? 5000
  const now = new Date()
  const dayOfYear = Math.floor((now.getTime() - new Date(now.getFullYear(), 0, 0).getTime()) / 86400000)
  const estimatedInterestYtd = balance * (rate / 100) * (dayOfYear / 365)
  const projectedInterestFullYear = balance * (rate / 100)
  return {
    estimatedInterestYtd: Math.round(estimatedInterestYtd * 100) / 100,
    projectedInterestFullYear: Math.round(projectedInterestFullYear * 100) / 100,
    nextCapitalizationDate: `${now.getFullYear()}-12-31`,
    annualRatePct: rate,
    basis: account?.savingsConfig?.rateBasis ?? 'NET',
    netOfTax: true,
  }
}
handlers.set(key('GET', '/accounts/:id/savings-interest'), (_config, { id }) => generateSavingsInterest(Number(id)))

// Realized P&L on closed positions. PEA (id=2) shows a green + a red closed lot;
// the other holding accounts report nothing realized yet.
handlers.set(key('GET', '/accounts/2/realized-pnl'), () => ({
  currency: 'EUR',
  realizedTotal: 420.5,
  byTicker: [
    { ticker: 'AAPL', name: 'Apple Inc.', realized: 512, quantitySold: 8, proceeds: 1512, costBasis: 1000, warning: false },
    { ticker: 'TSLA', name: 'Tesla Inc.', realized: -91.5, quantitySold: 3, proceeds: 660, costBasis: 751.5, warning: false },
  ],
  lots: [
    { ticker: 'AAPL', name: 'Apple Inc.', date: '2024-05-14', quantity: 8, avgCost: 125, proceeds: 1512, realized: 512 },
    { ticker: 'TSLA', name: 'Tesla Inc.', date: '2024-09-02', quantity: 3, avgCost: 250.5, proceeds: 660, realized: -91.5 },
  ],
}))
handlers.set(key('GET', '/accounts/:id/realized-pnl'), () => ({
  currency: 'EUR', realizedTotal: 0, byTicker: [], lots: [],
}))

const r2 = (n: number): number => Math.round(n * 100) / 100

handlers.set(key('GET', '/accounts/:id/loan-summary'), (_config, { id }) =>
  demoLoanSchedule(_demoAccounts.find(a => a.id === Number(id))?.debt))

// CSV transaction import wizard (holding accounts). Preview returns a French-style
// sample (semicolon delimiter, comma decimals); execute reports a canned result.
for (const i of [2, 3, 6]) {
  handlers.set(key('POST', `/accounts/${i}/transactions/import/preview`), () => ({
    fileToken: 'demo-token',
    detectedColumns: ['Date', 'Sens', 'ISIN', 'Quantité', 'Cours', 'Frais'],
    sampleRows: [
      ['15/01/2024', 'Achat', 'IE00B4L5Y983', '10', '85,20', '1,00'],
      ['02/06/2024', 'Vente', 'IE00B4L5Y983', '10', '92,50', '1,00'],
    ],
    totalRows: 2,
    hasHeaderRow: true,
    dialect: { delimiter: ';', decimal: 'COMMA', dateFormat: 'dd/MM/yyyy' },
    suggestedMapping: { date: 0, side: 1, tickerOrIsin: 2, name: null, quantity: 3, unitPrice: 4, fees: 5, currency: null, amount: null },
  }))
  handlers.set(key('POST', `/accounts/${i}/transactions/import`), () => ({
    imported: 2, skipped: 0, errors: [],
  }))
}

// Security insight (asset type + ETF composition). Mirrors the backend
// SecurityInsightResponse: { ticker, assetType, composition | null }.
const demoStockTickers = ['AAPL', 'MSFT', 'AMZN', 'NVDA']
const demoCryptoTickers = ['BTC', 'ETH', 'SOL']
const demoEtfCompositions: Record<string, { companies: [string, number][]; countries: [string, number][]; sectors: [string, number][] }> = {
  IWDA: {
    companies: [['Apple', 5.1], ['Microsoft', 4.4], ['Nvidia', 4.0], ['Amazon', 2.7], ['Meta Platforms', 1.9], ['Alphabet A', 1.7], ['Alphabet C', 1.5], ['Broadcom', 1.3], ['Eli Lilly', 0.9], ['JPMorgan Chase', 0.8]],
    countries: [['US', 70.8], ['JP', 6.0], ['GB', 3.7], ['FR', 3.1], ['CA', 3.0], ['CH', 2.6], ['DE', 2.3], ['AU', 1.8]],
    sectors: [['technology', 24.1], ['financial_services', 16.4], ['healthcare', 11.2], ['industrials', 10.7], ['consumer_cyclical', 10.2], ['communication_services', 7.6], ['consumer_defensive', 6.1], ['energy', 4.0], ['basic_materials', 3.6], ['utilities', 2.7]],
  },
  EUNL: {
    companies: [['Apple', 7.1], ['Microsoft', 6.6], ['Nvidia', 6.1], ['Amazon', 3.8], ['Meta Platforms', 2.6], ['Alphabet A', 2.3], ['Alphabet C', 2.0], ['Broadcom', 1.8], ['Berkshire Hathaway', 1.6], ['Eli Lilly', 1.3]],
    countries: [['US', 100.0]],
    sectors: [['technology', 31.2], ['financial_services', 13.1], ['healthcare', 11.6], ['consumer_cyclical', 10.3], ['communication_services', 9.1], ['industrials', 8.6], ['consumer_defensive', 5.9], ['energy', 3.7], ['utilities', 2.5], ['basic_materials', 2.2]],
  },
}

function demoInsight(ticker: string) {
  if (demoStockTickers.includes(ticker)) {
    return { ticker, assetType: 'STOCK', composition: null }
  }
  if (demoCryptoTickers.includes(ticker)) {
    return { ticker, assetType: 'CRYPTO', composition: null }
  }
  const comp = demoEtfCompositions[ticker]
  if (comp) {
    const toSlices = (pairs: [string, number][]) => pairs.map(([label, percent]) => ({ label, percent }))
    return {
      ticker,
      assetType: 'ETF',
      composition: {
        companies: toSlices(comp.companies),
        countries: toSlices(comp.countries),
        sectors: toSlices(comp.sectors),
        source: 'Boursorama',
        asOf: new Date().toISOString().split('T')[0],
      },
    }
  }
  return { ticker, assetType: 'UNKNOWN', composition: null }
}

handlers.set(key('GET', '/securities/:ticker/insight'), (_config, { ticker }) => demoInsight(ticker))

// Account details: month-start balances then today's, the same points /history sums.
// The live point is the account as the demo holds it now, so an edit shows in the last point.
handlers.set(key('GET', '/accounts/:id/history'), (_config, { id }) => {
  const account = _demoAccounts.find(a => a.id === Number(id))
  return account ? accountBalanceHistory(account).map((point, i) => ({ id: 100 + i, ...point })) : []
})

// ─── Real estate ─────────────────────────────────────────────────────────────
// Every route the property UI touches needs a handler: the demo adapter answers `{}` for
// anything unmatched, and the pages would then read fields off an empty object.

const demoProperty = mockAccounts.find((a) => a.id === 11)!
const demoMortgage = mockAccounts.find((a) => a.id === 15)!

function demoPaperSummary() {
  const paper = _demoAccounts.filter(account => account.type === 'SCPI').map(account => ({
    accountId: account.id,
    name: account.name,
    color: account.color,
    managementCompany: account.scpi?.managementCompany ?? null,
    shareCount: account.scpi?.shareCount ?? null,
    sharePercent: 100,
    withdrawalPriceEur: account.scpi?.withdrawalPriceEur ?? null,
    subscriptionPriceEur: account.scpi?.subscriptionPriceEur ?? null,
    grossValue: account.currentBalanceEur,
    outstandingDebt: 0,
    netValue: account.currentBalanceEur,
    valuationStatus: account.scpi?.valuationStatus ?? 'PRICE_INCOMPLETE',
    loans: [],
  }))
  const paperGross = paper.reduce((total, line) => total + line.grossValue, 0)
  return { paperGross, paperDebt: 0, paperNet: paperGross, paper }
}

handlers.set(key('GET', '/real-estate/summary'), () => ({
  grossValue: 412000,
  outstandingDebt: 168400,
  netValue: 243600,
  costBasis: 368800,
  unrealizedGain: 43200,
  unrealizedGainPercent: 11.71,
  loanToValue: 40.87,
  monthlyRentalIncome: 0,
  ...demoPaperSummary(),
  properties: [{
    accountId: 11,
    name: demoProperty.name,
    color: demoProperty.color,
    propertyType: 'HOUSE',
    category: 'PRIMARY_RESIDENCE',
    city: 'Bordeaux',
    sharePercent: 100,
    grossValue: 412000,
    outstandingDebt: 168400,
    netValue: 243600,
    costBasis: 368800,
    unrealizedGain: 43200,
    surfaceArea: 95,
    rentalIncome: 0,
    valuationMode: 'ESTIMATED',
    lastValuedAt: monthStartFromToday(0),
    lastConfidence: 'HIGH',
    loans: [{
      accountId: demoMortgage.id,
      name: demoMortgage.name,
      lenderName: demoMortgage.debt!.lenderName,
      outstandingBalance: demoMortgage.currentBalanceEur,
      sharePercent: 100,
      monthlyPayment: demoMortgage.debt!.monthlyPayment,
      endDate: demoMortgage.debt!.endDate,
    }],
  }],
}))

handlers.set(key('GET', '/real-estate/11/valuations'), () => {
  const points = [395000, 398000, 401500, 404000, 407500, 409000, 412000]
  return points.map((value, i) => ({
    valuedAt: monthStartFromToday(i - (points.length - 1)),
    estimatedValue: value,
    lowValue: Math.round(value * 0.88),
    highValue: Math.round(value * 1.14),
    pricePerSqm: Math.round(value / 95),
    provider: 'CEREMA_DV3F',
    confidence: 'HIGH',
    sampleSize: 1048,
    sourceYear: 2025,
  })).reverse()
})
handlers.set(key('GET', '/real-estate/:id/valuations'), () => [])

handlers.set(key('POST', '/accounts/11/valuation/refresh'), () => ({
  status: 'OK',
  mode: 'ESTIMATED',
  appliedToBalance: true,
  estimatedValue: 412000,
  lowValue: 362560,
  highValue: 469680,
  pricePerSqm: 4336,
  sampleSize: 1048,
  confidence: 'HIGH',
  sourceYear: 2025,
  provider: 'CEREMA_DV3F',
  scale: 'communes',
  valuedAt: daysFromToday(0),
  reindexRatio: 1.021,
  adjustments: [
    { code: 'GARDEN', factor: 0.02, sqm: null, amount: 8080 },
    { code: 'TERRACE', factor: 0.03, sqm: null, amount: 12120 },
    { code: 'GARAGE', factor: null, sqm: 12, amount: 52032 },
  ],
}))

handlers.set(key('GET', '/accounts/:id/ownership'), () => ({
  shares: [{ memberId: 1, displayName: 'Demo', avatarColor: '#6366f1', sharePercent: 100, isOwner: true }],
  totalAssigned: 100,
  unassigned: 0,
}))
handlers.set(key('PUT', '/accounts/:id/ownership'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const shares = (body.shares ?? []) as { memberId: number; sharePercent: number }[]
  const total = shares.reduce((sum, s) => sum + s.sharePercent, 0)
  return {
    shares: shares.map((s) => ({
      memberId: s.memberId,
      displayName: 'Demo',
      avatarColor: '#6366f1',
      sharePercent: s.sharePercent,
      isOwner: s.memberId === 1,
    })),
    totalAssigned: total,
    unassigned: 100 - total,
  }
})

// Address autocomplete. Returns a fixed match so the field behaves without reaching IGN.
handlers.set(key('GET', '/geocode'), () => ([
  {
    label: '12 Rue de la République 33000 Bordeaux',
    score: 0.94,
    postcode: '33000',
    city: 'Bordeaux',
    inseeCode: '33063',
    latitude: 44.8378,
    longitude: -0.5792,
  },
]))

/** The `accountIds=2,3,6` query parameter, or `fallback` when the request names none. */
function requestedAccountIds(config: InternalAxiosRequestConfig, fallback: number[]): number[] {
  const ids = String(config.params?.accountIds ?? '').split(',').map(Number).filter(n => n > 0)
  return ids.length ? ids : fallback
}

// Aggregate net worth history (dashboard chart; the Accounts page asks for split=true to get
// the per-account breakdown its PnL reads).
handlers.set(key('GET', '/history'), (config) => {
  const params = (config.params ?? {}) as Record<string, string>
  return netWorthHistory(
    requestedAccountIds(config, mockAccounts.map(a => a.id)),
    Number(params.months ?? 12),
    String(params.split) === 'true',
    _demoAccounts,
  )
})

// PnL summary (dashboard header + account detail)
// Live figures from the history the chart draws. The range reprices the holdings at the quote
// /prices/:ticker/history gives for `from`, as HistoryService does with its price snapshots.
const demoTopLevelIds = mockAccounts.filter(a => a.parentAccountId == null).map(a => a.id)
handlers.set(key('GET', '/history/pnl'), (config) => {
  const ids = requestedAccountIds(config, demoTopLevelIds)
  const { total, invested, pnl } = netWorthHistory(ids, 1, false, _demoAccounts).at(-1)!
  const live = { total, invested, pnl, pnlPercent: invested > 0 ? Math.round((pnl / invested) * 1000) / 10 : null }
  const from = config.params?.from
  // HistoryService returns the live figures alone without a range or a holding to price over it.
  if (!from || holdingsValue(ids) === 0) return { ...live, valueAtFrom: null, rangePnl: null, rangePnlPercent: null }
  const valueAtFrom = holdingsValue(ids, daysBeforeToday(String(from)))
  const rangePnl = r2(holdingsValue(ids) - valueAtFrom)
  return { ...live, valueAtFrom, rangePnl, rangePnlPercent: Math.round((rangePnl / valueAtFrom) * 1000) / 10 }
})

// Intraday net worth (24H dashboard range), as HistoryService builds it: the holdings repriced at
// each hour's /prices/:ticker/intraday quote, every other account at its live balance all day.
handlers.set(key('GET', '/history/net-worth/intraday'), (config) => {
  const ids = requestedAccountIds(config, demoTopLevelIds)
  const { total, invested } = netWorthHistory(ids, 1, false, _demoAccounts).at(-1)!
  const withoutHoldings = total - holdingsValue(ids)
  return Array.from({ length: 24 }, (_, i) => {
    const back = 23 - i
    return { timestamp: hoursAgo(back), total: r2(withoutHoldings + holdingsValueHoursAgo(ids, back)), invested }
  })
})

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
const demoGoal = (id: string) => mockGoals.find(g => g.id === Number(id))
handlers.set(key('GET', '/goals/:id'), (_config, { id }) => demoGoal(id))
handlers.set(key('GET', '/goals/:id/months'), (_config, { id }) => mockGoalMonths[Number(id)] ?? [])
handlers.set(key('POST', '/goals/:id/history/extend'), (_config, { id }) => demoGoal(id))
handlers.set(key('POST', '/goals/:id/history/extend/month'), (_config, { id }) => demoGoal(id))
/** The holding name behind a ticker in a demo account, for the split's display. */
function demoHoldingName(accountId: number | undefined, ticker: string): string | null {
  if (accountId == null) return null
  return mockHoldings[accountId]?.find(h => h.ticker === ticker)?.name ?? null
}

handlers.set(key('POST', '/goals'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockGoals[0],
    id: Date.now(),
    allocations: (body.allocations ?? []).map(
      (line: { ticker: string; monthlyAmount: number }) => ({
        ticker: line.ticker,
        name: demoHoldingName(body.accountIds?.[0], line.ticker),
        monthlyAmount: line.monthlyAmount,
      }),
    ),
    name: body.name ?? 'New Goal',
    targetAmount: body.targetAmount ?? 0,
    deadline: body.deadline ?? '2026-01-01',
    accounts: (body.accountIds ?? []).map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    currentTotal: 0,
    percentComplete: 0,
    monthsLeft: 6,
    monthlyNeeded: 0,
    avgMonthlyContribution: null,
    isOnTrack: true,
    surplus: 0,
  }
})
// Every mock goal, not just the first three: the recurring plan is id 4, and leaving it out
// meant editing or deleting it in demo mode resolved to {} -- a silent no-op that looked like a
// broken save.
for (let i = 1; i <= mockGoals.length; i++) {
  handlers.set(key('PUT', `/goals/${i}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    return {
      ...mockGoals[i - 1],
      name: body.name ?? mockGoals[i - 1].name,
      targetAmount: body.targetAmount ?? mockGoals[i - 1].targetAmount,
      deadline: body.deadline ?? mockGoals[i - 1].deadline,
      monthlyAmount: body.monthlyAmount ?? mockGoals[i - 1].monthlyAmount,
      // Names come from the account's holdings on the real backend; the demo resolves them
      // from the same fixture the picker reads.
      allocations: (body.allocations ?? []).map(
        (line: { ticker: string; monthlyAmount: number }) => ({
          ticker: line.ticker,
          name: demoHoldingName(body.accountIds?.[0], line.ticker),
          monthlyAmount: line.monthlyAmount,
        }),
      ),
      accounts: (body.accountIds ?? mockGoals[i - 1].accounts.map(a => a.id))
        .map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    }
  })
  handlers.set(key('DELETE', `/goals/${i}`), () => null)
}

// Sync
const DEMO_INSTITUTIONS = [
  { id: 'BNP Paribas::FR::personal', name: 'BNP Paribas', bic: 'BNPAFRPP', logoUrl: null, country: 'FR', psuType: 'personal' },
  { id: 'BoursoBank::FR::personal', name: 'BoursoBank', bic: 'BNPAFRPP', logoUrl: null, country: 'FR', psuType: 'personal' },
  { id: 'Swan::FR::business', name: 'Swan', bic: 'SWNBFR22', logoUrl: null, country: 'FR', psuType: 'business' },
  { id: 'Deutsche Bank::DE::personal', name: 'Deutsche Bank', bic: 'DEUTDEFF', logoUrl: null, country: 'DE', psuType: 'personal' },
  { id: 'LHV Pank::EE::personal', name: 'LHV Pank', bic: 'LHVBEE22', logoUrl: null, country: 'EE', psuType: 'personal' },
]
handlers.set(key('GET', '/sync/status'), () => mockRequisitions)
handlers.set(key('GET', '/sync/institutions'), (config) => {
  const params = (config.params ?? {}) as { query?: string; country?: string }
  const country = params.country || 'FR'
  const query = (params.query ?? '').toLowerCase()
  return DEMO_INSTITUTIONS.filter((inst) =>
    inst.country === country && (query === '' || inst.name.toLowerCase().includes(query)),
  )
})
handlers.set(key('GET', '/sync/countries'), () => ['FR', 'DE', 'EE'])

// Crypto exchange
handlers.set(key('GET', '/crypto/exchange/status'), () => mockExchangeStatuses)

// Crypto wallet
handlers.set(key('GET', '/crypto/wallet'), () => mockWalletStatuses)

// Sync - initiate
handlers.set(key('POST', '/sync/initiate'), () => ({
  requisitionId: 'demo-req-' + Date.now(),
  authLink: 'https://demo.enablebanking.com/auth?demo=true',
}))

// Sync - complete (real backend: POST /api/sync/complete with { code, state })
handlers.set(key('POST', '/sync/complete'), () => ([
  { id: 100, name: 'Demo Bank Account', type: 'CHECKING' as const, provider: 'Demo Bank', currency: 'EUR', currentBalance: 5000, currentBalanceEur: 5000, lastSyncedAt: new Date().toISOString(), isManual: false, color: '#3b82f6', ticker: null, createdAt: new Date().toISOString() }
]))

// Sync - retry
handlers.set(key('POST', '/sync/1/retry'), () => [])

// Sync - reconnect (re-initiate OAuth for a dead requisition)
handlers.set(key('POST', '/sync/1/reconnect'), () => ({
  requisitionId: 'demo-req-reconnect',
  authLink: 'https://demo.enablebanking.com/auth?demo=true',
}))

// Sync - delete
handlers.set(key('DELETE', '/sync/1'), () => null)

// Interactive Brokers — same demo convention as Trade Republic below: reads report a
// disconnected state, mutations fake-succeed with the real response shapes (without
// these, unmapped routes resolve `{}` and the tab silently misbehaves).
handlers.set(key('GET', '/ibkr/status'), () => ({
  connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null,
}))
handlers.set(key('POST', '/ibkr/connect'), () => null)
handlers.set(key('POST', '/ibkr/sync'), () => [])
handlers.set(key('DELETE', '/ibkr/connection'), () => null)

// SimpleFIN keeps its status in memory so the demo can walk the whole flow:
// connect flips the panel to connected, sync stamps lastSyncedAt, disconnect resets.
// A page reload starts disconnected again.
const demoSimplefin = {
  connected: false,
  connectionId: null as number | null,
  status: null as string | null,
  lastSyncedAt: null as string | null,
  maskedToken: null as string | null,
}
handlers.set(key('GET', '/simplefin/status'), () => ({ ...demoSimplefin }))
handlers.set(key('POST', '/simplefin/connect'), () => {
  demoSimplefin.connected = true
  demoSimplefin.connectionId = 1
  demoSimplefin.status = 'CONNECTED'
  demoSimplefin.maskedToken = '••••demo'
  return null
})
handlers.set(key('POST', '/simplefin/sync'), () => {
  demoSimplefin.lastSyncedAt = new Date().toISOString()
  return []
})
handlers.set(key('DELETE', '/simplefin/connection'), () => {
  demoSimplefin.connected = false
  demoSimplefin.connectionId = null
  demoSimplefin.status = null
  demoSimplefin.lastSyncedAt = null
  demoSimplefin.maskedToken = null
  return null
})

// Wise jars follow the SimpleFIN demo: connect imports the jars at once (no jar accounts
// exist in the demo data, so the list is empty), sync stamps lastSyncedAt, disconnect resets.
const demoWise = {
  connected: false,
  connectionId: null as number | null,
  status: null as string | null,
  lastSyncedAt: null as string | null,
  maskedToken: null as string | null,
}
handlers.set(key('GET', '/wise/status'), () => ({ ...demoWise }))
handlers.set(key('POST', '/wise/connect'), () => {
  demoWise.connected = true
  demoWise.connectionId = 1
  demoWise.status = 'CONNECTED'
  demoWise.maskedToken = '••••demo'
  demoWise.lastSyncedAt = new Date().toISOString()
  return []
})
handlers.set(key('POST', '/wise/sync'), () => {
  demoWise.lastSyncedAt = new Date().toISOString()
  return []
})
handlers.set(key('DELETE', '/wise/connection'), () => {
  demoWise.connected = false
  demoWise.connectionId = null
  demoWise.status = null
  demoWise.lastSyncedAt = null
  demoWise.maskedToken = null
  return null
})

// Amundi Épargne Salariale — same demo convention: reads report a disconnected
// session, mutations fake-succeed with the real response shapes.
const demoAmundiStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
handlers.set(key('GET', '/amundi/status'), () => demoAmundiStatus)
handlers.set(key('POST', '/amundi/auth/initiate'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/amundi/auth/complete'), () => demoAmundiStatus)
handlers.set(key('POST', '/amundi/sync'), () => demoAmundiStatus)
handlers.set(key('DELETE', '/amundi/session'), () => null)

// Crédit Mutuel Épargne Salariale — same convention. Sync all lists CMES as an
// expired session whenever its status is not IDLE, so the `{}` fallback would
// show a phantom connection.
const demoCmesStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
handlers.set(key('GET', '/cmes/status'), () => demoCmesStatus)
handlers.set(key('POST', '/cmes/auth/initiate'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/cmes/auth/complete'), () => demoCmesStatus)
handlers.set(key('POST', '/cmes/sync'), () => demoCmesStatus)
handlers.set(key('DELETE', '/cmes/session'), () => null)

// Natixis Interépargne — simulates the SMS-challenge login so Connect in demo
// mode ends on an active session instead of an unexplained "No active session".
const NATIXIS_DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
let demoNatixisStatus: typeof NATIXIS_DISCONNECTED | Record<string, unknown> = NATIXIS_DISCONNECTED
const activateDemoNatixis = () => {
  const now = new Date().toISOString()
  demoNatixisStatus = {
    isActive: true,
    syncStatus: 'SUCCESS',
    lastSyncStartedAt: now,
    lastSyncCompletedAt: now,
    lastSyncError: null,
  }
  return demoNatixisStatus
}
handlers.set(key('GET', '/natixis/status'), () => demoNatixisStatus)
handlers.set(key('POST', '/natixis/auth/initiate'), () => ({
  processId: 'demo-natixis', mfaRequired: true, mfaType: 'SMS',
}))
handlers.set(key('POST', '/natixis/auth/complete'), activateDemoNatixis)
handlers.set(key('POST', '/natixis/sync'), () => (demoNatixisStatus.isActive ? activateDemoNatixis() : demoNatixisStatus))
handlers.set(key('DELETE', '/natixis/session'), () => {
  demoNatixisStatus = NATIXIS_DISCONNECTED
  return null
})

// BoursoBank — same convention. Its demo accounts already carry
// `provider: 'BoursoBank'`, so without these the Sync-all modal would list a
// connection whose status request falls through to `{}`.
const demoBoursoStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
handlers.set(key('GET', '/bourso/status'), () => demoBoursoStatus)
handlers.set(key('POST', '/bourso/auth/initiate'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/bourso/auth/complete'), () => demoBoursoStatus)
handlers.set(key('POST', '/bourso/sync'), () => demoBoursoStatus)
handlers.set(key('DELETE', '/bourso/session'), () => null)

// Caisse d'Epargne — Sécur'Pass is a human step, so the demo skips it: initiate
// answers a pending process and complete answers connected straight away.
const demoCaisseEpargneStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
}
handlers.set(key('GET', '/caisse-epargne/status'), () => demoCaisseEpargneStatus)
const DEMO_PAD_PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
handlers.set(key('POST', '/caisse-epargne/auth/initiate'), () => ({
  processId: 'demo',
  keypad: { images: Array.from({ length: 10 }, () => DEMO_PAD_PNG), columns: 5 },
  expiresInSeconds: 90,
}))
handlers.set(key('POST', '/caisse-epargne/auth/keypad'), () => ({
  processId: 'demo', status: 'SECURPASS_PENDING',
}))
handlers.set(key('POST', '/caisse-epargne/auth/complete'), () => ({ connected: true }))
handlers.set(key('POST', '/caisse-epargne/sync'), () => demoCaisseEpargneStatus)
handlers.set(key('DELETE', '/caisse-epargne/session'), () => ({
  removed: true, bankSessionRevoked: false, message: '',
}))

// La Banque Postale — the mobile approval is a human step, so the demo answers
// the keypad then a completed session; the status stays consistent across reads.
let demoLbpAuthenticated = false
let demoLbpSynced = false
const demoLbpStatus = () => ({
  isActive: demoLbpAuthenticated,
  syncStatus: demoLbpSynced ? 'SUCCESS' : 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: demoLbpSynced ? '2026-11-06T12:00:00Z' : null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
  savedIdentifier: null,
})
handlers.set(key('GET', '/lbp/status'), () => demoLbpStatus())
handlers.set(key('POST', '/lbp/auth/initiate'), () => ({
  processId: 'demo',
  keypad: { keys: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'], columns: 5 },
  expiresInSeconds: 300,
}))
handlers.set(key('POST', '/lbp/auth/keypad'), () => ({
  processId: 'demo', status: 'APPROVAL_PENDING',
}))
handlers.set(key('POST', '/lbp/auth/complete'), () => {
  demoLbpAuthenticated = true
  return demoLbpStatus()
})
handlers.set(key('POST', '/lbp/sync'), () => {
  demoLbpAuthenticated = true
  demoLbpSynced = true
  return demoLbpStatus()
})
handlers.set(key('DELETE', '/lbp/session'), () => {
  demoLbpAuthenticated = false
  demoLbpSynced = false
  return { removed: true, bankSessionRevoked: false, message: '' }
})

// BNP Paribas / Hello bank! — same shortcut: a demo keypad (text keys), an app approval, then a session.
let demoBnpAuthenticated = false
let demoBnpSynced = false
const demoBnpStatus = () => ({
  isActive: demoBnpAuthenticated,
  syncStatus: demoBnpSynced ? 'SUCCESS' : 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: demoBnpSynced ? '2026-11-06T12:00:00Z' : null,
  lastSyncError: null,
  unsupported: [],
  unsupportedCount: 0,
})
handlers.set(key('GET', '/bnp/status'), () => demoBnpStatus())
handlers.set(key('POST', '/bnp/auth/initiate'), () => ({
  processId: 'demo',
  keypad: { keys: ['3', '8', '1', '0', '5', '9', '2', '7', '4', '6'], columns: 5 },
  expiresInSeconds: 300,
}))
handlers.set(key('POST', '/bnp/auth/keypad'), () => ({
  processId: 'demo', status: 'APPROVAL_PENDING',
}))
handlers.set(key('POST', '/bnp/auth/complete'), () => {
  demoBnpAuthenticated = true
  return demoBnpStatus()
})
handlers.set(key('POST', '/bnp/sync'), () => {
  demoBnpAuthenticated = true
  demoBnpSynced = true
  return demoBnpStatus()
})
handlers.set(key('DELETE', '/bnp/session'), () => {
  demoBnpAuthenticated = false
  demoBnpSynced = false
  return { removed: true, bankSessionRevoked: false, message: '' }
})

// Banque Populaire — the same BPCE login as Caisse d'Epargne, so the same shortcut:
// any region and identifier get the demo pad, and Sécur'Pass answers connected at once.
// Completing the sign-in makes the session live but not yet synced; only the sync answers
// SUCCESS, so the panel sees SUCCESS from its own sync and closes the add-account wizard.
// The sync stamps its time once and every status read repeats it: a fresh timestamp per read
// would look like a new sync to useProviderStatus and refetch the account views forever.
// The first sync also adds the connector's accounts, and later syncs and reconnects leave them
// be; disconnecting resets the status and the stamp but keeps the accounts, as the backend does.
let demoBanquePopulaireState: 'idle' | 'connected' | 'synced' = 'idle'
let demoBanquePopulaireSyncedAt: string | null = null
const demoBanquePopulaireStatus = () => {
  if (demoBanquePopulaireState === 'idle') return { ...demoCaisseEpargneStatus }
  if (demoBanquePopulaireState === 'connected') return { ...demoCaisseEpargneStatus, isActive: true }
  return {
    ...demoCaisseEpargneStatus,
    isActive: true,
    syncStatus: 'SUCCESS',
    lastSyncStartedAt: demoBanquePopulaireSyncedAt,
    lastSyncCompletedAt: demoBanquePopulaireSyncedAt,
  }
}
handlers.set(key('GET', '/banque-populaire/status'), () => demoBanquePopulaireStatus())
handlers.set(key('POST', '/banque-populaire/auth/initiate'), () => ({
  processId: 'demo',
  keypad: { images: Array.from({ length: 10 }, () => DEMO_PAD_PNG), columns: 5 },
  expiresInSeconds: 90,
}))
handlers.set(key('POST', '/banque-populaire/auth/keypad'), () => ({
  processId: 'demo', status: 'SECURPASS_PENDING',
}))
handlers.set(key('POST', '/banque-populaire/auth/complete'), () => {
  demoBanquePopulaireState = 'connected'
  return { connected: true }
})
handlers.set(key('POST', '/banque-populaire/sync'), () => {
  demoBanquePopulaireState = 'synced'
  const syncedAt = new Date().toISOString()
  demoBanquePopulaireSyncedAt = syncedAt
  // A later sync restamps the accounts it already made (createdAt kept) and adds none twice.
  const fixtureIds = new Set(mockBanquePopulaireAccounts.map(a => a.id))
  const known = new Set(_demoAccounts.map(a => a.id))
  const added = mockBanquePopulaireAccounts
    .filter(a => !known.has(a.id))
    .map(a => ({ ...a, createdAt: syncedAt, lastSyncedAt: syncedAt }))
  _demoAccounts = [
    ..._demoAccounts.map(a => (fixtureIds.has(a.id) ? { ...a, lastSyncedAt: syncedAt } : a)),
    ...added,
  ]
  return demoBanquePopulaireStatus()
})
for (const { id } of mockBanquePopulaireAccounts) {
  handlers.set(key('GET', `/accounts/${id}`), () => _demoAccounts.find(a => a.id === id))
  handlers.set(key('GET', `/accounts/${id}/transactions`), () => [])
}
handlers.set(key('DELETE', '/banque-populaire/session'), () => {
  demoBanquePopulaireState = 'idle'
  demoBanquePopulaireSyncedAt = null
  return { removed: true, bankSessionRevoked: false, message: '' }
})

// Demo authentication bypasses the bank's mobile/SMS confirmation.
const demoCicStatus: CicSessionStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  canReconnect: false,
}
handlers.set(key('GET', '/cic/status'), () => demoCicStatus)
handlers.set(key('POST', '/cic/auth/initiate'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/cic/auth/reconnect'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/cic/auth/complete'), () => demoCicStatus)
handlers.set(key('POST', '/cic/sync'), () => demoCicStatus)
handlers.set(key('DELETE', '/cic/session'), () => null)

// Fortuneo — its frontend validates every response with Zod, so the demo must
// return the complete production contract rather than relying on the adapter's
// permissive `{}` fallback.
const demoFortuneoStatus = {
  isActive: false,
  expiresAt: null,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
handlers.set(key('GET', '/fortuneo/status'), () => demoFortuneoStatus)
handlers.set(key('POST', '/fortuneo/auth/initiate'), () => ({
  processId: null, mfaRequired: false, mfaType: null,
}))
handlers.set(key('POST', '/fortuneo/auth/complete'), () => demoFortuneoStatus)
handlers.set(key('POST', '/fortuneo/sync'), () => demoFortuneoStatus)
handlers.set(key('DELETE', '/fortuneo/session'), () => null)

// The other sidecar providers: a session that was never opened.
const demoIdleSidecar = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
handlers.set(key('GET', '/bourse-direct/status'), () => ({ ...demoIdleSidecar, expiresAt: null }))
handlers.set(key('GET', '/amex/status'), () => demoIdleSidecar)
handlers.set(key('GET', '/corum/status'), () => demoIdleSidecar)
handlers.set(key('GET', '/sofidy/status'), () => demoIdleSidecar)
handlers.set(key('GET', '/degiro/status'), () => ({ isActive: false, status: null, lastSyncedAt: null }))
handlers.set(key('GET', '/finary/status'), () => ({
  connected: false, sessionId: null, status: null, lastSyncedAt: null, maskedEmail: null,
}))

// Revolut and Trade Republic progress is only polled while a sync runs; idle reads as finished.
const demoIdleSyncProgress = {
  running: false,
  phase: null,
  elapsedSeconds: null,
  remainingSeconds: null,
  accountsFound: null,
  done: true,
  error: null,
  errorCode: null,
  discovered: [],
}
handlers.set(key('GET', '/revolut/status'), () => ({ connected: false, remembered: false, lastSyncedAt: null }))
handlers.set(key('GET', '/revolut/sync/progress'), () => demoIdleSyncProgress)

// Trade Republic - session status
const demoTrStatus = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  maskedPhone: '+33 * ** ** ** 78',
}
handlers.set(key('GET', '/tr/status'), () => demoTrStatus)

// Trade Republic - initiate auth
handlers.set(key('POST', '/tr/auth/initiate'), () => ({
  processId: 'demo-tr-process', flow: 'confirmInApp', countdownInSeconds: 120,
}))

// Trade Republic - reconnect with the stored phone (PIN or password only)
handlers.set(key('POST', '/tr/auth/reconnect'), () => ({
  processId: 'demo-tr-process', flow: 'confirmInApp', countdownInSeconds: 120,
}))

// Trade Republic - complete auth
handlers.set(key('POST', '/tr/auth/complete'), () => demoTrStatus)

// Trade Republic - sync
handlers.set(key('POST', '/tr/sync'), () => demoTrStatus)

// Trade Republic - import CSV
handlers.set(key('POST', '/tr/import'), () => [])

// Trade Republic - clear session (real backend: DELETE /api/tr/session)
handlers.set(key('DELETE', '/tr/session'), () => null)

// Crypto exchange - add. Echoes the chosen exchange rather than hardcoding one, so the demo
// reflects whichever exchange the user picked.
handlers.set(key('POST', '/crypto/exchange'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const provider = body.type ?? 'BINANCE'
  return {
    id: Date.now(), name: provider, type: 'CRYPTO' as const, provider, currency: 'EUR', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#f59e0b', ticker: null, logoUrl: null, logoKey: null, createdAt: new Date().toISOString()
  }
})

// Crypto exchange - sync (one route per mockExchangeStatuses entry: an unmapped route resolves
// `{}` and the row's buttons silently misbehave)
handlers.set(key('POST', '/crypto/exchange/1/sync'), () => [])
handlers.set(key('POST', '/crypto/exchange/2/sync'), () => [])

// Crypto exchange - remove
handlers.set(key('DELETE', '/crypto/exchange/1'), () => null)
handlers.set(key('DELETE', '/crypto/exchange/2'), () => null)

// Crypto wallet - add
handlers.set(key('POST', '/crypto/wallet'), () => ({
  id: Date.now(), name: 'ETH Wallet', type: 'CRYPTO' as const, provider: null, currency: 'ETH', currentBalance: 0, currentBalanceEur: 0, lastSyncedAt: null, isManual: false, color: '#8b5cf6', ticker: 'ETH', createdAt: new Date().toISOString()
}))

// Crypto wallet - sync
handlers.set(key('POST', '/crypto/wallet/1/sync'), () => [])

// Crypto wallet - remove
handlers.set(key('DELETE', '/crypto/wallet/1'), () => null)

// Admin settings
handlers.set(key('GET', '/admin/settings'), () => ({
  security: { allowedOrigins: ['http://localhost:5173'], secureCookies: false },
  enableBanking: { applicationId: '', redirectUri: '', privateKeyPresent: false },
  integrations: {},
  ai: { provider: 'none', model: '', baseUrl: '', apiKeyPresent: false, maxConcurrency: 4 },
  telemetry: { available: false, consent: 'UNSET' },
}))
handlers.set(key('PUT', '/admin/settings/telemetry'), () => ({}))
// Demo never reports anything, whatever the toggle says.
handlers.set(key('GET', '/telemetry/config'), () => ({
  available: false, consent: 'UNSET', enabled: false, dsn: null, environment: 'demo', release: 'demo',
}))
handlers.set(key('POST', '/telemetry/tunnel'), () => null)
handlers.set(key('PUT', '/admin/settings/security'), () => ({}))
handlers.set(key('PUT', '/admin/settings/enablebanking'), () => ({}))
handlers.set(key('PUT', '/admin/settings/ai'), () => ({}))
handlers.set(key('POST', '/admin/settings/ai/test'), () => ({ ok: true, message: 'Demo mode' }))

// Admin AI call log
handlers.set(key('GET', '/admin/ai-calls'), () => ({
  items: [
    {
      id: 1,
      createdAt: new Date(Date.now() - 60_000).toISOString(),
      memberId: 1,
      transactionId: 42,
      merchantLabel: 'LIDL',
      batchId: 'batch-001',
      provider: 'anthropic',
      model: 'claude-haiku-4-5',
      prompt: 'Categorize the following transaction:\nMerchant: LIDL\nAmount: -45.20\nDate: 2026-06-26',
      response: '{"slug":"groceries","confidence":0.97}',
      promptTokens: 38,
      completionTokens: 12,
      totalTokens: 50,
      latencyMs: 320,
      status: 'OK',
      error: null,
      chosenSlug: 'groceries',
      confidence: 0.97,
      applied: true,
    },
    {
      id: 2,
      createdAt: new Date(Date.now() - 3_600_000).toISOString(),
      memberId: 1,
      transactionId: 37,
      merchantLabel: 'UNKNOWN TRANSFER',
      batchId: 'batch-001',
      provider: 'anthropic',
      model: 'claude-haiku-4-5',
      prompt: 'Categorize the following transaction:\nMerchant: UNKNOWN TRANSFER\nAmount: -200.00\nDate: 2026-06-25',
      response: null,
      promptTokens: 42,
      completionTokens: null,
      totalTokens: null,
      latencyMs: null,
      status: 'ERROR',
      error: 'Rate limit exceeded',
      chosenSlug: null,
      confidence: null,
      applied: false,
    },
  ],
  total: 2,
  totalTokens: 50,
}))

handlers.set(key('GET', '/admin/instance-status'), () => ({
  currentVersion: 'demo', update: null, issues: [],
}))
// The demo never reaches Enable Banking, so there is no call to log.
handlers.set(key('GET', '/admin/enablebanking/call-log'), () => [])
handlers.set(key('DELETE', '/admin/enablebanking/call-log'), () => null)

// Finary - configured
// Settings — security (2FA off in demo, one active session)
handlers.set(key('GET', '/auth/mfa/status'), () => ({
  enabled: false,
  enrolledAt: null,
  remainingRecoveryCodes: 0,
}))
handlers.set(key('GET', '/auth/sessions'), () => ([
  {
    id: 1,
    userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Demo Browser',
    ipPrefix: '192.168.1.x',
    createdAt: new Date(Date.now() - 3 * 86_400_000).toISOString(),
    lastUsedAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 87 * 86_400_000).toISOString(),
    trustedFor2fa: false,
    current: true,
  },
]))

// Settings — access keys (MCP). One mutable array backs list/create/revoke so
// the UI's refetch after a mutation actually reflects it (in-memory only,
// resets on reload — fine for the demo).
const demoAccessKeys: {
  id: number
  name: string
  keyPrefix: string
  scopes: string[]
  lastUsedAt: string | null
  expiresAt: string | null
  revokedAt: string | null
  createdAt: string
}[] = [
  {
    id: 1,
    name: 'Demo MCP key',
    keyPrefix: 'pk_demo_a1b2',
    scopes: ['accounts_read', 'dashboard_read'],
    lastUsedAt: new Date(Date.now() - 2 * 86_400_000).toISOString(),
    expiresAt: null,
    revokedAt: null,
    createdAt: new Date(Date.now() - 30 * 86_400_000).toISOString(),
  },
]
handlers.set(key('GET', '/access-keys'), () => [...demoAccessKeys])
handlers.set(key('POST', '/access-keys'), (config) => {
  const body = JSON.parse(config.data ?? '{}') as { name?: string; scopes?: string[]; expiresAt?: string | null }
  const newKey = {
    id: Math.max(0, ...demoAccessKeys.map((k) => k.id)) + 1,
    name: body.name ?? 'Demo key',
    keyPrefix: 'pk_demo_c3d4',
    scopes: body.scopes ?? [],
    lastUsedAt: null,
    expiresAt: body.expiresAt ?? null,
    revokedAt: null,
    createdAt: new Date().toISOString(),
  }
  demoAccessKeys.push(newKey)
  return { secret: 'pk_demo_secret_shown_once_0000000000000000', key: newKey }
})
// Routes are exact-match (no dynamic segments), so revocation is wired for the
// first few ids — enough for a demo session.
for (const id of [1, 2, 3, 4, 5]) {
  handlers.set(key('DELETE', `/access-keys/${id}`), () => {
    const k = demoAccessKeys.find((x) => x.id === id)
    if (k) k.revokedAt = new Date().toISOString()
    return null
  })
}

// Settings — one MCP client approved through the consent screen. Mutable so a revoke sticks
// until reload instead of reappearing on the refetch.
let demoConnectedApps = [{
  id: 'demo-claude',
  clientName: 'Claude',
  scopes: ['accounts:read', 'dashboard:read', 'goals:read'],
  issuedAt: hoursAgo(21 * 24),
  lastUsedAt: hoursAgo(5),
}]
handlers.set(key('GET', '/connected-apps'), () => demoConnectedApps)
handlers.set(key('DELETE', '/connected-apps/:id'), (_config, { id }) => {
  demoConnectedApps = demoConnectedApps.filter(app => app.id !== id)
  return null
})
// snake_case on purpose: the OAuth2 wire vocabulary, mapped to camelCase by the caller.
handlers.set(key('GET', '/oauth2/consent-info'), (config) => {
  const scope = String(config.params?.scope ?? 'accounts:read dashboard:read')
  return {
    client_name: 'Claude',
    requested_scopes: scope.split(' ').filter(Boolean),
    state: config.params?.state ?? null,
  }
})
// Family (solo demo profile: no managed members, a small shared view)
handlers.set(key('GET', '/family/members'), () => [])
// Read from the same fixtures as every other page, so a shared account keeps its name and balance.
handlers.set(key('GET', '/family/dashboard'), () => {
  const sharedAccounts = _demoAccounts.filter(a => a.id === 1 || a.id === 2).map(a => ({
    id: a.id, ownerName: 'Demo', name: a.name, type: a.type, currency: a.currency,
    balance: a.currentBalance, balanceEur: a.currentBalanceEur,
  }))
  const sharedGoals = mockGoals.filter(g => g.id === 1).map(g => ({
    id: g.id, ownerName: 'Demo', name: g.name, targetAmount: g.targetAmount ?? 0,
    currentTotal: g.currentTotal, contributions: [{ memberName: 'Demo', amount: g.currentTotal }],
  }))
  return {
    sharedAccounts,
    sharedGoals,
    totalSharedNetWorth: sharedAccounts.reduce((sum, a) => sum + a.balanceEur, 0),
  }
})
handlers.set(key('GET', '/family/sharing'), (config) => {
  const params = (config.params ?? {}) as { resourceType?: string }
  return {
    resourceType: params.resourceType ?? 'ACCOUNT',
    sharingLevel: 'ALL',
    sharedResourceIds: [],
  }
})

handlers.set(key('GET', '/finary/configured'), () => true)

// Finary - preview file
handlers.set(key('POST', '/finary/preview'), () => ({
  accounts: [
    { finaryId: 'checking-1', finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
    { finaryId: 'pea-1', finaryName: 'PEA', finaryInstitution: 'BoursoBank', finaryCategory: 'pea', suggestedType: 'PEA' as const, currentBalance: 8000, nativeCurrency: 'EUR', transactionCount: 15 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 57,
  fileToken: 'demo-file-token',
}))

// Finary - import
handlers.set(key('POST', '/finary/import'), () => ({
  accountsCreated: 1,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 3,
  transactionsImported: 57,
  importedAccounts: [
    { id: 100, name: 'PEA Finary', type: 'PEA' as const, currentBalance: 8000, color: '#10b981' },
  ],
}))

// Finary - API sync preview
handlers.set(key('POST', '/finary/api-sync/preview'), () => ({
  accounts: [
    { finaryId: 'checking-1', finaryName: 'Compte Courant', finaryInstitution: 'BoursoBank', finaryCategory: 'checking', suggestedType: 'CHECKING' as const, currentBalance: 2500, nativeCurrency: 'EUR', transactionCount: 42 },
  ],
  existingPicsouAccounts: [],
  totalTransactionCount: 42,
  syncToken: 'demo-sync-token',
}))

// Finary - API sync execute
handlers.set(key('POST', '/finary/api-sync/execute'), () => ({
  accountsCreated: 0,
  accountsMapped: 1,
  accountsSkipped: 0,
  snapshotsCreated: 2,
  transactionsImported: 42,
  importedAccounts: [],
}))

// ── Budget module ─────────────────────────────────────────────────────────────
// Read endpoints serve the mock fixtures; mutations echo a plausible object so the
// optimistic UI flows. Demo state is not persisted — refetches return the fixtures — except
// categorizing a transaction, which edits the ledger the Spending pages and the inbox read.

// Categories
handlers.set(key('GET', '/categories'), () => mockCategories)
handlers.set(key('POST', '/categories'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    id: Date.now(), name: body.name ?? 'Catégorie', kind: body.kind ?? 'EXPENSE',
    color: body.color ?? '#6366f1', icon: body.icon ?? null,
    isDefault: false, archived: false, sortOrder: 99, parentId: body.parentId ?? null,
  }
})
for (const c of mockCategories) {
  handlers.set(key('PUT', `/categories/${c.id}`), (config) => ({
    ...c, ...JSON.parse(config.data || '{}'),
  }))
  handlers.set(key('DELETE', `/categories/${c.id}`), () => ({}))
  handlers.set(key('POST', `/categories/${c.id}/unarchive`), () => ({ ...c, archived: false }))
}

// Categorization rules
handlers.set(key('GET', '/categorization-rules'), () => mockRules)
handlers.set(key('POST', '/categorization-rules'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  return {
    id: Date.now(), matchType: body.matchType ?? 'COUNTERPARTY', pattern: body.pattern ?? '',
    categoryId: body.categoryId ?? 0, categoryName: cat?.name ?? '', priority: body.priority ?? 0,
    source: 'USER',
  }
})
for (const r of mockRules) {
  handlers.set(key('DELETE', `/categorization-rules/${r.id}`), () => ({}))
}
handlers.set(key('POST', '/categorization-rules/recategorize'), () => ({ categorized: 4 }))

// To-categorize inbox
handlers.set(key('GET', '/transactions/uncategorized'), () => mockUncategorized())
// Unknown transaction or category IDs leave the ledger unchanged. The adapter returns {}.
handlers.set(key('PUT', '/transactions/:id/category'), (config, { id }) => {
  const { categoryId } = JSON.parse(config.data || '{}') as CategorizeRequest
  categorizeDemoTransaction(Number(id), categoryId)
  return {}
})

// The budget ledger on the main checking account: the rows the Spending pages and the review
// inbox summarize, so a search over any period lists what they count.
handlers.set(key('GET', '/transactions'), (config) =>
  withPeerLoanLinks(mockTransactionSearch(config.params ?? {})))
// Optional AI categorizer over the inbox (legacy sync shape).
handlers.set(key('POST', '/transactions/categorize-ai'), () => ({
  running: false, total: 0, processed: 0, applied: 0, suggested: 0, done: true, error: null,
}))
// Async AI job status (demo: idle / not running).
handlers.set(key('GET', '/transactions/categorize-ai/status'), () => ({
  running: false, total: 0, processed: 0, applied: 0, suggested: 0, done: false, error: null,
}))

// Envelopes
handlers.set(key('GET', '/budgets'), () => mockBudgetSummaries())
handlers.set(key('POST', '/budgets'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  const limit = body.monthlyLimit ?? 0
  return {
    id: Date.now(), categoryId: body.categoryId ?? 0, categoryName: cat?.name ?? 'Catégorie',
    categoryKind: cat?.kind ?? 'EXPENSE', categoryColor: cat?.color ?? null, categoryIcon: null,
    monthlyLimit: limit, spent: 0, remaining: limit, percent: 0, overBudget: false, rollup: false,
    cycleStart: mockBudgetSettings.currentCycleStart, cycleEnd: mockBudgetSettings.currentCycleEnd,
  }
})
for (const b of mockBudgets) {
  handlers.set(key('PUT', `/budgets/${b.id}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    const current = mockBudgetSummaries().find((budget) => budget.id === b.id)!
    const limit = body.monthlyLimit ?? current.monthlyLimit
    return { ...current, monthlyLimit: limit, remaining: Math.round((limit - current.spent) * 100) / 100,
      percent: limit > 0 ? Math.round((current.spent / limit) * 100) : 0, overBudget: current.spent > limit }
  })
  handlers.set(key('DELETE', `/budgets/${b.id}`), () => ({}))
}

// Settings (payday cycle)
handlers.set(key('GET', '/budget/settings'), () => mockBudgetSettings)
handlers.set(key('PUT', '/budget/settings'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockBudgetSettings,
    cycleStartDay: body.cycleStartDay ?? mockBudgetSettings.cycleStartDay,
    logoFetchEnabled: body.logoFetchEnabled ?? mockBudgetSettings.logoFetchEnabled,
    aiCategorizationEnabled: body.aiCategorizationEnabled ?? mockBudgetSettings.aiCategorizationEnabled,
    aiMode: body.aiMode ?? mockBudgetSettings.aiMode,
    aiConfidenceThreshold: body.aiConfidenceThreshold ?? mockBudgetSettings.aiConfidenceThreshold,
  }
})

// Cashflow & allocation (period comes from the query string)
handlers.set(key('GET', '/cashflow'), (config) =>
  mockCashflow(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
handlers.set(key('GET', '/cashflow/flow'), (config) =>
  mockFlow(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
handlers.set(key('GET', '/allocation'), (config) =>
  mockAllocation(((config.params?.period as CashflowPeriod) ?? 'CYCLE'), _demoAccounts))

// Spending breakdown & per-category drill (one handler per known category id)
handlers.set(key('GET', '/spending/by-category'), (config) =>
  mockSpendingByCategory(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
for (const c of mockCategories) {
  handlers.set(key('GET', `/spending/category/${c.id}`), (config) =>
    mockCategoryDetail(c.id, ((config.params?.period as CashflowPeriod) ?? 'CYCLE')))
}

// Recurring series
handlers.set(key('GET', '/recurring'), () => mockRecurring)
handlers.set(key('GET', '/recurring/calendar'), (config) =>
  mockCalendar(Number(config.params?.horizonDays ?? 60)))
handlers.set(key('GET', '/dividends/upcoming'), (config) => {
  const rawAccount = config.params?.accountId
  const accountId = rawAccount == null || rawAccount === '' ? undefined : Number(rawAccount)
  return mockUpcomingDividends(
    Number(config.params?.horizonDays ?? 60),
    accountId != null && Number.isFinite(accountId) ? accountId : undefined,
  )
})
handlers.set(key('POST', '/recurring'), (config) => {
  const body = JSON.parse(config.data || '{}')
  const cat = mockCategories.find((c) => c.id === body.categoryId)
  return {
    id: Date.now(), label: body.label ?? 'Récurrent', counterparty: body.counterparty ?? null,
    expectedAmount: body.expectedAmount ?? 0, cadence: body.cadence ?? 'MONTHLY', status: 'CONFIRMED',
    nextDueDate: body.nextDueDate ?? null, lastSeenDate: null, categoryId: body.categoryId ?? null,
    categoryName: cat?.name ?? null, categoryColor: cat?.color ?? null, categoryIcon: null,
  }
})
handlers.set(key('GET', '/recurring/activity'), () => mockActivity)
for (const s of mockRecurring) {
  handlers.set(key('POST', `/recurring/${s.id}/confirm`), () => ({ ...s, status: 'CONFIRMED' }))
  handlers.set(key('POST', `/recurring/${s.id}/ignore`), () => ({ ...s, status: 'IGNORED' }))
  handlers.set(key('DELETE', `/recurring/${s.id}`), () => ({}))
  // Context-aware undo, mirroring the backend: acknowledge a price step (keep the new amount,
  // clear the alert) or reject a silent auto-confirm (send the series back to IGNORED).
  handlers.set(key('POST', `/recurring/${s.id}/undo`), () =>
    s.priceChangedAt != null
      ? { ...s, previousAmount: null, priceChangedAt: null }
      : { ...s, status: 'IGNORED', autoConfirmed: false })
}
handlers.set(key('POST', '/recurring/detect'), () => ({ detected: 2 }))

export function createDemoAdapter() {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const k = key(config.method || 'GET', config.url || '')
    const match = findHandler(k)

    if (!match) {
      // Deliberately still resolves 200 {} — several demo screens lean on the
      // permissive fallback. The warning is what keeps frontend/backend
      // endpoint drift visible: a mismatched method/path (e.g. the old
      // POST /tr/logout) used to "succeed" here while 404ing in production.
      console.warn(`[demo] no handler registered for "${k}" — returning empty 200`)
    }

    return new Promise((resolve, reject) => {
      setTimeout(() => {
        // A handler that throws (for example the peer-loan simulation when a payment never
        // amortises) must reject the promise, otherwise the caller awaits forever.
        try {
          resolve({
            data: match ? match.handler(config, match.params) : {},
            status: 200,
            statusText: 'OK',
            headers: {},
            config,
          } as AxiosResponse)
        } catch (error) {
          reject(error)
        }
      }, randomDelay())
    })
  }
}
