import { NavLink, useLocation, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  CaretUpDown,
  Eye,
  EyeSlash,
  ArrowsClockwise,
  GearSix,
  SignOut,
  Shield,
} from '@phosphor-icons/react'
import type { Icon as LucideIcon } from '@phosphor-icons/react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Item, ItemContent, ItemDescription, ItemMedia, ItemTitle } from '@/components/ui/item'
import { useFamilyMembers } from '@/features/family/hooks'
import { selectSwitchableMembers } from '@/features/family/members'
import { useAuthStore } from '@/stores/auth-store'
import { selectIsAdvancedMode, useAppStore } from '@/stores/app-store'
import { useLogout } from '@/features/auth/hooks'
import { useProfileStore } from '@/stores/profile-store'
import { cn } from '@/lib/utils'
import { NAV_ITEMS, FAMILY_NAV_ITEM } from './sidebar-nav-items'
import { SidebarWhatsNewCard } from './SidebarWhatsNewCard'

function AccountMenuFooterItems({
  showAdmin,
  showLogout,
  hideAmounts,
  onToggleHideAmounts,
  onAdminClick,
  onLogoutClick,
  logoutPending,
}: {
  showAdmin: boolean
  showLogout: boolean
  hideAmounts: boolean
  onToggleHideAmounts: () => void
  onAdminClick: () => void
  onLogoutClick: () => void
  logoutPending: boolean
}) {
  const { t } = useTranslation()
  const location = useLocation()
  const settingsActive = location.pathname.startsWith('/settings')
  const syncActive = location.pathname.startsWith('/sync')
  return (
    <>
      <DropdownMenuItem asChild className={cn(settingsActive && 'bg-muted text-foreground')}>
        <NavLink to="/settings">
          <GearSix className="size-4" aria-hidden="true" />
          <span>{t('nav.settings')}</span>
        </NavLink>
      </DropdownMenuItem>

      <DropdownMenuItem asChild className={cn(syncActive && 'bg-muted text-foreground')}>
        <NavLink to="/sync">
          <ArrowsClockwise className="size-4" aria-hidden="true" />
          <span>{t('nav.sync')}</span>
        </NavLink>
      </DropdownMenuItem>

      <DropdownMenuItem onClick={onToggleHideAmounts}>
        {hideAmounts
          ? <Eye className="size-4" aria-hidden="true" />
          : <EyeSlash className="size-4" aria-hidden="true" />}
        <span>{t(hideAmounts ? 'nav.showAmounts' : 'nav.hideAmounts')}</span>
      </DropdownMenuItem>

      {showAdmin && (
        <DropdownMenuItem onClick={onAdminClick}>
          <Shield className="size-4" aria-hidden="true" />
          <span>{t('nav.admin')}</span>
        </DropdownMenuItem>
      )}

      {showLogout && (
        <>
          <DropdownMenuSeparator />

          <DropdownMenuItem onClick={onLogoutClick} disabled={logoutPending}>
            <SignOut className="size-4" aria-hidden="true" />
            <span>{t('settings.logout')}</span>
          </DropdownMenuItem>
        </>
      )}
    </>
  )
}

function NavItem({
  to,
  end,
  icon: Icon,
  title,
  description,
}: {
  to: string
  end?: boolean
  icon: LucideIcon
  title: string
  description: string
}) {
  const location = useLocation()
  const isActive = end
    ? location.pathname === to
    : location.pathname.startsWith(to)

  return (
    <Item
      asChild
      variant={isActive ? 'muted' : 'default'}
      className={cn(
        'min-h-[54px] rounded-xl px-3 py-2',
        isActive && 'bg-muted ring-1 ring-border',
      )}
    >
      <NavLink to={to} end={end}>
        <ItemMedia
          variant="icon"
          className={cn(
            'flex size-8 items-center justify-center rounded-lg bg-muted text-muted-foreground',
            isActive && 'text-foreground',
          )}
        >
          <Icon className="size-4" weight={isActive ? 'fill' : 'regular'} aria-hidden="true" />
        </ItemMedia>
        <ItemContent>
          <ItemTitle className="text-sm font-semibold">{title}</ItemTitle>
          <ItemDescription className="line-clamp-1 text-xs leading-4">{description}</ItemDescription>
        </ItemContent>
      </NavLink>
    </Item>
  )
}

export function AppSidebar() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)
  const demoMode = useAppStore((s) => s.demoMode)
  const desktopMode = useAppStore((s) => s.desktopMode)
  const desktopLockEnabled = useAppStore((s) => s.desktopLockEnabled)
  const isAdvancedMode = useAppStore(selectIsAdvancedMode)
  const hideAmounts = useAppStore((s) => s.hideAmounts)
  const toggleHideAmounts = useAppStore((s) => s.toggleHideAmounts)
  const profilePhotos = useAppStore((s) => s.profilePhotos)
  const activeMemberId = useProfileStore((s) => s.activeMemberId)
  const setActiveMember = useProfileStore((s) => s.setActiveMember)
  const canSwitchProfile = !demoMode && user?.role === 'ADMIN'
  const showAdmin = user?.role === 'ADMIN' && isAdvancedMode
  const showLogout = !desktopMode || desktopLockEnabled
  const { data: familyMembers = [] } = useFamilyMembers({ enabled: canSwitchProfile })
  const switchableMembers = selectSwitchableMembers(familyMembers)
  const activeMember = switchableMembers.find((member) => member.id === activeMemberId)
  const displayName = demoMode
    ? 'Demo'
    : user?.displayName ?? ''
  const activeDisplayName = activeMember?.displayName ?? (activeMemberId ? t('nav.unknownProfile') : displayName)
  const activeDescription = activeMemberId ? t('nav.managedProfile') : demoMode ? 'Demo' : t('nav.account')
  const initial = displayName.charAt(0).toUpperCase()
  const activeInitial = activeDisplayName.charAt(0).toUpperCase()
  const ownPhotoKey = user?.memberId != null ? String(user.memberId) : 'self'
  const ownPhoto = profilePhotos[ownPhotoKey]
  const activePhoto = activeMemberId != null ? profilePhotos[String(activeMemberId)] : ownPhoto
  const activeProfileValue = activeMemberId == null ? 'own' : `member-${activeMemberId}`
  const logoutMutation = useLogout()

  function handleProfileValueChange(value: string) {
    const nextMemberId = value === 'own'
      ? null
      : Number(value.replace('member-', ''))

    if (nextMemberId !== null && !Number.isFinite(nextMemberId)) return
    if (nextMemberId === activeMemberId) return

    setActiveMember(nextMemberId)
    void queryClient.invalidateQueries()
  }

  return (
    <nav
      className="hidden h-[calc(100vh-2rem)] w-64 shrink-0 flex-col rounded-xl bg-background px-2.5 py-3.5 md:flex"
    >
      <div className="mb-3 flex items-center px-3">
        <span className="font-heading text-[22px] font-bold leading-7 tracking-tight text-foreground select-none">
          Picsou
        </span>
      </div>

      <div className="flex flex-col gap-1.5">
        {NAV_ITEMS.map((item) => (
          <NavItem
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            icon={item.icon}
            title={t(item.labelKey)}
            description={t(item.descKey)}
          />
        ))}

        {/* Family view */}
        <NavItem
          to={FAMILY_NAV_ITEM.path}
          icon={FAMILY_NAV_ITEM.icon}
          title={t(FAMILY_NAV_ITEM.labelKey)}
          description={t(FAMILY_NAV_ITEM.descKey)}
        />
      </div>

      <div className="mt-auto pt-3">
        <SidebarWhatsNewCard />
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Item
              asChild
              variant="default"
              className="min-h-[52px] w-full rounded-xl px-3 py-2 text-left transition-colors hover:bg-muted"
            >
              <button type="button" aria-label={t('nav.account')}>
                <Avatar className="size-9 shrink-0 rounded-lg after:rounded-lg">
                  {activePhoto && (
                    <AvatarImage src={activePhoto} alt={activeDisplayName} className="rounded-lg" />
                  )}
                  <AvatarFallback
                    className={cn(
                      'rounded-lg text-sm font-bold',
                      activeMember ? 'text-white' : 'bg-muted text-muted-foreground',
                    )}
                    style={activeMember ? { backgroundColor: activeMember.avatarColor } : undefined}
                  >
                    {activeInitial}
                  </AvatarFallback>
                </Avatar>
                <ItemContent className="min-w-0">
                  <ItemTitle className="truncate text-sm font-semibold">{activeDisplayName}</ItemTitle>
                  <ItemDescription className="truncate text-xs">{activeDescription}</ItemDescription>
                </ItemContent>
                <CaretUpDown className="ml-auto size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
              </button>
            </Item>
          </DropdownMenuTrigger>

          <DropdownMenuContent side="top" align="start" className="w-64">
            {canSwitchProfile && switchableMembers.length > 0 && (
              <>
                <DropdownMenuRadioGroup value={activeProfileValue} onValueChange={handleProfileValueChange}>
                  <DropdownMenuRadioItem value="own">
                    <Avatar className="size-8 shrink-0 rounded-lg after:rounded-lg">
                      {ownPhoto && (
                        <AvatarImage src={ownPhoto} alt={displayName} className="rounded-lg" />
                      )}
                      <AvatarFallback className="rounded-lg bg-muted text-xs font-bold text-muted-foreground">
                        {initial}
                      </AvatarFallback>
                    </Avatar>
                    <div className="min-w-0">
                      <p className="truncate font-medium">{displayName}</p>
                      <p className="truncate text-xs text-muted-foreground">{t('nav.account')}</p>
                    </div>
                  </DropdownMenuRadioItem>

                  {switchableMembers.map((member) => (
                    <DropdownMenuRadioItem key={member.id} value={`member-${member.id}`}>
                      <span
                        className="size-8 shrink-0 rounded-lg"
                        style={{ backgroundColor: member.avatarColor }}
                        aria-hidden="true"
                      />
                      <div className="min-w-0">
                        <p className="truncate font-medium">{member.displayName}</p>
                        <p className="truncate text-xs text-muted-foreground">{t('nav.managedProfile')}</p>
                      </div>
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>

                <DropdownMenuSeparator />
              </>
            )}

            <AccountMenuFooterItems
              showAdmin={showAdmin}
              showLogout={showLogout}
              hideAmounts={hideAmounts}
              onToggleHideAmounts={toggleHideAmounts}
              onAdminClick={() => navigate('/admin')}
              onLogoutClick={() => logoutMutation.mutate()}
              logoutPending={logoutMutation.isPending}
            />
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </nav>
  )
}
