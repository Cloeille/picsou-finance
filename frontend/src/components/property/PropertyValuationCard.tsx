import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useMoney } from '@/hooks/use-money'
import { RefreshCw, ChevronDown, Info } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { useRefreshValuation } from '@/features/accounts/hooks'
import { formatApiError } from '@/lib/errors'
import { localeFromLanguage } from '@/lib/utils'
import type { PropertyValuation, RealEstateMetadata, ValuationConfidence } from '@/types/api'

interface PropertyValuationCardProps {
  accountId: number
  metadata: RealEstateMetadata
  currentValue: number
}

const CONFIDENCE_STYLES: Record<ValuationConfidence, string> = {
  HIGH: 'bg-emerald-500/10 text-emerald-600 dark:text-emerald-400',
  MEDIUM: 'bg-amber-500/10 text-amber-600 dark:text-amber-400',
  LOW: 'bg-rose-500/10 text-rose-600 dark:text-rose-400',
}

export function PropertyValuationCard({ accountId, metadata, currentValue }: PropertyValuationCardProps) {
  const money = useMoney()
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const refresh = useRefreshValuation()
  const [result, setResult] = useState<PropertyValuation | null>(null)
  const [showMethod, setShowMethod] = useState(false)

  const isManual = metadata.valuationMode === 'MANUAL'

  const onRefresh = () => {
    refresh.mutate(accountId, { onSuccess: setResult })
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between gap-2 pb-3">
        <CardTitle className="text-base">{t('property.valuation.title')}</CardTitle>
        <Button size="sm" variant="outline" onClick={onRefresh} disabled={refresh.isPending}>
          <RefreshCw className={`mr-2 size-4 ${refresh.isPending ? 'animate-spin' : ''}`} />
          {t('property.valuation.refresh')}
        </Button>
      </CardHeader>

      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <CurrencyDisplay value={currentValue} className="text-2xl font-semibold tabular-nums" />
          {isManual && <Badge variant="secondary">{t('property.valuation.manualMode')}</Badge>}
        </div>

        {refresh.isError && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(refresh.error, t, 'property.valuation.error')}
          </p>
        )}

        {result && result.status !== 'OK' && (
          <div role="alert" className="flex gap-2 rounded-md bg-muted/50 p-3 text-sm">
            <Info className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <div>
              <p>{t(`property.valuation.status.${result.status}`)}</p>
              {result.status === 'UNSUPPORTED_AREA' && (
                <p className="mt-1 text-xs text-muted-foreground">
                  {t('property.valuation.status.UNSUPPORTED_AREA_hint')}
                </p>
              )}
            </div>
          </div>
        )}

        {result && result.status === 'OK' && (
          <div className="space-y-3">
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="text-sm text-muted-foreground">{t('property.valuation.estimate')}</span>
              <CurrencyDisplay value={result.estimatedValue ?? 0} className="text-lg font-semibold tabular-nums" />
              {result.confidence && (
                <span className={`rounded px-2 py-0.5 text-xs font-medium ${CONFIDENCE_STYLES[result.confidence]}`}>
                  {t(`property.valuation.confidence.${result.confidence}`)}
                </span>
              )}
            </div>

            {result.lowValue != null && result.highValue != null && (
              <p className="text-sm text-muted-foreground">
                {t('property.valuation.range')}{' '}
                <CurrencyDisplay value={result.lowValue} className="tabular-nums" />
                {' – '}
                <CurrencyDisplay value={result.highValue} className="tabular-nums" />
              </p>
            )}

            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-4">
              {result.pricePerSqm != null && (
                <Fact label={t('property.valuation.pricePerSqm')}
                      value={`${money.amount(Math.round(result.pricePerSqm))}/m²`} />
              )}
              {result.sampleSize != null && (
                <Fact label={t('property.valuation.sampleSize')}
                      value={result.sampleSize.toLocaleString(locale)} />
              )}
              {result.sourceYear != null && (
                <Fact label={t('property.valuation.sourceYear')} value={String(result.sourceYear)} />
              )}
              {result.scale && (
                <Fact label={t('property.valuation.scale')}
                      value={t(`property.valuation.scaleValue.${result.scale}`)} />
              )}
            </dl>

            {isManual && (
              <p className="text-xs text-muted-foreground">
                {t('property.valuation.manualNotApplied')}
              </p>
            )}

            {result.adjustments.length > 0 && (
              <div>
                <button
                  type="button"
                  onClick={() => setShowMethod(v => !v)}
                  className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
                  aria-expanded={showMethod}
                >
                  <ChevronDown className={`size-4 transition-transform ${showMethod ? 'rotate-180' : ''}`} />
                  {t('property.valuation.method')}
                </button>

                {showMethod && (
                  <div className="mt-2 space-y-2 rounded-md border p-3">
                    {/* Stated plainly: these coefficients encode market intuition, not a
                        model fitted on the open data, which records none of these features. */}
                    <p className="text-xs text-muted-foreground">
                      {t('property.valuation.methodDisclaimer')}
                    </p>
                    <ul className="space-y-1 text-sm">
                      {result.adjustments.map(adj => (
                        <li key={adj.code} className="flex items-center justify-between gap-2">
                          <span>{t(`property.adjustments.${adj.code}`, { defaultValue: adj.code })}</span>
                          <span className="tabular-nums text-muted-foreground">
                            {adj.factor != null
                              ? `${adj.factor > 0 ? '+' : ''}${(adj.factor * 100).toFixed(1)} %`
                              : adj.sqm != null
                                ? `+${adj.sqm} m²`
                                : ''}
                          </span>
                        </li>
                      ))}
                      {result.reindexRatio != null && (
                        <li className="flex items-center justify-between gap-2 border-t pt-1">
                          <span>{t('property.valuation.reindexed')}</span>
                          <span className="tabular-nums text-muted-foreground">
                            ×{result.reindexRatio.toFixed(3)}
                          </span>
                        </li>
                      )}
                    </ul>
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="font-medium tabular-nums">{value}</dd>
    </div>
  )
}
