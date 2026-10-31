import type {
  Account,
  AccountType,
  AllocationContribution,
  AllocationResponse,
  AllocationStock,
  AssetClass,
  Budget,
  BudgetSettings,
  CashflowBucket,
  CashflowFlowResponse,
  CashflowPeriod,
  CashflowResponse,
  CategorizationRule,
  Category,
  CategorySpend,
  ChildSpend,
  FlowLink,
  FlowNode,
  RecurringActivity,
  RecurringOccurrence,
  RecurringSeries,
  SpendingByCategoryResponse,
  SpendingDetailResponse,
  Transaction,
  UncategorizedTransaction,
} from '@/types/api'
import { mockAccounts } from './accounts'
import { isoDate } from './dates'

// ── Date helpers ──────────────────────────────────────────────────────────────
// Demo data is generated relative to "today" so the Budget section always looks
// alive. The payday cycle is the calendar month here (cycleStartDay = 1).

const NOW = new Date()
const CYCLE_START = new Date(NOW.getFullYear(), NOW.getMonth(), 1)
const CYCLE_END = new Date(NOW.getFullYear(), NOW.getMonth() + 1, 0)
const CYCLE_FROM = isoDate(CYCLE_START)

function daysFromNow(n: number): string {
  const d = new Date(NOW)
  d.setDate(d.getDate() + n)
  return isoDate(d)
}

/** `days` before `to`, but never before `from`: on a cycle's first day every row lands on it. */
function daysBeforeWithin(to: Date, days: number, from: string): string {
  const d = new Date(to)
  d.setDate(d.getDate() - days)
  return isoDate(d) < from ? from : isoDate(d)
}

function daysAgoWithin(days: number, from: string): string {
  return daysBeforeWithin(NOW, days, from)
}

// ── Categories ────────────────────────────────────────────────────────────────

// `parentId` is null for top-level categories. Logement (id 3) is a parent here, holding two
// sub-categories (Loyer, Énergie) appended at the end — their spend rolls up to it. New entries
// are APPENDED, never inserted, because the arrays below reference categories by index.
export const mockCategories: Category[] = [
  { id: 1, name: 'Salaire', kind: 'INCOME', color: '#10b981', icon: null, isDefault: true, archived: false, sortOrder: 0, parentId: null },
  { id: 2, name: 'Courses', kind: 'EXPENSE', color: '#f59e0b', icon: null, isDefault: true, archived: false, sortOrder: 1, parentId: null },
  { id: 3, name: 'Logement', kind: 'EXPENSE', color: '#6366f1', icon: null, isDefault: true, archived: false, sortOrder: 2, parentId: null },
  { id: 4, name: 'Transport', kind: 'EXPENSE', color: '#0ea5e9', icon: null, isDefault: true, archived: false, sortOrder: 3, parentId: null },
  { id: 5, name: 'Loisirs', kind: 'EXPENSE', color: '#ec4899', icon: null, isDefault: true, archived: false, sortOrder: 4, parentId: null },
  { id: 6, name: 'Abonnements', kind: 'EXPENSE', color: '#8b5cf6', icon: null, isDefault: true, archived: false, sortOrder: 5, parentId: null },
  { id: 7, name: 'Restaurants', kind: 'EXPENSE', color: '#ef4444', icon: null, isDefault: true, archived: false, sortOrder: 6, parentId: null },
  { id: 8, name: 'Épargne', kind: 'TRANSFER', color: '#22c55e', icon: null, isDefault: true, archived: false, sortOrder: 7, parentId: null },
  { id: 9, name: 'Investissement', kind: 'TRANSFER', color: '#14b8a6', icon: null, isDefault: true, archived: false, sortOrder: 8, parentId: null },
  // ── Sub-categories of Logement (id 3) ───────────────────────────────────────
  { id: 10, name: 'Loyer', kind: 'EXPENSE', color: '#818cf8', icon: null, isDefault: false, archived: false, sortOrder: 9, parentId: 3 },
  { id: 11, name: 'Énergie', kind: 'EXPENSE', color: '#a5b4fc', icon: null, isDefault: false, archived: false, sortOrder: 10, parentId: 3 },
  // Top-level, appended after the sub-categories so the index lookups above keep pointing at
  // Loyer/Énergie. No demo spend against it — it exists so the review inbox offers the choice.
  { id: 12, name: 'Impôts & taxes', kind: 'EXPENSE', color: '#a16207', icon: null, isDefault: true, archived: false, sortOrder: 11, parentId: null },
]

/** Index lookups for the sub-categories appended above (kept readable for the spend tables). */
const LOYER = mockCategories[9]
const ENERGIE = mockCategories[10]

// ── Categorization rules ──────────────────────────────────────────────────────

export const mockRules: CategorizationRule[] = [
  { id: 1, matchType: 'COUNTERPARTY', pattern: 'NETFLIX', categoryId: 6, categoryName: 'Abonnements', priority: 0, source: 'USER' },
  { id: 2, matchType: 'KEYWORD', pattern: 'CARREFOUR', categoryId: 2, categoryName: 'Courses', priority: 1, source: 'AUTO' },
  { id: 3, matchType: 'COUNTERPARTY', pattern: 'TOTALENERGIES', categoryId: 3, categoryName: 'Logement', priority: 2, source: 'AUTO' },
]

// ── To-categorize inbox ───────────────────────────────────────────────────────

const inboxSeed: UncategorizedTransaction[] = [
  {
    id: 9001, date: daysAgoWithin(2, CYCLE_FROM), description: 'AMAZON EU SARL', amount: -42.9, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysAgoWithin(2, CYCLE_FROM), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'AMAZON EU SARL',
    merchantLabel: 'Amazon', merchantBrandId: null,
    aiSuggestedCategoryId: null, aiConfidence: null,
  },
  {
    id: 9002, date: daysAgoWithin(3, CYCLE_FROM), description: 'SNCF VOYAGEURS', amount: -68.0, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysAgoWithin(3, CYCLE_FROM), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'SNCF VOYAGEURS',
    merchantLabel: 'SNCF', merchantBrandId: null,
    aiSuggestedCategoryId: 4, aiConfidence: 92,
  },
  {
    id: 9003, date: daysAgoWithin(5, CYCLE_FROM), description: 'BOULANGERIE DU COIN', amount: -8.4, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysAgoWithin(5, CYCLE_FROM), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'BOULANGERIE DU COIN',
    merchantLabel: 'Boulangerie du Coin', merchantBrandId: null,
    aiSuggestedCategoryId: 2, aiConfidence: 71,
  },
]

// ── Envelopes ─────────────────────────────────────────────────────────────────

// `rollup` marks an envelope set on a *parent* category: its `spent` then covers the whole
// subtree (here Loyer + Énergie under Logement). Leaf envelopes pass false.
function envelope(id: number, cat: Category, limit: number, spent: number, rollup = false): Budget {
  const remaining = Math.round((limit - spent) * 100) / 100
  const percent = limit > 0 ? Math.round((spent / limit) * 100) : 0
  return {
    id,
    categoryId: cat.id,
    categoryName: cat.name,
    categoryKind: cat.kind,
    categoryColor: cat.color,
    categoryIcon: cat.icon,
    monthlyLimit: limit,
    spent,
    remaining,
    percent,
    overBudget: spent > limit,
    rollup,
    cycleStart: isoDate(CYCLE_START),
    cycleEnd: isoDate(CYCLE_END),
  }
}

export const mockBudgets: Budget[] = [
  envelope(1, mockCategories[1], 500, 412.3), // Courses
  envelope(2, mockCategories[2], 1100, 1100, true), // Logement (parent — rolls up Loyer + Énergie, at limit)
  envelope(3, mockCategories[3], 200, 168.5), // Transport
  envelope(4, mockCategories[4], 150, 187.9), // Loisirs (over)
  envelope(5, mockCategories[5], 90, 53.97), // Abonnements
  envelope(6, mockCategories[6], 250, 134.2), // Restaurants
]

// ── Settings ──────────────────────────────────────────────────────────────────

export const mockBudgetSettings: BudgetSettings = {
  cycleStartDay: 1,
  logoFetchEnabled: false,
  aiCategorizationEnabled: true,
  aiMode: 'AUTO_HIGH_CONFIDENCE',
  aiConfidenceThreshold: 75,
  currentCycleStart: isoDate(CYCLE_START),
  currentCycleEnd: isoDate(CYCLE_END),
}

// ── Cashflow ──────────────────────────────────────────────────────────────────

const MONTH_LABELS = ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Juin', 'Juil', 'Août', 'Sep', 'Oct', 'Nov', 'Déc']

export function mockCashflow(period: CashflowPeriod): CashflowResponse {
  const { from, to } = periodRange(period)
  const ledger = ledgerIn(period).filter((tx) => !isTransfer(tx))
  const totals = (rows: Transaction[]) => {
    const income = r2(rows.reduce((sum, tx) => sum + Math.max(tx.amount, 0), 0))
    const expense = r2(rows.reduce((sum, tx) => sum - Math.min(tx.amount, 0), 0))
    return { income, expense, net: r2(income - expense) }
  }
  const ranges = period === 'YTD'
    ? Array.from({ length: NOW.getMonth() + 1 }, (_, month) => ({
      start: isoDate(new Date(NOW.getFullYear(), month, 1)),
      end: month === NOW.getMonth() ? to : isoDate(new Date(NOW.getFullYear(), month + 1, 0)),
      label: MONTH_LABELS[month],
    }))
    : Array.from({ length: Math.ceil(CYCLE_END.getDate() / 7) }, (_, week) => ({
      start: isoDate(new Date(NOW.getFullYear(), NOW.getMonth(), 1 + week * 7)),
      end: isoDate(new Date(NOW.getFullYear(), NOW.getMonth(), Math.min(7 + week * 7, CYCLE_END.getDate()))),
      label: `S${week + 1}`,
    }))
  const series: CashflowBucket[] = ranges.map((range) => ({
    ...range,
    ...totals(ledger.filter((tx) => tx.date >= range.start && tx.date <= range.end)),
  }))
  return { period, from, to, ...totals(ledger), series }
}

// ── Allocation ────────────────────────────────────────────────────────────────

/** Mirrors the backend's `AssetClass.of`: the bucket an account type counts in. */
function assetClassOf(type: AccountType): AssetClass {
  switch (type) {
    case 'CHECKING':
      return 'CURRENT'
    case 'LEP': case 'SAVINGS': case 'LIVRET_A': case 'LDDS': case 'LIVRET_JEUNE': case 'PEL': case 'CEL':
      return 'SAVINGS'
    case 'PEA': case 'COMPTE_TITRES': case 'CRYPTO': case 'EMPLOYEE_SAVINGS': case 'ASSURANCE_VIE': case 'PER':
      return 'INVESTMENT'
    default:
      return 'OTHER'
  }
}

const STOCK_CLASSES: AssetClass[] = ['CURRENT', 'SAVINGS', 'INVESTMENT']

/**
 * What the accounts hold per asset class, as `AllocationService.buildStock` sums it: hidden
 * accounts and classes outside the cash allocation are left out. Pockets are left out too, as
 * the dashboard does, since their wallet's balance already holds their money.
 */
function allocationStock(accounts: Account[]): AllocationStock[] {
  const amounts = new Map<AssetClass, number>()
  for (const account of accounts) {
    if (account.hidden || account.parentAccountId != null) continue
    const assetClass = assetClassOf(account.type)
    if (assetClass !== 'OTHER') amounts.set(assetClass, (amounts.get(assetClass) ?? 0) + account.currentBalance)
  }
  const total = [...amounts.values()].reduce((a, b) => a + b, 0)
  return STOCK_CLASSES.filter((assetClass) => amounts.has(assetClass)).map((assetClass) => {
    const amount = amounts.get(assetClass)!
    return { assetClass, amount: r2(amount), percent: total === 0 ? 0 : r2((amount * 100) / total) }
  })
}

/** Transfers into the savings and investment accounts: YTD accumulates, CYCLE is this month's. */
function allocationContributions(period: CashflowPeriod): AllocationContribution[] {
  const factor = cyclesIn(period)
  return [
    { accountId: 1, accountName: 'Livret A', assetClass: 'SAVINGS', color: '#22c55e', amount: 300 * factor },
    { accountId: 7, accountName: 'LEP', assetClass: 'SAVINGS', color: '#16a34a', amount: 150 * factor },
    { accountId: 2, accountName: 'PEA', assetClass: 'INVESTMENT', color: '#8b5cf6', amount: 400 * factor },
  ]
}

export function mockAllocation(period: CashflowPeriod, accounts: Account[]): AllocationResponse {
  const stock = allocationStock(accounts)
  const totalStock = r2(stock.reduce((a, s) => a + s.amount, 0))
  const contributions = allocationContributions(period)
  const totalContributions = contributions.reduce((a, c) => a + c.amount, 0)
  return {
    period,
    from: period === 'YTD' ? isoDate(new Date(NOW.getFullYear(), 0, 1)) : isoDate(CYCLE_START),
    to: isoDate(period === 'YTD' ? NOW : CYCLE_END),
    totalStock,
    stock,
    totalContributions,
    contributions,
  }
}

// ── Recurring ─────────────────────────────────────────────────────────────────

// v2 fields tell the "why" story: Netflix was silently auto-confirmed *and* just had a price
// step (10.99 → 13.49); the salary was auto-confirmed at high confidence; Spotify is still a
// low-confidence suggestion (manual confirm/ignore); the car insurance is a variable-amount
// series the user ignored. runtimeStatus is what the backend would compute from nextDueDate vs
// today (Netflix at +6d → DUE_SOON, everything else outside the 7-day window → SCHEDULED).
export const mockRecurring: RecurringSeries[] = [
  { id: 1, label: 'Netflix', counterparty: 'NETFLIX.COM', expectedAmount: -13.49, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(6), lastSeenDate: daysFromNow(-24), categoryId: 6, categoryName: 'Abonnements', categoryColor: '#8b5cf6', categoryIcon: null, confidence: 0.86, amountMin: -13.49, amountMax: -13.49, variable: false, previousAmount: -10.99, priceChangedAt: daysFromNow(-3), autoConfirmed: true, runtimeStatus: 'DUE_SOON' },
  { id: 2, label: 'Loyer', counterparty: 'AGENCE IMMO', expectedAmount: -1100, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(12), lastSeenDate: daysFromNow(-18), categoryId: 3, categoryName: 'Logement', categoryColor: '#6366f1', categoryIcon: null, confidence: 0.92, amountMin: -1100, amountMax: -1100, variable: false, previousAmount: null, priceChangedAt: null, autoConfirmed: false, runtimeStatus: 'SCHEDULED' },
  { id: 3, label: 'Spotify', counterparty: 'SPOTIFY', expectedAmount: -10.99, cadence: 'MONTHLY', status: 'SUGGESTED', nextDueDate: daysFromNow(9), lastSeenDate: daysFromNow(-21), categoryId: 6, categoryName: 'Abonnements', categoryColor: '#8b5cf6', categoryIcon: null, confidence: 0.74, amountMin: -10.99, amountMax: -10.99, variable: false, previousAmount: null, priceChangedAt: null, autoConfirmed: false, runtimeStatus: 'SCHEDULED' },
  { id: 4, label: 'Salaire', counterparty: 'EMPLOYEUR SAS', expectedAmount: 3200, cadence: 'MONTHLY', status: 'CONFIRMED', nextDueDate: daysFromNow(20), lastSeenDate: daysFromNow(-5), categoryId: 1, categoryName: 'Salaire', categoryColor: '#10b981', categoryIcon: null, confidence: 0.95, amountMin: 3180, amountMax: 3220, variable: false, previousAmount: null, priceChangedAt: null, autoConfirmed: true, runtimeStatus: 'SCHEDULED' },
  { id: 5, label: 'Assurance auto', counterparty: 'MAIF', expectedAmount: -42.6, cadence: 'MONTHLY', status: 'IGNORED', nextDueDate: daysFromNow(15), lastSeenDate: daysFromNow(-16), categoryId: 4, categoryName: 'Transport', categoryColor: '#0ea5e9', categoryIcon: null, confidence: 0.61, amountMin: -55.2, amountMax: -38.4, variable: true, previousAmount: null, priceChangedAt: null, autoConfirmed: false, runtimeStatus: 'SCHEDULED' },
]

/**
 * The "what changed" feed, newest first — mirrors what the backend derives from series state:
 * a recent price step (Netflix) preferred over the auto-confirm note, then a silent
 * high-confidence auto-confirm (the salary). Drives `ActivityFeed` + the undo affordance.
 */
export const mockActivity: RecurringActivity[] = [
  { seriesId: 1, label: 'Netflix', type: 'PRICE_CHANGE', occurredOn: daysFromNow(-3), expectedAmount: -13.49, previousAmount: -10.99, cadence: 'MONTHLY', categoryId: 6, categoryName: 'Abonnements', categoryColor: '#8b5cf6', categoryIcon: null },
  { seriesId: 4, label: 'Salaire', type: 'AUTO_CONFIRMED', occurredOn: daysFromNow(-5), expectedAmount: 3200, previousAmount: null, cadence: 'MONTHLY', categoryId: 1, categoryName: 'Salaire', categoryColor: '#10b981', categoryIcon: null },
]

// Clamp monthly dates like LocalDate.plusMonths(1), so October 31 does not skip November.
function nextMonth(d: Date): Date {
  const lastDayOfNextMonth = new Date(d.getFullYear(), d.getMonth() + 2, 0).getDate()
  return new Date(d.getFullYear(), d.getMonth() + 1, Math.min(d.getDate(), lastDayOfNextMonth))
}

/** Project upcoming occurrences from the confirmed/suggested series across the horizon. */
export function mockCalendar(horizonDays: number): RecurringOccurrence[] {
  const occ: RecurringOccurrence[] = []
  for (const s of mockRecurring) {
    if (s.status === 'IGNORED' || !s.nextDueDate) continue
    let due = new Date(`${s.nextDueDate}T00:00:00`)
    const limit = new Date(NOW)
    limit.setDate(limit.getDate() + horizonDays)
    while (due <= limit) {
      occ.push({
        seriesId: s.id,
        label: s.label,
        counterparty: s.counterparty,
        expectedAmount: s.expectedAmount,
        dueDate: isoDate(due),
        categoryId: s.categoryId,
        categoryName: s.categoryName,
        categoryColor: s.categoryColor,
        categoryIcon: s.categoryIcon,
      })
      due = nextMonth(due)
    }
  }
  return occ.sort((a, b) => a.dueDate.localeCompare(b.dueDate))
}

// ── Flow & spending (M2) ────────────────────────────────────────────────────────
// One dated ledger drives the Sankey, the ranked breakdown, the category drill and the
// transaction search, so every view agrees over any period. Each cycle since January holds the
// salary and the same spend; the current one also holds the review inbox. The flow is built
// exactly like the backend (sources → hub → sinks: expenses, savings accounts, unspent/shortfall) so
// the demo graph stays balanced the same way the real one is.

const r2 = (n: number): number => Math.round(n * 100) / 100

const INCOME_BASE = 3200

// Seed housing under its children so the demo shows their rollup under Logement.
const SPEND_BASE: { cat: Category; amount: number }[] = [
  { cat: LOYER, amount: 900 }, // Loyer (child of Logement)
  { cat: ENERGIE, amount: 200 }, // Énergie (child of Logement)
  { cat: mockCategories[1], amount: 412.3 }, // Courses
  { cat: mockCategories[4], amount: 187.9 }, // Loisirs
  { cat: mockCategories[3], amount: 168.5 }, // Transport
  { cat: mockCategories[6], amount: 134.2 }, // Restaurants
  { cat: mockCategories[5], amount: 53.97 }, // Abonnements
]

/** A couple of believable merchants per category, for the drill transaction list. */
const MERCHANTS_BY_CATEGORY: Record<number, string[]> = {
  1: ['Employeur SAS'],
  2: ['Carrefour', 'Monoprix', 'Boulangerie du Coin'],
  4: ['SNCF', 'TotalEnergies'],
  5: ['Fnac', 'UGC'],
  6: ['Netflix', 'Spotify'],
  7: ['Le Bistrot', 'Sushi Shop'],
  10: ['Agence Immo'], // Loyer (leaf under Logement)
  11: ['EDF'], // Énergie (leaf under Logement)
}

/** How many transactions each category holds in one cycle of the ledger. */
const TX_COUNT_BY_CATEGORY: Record<number, number> = {
  1: 1, // Salaire — single monthly inflow
  2: 9, // Courses — many small grocery trips
  4: 4, // Transport
  5: 3, // Loisirs
  6: 2, // Abonnements — Netflix + Spotify
  7: 5, // Restaurants
  10: 1, // Loyer — single monthly debit
  11: 1, // Énergie — single monthly debit
}

function periodRange(period: CashflowPeriod): { from: string; to: string } {
  return period === 'YTD'
    ? { from: isoDate(new Date(NOW.getFullYear(), 0, 1)), to: isoDate(NOW) }
    : { from: isoDate(CYCLE_START), to: isoDate(CYCLE_END) }
}

/** How many cycles a period spans: YTD holds every month since January. */
function cyclesIn(period: CashflowPeriod): number {
  return period === 'YTD' ? NOW.getMonth() + 1 : 1
}

/**
 * One cycle's transactions for a LEAF category. `totalAbs` is spread across the category's
 * transactions with a deterministic, non-uniform split (so amounts look real rather than
 * identical), and the last one absorbs rounding so the sum is exact. Spending is dated back from
 * `to` and the salary lands on `from`, the cycle's first day. Ids are namespaced by cycle and
 * category (`cycle*10000 + id*100 + i`) so the ledger never repeats one.
 */
function leafTransactions(cat: Category, totalAbs: number, cycle: number, from: string, to: Date): Transaction[] {
  const merchants = MERCHANTS_BY_CATEGORY[cat.id] ?? ['Achat']
  const isIncome = cat.kind === 'INCOME'
  const n = TX_COUNT_BY_CATEGORY[cat.id] ?? merchants.length

  const weights = Array.from({ length: n }, (_, i) => 1 + ((i * 37) % 13) / 12)
  const wSum = weights.reduce((acc, w) => acc + w, 0)
  let allocated = 0
  const amounts = weights.map((w, i) => {
    if (i === n - 1) return r2(totalAbs - allocated)
    const a = r2((totalAbs * w) / wSum)
    allocated = r2(allocated + a)
    return a
  })

  return amounts.map((abs, i) => {
    const merchant = merchants[i % merchants.length]
    return {
      id: cycle * 10000 + cat.id * 100 + i,
      date: isIncome ? from : daysBeforeWithin(to, i * 3 + 2, from),
      description: merchant.toUpperCase(),
      amount: isIncome ? abs : -abs,
      type: null,
      category: cat.name,
      categoryId: cat.id,
      nativeCurrency: 'EUR',
      isManual: false,
      txType: isIncome ? 'DEPOSIT' : 'WITHDRAWAL',
      ticker: null,
      name: null,
      quantity: null,
      pricePerUnit: null,
      merchantLabel: merchant,
      merchantBrandId: null,
      fees: null,
    }
  })
}

const inboxTransactions = inboxSeed.map((tx): Transaction => ({
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

const checkingAccount = mockAccounts.find((account) => account.type === 'CHECKING')!

/** Every transaction since January, newest first: one cycle per month, the current one up to today. */
export let mockLedger: Transaction[] = Array.from({ length: NOW.getMonth() + 1 }, (_, cycle) => {
  const from = isoDate(new Date(NOW.getFullYear(), NOW.getMonth() - cycle, 1))
  const to = cycle === 0 ? NOW : new Date(NOW.getFullYear(), NOW.getMonth() - cycle + 1, 0)
  return [
    ...leafTransactions(mockCategories[0], INCOME_BASE, cycle, from, to),
    ...SPEND_BASE.flatMap((s) => leafTransactions(s.cat, s.amount, cycle, from, to)),
  ]
}).flat().concat(inboxTransactions).map((tx) => ({
  ...tx, accountId: checkingAccount.id, accountName: checkingAccount.name,
})).sort((a, b) => b.date.localeCompare(a.date) || b.id - a.id)

export function mockUncategorized(): UncategorizedTransaction[] {
  return mockLedger.filter((tx) => tx.categoryId == null).map((tx) => {
    const seed = inboxSeed.find((row) => row.id === tx.id)
    return {
      ...tx,
      createdAt: tx.date,
      txType: tx.txType ?? null,
      categoryId: null,
      categoryName: null,
      counterparty: tx.description,
      merchantLabel: tx.merchantLabel ?? null,
      merchantBrandId: tx.merchantBrandId ?? null,
      aiSuggestedCategoryId: seed?.aiSuggestedCategoryId ?? null,
      aiConfidence: seed?.aiConfidence ?? null,
    }
  })
}

function isTransfer(tx: Transaction): boolean {
  return mockCategories.find((category) => category.id === tx.categoryId)?.kind === 'TRANSFER'
}

const categoryById = (id: number | null | undefined): Category | undefined =>
  id == null ? undefined : mockCategories.find((c) => c.id === id)

/**
 * Files a transaction under a category as `PUT /transactions/{id}/category` does: the ledger row
 * takes the category and leaves the review inbox, so every view that reads the ledger follows.
 * The ledger and changed row are replaced, so previous query responses stay unchanged. An unknown
 * transaction or category changes nothing and returns false.
 */
export function categorizeDemoTransaction(id: number, categoryId: number): boolean {
  const category = categoryById(categoryId)
  if (!category || !mockLedger.some((tx) => tx.id === id)) return false
  mockLedger = mockLedger.map((tx) => (tx.id === id ? { ...tx, category: category.name, categoryId: category.id } : tx))
  return true
}

function ledgerIn(period: CashflowPeriod): Transaction[] {
  const { from, to } = periodRange(period)
  return mockLedger.filter((tx) => tx.date >= from && tx.date <= to)
}

export function mockFlow(period: CashflowPeriod): CashflowFlowResponse {
  const { from, to } = periodRange(period)
  const income = r2(ledgerIn(period).filter((tx) => !isTransfer(tx)).reduce((acc, tx) => (tx.amount > 0 ? acc + tx.amount : acc), 0))

  const expenseItems = mockSpendingByCategory(period).categories.map((c) => ({
    key: c.categoryId == null ? '__expense_uncat__' : `cat:${c.categoryId}`,
    label: c.name,
    color: c.color,
    amount: c.amount,
  }))

  const totalExpense = r2(expenseItems.reduce((acc, e) => acc + e.amount, 0))
  const net = r2(income - totalExpense)

  // Transfers into savings/investment accounts: the allocation demo's contributions, so the
  // Sankey and the allocation view agree (one SAVINGS sink per account, largest first).
  const savings = allocationContributions(period).sort((a, b) => b.amount - a.amount)
  const saved = r2(savings.reduce((acc, s) => acc + s.amount, 0))
  // A small transfer to an account Picsou doesn't see, to showcase the TRANSFER_OUT sink.
  const transferredOut = r2(50 * cyclesIn(period))
  const out = r2(totalExpense + saved + transferredOut)
  const shortfall = out > income ? r2(out - income) : 0
  const unspent = income > out ? r2(income - out) : 0

  const nodes: FlowNode[] = []
  const links: FlowLink[] = []

  // Sources (left): the salary, plus a shortfall source if spending + savings exceed it.
  nodes.push({ key: `cat:${mockCategories[0].id}`, label: mockCategories[0].name, color: mockCategories[0].color, type: 'INCOME' })
  if (shortfall > 0) nodes.push({ key: '__shortfall__', label: null, color: null, type: 'SHORTFALL' })

  const hubIndex = nodes.length
  nodes.push({ key: '__hub__', label: null, color: null, type: 'HUB' })
  links.push({ source: 0, target: hubIndex, value: income })
  if (shortfall > 0) links.push({ source: 1, target: hubIndex, value: shortfall })

  // Sinks (right): expense categories, the savings accounts, transfers out, then income left unspent.
  for (const e of expenseItems) {
    const idx = nodes.length
    nodes.push({ key: e.key, label: e.label, color: e.color, type: 'EXPENSE' })
    links.push({ source: hubIndex, target: idx, value: e.amount })
  }
  for (const s of savings) {
    const idx = nodes.length
    nodes.push({ key: `acct:${s.accountId}`, label: s.accountName, color: s.color, type: 'SAVINGS', assetClass: s.assetClass })
    links.push({ source: hubIndex, target: idx, value: r2(s.amount) })
  }
  {
    const idx = nodes.length
    nodes.push({ key: '__transfer_out__', label: null, color: null, type: 'TRANSFER_OUT' })
    links.push({ source: hubIndex, target: idx, value: transferredOut })
  }
  if (unspent > 0) {
    const idx = nodes.length
    nodes.push({ key: '__unspent__', label: null, color: null, type: 'UNSPENT' })
    links.push({ source: hubIndex, target: idx, value: unspent })
  }

  return { period, from, to, income, expense: totalExpense, net, saved, withdrawn: 0, transferredOut, transferredIn: 0, nodes, links }
}

export function mockSpendingByCategory(period: CashflowPeriod): SpendingByCategoryResponse {
  const { from, to } = periodRange(period)
  const expenses = ledgerIn(period).filter((tx) => tx.amount < 0 && !isTransfer(tx))
  const grouped = new Map<number | null, Transaction[]>()
  for (const tx of expenses) {
    const categoryId = tx.categoryId ?? null
    const rows = grouped.get(categoryId) ?? []
    rows.push(tx)
    grouped.set(categoryId, rows)
  }
  const rows = [...grouped].map(([categoryId, transactions]) => {
    const cat = categoryById(categoryId)
    const parent = categoryById(cat?.parentId)
    return {
      categoryId,
      slug: null,
      name: cat?.name ?? null,
      color: cat?.color ?? null,
      icon: cat?.icon ?? null,
      amount: r2(transactions.reduce((sum, tx) => sum - tx.amount, 0)),
      count: transactions.length,
      parentId: parent?.id ?? null,
      parentName: parent?.name ?? null,
      parentColor: parent?.color ?? null,
    }
  })
  const totalExpense = r2(rows.reduce((acc, r) => acc + r.amount, 0))
  const categories: CategorySpend[] = rows
    .map((r) => ({ ...r, share: totalExpense > 0 ? Math.round((r.amount / totalExpense) * 1e4) / 1e4 : 0 }))
    .sort((a, b) => b.amount - a.amount)

  return { period, from, to, totalExpense, categories }
}

export function mockCategoryDetail(categoryId: number, period: CashflowPeriod): SpendingDetailResponse {
  const { from, to } = periodRange(period)
  const cat = categoryById(categoryId)
  const ledger = ledgerIn(period)

  // A drill spans the category's subtree, as CashflowFlowService.categoryDetail does: the rows
  // filed under it plus every (non-archived) child's, newest first as the ledger is. `children`
  // carries each child's signed subtotal + count, and is empty for a leaf.
  const childCats = mockCategories.filter((c) => c.parentId === categoryId && !c.archived)
  const subtree = new Set([categoryId, ...childCats.map((c) => c.id)])
  const transactions = ledger.filter((tx) => tx.categoryId != null && subtree.has(tx.categoryId))
  const children: ChildSpend[] = childCats.map((child) => {
    const txns = ledger.filter((tx) => tx.categoryId === child.id)
    return {
      categoryId: child.id,
      name: child.name,
      color: child.color,
      icon: child.icon,
      total: r2(txns.reduce((acc, t) => acc + t.amount, 0)),
      count: txns.length,
    }
  })

  const total = r2(transactions.reduce((acc, t) => acc + t.amount, 0))

  return {
    categoryId,
    slug: null,
    name: cat?.name ?? 'Catégorie',
    color: cat?.color ?? null,
    icon: null,
    period,
    from,
    to,
    total,
    count: transactions.length,
    transactions,
    children,
  }
}

export function mockBudgetSummaries(): Budget[] {
  return mockBudgets.map((budget) => {
    const cat = mockCategories.find((category) => category.id === budget.categoryId)!
    const expenses = mockCategoryDetail(cat.id, 'CYCLE').transactions.filter((tx) => tx.amount < 0 && !isTransfer(tx))
    const spent = r2(expenses.reduce((sum, tx) => sum - tx.amount, 0))
    return envelope(budget.id, cat, budget.monthlyLimit, spent, budget.rollup)
  })
}

// Search the shared ledger so the cycle, YTD, salary and review inbox keep their rows.
export function mockTransactionSearch(params: {
  from?: string
  to?: string
  accountId?: number | string
  categoryId?: number | string
}): Transaction[] {
  return mockLedger
    .filter((tx) =>
      (!params.from || tx.date >= params.from) &&
      (!params.to || tx.date <= params.to) &&
      (params.accountId == null || tx.accountId === Number(params.accountId)) &&
      (params.categoryId == null || tx.categoryId === Number(params.categoryId)))
    .sort((a, b) => b.date.localeCompare(a.date) || b.id - a.id)
}
