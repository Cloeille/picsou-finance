import { useTranslation } from 'react-i18next'
import { ArrowRight, Check, Info, TrendingUp } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { cn, formatDecimal, formatPercent } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { WealthAlert, WealthPyramid, WealthTier, WealthTierLine } from '@/types/api'

/**
 * Tier colours reuse the Accounts page's asset-group palette so the same money keeps the same
 * colour across the app.
 */
const TIER_COLORS: Record<WealthTier, string> = {
  SAFETY_NET: '#10b981',
  REAL_ESTATE: '#8b5cf6',
  EQUITY: '#3b82f6',
  CRYPTO: '#0ea5e9',
  ALTERNATIVE: '#64748b',
}

/** Gap in points beyond which a tier is worth flagging rather than merely reporting. */
const NOTABLE_GAP = 5

function scoreTone(score: number): string {
  if (score >= 75) return 'text-emerald-600 dark:text-emerald-400'
  if (score >= 50) return 'text-foreground'
  return 'text-rose-600 dark:text-rose-400'
}

function TierRow({ line }: { line: WealthTierLine }) {
  const { t } = useTranslation()
  const notable = Math.abs(line.gapPercent) >= NOTABLE_GAP
  const color = TIER_COLORS[line.tier]

  return (
    <div className="py-3.5 first:pt-0 last:pb-0">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2.5">
          <span
            className="size-2.5 shrink-0 rounded-full"
            style={{ backgroundColor: color }}
            aria-hidden="true"
          />
          <span className="truncate text-sm font-medium text-foreground">
            {t(`analysis.tiers.${line.tier}`)}
          </span>
        </div>
        <div className="flex shrink-0 items-baseline gap-2 text-sm tabular-nums">
          <span className="font-semibold text-foreground">{formatPercent(line.actualPercent)}</span>
          <span className="text-xs text-muted-foreground">
            {t('analysis.targetShort', { value: formatPercent(line.targetPercent, { fractionDigits: 0 }) })}
          </span>
        </div>
      </div>

      {/* Target sits as a marker on the actual bar rather than as a second bar. */}
      <div className="relative h-2 w-full overflow-hidden rounded-full bg-muted">
        <div
          className="h-full rounded-full transition-[width]"
          style={{ width: `${Math.min(100, line.actualPercent)}%`, backgroundColor: color }}
        />
        {line.targetPercent > 0 && (
          <div
            className="absolute inset-y-0 w-0.5 bg-foreground/45"
            style={{ left: `${Math.min(100, line.targetPercent)}%` }}
            aria-hidden="true"
          />
        )}
      </div>

      <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs tabular-nums">
        <CurrencyDisplay value={line.valueEur} className="font-medium text-foreground/80" />
        <div className="flex items-center gap-2">
          <span className="text-muted-foreground">
            {t('analysis.targetAmount')} <CurrencyDisplay value={line.targetEur} />
          </span>
          {notable && (
            <span className="rounded-md bg-muted px-1.5 py-0.5 font-medium text-muted-foreground">
              {line.gapPercent > 0 ? '+' : ''}
              {formatDecimal(line.gapPercent, 1)} {t('analysis.points')}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

function Alerts({ alerts }: { alerts: WealthAlert[] }) {
  const { t } = useTranslation()
  const money = useMoney()
  if (alerts.length === 0) return null

  return (
    <div className="mt-5 border-t border-border/60 pt-4">
      <p className="mb-2.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {t('analysis.alertsTitle')}
      </p>
      <ul className="space-y-2">
        {alerts.map((alert) => (
          <li key={`${alert.code}-${alert.label ?? ''}`} className="flex items-start gap-2.5 text-sm">
            <Info
              className="mt-0.5 size-4 shrink-0 text-muted-foreground"
              aria-hidden="true"
            />
            <span className="text-muted-foreground">
              {t(`analysis.alerts.${alert.code}`, {
                label: alert.label
                  ? t(`analysis.tiers.${alert.label}`, alert.label)
                  : '',
                percent: formatPercent(alert.percent, { fractionDigits: 0 }),
                value: money.amount(alert.valueEur),
              })}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function PyramidSection({ pyramid }: { pyramid: WealthPyramid }) {
  const { t } = useTranslation()
  const { safetyNet, score, alerts } = pyramid

  const coveragePercent = safetyNet.coverage == null ? 0 : Math.min(100, safetyNet.coverage * 100)

  return (
    <div className="grid items-start gap-4 lg:grid-cols-12">
      {/* Left column: score + concise observations */}
      <Card className="lg:col-span-5">
        <CardHeader>
          <CardTitle>{t('analysis.score.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          {score.global == null ? (
            <p className="text-sm text-muted-foreground">{t('analysis.score.notRatedYet')}</p>
          ) : (
            <div className="flex items-baseline gap-2 tabular-nums">
              <span className={cn('text-4xl leading-none font-bold tracking-tight', scoreTone(score.global))}>
                {score.global}
              </span>
              <span className="text-base text-muted-foreground">/ 100</span>
            </div>
          )}

          {score.allocation != null && (
            <p className="mt-2.5 text-sm text-muted-foreground">
              {t('analysis.score.misplaced', { value: formatPercent(score.misplacedPercent) })}
            </p>
          )}

          <dl className="mt-5 grid grid-cols-2 gap-2.5 text-sm">
            <div className="rounded-lg bg-muted/50 px-3 py-2.5">
              <dt className="text-xs text-muted-foreground">{t('analysis.score.safetyNet')}</dt>
              <dd className="mt-1 font-semibold tabular-nums text-foreground">
                {safetyNet.known && safetyNet.score != null
                  ? `${safetyNet.score} / 100`
                  : t('analysis.score.notRated')}
              </dd>
            </div>
            <div className="rounded-lg bg-muted/50 px-3 py-2.5">
              <dt className="text-xs text-muted-foreground">{t('analysis.score.allocation')}</dt>
              <dd className="mt-1 font-semibold tabular-nums text-foreground">
                {score.allocation == null
                  ? t('analysis.score.notRated')
                  : `${score.allocation} / 100`}
              </dd>
            </div>
            {score.cryptoPenalty > 0 && (
              <div className="col-span-2 flex items-center justify-between rounded-lg bg-muted/50 px-3 py-2">
                <dt className="text-xs text-muted-foreground">{t('analysis.score.cryptoPenalty')}</dt>
                <dd className="text-sm font-medium tabular-nums text-rose-600 dark:text-rose-400">
                  −{formatDecimal(score.cryptoPenalty, 1)}
                </dd>
              </div>
            )}
            {score.leverageBonus > 0 && (
              <div className="col-span-2 flex items-center justify-between rounded-lg bg-muted/50 px-3 py-2">
                <dt className="text-xs text-muted-foreground">{t('analysis.score.leverageBonus')}</dt>
                <dd className="text-sm font-medium tabular-nums text-emerald-600 dark:text-emerald-400">
                  +{formatDecimal(score.leverageBonus, 1)}
                </dd>
              </div>
            )}
          </dl>

          <Alerts alerts={alerts} />
        </CardContent>
      </Card>

      {/* Right column: Safety net card + Asset allocation card */}
      <div className="space-y-4 lg:col-span-7">
        <Card>
          <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
            <CardTitle>{t('analysis.safetyNet.cardTitle')}</CardTitle>
            {!safetyNet.known && (
              <Button variant="outline" size="sm" asChild>
                <a href="/settings?tab=profile">
                  {t('analysis.safetyNet.configureCta')}
                  <ArrowRight className="size-3.5" aria-hidden="true" />
                </a>
              </Button>
            )}
          </CardHeader>
          <CardContent>
            {safetyNet.known ? (
              <>
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <CurrencyDisplay value={safetyNet.valueEur} className="text-2xl font-bold text-foreground" />
                  <span className="text-sm text-muted-foreground">
                    {t('analysis.safetyNet.ofTarget')}{' '}
                    <CurrencyDisplay value={safetyNet.targetEur ?? 0} />
                  </span>
                </div>
                <Progress value={coveragePercent} className="mt-3" />
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  {safetyNet.coverage != null && safetyNet.coverage < 1 && (
                    <Badge variant="destructive">
                      {t('analysis.safetyNet.short', {
                        value: formatPercent(safetyNet.coverage * 100, { fractionDigits: 0 }),
                      })}
                    </Badge>
                  )}
                  {safetyNet.excessEur > 0 && (
                    <Badge variant="secondary">
                      <TrendingUp className="size-3" aria-hidden="true" />
                      {t('analysis.safetyNet.excess')}
                    </Badge>
                  )}
                  {safetyNet.coverage != null &&
                    safetyNet.coverage >= 1 &&
                    safetyNet.excessEur === 0 && (
                      <Badge variant="default">
                        <Check className="size-3" aria-hidden="true" />
                        {t('analysis.safetyNet.covered')}
                      </Badge>
                    )}
                </div>
                {safetyNet.excessEur > 0 && (
                  <p className="mt-2.5 text-sm text-muted-foreground">
                    <CurrencyDisplay value={safetyNet.excessEur} className="font-medium text-foreground" />{' '}
                    {t('analysis.safetyNet.excessHint')}
                  </p>
                )}
              </>
            ) : (
              <div className="space-y-2">
                <div className="flex flex-wrap items-baseline gap-2">
                  <CurrencyDisplay value={safetyNet.valueEur} className="text-2xl font-bold text-foreground" />
                  <span className="text-sm text-muted-foreground">{t('analysis.safetyNet.inPassbooks')}</span>
                </div>
                <p className="text-sm text-muted-foreground">{t('analysis.safetyNet.unknown')}</p>
              </div>
            )}

            {safetyNet.dailyCashEur > 0 && (
              <p className="mt-2.5 text-sm text-muted-foreground">
                <CurrencyDisplay value={safetyNet.dailyCashEur} /> {t('analysis.safetyNet.dailyCashNote')}
              </p>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t('analysis.allocationCardTitle')}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="divide-y divide-border/60">
              {pyramid.tiers.map((line) => (
                <TierRow key={line.tier} line={line} />
              ))}
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
