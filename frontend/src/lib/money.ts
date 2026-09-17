import { getLocale, normalizeIntlLocale } from '@/lib/utils'

/**
 * A fixed-length token, never derived from the value it replaces.
 *
 * This is the whole point. Masking digit by digit — `1 234 567,89 €` → `*********,** €` against
 * `12,00 €` → `**,** €` — leaks the order of magnitude in the width of the mask, which is exactly
 * what the mode exists to hide. Same reason a CSS blur is not enough: it cannot change how wide
 * the element is, and leaves the real digits in the DOM and in a copy-paste.
 */
export const AMOUNT_MASK = '*****'

/** Shorter, because every masked axis has to fit a gutter as narrow as `width={45}`. */
export const AXIS_MASK = '***'

export interface MoneyFormatter {
  /** True when amounts are hidden — for the few places that need to branch on more than a string. */
  readonly hidden: boolean
  /** A monetary amount: masked, or formatted for the current locale. */
  amount(value: number, currency?: string): string
  /**
   * A holding's quantity. Masked with the amounts: a quote is public, so quantity times price
   * reconstitutes the line, and a position anyone can price is not hidden by hiding its total.
   */
  quantity(value: number | string): string
  /**
   * A compact amount, for somewhere too narrow for the full format — an axis tick, the text
   * inside a donut. Masked like any other amount.
   *
   * @param maximumFractionDigits fixed precision; omitted, the precision follows the magnitude
   *                              (2 above a million, 0 above ten thousand, 1 above a thousand)
   *                              and anything smaller is rendered in full
   */
  compact(value: number, opts?: { currency?: string; maximumFractionDigits?: number }): string
  /**
   * A published market quote. Never masked: it discloses nothing about how much is held, and the
   * quantity beside it is masked, which is what keeps the position unreconstructible.
   */
  quote(value: number, currency?: string): string
  /** An axis tick. Masked to {@link AXIS_MASK}, otherwise handed to the chart's own ladder. */
  tick<T>(format: (value: T) => string): (value: T) => string
}

/**
 * Formats a monetary amount for display. Private on purpose.
 *
 * The public path is {@link MoneyFormatter} via `useMoney()`, so that hiding amounts cannot be
 * bypassed by reaching for the raw formatter. The one audited exception is exported below.
 */
function formatCurrencyIntl(value: number, currency = 'EUR', locale = getLocale()): string {
  const safeLocale = normalizeIntlLocale(locale)
  try {
    return new Intl.NumberFormat(safeLocale, { style: 'currency', currency }).format(value)
  } catch {
    // An unknown/invalid ISO 4217 code makes Intl.NumberFormat throw a RangeError.
    // Degrade to a plain decimal + the raw code instead of crashing the whole app (issue #9).
    const num = new Intl.NumberFormat(safeLocale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)
    return `${num} ${currency}`
  }
}

/**
 * The raw formatter, ignoring privacy mode.
 *
 * Reserved for a figure the member is typing in front of them, where masking protects nothing —
 * both operands are on screen — and would only make the form unreadable. Every other caller must
 * go through `useMoney()`; `money-leak.guard.test.ts` enforces the allowlist.
 */
export const formatCurrencyUnmasked = formatCurrencyIntl

/**
 * Replaces the digits of a formatted amount, keeping everything the locale put around them.
 *
 * The symbol and its position survive (`***** €` in French, `€*****` in English) so a masked
 * figure reads as "an amount, hidden" rather than as a rendering fault, and so the currency of a
 * non-EUR account is still legible. The sign survives too: it states a direction, not a
 * magnitude, and the percentages beside it — which stay visible — already give that away.
 */
function maskAmount(value: number, currency: string, locale: string): string {
  const safeLocale = normalizeIntlLocale(locale)
  let parts: Intl.NumberFormatPart[]
  try {
    parts = new Intl.NumberFormat(safeLocale, { style: 'currency', currency }).formatToParts(value)
  } catch {
    return `${AMOUNT_MASK} ${currency}`
  }

  let masked = ''
  let digitsSeen = false
  for (const part of parts) {
    switch (part.type) {
      case 'integer':
      case 'fraction':
      case 'group':
      case 'decimal':
        // The whole numeric run collapses to one token, however many parts it came in.
        if (!digitsSeen) masked += AMOUNT_MASK
        digitsSeen = true
        break
      case 'minusSign':
      case 'plusSign':
      case 'currency':
      case 'literal':
        masked += part.value
        break
      default:
        break
    }
  }
  return masked
}

export function makeMoneyFormatter({ hidden, locale }: { hidden: boolean; locale: string }): MoneyFormatter {
  return {
    hidden,
    amount(value: number, currency = 'EUR') {
      return hidden
        ? maskAmount(value, currency, locale)
        : formatCurrencyIntl(value, currency, locale)
    },
    compact(value: number, opts: { currency?: string; maximumFractionDigits?: number } = {}) {
      const currency = opts.currency ?? 'EUR'
      if (hidden) return maskAmount(value, currency, locale)
      const abs = Math.abs(value)
      const digits = opts.maximumFractionDigits
        ?? (abs >= 1_000_000 ? 2 : abs >= 10_000 ? 0 : abs >= 1_000 ? 1 : null)
      if (digits == null) return formatCurrencyIntl(value, currency, locale)
      try {
        return new Intl.NumberFormat(normalizeIntlLocale(locale), {
          style: 'currency', currency, notation: 'compact', maximumFractionDigits: digits,
        }).format(value)
      } catch {
        // An invalid currency or locale throws a RangeError; the full format degrades gracefully.
        return formatCurrencyIntl(value, currency, locale)
      }
    },
    quote(value: number, currency = 'EUR') {
      return formatCurrencyIntl(value, currency, locale)
    },
    quantity(value: number | string) {
      return hidden ? AMOUNT_MASK : String(value)
    },
    tick<T>(format: (value: T) => string) {
      return hidden ? () => AXIS_MASK : format
    },
  }
}
