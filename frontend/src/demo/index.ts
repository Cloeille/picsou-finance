import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import type { GoalProgress } from '@/types/api'
import { mockAccounts } from './data/accounts'
import { mockDashboard } from './data/dashboard'
import { mockGoals } from './data/goals'
import { mockHoldings } from './data/holdings'
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
import type { CashflowPeriod } from '@/types/api'

function randomDelay(): number {
  return 200 + Math.random() * 400
}

type MockHandler = (config: InternalAxiosRequestConfig) => unknown

const handlers = new Map<string, MockHandler>()

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
handlers.set(key('POST', '/auth/login'), () => ({ username: 'demo' }))
handlers.set(key('POST', '/auth/refresh'), () => ({ username: 'demo' }))

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

// Account CRUD
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
handlers.set(key('GET', '/accounts/2/holdings'), () => mockHoldings[2] ?? [])
handlers.set(key('GET', '/accounts/3/holdings'), () => mockHoldings[3] ?? [])
handlers.set(key('GET', '/accounts/6/holdings'), () => mockHoldings[6] ?? [])

// Per-product breakdown. Only the crypto account (id=6) has one, exactly like a real crypto
// exchange account; every other account falls back to the flat holdings table.
handlers.set(key('GET', '/accounts/6/positions'), () => {
  const today = new Date().toISOString().slice(0, 10)
  return [
    { product: 'SPOT', ticker: 'BTC', quantity: 0.01204, principal: null, interest: null, averageBuyIn: 68000, currentPriceEur: 92100, currentValueEur: 1108.88, costBasisEur: 818.72, pnlEur: 290.16, pnlPercent: 35.4, priceAsOf: today, priceStale: false },
    { product: 'SPOT', ticker: 'ETH', quantity: 0.031906, principal: null, interest: null, averageBuyIn: 3200, currentPriceEur: 4116, currentValueEur: 131.32, costBasisEur: 102.1, pnlEur: 29.22, pnlPercent: 28.6, priceAsOf: today, priceStale: false },
    { product: 'STAKING', ticker: 'ATOM', quantity: 33.154, principal: 19.73, interest: 13.424, averageBuyIn: 6.4, currentPriceEur: 5.65, currentValueEur: 187.32, costBasisEur: 212.19, pnlEur: -24.87, pnlPercent: -11.7, priceAsOf: today, priceStale: false },
    { product: 'LENDING', ticker: 'USDT', quantity: 75.01, principal: 75, interest: 0.01, averageBuyIn: 0.91, currentPriceEur: 0.92, currentValueEur: 69.01, costBasisEur: 68.26, pnlEur: 0.75, pnlPercent: 1.1, priceAsOf: today, priceStale: false },
  ]
})
for (const i of [1, 2, 3, 4, 5, 7]) {
  handlers.set(key('GET', `/accounts/${i}/positions`), () => [])
}

// Account details: transactions for all accounts (1–10)
for (let i = 1; i <= 10; i++) {
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
handlers.set(key('GET', '/accounts/1/savings-interest'), () => generateSavingsInterest(1))
handlers.set(key('GET', '/accounts/7/savings-interest'), () => generateSavingsInterest(7))

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
for (const i of [3, 6]) {
  handlers.set(key('GET', `/accounts/${i}/realized-pnl`), () => ({
    currency: 'EUR', realizedTotal: 0, byTicker: [], lots: [],
  }))
}

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

for (const ticker of [...demoStockTickers, ...demoCryptoTickers, ...Object.keys(demoEtfCompositions)]) {
  handlers.set(key('GET', `/securities/${ticker}/insight`), () => demoInsight(ticker))
}

// Account details: history for multiple accounts (12 months each)
function generateHistory(startBalances: number[]) {
  const now = new Date()
  const points: { id: number; date: string; balance: number }[] = []
  const months = startBalances.length

  for (let i = 0; i < months; i++) {
    // UTC for the same reason as generateNetWorthHistory: keep the ISO date on the 1st.
    const d = new Date(Date.UTC(now.getFullYear(), now.getMonth() - (months - 1 - i), 1))
    points.push({
      id: 100 + i,
      date: d.toISOString().split('T')[0],
      balance: startBalances[i],
    })
  }

  return points
}

// LEP: slow steady growth (savings account)
handlers.set(key('GET', '/accounts/1/history'), () => generateHistory(
  [6100, 6250, 6400, 6500, 6650, 6800, 6950, 7100, 7200, 7400, 7600, 7800]))

// PEA: moderate growth with some dips
handlers.set(key('GET', '/accounts/2/history'), () => generateHistory(
  [8200, 8600, 9100, 8800, 9400, 9900, 10200, 10800, 11200, 11600, 12000, 12450.5]))

// Compte Titres: more volatile
handlers.set(key('GET', '/accounts/3/history'), () => generateHistory(
  [5800, 6200, 6700, 6400, 6900, 7200, 7500, 7100, 7600, 7900, 8100, 8320.75]))

// Checking BNP: fluctuates around salary cycle
handlers.set(key('GET', '/accounts/4/history'), () => generateHistory(
  [1200, 2800, 1500, 3100, 1800, 2600, 1400, 2900, 1700, 2500, 2100, 2340.2]))

// Checking BoursoBank: smaller balance, fluctuates
handlers.set(key('GET', '/accounts/5/history'), () => generateHistory(
  [800, 1100, 950, 1300, 1050, 1200, 900, 1350, 1100, 1250, 1400, 1580.9]))

// Crypto: volatile, strong upward trend
handlers.set(key('GET', '/accounts/6/history'), () => generateHistory(
  [1800, 2100, 2400, 1900, 2600, 2800, 3100, 2700, 3400, 3600, 3900, 4250]))

// Livret A: slow steady growth
handlers.set(key('GET', '/accounts/7/history'), () => generateHistory(
  [4200, 4320, 4440, 4560, 4620, 4740, 4800, 4920, 4980, 5040, 5080, 5120]))

// Revolut wallet (id=8)
handlers.set(key('GET', '/accounts/8/history'), () => generateHistory(
  [3000, 3050, 3100, 3200, 3150, 3100, 3200, 3300, 3250, 3200, 3240, 3240.5]))

// Property: slow appreciation, revalued monthly rather than daily.
handlers.set(key('GET', '/accounts/11/history'), () => generateHistory(
  [392000, 393500, 395000, 397000, 399500, 401000, 403000, 405500, 407000, 409000, 410500, 412000]))

// ─── Real estate ─────────────────────────────────────────────────────────────
// Every route the property UI touches needs a handler: the demo adapter answers `{}` for
// anything unmatched, and the pages would then read fields off an empty object.

const demoProperty = mockAccounts.find((a) => a.id === 11)!

handlers.set(key('GET', '/real-estate/summary'), () => ({
  grossValue: 412000,
  outstandingDebt: 168400,
  netValue: 243600,
  costBasis: 368800,
  unrealizedGain: 43200,
  unrealizedGainPercent: 11.71,
  loanToValue: 40.87,
  monthlyRentalIncome: 0,
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
    lastValuedAt: '2026-07-01',
    lastConfidence: 'HIGH',
    loans: [{
      accountId: 4,
      name: 'Prêt immobilier',
      lenderName: 'BNP Paribas',
      outstandingBalance: 168400,
      sharePercent: 100,
      monthlyPayment: 1120,
      endDate: '2043-06-01',
    }],
  }],
}))

handlers.set(key('GET', '/real-estate/11/valuations'), () => {
  const points = [395000, 398000, 401500, 404000, 407500, 409000, 412000]
  return points.map((value, i) => ({
    valuedAt: `2026-0${i + 1}-01`,
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
  valuedAt: '2026-08-01',
  reindexRatio: 1.021,
  adjustments: [
    { code: 'GARDEN', factor: 0.02, sqm: null, amount: 8080 },
    { code: 'TERRACE', factor: 0.03, sqm: null, amount: 12120 },
    { code: 'GARAGE', factor: null, sqm: 12, amount: 52032 },
  ],
}))

handlers.set(key('GET', '/accounts/11/ownership'), () => ({
  shares: [{ memberId: 1, displayName: 'Demo', avatarColor: '#6366f1', sharePercent: 100, isOwner: true }],
  totalAssigned: 100,
  unassigned: 0,
}))
handlers.set(key('PUT', '/accounts/11/ownership'), (config) => {
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

// Aggregate net-worth history (dashboard chart, accounts page with split=true).
// Mirrors backend NetWorthPoint: { date, total, invested, pnl, accounts? }.
function generateNetWorthHistory(months: number, accountIds: number[], split: boolean) {
  const now = new Date()
  const weights = accountIds.map((id) => mockAccounts.find((a) => a.id === id)?.currentBalanceEur ?? 1000)
  const weightSum = weights.reduce((s, w) => s + w, 0) || 1

  return Array.from({ length: months }, (_, i) => {
    // Build in UTC: a local-midnight Date run through toISOString() shifts to
    // the previous day in any timezone ahead of UTC.
    const d = new Date(Date.UTC(now.getFullYear(), now.getMonth() - (months - 1 - i), 1))
    const progress = months > 1 ? i / (months - 1) : 1
    const total = Math.round((58_000 + progress * 14_000 + Math.sin(i * 1.7) * 1_200) * 100) / 100
    const invested = Math.round(total * 0.55 * 100) / 100
    const pnl = Math.round((total * 0.06 + progress * 1_500) * 100) / 100
    const point: {
      date: string; total: number; invested: number; pnl: number
      accounts?: Record<string, { total: number; invested: number; pnl: number }>
    } = { date: d.toISOString().split('T')[0], total, invested, pnl }
    if (split) {
      point.accounts = Object.fromEntries(accountIds.map((id, idx) => {
        const share = weights[idx] / weightSum
        return [String(id), {
          total: Math.round(total * share * 100) / 100,
          invested: Math.round(invested * share * 100) / 100,
          pnl: Math.round(pnl * share * 100) / 100,
        }]
      }))
    }
    return point
  })
}

handlers.set(key('GET', '/history'), (config) => {
  const params = (config.params ?? {}) as { accountIds?: string; months?: number | string; split?: boolean | string }
  const months = Number(params.months) || 12
  const ids = String(params.accountIds ?? '').split(',').filter(Boolean).map(Number)
  const split = params.split === true || params.split === 'true'
  return generateNetWorthHistory(months, ids.length ? ids : mockAccounts.map((a) => a.id), split)
})

// Pocket "Vacances" (id=9): inflows-only
handlers.set(key('GET', '/accounts/9/history'), () => generateHistory(
  [0, 0, 100, 300, 500, 600, 666, 774, 774, 774, 774, 774]))

// Pocket unnamed (id=10): inflows-only
handlers.set(key('GET', '/accounts/10/history'), () => generateHistory(
  [0, 0, 0, 100, 200, 200, 300, 300, 300, 300, 300, 300]))

// Aggregate net worth history — GET /history?accountIds=...&months=...&split=...
// Mirrors HistoryController which aggregates account snapshots into NetWorthPoint[].
// When split=true, includes per-account breakdown used by AccountsPage PnL chart.
const DEMO_NW_BALANCES: Record<number, number[]> = {
  1: [6100, 6250, 6400, 6500, 6650, 6800, 6950, 7100, 7200, 7400, 7600, 7800],
  2: [8200, 8600, 9100, 8800, 9400, 9900, 10200, 10800, 11200, 11600, 12000, 12450.5],
  3: [5800, 6200, 6700, 6400, 6900, 7200, 7500, 7100, 7600, 7900, 8100, 8320.75],
  4: [1200, 2800, 1500, 3100, 1800, 2600, 1400, 2900, 1700, 2500, 2100, 2340.2],
  5: [800, 1100, 950, 1300, 1050, 1200, 900, 1350, 1100, 1250, 1400, 1580.9],
  6: [1800, 2100, 2400, 1900, 2600, 2800, 3100, 2700, 3400, 3600, 3900, 4250],
  7: [4200, 4320, 4440, 4560, 4620, 4740, 4800, 4920, 4980, 5040, 5080, 5120],
  8: [3000, 3050, 3100, 3200, 3150, 3100, 3200, 3300, 3250, 3200, 3240, 3240.5],
}
// Initial invested amounts for investment accounts (drives PnL computation in demo).
const DEMO_NW_INVESTED: Record<number, number> = {
  2: 8200,  // PEA — initial position
  3: 5800,  // Compte Titres
  6: 1800,  // Crypto
}

handlers.set(key('GET', '/history'), (config) => {
  const params = (config.params ?? {}) as Record<string, string>
  const ids = String(params.accountIds ?? '').split(',').map(Number).filter(n => n > 0)
  const months = Math.min(Number(params.months ?? 12), 12)
  const split = String(params.split) === 'true'

  const now = new Date()
  return Array.from({ length: months }, (_, i) => {
    const d = new Date(now.getFullYear(), now.getMonth() - (months - 1 - i), 1)
    const date = d.toISOString().split('T')[0]
    const idx = 12 - months + i

    let total = 0
    let invested = 0
    let pnl = 0
    const accounts: Record<string, { total: number; invested: number; pnl: number }> = {}

    for (const id of ids) {
      const bal = (DEMO_NW_BALANCES[id] ?? [])[idx] ?? 0
      const inv = DEMO_NW_INVESTED[id] ?? bal
      const ap = bal - inv

      total += bal
      invested += inv
      pnl += ap
      if (split) accounts[String(id)] = { total: bal, invested: inv, pnl: ap }
    }

    return { date, total, invested, pnl, ...(split ? { accounts } : {}) }
  })
})

// PnL summary (dashboard header + account detail)
handlers.set(key('GET', '/history/pnl'), () => ({
  total: 72_000,
  invested: 39_600,
  pnl: 5_820,
  pnlPercent: 14.7,
  valueAtFrom: 66_500,
  rangePnl: 5_500,
  rangePnlPercent: 8.3,
}))

// Intraday net worth (24H dashboard range): one point per hour, mild noise.
handlers.set(key('GET', '/history/net-worth/intraday'), () => {
  const now = Date.now()
  return Array.from({ length: 24 }, (_, i) => ({
    timestamp: new Date(now - (23 - i) * 3_600_000).toISOString(),
    total: Math.round((71_400 + i * 25 + Math.sin(i / 2.5) * 180) * 100) / 100,
    invested: 39_600,
  }))
})

// Goals
handlers.set(key('GET', '/goals'), () => mockGoals)
for (let i = 1; i <= 3; i++) {
  handlers.set(key('GET', `/goals/${i}`), () => mockGoals[i - 1])
  handlers.set(key('GET', `/goals/${i}/months`), () => generateMockMonths(mockGoals[i - 1]))
  handlers.set(key('POST', `/goals/${i}/history/extend`), () => mockGoals[i - 1])
  handlers.set(key('POST', `/goals/${i}/history/extend/month`), () => mockGoals[i - 1])
}
handlers.set(key('POST', '/goals'), (config) => {
  const body = JSON.parse(config.data || '{}')
  return {
    ...mockGoals[0],
    id: Date.now(),
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
for (let i = 1; i <= 3; i++) {
  handlers.set(key('PUT', `/goals/${i}`), (config) => {
    const body = JSON.parse(config.data || '{}')
    return {
      ...mockGoals[i - 1],
      name: body.name ?? mockGoals[i - 1].name,
      targetAmount: body.targetAmount ?? mockGoals[i - 1].targetAmount,
      deadline: body.deadline ?? mockGoals[i - 1].deadline,
      accounts: (body.accountIds ?? mockGoals[i - 1].accounts.map(a => a.id))
        .map((id: number) => mockAccounts.find(a => a.id === id)).filter(Boolean),
    }
  })
}
handlers.set(key('DELETE', '/goals/1'), () => null)
handlers.set(key('DELETE', '/goals/2'), () => null)
handlers.set(key('DELETE', '/goals/3'), () => null)

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

// Sync - complete (real backend: GET /api/sync/complete?code=...&state=...)
handlers.set(key('GET', '/sync/complete'), () => ([
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

// Amundi Épargne Salariale — same demo convention: reads report a disconnected
// session, mutations fake-succeed with the real response shapes. Bourse Direct
// has no handlers at all, which leaves its panel reading `isActive: undefined`
// in demo mode; do not copy that gap here.
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
}))
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

// Family (solo demo profile: no managed members, a small shared view)
handlers.set(key('GET', '/family/members'), () => [])
handlers.set(key('GET', '/family/dashboard'), () => ({
  sharedAccounts: [
    { id: 1, ownerName: 'Demo', name: 'LEP La Banque Postale', type: 'LEP', currency: 'EUR', balance: 7800, balanceEur: 7800 },
    { id: 2, ownerName: 'Demo', name: 'PEA Boursorama', type: 'PEA', currency: 'EUR', balance: 12450, balanceEur: 12450 },
  ],
  sharedGoals: [
    {
      id: 1,
      ownerName: 'Demo',
      name: 'Vacances été 2025',
      targetAmount: 3000,
      currentTotal: 1580.9,
      contributions: [{ memberName: 'Demo', amount: 1580.9 }],
    },
  ],
  totalSharedNetWorth: 20_250,
}))
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
for (const tx of mockUncategorized) {
  handlers.set(key('PUT', `/transactions/${tx.id}/category`), () => ({}))
}
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

function generateMockMonths(goal: GoalProgress) {
  const start = new Date('2025-01-01')
  const end = new Date(goal.deadline)
  const months: { yearMonth: string; objective: number; actual: number | null; manualActual: number | null; override: number | null; effective: number | null }[] = []
  const current = new Date(start)
  const now = new Date()
  while (current <= end) {
    const ym = `${current.getFullYear()}-${String(current.getMonth() + 1).padStart(2, '0')}`
    const isPast = current <= now
    const actual = isPast ? Math.round((goal.monthlyNeeded * (0.7 + Math.random() * 0.6)) * 100) / 100 : null
    months.push({
      yearMonth: ym,
      objective: goal.monthlyNeeded,
      actual,
      manualActual: null,
      override: null,
      effective: actual,
    })
    current.setMonth(current.getMonth() + 1)
  }
  return months
}

export function createDemoAdapter() {
  return (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const k = key(config.method || 'GET', config.url || '')
    const handler = handlers.get(k)

    if (!handler) {
      // Deliberately still resolves 200 {} — several demo screens lean on the
      // permissive fallback. The warning is what keeps frontend/backend
      // endpoint drift visible: a mismatched method/path (e.g. the old
      // POST /tr/logout) used to "succeed" here while 404ing in production.
      console.warn(`[demo] no handler registered for "${k}" — returning empty 200`)
    }

    return new Promise((resolve) => {
      setTimeout(() => {
        resolve({
          data: handler ? handler(config) : {},
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
        } as AxiosResponse)
      }, randomDelay())
    })
  }
}
