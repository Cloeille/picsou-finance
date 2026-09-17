import { describe, it, expect } from 'vitest'
import { AMOUNT_MASK, AXIS_MASK, formatCurrencyUnmasked, makeMoneyFormatter } from './money'

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
  it('replaces an axis ladder wholesale so no scale can be read off the grid', () => {
    const ladder = (v: number) => `${(v / 1000).toFixed(0)}k`
    expect(visible.tick(ladder)(350_000)).toBe('350k')
    expect(masked.tick(ladder)(350_000)).toBe(AXIS_MASK)
  })
})

describe('formatCurrencyUnmasked', () => {
  it('ignores privacy mode — it is the audited escape hatch, not a default', () => {
    expect(formatCurrencyUnmasked(1234.5, 'EUR', 'fr-FR')).toContain('234')
  })
})
