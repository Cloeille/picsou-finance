import { Cell, Pie, PieChart, Tooltip } from 'recharts'
import { ChartContainer, type ChartConfig } from '@/components/ui/chart'
import { squarify } from '@/lib/treemap'
import { cn, formatPercent } from '@/lib/utils'

/** The three ways a breakdown can be drawn. */
export type BreakdownView = 'bar' | 'donut' | 'treemap'

export interface ChartItem {
  /** The backend's stable label; null on the folded remainder, which nothing sits behind. */
  key: string | null
  label: string
  percent: number
  color: string
}

const CHART_CONFIG = { value: { label: 'Part' } } satisfies ChartConfig

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
    <ChartContainer config={CHART_CONFIG} className="mx-auto aspect-auto h-[220px] w-full">
      <PieChart>
        <Pie
          data={data}
          dataKey="value"
          nameKey="label"
          cx="50%"
          cy="50%"
          innerRadius={56}
          outerRadius={88}
          paddingAngle={2}
          strokeWidth={0}
          isAnimationActive={false}
          onClick={(slice: unknown) => {
            const key = (slice as { key?: string | null })?.key ?? null
            onSelect(selected === key ? null : key)
          }}
        >
          {data.map((item) => (
            <Cell
              key={item.label}
              fill={item.color}
              opacity={selected === null || selected === item.key ? 1 : 0.35}
              className={item.key === null ? undefined : 'cursor-pointer'}
            />
          ))}
        </Pie>
        <Tooltip
          content={({ active, payload }) => {
            if (!active || !payload?.length) return null
            const entry = payload[0]?.payload as ChartItem | undefined
            if (!entry) return null
            return (
              <div className="rounded-lg border border-border/60 bg-background px-2.5 py-1.5 text-xs shadow-sm">
                <div className="flex items-center gap-2">
                  <span
                    className="size-2 shrink-0 rounded-full"
                    style={{ backgroundColor: entry.color }}
                  />
                  <span className="font-medium text-foreground">{entry.label}</span>
                  <span className="tabular-nums text-muted-foreground">
                    {formatPercent(entry.percent)}
                  </span>
                </div>
              </div>
            )
          }}
        />
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
    <div className="relative h-[220px] w-full overflow-hidden rounded-xl">
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
            outline: '1.5px solid var(--color-background)',
          }}
        >
          {w > 12 && h > 16 && (
            <span className="pointer-events-none absolute inset-x-2 top-1.5 truncate text-xs font-medium text-white/95 drop-shadow-xs">
              {item.label}
              {w > 22 && h > 26 && (
                <span className="block text-xs font-normal text-white/80 tabular-nums">
                  {formatPercent(item.percent)}
                </span>
              )}
            </span>
          )}
        </button>
      ))}
    </div>
  )
}
