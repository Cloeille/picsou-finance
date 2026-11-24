import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { CircleNotch, Eye, EyeSlash, Lock, LockOpen } from '@phosphor-icons/react'
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
import { AlertBanner } from '@/components/shared/AlertBanner'
import { useDesktopLockStatus, useUpdateDesktopLock } from '@/features/desktop/hooks'
import { useMfaStatus } from '@/features/mfa/hooks'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { formatApiError, getErrorCode, getErrorStatus } from '@/lib/errors'

type LockFormMode = 'idle' | 'enable' | 'disable'

export function DesktopLockCard() {
  const { t } = useTranslation()
  const user = useAuthStore((s) => s.user)
  const storeLocked = useAppStore((s) => s.desktopLockEnabled)
  const lockQuery = useDesktopLockStatus(true)
  const mfaStatus = useMfaStatus()
  const updateLock = useUpdateDesktopLock()

  const locked = lockQuery.data?.enabled ?? storeLocked
  const mfaEnabled = mfaStatus.data?.enabled === true
  const disableBlockedByMfa = locked && mfaEnabled
  const [mode, setMode] = useState<LockFormMode>('idle')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function resetForm() {
    setMode('idle')
    setNewPassword('')
    setConfirmPassword('')
    setCurrentPassword('')
    setShowPw(false)
    setError(null)
  }

  async function handleEnable(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (newPassword.length < 8) {
      setError(t('desktop.lock.passwordTooShort'))
      return
    }
    if (newPassword !== confirmPassword) {
      setError(t('desktop.lock.passwordMismatch'))
      return
    }
    try {
      await updateLock.mutateAsync({ enabled: true, newPassword })
      toast.success(t('desktop.lock.enabledToast'))
      resetForm()
    } catch (err: unknown) {
      setError(formatApiError(err, t, 'common.error'))
    }
  }

  async function handleDisable(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (!currentPassword) {
      setError(t('desktop.lock.invalidPassword'))
      return
    }
    try {
      await updateLock.mutateAsync({ enabled: false, currentPassword })
      toast.success(t('desktop.lock.disabledToast'))
      resetForm()
    } catch (err: unknown) {
      if (getErrorStatus(err) === 401 && getErrorCode(err) === 'REAUTH_FAILED') {
        setError(t('desktop.lock.invalidPassword'))
      } else if (getErrorStatus(err) === 409 && getErrorCode(err) === 'MFA_ACTIVE') {
        setError(t('desktop.lock.disableBlockedByMfa'))
      } else {
        setError(formatApiError(err, t, 'common.error'))
      }
    }
  }

  return (
    <Card className="rounded-xl border-0 bg-card ring-1 ring-foreground/10">
      <CardHeader>
        <CardTitle className="text-sm font-bold">
          {t('desktop.lock.title')}
        </CardTitle>
        <CardDescription className="text-sm">
          {t('desktop.lock.description')}
        </CardDescription>
      </CardHeader>

      <CardContent className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-2.5">
            {locked ? (
              <Lock className="size-5 shrink-0 text-foreground" aria-hidden="true" />
            ) : (
              <LockOpen className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
            )}
            <div className="space-y-0.5">
              <p className="text-sm font-medium text-foreground">
                {locked ? t('desktop.lock.statusEnabled') : t('desktop.lock.statusDisabled')}
              </p>
              {user?.username && (
                <p className="text-xs text-muted-foreground">
                  {t('desktop.lock.usernameHint', { username: user.username })}
                </p>
              )}
            </div>
          </div>

          {mode === 'idle' && (
            locked ? (
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={disableBlockedByMfa}
                onClick={() => {
                  setError(null)
                  setMode('disable')
                }}
              >
                {t('desktop.lock.disableButton')}
              </Button>
            ) : (
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  setError(null)
                  setMode('enable')
                }}
              >
                {t('desktop.lock.enableButton')}
              </Button>
            )
          )}
        </div>

        {disableBlockedByMfa && (
          <p className="text-sm text-muted-foreground">
            {t('desktop.lock.disableBlockedByMfa')}
          </p>
        )}

        {mode === 'enable' && (
          <form onSubmit={handleEnable} className="space-y-4 border-t border-border/60 pt-4">
            <div className="space-y-2">
              <Label htmlFor="desktop-lock-new-password" className="text-sm font-medium">
                {t('desktop.lock.newPasswordLabel')}
              </Label>
              <div className="relative">
                <Input
                  id="desktop-lock-new-password"
                  type={showPw ? 'text' : 'password'}
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  autoComplete="new-password"
                  minLength={8}
                  maxLength={128}
                  required
                  disabled={updateLock.isPending}
                  className="pr-11"
                />
                <button
                  type="button"
                  onClick={() => setShowPw((v) => !v)}
                  className="absolute right-1 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                  aria-label={showPw ? t('auth.hidePassword') : t('auth.showPassword')}
                  aria-pressed={showPw}
                >
                  {showPw ? (
                    <EyeSlash className="size-4" aria-hidden="true" />
                  ) : (
                    <Eye className="size-4" aria-hidden="true" />
                  )}
                </button>
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="desktop-lock-confirm-password" className="text-sm font-medium">
                {t('desktop.lock.confirmPasswordLabel')}
              </Label>
              <Input
                id="desktop-lock-confirm-password"
                type={showPw ? 'text' : 'password'}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                minLength={8}
                maxLength={128}
                required
                disabled={updateLock.isPending}
              />
            </div>

            {error && (
              <AlertBanner variant="prominent" role="alert">
                {error}
              </AlertBanner>
            )}

            <div className="flex items-center justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={resetForm}
                disabled={updateLock.isPending}
              >
                {t('desktop.lock.cancel')}
              </Button>
              <Button type="submit" size="sm" disabled={updateLock.isPending}>
                {updateLock.isPending && (
                  <CircleNotch className="size-4 animate-spin" aria-hidden="true" />
                )}
                {t('desktop.lock.enableConfirm')}
              </Button>
            </div>
          </form>
        )}

        {mode === 'disable' && (
          <form onSubmit={handleDisable} className="space-y-4 border-t border-border/60 pt-4">
            <div className="space-y-2">
              <Label htmlFor="desktop-lock-current-password" className="text-sm font-medium">
                {t('desktop.lock.currentPasswordLabel')}
              </Label>
              <Input
                id="desktop-lock-current-password"
                type="password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                autoComplete="current-password"
                required
                disabled={updateLock.isPending}
              />
            </div>

            {error && (
              <AlertBanner variant="prominent" role="alert">
                {error}
              </AlertBanner>
            )}

            <div className="flex items-center justify-end gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={resetForm}
                disabled={updateLock.isPending}
              >
                {t('desktop.lock.cancel')}
              </Button>
              <Button type="submit" variant="destructive" size="sm" disabled={updateLock.isPending}>
                {updateLock.isPending && (
                  <CircleNotch className="size-4 animate-spin" aria-hidden="true" />
                )}
                {t('desktop.lock.disableConfirm')}
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  )
}
