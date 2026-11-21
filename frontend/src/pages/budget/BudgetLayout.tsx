import { useTranslation } from 'react-i18next'
import { NavLink, Outlet, useLocation } from 'react-router'
import { PageHeader } from '@/components/shared/PageHeader'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useUncategorized } from '@/features/budget/hooks'
import { BUDGET_NAV } from './budget-nav'
import { BudgetPeriodProvider } from './BudgetPeriodContext'

/**
 * Shell for the whole Budget section. Uses nested routes (`/budget`, `/budget/spending`, …)
 * so each surface is deep-linkable, lazily code-split and back-button friendly.
 */
export function BudgetLayout() {
  const { t } = useTranslation()
  const location = useLocation()
  const { data: uncategorized } = useUncategorized()
  const toReview = uncategorized?.length ?? 0

  return (
    <div className="space-y-6">
      <PageHeader title={t('budget.title')} />

      <nav
        aria-label={t('budget.title')}
        className="flex w-full flex-wrap items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
      >
        {BUDGET_NAV.map(({ section, to, end, labelKey, badge }) => (
          <NavLink
            key={section}
            to={to}
            end={end}
            className={({ isActive }) =>
              cn(
                'inline-flex h-8 flex-auto items-center justify-center gap-2 rounded-md px-3.5 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
                isActive
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground',
              )
            }
          >
            {({ isActive }) => (
              <>
                <span>{t(labelKey)}</span>
                {badge && toReview > 0 && (
                  <Badge
                    variant={isActive ? 'secondary' : 'outline'}
                    className={cn(
                      'h-5 px-1.5 text-xs tabular-nums',
                      isActive && 'border-transparent bg-primary-foreground/20 text-primary-foreground',
                    )}
                  >
                    {toReview}
                  </Badge>
                )}
              </>
            )}
          </NavLink>
        ))}
      </nav>

      {/* Provider mounts outside the keyed div so the anchor survives tab switches.
          The keyed div still remounts per path (entrance animation, local page state reset)
          but BudgetPeriodProvider stays alive for the whole /budget section. */}
      <BudgetPeriodProvider>
        <div key={location.pathname} className="animate-in fade-in-0 slide-in-from-bottom-1 duration-300">
          <Outlet />
        </div>
      </BudgetPeriodProvider>
    </div>
  )
}
