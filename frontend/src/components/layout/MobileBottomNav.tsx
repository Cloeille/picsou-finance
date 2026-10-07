import { NavLink, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import type { LucideIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { CLASSIC_SETTINGS_NAV_ITEM, NAV_ITEMS } from './sidebar-nav-items'
import { LanguageToggle } from './LanguageToggle'

/**
 * The bar carries the same routes as the sidebar, plus Settings — which on mobile has no other
 * entry point, since the desktop sidebar reaches it through the bottom account row.
 *
 * It used to keep its own hardcoded copy of the route list with the brand logo centred between
 * two pairs of items. Adding a fifth route left no honest way to keep that symmetry, and a
 * private copy of the list was one more place to forget a route, so both went: one registry,
 * evenly spaced items, and the logo stays on the desktop sidebar where it has room. The language
 * toggle keeps the trailing position it held beside the logo, next to Settings it belongs with.
 */
const MOBILE_NAV_ITEMS = [...NAV_ITEMS, CLASSIC_SETTINGS_NAV_ITEM] as const

function MobileNavItem({
  to,
  end,
  icon: Icon,
  label,
}: {
  to: string
  end: boolean
  icon: LucideIcon
  label: string
}) {
  const location = useLocation()
  const isActive = end
    ? location.pathname === to
    : location.pathname.startsWith(to)

  return (
    <NavLink
      to={to}
      end={end}
      title={label}
      className={cn(
        'flex size-10 items-center justify-center rounded-lg bg-muted text-muted-foreground transition-colors',
        isActive && 'ring-1 ring-border bg-muted text-foreground',
      )}
    >
      <Icon className="size-5" aria-hidden="true" />
    </NavLink>
  )
}

export function MobileBottomNav() {
  const { t } = useTranslation()

  return (
    <nav className="fixed bottom-4 inset-x-4 z-50 md:hidden">
      <div className="flex items-center justify-between rounded-xl bg-background px-3 py-3 ring-1 ring-border">
        {MOBILE_NAV_ITEMS.map((item) => (
          <MobileNavItem
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            icon={item.icon}
            label={t(item.labelKey)}
          />
        ))}
        <LanguageToggle />
      </div>
      {/* iOS safe area */}
      <div className="h-[env(safe-area-inset-bottom)]" />
    </nav>
  )
}
