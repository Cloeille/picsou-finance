import { useTranslation } from 'react-i18next'
import { cn } from '@/lib/utils'

export type TimeRange = '24H' | '7D' | '1M' | '3M' | 'YTD' | '1Y' | 'ALL'

interface TimeRangeSelectorProps {
  value: TimeRange
  onChange: (range: TimeRange) => void
}

const RANGES: TimeRange[] = ['24H', '7D', '1M', '3M', 'YTD', '1Y', 'ALL']

export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  const { t } = useTranslation()

  return (
    <div
      role="group"
      className="inline-flex flex-wrap items-center gap-0.5 rounded-lg border border-border/60 bg-background/80 p-1"
    >
      {RANGES.map(range => (
        <button
          key={range}
          type="button"
          aria-pressed={value === range}
          title={t(`dashboard.rangeTitles.${range}`)}
          onClick={() => onChange(range)}
          className={cn(
            'inline-flex h-7 items-center justify-center whitespace-nowrap rounded-md px-2.5 text-xs font-medium transition-[background-color,color] sm:text-sm',
            value === range
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {t(`dashboard.ranges.${range}`)}
        </button>
      ))}
    </div>
  )
}
