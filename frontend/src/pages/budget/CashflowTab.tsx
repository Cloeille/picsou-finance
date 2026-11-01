import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Bar, BarChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  type ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
} from '@/components/ui/chart'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { ErrorState } from '@/components/shared/ErrorState'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { Skeleton } from '@/components/ui/skeleton'
import { useCashflow } from '@/features/budget/hooks'
import { useMoney } from '@/hooks/use-money'
import { MONEY_AXIS_WIDTH } from '@/lib/money'
import type { CashflowPeriod } from '@/types/api'
import { PeriodToggle } from './budget-utils'
import { PeriodNavigator } from './PeriodNavigator'
import { useBudgetPeriod } from './BudgetPeriodContext'
import { AXIS_PROPS } from '@/lib/chart-axis'

function StatCard({ labelKey, value, tone }: { labelKey: string; value: number; tone?: string }) {
  const { t } = useTranslation()
  return (
    <Card>
      <CardContent className="pt-6">
        <p className="text-sm text-muted-foreground">{t(labelKey)}</p>
        <p className={`mt-1 text-2xl font-bold ${tone ?? 'text-foreground'}`}>
          <CurrencyDisplay value={value} />
        </p>
      </CardContent>
    </Card>
  )
}

export function CashflowTab() {
  const { t } = useTranslation()
  const money = useMoney()
  const [period, setPeriod] = useState<CashflowPeriod>('CYCLE')
  const { anchor, setAnchor } = useBudgetPeriod()
  const { data, isLoading, isError, refetch } = useCashflow(period, anchor)
  const chartConfig = {
    income: { label: t('budget.cashflow.income'), color: 'var(--chart-2)' },
    expense: { label: t('budget.cashflow.expense'), color: 'var(--chart-5)' },
  } satisfies ChartConfig

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">{t('budget.cashflow.subtitle')}</p>
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

      {isError && <ErrorState message={t('budget.cashflow.error')} onRetry={() => refetch()} />}

      {isLoading && (
        <div className="grid gap-4 sm:grid-cols-3">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      )}

      {data && (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <StatCard labelKey="budget.cashflow.income" value={data.income}
              tone="text-emerald-600 dark:text-emerald-400" />
            <StatCard labelKey="budget.cashflow.expense" value={-data.expense}
              tone="text-foreground" />
            <StatCard labelKey="budget.cashflow.net" value={data.net}
              tone={data.net >= 0 ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'} />
          </div>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">{t('budget.cashflow.byCycle')}</CardTitle>
            </CardHeader>
            <CardContent>
              {data.series.length === 0 ? (
                <p className="py-8 text-center text-sm text-muted-foreground">
                  {t('budget.cashflow.empty')}
                </p>
              ) : (
                <ChartContainer config={chartConfig} className="h-[260px] w-full">
                  <BarChart data={data.series} accessibilityLayer>
                    <CartesianGrid vertical={false} />
                    <XAxis dataKey="label" {...AXIS_PROPS}
                      fontSize={12} />
                    <YAxis {...AXIS_PROPS} width={MONEY_AXIS_WIDTH} fontSize={12}
                      tickFormatter={money.tick} />
                    <ChartTooltip content={<MoneyChartTooltip config={chartConfig} />} />
                    <ChartLegend content={<ChartLegendContent />} />
                    <Bar dataKey="income" fill="var(--color-income)" radius={4} />
                    <Bar dataKey="expense" fill="var(--color-expense)" radius={4} />
                  </BarChart>
                </ChartContainer>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}
