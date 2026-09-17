import { useTranslation } from 'react-i18next'
import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useProjectionDateLabel } from '@/features/analysis/hooks'
import { SLICE_PALETTE } from '@/lib/chart-palette'
import type { AllocationPoint, WealthTier } from '@/types/api'

/** Bottom to top, heaviest first — the order the pyramid itself reads in. */
const TIER_ORDER: WealthTier[] = [
  'SAFETY_NET',
  'REAL_ESTATE',
  'EQUITY',
  'CRYPTO',
  'ALTERNATIVE',
]

const TIER_COLOR: Record<WealthTier, string> = {
  SAFETY_NET: SLICE_PALETTE[5],
  REAL_ESTATE: SLICE_PALETTE[1],
  EQUITY: SLICE_PALETTE[0],
  CRYPTO: SLICE_PALETTE[3],
  ALTERNATIVE: SLICE_PALETTE[8],
}

/**
 * Where the recurring plans take the mix, against the member's own targets.
 *
 * <p>The wealth curve answers "how much"; the pyramid answers "in what, today". Neither could say
 * whether the plans close the gap or widen it, because one has no notion of time and the other
 * has no notion of allocation. This is the join.
 *
 * <p>Shares rather than euros: a stacked euro chart is dominated by whichever tier is already
 * largest, which is exactly the tier a member is trying to see past.
 */
export function AllocationTrajectory({ allocation }: { allocation: AllocationPoint[] }) {
  const { t } = useTranslation()
  // Above the early return: hooks must run unconditionally.
  const dateLabel = useProjectionDateLabel()
  if (allocation.length === 0) return null

  const config = Object.fromEntries(
    TIER_ORDER.map((tier) => [
      tier,
      { label: t(`analysis.tiers.${tier}`), color: TIER_COLOR[tier] },
    ]),
  ) as ChartConfig

  const rows = allocation.map((point) => {
    const row: Record<string, string | number> = { date: point.date }
    for (const share of point.tiers) row[share.tier] = share.percent
    return row
  })

  const last = allocation[allocation.length - 1]
  const first = allocation[0]

  return (
    <>
      <ChartContainer config={config} className="h-[320px] w-full">
        <AreaChart data={rows} margin={{ left: 4, right: 8, top: 8, bottom: 0 }}>
          <CartesianGrid vertical={false} strokeDasharray="3 3" />
          <XAxis
            dataKey="date"
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            tickFormatter={(value: string) => value.slice(0, 4)}
          />
          <YAxis
            tickLine={false}
            axisLine={false}
            width={44}
            domain={[0, 100]}
            tickFormatter={(value: number) => `${value}%`}
          />
          <ChartTooltip
            content={
              <ChartTooltipContent
                indicator="line"
                labelFormatter={(value) => dateLabel(String(value))}
              />
            }
          />
          {TIER_ORDER.map((tier) => (
            <Area
              key={tier}
              type="monotone"
              dataKey={tier}
              stackId="mix"
              stroke={`var(--color-${tier})`}
              fill={`var(--color-${tier})`}
              fillOpacity={0.55}
              strokeWidth={1}
              dot={false}
            />
          ))}
        </AreaChart>
      </ChartContainer>

      {/* The numbers the chart is too coarse to read: where each tier lands, and how far that is
          from the target. A share on a stacked area is legible to within a few points at best. */}
      <table className="mt-4 w-full text-sm">
        <thead>
          <tr className="text-xs text-muted-foreground">
            <th className="pb-1 text-left font-normal">{t('analysis.projection.tier')}</th>
            <th className="pb-1 text-right font-normal">{t('analysis.projection.today')}</th>
            <th className="pb-1 text-right font-normal">
              {t('analysis.projection.atHorizon', { count: allocation.length - 1 })}
            </th>
            <th className="pb-1 text-right font-normal">{t('analysis.projection.target')}</th>
          </tr>
        </thead>
        <tbody>
          {TIER_ORDER.map((tier) => {
            const now = first.tiers.find((s) => s.tier === tier)
            const then = last.tiers.find((s) => s.tier === tier)
            if (!now || !then) return null
            return (
              <tr key={tier} className="border-t border-border">
                <td className="py-1.5">
                  <span className="flex items-center gap-2">
                    <span
                      className="size-2 shrink-0 rounded-full"
                      style={{ backgroundColor: TIER_COLOR[tier] }}
                      aria-hidden="true"
                    />
                    {t(`analysis.tiers.${tier}`)}
                  </span>
                </td>
                <td className="py-1.5 text-right tabular-nums text-muted-foreground">
                  {now.percent.toFixed(1)}%
                </td>
                <td className="py-1.5 text-right tabular-nums text-foreground">
                  {then.percent.toFixed(1)}%
                  <span className="ml-2 text-xs text-muted-foreground">
                    <CurrencyDisplay value={then.valueEur} />
                  </span>
                </td>
                <td className="py-1.5 text-right tabular-nums text-muted-foreground">
                  {/* The cushion is measured in euros against an absolute target, not as a share,
                      so it has no percentage to compare against.

                      Loose equality, and it is not a style choice: the API omits null fields
                      entirely (spring.jackson default-property-inclusion: non_null), so this
                      arrives as undefined and `=== null` was false — .toFixed then threw and took
                      the whole tab down. Demo mode sends an explicit null, which is why the
                      fixtures were happy. */}
                  {then.targetPercent == null ? '—' : `${then.targetPercent.toFixed(0)}%`}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </>
  )
}
