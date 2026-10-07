/**
 * Solid, theme-stable colours for proportional bars, donuts and treemaps.
 *
 * Shared so the sector breakdown of one ETF and the sector breakdown of a whole portfolio use
 * the same visual language — two palettes would read as two unrelated charts of the same thing.
 *
 * CSS values rather than Tailwind classes, because Recharts paints through `fill` and the
 * treemap through `backgroundColor`; a class name is useless to both. The values are indirections
 * to `--slice-N` in `index.css`, so there is still exactly one definition per colour.
 */
export const SLICE_PALETTE = [
  'var(--slice-1)',
  'var(--slice-2)',
  'var(--slice-3)',
  'var(--slice-4)',
  'var(--slice-5)',
  'var(--slice-6)',
  'var(--slice-7)',
  'var(--slice-8)',
  'var(--slice-9)',
  'var(--slice-10)',
] as const

/** The remainder a top-N breakdown does not name. */
export const OTHERS_SLICE_COLOR = 'color-mix(in oklab, var(--color-muted-foreground) 30%, transparent)'
