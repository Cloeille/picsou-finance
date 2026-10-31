import { useTranslation } from 'react-i18next'
import { formatPercent, localeFromLanguage } from '@/lib/utils'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import type { LoanSummary } from '@/types/api'

interface LoanProgressCardProps {
  summary: LoanSummary
}

export function LoanProgressCard({ summary }: LoanProgressCardProps) {
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const endLabel = summary.endDate
    ? new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' })
        .format(new Date(summary.endDate))
    : '—'

  const pct = Math.max(0, Math.min(100, summary.capitalRepaidPct))

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('debt.loanProgress')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          <Stat label={t('debt.paidInstallments')} value={String(summary.paidInstallments)} />
          <Stat label={t('debt.remainingInstallments')} value={String(summary.remainingInstallments)} />
          <Stat label={t('debt.endDate')} value={endLabel} className="capitalize" />
        </div>

        <div className="space-y-2">
          <Progress value={pct} className="h-2" />
          <p className="text-xs text-muted-foreground">
            {t('debt.capitalRepaidPct', { pct: formatPercent(pct, { fractionDigits: 0 }, locale) })}
          </p>
        </div>
      </CardContent>
    </Card>
  )
}

function Stat({ label, value, className = '' }: { label: string; value: string; className?: string }) {
  return (
    <div className="flex flex-col">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={`text-base font-semibold tabular-nums ${className}`}>{value}</span>
    </div>
  )
}
