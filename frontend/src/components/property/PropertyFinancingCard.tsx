import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { localeFromLanguage } from '@/lib/utils'
import type { RealEstatePropertyLine } from '@/types/api'

interface PropertyFinancingCardProps {
  line: RealEstatePropertyLine
}

/**
 * The mortgages attached to a property, and the equity left once they are paid.
 *
 * <p>Each loan is weighted by the member's share of <em>that loan</em>, which need not match
 * their share of the property — one partner can own more of the house while the mortgage is
 * split evenly.
 */
export function PropertyFinancingCard({ line }: PropertyFinancingCardProps) {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  if (line.loans.length === 0) return null

  const ltv = line.grossValue > 0 ? (line.outstandingDebt / line.grossValue) * 100 : 0
  const equityPct = Math.max(0, Math.min(100, 100 - ltv))

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">{t('property.financing.title')}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <Stat label={t('property.financing.gross')} value={<CurrencyDisplay value={line.grossValue} />} />
          <Stat label={t('property.financing.debt')} value={<CurrencyDisplay value={line.outstandingDebt} />} />
          <Stat
            label={t('property.financing.net')}
            value={<CurrencyDisplay value={line.netValue} />}
            className={line.netValue < 0 ? 'text-destructive' : ''}
          />
        </div>

        <div className="space-y-2">
          <Progress value={equityPct} className="h-2" />
          <p className="text-xs text-muted-foreground">
            {t('property.financing.equityShare', { pct: equityPct.toFixed(0) })}
          </p>
        </div>

        <ul className="space-y-2 border-t pt-3">
          {line.loans.map(loan => (
            <li key={loan.accountId}>
              <button
                type="button"
                onClick={() => navigate(`/accounts/${loan.accountId}`)}
                className="flex w-full items-center justify-between gap-3 rounded-lg px-2 py-1.5 text-left hover:bg-muted/40"
              >
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{loan.name}</span>
                  {loan.lenderName && (
                    <span className="block truncate text-xs text-muted-foreground">{loan.lenderName}</span>
                  )}
                </span>
                <span className="shrink-0 text-right">
                  <CurrencyDisplay value={loan.outstandingBalance} className="text-sm tabular-nums" />
                  {loan.endDate && (
                    <span className="block text-xs text-muted-foreground">
                      {new Intl.DateTimeFormat(locale, { month: 'short', year: 'numeric' })
                        .format(new Date(loan.endDate))}
                    </span>
                  )}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

function Stat({ label, value, className = '' }: {
  label: string; value: React.ReactNode; className?: string
}) {
  return (
    <div className="flex flex-col">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={`text-base font-semibold tabular-nums ${className}`}>{value}</span>
    </div>
  )
}
