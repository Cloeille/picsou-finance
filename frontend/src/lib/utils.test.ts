import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import {
  ageAt,
  cn,
  formatDate,
  formatPercent,
  freshnessLevel,
  isOAuthAuthorizeRedirect,
  localeFromLanguage,
  parseDate,
  todayLabel,
} from './utils'

describe('cn', () => {
  it('merges class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('handles conditional classes', () => {
    const enabled = false
    expect(cn('foo', enabled && 'bar', 'baz')).toBe('foo baz')
  })

  it('merges tailwind conflicts', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })
})

describe('formatDate', () => {
  it('formats ISO date string', () => {
    const result = formatDate('2025-03-15', 'fr-FR')
    expect(result).toBeTruthy()
    expect(typeof result).toBe('string')
  })

  describe('west of UTC', () => {
    // A date-only value denotes a calendar day, not an instant: `new Date('2026-07-31')` is
    // specified to parse as UTC midnight, so behind UTC every LocalDate the backend sends —
    // priceAsOf, transaction dates, goal deadlines — used to render as the day before. The
    // runner's own zone is UTC, where the bug is invisible, hence forcing one here.
    // stubEnv rather than touching process.env: it is typed by vitest, so the app tsconfig keeps
    // its node-free `types: ["vite/client"]`, and the restore is handled for us.
    beforeEach(() => { vi.stubEnv('TZ', 'America/New_York') })
    afterEach(() => { vi.unstubAllEnvs() })

    it('keeps a date-only value on its own day', () => {
      expect(formatDate('2026-07-31', 'fr-FR', 'iso')).toBe('31-07-2026')
      expect(formatDate('2026-07-31', 'fr-FR', 'locale')).toBe('31/07/2026')
    })

    it('still honours the offset of a value that carries a time', () => {
      // 02:00 UTC is the previous day at 22:00 in New York, and that shift is real information
      // rather than a parsing artefact — the date-only rule must not swallow it.
      expect(formatDate('2026-07-31T02:00:00Z', 'fr-FR', 'iso')).toBe('30-07-2026')
    })
  })
})

describe('formatPercent', () => {
  it('formats percentage', () => {
    const result = formatPercent(0.5)
    expect(result).toContain('50')
  })
})

describe('localeFromLanguage', () => {
  it('maps supported app languages to browser locales', () => {
    expect(localeFromLanguage('fr')).toBe('fr-FR')
    expect(localeFromLanguage('fr-CA')).toBe('fr-FR')
    expect(localeFromLanguage('en')).toBe('en-US')
    expect(localeFromLanguage('de')).toBe('de-DE')
    expect(localeFromLanguage('de-AT')).toBe('de-DE')
    expect(localeFromLanguage('es')).toBe('es-ES')
  })

  it('falls back to the app default locale for unknown or missing tags', () => {
    // The app's fallback language is French (i18n fallbackLng: 'fr'),
    // so unresolvable tags map to fr-FR — not en-US.
    expect(localeFromLanguage(undefined)).toBe('fr-FR')
    expect(localeFromLanguage('it')).toBe('fr-FR')
  })
})

describe('todayLabel', () => {
  const monday = new Date('2026-07-06T12:00:00Z')

  it('capitalizes the weekday in French', () => {
    expect(todayLabel('fr-FR', monday)).toMatch(/^Lundi\b/)
  })

  it('keeps the weekday capitalized in English', () => {
    expect(todayLabel('en-US', monday)).toMatch(/^Monday\b/)
  })
})

describe('parseDate', () => {
  it('parses dd/mm/yyyy in fr-FR locale mode', () => {
    expect(parseDate('15/03/2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('parses mm/dd/yyyy in en-US locale mode (day/month swapped)', () => {
    // 03/15 must be read month-first → March 15th, not "day 15 month 3".
    expect(parseDate('03/15/2025', 'en-US', 'locale')).toBe('2025-03-15')
  })

  it('parses dd-mm-yyyy in iso mode regardless of locale', () => {
    expect(parseDate('15-03-2025', 'en-US', 'iso')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'iso')).toBe('2025-03-15')
  })

  it('accepts mixed separators (/, -, .)', () => {
    expect(parseDate('15.03.2025', 'fr-FR', 'locale')).toBe('2025-03-15')
    expect(parseDate('15-03-2025', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('expands 2-digit years into the 2000s', () => {
    expect(parseDate('15/03/25', 'fr-FR', 'locale')).toBe('2025-03-15')
  })

  it('rejects impossible calendar dates', () => {
    expect(parseDate('31/02/2025', 'fr-FR', 'locale')).toBeNull() // no Feb 31
    expect(parseDate('00/01/2025', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/13/2025', 'fr-FR', 'locale')).toBeNull() // month 13
  })

  it('rejects malformed input', () => {
    expect(parseDate('', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('not a date', 'fr-FR', 'locale')).toBeNull()
    expect(parseDate('15/03', 'fr-FR', 'locale')).toBeNull() // too few parts
    expect(parseDate(null, 'fr-FR', 'locale')).toBeNull()
  })

  // The contract that matters: parseDate is the exact inverse of formatDate, so a
  // value rendered in the input can always be read back to the same ISO string.
  it('round-trips formatDate → parseDate across formats and locales', () => {
    const iso = '2025-03-15'
    const cases: Array<{ locale: string; format: 'iso' | 'locale' }> = [
      { locale: 'fr-FR', format: 'locale' },
      { locale: 'en-US', format: 'locale' },
      { locale: 'fr-FR', format: 'iso' },
      { locale: 'en-US', format: 'iso' },
    ]
    for (const { locale, format } of cases) {
      const displayed = formatDate(iso, locale, format)
      expect(parseDate(displayed, locale, format), `${locale}/${format} → ${displayed}`).toBe(iso)
    }
  })
})

describe('isOAuthAuthorizeRedirect', () => {
  it('recognises backend OAuth2 authorize targets (full-page navigation, not SPA routing)', () => {
    expect(isOAuthAuthorizeRedirect('/oauth2/authorize')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/authorize?response_type=code&client_id=picsou-ios')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/token')).toBe(true)
    expect(isOAuthAuthorizeRedirect('/oauth2/')).toBe(true)
  })

  it('treats ordinary in-app SPA routes as client-side navigation', () => {
    expect(isOAuthAuthorizeRedirect('/')).toBe(false)
    expect(isOAuthAuthorizeRedirect('/dashboard')).toBe(false)
    expect(isOAuthAuthorizeRedirect('/login')).toBe(false)
    expect(isOAuthAuthorizeRedirect('')).toBe(false)
  })

  // The guard doubles as an open-redirect guard: only a literal same-origin `/oauth2/`
  // prefix triggers the full-page navigation, so protocol-relative and absolute-URL
  // targets — the classic open-redirect vectors — never qualify.
  it('rejects open-redirect vectors and lookalikes that are not a same-origin /oauth2/ path', () => {
    expect(isOAuthAuthorizeRedirect('//evil.com/oauth2/authorize')).toBe(false) // protocol-relative
    expect(isOAuthAuthorizeRedirect('https://evil.com/oauth2/authorize')).toBe(false) // absolute URL
    expect(isOAuthAuthorizeRedirect('/evil/oauth2/authorize')).toBe(false) // nested, wrong prefix
    expect(isOAuthAuthorizeRedirect('/oauth2')).toBe(false) // no trailing slash
    expect(isOAuthAuthorizeRedirect('/oauth2evil/authorize')).toBe(false) // prefix without the slash boundary
  })
})

describe('freshnessLevel', () => {
  const NOW = new Date('2026-08-11T12:00:00Z').getTime()
  const DAY = 24 * 60 * 60 * 1000
  const bounds = { fresh: DAY, recent: 2 * DAY, stale: 7 * DAY }
  const ago = (ms: number) => new Date(NOW - ms).toISOString()

  it('buckets an age against the bounds it is given', () => {
    expect(freshnessLevel(ago(60 * 60 * 1000), bounds, NOW)).toBe('fresh')
    expect(freshnessLevel(ago(1.5 * DAY), bounds, NOW)).toBe('recent')
    expect(freshnessLevel(ago(3 * DAY), bounds, NOW)).toBe('stale')
    expect(freshnessLevel(ago(30 * DAY), bounds, NOW)).toBe('old')
  })

  it('treats each bound as exclusive, so a level ends exactly where the next begins', () => {
    expect(freshnessLevel(ago(DAY - 1), bounds, NOW)).toBe('fresh')
    expect(freshnessLevel(ago(DAY), bounds, NOW)).toBe('recent')
  })

  /** Never synced is not "very old": it must not read as an alarm about ageing data. */
  it('reports a missing date as unknown rather than old', () => {
    expect(freshnessLevel(null, bounds, NOW)).toBe('unknown')
    expect(freshnessLevel(undefined, bounds, NOW)).toBe('unknown')
  })

  /** A date-only string is local midnight, not UTC -- the property valuation date's shape. */
  it('reads a date-only value without a timezone shift', () => {
    const today = new Date(NOW)
    const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
    expect(freshnessLevel(iso, bounds, NOW)).toBe('fresh')
  })

  /** Server clock ahead of the browser: an age below zero is as fresh as it gets, not old. */
  it('treats a future date as fresh', () => {
    expect(freshnessLevel(ago(-DAY), bounds, NOW)).toBe('fresh')
  })
})

describe('ageAt', () => {
  it('counts completed years, not calendar years', () => {
    // The projection asks this about points twenty years out, where the difference between
    // "turns 44 this year" and "is 44 on that date" is a whole year of the answer.
    expect(ageAt('1998-09-20', '2042-09-20')).toBe(44)
    expect(ageAt('1998-09-20', '2042-09-19')).toBe(43)
    expect(ageAt('1998-09-20', '2042-08-31')).toBe(43)
  })

  it('turns over on the birthday itself', () => {
    expect(ageAt('1990-06-14', '2026-06-13')).toBe(35)
    expect(ageAt('1990-06-14', '2026-06-14')).toBe(36)
  })

  it('is unaffected by the timezone', () => {
    // It computes on the string parts and never builds a Date, which is what keeps a browser
    // west of UTC from reading every date-only value as the day before -- the same class of bug
    // the toDate helper guards against for formatDate.
    vi.stubEnv('TZ', 'America/Los_Angeles')
    try {
      expect(ageAt('1998-09-20', '2042-09-20')).toBe(44)
      expect(ageAt('1998-09-20', '2042-09-19')).toBe(43)
    } finally {
      vi.unstubAllEnvs()
    }
  })

  it('returns null for a malformed date rather than NaN', () => {
    expect(ageAt('', '2042-08-31')).toBeNull()
    expect(ageAt('1998-09-20', 'not-a-date')).toBeNull()
    expect(ageAt('1998/09/20', '2042-08-31')).toBeNull()
  })

  it('reports a negative age before the birth', () => {
    // Possible if a member states a birth date after a projected point; callers decide what it
    // means rather than the helper pretending it cannot happen. It stays the same count of
    // completed years, signed: exactly four years before the birth is -4, a few months more
    // than four is -5.
    expect(ageAt('2030-01-01', '2026-01-01')).toBe(-4)
    expect(ageAt('2030-06-01', '2026-01-01')).toBe(-5)
  })
})
