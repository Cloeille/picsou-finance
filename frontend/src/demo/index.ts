import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { ScpiPosition, ScpiPositionRequest } from '@/types/api'
import { mockAccounts } from './data/accounts'
import {
  mockAllocationTargets,
  mockDiversification,
  mockExpenseEstimate,
  mockProjection,
  mockWealthPyramid,
} from './data/analysis'
import { mockDashboard } from './data/dashboard'
import { mockGoalMonths, mockGoals } from './data/goals'
import { mockBalanceHistory, netWorthHistory } from './data/history'
import { daysBeforeToday, daysFromToday, hoursAgo, isoDate, monthStartFromToday } from './data/dates'
import { holdingsValue, mockHoldings, quoteDaysAgo } from './data/holdings'
import { ageFromBirthDate, mockMemberProfile, netIncome } from './data/profile'
import { mockTransactions } from './data/transactions'
import { mockExchangeStatuses, mockWalletStatuses, mockRequisitions } from './data/sync-status'
import {
  mockActivity,
  mockAllocation,
  mockBudgetSettings,
  mockBudgets,
  mockCalendar,
  mockCashflow,
  mockCategories,
  mockCategoryDetail,
  mockFlow,
  mockRecurring,
  mockRules,
  mockSpendingByCategory,
  mockUncategorized,
} from './data/budget'
import { mockUpcomingDividends } from './data/dividends'
import type { CashflowPeriod, DebtInfo, LoanScheduleResponse, Transaction } from '@/types/api'

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
        params[segment.slice(1)] = decodeURIComponent(segments[i])
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
  { id: 1, displayName: 'Demo', avatarColor: '#6366f1', managed: false, hasLogin: true, activated: true, loginName: 'demo', mfaEnabled: false },
  { id: 2, displayName: 'Léa', avatarColor: '#ec4899', managed: true, hasLogin: false, activated: false, loginName: null, mfaEnabled: false },
])

// Dashboard
handlers.set(key('GET', '/dashboard'), () => mockDashboard)

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

// Live quotes. The holdings fixtures win over the exchange positions for a ticker both carry, so
// re-pricing a holdings table with these leaves its values exactly as the fixture states them.
const demoPrices: Record<string, number> = {}
for (const line of [...Object.values(mockHoldings).flat(), ...demoExchangePositions]) {
  const price = 'currentPrice' in line ? line.currentPrice : line.currentPriceEur
  if (price != null) demoPrices[line.ticker] ??= price
}

handlers.set(key('GET', '/prices'), (config) => {
  const tickers = String(config.params?.tickers ?? '').split(',').map(t => t.trim())
  return Object.fromEntries(tickers.filter(t => t in demoPrices).map(t => [t, demoPrices[t]]))
})

// Both series end exactly on the live quote, so the chart's last point matches the table.
function demoQuote(price: number, drift: number): number {
  return Number((price * (1 + drift)).toFixed(price < 10 ? 4 : 2))
}

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
      priceEur: demoQuote(price, -0.004 * (back / 23) + 0.006 * Math.sin(back / 2.5)),
    }
  })
})

// Account details: transactions for all accounts
for (let i = 1; i <= mockAccounts.length; i++) {
  handlers.set(key('GET', `/accounts/${i}/transactions`), () => mockTransactions[i] ?? [])
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

// Port of the backend's LoanAmortizationService.compute, so the schedule is whatever the stored
// debt block yields in production rather than figures that could drift from it. An account
// without a debt block (or without dates) gets the backend's empty schedule.
function demoLoanSchedule(debt: DebtInfo | undefined): LoanScheduleResponse {
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
  const today = isoDate(new Date())
  const paid = startDate && total > 0 && today > startDate
    ? Math.min(Math.max(monthIndex(today) - monthIndex(startDate), 0), total)
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

// Account details: twelve month-start balances, from the fixture /history also sums.
handlers.set(key('GET', '/accounts/:id/history'), (_config, { id }) =>
  (mockBalanceHistory[Number(id)] ?? []).map((balance, i, balances) => ({
    id: 100 + i,
    date: monthStartFromToday(i - (balances.length - 1)),
    balance,
  })))

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
  )
})

// PnL summary (dashboard header + account detail)
// Live figures from the history the chart draws. The range reprices the holdings at the quote
// /prices/:ticker/history gives for `from`, as HistoryService does with its price snapshots.
const demoTopLevelIds = mockAccounts.filter(a => a.parentAccountId == null).map(a => a.id)
handlers.set(key('GET', '/history/pnl'), (config) => {
  const ids = requestedAccountIds(config, demoTopLevelIds)
  const { total, invested, pnl } = netWorthHistory(ids, 1, false)[0]
  const live = { total, invested, pnl, pnlPercent: invested > 0 ? Math.round((pnl / invested) * 1000) / 10 : null }
  const from = config.params?.from
  // HistoryService returns the live figures alone without a range or a holding to price over it.
  if (!from || holdingsValue(ids) === 0) return { ...live, valueAtFrom: null, rangePnl: null, rangePnlPercent: null }
  const valueAtFrom = holdingsValue(ids, daysBeforeToday(String(from)))
  const rangePnl = r2(holdingsValue(ids) - valueAtFrom)
  return { ...live, valueAtFrom, rangePnl, rangePnlPercent: Math.round((rangePnl / valueAtFrom) * 1000) / 10 }
})

// Intraday net worth (24H dashboard range): one point per hour, mild noise, ending on today's.
handlers.set(key('GET', '/history/net-worth/intraday'), (config) => {
  const { total, invested } = netWorthHistory(requestedAccountIds(config, demoTopLevelIds), 1, false)[0]
  return Array.from({ length: 24 }, (_, i) => {
    const back = 23 - i
    return { timestamp: hoursAgo(back), total: r2(total - back * 25 + Math.sin(back / 2.5) * 180), invested }
  })
})

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
const demoGoal = (id: string) => mockGoals.find(g => g.id === Number(id))
handlers.set(key('GET', '/goals/:id'), (_config, { id }) => demoGoal(id))
handlers.set(key('GET', '/goals/:id/months'), (_config, { id }) => mockGoalMonths[Number(id)] ?? [])
handlers.set(key('GET', '/goals/:id/history'), (_config, { id }) =>
  netWorthHistory(demoGoal(id)?.accounts.map(a => a.id) ?? [], 12, false))
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
handlers.set(key('GET', '/tr/sync/progress'), () => demoIdleSyncProgress)

// Trade Republic - session status
handlers.set(key('GET', '/tr/status'), () => ({ isActive: false, expiresAt: null }))

// Trade Republic - initiate auth
handlers.set(key('POST', '/tr/auth/initiate'), () => ({ processId: 'demo-tr-process' }))

// Trade Republic - complete auth
handlers.set(key('POST', '/tr/auth/complete'), () => [])

// Trade Republic - sync
handlers.set(key('POST', '/tr/sync'), () => [])

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
// The demo login is the instance's only admin, so deleting it resets the instance.
handlers.set(key('GET', '/me/deletion-impact'), () => ({ mode: 'RESET_LAST_ADMIN' }))

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
// A solo demo: whatever a goal holds, Demo put in.
handlers.set(key('GET', '/family/goals/:id/contributions'), (_config, { id }) => {
  const goal = mockGoals.find(g => g.id === Number(id))
  return goal ? [{ memberName: 'Demo', amount: goal.currentTotal }] : []
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
// optimistic UI flows. Demo state is not persisted — refetches return the fixtures.

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
  handlers.set(key('PUT', `/categorization-rules/${r.id}`), (config) => ({
    ...r, ...JSON.parse(config.data || '{}'),
  }))
  handlers.set(key('DELETE', `/categorization-rules/${r.id}`), () => ({}))
}
handlers.set(key('POST', '/categorization-rules/recategorize'), () => ({ categorized: 4 }))

// To-categorize inbox
handlers.set(key('GET', '/transactions/uncategorized'), () => mockUncategorized)
handlers.set(key('PUT', '/transactions/:id/category'), () => ({}))

// The cycle's ledger on the main checking account, assembled from the same fixtures as the
// Spending drill and the review inbox, so the three pages list the same rows.
function demoBudgetTransactions(): Transaction[] {
  const account = _demoAccounts.find(a => a.id === 4)!
  const onAccount = { accountId: account.id, accountName: account.name }
  const salaryCategory = mockCategories.find(c => c.kind === 'INCOME')!
  const now = new Date()
  const salary: Transaction = {
    id: salaryCategory.id * 100,
    date: isoDate(new Date(now.getFullYear(), now.getMonth(), 1)),
    description: 'EMPLOYEUR SAS',
    amount: mockFlow('CYCLE').income,
    type: null,
    category: salaryCategory.name,
    categoryId: salaryCategory.id,
    nativeCurrency: 'EUR',
    isManual: false,
    txType: 'DEPOSIT',
    ticker: null,
    name: null,
    quantity: null,
    pricePerUnit: null,
    merchantLabel: 'Employeur SAS',
    merchantBrandId: null,
    fees: null,
  }
  const spending = mockSpendingByCategory('CYCLE').categories
    .flatMap(row => row.categoryId == null ? [] : mockCategoryDetail(row.categoryId, 'CYCLE').transactions
      .map(tx => ({ ...tx, categoryId: row.categoryId })))
  const toReview = mockUncategorized.map((tx): Transaction => ({
    id: tx.id,
    date: tx.date,
    description: tx.description,
    amount: tx.amount,
    type: tx.type,
    category: null,
    categoryId: null,
    nativeCurrency: tx.nativeCurrency,
    isManual: tx.isManual,
    txType: tx.txType,
    ticker: tx.ticker,
    name: null,
    quantity: tx.quantity,
    pricePerUnit: tx.pricePerUnit,
    merchantLabel: tx.merchantLabel,
    merchantBrandId: tx.merchantBrandId,
    fees: null,
  }))
  return [salary, ...spending, ...toReview]
    .map(tx => ({ ...tx, ...onAccount }))
    .sort((a, b) => b.date.localeCompare(a.date))
}

handlers.set(key('GET', '/transactions'), (config) => {
  const { from, to, accountId, categoryId } = (config.params ?? {}) as {
    from?: string; to?: string; accountId?: number | string; categoryId?: number | string
  }
  return demoBudgetTransactions().filter(tx =>
    (from == null || tx.date >= from)
    && (to == null || tx.date <= to)
    && (accountId == null || tx.accountId === Number(accountId))
    && (categoryId == null || tx.categoryId === Number(categoryId)))
})
// Optional AI categorizer over the inbox (legacy sync shape).
handlers.set(key('POST', '/transactions/categorize-ai'), () => ({
  running: false, total: 0, processed: 0, applied: 0, suggested: 0, done: true, error: null,
}))
// Async AI job status (demo: idle / not running).
handlers.set(key('GET', '/transactions/categorize-ai/status'), () => ({
  running: false, total: 0, processed: 0, applied: 0, suggested: 0, done: false, error: null,
}))

// Envelopes
handlers.set(key('GET', '/budgets'), () => mockBudgets)
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
    const limit = body.monthlyLimit ?? b.monthlyLimit
    return { ...b, monthlyLimit: limit, remaining: Math.round((limit - b.spent) * 100) / 100,
      percent: limit > 0 ? Math.round((b.spent / limit) * 100) : 0, overBudget: b.spent > limit }
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
  mockAllocation(((config.params?.period as CashflowPeriod) ?? 'CYCLE')))

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
  handlers.set(key('PUT', `/recurring/${s.id}`), (config) => ({ ...s, ...JSON.parse(config.data || '{}') }))
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

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          data: match ? match.handler(config, match.params) : {},
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse)
      }, randomDelay())
    })
  }
}
