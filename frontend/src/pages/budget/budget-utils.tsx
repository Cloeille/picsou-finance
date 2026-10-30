import { useTranslation } from 'react-i18next'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import type { CashflowPeriod } from '@/types/api'
import { FALLBACK_COLOR } from './budget-meta'

/**
 * Shared presentational helpers for the Budget module. Static lookup tables live in
 * the component-free `budget-meta.ts` so this file exports only components (Fast Refresh).
 */

/** A small round colour swatch — used next to category names everywhere. */
export function ColorDot({ color, className }: { color?: string | null; className?: string }) {
  return (
    <span
      className={className ?? 'inline-block size-2.5 shrink-0 rounded-full'}
      style={{ backgroundColor: color || FALLBACK_COLOR }}
    />
  )
}

/** Cycle / YTD segmented toggle, shared by the cashflow and allocation views. */
export function PeriodToggle({
  value,
  onChange,
}: {
  value: CashflowPeriod
  onChange: (period: CashflowPeriod) => void
}) {
  const { t } = useTranslation()
  return (
    <Tabs value={value} onValueChange={(v) => onChange(v as CashflowPeriod)}>
      <TabsList>
        <TabsTrigger value="CYCLE">{t('budget.period.cycle')}</TabsTrigger>
        <TabsTrigger value="YTD">{t('budget.period.ytd')}</TabsTrigger>
      </TabsList>
    </Tabs>
  )
}
