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
import { CircleNotch } from '@phosphor-icons/react'
import { useRegenerateRecoveryCodes } from '@/features/mfa/hooks'
import { RecoveryCodesView } from './RecoveryCodesView'
import { formatApiError, safeBackendMessage, getErrorStatus } from '@/lib/errors'

export function RecoveryCodesDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const regenerate = useRegenerateRecoveryCodes()
  const [currentPassword, setCurrentPassword] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [generated, setGenerated] = useState<string[] | null>(null)

  const reset = () => {
    setCurrentPassword('')
    setCode('')
    setError(null)
    setGenerated(null)
    regenerate.reset()
  }

  const close = () => {
    onOpenChange(false)
    setTimeout(reset, 250)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const data = await regenerate.mutateAsync({ currentPassword, code })
      setGenerated(data.recoveryCodes)
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      if (status === 400) setError(safeBackendMessage(err) ?? t('auth.mfaInvalidCode'))
      else setError(formatApiError(err, t))
    }
  }

  return (
    <Dialog open={open} onOpenChange={v => (v ? onOpenChange(true) : close())}>
      <DialogContent className="sm:max-w-md max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t('settings.mfaRegenerateTitle')}</DialogTitle>
          <DialogDescription>{t('settings.mfaRegenerateDesc')}</DialogDescription>
        </DialogHeader>

        {!generated && (
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="regen-pw">{t('settings.currentPassword')}</Label>
              <Input
                id="regen-pw"
                type="password"
                value={currentPassword}
                onChange={e => setCurrentPassword(e.target.value)}
                autoComplete="current-password"
                required
                autoFocus
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="regen-code">{t('auth.mfaCodeLabel')}</Label>
              <Input
                id="regen-code"
                type="text"
                inputMode="numeric"
                pattern="\d{6}"
                maxLength={6}
                value={code}
                onChange={e => setCode(e.target.value)}
                autoComplete="one-time-code"
                required
                placeholder="123456"
                className="text-center text-lg tracking-widest font-mono"
              />
            </div>

            {error && <p className="text-sm text-destructive">{error}</p>}

            <DialogFooter className="flex-col-reverse sm:flex-row gap-2">
              <Button type="button" variant="ghost" onClick={close}>
                {t('settings.mfaCancel')}
              </Button>
              <Button type="submit" disabled={regenerate.isPending || code.length !== 6}>
                {regenerate.isPending && <CircleNotch size={14} className=".5 animate-spin" />}
                {t('settings.mfaRegenerate')}
              </Button>
            </DialogFooter>
          </form>
        )}

        {generated && (
          <RecoveryCodesView
            codes={generated}
            saveLabel={t('settings.mfaDone')}
            onSaved={close}
          />
        )}
      </DialogContent>
    </Dialog>
  )
}
