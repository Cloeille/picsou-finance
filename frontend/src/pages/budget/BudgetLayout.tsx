import { useTranslation } from 'react-i18next'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { PageHeader } from '@/components/shared/PageHeader'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useUncategorized } from '@/features/budget/hooks'
import { BUDGET_NAV } from './budget-nav'
import { BudgetPeriodProvider } from './BudgetPeriodContext'

/**
 * Shell for the whole Budget section. Replaces the former single-page / in-page-tabs
 * design with proper nested routes (`/budget`, `/budget/spending`, …) so each surface
 * is deep-linkable, lazily code-split and back-button friendly.
 *
 * The sub-nav is a horizontally-scrollable segmented control — it reads as a pill row
 * on desktop and stays fully reachable by swipe on narrow phones (no second bottom bar
 * competing with the global MobileBottomNav).
 */
export function BudgetLayout() {
  const { t } = useTranslation()
  const location = useLocation()
  const { data: uncategorized } = useUncategorized()
  const toReview = uncategorized?.length ?? 0

  return (
    <div>
      <PageHeader surtitle={t('budget.surtitle')} title={t('budget.title')} />

      <div className="-mx-1 overflow-x-auto px-1 pb-1">
        <nav className="flex w-max gap-1 rounded-lg bg-muted p-1 text-muted-foreground">
          {BUDGET_NAV.map(({ section, to, end, labelKey, icon: Icon, badge }) => (
            <NavLink
              key={section}
              to={to}
              end={end}
              className={({ isActive }) =>
                cn(
                  'inline-flex items-center gap-1.5 whitespace-nowrap rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-background text-foreground shadow-sm'
                    : 'hover:text-foreground',
                )
              }
            >
              <Icon className="size-4" />
              <span>{t(labelKey)}</span>
              {badge && toReview > 0 && (
                <Badge variant="secondary" className="ml-1">{toReview}</Badge>
              )}
            </NavLink>
          ))}
        </nav>
      </div>

      {/* Provider mounts outside the keyed div so the anchor survives tab switches.
          The keyed div still remounts per path (entrance animation, local page state reset)
          but BudgetPeriodProvider stays alive for the whole /budget section. */}
      <BudgetPeriodProvider>
        <div key={location.pathname} className="mt-4 animate-in fade-in-0 slide-in-from-bottom-1 duration-300">
          <Outlet />
        </div>
      </BudgetPeriodProvider>
    </div>
  )
}
