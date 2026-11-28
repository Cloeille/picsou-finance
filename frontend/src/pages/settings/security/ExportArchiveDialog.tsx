import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { CircleNotch, DownloadSimple, Warning } from '@phosphor-icons/react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { SecretInput } from '@/components/shared/SecretInput'
import { useMfaStatus } from '@/features/mfa/hooks'
import { useExportArchive } from '@/features/export/hooks'
import { archiveCodeKey, ARCHIVE_MIN_PASSPHRASE_CHARS, passphraseLength } from '@/features/setup/archive-import'
import { formatSsoApiError } from '@/features/sso/errors'
import { useSignInMethods } from '@/features/sso/hooks'
import { formatApiError, getErrorCode, getErrorStatus } from '@/lib/errors'
import { useAppStore } from '@/stores/app-store'
import { ReauthPanel } from './ReauthPanel'

/**
 * Downloads the encrypted archive of the whole instance (`.picsou-archive`), to move it to
 * another install. Same proof as the data export: the current password (or the TOTP code when
 * 2FA is on), or an IdP confirmation for a password-less account (ReauthPanel). The desktop app
 * without its password lock asks for nothing first: the window stands for the proof of the
 * account it signs in itself. Any other client of that account has no password to type, so the
 * dialog sends the user to the lock card instead of asking for one.
 *
 * The archive passphrase is typed twice, lives in this component's state only and is cleared
 * when the dialog closes. It cannot be recovered: nothing on the server keeps it.
 */
export function ExportArchiveDialog({
  open,
  onOpenChange,
  resumed = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Mounted on the way back from the IdP re-authentication (`resume=archive`): skip the confirmation. */
  resumed?: boolean
}) {
  const { t } = useTranslation()
  const { data: mfaStatus } = useMfaStatus()
  const signInMethods = useSignInMethods()
  const exportMutation = useExportArchive()
  const passphraseId = useId()
  const confirmationId = useId()
  const passwordId = useId()
  const totpId = useId()

  const [passphrase, setPassphrase] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [reauthConfirmed, setReauthConfirmed] = useState(resumed)
  const [reauthExpired, setReauthExpired] = useState(false)
  const [windowRefused, setWindowRefused] = useState(false)
  const passwordless = signInMethods.data?.hasPassword === false
  const desktopUnlocked = useAppStore((s) => s.desktopMode && !s.desktopLockEnabled)
  const asksCredentials = !passwordless && !(desktopUnlocked && !windowRefused)
  // The window did not vouch for this session: a password is asked, and the quick-start account
  // has none anyone knows, so the lock card has to set one first.
  const needsLock = desktopUnlocked && windowRefused
  const step = signInMethods.isPending
    ? 'loading'
    : !signInMethods.data
      ? 'error'
      : passwordless && !reauthConfirmed
        ? 'reauth'
        : 'form'

  const length = passphraseLength(passphrase)
  const tooShort = length > 0 && length < ARCHIVE_MIN_PASSPHRASE_CHARS
  const mismatch = confirmation.length > 0 && confirmation !== passphrase
  const canSubmit = length >= ARCHIVE_MIN_PASSPHRASE_CHARS && confirmation === passphrase

  const reset = () => {
    setPassphrase('')
    setConfirmation('')
    setPassword('')
    setTotpCode('')
    setError(null)
    setReauthConfirmed(false)
    setReauthExpired(false)
    setWindowRefused(false)
    exportMutation.reset()
  }

  const close = () => {
    onOpenChange(false)
    setTimeout(reset, 250)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    setError(null)
    try {
      await exportMutation.mutateAsync({
        passphrase,
        // Password-less: the proof cookie minted through ReauthPanel authorizes the export.
        ...(asksCredentials ? { reAuth: mfaStatus?.enabled ? { totpCode } : { password } } : {}),
      })
      close()
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      const code = getErrorCode(err)
      if (code === 'PASSPHRASE_TOO_SHORT' || code === 'PASSPHRASE_REQUIRED') {
        setError(t(archiveCodeKey(code)))
      } else if (status === 401 && passwordless) {
        // The proof expired before the archive was built.
        setReauthConfirmed(false)
        setReauthExpired(true)
      } else if (status === 401 && !asksCredentials) {
        // Not the account the app signs in itself: its password is asked, or has to be set.
        setWindowRefused(true)
      } else if (status === 401) setError(t('settings.exportInvalidCredentials'))
      else if (status === 429) setError(t('settings.archive.rateLimited'))
      else if (code === 'EXPORT_UNAVAILABLE') setError(t('settings.archive.unavailable'))
      else setError(formatApiError(err, t))
    }
  }

  return (
    <Dialog open={open} onOpenChange={v => (v ? onOpenChange(true) : close())}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.archive.title')}</DialogTitle>
          <DialogDescription>{t('settings.archive.description')}</DialogDescription>
        </DialogHeader>

        {step === 'loading' && (
          <div className="flex justify-center py-4">
            <CircleNotch className="size-5 animate-spin text-muted-foreground" />
          </div>
        )}

        {step === 'error' && (
          <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
            {formatSsoApiError(signInMethods.error, t)}
          </p>
        )}

        {step === 'reauth' && (
          <ReauthPanel
            action="archive"
            expired={reauthExpired}
            onConfirmed={() => {
              setReauthConfirmed(true)
              setReauthExpired(false)
            }}
          />
        )}

        {step === 'form' && (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div
              role="note"
              className="flex items-start gap-3 rounded-xl bg-muted/50 px-4 py-3 text-sm leading-relaxed text-foreground ring-1 ring-foreground/10"
            >
              <Warning className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <p>{t('settings.archive.warning')}</p>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor={passphraseId}>{t('settings.archive.passphraseLabel')}</Label>
              <SecretInput
                id={passphraseId}
                value={passphrase}
                onChange={e => setPassphrase(e.target.value)}
                autoComplete="new-password"
                aria-invalid={tooShort}
                autoFocus
              />
              <p className={tooShort ? 'text-sm text-destructive' : 'text-sm text-muted-foreground'}>
                {tooShort ? t('settings.archive.passphraseTooShort') : t('settings.archive.passphraseHint')}
              </p>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor={confirmationId}>{t('settings.archive.confirmationLabel')}</Label>
              <SecretInput
                id={confirmationId}
                value={confirmation}
                onChange={e => setConfirmation(e.target.value)}
                autoComplete="new-password"
                aria-invalid={mismatch}
              />
              {mismatch && (
                <p role="alert" className="text-sm text-destructive">
                  {t('settings.archive.mismatch')}
                </p>
              )}
            </div>

            {asksCredentials &&
              (mfaStatus?.enabled ? (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={totpId}>{t('auth.mfaCodeLabel')}</Label>
                  <Input
                    id={totpId}
                    type="text"
                    inputMode="numeric"
                    pattern="\d{6}"
                    maxLength={6}
                    value={totpCode}
                    onChange={e => setTotpCode(e.target.value)}
                    autoComplete="one-time-code"
                    required
                    placeholder="123456"
                    className="text-center text-lg tracking-widest font-mono"
                  />
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={passwordId}>{t('settings.currentPassword')}</Label>
                  <Input
                    id={passwordId}
                    type="password"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                  />
                </div>
              ))}

            {needsLock && (
              <AlertBanner variant="prominent" role="alert">
                {t('settings.archive.needsLock')}
              </AlertBanner>
            )}

            {error && <p role="alert" className="text-sm text-destructive">{error}</p>}

            <DialogFooter className="flex-col-reverse gap-2 sm:flex-row">
              <Button type="button" variant="ghost" onClick={close}>
                {t('settings.mfaCancel')}
              </Button>
              <Button type="submit" disabled={!canSubmit || exportMutation.isPending}>
                {exportMutation.isPending ? (
                  <CircleNotch className="size-4 animate-spin" />
                ) : (
                  <DownloadSimple className="size-4" />
                )}
                {t('settings.archive.download')}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
