import { getNiceTickValues } from 'recharts'

/** Upper bound of one tick glyph's advance at the charts' `text-xs` (12px Geist). */
const TICK_CHAR_PX = 7.5

/**
 * Width of a Y axis that fits its longest tick label.
 *
 * Recharts picks "nice" ticks that need not match any plotted value: PnL spanning -1 to 10 000
 * gets -3 500 and 10 500 ticks. The ticks are therefore generated the way a default `<YAxis>`
 * does (domain `[0, 'auto']` widened to the data, 5 ticks, adaptive steps) and each is measured.
 *
 * `format` must be the formatter handed to the axis (`money.tick`), so a masked axis is
 * sized for the mask.
 *
 * `tickSize` is reserved even under `tickLine={false}`: Recharts still offsets the tick text by
 * it (6px by default), so leaving it out clips the label's first glyph.
 */
export function yAxisWidth(
  values: Iterable<number>,
  format: (value: number) => string,
  tickMargin = 8,
  tickSize = 6,
): number {
  let min = Infinity
  let max = -Infinity
  for (const v of values) {
    if (!Number.isFinite(v)) continue
    if (v < min) min = v
    if (v > max) max = v
  }
  const domain: [number, number] = min > max ? [0, 0] : max < 0 ? [min, max] : [Math.min(0, min), max]
  const ticks = getNiceTickValues(domain, 5, true, 'adaptive')
  const longest = Math.max(...ticks.map((v) => format(v).length))
  return Math.ceil(longest * TICK_CHAR_PX) + tickMargin + tickSize
}
