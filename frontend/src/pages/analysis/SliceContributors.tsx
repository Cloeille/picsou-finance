import { useTranslation } from 'react-i18next'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import type { DiversificationSecurity, DiversificationSlice } from '@/types/api'

/**
 * The holdings behind one slice — the answer to "why is France 8.4 %?".
 *
 * <p>A panel rather than only a tooltip: a tooltip cannot be reached on a touch screen, and the
 * list is the part worth reading slowly. Hovering still previews it; this is what a click pins.
 */
export function SliceContributors({
  slice,
  securities,
  labelNs,
  color,
}: {
  slice: DiversificationSlice
  securities: DiversificationSecurity[]
  labelNs: string
  color: string
}) {
  const { t } = useTranslation()
  const byTicker = new Map(securities.map((s) => [s.ticker, s]))

  return (
    <div className="rounded-lg bg-muted p-3">
      <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
        <span className="flex items-center gap-2 text-sm text-foreground">
          <span
            className="size-2 shrink-0 rounded-full"
            style={{ backgroundColor: color }}
            aria-hidden="true"
          />
          {t(`${labelNs}.${slice.label}`, slice.label)}
        </span>
        <span className="text-xs text-muted-foreground">
          <CurrencyDisplay value={slice.valueEur} className="text-foreground" />{' '}
          {t('analysis.drilldown.holdingCount', { count: slice.contributorCount })}
        </span>
      </div>

      <ul className="space-y-1">
        {slice.contributors.map((c, i) => {
          // The folded tail has no ticker, and the API omits the null rather than sending it.
          const security = c.ticker == null ? null : byTicker.get(c.ticker)
          return (
            <li
              key={c.ticker ?? `folded-${i}`}
              className="flex flex-wrap items-baseline justify-between gap-2 text-xs"
            >
              <span className="min-w-0 text-foreground">
                {/* A null ticker is the folded tail, not a security nobody named. */}
                {c.ticker == null
                  ? t('analysis.drilldown.others', {
                      count: slice.contributorCount - slice.contributors.length + 1,
                    })
                  : (security?.name ?? c.ticker)}
                {c.ticker != null && security?.name && (
                  <span className="ml-2 text-muted-foreground">{c.ticker}</span>
                )}
              </span>
              <span className="flex items-baseline gap-2 text-muted-foreground">
                <CurrencyDisplay value={c.valueEur} />
                <span className="tabular-nums">{c.sharePercent.toFixed(1)}%</span>
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
