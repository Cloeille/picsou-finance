import type { AccountType } from '@/types/api'

export const ACCOUNT_TYPES: { value: AccountType; labelKey: string }[] = [
  { value: 'CHECKING', labelKey: 'accountTypes.checking' },
  { value: 'SAVINGS', labelKey: 'accountTypes.savings' },
  { value: 'LEP', labelKey: 'accountTypes.lep' },
  { value: 'LIVRET_A', labelKey: 'accountTypes.livretA' },
  { value: 'LDDS', labelKey: 'accountTypes.ldds' },
  { value: 'LIVRET_JEUNE', labelKey: 'accountTypes.livretJeune' },
  { value: 'PEL', labelKey: 'accountTypes.pel' },
  { value: 'CEL', labelKey: 'accountTypes.cel' },
  { value: 'PEA', labelKey: 'accountTypes.pea' },
  { value: 'COMPTE_TITRES', labelKey: 'accountTypes.compteTitres' },
  { value: 'CRYPTO', labelKey: 'accountTypes.crypto' },
  { value: 'ASSURANCE_VIE', labelKey: 'accountTypes.assuranceVie' },
  { value: 'REAL_ESTATE', labelKey: 'accountTypes.realEstate' },
  { value: 'SCPI', labelKey: 'accountTypes.scpi' },
  { value: 'EMPLOYEE_SAVINGS', labelKey: 'accountTypes.employeeSavings' },
  { value: 'LOAN', labelKey: 'accountTypes.loan' },
  { value: 'OTHER', labelKey: 'accountTypes.other' },
]

/**
 * Account types whose value comes from `account_holding` lines rather than a stored
 * balance — the client-side mirror of the backend's `AccountType.isInvestment()`, plus
 * `EMPLOYEE_SAVINGS`, whose FCPE lines arrive from the Amundi sync rather than from
 * manual BUY/SELL entry.
 *
 * Lives here because three pages used to keep their own copy, so adding a type meant
 * three chances to forget one.
 */
export const HOLDING_ACCOUNT_TYPES: AccountType[] = [
  'PEA', 'COMPTE_TITRES', 'CRYPTO', 'EMPLOYEE_SAVINGS', 'ASSURANCE_VIE',
]

/** Translation key for an account type's display label. */
export function accountTypeLabelKey(type: AccountType): string {
  return ACCOUNT_TYPES.find((t) => t.value === type)?.labelKey ?? 'accountTypes.other'
}

/**
 * Curated list of valid ISO 4217 codes offered in the account form's currency
 * dropdown (EUR first). Labels are rendered live via `Intl.DisplayNames`, so this
 * stays codes-only and is trivial to extend. The backend `@ValidCurrency` constraint
 * accepts any real ISO 4217 code, so this list can grow without backend changes.
 */
export const SUPPORTED_CURRENCIES = [
  'EUR', 'USD', 'GBP', 'CHF', 'JPY', 'CAD', 'AUD', 'CNY',
  'SEK', 'NOK', 'DKK', 'NZD', 'HKD', 'SGD', 'PLN',
] as const

export const ACCOUNT_COLORS = [
  '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
  '#ec4899', '#f43f5e', '#ef4444', '#f97316',
  '#eab308', '#84cc16', '#22c55e', '#10b981',
  '#14b8a6', '#06b6d4', '#0ea5e9', '#3b82f6',
]

export const QUERY_STALE_TIMES = {
  dashboard: 5 * 60 * 1000,
  accounts: 1 * 60 * 1000,
  accountDetail: 2 * 60 * 1000,
  sync: 30 * 1000,
  goals: 2 * 60 * 1000,
  budget: 2 * 60 * 1000,
  // Property valuations refresh monthly at most -- the underlying open data is published
  // twice a year -- so anything shorter would just re-fetch an identical answer.
  realEstate: 10 * 60 * 1000,
  // Allocation moves at the pace of the portfolio behind it, and the score is read, not
  // watched -- the dashboard's cadence is the right one here too.
  analysis: 5 * 60 * 1000,
} as const

/**
 * Households' savings rate in France, as a share of gross disposable income (INSEE).
 *
 * The benchmark the goals page compares a member's own rate against.
 *
 * **"Gross disposable income" is gross of capital consumption, not of tax.** RDB is measured
 * after compulsory levies, so its base is a net-of-tax concept -- which is why the member's side
 * divides by their net income and not by a gross salary. Reading the "brut" the other way once
 * cost this feature a denominator about a quarter too large.
 *
 * The two are still not the same quantity: this one is household-wide national-accounts saving,
 * the member's is their recurring plans over the net they stated. Close enough to answer "am I
 * saving more or less than people around me", not close enough to be a statistic -- which is why
 * the tooltip quotes the definition rather than just the number.
 */
export const FRENCH_HOUSEHOLD_SAVINGS_RATE = 17.5

/**
 * Length of the SMS verification code (TAN) Trade Republic sends during device
 * pairing. Shared by every TR entry point (AddAccountModal, SyncAllModal,
 * TradeRepublicTab) so client-side validation stays consistent.
 */
export const TR_VERIFICATION_CODE_LENGTH = 4

/**
 * Mirrors the `@Size` bounds on `CryptoExchangeController.AddExchangeRequest`, shared by both
 * exchange forms (AddAccountModal, CryptoExchangeTab).
 *
 * A credential over the limit is rejected as a 422 whose ProblemDetail carries an `errors` map
 * but no `detail` — and the forms only render `detail`, so the user gets an error with no text.
 * Capping the inputs means that response is unreachable from the UI. The backend bounds are sized
 * against the `varchar(500)` columns holding the AES-GCM ciphertext; raise these only together.
 */
export const EXCHANGE_API_KEY_MAX_LENGTH = 200
export const EXCHANGE_API_SECRET_MAX_LENGTH = 300

/**
 * How long a successful `session-probe` result (RequireAuth's cookie-backed
 * session check) may sit in the query cache after it stops being observed
 * (isAuthenticated flips true). Bounded rather than Infinity so a stale
 * "success" can eventually be garbage-collected as a backstop, even if some
 * future logout path forgot to explicitly clear it via queryClient.clear().
 */
export const SESSION_PROBE_GC_TIME = 5 * 60 * 1000

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

/**
 * Upper bound of each freshness level, in ms since the date being judged. Read in order:
 * the first bound not exceeded wins, and anything past the last one is `old`.
 *
 * Two scales because the two dates are produced on entirely different cadences, and a scale
 * that cries wolf is one users learn to ignore. Bank and wallet syncs run daily
 * (`SchedulerService.dailyBankSync`), so a figure over a day old means something is wrong.
 * Property valuations run monthly (`monthlyPropertyValuation`, 1st of the month) against
 * sources that themselves refresh twice a year — a 40-day-old estimate is the system working
 * as designed, and would be permanently red on the sync scale.
 */
export const SYNC_FRESHNESS_BOUNDS_MS = { fresh: DAY_MS, recent: 2 * DAY_MS, stale: 7 * DAY_MS }
export const VALUATION_FRESHNESS_BOUNDS_MS = { fresh: 35 * DAY_MS, recent: 60 * DAY_MS, stale: 90 * DAY_MS }

/**
 * The native `<select>` chrome, matching the `Input` primitive.
 *
 * Selects are not a shadcn primitive here — the project uses the native element — so this string
 * is the only thing keeping them on the same scale as inputs, and every copy is a chance for one
 * to drift off the ladder `docs/features/ui-control-shape-system.md` exists to hold.
 *
 * `AddAccountModal` and `FinaryTab` still carry their own copy of the same declarations without
 * the leading `flex`. They are left alone deliberately: folding them in here would add `flex` to
 * four controls as a side effect of an unrelated feature, and a visual change belongs in a change
 * that is about the visuals.
 */
export const SELECT_CONTROL_CLASS =
  'flex h-10 w-full rounded-xl border border-input bg-input/20 px-4 text-sm outline-none dark:bg-input/30'
