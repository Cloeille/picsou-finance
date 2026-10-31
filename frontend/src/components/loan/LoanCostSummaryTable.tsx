import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { formatPercent } from '@/lib/utils'
import type { LoanSummary } from '@/types/api'

interface LoanCostSummaryTableProps {
  summary: LoanSummary
}

export function LoanCostSummaryTable({ summary }: LoanCostSummaryTableProps) {
  const { t } = useTranslation()

  const interestAndInsuranceCost = summary.totalInterestCost + summary.totalInsuranceCost
  const remainingPct = summary.totalCost > 0
    ? (summary.remainingBalance / summary.totalCost) * 100
    : 0

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('debt.summary')}</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {/* Total cost */}
          <Column title={t('debt.totalCost')} total={summary.totalCost}>
            <Row label={t('debt.capital')} value={summary.totalCapitalCost} />
            <Row label={t('debt.interestAndInsurance')} value={interestAndInsuranceCost} />
            <Row label={t('debt.fileFees')} value={summary.fileFees} />
          </Column>

          {/* Total repaid */}
          <Column title={t('debt.totalRepaid')} total={summary.totalRepaid}>
            <Row label={t('debt.capital')} value={summary.capitalRepaid} />
            <Row label={t('debt.interest')} value={summary.interestRepaid} />
            <Row label={t('debt.insurance')} value={summary.insuranceRepaid} />
          </Column>

          {/* Remaining */}
          <Column title={t('debt.remainingBalance')} total={summary.remainingBalance}>
            <Row label={t('debt.remainingToRepay')} value={summary.remainingBalance} />
            <Row
              label={t('debt.remainingToRepayPct')}
              value={remainingPct}
              format="percent"
            />
          </Column>
        </div>
      </CardContent>
    </Card>
  )
}

function Column({ title, total, children }: { title: string; total: number; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2 p-3 rounded-lg border bg-card/50">
      <div className="flex items-center justify-between border-b pb-2 mb-1">
        <span className="text-xs text-muted-foreground">{title}</span>
        <CurrencyDisplay value={total} className="text-base font-semibold" />
      </div>
      <div className="flex flex-col gap-1.5">
        {children}
      </div>
    </div>
  )
}

function Row({ label, value, format = 'currency', hidden = false }: {
  label: string
  value: number
  format?: 'currency' | 'percent'
  hidden?: boolean
}) {
  if (hidden) return null
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-muted-foreground">{label}</span>
      {format === 'percent'
        ? <span className="tabular-nums font-medium">{formatPercent(value, { fractionDigits: 0 })}</span>
        : <CurrencyDisplay value={value} className="tabular-nums font-medium" />}
    </div>
  )
}
