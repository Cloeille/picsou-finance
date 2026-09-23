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
  { value: 'REAL_ESTATE', labelKey: 'accountTypes.realEstate' },
  { value: 'SCPI', labelKey: 'accountTypes.scpi' },
  { value: 'EMPLOYEE_SAVINGS', labelKey: 'accountTypes.employeeSavings' },
  { value: 'LOAN', labelKey: 'accountTypes.loan' },
  { value: 'OTHER', labelKey: 'accountTypes.other' },
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
  // Property valuations refresh monthly at most -- the underlying open data is published
  // twice a year -- so anything shorter would just re-fetch an identical answer.
  realEstate: 10 * 60 * 1000,
} as const

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
