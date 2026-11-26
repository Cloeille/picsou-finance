import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { List, SquaresFour, PencilSimple, ChartPieSlice, ArrowsClockwise } from '@phosphor-icons/react'
import type { Icon as LucideIcon } from '@phosphor-icons/react'
import { toast } from 'sonner'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { OTHERS_SLICE_COLOR, SLICE_PALETTE } from '@/lib/chart-palette'
import { cn, formatDecimal, formatPercent } from '@/lib/utils'
import { useRefreshSecurityProfiles } from '@/features/analysis/hooks'
import { HoldingClassificationModal } from './HoldingClassificationModal'
import { SliceContributors } from './SliceContributors'
import { BreakdownDonut, BreakdownTreemap, type BreakdownView } from './BreakdownChart'
import type {
  Diversification,
  DiversificationBreakdown,
  DiversificationSecurity,
  UnclassifiedLine,
} from '@/types/api'

/** Longest list shown before it is cut; the rest are reachable from the holdings table. */
const MAX_UNCLASSIFIED_SHOWN = 8

const VIEWS: { value: BreakdownView; icon: LucideIcon; labelKey: string }[] = [
  { value: 'bar', icon: List, labelKey: 'analysis.diversification.views.bar' },
  { value: 'donut', icon: ChartPieSlice, labelKey: 'analysis.diversification.views.donut' },
  { value: 'treemap', icon: SquaresFour, labelKey: 'analysis.diversification.views.treemap' },
]

const VIEW_STORAGE_KEY = 'picsou.diversification.view'

function storedView(): BreakdownView {
  if (typeof window === 'undefined') return 'bar'
  const saved = window.localStorage.getItem(VIEW_STORAGE_KEY)
  return saved === 'donut' || saved === 'treemap' ? saved : 'bar'
}

/** Below this a slice is thinner than a hairline; it joins the remainder instead. */
const MIN_VISIBLE_PERCENT = 0.5

function scoreTone(score: number): string {
  if (score >= 75) return 'text-emerald-600 dark:text-emerald-400'
  if (score >= 50) return 'text-foreground'
  return 'text-rose-600 dark:text-rose-400'
}

function BreakdownBar({
  breakdown,
  labelNs,
  title,
  securities,
  view,
}: {
  breakdown: DiversificationBreakdown
  labelNs: string
  title: string
  securities: DiversificationSecurity[]
  view: BreakdownView
}) {
  const { t } = useTranslation()
  const [selected, setSelected] = useState<string | null>(null)

  const visible = breakdown.slices.filter((s) => s.percent >= MIN_VISIBLE_PERCENT)
  const remainder = breakdown.slices
    .filter((s) => s.percent < MIN_VISIBLE_PERCENT)
    .reduce((acc, s) => acc + s.percent, 0)

  const items: { key: string | null; label: string; percent: number; color: string }[] =
    visible.map((slice, i) => ({
      key: slice.label,
      label: t(`${labelNs}.${slice.label}`, slice.label),
      percent: slice.percent,
      color: slice.label === 'other' ? OTHERS_SLICE_COLOR : SLICE_PALETTE[i % SLICE_PALETTE.length],
    }))
  if (remainder >= MIN_VISIBLE_PERCENT) {
    const existingOther = items.find((item) => item.key === 'other')
    if (existingOther) {
      existingOther.percent += remainder
    } else {
      items.push({
        key: null,
        label: t('holdings.insight.others'),
        percent: remainder,
        color: OTHERS_SLICE_COLOR,
      })
    }
  }

  const openSlice = selected === null ? null : breakdown.slices.find((s) => s.label === selected)
  const openColor = items.find((i) => i.key === selected)?.color ?? OTHERS_SLICE_COLOR

  return (
    <div>
      <div className="mb-2.5 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium text-foreground">{title}</span>
        <div className="flex items-baseline gap-2.5 tabular-nums">
          <span className={cn('text-sm font-semibold', scoreTone(breakdown.score))}>
            {breakdown.score} / 100
          </span>
          <span className="text-xs text-muted-foreground">
            {t('analysis.diversification.effective', {
              value: formatDecimal(breakdown.effectiveCount, 1),
              target: breakdown.targetCount,
            })}
          </span>
        </div>
      </div>

      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('analysis.diversification.noData')}</p>
      ) : (
        <>
          {view === 'bar' && (
            <div className="flex h-2 w-full overflow-hidden rounded-full bg-muted">
              {items.map((item, i) => (
                <div
                  key={`${item.label}-${i}`}
                  style={{ width: `${item.percent}%`, backgroundColor: item.color }}
                  aria-hidden="true"
                />
              ))}
            </div>
          )}
          {view === 'donut' && (
            <BreakdownDonut items={items} selected={selected} onSelect={setSelected} />
          )}
          {view === 'treemap' && (
            <BreakdownTreemap items={items} selected={selected} onSelect={setSelected} />
          )}
          <ul className="mt-3 flex flex-wrap gap-x-3 gap-y-1.5">
            {items.map((item, i) => (
              <li key={`${item.label}-${i}`}>
                <button
                  type="button"
                  disabled={item.key === null}
                  onClick={() => setSelected(selected === item.key ? null : item.key)}
                  aria-pressed={selected === item.key}
                  className={cn(
                    'flex items-center gap-1.5 rounded-md px-1.5 py-0.5 text-xs transition-colors',
                    item.key !== null && 'hover:bg-muted',
                    selected === item.key && 'bg-muted font-medium',
                  )}
                >
                  <span
                    className="size-2 shrink-0 rounded-full"
                    style={{ backgroundColor: item.color }}
                    aria-hidden="true"
                  />
                  <span className="text-foreground">{item.label}</span>
                  <span className="tabular-nums text-muted-foreground">{formatPercent(item.percent)}</span>
                </button>
              </li>
            ))}
          </ul>

          {openSlice != null && (
            <div className="mt-3">
              <SliceContributors
                slice={openSlice}
                securities={securities}
                labelNs={labelNs}
                color={openColor}
              />
            </div>
          )}
        </>
      )}
    </div>
  )
}

function UnclassifiedList({
  lines,
  onEdit,
}: {
  lines: UnclassifiedLine[]
  onEdit: (line: UnclassifiedLine) => void
}) {
  const { t } = useTranslation()
  const refresh = useRefreshSecurityProfiles()

  const shown = lines.slice(0, MAX_UNCLASSIFIED_SHOWN)
  const hidden = lines.length - shown.length
  const anyPending = lines.some((line) => !line.profileLooked)

  return (
    <div className="space-y-3 border-t border-border/60 pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm font-medium text-foreground">
          {t('analysis.classification.listTitle')}
        </span>
        {anyPending && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={refresh.isPending}
            onClick={() =>
              refresh.mutate(undefined, {
                onSuccess: (result) =>
                  toast.success(
                    result.alreadyRunning
                      ? t('analysis.classification.refreshAlready')
                      : t('analysis.classification.refreshQueued', {
                          count: result.queuedTickers,
                        }),
                  ),
              })
            }
          >
            <ArrowsClockwise
              className={cn('size-4', refresh.isPending && 'animate-spin')}
              aria-hidden="true"
            />
            {t('analysis.classification.refresh')}
          </Button>
        )}
      </div>

      <ul className="space-y-1.5">
        {shown.map((line) => (
          <li
            key={line.ticker}
            className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-muted/50 px-3 py-2.5"
          >
            <div className="min-w-0">
              <span className="text-sm font-medium text-foreground">{line.name ?? line.ticker}</span>
              <span className="ml-2 text-xs text-muted-foreground">{line.ticker}</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-xs text-muted-foreground">
                {line.profileLooked
                  ? t('analysis.classification.missing', {
                      fields: [
                        line.sectorMissing && t('analysis.classification.sector'),
                        line.countryMissing && t('analysis.classification.country'),
                      ]
                        .filter(Boolean)
                        .join(', '),
                    })
                  : t('analysis.classification.notLookedUp')}
              </span>
              <CurrencyDisplay value={line.valueEur} className="text-sm font-medium text-foreground" />
              {line.accountId != null && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => onEdit(line)}
                  aria-label={t('analysis.classification.editAria', { ticker: line.ticker })}
                >
                  <PencilSimple className="size-4" aria-hidden="true" />
                </Button>
              )}
            </div>
          </li>
        ))}
      </ul>

      {hidden > 0 && (
        <p className="text-xs text-muted-foreground">
          {t('analysis.classification.andMore', { count: hidden })}
        </p>
      )}
    </div>
  )
}

export function DiversificationSection({ data }: { data: Diversification }) {
  const { t } = useTranslation()
  const [editing, setEditing] = useState<UnclassifiedLine | null>(null)
  const [view, setView] = useState<BreakdownView>(storedView)

  function chooseView(next: BreakdownView) {
    setView(next)
    window.localStorage.setItem(VIEW_STORAGE_KEY, next)
  }

  const hasHoldings = data.totalValueEur > 0
  const hasBreakdowns =
    data.sectors.slices.some((s) => s.percent >= MIN_VISIBLE_PERCENT) ||
    data.countries.slices.some((s) => s.percent >= MIN_VISIBLE_PERCENT)

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <CardTitle>{t('analysis.diversification.title')}</CardTitle>
        {hasHoldings && hasBreakdowns && (
          <div className="inline-flex items-center gap-1 rounded-lg bg-muted p-1">
            {VIEWS.map(({ value, icon: Icon, labelKey }) => (
              <button
                key={value}
                type="button"
                aria-pressed={view === value}
                title={t(labelKey)}
                aria-label={t(labelKey)}
                onClick={() => chooseView(value)}
                className={cn(
                  'inline-flex h-8 items-center justify-center gap-1.5 rounded-md px-2.5 text-xs font-medium transition-colors',
                  view === value
                    ? 'bg-background text-foreground shadow-xs'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                <Icon className="size-3.5" aria-hidden="true" />
                <span className="hidden sm:inline">{t(labelKey)}</span>
              </button>
            ))}
          </div>
        )}
      </CardHeader>
      <CardContent>
        {!hasHoldings ? (
          <p className="text-sm text-muted-foreground">
            {t('analysis.diversification.noHoldings')}
          </p>
        ) : (
          <div className="space-y-6">
            {hasBreakdowns ? (
              <>
                <BreakdownBar
                  breakdown={data.sectors}
                  labelNs="holdings.insight.sectorNames"
                  title={t('analysis.diversification.sectors')}
                  securities={data.securities}
                  view={view}
                />
                <BreakdownBar
                  breakdown={data.countries}
                  labelNs="holdings.insight.countryNames"
                  title={t('analysis.diversification.countries')}
                  securities={data.securities}
                  view={view}
                />
              </>
            ) : (
              <div className="rounded-xl border border-dashed border-border/70 bg-muted/20 p-5 text-sm text-muted-foreground">
                {t('analysis.diversification.pendingProfiles')}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2 border-t border-border/60 pt-4 text-xs text-muted-foreground">
              <span>
                {t('analysis.diversification.coverage', {
                  value: formatPercent(data.coveragePercent, { fractionDigits: 0 }),
                })}
              </span>
              {data.unclassifiedValueEur > 0 && (
                <Badge variant="outline">
                  <CurrencyDisplay value={data.unclassifiedValueEur} />{' '}
                  {t('analysis.diversification.unclassified')}
                </Badge>
              )}
            </div>

            {data.unclassified.length > 0 && (
              <UnclassifiedList lines={data.unclassified} onEdit={setEditing} />
            )}
          </div>
        )}
      </CardContent>

      <HoldingClassificationModal
        open={editing !== null}
        accountId={editing?.accountId ?? null}
        ticker={editing?.ticker ?? null}
        name={editing?.name}
        onClose={() => setEditing(null)}
      />
    </Card>
  )
}
