import { Cell, Pie, PieChart } from 'recharts'
import { ChartContainer, type ChartConfig } from '@/components/ui/chart'
import { squarify } from '@/lib/treemap'
import { cn } from '@/lib/utils'

/** The three ways a breakdown can be drawn. */
export type BreakdownView = 'bar' | 'donut' | 'treemap'

export interface ChartItem {
  /** The backend's stable label; null on the folded remainder, which nothing sits behind. */
  key: string | null
  label: string
  percent: number
  color: string
}

/**
 * Donut and treemap renderings of a breakdown.
 *
 * <p>The bar stays in `DiversificationSection` because it doubles as the legend. These two are
 * pure pictures: selection, colours and the contributor panel all live with the caller, so all
 * three views share one selection model rather than each inventing its own.
 *
 * <p>Recharts is not used for the treemap. Its `Treemap` cannot express the hover behaviour the
 * account distribution already established — an oversized hit area to stop the tooltip
 * flickering on the seams — and this repo already had a working layout to lift.
 */
const CHART_CONFIG = { value: { label: 'Share' } } satisfies ChartConfig

export function BreakdownDonut({
  items,
  selected,
  onSelect,
}: {
  items: ChartItem[]
  selected: string | null
  onSelect: (key: string | null) => void
}) {
  const data = items.map((i) => ({ ...i, value: i.percent }))

  return (
    <ChartContainer config={CHART_CONFIG} className="mx-auto h-[220px] w-full">
      <PieChart>
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          cx="50%"
          cy="50%"
          innerRadius={55}
          outerRadius={85}
          paddingAngle={2}
          strokeWidth={0}
          isAnimationActive={false}
          onClick={(slice: unknown) => {
            const key = (slice as { key?: string | null })?.key ?? null
            onSelect(key)
          }}
        >
          {data.map((item) => (
            <Cell
              key={item.label}
              fill={item.color}
              // Dim the rest rather than highlight the one: a selected slice keeps its own colour,
              // which is what ties it to the legend and the panel below.
              opacity={selected === null || selected === item.key ? 1 : 0.35}
              className={item.key === null ? undefined : 'cursor-pointer'}
            />
          ))}
        </Pie>
      </PieChart>
    </ChartContainer>
  )
}

export function BreakdownTreemap({
  items,
  selected,
  onSelect,
}: {
  items: ChartItem[]
  selected: string | null
  onSelect: (key: string | null) => void
}) {
  const rects = squarify(items, (i) => i.percent)

  return (
    <div className="relative h-[220px] w-full overflow-hidden rounded-lg">
      {rects.map(({ x, y, w, h, item }) => (
        <button
          type="button"
          key={item.label}
          disabled={item.key === null}
          onClick={() => onSelect(selected === item.key ? null : item.key)}
          aria-pressed={selected === item.key}
          className={cn(
            'absolute overflow-hidden text-left transition-opacity',
            item.key !== null && 'cursor-pointer',
          )}
          style={{
            left: `${x}%`,
            top: `${y}%`,
            width: `${w}%`,
            height: `${h}%`,
            backgroundColor: item.color,
            opacity: selected === null || selected === item.key ? 1 : 0.35,
            // Hairline gaps rather than borders, so the rectangles keep their exact proportions.
            outline: '1px solid var(--color-background)',
          }}
        >
          {/* Below this a label is unreadable and only adds noise — the same threshold the
              account distribution treemap settled on. */}
          {w > 12 && h > 14 && (
            <span className="pointer-events-none absolute left-1.5 top-1 text-[10px] font-medium text-white/90 drop-shadow">
              {item.label}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
