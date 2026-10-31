import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Cell, Label, Pie, PieChart } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  type ChartConfig,
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
} from '@/components/ui/chart'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { EmptyState } from '@/components/shared/EmptyState'
import { ErrorState } from '@/components/shared/ErrorState'
import { Skeleton } from '@/components/ui/skeleton'
import { PiggyBank } from 'lucide-react'
import { useAllocation } from '@/features/budget/hooks'
import type { CashflowPeriod } from '@/types/api'
import { ColorDot, PeriodToggle } from './budget-utils'
import { PeriodNavigator } from './PeriodNavigator'
import { ASSET_CLASS_COLOR, ASSET_CLASS_LABEL_KEY } from './budget-meta'
import { useBudgetPeriod } from './BudgetPeriodContext'

const chartConfig = { amount: { label: 'Amount' } } satisfies ChartConfig

export function AllocationTab() {
  const { t } = useTranslation()
  const [period, setPeriod] = useState<CashflowPeriod>('CYCLE')
  const { anchor, setAnchor } = useBudgetPeriod()
  const { data, isLoading, isError, refetch } = useAllocation(period, anchor)

  const stockData = (data?.stock ?? []).map((s) => ({
    ...s,
    name: t(ASSET_CLASS_LABEL_KEY[s.assetClass]),
    color: ASSET_CLASS_COLOR[s.assetClass],
  }))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t('budget.allocation.subtitle')}</p>
        <div className="flex flex-wrap items-center gap-2">
          <PeriodNavigator
            period={period}
            from={data?.from}
            to={data?.to}
            onAnchorChange={setAnchor}
          />
          <PeriodToggle value={period} onChange={(p) => { setPeriod(p); setAnchor(undefined) }} />
        </div>
      </div>

      {isError && <ErrorState message={t('budget.allocation.error')} onRetry={() => refetch()} />}

      {isLoading && (
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-72 w-full rounded-xl" />
          <Skeleton className="h-72 w-full rounded-xl" />
        </div>
      )}

      {data && (
        <div className="grid gap-4 md:grid-cols-2">
          {/* Stock donut */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('budget.allocation.stock')}</CardTitle>
            </CardHeader>
            <CardContent>
              {stockData.length === 0 ? (
                <EmptyState icon={<PiggyBank className="size-10" />}
                  title={t('budget.allocation.noStock')} />
              ) : (
                <>
                  <ChartContainer config={chartConfig} className="mx-auto h-[220px] w-full">
                    <PieChart>
                      <ChartTooltip content={<ChartTooltipContent hideLabel />} />
                      <Pie data={stockData} dataKey="amount" nameKey="name"
                        cx="50%" cy="50%" innerRadius={55} outerRadius={85} paddingAngle={2}
                        strokeWidth={0}>
                        {stockData.map((entry) => (
                          <Cell key={entry.assetClass} fill={entry.color} />
                        ))}
                        <Label content={({ viewBox }) => {
                          if (viewBox && 'cx' in viewBox && 'cy' in viewBox) {
                            return (
                              <text x={viewBox.cx} y={viewBox.cy} textAnchor="middle"
                                dominantBaseline="middle">
                                <tspan x={viewBox.cx} y={(viewBox.cy ?? 0) - 4}
                                  className="fill-foreground text-lg font-bold">
                                  {new Intl.NumberFormat(t('common.locale'), {
                                    notation: 'compact', maximumFractionDigits: 1,
                                  }).format(data.totalStock)}
                                </tspan>
                                <tspan x={viewBox.cx} y={(viewBox.cy ?? 0) + 16}
                                  className="fill-muted-foreground text-xs">
                                  {t('common.currency')}
                                </tspan>
                              </text>
                            )
                          }
                        }} />
                      </Pie>
                    </PieChart>
                  </ChartContainer>
                  <div className="mt-2 space-y-1">
                    {stockData.map((s) => (
                      <div key={s.assetClass} className="flex items-center gap-2 text-sm">
                        <ColorDot color={s.color} />
                        <span className="truncate">{s.name}</span>
                        <span className="ml-auto tabular-nums text-muted-foreground">
                          {s.percent}%
                        </span>
                        <span className="w-24 text-right tabular-nums">
                          <CurrencyDisplay value={s.amount} />
                        </span>
                      </div>
                    ))}
                  </div>
                </>
              )}
            </CardContent>
          </Card>

          {/* Contributions (flux) */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('budget.allocation.contributions')}</CardTitle>
            </CardHeader>
            <CardContent>
              {data.contributions.length === 0 ? (
                <EmptyState icon={<PiggyBank className="size-10" />}
                  title={t('budget.allocation.noContributions')}
                  description={t('budget.allocation.noContributionsHint')} />
              ) : (
                <div className="space-y-3">
                  <p className="text-sm text-muted-foreground">
                    {t('budget.allocation.totalContributed')}{' '}
                    <span className="font-semibold text-foreground">
                      <CurrencyDisplay value={data.totalContributions} />
                    </span>
                  </p>
                  {data.contributions.map((c) => {
                    const pct = data.totalContributions > 0
                      ? (c.amount / data.totalContributions) * 100 : 0
                    return (
                      <div key={c.accountId} className="space-y-1">
                        <div className="flex items-center gap-2 text-sm">
                          <ColorDot color={c.color} />
                          <span className="truncate">{c.accountName}</span>
                          <span className="ml-auto tabular-nums">
                            <CurrencyDisplay value={c.amount} />
                          </span>
                        </div>
                        <div className="h-1.5 w-full overflow-hidden rounded-md bg-muted">
                          <div className="h-full rounded-md"
                            style={{ width: `${pct}%`, backgroundColor: c.color || ASSET_CLASS_COLOR[c.assetClass] }} />
                        </div>
                      </div>
                    )
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  )
}
