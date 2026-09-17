import type {
  AllocationResponse,
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

// ── Date helpers ──────────────────────────────────────────────────────────────
// Demo data is generated relative to "today" so the Budget section always looks
// alive. The payday cycle is the calendar month here (cycleStartDay = 1).

function iso(d: Date): string {
  return d.toISOString().split('T')[0]
}

const NOW = new Date()
const CYCLE_START = new Date(NOW.getFullYear(), NOW.getMonth(), 1)
const CYCLE_END = new Date(NOW.getFullYear(), NOW.getMonth() + 1, 0)

function daysFromNow(n: number): string {
  const d = new Date(NOW)
  d.setDate(d.getDate() + n)
  return iso(d)
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

export const mockUncategorized: UncategorizedTransaction[] = [
  {
    id: 9001, date: daysFromNow(-2), description: 'AMAZON EU SARL', amount: -42.9, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-2), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'AMAZON EU SARL',
    merchantLabel: 'Amazon', merchantBrandId: null,
    aiSuggestedCategoryId: null, aiConfidence: null,
  },
  {
    id: 9002, date: daysFromNow(-3), description: 'SNCF VOYAGEURS', amount: -68.0, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-3), isManual: false,
    txType: 'WITHDRAWAL', ticker: null, quantity: null, pricePerUnit: null,
    categoryId: null, categoryName: null, counterparty: 'SNCF VOYAGEURS',
    merchantLabel: 'SNCF', merchantBrandId: null,
    aiSuggestedCategoryId: 4, aiConfidence: 92,
  },
  {
    id: 9003, date: daysFromNow(-5), description: 'BOULANGERIE DU COIN', amount: -8.4, type: null,
    category: null, nativeCurrency: 'EUR', createdAt: daysFromNow(-5), isManual: false,
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
    cycleStart: iso(CYCLE_START),
    cycleEnd: iso(CYCLE_END),
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
  currentCycleStart: iso(CYCLE_START),
  currentCycleEnd: iso(CYCLE_END),
}

// ── Cashflow ──────────────────────────────────────────────────────────────────

const MONTH_LABELS = ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Juin', 'Juil', 'Août', 'Sep', 'Oct', 'Nov', 'Déc']

export function mockCashflow(period: CashflowPeriod): CashflowResponse {
  if (period === 'YTD') {
    const series: CashflowBucket[] = []
    let income = 0
    let expense = 0
    for (let m = 0; m <= NOW.getMonth(); m++) {
      const inc = 3200 + ((m * 37) % 250)
      const exp = 1950 + ((m * 53) % 600)
      income += inc
      expense += exp
      series.push({
        start: iso(new Date(NOW.getFullYear(), m, 1)),
        end: iso(new Date(NOW.getFullYear(), m + 1, 0)),
        label: MONTH_LABELS[m],
        income: inc,
        expense: exp,
        net: inc - exp,
      })
    }
    return { period, from: iso(new Date(NOW.getFullYear(), 0, 1)), to: iso(NOW), income, expense, net: income - expense, series }
  }

  // CYCLE — four weekly buckets within the current month.
  const weekly = [
    { label: 'S1', income: 3200, expense: 720 },
    { label: 'S2', income: 0, expense: 540 },
    { label: 'S3', income: 0, expense: 610 },
    { label: 'S4', income: 0, expense: 387 },
  ]
  const series: CashflowBucket[] = weekly.map((w, i) => {
    const s = new Date(CYCLE_START)
    s.setDate(1 + i * 7)
    const e = new Date(CYCLE_START)
    e.setDate(Math.min(7 + i * 7, CYCLE_END.getDate()))
    return { start: iso(s), end: iso(e), label: w.label, income: w.income, expense: w.expense, net: w.income - w.expense }
  })
  const income = weekly.reduce((a, w) => a + w.income, 0)
  const expense = weekly.reduce((a, w) => a + w.expense, 0)
  return { period, from: iso(CYCLE_START), to: iso(CYCLE_END), income, expense, net: income - expense, series }
}

// ── Allocation ────────────────────────────────────────────────────────────────

export function mockAllocation(period: CashflowPeriod): AllocationResponse {
  const stock = [
    { assetClass: 'CURRENT' as const, amount: 3920, percent: 11 },
    { assetClass: 'SAVINGS' as const, amount: 12920, percent: 36 },
    { assetClass: 'INVESTMENT' as const, amount: 18770, percent: 53 },
  ]
  const totalStock = stock.reduce((a, s) => a + s.amount, 0)
  // YTD shows the accumulated transfers; CYCLE shows just this month's.
  const factor = period === 'YTD' ? 6 : 1
  const contributions = [
    { accountId: 1, accountName: 'Livret A', assetClass: 'SAVINGS' as const, color: '#22c55e', amount: 300 * factor },
    { accountId: 7, accountName: 'LEP', assetClass: 'SAVINGS' as const, color: '#16a34a', amount: 150 * factor },
    { accountId: 2, accountName: 'PEA', assetClass: 'INVESTMENT' as const, color: '#8b5cf6', amount: 400 * factor },
  ]
  const totalContributions = contributions.reduce((a, c) => a + c.amount, 0)
  return {
    period,
    from: period === 'YTD' ? iso(new Date(NOW.getFullYear(), 0, 1)) : iso(CYCLE_START),
    to: iso(period === 'YTD' ? NOW : CYCLE_END),
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
        dueDate: iso(due),
        categoryId: s.categoryId,
        categoryName: s.categoryName,
        categoryColor: s.categoryColor,
        categoryIcon: s.categoryIcon,
      })
      // monthly cadence step (all demo series are monthly)
      due = new Date(due)
      due.setMonth(due.getMonth() + 1)
    }
  }
  return occ.sort((a, b) => a.dueDate.localeCompare(b.dueDate))
}

// ── Flow & spending (M2) ────────────────────────────────────────────────────────
// One coherent dataset drives the Sankey, the ranked breakdown and the category drill,
// so the three views always agree. YTD is just the cycle scaled ~6×. The flow is built
// exactly like the backend (sources → hub → sinks, savings sink when net positive) so
// the demo graph stays balanced the same way the real one is.

const r2 = (n: number): number => Math.round(n * 100) / 100

const INCOME_BASE = 3200

// Spend is recorded against LEAF categories only — exactly like the backend, where a
// transaction never attaches to a parent. Logement is now a parent, so its 1100 is split
// across its two children: Loyer (900) + Énergie (200) = 1100, leaving every aggregate
// total identical to before. The client rolls these back up under Logement via `parentId`.
const SPEND_BASE: { cat: Category; amount: number }[] = [
  { cat: LOYER, amount: 900 }, // Loyer (child of Logement)
  { cat: ENERGIE, amount: 200 }, // Énergie (child of Logement)
  { cat: mockCategories[1], amount: 412.3 }, // Courses
  { cat: mockCategories[4], amount: 187.9 }, // Loisirs
  { cat: mockCategories[3], amount: 168.5 }, // Transport
  { cat: mockCategories[6], amount: 134.2 }, // Restaurants
  { cat: mockCategories[5], amount: 53.97 }, // Abonnements
]
const UNCATEGORIZED_BASE = 60

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

/**
 * Single source of truth for how many transactions each category holds in the
 * period. Both the spending breakdown (`count`) and the per-category drill read
 * this, so the "N transactions" badge always matches the rows actually listed —
 * and the backend gives the same guarantee for free (detail count == size()).
 */
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
    ? { from: iso(new Date(NOW.getFullYear(), 0, 1)), to: iso(NOW) }
    : { from: iso(CYCLE_START), to: iso(CYCLE_END) }
}

export function mockFlow(period: CashflowPeriod): CashflowFlowResponse {
  const factor = period === 'YTD' ? 6 : 1
  const { from, to } = periodRange(period)
  const income = r2(INCOME_BASE * factor)

  const expenseItems = [
    ...SPEND_BASE.map((s) => ({
      key: `cat:${s.cat.id}`,
      label: s.cat.name as string | null,
      color: s.cat.color,
      amount: r2(s.amount * factor),
    })),
    { key: '__expense_uncat__', label: null, color: null, amount: r2(UNCATEGORIZED_BASE * factor) },
  ].sort((a, b) => b.amount - a.amount)

  const totalExpense = r2(expenseItems.reduce((acc, e) => acc + e.amount, 0))
  const net = r2(income - totalExpense)

  const nodes: FlowNode[] = []
  const links: FlowLink[] = []

  // Sources (left): the salary, plus a drawdown source if we overspent.
  nodes.push({ key: `cat:${mockCategories[0].id}`, label: mockCategories[0].name, color: mockCategories[0].color, type: 'INCOME' })
  if (net < 0) nodes.push({ key: '__drawdown__', label: null, color: null, type: 'INCOME' })

  const hubIndex = nodes.length
  nodes.push({ key: '__hub__', label: null, color: null, type: 'HUB' })
  links.push({ source: 0, target: hubIndex, value: income })
  if (net < 0) links.push({ source: 1, target: hubIndex, value: r2(-net) })

  // Sinks (right): expense categories, then a savings sink if net positive.
  for (const e of expenseItems) {
    const idx = nodes.length
    nodes.push({ key: e.key, label: e.label, color: e.color, type: 'EXPENSE' })
    links.push({ source: hubIndex, target: idx, value: e.amount })
  }
  if (net > 0) {
    const idx = nodes.length
    nodes.push({ key: '__savings__', label: null, color: null, type: 'SAVINGS' })
    links.push({ source: hubIndex, target: idx, value: net })
  }

  return { period, from, to, income, expense: totalExpense, net, nodes, links }
}

export function mockSpendingByCategory(period: CashflowPeriod): SpendingByCategoryResponse {
  const factor = period === 'YTD' ? 6 : 1
  const { from, to } = periodRange(period)

  const rows = [
    ...SPEND_BASE.map((s) => {
      // Leaf rows carry their parent's identity so the client can fold them under a group
      // header — the aggregation itself stays strictly leaf-scoped (no double-counting).
      const parent = s.cat.parentId != null ? mockCategories.find((c) => c.id === s.cat.parentId) : null
      return {
        categoryId: s.cat.id as number | null,
        slug: null,
        name: s.cat.name as string | null,
        color: s.cat.color,
        icon: null,
        amount: r2(s.amount * factor),
        count: TX_COUNT_BY_CATEGORY[s.cat.id] ?? 3,
        parentId: parent?.id ?? null,
        parentName: parent?.name ?? null,
        parentColor: parent?.color ?? null,
      }
    }),
    {
      categoryId: null,
      slug: null,
      name: null,
      color: null,
      icon: null,
      amount: r2(UNCATEGORIZED_BASE * factor),
      count: 2,
      parentId: null,
      parentName: null,
      parentColor: null,
    },
  ]
  const totalExpense = r2(rows.reduce((acc, r) => acc + r.amount, 0))
  const categories: CategorySpend[] = rows
    .map((r) => ({ ...r, share: totalExpense > 0 ? Math.round((r.amount / totalExpense) * 1e4) / 1e4 : 0 }))
    .sort((a, b) => b.amount - a.amount)

  return { period, from, to, totalExpense, categories }
}

/**
 * Synthesize the transaction list for a single LEAF category over the period. The leaf's total
 * is spread across `n` transactions with a deterministic, non-uniform split (so amounts look
 * real rather than identical), and the last one absorbs rounding so the sum matches the breakdown
 * total exactly. Transaction ids are namespaced by category id (`id*100 + i`) so a parent drill
 * can concatenate several leaves' lists without colliding.
 */
function leafTransactions(cat: Category, factor: number): Transaction[] {
  const base = SPEND_BASE.find((s) => s.cat.id === cat.id)?.amount ?? 80
  const merchants = MERCHANTS_BY_CATEGORY[cat.id] ?? ['Achat']
  const isIncome = cat.kind === 'INCOME'
  const n = TX_COUNT_BY_CATEGORY[cat.id] ?? merchants.length

  const totalAbs = r2(base * factor)
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
      id: cat.id * 100 + i,
      date: daysFromNow(-(i * 3 + 2)),
      description: merchant.toUpperCase(),
      amount: isIncome ? abs : -abs,
      type: null,
      category: cat.name,
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

export function mockCategoryDetail(categoryId: number, period: CashflowPeriod): SpendingDetailResponse {
  const factor = period === 'YTD' ? 6 : 1
  const { from, to } = periodRange(period)
  const cat = mockCategories.find((c) => c.id === categoryId)

  // A parent drill rolls up its (non-archived) children: the transaction list is the union of the
  // children's leaves, and `children` carries each child's signed subtotal + count. A leaf returns
  // its own transactions with an empty `children` — mirroring CashflowFlowService.categoryDetail.
  const childCats = mockCategories.filter((c) => c.parentId === categoryId && !c.archived)

  let transactions: Transaction[]
  let children: ChildSpend[]
  if (childCats.length > 0) {
    children = childCats.map((child) => {
      const txns = leafTransactions(child, factor)
      return {
        categoryId: child.id,
        name: child.name,
        color: child.color,
        icon: child.icon,
        total: r2(txns.reduce((acc, t) => acc + t.amount, 0)),
        count: txns.length,
      }
    })
    transactions = childCats
      .flatMap((child) => leafTransactions(child, factor))
      .sort((a, b) => b.date.localeCompare(a.date)) // newest first across the whole subtree
  } else {
    transactions = cat ? leafTransactions(cat, factor) : []
    children = []
  }

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
