/** Upper bound of one tick glyph's advance at the charts' `text-xs` (12px Geist). */
const TICK_CHAR_PX = 7.5

/**
 * Width of a Y axis that fits its longest tick label.
 *
 * Recharts picks "nice" ticks, so the outer tick can sit past the data: a series peaking at
 * 9 600 gets a 10 000 tick, one digit longer than any value plotted. Each extreme is therefore
 * measured at the next power of ten too, which bounds every nice tick from outside.
 *
 * `format` must be the formatter handed to the axis (`money.tick(...)`), so a masked axis is
 * sized for the mask. Recharts also offsets the tick text by `tickSize` (6px by default) even with
 * `tickLine={false}`, so that offset is part of the width.
 */
export function yAxisWidth(values: Iterable<number>, format: (value: number) => string, tickMargin = 8, tickSize = 6): number {
  let min = 0
  let max = 0
  for (const v of values) {
    if (!Number.isFinite(v)) continue
    if (v < min) min = v
    if (v > max) max = v
  }
  const candidates = [min, max, 0]
  for (const v of [min, max]) {
    if (v !== 0) candidates.push(Math.sign(v) * 10 ** Math.ceil(Math.log10(Math.abs(v))))
  }
  const longest = Math.max(...candidates.map((v) => format(v).length))
  return Math.ceil(longest * TICK_CHAR_PX) + tickMargin + tickSize
}
