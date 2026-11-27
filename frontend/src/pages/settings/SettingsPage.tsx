import { useEffect, useRef, useState } from 'react'
import { type Theme, applyTheme, getStoredTheme } from '@/lib/theme'
import { useTranslation } from 'react-i18next'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'
import { useNavigate, useSearchParams } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore, type DateFormat } from '@/stores/app-store'
import { useDesktopUpdateBridge, useDesktopUpdateStore } from '@/stores/desktop-update-store'
import { useLogout } from '@/features/auth/hooks'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { PageHeader } from '@/components/shared/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { resizeProfilePhoto } from '@/lib/image-avatar'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { getErrorStatus } from '@/lib/errors'
import { cn, formatDate, getLocale } from '@/lib/utils'
import { useMoney } from '@/hooks/use-money'
import {
  SignOut,
  Users,
  CaretRight,
  Check,
  X,
  PencilSimple,
  Shield,
  ArrowSquareOut,
  Camera,
  Trash,
  ArrowsClockwise,
  CircleNotch,
} from '@phosphor-icons/react'
import { api } from '@/lib/api-client'
import { APP_VERSION } from '@/lib/app-version'
import { PICSOU_GITHUB_URL } from '@/lib/whats-new'
import { useInstanceStatus } from '@/features/admin/hooks'
import { SecuritySection } from './security/SecuritySection'
import { DesktopLockCard } from './security/DesktopLockCard'
import { AccessKeysSection } from './sections/AccessKeysSection'
import { ProfileSection } from './sections/ProfileSection'
import { ConnectedAppsSection } from './sections/ConnectedAppsSection'

// ---------------------------------------------------------------------------
// Toggle group button (theme / language)
// ---------------------------------------------------------------------------

interface ToggleOption {
  value: string
  label: string
}

function ToggleGroup({
  options,
  value,
  onChange,
}: {
  options: ToggleOption[]
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div className="flex w-full items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1">
      {options.map(({ value: optValue, label }) => (
        <button
          key={optValue}
          type="button"
          onClick={() => onChange(optValue)}
          className={cn(
            'inline-flex h-8 flex-1 basis-0 items-center justify-center whitespace-nowrap rounded-md px-3 text-sm font-medium transition-[background-color,color]',
            value === optValue
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground',
          )}
        >
          <span>{label}</span>
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Settings section card wrapper
// ---------------------------------------------------------------------------

function SectionCard({
  title,
  description,
  children,
}: {
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <Card className="rounded-xl bg-card">
      <CardHeader>
        <CardTitle className="text-base font-semibold">{title}</CardTitle>
        <CardDescription className="text-sm">{description}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

type SettingsTab = 'appearance' | 'profile' | 'security' | 'api' | 'system'

const VALID_TABS: SettingsTab[] = ['appearance', 'profile', 'security', 'api', 'system']

function resolveTab(params: URLSearchParams): SettingsTab {
  const raw = params.get('tab')
  if (raw && VALID_TABS.includes(raw as SettingsTab)) return raw as SettingsTab
  if (params.has('resume') || params.has('sso') || params.has('ssoError')) return 'security'
  return 'appearance'
}

// ---------------------------------------------------------------------------
// SettingsPage
// ---------------------------------------------------------------------------

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const activeTab = resolveTab(searchParams)
  const user = useAuthStore((s) => s.user)
  const money = useMoney()
  const logoutMutation = useLogout()
  const setUsername = useAuthStore((s) => s.setUsername)
  const {
    demoMode,
    desktopMode,
    desktopLockEnabled,
    advancedMode,
    setAdvancedMode,
    dateFormat,
    setDateFormat,
    hideAmounts,
    setHideAmounts,
    profilePhotos,
    setProfilePhoto,
  } = useAppStore()
  const isAdvancedMode = !desktopMode || advancedMode
  const effectiveTab: SettingsTab =
    activeTab === 'api' && !isAdvancedMode ? 'appearance' : activeTab
  const isAdmin = user?.role === 'ADMIN'
  const showAdmin = isAdmin && isAdvancedMode
  const showLogout = !desktopMode || desktopLockEnabled
  useDesktopUpdateBridge()
  const desktopUpdate = useDesktopUpdateStore()
  const showDesktopUpdates = desktopUpdate.enabled
  const showWebUpdates = showAdmin && !demoMode && !desktopMode && !showDesktopUpdates
  const instanceStatusQuery = useInstanceStatus(
    effectiveTab === 'system' && showWebUpdates,
  )
  const displayedVersion =
    desktopUpdate.currentVersion || instanceStatusQuery.data?.currentVersion || APP_VERSION
  const availableUpdate = instanceStatusQuery.data?.update ?? null
  const photoInputRef = useRef<HTMLInputElement>(null)
  const [photoError, setPhotoError] = useState<string | null>(null)
  const photoKey = user?.memberId != null ? String(user.memberId) : 'self'
  const profilePhoto = profilePhotos[photoKey]
  const accountInitial = (user?.displayName || user?.username || 'P').charAt(0).toUpperCase()

  async function handlePhotoSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setPhotoError(null)
    try {
      const dataUrl = await resizeProfilePhoto(file)
      setProfilePhoto(photoKey, dataUrl)
    } catch {
      setPhotoError(t('settings.profilePhotoInvalid'))
    }
  }

  const setActiveTab = (tab: SettingsTab) => {
    setSearchParams(tab === 'appearance' ? {} : { tab }, { replace: true })
  }

  // Username editing -------------------------------------------------------
  const [editingUsername, setEditingUsername] = useState(false)
  const [newUsername, setNewUsername] = useState('')
  const [usernameError, setUsernameError] = useState<string | null>(null)
  const [usernameSaving, setUsernameSaving] = useState(false)

  function startEditUsername() {
    setNewUsername(user?.username ?? '')
    setUsernameError(null)
    setEditingUsername(true)
  }

  function cancelEditUsername() {
    setEditingUsername(false)
    setUsernameError(null)
  }

  async function saveUsername() {
    const trimmed = newUsername.trim()
    if (!trimmed || trimmed === user?.username) { setEditingUsername(false); return }
    if (trimmed.length < 3) { setUsernameError(t('settings.usernameTooShort')); return }
    if (!/^[a-zA-Z0-9._-]+$/.test(trimmed)) { setUsernameError(t('settings.usernameInvalidChars')); return }
    setUsernameSaving(true)
    setUsernameError(null)
    try {
      await api.patch('/auth/username', { newUsername: trimmed })
      setUsername(trimmed)
      setEditingUsername(false)
    } catch (err: unknown) {
      setUsernameError(getErrorStatus(err) === 409 ? t('settings.usernameTaken') : t('common.error'))
    } finally {
      setUsernameSaving(false)
    }
  }

  // Theme -----------------------------------------------------------------
  const [theme, setTheme] = useState<Theme>(getStoredTheme)

  useEffect(() => {
    applyTheme(theme)
  }, [theme])

  // Language --------------------------------------------------------------
  const [locale, setLocale] = useState(i18n.language)

  // i18next persists the choice itself (localStorage key "picsou-locale").
  const handleLocaleChange = (lng: string) => {
    i18n.changeLanguage(lng)
    setLocale(lng)
  }

  // Logout ----------------------------------------------------------------
  // Routes through the server-side /auth/logout call (via useLogout), not just
  // the local zustand flag -- otherwise the session cookies stay valid and
  // RequireAuth's session-probe silently re-authenticates the user right back in.
  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      onSuccess: () => navigate('/login'),
    })
  }

  // Theme / locale options
  const themeOptions: ToggleOption[] = [
    { value: 'light', label: t('settings.themeLight') },
    { value: 'dark', label: t('settings.themeDark') },
    { value: 'system', label: t('settings.themeSystem') },
  ]

  const localeOptions: ToggleOption[] = SUPPORTED_LOCALES.map((l) => ({
    value: l.code,
    label: l.label,
  }))

  const dateFormatOptions: ToggleOption[] = [
    { value: 'locale', label: t('settings.dateFormatLocale') },
    { value: 'iso', label: t('settings.dateFormatIso') },
  ]

  const amountsOptions: ToggleOption[] = [
    { value: 'visible', label: t('settings.amountsVisible') },
    { value: 'hidden', label: t('settings.amountsHidden') },
  ]

  const todayIso = new Date().toLocaleDateString('en-CA')
  const sampleDate = formatDate(todayIso, getLocale())


  const tabs: Array<{ id: SettingsTab; label: string }> = [
    { id: 'appearance', label: t('settings.tabs.appearance') },
    { id: 'profile', label: t('settings.tabs.profile') },
    { id: 'security', label: t('settings.tabs.security') },
    ...(isAdvancedMode ? [{ id: 'api' as const, label: t('settings.tabs.api') }] : []),
    { id: 'system', label: t('settings.tabs.system') },
  ]

  return (
    <div className="space-y-6">
      <PageHeader title={t('settings.title')} />

      <div
        role="tablist"
        aria-label={t('settings.title')}
        className="flex w-full flex-wrap items-center gap-1 rounded-lg border border-border/60 bg-background/80 p-1"
      >
        {tabs.map(({ id, label }) => {
          const active = effectiveTab === id
          return (
            <button
              key={id}
              role="tab"
              type="button"
              aria-selected={active}
              onClick={() => setActiveTab(id)}
              className={cn(
                'inline-flex h-8 flex-auto items-center justify-center rounded-md px-3.5 text-sm font-medium whitespace-nowrap transition-[background-color,color]',
                active
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:text-foreground'
              )}
            >
              <span>{label}</span>
            </button>
          )
        })}
      </div>

      {effectiveTab === 'appearance' && (
        <div className="grid gap-4 sm:grid-cols-2">
          <Card className="rounded-xl bg-card">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold">
                {t('settings.theme')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ToggleGroup
                options={themeOptions}
                value={theme}
                onChange={(v) => setTheme(v as Theme)}
              />
            </CardContent>
          </Card>

          <Card className="rounded-xl bg-card">
            <CardHeader className="pb-3">
              <CardTitle className="text-base font-semibold">
                {t('settings.language')}
              </CardTitle>
            </CardHeader>
            <CardContent>
              <ToggleGroup
                options={localeOptions}
                value={resolveLocale(locale).code}
                onChange={handleLocaleChange}
              />
            </CardContent>
          </Card>

          <Card className="rounded-xl bg-card">
            <CardHeader className="flex flex-row items-center justify-between gap-3 space-y-0 pb-3">
              <CardTitle className="text-base font-semibold">
                {t('settings.dateFormat')}
              </CardTitle>
              <span className="rounded-md bg-muted/60 px-2.5 py-1 text-xs font-medium tabular-nums text-muted-foreground">
                {sampleDate}
              </span>
            </CardHeader>
            <CardContent>
              <ToggleGroup
                options={dateFormatOptions}
                value={dateFormat}
                onChange={(v) => setDateFormat(v as DateFormat)}
              />
            </CardContent>
          </Card>

          <Card className="rounded-xl bg-card">
            <CardHeader className="flex flex-row items-center justify-between gap-3 space-y-0 pb-3">
              <CardTitle className="text-base font-semibold">
                {t('settings.amounts')}
              </CardTitle>
              <span className="rounded-md bg-muted/60 px-2.5 py-1 text-xs font-medium tabular-nums text-muted-foreground">
                {money.amount(1234.56, 'EUR')}
              </span>
            </CardHeader>
            <CardContent>
              <ToggleGroup
                options={amountsOptions}
                value={hideAmounts ? 'hidden' : 'visible'}
                onChange={(v) => setHideAmounts(v === 'hidden')}
              />
            </CardContent>
          </Card>

          {desktopMode && (
            <Card className="rounded-xl bg-card sm:col-span-2">
              <CardContent className="flex items-center justify-between gap-4">
                <div className="space-y-1">
                  <Label
                    htmlFor="desktop-advanced-mode"
                    className="cursor-pointer text-base font-semibold leading-none"
                  >
                    {t('desktop.advancedMode.title')}
                  </Label>
                  <p className="text-sm text-muted-foreground">
                    {t('desktop.advancedMode.description')}
                  </p>
                </div>
                <Switch
                  id="desktop-advanced-mode"
                  checked={advancedMode}
                  onCheckedChange={setAdvancedMode}
                />
              </CardContent>
            </Card>
          )}
        </div>
      )}

      {effectiveTab === 'profile' && (
        <div className="space-y-6">
          <SectionCard
            title={t('settings.account')}
            description={t('settings.accountDescription')}
          >
            <div className="space-y-5">
              {/* Profile photo */}
              <div className="flex items-center justify-between gap-4 border-b border-border/60 pb-4">
                <div className="space-y-0.5">
                  <Label className="text-sm font-medium">{t('settings.profilePhoto')}</Label>
                  {photoError && <p className="text-sm text-destructive">{photoError}</p>}
                </div>
                <div className="flex items-center gap-2">
                  <input
                    ref={photoInputRef}
                    type="file"
                    accept="image/*"
                    className="hidden"
                    onChange={(e) => void handlePhotoSelected(e)}
                  />
                  {profilePhoto && (
                    <Button
                      type="button"
                      size="icon-sm"
                      variant="ghost"
                      className="text-muted-foreground hover:text-destructive"
                      aria-label={t('settings.profilePhotoRemove')}
                      title={t('settings.profilePhotoRemove')}
                      onClick={() => {
                        setPhotoError(null)
                        setProfilePhoto(photoKey, null)
                      }}
                    >
                      <Trash className="size-4" aria-hidden="true" />
                    </Button>
                  )}
                  <button
                    type="button"
                    onClick={() => photoInputRef.current?.click()}
                    aria-label={profilePhoto ? t('settings.profilePhotoChange') : t('settings.profilePhotoUpload')}
                    title={profilePhoto ? t('settings.profilePhotoChange') : t('settings.profilePhotoUpload')}
                    className="group relative cursor-pointer rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <Avatar className="size-10 rounded-lg after:rounded-lg">
                      {profilePhoto && (
                        <AvatarImage src={profilePhoto} alt={user?.displayName || user?.username || ''} className="rounded-lg" />
                      )}
                      <AvatarFallback className="rounded-lg bg-muted text-sm font-semibold text-muted-foreground">
                        {accountInitial}
                      </AvatarFallback>
                    </Avatar>
                    <span className="absolute inset-0 flex items-center justify-center rounded-lg bg-black/45 text-white opacity-0 transition-opacity group-hover:opacity-100">
                      <Camera className="size-4" aria-hidden="true" />
                    </span>
                  </button>
                </div>
              </div>

              <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
                <Label className="shrink-0 text-sm font-medium">
                  {t('settings.username')}
                </Label>
                {editingUsername ? (
                  <div className="flex flex-col items-end gap-1">
                    <div className="flex items-center gap-2">
                      <Input
                        value={newUsername}
                        onChange={e => setNewUsername(e.target.value)}
                        onKeyDown={e => { if (e.key === 'Enter') saveUsername(); if (e.key === 'Escape') cancelEditUsername() }}
                        className="w-64 sm:w-72"
                        autoFocus
                        disabled={usernameSaving}
                      />
                      <Button size="icon" variant="outline" onClick={saveUsername} disabled={usernameSaving}>
                        <Check className="size-4 text-emerald-600" />
                      </Button>
                      <Button size="icon" variant="outline" onClick={cancelEditUsername} disabled={usernameSaving}>
                        <X className="size-4" />
                      </Button>
                    </div>
                    {usernameError && <p className="text-sm text-destructive">{usernameError}</p>}
                  </div>
                ) : (
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-foreground">{user?.username}</span>
                    <Button size="icon" variant="outline" onClick={startEditUsername}>
                      <PencilSimple className="size-4" />
                    </Button>
                  </div>
                )}
              </div>
              {showLogout && (
                <div className="flex justify-end border-t border-border/60 pt-4">
                  <Button variant="destructive" onClick={handleLogout} disabled={logoutMutation.isPending}>
                    <SignOut className="size-4" />
                    {t('settings.logout')}
                  </Button>
                </div>
              )}
            </div>
          </SectionCard>

          <SectionCard
            title={t('settings.profile.title')}
            description={t('settings.profile.description')}
          >
            <ProfileSection />
          </SectionCard>
        </div>
      )}

      {effectiveTab === 'security' && (
        <div className="space-y-6">
          {desktopMode && isAdmin && <DesktopLockCard />}
          {!desktopMode || desktopLockEnabled || isAdvancedMode ? (
            <SectionCard
              title={t('settings.security')}
              description={t('settings.securityDescription')}
            >
              <SecuritySection />
            </SectionCard>
          ) : (
            // No lock: sign-in methods, two-factor and trusted devices have nothing to act on,
            // but the member's data is still theirs to take out.
            <SectionCard
              title={t('settings.exportTitle')}
              description={t('settings.exportShortDesc')}
            >
              <SecuritySection exportOnly />
            </SectionCard>
          )}
        </div>
      )}

      {effectiveTab === 'api' && isAdvancedMode && (
        <div className="space-y-6">
          <SectionCard
            title={t('accessKeys.sectionTitle')}
            description={t('accessKeys.sectionDescription')}
          >
            <AccessKeysSection />
          </SectionCard>

          <SectionCard
            title={t('connectedApps.sectionTitle')}
            description={t('connectedApps.sectionDescription')}
          >
            <ConnectedAppsSection />
          </SectionCard>
        </div>
      )}

      {effectiveTab === 'system' && (
        <div className="space-y-6">
          <div className={cn('grid gap-4', showAdmin && 'sm:grid-cols-2')}>
            <button
              type="button"
              onClick={() => navigate('/settings/family')}
              className="group flex items-center gap-4 rounded-xl border border-border/60 bg-card p-5 text-left transition-colors hover:bg-muted/35"
            >
              <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground">
                <Users className="size-5" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-base font-semibold text-foreground">{t('settings.family')}</p>
                <p className="mt-0.5 text-sm text-muted-foreground">{t('settings.familyDescription')}</p>
              </div>
              <CaretRight className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
            </button>

            {showAdmin && (
              <button
                type="button"
                onClick={() => navigate('/admin')}
                className="group flex items-center gap-4 rounded-xl border border-border/60 bg-card p-5 text-left transition-colors hover:bg-muted/35"
              >
                <span className="inline-flex size-10 shrink-0 items-center justify-center rounded-lg bg-muted text-foreground">
                  <Shield className="size-5" aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-base font-semibold text-foreground">{t('settings.adminSection')}</p>
                  <p className="mt-0.5 text-sm text-muted-foreground">{t('settings.adminDescription')}</p>
                </div>
                <CaretRight className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
              </button>
            )}
          </div>

          <SectionCard
            title={t('settings.about')}
            description={t('settings.aboutDescription')}
          >
            <div className="space-y-4">
              <div className="grid gap-3 text-sm sm:grid-cols-3">
                <div className="rounded-xl bg-muted/40 px-4 py-3">
                  <p className="text-xs text-muted-foreground">{t('settings.application')}</p>
                  <p className="mt-1 font-semibold text-foreground">Picsou</p>
                </div>
                <div className="rounded-xl bg-muted/40 px-4 py-3">
                  <p className="text-xs text-muted-foreground">{t('settings.version')}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <span className="font-semibold tabular-nums text-foreground">
                      v{displayedVersion}
                    </span>
                    {showDesktopUpdates && (
                      desktopUpdate.availableVersion ? (
                        <Badge className="tabular-nums">
                          {t('whatsNew.updateAvailable', { version: `v${desktopUpdate.availableVersion}` })}
                        </Badge>
                      ) : desktopUpdate.checked && !desktopUpdate.checking && !desktopUpdate.error ? (
                        <Badge
                          variant="secondary"
                          className="bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                        >
                          {t('whatsNew.upToDate')}
                        </Badge>
                      ) : null
                    )}
                    {showWebUpdates && instanceStatusQuery.data && (
                      availableUpdate ? (
                        <Badge className="tabular-nums">
                          {t('whatsNew.updateAvailable', { version: `v${availableUpdate.latestVersion}` })}
                        </Badge>
                      ) : (
                        <Badge
                          variant="secondary"
                          className="bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                        >
                          {t('whatsNew.upToDate')}
                        </Badge>
                      )
                    )}
                  </div>
                </div>
                <a
                  href={PICSOU_GITHUB_URL}
                  target="_blank"
                  rel="noreferrer"
                  className="group flex flex-col justify-between rounded-xl bg-muted/40 px-4 py-3 transition-colors hover:bg-muted/70"
                >
                  <p className="text-xs text-muted-foreground">GitHub</p>
                  <span className="mt-1 inline-flex items-center gap-1.5 font-semibold text-foreground">
                    <span className="truncate">Cloeille/picsou-finance</span>
                    <ArrowSquareOut className="size-3.5 shrink-0 text-muted-foreground transition-colors group-hover:text-foreground" aria-hidden="true" />
                  </span>
                </a>
              </div>

              {showDesktopUpdates && desktopUpdate.error && (
                <AlertBanner variant="inline" role="alert">
                  {t('desktop.update.checkFailed')}
                </AlertBanner>
              )}

              <div className="flex flex-wrap items-center gap-2 border-t border-border/60 pt-4">
                {showDesktopUpdates && desktopUpdate.availableVersion && (
                  <Button
                    type="button"
                    size="sm"
                    disabled={desktopUpdate.installing || desktopUpdate.checking}
                    onClick={() => void desktopUpdate.installUpdate()}
                  >
                    {desktopUpdate.installing && (
                      <CircleNotch className="size-3.5 animate-spin" aria-hidden="true" />
                    )}
                    {desktopUpdate.installing
                      ? t('desktop.update.installing')
                      : t('desktop.update.install')}
                  </Button>
                )}
                {showDesktopUpdates && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={desktopUpdate.checking || desktopUpdate.installing}
                    onClick={() => void desktopUpdate.checkForUpdates()}
                  >
                    <ArrowsClockwise
                      className={cn('size-3.5', desktopUpdate.checking && 'animate-spin')}
                      aria-hidden="true"
                    />
                    {desktopUpdate.checking
                      ? t('desktop.update.checking')
                      : t('whatsNew.checkUpdates')}
                  </Button>
                )}
                {showWebUpdates && (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={instanceStatusQuery.isFetching}
                    onClick={() => void instanceStatusQuery.refetch()}
                  >
                    <ArrowsClockwise
                      className={cn('size-3.5', instanceStatusQuery.isFetching && 'animate-spin')}
                      aria-hidden="true"
                    />
                    {t('whatsNew.checkUpdates')}
                  </Button>
                )}
                <Button size="sm" variant="outline" asChild>
                  <a href={PICSOU_GITHUB_URL} target="_blank" rel="noreferrer">
                    {t('whatsNew.starGithub')}
                    <ArrowSquareOut className="size-3.5" aria-hidden="true" />
                  </a>
                </Button>
              </div>
            </div>
          </SectionCard>
        </div>
      )}
    </div>
  )
}
