import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Line, LineChart, CartesianGrid, XAxis, YAxis } from 'recharts'
import { type ChartConfig, ChartContainer, ChartTooltip } from '@/components/ui/chart'
import { TimeRangeSelector, type TimeRange } from '@/components/shared/TimeRangeSelector'
import { localeFromLanguage } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { Account } from '@/types/api'

interface AccountsStackedChartProps {
  accounts: Account[]
  data: { date: string; [accountId: string]: string | number }[]
}

function PnlTooltip({ active, payload, accounts, labels }: {
  active?: boolean
  payload?: Array<{
    value: number
    dataKey: string
    payload: { date: string }
  }>
  accounts: Account[]
  labels: { locale: string; currency: string }
}) {
  const money = useMoney()
  if (!active || !payload?.length) return null

  const dateStr = payload[0]?.payload?.date
  if (!dateStr) return null

  const accountMap = new Map(accounts.map(a => [String(a.id), a]))

  // Build the visible rows and their sum up-front — mutating a `total`
  // binding while mapping inside JSX would be a reassign-after-render.
  const rows = payload
    .filter(p => p.dataKey !== 'date')
    .map(item => ({ item, account: accountMap.get(String(item.dataKey)) }))
    .filter((r): r is { item: typeof r.item; account: Account } => r.account != null)
  const total = rows.reduce((sum, { item }) => sum + item.value, 0)

  return (
    <div className="rounded-xl bg-popover px-3 py-2.5 text-xs text-popover-foreground shadow-lg ring-1 ring-foreground/5 dark:ring-foreground/10">
      <div className="mb-1.5 font-medium">
        {new Date(dateStr).toLocaleDateString(labels.locale, { day: 'numeric', month: 'short', year: 'numeric' })}
      </div>
      <div className="space-y-0.5">
        {rows.map(({ item, account }) => (
          <div key={item.dataKey} className="flex items-center gap-2 py-0.5">
            <div
              className="h-0.5 w-4 shrink-0 rounded-full"
              style={{ backgroundColor: account.color }}
            />
            <span className="text-muted-foreground truncate">{account.name}</span>
            <span className={`ml-auto font-mono font-medium tabular-nums ${item.value >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
              {item.value >= 0 ? '+' : ''}{money.amount(item.value, labels.currency)}
            </span>
          </div>
        ))}
      </div>
      <div className="my-1.5 border-t border-border" />
      <div className="flex items-center justify-between py-0.5">
        <span className={`font-mono font-medium tabular-nums ${total >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
          {total >= 0 ? '+' : ''}{money.amount(total, labels.currency)}
        </span>
      </div>
    </div>
  )
}

function filterByRange(data: AccountsStackedChartProps['data'], range: TimeRange) {
  if (range === 'ALL') return data
  const now = new Date()
  let from: Date
  switch (range) {
    case '24H': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1); break
    case '7D': from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 7); break
    case '1M': from = new Date(now.getFullYear(), now.getMonth() - 1, now.getDate()); break
    case '3M': from = new Date(now.getFullYear(), now.getMonth() - 3, now.getDate()); break
    case 'YTD': from = new Date(now.getFullYear(), 0, 1); break
    case '1Y': from = new Date(now.getFullYear() - 1, now.getMonth(), now.getDate()); break
  }
  return data.filter(p => new Date(p.date) >= from)
}

export function AccountsStackedChart({ accounts, data }: AccountsStackedChartProps) {
  const money = useMoney()
  const { i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const [range, setRange] = useState<TimeRange>('1Y')

  const filteredData = useMemo(() => filterByRange(data, range), [data, range])

  const chartConfig = useMemo(() => {
    const config: ChartConfig = {}
    for (const account of accounts) {
      config[account.id] = {
        label: account.name,
        color: account.color,
      }
    }
    return config
  }, [accounts])

  const labels = useMemo(() => ({
    locale,
    currency: 'EUR',
  }), [locale])

  if (data.length === 0) return null

  return (
    <div>
      <div className="flex justify-end mb-3">
        <TimeRangeSelector value={range} onChange={setRange} />
      </div>
      <ChartContainer config={chartConfig} className="h-[250px] w-full [&>div>div]:!w-full [&>div>div>svg]:!w-full">
        <LineChart data={filteredData} margin={{ top: 10, right: 10, left: 0, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="date"
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            tickFormatter={(value) => new Date(value).toLocaleDateString(locale, { month: 'short' })}
          />
          <YAxis
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            tickFormatter={money.tick((value: number) => {
              const abs = Math.abs(value)
              const formatted = abs >= 1000 ? `${(abs / 1000).toFixed(abs >= 10000 ? 0 : 1)}k` : abs.toFixed(0)
              return `${value < 0 ? '-' : ''}${formatted}`
            })}
            width={45}
          />
          <ChartTooltip content={<PnlTooltip accounts={accounts} labels={labels} />} />
          {accounts.map((account) => (
            <Line
              key={account.id}
              dataKey={account.id}
              type="monotone"
              stroke={account.color}
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 3 }}
            />
          ))}
        </LineChart>
      </ChartContainer>
    </div>
  )
}
