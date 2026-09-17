import React, { useEffect, useState } from 'react'
import { type Theme, applyTheme, getStoredTheme } from '@/lib/theme'
import { useTranslation } from 'react-i18next'
import { SUPPORTED_LOCALES, resolveLocale } from '@/i18n/locales'
import { useNavigate } from 'react-router'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore, type DateFormat, type SidebarStyle } from '@/stores/app-store'
import { useLogout } from '@/features/auth/hooks'
import { PageHeader } from '@/components/shared/PageHeader'
import { Button } from '@/components/ui/button'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { getErrorStatus } from '@/lib/errors'
import {
  Paintbrush,
  Globe,
  User,
  LogOut,
  Users,
  ChevronRight,
  Check,
  X,
  Pencil,
  Shield,
  KeyRound,
  IdCard,
  Link2,
  ExternalLink,
} from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { api } from '@/lib/api-client'
import { APP_VERSION } from '@/lib/app-version'
import { SecuritySection } from './security/SecuritySection'
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
    <div className="inline-flex items-center rounded-2xl bg-muted p-1">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={`inline-flex h-10 min-w-24 items-center justify-center rounded-xl px-8 text-sm font-medium transition-[background-color,color] ${
            value === opt.value
              ? 'bg-primary text-primary-foreground'
              : 'text-muted-foreground hover:text-foreground'
          }`}
        >
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// Settings section card wrapper
// ---------------------------------------------------------------------------

function SectionCard({
  icon,
  title,
  description,
  children,
}: {
  icon: LucideIcon
  title: string
  description: string
  children: React.ReactNode
}) {
  return (
    <Card className="rounded-4xl bg-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          {React.createElement(icon, { className: "size-5 text-muted-foreground" })}
          {title}
        </CardTitle>
        <CardDescription>{description}</CardDescription>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  )
}

// ---------------------------------------------------------------------------
// SettingsPage
// ---------------------------------------------------------------------------

export function SettingsPage() {
  const { t, i18n } = useTranslation()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const logoutMutation = useLogout()
  const setUsername = useAuthStore((s) => s.setUsername)
  const { dateFormat, setDateFormat, sidebarStyle, setSidebarStyle, hideAmounts, setHideAmounts } = useAppStore()

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

  const sidebarStyleOptions: ToggleOption[] = [
    { value: 'current', label: t('settings.sidebarStyleCurrent') },
    { value: 'classic', label: t('settings.sidebarStyleClassic') },
  ]

  return (
    <div className="space-y-6">
      <PageHeader title={t('settings.title')} />

      {/* Appearance ------------------------------------------------------- */}
      <SectionCard
        icon={Paintbrush}
        title={t('settings.appearance')}
        description={t('settings.appearanceDescription')}
      >
        <div className="space-y-6">
          {/* Theme */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.theme')}</Label>
            <ToggleGroup
              options={themeOptions}
              value={theme}
              onChange={(v) => setTheme(v as Theme)}
            />
          </div>

          {/* Language */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.language')}</Label>
            <ToggleGroup
              options={localeOptions}
              value={resolveLocale(locale).code}
              onChange={handleLocaleChange}
            />
          </div>

          {/* Date format */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.dateFormat')}</Label>
            <ToggleGroup
              options={dateFormatOptions}
              value={dateFormat}
              onChange={(v) => setDateFormat(v as DateFormat)}
            />
          </div>

          {/* Amounts. Duplicated from the sidebar's eye button on purpose: that button lives in
              a sidebar that is hidden below 768px, and shoulder-surfing is likelier on a phone
              than at a desk. */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.amounts')}</Label>
            <ToggleGroup
              options={amountsOptions}
              value={hideAmounts ? 'hidden' : 'visible'}
              onChange={(v) => setHideAmounts(v === 'hidden')}
            />
          </div>

          {/* Sidebar style */}
          <div className="flex items-center justify-between">
            <Label className="text-sm font-medium">{t('settings.sidebarStyle')}</Label>
            <ToggleGroup
              options={sidebarStyleOptions}
              value={sidebarStyle}
              onChange={(v) => setSidebarStyle(v as SidebarStyle)}
            />
          </div>
        </div>
      </SectionCard>

      {/* Account ---------------------------------------------------------- */}
      <SectionCard
        icon={User}
        title={t('settings.account')}
        description={t('settings.accountDescription')}
      >
        <div className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <Label className="text-sm font-medium shrink-0">
              {t('settings.username')}
            </Label>
            {editingUsername ? (
              <div className="flex flex-col items-end gap-1">
                <div className="flex items-center gap-2">
                  <Input
                    value={newUsername}
                    onChange={e => setNewUsername(e.target.value)}
                    onKeyDown={e => { if (e.key === 'Enter') saveUsername(); if (e.key === 'Escape') cancelEditUsername() }}
                    className="w-72"
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
                <span className="text-sm text-muted-foreground">{user?.username}</span>
                <Button size="icon" variant="outline" onClick={startEditUsername}>
                  <Pencil className="size-4" />
                </Button>
              </div>
            )}
          </div>
          <div className="flex justify-end">
            <Button variant="destructive" onClick={handleLogout} disabled={logoutMutation.isPending}>
              <LogOut className="mr-2 size-4" />
              {t('settings.logout')}
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* Profile (personal + fiscal context) ------------------------------ */}
      <SectionCard
        icon={IdCard}
        title={t('settings.profile.title')}
        description={t('settings.profile.description')}
      >
        <ProfileSection />
      </SectionCard>

      {/* Security --------------------------------------------------------- */}
      <SectionCard
        icon={Shield}
        title={t('settings.security')}
        description={t('settings.securityDescription')}
      >
        <SecuritySection />
      </SectionCard>

      {/* Access keys & MCP ------------------------------------------------ */}
      <SectionCard
        icon={KeyRound}
        title={t('accessKeys.sectionTitle')}
        description={t('accessKeys.sectionDescription')}
      >
        <AccessKeysSection />
      </SectionCard>

      {/* Connected apps (OAuth) -------------------------------------------- */}
      <SectionCard
        icon={Link2}
        title={t('connectedApps.sectionTitle')}
        description={t('connectedApps.sectionDescription')}
      >
        <ConnectedAppsSection />
      </SectionCard>

      {/* Family ----------------------------------------------------------- */}
      <SectionCard
        icon={Users}
        title={t('settings.family')}
        description={t('settings.familyDescription')}
      >
        <button
          type="button"
          onClick={() => navigate('/settings/family')}
          className="flex min-h-10 w-full items-center justify-between rounded-xl bg-muted/70 px-4 py-2 text-sm font-medium transition-colors hover:bg-muted"
        >
          <span>{t('settings.familyManage')}</span>
          <ChevronRight className="size-4 text-muted-foreground" />
        </button>
      </SectionCard>

      {/* Admin (admin-only) ----------------------------------------------- */}
      {user?.role === 'ADMIN' && (
        <SectionCard
          icon={Shield}
          title={t('settings.adminSection')}
          description={t('settings.adminDescription')}
        >
          <button
            type="button"
            onClick={() => navigate('/admin')}
            className="flex min-h-10 w-full items-center justify-between rounded-xl bg-muted/70 px-4 py-2 text-sm font-medium transition-colors hover:bg-muted"
          >
            <span>{t('settings.adminButton')}</span>
            <ChevronRight className="size-4 text-muted-foreground" />
          </button>
        </SectionCard>
      )}

      {/* About ------------------------------------------------------------ */}
      <SectionCard
        icon={Globe}
        title={t('settings.about')}
        description={t('settings.aboutDescription')}
      >
        <div className="grid gap-5 text-sm sm:grid-cols-3 sm:items-start">
          <div className="space-y-1">
            <p className="text-muted-foreground">{t('settings.application')}</p>
            <p className="font-medium text-foreground">Picsou</p>
          </div>
          <div className="space-y-1">
            <p className="text-muted-foreground">
              {t('settings.version')}
            </p>
            <p className="font-medium text-foreground">{APP_VERSION}</p>
          </div>
          <div className="space-y-1 sm:justify-self-end sm:text-right">
            <p className="text-muted-foreground">GitHub</p>
            <a
              href="https://github.com/zoeille/picsou-finance"
              target="_blank"
              rel="noreferrer"
              className="inline-flex min-h-10 max-w-full items-center gap-2 rounded-xl font-medium text-foreground transition-colors hover:text-muted-foreground"
            >
              <span className="truncate">github.com/zoeille/picsou-finance</span>
              <ExternalLink className="size-4 text-muted-foreground" />
            </a>
          </div>
        </div>
      </SectionCard>
    </div>
  )
}
