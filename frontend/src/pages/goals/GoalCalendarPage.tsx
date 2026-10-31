import { useState, useMemo, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useGoal, useGoalMonths, useSetMonthOverride, useDeleteMonthOverride, useSetManualContribution, useDeleteManualContribution, useExtendGoalHistory, useExtendGoalHistoryByMonth } from '@/features/goals/hooks'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import { NumericInput } from '@/components/shared/NumericInput'
import { PageHeader } from '@/components/shared/PageHeader'
import { ErrorState } from '@/components/shared/ErrorState'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from '@/components/ui/sheet'
import { Separator } from '@/components/ui/separator'
import { ArrowLeft, Calendar, LayoutGrid, Clock, Loader2, Plus } from 'lucide-react'
import { cn, localeFromLanguage, parseAmount } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import type { GoalMonthEntry } from '@/types/api'

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function isPastOrCurrent(ym: string): boolean {
  const now = new Date()
  const [y, m] = ym.split('-').map(Number)
  return y < now.getFullYear() || (y === now.getFullYear() && m <= now.getMonth() + 1)
}

function monthDate(ym: string): Date {
  const [year, month] = ym.split('-').map(Number)
  return new Date(year, month - 1, 1)
}

function capitalizeLocalized(value: string, locale: string): string {
  const [first = '', ...rest] = Array.from(value)
  return first.toLocaleUpperCase(locale) + rest.join('')
}

function monthAbbr(ym: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { month: 'short' }).format(monthDate(ym))
}

function fullMonthName(ym: string, locale: string): string {
  const label = new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(monthDate(ym))
  return capitalizeLocalized(label, locale)
}

function groupByYear(months: GoalMonthEntry[]): { year: number; entries: GoalMonthEntry[] }[] {
  const map = new Map<number, GoalMonthEntry[]>()
  for (const e of months) {
    const year = parseInt(e.yearMonth.split('-')[0])
    if (!map.has(year)) map.set(year, [])
    map.get(year)!.push(e)
  }
  return Array.from(map.entries())
    .sort(([a], [b]) => a - b)
    .map(([year, entries]) => ({ year, entries }))
}

// ---------------------------------------------------------------------------
// Progress Ring
// ---------------------------------------------------------------------------

const COLORS = {
  success: 'oklch(from var(--primary) calc(l + 0.2) c h)',
  warning: '#f59e0b',
  destructive: 'oklch(from var(--destructive) calc(l + 0.15) c h)',
  muted: 'var(--muted)',
  mutedFg: 'var(--muted-foreground)',
} as const

function ProgressRing({ pct, color, size = 80, stroke = 9 }: {
  pct: number; color: string; size?: number; stroke?: number
}) {
  const r = (size - stroke) / 2
  const circ = 2 * Math.PI * r
  const isOverflow = pct > 1
  const baseFilled = Math.min(1, Math.max(0, pct)) * circ
  const bonusFilled = isOverflow ? Math.min(pct - 1, 1) * circ : 0
  const bonusIsFullCircle = bonusFilled >= circ - 0.1

  return (
    <svg width={size} height={size} overflow="visible" style={{ transform: 'rotate(-90deg)' }}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--muted)" strokeWidth={stroke} />
      <circle
        cx={size / 2} cy={size / 2} r={r} fill="none"
        stroke={color} strokeWidth={stroke}
        strokeDasharray={`${baseFilled} ${circ}`}
        strokeLinecap={isOverflow ? 'butt' : 'round'}
      />
      {isOverflow && (
        <circle
          cx={size / 2} cy={size / 2} r={r} fill="none"
          stroke="#818cf8" strokeWidth={stroke + 3}
          strokeDasharray={`${bonusFilled} ${circ}`}
          strokeLinecap={bonusIsFullCircle ? 'butt' : 'round'}
        />
      )}
    </svg>
  )
}

function getProgressColor(entry: GoalMonthEntry, isPast: boolean): { color: string; pct: number; label: string; textColor: string } {
  if (!isPast) return { color: COLORS.muted, pct: 0, label: '···', textColor: COLORS.mutedFg }
  if (entry.effective == null) return { color: COLORS.muted, pct: 0, label: '–', textColor: COLORS.mutedFg }
  const obj = entry.objective ?? 0
  const ratio = obj > 0 ? entry.effective / obj : 1
  if (ratio >= 1) return { color: COLORS.success, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.success }
  if (ratio >= 0.6) return { color: COLORS.warning, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.warning }
  return { color: COLORS.destructive, pct: ratio, label: `${Math.round(ratio * 100)}%`, textColor: COLORS.destructive }
}


// ---------------------------------------------------------------------------
// View: Year Grid (default)
// ---------------------------------------------------------------------------

function YearGridView({ months, selectedYm, onSelect, onAddPreviousMonth, isAddingMonth }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
  onAddPreviousMonth: () => void; isAddingMonth: boolean
}) {
  const { t, i18n } = useTranslation()
  const money = useMoney()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const currency = 'EUR'
  const years = useMemo(() => groupByYear((months ?? []).filter(e => isPastOrCurrent(e.yearMonth))), [months])
  const earliestYear = years[0]?.year

  return (
    <div className="space-y-4">
      {years.map(({ year, entries }) => (
        <Card key={year}>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-bold tracking-wider uppercase text-muted-foreground">{year}</CardTitle>
          </CardHeader>
          <CardContent className="pb-4 pt-3 px-4">
            <div className="overflow-x-auto">
              <div className="flex gap-3 min-w-max">
                {year === earliestYear && (
                  <button
                    type="button"
                    onClick={onAddPreviousMonth}
                    disabled={isAddingMonth}
                    title={t('goals.addPreviousMonth')}
                    aria-label={t('goals.addPreviousMonth')}
                    className="flex flex-col items-center justify-center gap-[9px] rounded-xl border border-dashed border-border px-2 py-3 transition-colors cursor-pointer min-w-[90px] text-muted-foreground hover:bg-accent/50 hover:text-foreground disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {isAddingMonth
                      ? <Loader2 className="size-5 animate-spin" />
                      : <Plus className="size-5" />}
                    <span className="text-[10px] font-semibold uppercase tracking-[0.1em] leading-tight text-center">
                      {t('goals.addPreviousMonth')}
                    </span>
                  </button>
                )}
                {entries.map(entry => {
                  const isSelected = selectedYm === entry.yearMonth
                  const hasOverride = entry.override != null
                  const hasManual = entry.manualActual != null
                  const { color, pct, textColor } = getProgressColor(entry, true)
                  const effectiveText = entry.effective != null ? money.compact(entry.effective, { currency }) : null
                  const percentLabel = effectiveText != null
                    ? (pct > 1 ? `+${Math.round((pct - 1) * 100)}%` : `${Math.round(pct * 100)}%`)
                    : null

                  return (
                    <button
                      key={entry.yearMonth}
                      onClick={() => onSelect(entry.yearMonth)}
                      className={`flex flex-col items-center gap-[9px] rounded-xl border px-2 py-3 transition-colors cursor-pointer min-w-[90px] ${
                        isSelected ? 'border-primary bg-accent' : 'border-border hover:bg-accent/50'
                      }`}
                    >
                      <span className="text-[10px] font-semibold uppercase tracking-[0.1em] text-muted-foreground leading-none">
                        {monthAbbr(entry.yearMonth, locale)}
                      </span>
                      <div className="relative">
                        {hasOverride && !hasManual && (
                          <div className="absolute top-[3px] right-[3px] z-10 w-[9px] h-[9px] rounded-full bg-violet-600 border-2 border-background" />
                        )}
                        {hasManual && (
                          <div className="absolute top-[3px] right-[3px] z-10 w-[9px] h-[9px] rounded-full bg-blue-500 border-2 border-background" />
                        )}
                        <ProgressRing pct={pct} color={color} />
                        <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none gap-[2px]">
                          {effectiveText != null ? (
                            <>
                              <span className="text-[11px] font-bold leading-none" style={{ color: textColor }}>
                                {effectiveText}
                              </span>
                              <span className="text-[9px] leading-none" style={{ color: textColor }}>
                                {percentLabel}
                              </span>
                            </>
                          ) : (
                            <span className="text-[13px] font-bold leading-none" style={{ color: COLORS.mutedFg }}>
                              –
                            </span>
                          )}
                        </div>
                      </div>
                      <span className="text-[10px] text-muted-foreground leading-none">
                        obj.&nbsp;{money.compact(entry.objective, { currency })}
                      </span>
                    </button>
                  )
                })}
              </div>
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// View: Timeline
// ---------------------------------------------------------------------------

function TimelineView({ months, selectedYm, onSelect }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
}) {
  const { i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const sorted = useMemo(() =>
    [...(months ?? [])].filter(e => isPastOrCurrent(e.yearMonth)).reverse(),
    [months]
  )

  return (
    <Card>
      <CardContent className="p-4 space-y-1">
        {sorted.map(entry => {
          const isSelected = selectedYm === entry.yearMonth
          const obj = entry.objective ?? 0
          const eff = entry.effective ?? 0
          const pct = obj > 0 ? Math.min(100, (eff / obj) * 100) : (eff > 0 ? 100 : 0)
          const barClass = pct >= 100 ? 'bg-primary' : pct >= 60 ? 'bg-primary/60' : 'bg-destructive/50'

          return (
            <button
              key={entry.yearMonth}
              onClick={() => onSelect(entry.yearMonth)}
              className={`w-full flex items-center gap-3 rounded-md px-3 py-2.5 text-left transition-colors ${isSelected ? 'bg-accent' : 'hover:bg-accent/50'}`}
            >
              <span className="w-28 shrink-0 text-sm font-medium">{fullMonthName(entry.yearMonth, locale)}</span>
              <div className="flex-1 h-2 rounded-full bg-muted overflow-hidden">
                <div className={`h-full rounded-full transition-[width] ${barClass}`} style={{ width: `${pct}%` }} />
              </div>
              <span className="w-12 shrink-0 text-sm font-bold text-right">
                {entry.effective != null ? `${Math.round(pct)}%` : '—'}
              </span>
              <span className="w-20 shrink-0 text-right text-sm text-muted-foreground">
                {entry.effective != null ? <CurrencyDisplay value={entry.effective} /> : ''}
              </span>
              {entry.manualActual != null && (
                <Badge variant="secondary" className="text-[10px] px-1.5 py-0">manu.</Badge>
              )}
              {entry.override != null && (
                <Badge variant="outline" className="text-[10px] px-1.5 py-0">modif.</Badge>
              )}
            </button>
          )
        })}
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// View: Calendar Grid
// ---------------------------------------------------------------------------

function CalendarGridView({ months, selectedYm, onSelect }: {
  months: GoalMonthEntry[]; selectedYm: string | null; onSelect: (ym: string) => void
}) {
  const { i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const years = useMemo(() => groupByYear(months), [months])

  return (
    <div className="space-y-4">
      {years.map(({ year, entries }) => (
        <Card key={year}>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-bold tracking-wider uppercase text-muted-foreground">{year}</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-4 gap-2">
              {entries.map(entry => {
                const isSelected = selectedYm === entry.yearMonth
                const isPast = isPastOrCurrent(entry.yearMonth)
                const { pct, label, textColor } = getProgressColor(entry, isPast)

                return (
                  <button
                    key={entry.yearMonth}
                    onClick={() => onSelect(entry.yearMonth)}
                    className={`rounded-lg border p-3 text-left transition-colors ${isSelected ? 'border-primary bg-accent' : 'border-border hover:bg-accent/50'}`}
                  >
                    <div className="flex items-center justify-between mb-1">
                      <span className="text-sm font-medium">{monthAbbr(entry.yearMonth, locale)}</span>
                      <span className="text-xs font-bold" style={{ color: textColor }}>{label}</span>
                    </div>
                    {entry.effective != null ? (
                      <div>
                        <div className="h-1.5 rounded-full bg-muted overflow-hidden mb-1">
                          <div className="h-full rounded-full bg-primary/70 transition-[width]" style={{ width: `${Math.min(100, pct * 100)}%` }} />
                        </div>
                        <div className="flex justify-between text-[11px] text-muted-foreground">
                          <span>obj. <CurrencyDisplay value={entry.objective} className="text-[11px]" /></span>
                          <span><CurrencyDisplay value={entry.effective} className="text-[11px]" /></span>
                        </div>
                      </div>
                    ) : (
                      <p className="text-xs text-muted-foreground">—</p>
                    )}
                    {(entry.manualActual != null || entry.override != null) && (
                      <div className="flex gap-1 mt-1.5">
                        {entry.manualActual != null && <Badge variant="secondary" className="text-[9px] px-1 py-0">manu.</Badge>}
                        {entry.override != null && <Badge variant="outline" className="text-[9px] px-1 py-0 text-violet-500 border-violet-300">modif.</Badge>}
                      </div>
                    )}
                  </button>
                )
              })}
            </div>
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Month Detail Panel
// ---------------------------------------------------------------------------

function MonthDetailPanel({ goalId, entry, onClose, className }: {
  goalId: number; entry: GoalMonthEntry; onClose: () => void; className?: string
}) {
  const { t, i18n } = useTranslation()
  const money = useMoney()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)
  const setOverride = useSetMonthOverride()
  const deleteOverride = useDeleteMonthOverride()
  const setManual = useSetManualContribution()
  const deleteManual = useDeleteManualContribution()

  const [overrideValue, setOverrideValue] = useState(entry.override?.toString() ?? '')
  const [manualValue, setManualValue] = useState(entry.manualActual?.toString() ?? '')

  const busy = setOverride.isPending || deleteOverride.isPending || setManual.isPending || deleteManual.isPending

  return (
    <Card className={className}>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <CardTitle className="text-base">{fullMonthName(entry.yearMonth, locale)}</CardTitle>
            {entry.override != null && (
              <Badge variant="outline" className="text-[10px]">{t('goals.modified')}</Badge>
            )}
            {entry.manualActual != null && (
              <Badge variant="secondary" className="text-[10px]">{t('goals.declared')}</Badge>
            )}
          </div>
          <Button variant="ghost" size="icon" onClick={onClose}>
            &times;
          </Button>
        </div>
      </CardHeader>
      <Separator />
      <CardContent className="pt-4">
        <div className="grid grid-cols-1 gap-6">
          {/* Objective override */}
          <div className="space-y-3">
            <Label>{t('goals.monthlyObjective')}</Label>
            <p className="text-sm text-muted-foreground">
              {t('goals.calculatedObjective')}: <CurrencyDisplay value={entry.objective} />
            </p>
            <div className="relative">
              <NumericInput
                value={overrideValue}
                onChange={e => setOverrideValue(e.target.value)}
                placeholder={money.quantity(entry.objective)}
                className="pr-8"
              />
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground pointer-events-none">€</span>
            </div>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setOverrideValue(String(entry.objective))}
              >
                {t('goals.calculatedObjective')}
              </Button>
              <Button
                size="sm"
                disabled={busy}
                onClick={() => {
                  const amount = parseAmount(overrideValue)
                  if (isNaN(amount) || amount < 0) return
                  setOverride.mutate({ id: goalId, ym: entry.yearMonth, amount })
                }}
              >
                {setOverride.isPending && <Loader2 className="size-3 animate-spin" />}
                {t('goals.saveOverride')}
              </Button>
              {entry.override != null && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => deleteOverride.mutate({ id: goalId, ym: entry.yearMonth })}
                >
                  {t('goals.resetOverride')}
                </Button>
              )}
            </div>
          </div>

          {/* Manual contribution */}
          <div className="space-y-3">
            <Label>{t('goals.actual')}</Label>
            <p className="text-sm text-muted-foreground">
              {t('goals.fromAccounts')}: {entry.actual != null ? <CurrencyDisplay value={entry.actual} /> : t('goals.noData')}
            </p>
            <div className="relative">
              <NumericInput
                value={manualValue}
                onChange={e => setManualValue(e.target.value)}
                placeholder={entry.actual != null ? money.quantity(entry.actual) : ''}
                className="pr-8"
              />
              <span className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground pointer-events-none">€</span>
            </div>
            <div className="flex gap-2">
              {entry.actual != null && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setManualValue(entry.actual!.toString())}
                >
                  {t('goals.fromAccounts')}
                </Button>
              )}
              <Button
                size="sm"
                disabled={busy}
                onClick={() => {
                  const amount = parseAmount(manualValue)
                  if (isNaN(amount) || amount < 0) return
                  setManual.mutate({ id: goalId, ym: entry.yearMonth, amount })
                }}
              >
                {setManual.isPending && <Loader2 className="size-3 animate-spin" />}
                {t('goals.saveManual')}
              </Button>
              {entry.manualActual != null && (
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => deleteManual.mutate({ id: goalId, ym: entry.yearMonth })}
                >
                  {t('goals.resetManual')}
                </Button>
              )}
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// Goal Calendar Page
// ---------------------------------------------------------------------------

export function GoalCalendarPage() {
  const { id } = useParams<{ id: string }>()
  const goalId = Number(id)
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()
  const locale = localeFromLanguage(i18n.resolvedLanguage ?? i18n.language)

  const { data: goal, isLoading: goalLoading, error: goalError } = useGoal(goalId)
  const { data: months, isLoading: monthsLoading } = useGoalMonths(goalId)
  const extendHistory = useExtendGoalHistory()
  const extendHistoryByMonth = useExtendGoalHistoryByMonth()

  const [viewMode, setViewMode] = useState<'grid' | 'timeline' | 'calendar'>('grid')
  const [selectedYm, setSelectedYm] = useState<string | null>(null)

  // Track the `lg` breakpoint so the bottom sheet only opens on mobile/tablet —
  // on desktop the sticky side panel already shows the selected month.
  const [isDesktop, setIsDesktop] = useState(false)
  useEffect(() => {
    const mql = window.matchMedia('(min-width: 1024px)')
    const onChange = () => setIsDesktop(mql.matches)
    onChange()
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  const selectedEntry = selectedYm ? (months ?? []).find(e => e.yearMonth === selectedYm) ?? null : null

  // The earliest rendered year drives the "+ Add <year>" backfill button.
  const earliestYear = (months ?? []).length
    ? Math.min(...(months ?? []).map(e => parseInt(e.yearMonth.split('-')[0])))
    : new Date().getFullYear()
  const yearToAdd = earliestYear - 1

  if (goalLoading || monthsLoading) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('goals.calendar')} />
        <Skeleton className="h-9 w-72" />
        <Card>
          <CardContent className="pb-4 pt-3 px-4">
            <div className="overflow-x-auto">
              <div className="flex gap-3 min-w-max">
                {Array.from({ length: 12 }).map((_, i) => (
                  <div key={i} className="flex flex-col items-center gap-[9px] rounded-xl border border-border px-2 py-3 min-w-[90px]">
                    <Skeleton className="h-2.5 w-8" />
                    <Skeleton className="size-[80px] rounded-full" />
                    <Skeleton className="h-2.5 w-14" />
                  </div>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (goalError || !goal) {
    return (
      <div className="space-y-6">
        <PageHeader title={t('goals.calendar')} />
        <ErrorState message={goalError?.message ?? t('common.notFound')} onRetry={() => window.location.reload()} />
      </div>
    )
  }

  const pastMonths = (months ?? []).filter(e => isPastOrCurrent(e.yearMonth))
  const achievedCount = pastMonths.filter(
    e => e.effective != null && e.objective != null && e.effective >= e.objective
  ).length

  return (
    <div className="space-y-6">
      <PageHeader
        title={t('goals.calendarTitle', { name: goal.name })}
        actions={
          <Button variant="outline" size="sm" onClick={() => navigate('/goals')}>
            <ArrowLeft className="size-4" />
            {t('goals.title')}
          </Button>
        }
      />

      {/* Summary bar */}
      <div className="flex items-center gap-3 text-sm">
        <span className="text-muted-foreground">
          {t('goals.monthlyObjective')}: <CurrencyDisplay value={goal.monthlyNeeded ?? 0} />
        </span>
        {pastMonths.length > 0 && (
          <Badge variant="secondary">
            {achievedCount}/{pastMonths.length} {t('goals.achieved')}
          </Badge>
        )}
      </div>

      {/* View toggle with shadcn Tabs */}
      <Tabs value={viewMode} onValueChange={(v) => setViewMode(v as typeof viewMode)}>
        <TabsList>
          <TabsTrigger value="grid" className="gap-1.5">
            <LayoutGrid className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewGrid')}</span>
          </TabsTrigger>
          <TabsTrigger value="timeline" className="gap-1.5">
            <Clock className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewTimeline')}</span>
          </TabsTrigger>
          <TabsTrigger value="calendar" className="gap-1.5">
            <Calendar className="size-4" />
            <span className="hidden sm:inline">{t('goals.viewCalendar')}</span>
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {/* Backfill: extend history one year before the goal's creation */}
      <Card className="flex flex-row items-center justify-between gap-3 px-4 py-2.5">
        <p className="text-sm text-muted-foreground">{t('goals.backfillHint')}</p>
        <Button
          variant="outline"
          size="sm"
          className="shrink-0"
          disabled={extendHistory.isPending}
          onClick={() => extendHistory.mutate(goalId)}
        >
          {extendHistory.isPending && <Loader2 className="size-3 animate-spin" />}
          {t('goals.addYear', { year: yearToAdd })}
        </Button>
      </Card>

      {/* Two-column layout: active view (left) + sticky edit panel (right, desktop) */}
      <div className="grid gap-6 lg:grid-cols-[1fr_360px] items-start">
        <div className="min-w-0 space-y-4">
          {viewMode === 'grid' && (
            <YearGridView
              months={months ?? []}
              selectedYm={selectedYm}
              onSelect={setSelectedYm}
              onAddPreviousMonth={() => extendHistoryByMonth.mutate(goalId)}
              isAddingMonth={extendHistoryByMonth.isPending}
            />
          )}
          {viewMode === 'timeline' && (
            <TimelineView months={months ?? []} selectedYm={selectedYm} onSelect={setSelectedYm} />
          )}
          {viewMode === 'calendar' && (
            <CalendarGridView months={months ?? []} selectedYm={selectedYm} onSelect={setSelectedYm} />
          )}
        </div>

        {/* Desktop: sticky side panel (does not collapse → no layout shift) */}
        <div className="hidden lg:block lg:sticky lg:top-4">
          {selectedEntry ? (
            <MonthDetailPanel goalId={goalId} entry={selectedEntry} onClose={() => setSelectedYm(null)} />
          ) : (
            <Card className="flex items-center justify-center p-6">
              <p className="text-center text-sm text-muted-foreground">{t('goals.clickMonthHint')}</p>
            </Card>
          )}
        </div>
      </div>

      {/* Mobile/tablet: bottom sheet */}
      <Sheet
        open={!!selectedEntry && !isDesktop}
        onOpenChange={(open) => { if (!open) setSelectedYm(null) }}
      >
        <SheetContent
          side="bottom"
          showCloseButton={false}
          className="max-h-[85vh] overflow-y-auto rounded-t-2xl p-4"
        >
          <SheetHeader className="sr-only">
            <SheetTitle>{selectedEntry ? fullMonthName(selectedEntry.yearMonth, locale) : ''}</SheetTitle>
            <SheetDescription>{t('goals.calendar')}</SheetDescription>
          </SheetHeader>
          {selectedEntry && (
            <MonthDetailPanel
              goalId={goalId}
              entry={selectedEntry}
              onClose={() => setSelectedYm(null)}
              className={cn('border-0 bg-transparent shadow-none')}
            />
          )}
        </SheetContent>
      </Sheet>
    </div>
  )
}
