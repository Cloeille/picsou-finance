import { describe, it, expect } from 'vitest'
import { AMOUNT_MASK, AXIS_MASK, MONEY_AXIS_WIDTH, formatCurrencyUnmasked, makeMoneyFormatter } from './money'

const visible = makeMoneyFormatter({ hidden: false, locale: 'fr-FR' })
const masked = makeMoneyFormatter({ hidden: true, locale: 'fr-FR' })

describe('the mask', () => {
  it('is the same width whatever the amount', () => {
    // The load-bearing assertion. Masking digit by digit would give the seven-figure amount a
    // visibly wider mask than the two-figure one, and the width of the mask is the order of
    // magnitude — which is the single thing privacy mode exists to hide.
    expect(masked.amount(12)).toBe(masked.amount(12_345_678))
    expect(masked.amount(-4)).toBe(masked.amount(-987_654.32))
  })

  it('hides zero, which is a magnitude like any other', () => {
    expect(masked.amount(0)).not.toContain('0')
  })

  it('keeps the currency and where the locale puts it', () => {
    // A masked figure has to read as "an amount, hidden" rather than as a rendering fault, and a
    // non-EUR account has to stay identifiable.
    expect(masked.amount(1234, 'EUR')).toContain('€')
    expect(masked.amount(1234, 'EUR')).toContain(AMOUNT_MASK)
    expect(masked.amount(1234, 'USD')).toMatch(/\$|USD/)
  })

  it('keeps the sign, which states a direction rather than a magnitude', () => {
    expect(masked.amount(-500)).toContain('-')
    expect(masked.amount(500)).not.toContain('-')
  })

  it('leaves no digit behind', () => {
    for (const value of [0, 1, 999, 1000.5, 123456.78, -42, 9_876_543.21]) {
      expect(masked.amount(value)).not.toMatch(/\d/)
    }
  })

  it('masks quantities, because quantity times a public quote is the position', () => {
    expect(masked.quantity(137)).toBe(AMOUNT_MASK)
    expect(visible.quantity(137)).toBe('137')
  })

  it('does not mask a published quote', () => {
    expect(masked.quote(452.3)).toBe(visible.quote(452.3))
  })
})

describe('unmasked formatting', () => {
  it('formats EUR in French locale', () => {
    const result = visible.amount(1234.5, 'EUR')
    expect(result).toContain('1')
    expect(result).toContain('234')
  })

  it('groups French digits with a space the bundled font can draw', () => {
    // Geist has no U+202F glyph, so ICU's narrow no-break space fell back to a system sliver and
    // "51 646,95 €" read as "51646,95 €" (#274).
    expect(visible.amount(51_646.95)).toBe('51\u00a0646,95\u00a0€')
    expect(visible.tick(60_000)).toBe('60\u00a0k\u00a0€')
  })

  it('formats zero', () => {
    expect(visible.amount(0, 'EUR')).toContain('0')
  })

  it('formats negative values', () => {
    expect(visible.amount(-500, 'EUR')).toContain('500')
  })

  it('degrades gracefully on an invalid currency code instead of throwing (issue #9)', () => {
    expect(() => visible.amount(100, 'AMAT')).not.toThrow()
    const result = visible.amount(100, 'AMAT')
    expect(result).toContain('AMAT')
    expect(result).toContain('100')
  })

  it('degrades gracefully when both currency and locale are invalid', () => {
    const odd = makeMoneyFormatter({ hidden: false, locale: 'common.locale' })
    expect(() => odd.amount(100, 'AMAT')).not.toThrow()
    const result = odd.amount(100, 'AMAT')
    expect(result).toContain('AMAT')
    expect(result).toContain('100')
  })

  it('rounds to whole units when asked, without switching to a compact unit', () => {
    expect(visible.amount(1373.33, 'EUR', { maximumFractionDigits: 0 })).toMatch(/^1\s373\s€$/)
    expect(visible.amount(984.72, 'EUR', { maximumFractionDigits: 0 })).toMatch(/^985\s€$/)
    expect(masked.amount(1373.33, 'EUR', { maximumFractionDigits: 0 })).toBe(masked.amount(1373.33))
  })

  it('keeps the requested precision when the currency code is invalid', () => {
    expect(visible.amount(1373.33, 'AMAT', { maximumFractionDigits: 0 })).toMatch(/^1\s373 AMAT$/)
    expect(visible.amount(1373.33, 'AMAT')).toMatch(/^1\s373,33 AMAT$/)
  })

  it('still masks when the currency is invalid, rather than falling back to the digits', () => {
    // The RangeError path is the one a leak would hide in: an odd currency code must not be a
    // way back to a legible amount.
    expect(masked.amount(100, 'AMAT')).not.toMatch(/\d/)
  })
})

describe('compact', () => {
  it('shortens by magnitude when no precision is imposed', () => {
    expect(visible.compact(1_500_000)).toMatch(/M/)
    expect(visible.compact(35_000)).toMatch(/k|K/)
    // Below a thousand there is nothing to compact, so it falls back to the full format.
    expect(visible.compact(250)).toContain('250')
  })

  it('is masked like any other amount', () => {
    expect(masked.compact(1_500_000)).not.toMatch(/\d/)
    expect(masked.compact(250)).not.toMatch(/\d/)
  })
})

describe('tick', () => {
  // One formatter for every amount axis, so the locale (not a hand-rolled `toFixed` ladder) decides
  // the separator, the suffix and the symbol. Spaces are flattened for legibility; which no-break
  // space is emitted is pinned in 'unmasked formatting'.
  const tick = (locale: string, value: number) =>
    makeMoneyFormatter({ hidden: false, locale }).tick(value).replace(/[\u202f\u00a0]/g, ' ')

  it.each([
    ['fr-FR', ['60 k €', '2,5 k €', '-250 k €', '1,25 M €']],
    ['en-US', ['€60K', '€2.5K', '-€250K', '€1.25M']],
    ['de-DE', ['60.000 €', '2500 €', '-250.000 €', '1,25 Mio. €']],
    ['es-ES', ['60 mil €', '2,5 mil €', '-250 mil €', '1,25 M €']],
  ])('formats a compact amount in %s', (locale, expected) => {
    expect([60_000, 2_500, -250_000, 1_250_000].map((v) => tick(locale, v))).toEqual(expected)
  })

  it('never pads a round tick with a trailing zero', () => {
    expect(tick('fr-FR', 0)).toBe('0 €')
    expect(tick('fr-FR', 45_000)).toBe('45 k €')
  })

  it('is masked wholesale so no scale can be read off the grid', () => {
    expect(masked.tick(350_000)).toBe(AXIS_MASK)
  })

  it('fits the widest label, German "-250.000 €", inside the axis gutter', () => {
    // jsdom cannot measure text, so the label widths are the ones measured in Chromium with Geist
    // Variable at 12px (#286 review): "-250.000 €" is 66px, "-1,25 Mio. €" is 65px. Recharts anchors
    // the label at width − tickMargin − tickSize, and keeps the 6px tick size even with
    // tickLine={false}: 76px put the minus sign at x = −4.
    const GERMAN_LABEL_PX = 66, GERMAN_MILLION_LABEL_PX = 65, TICK_MARGIN = 8, TICK_SIZE = 6
    expect(tick('de-DE', -250_000)).toBe('-250.000 €')
    expect(tick('de-DE', -1_250_000)).toBe('-1,25 Mio. €')
    expect(MONEY_AXIS_WIDTH - TICK_MARGIN - TICK_SIZE - GERMAN_LABEL_PX).toBeGreaterThanOrEqual(4)
    expect(MONEY_AXIS_WIDTH - TICK_MARGIN - TICK_SIZE - GERMAN_MILLION_LABEL_PX).toBeGreaterThanOrEqual(4)
  })
})

describe('formatCurrencyUnmasked', () => {
  it('ignores privacy mode — it is the audited escape hatch, not a default', () => {
    expect(formatCurrencyUnmasked(1234.5, 'EUR', 'fr-FR')).toContain('234')
  })
})
