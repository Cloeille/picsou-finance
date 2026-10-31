import { useTranslation } from 'react-i18next'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { formatLocalDate } from '@/lib/utils'
import type { DashboardLiability } from '@/types/api'

interface Props {
  liabilities: DashboardLiability[]
  totalMonthlyPayment?: number | null
}

/**
 * Renders the dashboard's liabilities as their own reading, separate from assets and
 * portfolio performance (issue #18). Each row shows the outstanding amount in red, then
 * a loan's repayment progress and monthly payment, or a credit card's next statement.
 */
export function LiabilitiesCard({ liabilities, totalMonthlyPayment }: Props) {
  const { t } = useTranslation()
  const totalDebt = liabilities.reduce((sum, l) => sum + l.balanceEur, 0)

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('dashboard.liabilities')}</CardTitle>
        <CardDescription className="flex flex-wrap gap-x-4 gap-y-1">
          <span>
            {t('dashboard.totalLiabilities')}:{' '}
            <span className="font-medium text-destructive">
              <CurrencyDisplay value={totalDebt} />
            </span>
          </span>
          {totalMonthlyPayment != null && (
            <span>
              {t('dashboard.monthlyPayment')}:{' '}
              <span className="font-medium text-foreground">
                <CurrencyDisplay value={totalMonthlyPayment} />/mo
              </span>
            </span>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {liabilities.map((liability) => (
          <div
            key={liability.accountId}
            className="flex flex-col gap-1.5 rounded-2xl bg-muted/40 px-4 py-3"
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 min-w-0">
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: liability.color }}
                />
                <span className="truncate text-sm font-medium">{liability.name}</span>
              </div>
              <span className="shrink-0 text-sm font-semibold text-destructive">
                <CurrencyDisplay value={liability.balanceEur} />
              </span>
            </div>

            {liability.accountType === 'CREDIT_CARD'
              ? <CardStatement liability={liability} />
              : <LoanProgress liability={liability} />}
          </div>
        ))}
      </CardContent>
    </Card>
  )
}

function LoanProgress({ liability }: { liability: DashboardLiability }) {
  const { t } = useTranslation()
  const { percentPaid, monthlyPayment } = liability

  if (percentPaid == null) {
    return (
      <div className="flex items-center gap-1.5">
        <span
          aria-label="Parameters not configured"
          className="flex size-3.5 shrink-0 items-center justify-center rounded-full border border-muted-foreground/30 text-[9px] text-muted-foreground/40"
        >
          i
        </span>
        <span className="text-xs italic text-muted-foreground/50">
          {t('dashboard.loanParamsUnconfigured')}
        </span>
      </div>
    )
  }

  return (
    <div className="flex items-center gap-2">
      <Progress
        value={percentPaid}
        className="h-1.5 flex-1 [&_[data-slot=progress-indicator]]:bg-primary/60"
      />
      <span className="shrink-0 text-xs text-muted-foreground">
        {Math.round(percentPaid)}%
        {monthlyPayment != null && (
          <> · <CurrencyDisplay value={monthlyPayment} />/mo</>
        )}
      </span>
    </div>
  )
}

/** A card has no amortisation: what matters is the next statement, when the provider knows it. */
function CardStatement({ liability }: { liability: DashboardLiability }) {
  const { t } = useTranslation()
  const { paymentDueAmountEur, paymentDueDate } = liability

  if (paymentDueAmountEur == null && !paymentDueDate) return null

  return (
    <p className="text-xs text-muted-foreground">
      {paymentDueAmountEur != null && (
        <>
          {t('dashboard.amountDue')}{' '}
          <span className="font-medium text-foreground">
            <CurrencyDisplay value={paymentDueAmountEur} />
          </span>
        </>
      )}
      {paymentDueAmountEur != null && paymentDueDate && ' · '}
      {paymentDueDate && t('dashboard.dueOn', { date: formatLocalDate(paymentDueDate) })}
    </p>
  )
}
