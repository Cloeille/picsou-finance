import { useTranslation } from 'react-i18next'
import { Home, TrendingUp, TrendingDown } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useRealEstateSummary } from '@/features/accounts/hooks'

/**
 * Whole-portfolio property position: gross value, mortgage debt and the equity between them.
 *
 * <p>Every figure is already weighted by the member's shares server-side, so a half-owned
 * house shows half its value <em>and</em> half its mortgage.
 */
export function RealEstateSummaryCard({ enabled = true }: { enabled?: boolean }) {
  const { t } = useTranslation()
  const { data, isLoading } = useRealEstateSummary(enabled)

  if (isLoading) {
    return (
      <Card>
        <CardContent className="pt-6"><Skeleton className="h-24 w-full" /></CardContent>
      </Card>
    )
  }

  // Nothing to say when the user owns no property; an empty card would just be noise.
  // The optional chain also covers a truncated payload, which would otherwise throw during
  // render and take the whole dashboard down with it.
  if (!data?.properties?.length && !data?.paper?.length) return null

  const gainPositive = data.unrealizedGain >= 0
  const GainIcon = gainPositive ? TrendingUp : TrendingDown

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <Home className="size-4" />
          {t('property.summary.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <Stat label={t('property.summary.gross')}>
            <CurrencyDisplay value={data.grossValue} />
          </Stat>
          <Stat label={t('property.summary.debt')}>
            <CurrencyDisplay value={data.outstandingDebt} />
          </Stat>
          <Stat label={t('property.summary.net')} emphasis>
            <CurrencyDisplay value={data.netValue} className={data.netValue < 0 ? 'text-destructive' : ''} />
          </Stat>
          <Stat label={t('property.summary.gain')}>
            <span className={`inline-flex items-center gap-1 ${gainPositive ? 'text-emerald-600 dark:text-emerald-400' : 'text-destructive'}`}>
              <GainIcon className="size-4" />
              <CurrencyDisplay value={data.unrealizedGain} showSign />
              {data.unrealizedGainPercent != null && (
                <span className="text-xs">({data.unrealizedGainPercent.toFixed(1)} %)</span>
              )}
            </span>
          </Stat>
        </div>

        {data.loanToValue != null && (
          <p className="text-xs text-muted-foreground">
            {t('property.summary.loanToValue', { pct: data.loanToValue.toFixed(1) })}
          </p>
        )}

        {data.monthlyRentalIncome > 0 && (
          <p className="text-xs text-muted-foreground">
            {t('property.summary.rentalIncome')}{' '}
            <CurrencyDisplay value={data.monthlyRentalIncome} className="tabular-nums" />
          </p>
        )}

        {(data.paper?.length ?? 0) > 0 && (
          <div className="space-y-2 border-t pt-4">
            <p className="text-sm font-medium">{t('scpi.summary.title')}</p>
            <div className="grid grid-cols-3 gap-4">
              <Stat label={t('scpi.summary.gross')}>
                <CurrencyDisplay value={data.paperGross} />
              </Stat>
              <Stat label={t('scpi.summary.debt')}>
                <CurrencyDisplay value={data.paperDebt} />
              </Stat>
              <Stat label={t('scpi.summary.net')} emphasis>
                <CurrencyDisplay value={data.paperNet} />
              </Stat>
            </div>
            {data.paper?.some((line) => line.valuationStatus === 'PRICE_INCOMPLETE') && (
              <p className="text-xs text-amber-600 dark:text-amber-400">{t('scpi.summary.incomplete')}</p>
            )}
            <p className="text-xs text-muted-foreground">{t('scpi.summary.hint')}</p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function Stat({ label, children, emphasis = false }: {
  label: string; children: React.ReactNode; emphasis?: boolean
}) {
  return (
    <div className="flex flex-col">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={`tabular-nums ${emphasis ? 'text-lg font-semibold' : 'text-base font-medium'}`}>
        {children}
      </span>
    </div>
  )
}
