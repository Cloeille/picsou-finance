import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { CartesianGrid, Line, LineChart, XAxis, YAxis } from 'recharts'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ChartContainer, ChartTooltip, type ChartConfig } from '@/components/ui/chart'
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { MoneyChartTooltip } from '@/components/shared/MoneyChartTooltip'
import { AXIS_PROPS, yAxisWidth } from '@/lib/chart-axis'
import { useMoney } from '@/hooks/use-money'
import { formatLocalDate, formatPercent, localeFromLanguage, toDate } from '@/lib/utils'
import type { PeerLoanOverviewEntry, PeerLoansOverview } from '@/types/api'

function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="rounded-lg border border-border/60 p-3">
      <p className="text-xs text-muted-foreground">{label}</p>
      <p className="mt-1 text-lg font-semibold tabular-nums">{value}</p>
    </div>
  )
}

/**
 * Every peer loan as one view: who still owes what, whether they are behind, and how the
 * total outstanding moved. Facts only -- the badge reports the plan's drift, it does not
 * score the borrower.
 */
export function PeerLoansOverviewCard({ data }: { data: PeerLoansOverview }) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const money = useMoney()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const chartConfig = {
    outstanding: { label: t('peerLoan.overview.totalOutstanding'), color: 'var(--chart-1)' },
  } satisfies ChartConfig

  function status(loan: PeerLoanOverviewEntry) {
    if (loan.closedAt) {
      return <Badge variant="secondary">{t('peerLoan.overview.status.closed')}</Badge>
    }
    if (loan.installmentsBehind != null && loan.installmentsBehind > 0) {
      return (
        <Badge variant="destructive">
          {t('peerLoan.overview.status.behind', { count: loan.installmentsBehind })}
        </Badge>
      )
    }
    if (loan.installmentsBehind != null) {
      return <Badge variant="outline">{t('peerLoan.overview.status.onTime')}</Badge>
    }
    return <Badge variant="outline">{t('peerLoan.overview.status.noPlan')}</Badge>
  }

  function open(accountId: number) {
    void navigate(`/accounts/${accountId}`)
  }

  const yWidth = yAxisWidth(data.history.map((point) => point.outstanding), money.tick)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('peerLoan.overview.title')}</CardTitle>
        <p className="text-sm text-muted-foreground">{t('peerLoan.overview.subtitle')}</p>
      </CardHeader>
      <CardContent className="space-y-5">
        <div className="grid gap-3 sm:grid-cols-3">
          <Stat
            label={t('peerLoan.overview.totalOutstanding')}
            value={<CurrencyDisplay value={data.totalOutstanding} />}
          />
          <Stat label={t('peerLoan.overview.openLoans')} value={data.openCount} />
          <Stat
            label={t('peerLoan.overview.interestAccrued')}
            value={<CurrencyDisplay value={data.interestAccrued} />}
          />
        </div>

        {data.history.length >= 2 && (
          <div>
            <p className="mb-2 text-sm font-medium">{t('peerLoan.overview.curveTitle')}</p>
            <ChartContainer config={chartConfig} className="h-[220px] w-full">
              <LineChart data={data.history} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="date"
                  {...AXIS_PROPS}
                  tickFormatter={(value: string) => toDate(value).toLocaleDateString(locale, {
                    month: 'short',
                    year: '2-digit',
                  })}
                />
                <YAxis
                  {...AXIS_PROPS}
                  width={yWidth}
                  tickFormatter={money.tick}
                />
                <ChartTooltip
                  content={
                    <MoneyChartTooltip
                      config={chartConfig}
                      labelFormatter={(value) => toDate(String(value)).toLocaleDateString(locale, {
                        month: 'long',
                        year: 'numeric',
                      })}
                    />
                  }
                />
                <Line
                  dataKey="outstanding"
                  type="monotone"
                  stroke="var(--color-outstanding)"
                  strokeWidth={2}
                  dot={false}
                />
              </LineChart>
            </ChartContainer>
          </div>
        )}

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t('peerLoan.overview.columns.borrower')}</TableHead>
              <TableHead className="text-right">{t('peerLoan.overview.columns.remaining')}</TableHead>
              <TableHead className="hidden text-right sm:table-cell">
                {t('peerLoan.overview.columns.repaid')}
              </TableHead>
              <TableHead>{t('peerLoan.overview.columns.status')}</TableHead>
              <TableHead className="hidden sm:table-cell">
                {t('peerLoan.overview.columns.lastPayment')}
              </TableHead>
              <TableHead className="hidden text-right md:table-cell">
                {t('peerLoan.overview.columns.expectedBack')}
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.loans.map((loan) => (
              <TableRow
                key={loan.accountId}
                className={loan.closedAt ? 'opacity-70' : ''}
              >
                <TableCell className="font-medium">
                  <button
                    type="button"
                    className="cursor-pointer text-left underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    onClick={() => open(loan.accountId)}
                  >
                    {loan.name}
                  </button>
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  <CurrencyDisplay value={loan.principalRemaining} />
                </TableCell>
                <TableCell className="hidden text-right tabular-nums sm:table-cell">
                  {formatPercent(loan.repaidPct, { fractionDigits: 0 }, locale)}
                </TableCell>
                <TableCell>{status(loan)}</TableCell>
                <TableCell className="hidden sm:table-cell">
                  {loan.lastPaymentDate ? (
                    <span className="flex flex-col text-xs">
                      <span>{formatLocalDate(loan.lastPaymentDate, locale)}</span>
                      <CurrencyDisplay
                        value={loan.lastPaymentAmount ?? 0}
                        className="font-medium tabular-nums"
                      />
                    </span>
                  ) : '—'}
                </TableCell>
                <TableCell className="hidden text-right text-xs md:table-cell">
                  {loan.expectedBack ? formatLocalDate(loan.expectedBack, locale) : '—'}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
