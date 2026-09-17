import { useMemo, useRef, useState } from 'react'
import { Cell, Pie, PieChart, Label } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { useTranslation } from 'react-i18next'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { accountTypeLabelKey } from '@/lib/constants'
import {
  disambiguateDistributionNames,
  type DistributionItem,
} from './distribution-labels'
import { squarify, type TreemapRect } from '@/lib/treemap'

/** The layout is shared with the diversification treemap; only the item shape differs. */
type Rect = TreemapRect<DistributionItem>

const chartConfig = {
  balanceEur: {
    label: 'Balance',
  },
} satisfies ChartConfig

interface DistributionPieProps {
  data: DistributionItem[]
}

function TooltipAnchor({ rect }: { rect: Rect }) {
  const ref = useRef<HTMLDivElement>(null)

  const cx = rect.x + rect.w / 2
  const cy = rect.y + rect.h / 2

  // Determine anchor strategy: stick to block but flip if near edge
  const anchorLeft = cx > 65 ? rect.x : cx < 35 ? rect.x + rect.w : cx
  const anchorTop = cy > 75 ? rect.y : cy < 25 ? rect.y + rect.h : cy
  const translateX = cx > 65 ? '-100%' : cx < 35 ? '0' : '-50%'
  const translateY = cy > 75 ? '-100%' : cy < 25 ? '0' : '-50%'

  const style = useMemo<React.CSSProperties>(() => ({
    left: `${anchorLeft}%`,
    top: `${anchorTop}%`,
    transform: `translate(${translateX}, ${translateY})`,
    whiteSpace: 'nowrap',
  }), [anchorLeft, anchorTop, translateX, translateY])

  return (
    <div
      ref={ref}
      className="absolute z-30 flex flex-col items-center pointer-events-none"
      style={style}
    >
      <span className="text-white font-semibold text-sm bg-black/60 backdrop-blur-sm px-2 py-0.5 rounded">
        {rect.item.name}
      </span>
      <span className="text-white/80 text-xs bg-black/60 backdrop-blur-sm px-2 py-0.5 rounded mt-0.5">
        {rect.item.percentage}%
      </span>
    </div>
  )
}

function AllocationTreemap({ data }: { data: DistributionItem[] }) {
  const rects = useMemo(() => squarify(data, (i) => i.balanceEur), [data])
  const [hoveredId, setHoveredId] = useState<number | null>(null)

  const hoveredRect = hoveredId !== null ? rects.find(r => r.item.accountId === hoveredId) : null

  return (
    <div className="relative h-full min-h-0 w-full overflow-hidden"
      onMouseLeave={() => setHoveredId(null)}
    >
      {rects.map(({ x, y, w, h, item }) => {
        const isSmall = w < 12 || h < 18
        const isHovered = hoveredId === item.accountId
        return (
          <div
            key={item.accountId}
            className={`absolute flex flex-col items-center justify-center rounded-md cursor-default ${isHovered ? 'z-10' : ''}`}
            style={{
              left: `calc(${x}% + 2px)`,
              top: `calc(${y}% + 2px)`,
              width: `calc(${w}% - 4px)`,
              height: `calc(${h}% - 4px)`,
              backgroundColor: item.color,
            }}
            onMouseEnter={() => setHoveredId(item.accountId)}
          >
            {/* Invisible larger hitbox to prevent flickering in gaps */}
            <div className="absolute -inset-1 z-20" onMouseEnter={() => setHoveredId(item.accountId)} />
            <div className={`pointer-events-none absolute inset-0 rounded-md transition-[box-shadow] duration-150 ${isHovered ? 'ring-2 ring-white/40' : ''}`} />
            {!isSmall && (
              <div className="relative z-10 flex flex-col items-center pointer-events-none">
                <span className="truncate text-white font-semibold text-sm px-1 text-center leading-tight">
                  {item.name}
                </span>
                <span className="text-white/80 text-xs mt-0.5">
                  {item.percentage}%
                </span>
              </div>
            )}
          </div>
        )
      })}

      {/* Tooltip anchored near the block, clamped to stay inside container */}
      {hoveredRect && (hoveredRect.w < 12 || hoveredRect.h < 18) && (
        <TooltipAnchor rect={hoveredRect} />
      )}
    </div>
  )
}

export function DistributionPie({ data }: DistributionPieProps) {
  const { t } = useTranslation()
  const displayData = useMemo(
    () => disambiguateDistributionNames(data, type => t(accountTypeLabelKey(type))),
    [data, t],
  )

  return (
    <Card className="h-[420px]">
      <Tabs defaultValue="distribution" className="h-full w-full gap-0">
        <CardHeader className="shrink-0">
          <TabsList>
            <TabsTrigger value="distribution">{t('dashboard.distribution')}</TabsTrigger>
            <TabsTrigger value="allocation">{t('dashboard.allocation')}</TabsTrigger>
          </TabsList>
        </CardHeader>
        <CardContent className="min-h-0 flex-1 pt-3">
          <TabsContent value="distribution" className="h-full">
            <div className="flex h-full min-h-0 flex-col">
              <ChartContainer config={chartConfig} className="mx-auto h-[250px] w-full shrink-0">
                <PieChart>
                  <ChartTooltip content={<MoneyChartTooltip config={chartConfig} hideLabel />} />
                  <Pie
                    data={displayData}
                    dataKey="balanceEur"
                    nameKey="name"
                    cx="50%"
                    cy="50%"
                    innerRadius={60}
                    outerRadius={90}
                    paddingAngle={2}
                    strokeWidth={0}
                  >
                    {displayData.map((entry) => (
                      <Cell key={entry.accountId} fill={entry.color} />
                    ))}
                    <Label
                      content={({ viewBox }) => {
                        if (viewBox && 'cx' in viewBox && 'cy' in viewBox) {
                          return (
                            <text
                              x={viewBox.cx}
                              y={viewBox.cy}
                              textAnchor="middle"
                              dominantBaseline="middle"
                            >
                              <tspan
                                x={viewBox.cx}
                                y={viewBox.cy}
                                className="fill-foreground text-2xl font-bold"
                              >
                                {displayData.length}
                              </tspan>
                              <tspan
                                x={viewBox.cx}
                                y={(viewBox.cy || 0) + 20}
                                className="fill-muted-foreground text-xs"
                              >
                                {t('dashboard.accountsLabel')}
                              </tspan>
                            </text>
                          )
                        }
                      }}
                    />
                  </Pie>
                </PieChart>
              </ChartContainer>
              <div className="mt-2 grid min-h-0 grid-cols-2 gap-x-4 gap-y-1 overflow-y-auto">
                {displayData.map(item => (
                  <div key={item.accountId} className="flex items-center gap-2 text-sm">
                    <div className="size-2.5 rounded-full" style={{ backgroundColor: item.color }} />
                    <span className="truncate">{item.name}</span>
                    <span className="ml-auto text-muted-foreground">{item.percentage}%</span>
                  </div>
                ))}
              </div>
            </div>
          </TabsContent>

          <TabsContent value="allocation" className="h-full">
            <AllocationTreemap data={displayData} />
          </TabsContent>
        </CardContent>
      </Tabs>
    </Card>
  )
}
