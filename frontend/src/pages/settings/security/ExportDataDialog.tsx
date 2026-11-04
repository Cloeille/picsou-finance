import { useState } from 'react'
import { useTranslation } from 'react-i18next'
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
import { Switch } from '@/components/ui/switch'
import { Loader2, Download } from 'lucide-react'
import { useMfaStatus } from '@/features/mfa/hooks'
import { useExportData } from '@/features/export/hooks'
import { useSignInMethods } from '@/features/sso/hooks'
import { formatApiError, getErrorStatus } from '@/lib/errors'
import { ReauthPanel } from './ReauthPanel'

/**
 * GDPR self-service data export. Asks the user for re-auth (TOTP if 2FA is
 * on, current password otherwise) and an opt-in toggle for the historical
 * balance snapshots (off by default — they can dwarf the rest of the
 * archive). A password-less account confirms through the IdP instead
 * (ReauthPanel), and the resulting proof cookie authorizes the export.
 *
 * On success the browser starts downloading a ZIP and the dialog closes.
 * The form fields are cleared after the close transition so re-opening
 * doesn't show stale credentials.
 */
export function ExportDataDialog({
  open,
  onOpenChange,
  resumed = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Mounted on the way back from the IdP re-authentication (`resume=export`): skip the confirmation. */
  resumed?: boolean
}) {
  const { t } = useTranslation()
  const { data: mfaStatus } = useMfaStatus()
  const signInMethods = useSignInMethods()
  const exportMutation = useExportData()

  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [includeBalanceSnapshots, setIncludeBalanceSnapshots] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reauthConfirmed, setReauthConfirmed] = useState(resumed)
  const [reauthExpired, setReauthExpired] = useState(false)
  const passwordless = signInMethods.data?.hasPassword === false
  const step = signInMethods.isPending ? 'loading' : passwordless && !reauthConfirmed ? 'reauth' : 'form'

  const reset = () => {
    setPassword('')
    setTotpCode('')
    setIncludeBalanceSnapshots(false)
    setError(null)
    setReauthConfirmed(false)
    setReauthExpired(false)
    exportMutation.reset()
  }

  const close = () => {
    onOpenChange(false)
    setTimeout(reset, 250)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await exportMutation.mutateAsync({
        // Password-less: the proof cookie minted through ReauthPanel authorizes the export.
        ...(passwordless ? {} : { reAuth: mfaStatus?.enabled ? { totpCode } : { password } }),
        includeBalanceSnapshots,
      })
      close()
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      if (status === 401 && passwordless) {
        // The ZIP endpoint answers with a Blob, so only the status is readable: the proof expired.
        setReauthConfirmed(false)
        setReauthExpired(true)
      } else if (status === 401) setError(t('settings.exportInvalidCredentials'))
      else if (status === 429) setError(t('settings.exportRateLimited'))
      else setError(formatApiError(err, t))
    }
  }

  return (
    <Dialog open={open} onOpenChange={v => (v ? onOpenChange(true) : close())}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.exportTitle')}</DialogTitle>
          <DialogDescription>{t('settings.exportDesc')}</DialogDescription>
        </DialogHeader>

        {step === 'loading' && (
          <div className="flex justify-center py-4">
            <Loader2 className="size-5 animate-spin text-muted-foreground" />
          </div>
        )}

        {step === 'reauth' && (
          <ReauthPanel
            action="export"
            expired={reauthExpired}
            onConfirmed={() => {
              setReauthConfirmed(true)
              setReauthExpired(false)
            }}
          />
        )}

        {step === 'form' && (
          <form onSubmit={handleSubmit} className="space-y-4">
            {!passwordless && (mfaStatus?.enabled ? (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="export-totp">{t('auth.mfaCodeLabel')}</Label>
                <Input
                  id="export-totp"
                  type="text"
                  inputMode="numeric"
                  pattern="\d{6}"
                  maxLength={6}
                  value={totpCode}
                  onChange={e => setTotpCode(e.target.value)}
                  autoComplete="one-time-code"
                  required
                  placeholder="123456"
                  autoFocus
                  className="text-center text-lg tracking-widest font-mono"
                />
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor="export-pw">{t('settings.currentPassword')}</Label>
                <Input
                  id="export-pw"
                  type="password"
                  value={password}
                  onChange={e => setPassword(e.target.value)}
                  autoComplete="current-password"
                  required
                  autoFocus
                />
              </div>
            ))}

            <div className="flex items-start gap-3 rounded-md border p-3">
              <Switch
                id="export-snapshots"
                checked={includeBalanceSnapshots}
                onCheckedChange={setIncludeBalanceSnapshots}
              />
              <div className="flex flex-col gap-0.5">
                <Label htmlFor="export-snapshots" className="text-sm font-medium cursor-pointer">
                  {t('settings.exportIncludeSnapshots')}
                </Label>
                <p className="text-xs text-muted-foreground">
                  {t('settings.exportIncludeSnapshotsHint')}
                </p>
              </div>
            </div>

            {error && <p className="text-sm text-destructive">{error}</p>}

            <DialogFooter className="flex-col-reverse sm:flex-row gap-2">
              <Button type="button" variant="ghost" onClick={close}>
                {t('settings.mfaCancel')}
              </Button>
              <Button type="submit" disabled={exportMutation.isPending}>
                {exportMutation.isPending ? (
                  <Loader2 size={14} className="mr-1.5 animate-spin" />
                ) : (
                  <Download size={14} className="mr-1.5" />
                )}
                {t('settings.exportDownload')}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
