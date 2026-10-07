import { ChevronLeft, ChevronRight, ChevronDown } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import { useBudgetSettings } from '@/features/budget/hooks'
import { getLocale } from '@/lib/utils'
import type { CashflowPeriod } from '@/types/api'
import { pad2, addDays, yearOf, yearAnchor, cycleAnchor } from './period-navigator-helpers'

/** Today as YYYY-MM-DD in local time. */
function getTodayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`
}

// ─── Component ────────────────────────────────────────────────────────────────

export function PeriodNavigator({
  period,
  from,
  to,
  onAnchorChange,
}: {
  period: CashflowPeriod
  from?: string
  to?: string
  onAnchorChange: (anchorIso: string) => void
}) {
  const { t } = useTranslation()
  const { data: settings } = useBudgetSettings()
  const cycleStartDay = settings?.cycleStartDay ?? 1

  const todayIso = getTodayIso()
  const currentYear = Number(todayIso.slice(0, 4))
  const locale = getLocale()
  const effective = from ?? todayIso

  // ─── Label ─────────────────────────────────────────────────────────────────
  const label: string = (() => {
    if (period === 'CYCLE') {
      const [ey, em, ed] = effective.split('-').map(Number)
      return new Intl.DateTimeFormat(locale, {
        month: 'long',
        year: 'numeric',
        timeZone: 'UTC',
      }).format(new Date(Date.UTC(ey, em - 1, ed)))
    }
    return effective.slice(0, 4)
  })()

  // ─── Next disabled: at or beyond the current period (or not yet resolved) ──
  const nextDisabled = !to || to >= todayIso

  // ─── Navigation handlers ───────────────────────────────────────────────────
  function handlePrev() {
    if (period === 'CYCLE') {
      onAnchorChange(addDays(effective, -1))
    } else {
      onAnchorChange(yearAnchor(yearOf(effective) - 1, currentYear, todayIso))
    }
  }

  function handleNext() {
    if (period === 'CYCLE') {
      onAnchorChange(addDays(to ?? todayIso, 1))
    } else {
      onAnchorChange(yearAnchor(yearOf(effective) + 1, currentYear, todayIso))
    }
  }

  // ─── Jump items ────────────────────────────────────────────────────────────
  const todayYear = Number(todayIso.slice(0, 4))
  const todayMonth = Number(todayIso.slice(5, 7))

  const jumpItems =
    period === 'CYCLE'
      ? Array.from({ length: 24 }, (_, i) => {
          // Subtract i months from today's year-month.
          const totalMonths = todayYear * 12 + (todayMonth - 1) - i
          const y = Math.floor(totalMonths / 12)
          const m = (totalMonths % 12) + 1
          const itemLabel = new Intl.DateTimeFormat(locale, {
            month: 'long',
            year: 'numeric',
            timeZone: 'UTC',
          }).format(new Date(Date.UTC(y, m - 1, 1)))
          return { key: `${y}-${pad2(m)}`, label: itemLabel, anchor: cycleAnchor(y, m, cycleStartDay) }
        })
        .filter(({ anchor }) => anchor <= todayIso)
      : Array.from({ length: 6 }, (_, i) => {
          const y = currentYear - i
          return { key: String(y), label: String(y), anchor: yearAnchor(y, currentYear, todayIso) }
        })

  return (
    <div className="flex items-center gap-1">
      {/* Prev */}
      <Button
        variant="ghost"
        size="icon-lg"
        aria-label={t('budget.period.prev')}
        onClick={handlePrev}
      >
        <ChevronLeft />
      </Button>

      {/* Jump dropdown — the label doubles as the trigger */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" className="h-8 gap-1 px-2 text-sm font-medium">
            {label}
            <ChevronDown className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent className="max-h-72 overflow-y-auto">
          {jumpItems.map((item) => (
            <DropdownMenuItem key={item.key} onSelect={() => onAnchorChange(item.anchor)}>
              {item.label}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>

      {/* Next */}
      <Button
        variant="ghost"
        size="icon-lg"
        aria-label={t('budget.period.next')}
        onClick={handleNext}
        disabled={nextDisabled}
      >
        <ChevronRight />
      </Button>
    </div>
  )
}
