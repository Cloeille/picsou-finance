import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"
import { useAppStore, type DateFormat } from "@/stores/app-store"
import { DEFAULT_LOCALE, resolveLocale } from "@/i18n/locales"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** Accept French commas as decimal separators: "12,50" → "12.50". */
export function normalizeDecimal(value: string | null | undefined): string {
  return (value ?? '').replace(',', '.')
}

/** Parse a user-entered amount tolerating both "." and "," separators. */
export function parseAmount(value: string | null | undefined): number {
  return parseFloat(normalizeDecimal(value))
}

export function getLocale(): string {
  try {
    // <html lang> is kept in sync with the active i18next language (see i18n/index.ts).
    return localeFromLanguage(document.documentElement.lang || navigator.language)
  } catch {
    return DEFAULT_LOCALE.intlLocale
  }
}

/** Intl locale for a raw language tag, resolved through the SUPPORTED_LOCALES registry. */
export function localeFromLanguage(language: string | null | undefined): string {
  return resolveLocale(language).intlLocale
}

/** Exported for `lib/money.ts`, the only other place allowed to build an Intl currency format. */
export function normalizeIntlLocale(locale: string): string {
  try {
    return Intl.NumberFormat.supportedLocalesOf(locale).length > 0 ? locale : DEFAULT_LOCALE.intlLocale
  } catch {
    return DEFAULT_LOCALE.intlLocale
  }
}

// formatCurrency used to live here. It moved to `lib/money.ts` and became private, so that every
// amount on screen goes through the formatter that knows whether amounts are hidden. Reaching for
// a raw currency format is now a compile error rather than a silent leak during a demo.

/** ISO 3166-1 alpha-2 code → localized country name (e.g. "EE" → "Estonia"). Falls back to the raw code for an unknown/invalid one. */
export function formatCountryName(code: string, locale = getLocale()): string {
  try {
    const name = new Intl.DisplayNames([normalizeIntlLocale(locale)], { type: 'region' }).of(code)
    return name && name !== code ? name : code
  } catch {
    return code
  }
}

/**
 * Parses an API date, anchoring a date-only value at *local* midnight.
 *
 * `new Date('2026-07-31')` is specified to parse as UTC midnight, so anywhere west of UTC the
 * rendered day is the one before. A date-only string carries no zone because it denotes a calendar
 * day rather than an instant — which is exactly why the backend sends `LocalDate` for `priceAsOf`,
 * transaction dates and goal deadlines. Values that do carry a time are left to `Date` untouched:
 * there the offset is real information.
 */
function toDate(dateStr: string): Date {
  return /^\d{4}-\d{2}-\d{2}$/.test(dateStr) ? new Date(`${dateStr}T00:00:00`) : new Date(dateStr)
}

export function formatDate(dateStr: string | null | undefined, locale = getLocale(), format?: DateFormat): string {
  if (!dateStr) return '—'
  const resolvedFormat = format ?? useAppStore.getState().dateFormat
  if (resolvedFormat === 'iso') {
    const d = toDate(dateStr)
    const day = String(d.getDate()).padStart(2, '0')
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const year = d.getFullYear()
    return `${day}-${month}-${year}`
  }
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric' }).format(toDate(dateStr))
}

/**
 * Inverse of {@link formatDate}: parses a user-typed date string back into an
 * ISO `yyyy-MM-dd` string, honoring the active format/locale, or returns `null`
 * when the input can't be parsed into a real calendar date.
 *
 * Accepts `/`, `-` and `.` as separators regardless of the active format (people
 * mix them), and tolerates 2-digit years. The year is always the last token in
 * every shape we render (`dd-mm-yyyy`, `dd/mm/yyyy`, `mm/dd/yyyy`); only the
 * day/month order varies — `mm/dd` for en-US locale (non-iso), `dd/mm` otherwise.
 */
export function parseDate(
  input: string | null | undefined,
  locale = getLocale(),
  format: DateFormat = useAppStore.getState().dateFormat,
): string | null {
  if (!input) return null
  const parts = input.trim().split(/[/.-]/).map((p) => p.trim())
  if (parts.length !== 3 || parts.some((p) => !/^\d+$/.test(p))) return null

  const monthFirst = format !== 'iso' && locale.startsWith('en')
  const [first, second, yearStr] = parts
  const day = Number(monthFirst ? second : first)
  const month = Number(monthFirst ? first : second)
  let year = Number(yearStr)
  if (yearStr.length === 2) year += 2000

  if (year < 1000 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31) return null

  const iso = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
  // Reject impossible dates (e.g. 31/02) by round-tripping through Date.
  const d = new Date(`${iso}T00:00:00`)
  if (d.getFullYear() !== year || d.getMonth() + 1 !== month || d.getDate() !== day) return null
  return iso
}

export function formatDateTime(dateStr: string | null | undefined, locale = getLocale(), format?: DateFormat): string {
  if (!dateStr) return '—'
  const d = toDate(dateStr)
  const resolvedFormat = format ?? useAppStore.getState().dateFormat
  if (resolvedFormat === 'iso') {
    const day = String(d.getDate()).padStart(2, '0')
    const month = String(d.getMonth() + 1).padStart(2, '0')
    const year = d.getFullYear()
    const hours = String(d.getHours()).padStart(2, '0')
    const minutes = String(d.getMinutes()).padStart(2, '0')
    return `${day}-${month}-${year} ${hours}:${minutes}`
  }
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' }).format(d)
}

export function formatPercent(value: number, locale = getLocale()): string {
  return new Intl.NumberFormat(locale, { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(value)
}

function capitalizeFirstCharacter(value: string, locale = getLocale()): string {
  const [first = '', ...rest] = Array.from(value)
  return first.toLocaleUpperCase(normalizeIntlLocale(locale)) + rest.join('')
}

export function todayLabel(locale = getLocale(), date = new Date()): string {
  const safeLocale = normalizeIntlLocale(locale)
  const label = new Intl.DateTimeFormat(safeLocale, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }).format(date)
  return capitalizeFirstCharacter(label, safeLocale)
}

export function formatLocalDate(dateStr: string | null | undefined, locale = getLocale()): string {
  if (!dateStr) return '—'
  return new Intl.DateTimeFormat(locale, { day: '2-digit', month: 'long', year: 'numeric' }).format(toDate(dateStr))
}

/**
 * Completed years between two `yyyy-MM-dd` dates — someone's age on a given day.
 *
 * Computed on the string parts rather than through `Date`: the projection asks this about dates
 * twenty years out, and building two `Date` objects to subtract them would reintroduce exactly
 * the local-midnight problem `toDate` exists to avoid, for no gain.
 *
 * Returns null if either date is malformed, and a negative number is possible (a date before the
 * birth) — callers decide whether that is meaningful.
 */
export function ageAt(birthDate: string, at: string): number | null {
  const born = birthDate.split('-').map(Number)
  const on = at.split('-').map(Number)
  if (born.length !== 3 || on.length !== 3) return null
  if ([...born, ...on].some((part) => !Number.isFinite(part))) return null

  const [bornYear, bornMonth, bornDay] = born
  const [year, month, day] = on
  const hadBirthday = month > bornMonth || (month === bornMonth && day >= bornDay)
  return year - bornYear - (hadBirthday ? 0 : 1)
}

export function formatTimeAgo(dateStr: string | null | undefined, locale = getLocale()): string {
  if (!dateStr) return '—'
  const diff = Date.now() - new Date(dateStr).getTime()
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 1) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(0, 'minute')
  if (minutes < 60) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-minutes, 'minute')
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-hours, 'hour')
  const days = Math.floor(hours / 24)
  return new Intl.RelativeTimeFormat(locale, { numeric: 'auto' }).format(-days, 'day')
}

export type FreshnessLevel = 'fresh' | 'recent' | 'stale' | 'old' | 'unknown'

/** Upper bound of each level, in ms. See the two scales in `lib/constants.ts`. */
export interface FreshnessBounds {
  fresh: number
  recent: number
  stale: number
}

/**
 * How old a date is, bucketed. `unknown` covers a missing date — never synced, never valued —
 * which is not the same as "very old" and must not read as an alarm.
 *
 * `now` is a parameter rather than a `Date.now()` call so callers stay pure and testable; the
 * card that uses this re-renders on a timer to cross a boundary without a remount.
 */
export function freshnessLevel(
  dateStr: string | null | undefined,
  bounds: FreshnessBounds,
  now: number = Date.now(),
): FreshnessLevel {
  if (!dateStr) return 'unknown'
  const age = now - toDate(dateStr).getTime()
  // A date in the future (clock skew between the server and this browser) is as fresh as it gets.
  if (age < bounds.fresh) return 'fresh'
  if (age < bounds.recent) return 'recent'
  if (age < bounds.stale) return 'stale'
  return 'old'
}

/**
 * Text colour per level. Both themes are spelled out: the 600 shades are unreadable on a dark
 * background and the 400s wash out on a light one.
 */
export const FRESHNESS_TEXT_CLASS: Record<FreshnessLevel, string> = {
  fresh: 'text-emerald-600 dark:text-emerald-400',
  recent: 'text-yellow-600 dark:text-yellow-400',
  stale: 'text-orange-600 dark:text-orange-400',
  old: 'text-red-600 dark:text-red-400',
  unknown: 'text-muted-foreground',
}

export function safeRedirect(redirect: string | null, fallback = '/'): string {
  if (!redirect || !redirect.startsWith('/')) return fallback
  return redirect
}

/**
 * True when a post-login redirect target is the backend OAuth2 authorize endpoint, which is
 * server-handled (not a client-side SPA route) and must be reached via a full-page navigation so
 * the native app's Authorization Code + PKCE flow can resume. A `/oauth2/` prefix is inherently a
 * same-origin absolute path — it cannot be a protocol-relative (`//host`) or absolute-URL open
 * redirect — so this doubles as the open-redirect guard for the full-navigation path.
 */
export function isOAuthAuthorizeRedirect(redirect: string): boolean {
  return redirect.startsWith('/oauth2/')
}
