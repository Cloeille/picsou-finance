import { useTranslation } from 'react-i18next'
import { AlertTriangle, Check, TrendingUp, TriangleAlert } from 'lucide-react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { cn } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { WealthAlert, WealthPyramid, WealthTier, WealthTierLine } from '@/types/api'

/**
 * Tier colours reuse the Accounts page's asset-group palette so the same money keeps the same
 * colour across the app.
 */
const TIER_COLORS: Record<WealthTier, string> = {
  SAFETY_NET: '#f59e0b',
  REAL_ESTATE: '#a855f7',
  EQUITY: '#6366f1',
  CRYPTO: '#f97316',
  ALTERNATIVE: '#eab308',
}

/** Gap in points beyond which a tier is worth flagging rather than merely reporting. */
const NOTABLE_GAP = 5

function scoreTone(score: number): string {
  if (score >= 75) return 'text-green-600 dark:text-green-500'
  if (score >= 50) return 'text-amber-600 dark:text-amber-500'
  return 'text-red-600 dark:text-red-500'
}

function TierRow({ line }: { line: WealthTierLine }) {
  const { t } = useTranslation()
  const notable = Math.abs(line.gapPercent) >= NOTABLE_GAP
  const color = TIER_COLORS[line.tier]

  return (
    <div className="py-3">
      <div className="mb-2 flex items-baseline justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <span
            className="size-2.5 shrink-0 rounded-full"
            style={{ backgroundColor: color }}
            aria-hidden="true"
          />
          <span className="truncate text-sm text-foreground">
            {t(`analysis.tiers.${line.tier}`)}
          </span>
        </div>
        <div className="flex shrink-0 items-baseline gap-2 text-sm">
          <span className="text-foreground">{line.actualPercent.toFixed(1)}%</span>
          <span className="text-muted-foreground">
            {t('analysis.targetShort', { value: line.targetPercent.toFixed(0) })}
          </span>
        </div>
      </div>

      {/* Target sits as a marker on the actual bar rather than as a second bar: the question is
          "how far off am I", and two bars make the reader compute the difference themselves. */}
      <div className="relative h-2 w-full overflow-hidden rounded-full bg-muted">
        <div
          className="h-full rounded-full transition-[width]"
          style={{ width: `${Math.min(100, line.actualPercent)}%`, backgroundColor: color }}
        />
        {line.targetPercent > 0 && (
          <div
            className="absolute inset-y-0 w-0.5 bg-foreground/50"
            style={{ left: `${Math.min(100, line.targetPercent)}%` }}
            aria-hidden="true"
          />
        )}
      </div>

      <div className="mt-1.5 flex items-baseline justify-between gap-3 text-xs">
        <CurrencyDisplay value={line.valueEur} className="text-muted-foreground" />
        <div className="flex items-baseline gap-2">
          {/* The target in euros sits beside the gap in points: "-6.4 pts" is not something you
              can act on, "the target here is 71 000 €" is. */}
          <span className="text-muted-foreground">
            {t('analysis.targetAmount')} <CurrencyDisplay value={line.targetEur} />
          </span>
          {notable && (
            <span
              className={cn(
                line.gapPercent > 0
                  ? 'text-amber-600 dark:text-amber-500'
                  : 'text-sky-600 dark:text-sky-500',
              )}
            >
              ({line.gapPercent > 0 ? '+' : ''}
              {line.gapPercent.toFixed(1)} {t('analysis.points')})
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

/**
 * Observations the score cannot make.
 *
 * The score measures distance to targets the member chose, so a portfolio whose targets were set
 * to match it scores full marks however it is shaped. These come from the portfolio alone and
 * cannot be silenced by editing a target — which is the whole point of showing them beside the
 * number rather than folding them into it.
 */
function Alerts({ alerts }: { alerts: WealthAlert[] }) {
  const { t } = useTranslation()
  const money = useMoney()
  if (alerts.length === 0) return null

  return (
    <ul className="mt-4 space-y-2 border-t border-border pt-4">
      {alerts.map((alert) => (
        <li key={`${alert.code}-${alert.label ?? ''}`} className="flex gap-2 text-sm">
          <TriangleAlert
            className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-500"
            aria-hidden="true"
          />
          <span className="text-muted-foreground">
            {t(`analysis.alerts.${alert.code}`, {
              label: alert.label
                ? t(`analysis.tiers.${alert.label}`, alert.label)
                : '',
              percent: alert.percent.toFixed(0),
              // The amount sits mid-sentence in the translation, so the mask has to be applied
              // to the interpolated value -- there is no element to intercept.
              value: money.amount(alert.valueEur),
            })}
          </span>
        </li>
      ))}
    </ul>
  )
}

export function PyramidSection({ pyramid }: { pyramid: WealthPyramid }) {
  const { t } = useTranslation()
  const { safetyNet, score, alerts } = pyramid

  // Coverage is capped for the bar only — the figure beside it still tells the truth.
  // Loose: an absent coverage arrives as undefined, and undefined * 100 is NaN.
  const coveragePercent = safetyNet.coverage == null ? 0 : Math.min(100, safetyNet.coverage * 100)

  return (
    <div className="grid items-start gap-4 lg:grid-cols-3">
      <Card className="lg:col-span-1">
        <CardHeader>
          <CardTitle>{t('analysis.score.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          {/* No score rather than a flattering one: with nothing to allocate and no stated
              expenses there is nothing to judge, and the old fallback returned 100. */}
          {score.global == null ? (
            <p className="text-sm text-muted-foreground">{t('analysis.score.notRatedYet')}</p>
          ) : (
            <div className="flex items-baseline gap-2">
              <span className={cn('text-[44px] leading-none font-bold', scoreTone(score.global))}>
                {score.global}
              </span>
              <span className="text-lg text-muted-foreground">/ 100</span>
            </div>
          )}

          {score.allocation != null && (
            <p className="mt-3 text-sm text-muted-foreground">
              {t('analysis.score.misplaced', { value: score.misplacedPercent.toFixed(1) })}
            </p>
          )}

          <dl className="mt-4 space-y-2 text-sm">
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted-foreground">{t('analysis.score.safetyNet')}</dt>
              <dd className="text-foreground">
                {safetyNet.known && safetyNet.score != null
                  ? `${safetyNet.score} / 100`
                  : t('analysis.score.notRated')}
              </dd>
            </div>
            <div className="flex items-center justify-between gap-3">
              <dt className="text-muted-foreground">{t('analysis.score.allocation')}</dt>
              <dd className="text-foreground">
                {score.allocation == null
                  ? t('analysis.score.notRated')
                  : `${score.allocation} / 100`}
              </dd>
            </div>
            {score.cryptoPenalty > 0 && (
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">{t('analysis.score.cryptoPenalty')}</dt>
                <dd className="text-amber-600 dark:text-amber-500">
                  −{score.cryptoPenalty.toFixed(1)}
                </dd>
              </div>
            )}
            {score.leverageBonus > 0 && (
              <div className="flex items-center justify-between gap-3">
                <dt className="text-muted-foreground">{t('analysis.score.leverageBonus')}</dt>
                <dd className="text-green-600 dark:text-green-500">
                  +{score.leverageBonus.toFixed(1)}
                </dd>
              </div>
            )}
          </dl>

          <Alerts alerts={alerts} />

          {!safetyNet.known && (
            <p className="mt-4 flex gap-2 rounded-lg bg-muted p-3 text-xs text-muted-foreground">
              <AlertTriangle className="mt-px size-4 shrink-0" aria-hidden="true" />
              {t('analysis.score.safetyNetUnknownHint')}
            </p>
          )}
        </CardContent>
      </Card>

      <Card className="lg:col-span-2">
        <CardHeader>
          <CardTitle>{t('analysis.safetyNet.title')}</CardTitle>
        </CardHeader>
        <CardContent>
          {safetyNet.known ? (
            <>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <CurrencyDisplay value={safetyNet.valueEur} className="text-2xl text-foreground" />
                <span className="text-sm text-muted-foreground">
                  {t('analysis.safetyNet.ofTarget')}{' '}
                  <CurrencyDisplay value={safetyNet.targetEur ?? 0} />
                </span>
              </div>
              <Progress value={coveragePercent} className="mt-3" />
              <div className="mt-2 flex flex-wrap items-center gap-2">
                {safetyNet.coverage != null && safetyNet.coverage < 1 && (
                  <Badge variant="destructive">
                    {t('analysis.safetyNet.short', {
                      value: Math.round(safetyNet.coverage * 100),
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
                <p className="mt-3 text-sm text-muted-foreground">
                  {t('analysis.safetyNet.excessHint')}
                </p>
              )}
              {safetyNet.dailyCashEur > 0 && (
                <p className="mt-2 text-sm text-muted-foreground">
                  {t('analysis.safetyNet.dailyCash')}{' '}
                  <CurrencyDisplay value={safetyNet.dailyCashEur} className="text-foreground" />
                  {', '}
                  {t('analysis.safetyNet.dailyCashHint')}
                </p>
              )}
            </>
          ) : (
            <>
              <p className="text-sm text-muted-foreground">
                {t('analysis.safetyNet.unknown')}
              </p>
              <p className="mt-2 flex flex-wrap items-baseline gap-1 text-sm text-muted-foreground">
                <CurrencyDisplay value={safetyNet.valueEur} className="text-foreground" />
                <span>{t('analysis.safetyNet.inPassbooks')}</span>
              </p>
            </>
          )}

          <div className="mt-6 divide-y divide-border border-t border-border">
            {pyramid.tiers.map((line) => (
              <TierRow key={line.tier} line={line} />
            ))}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
