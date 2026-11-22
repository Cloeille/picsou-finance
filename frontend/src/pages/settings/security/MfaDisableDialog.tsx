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
import { useMfaDisable } from '@/features/mfa/hooks'
import { getErrorStatus, getErrorDetail } from '@/lib/errors'

export function MfaDisableDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const { t } = useTranslation()
  const disable = useMfaDisable()
  const [currentPassword, setCurrentPassword] = useState('')
  const [code, setCode] = useState('')
  const [isRecoveryCode, setIsRecoveryCode] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const reset = () => {
    setCurrentPassword('')
    setCode('')
    setIsRecoveryCode(false)
    setError(null)
    disable.reset()
  }

  const close = () => {
    onOpenChange(false)
    setTimeout(reset, 250)
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await disable.mutateAsync({ currentPassword, code, isRecoveryCode })
      close()
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      if (status === 400) setError(getErrorDetail(err) ?? t('auth.mfaInvalidCode'))
      else setError(`${status ?? ''} — ${(err as { message?: string })?.message ?? 'Error'}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={v => (v ? onOpenChange(true) : close())}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.mfaDisableTitle')}</DialogTitle>
          <DialogDescription>{t('settings.mfaDisableDesc')}</DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="disable-pw">{t('settings.currentPassword')}</Label>
            <Input
              id="disable-pw"
              type="password"
              value={currentPassword}
              onChange={e => setCurrentPassword(e.target.value)}
              autoComplete="current-password"
              required
              autoFocus
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="disable-code">
              {isRecoveryCode ? t('auth.mfaRecoveryLabel') : t('auth.mfaCodeLabel')}
            </Label>
            <Input
              id="disable-code"
              type="text"
              inputMode={isRecoveryCode ? 'text' : 'numeric'}
              pattern={isRecoveryCode ? undefined : '\\d{6}'}
              maxLength={isRecoveryCode ? 16 : 6}
              value={code}
              onChange={e => setCode(e.target.value)}
              autoComplete="one-time-code"
              required
              placeholder={isRecoveryCode ? '12345678' : '123456'}
              className="text-center text-lg tracking-widest font-mono"
            />
          </div>

          <button
            type="button"
            onClick={() => {
              setIsRecoveryCode(v => !v)
              setCode('')
              setError(null)
            }}
            className="text-xs text-muted-foreground hover:text-foreground underline-offset-4 hover:underline self-start"
          >
            {isRecoveryCode ? t('auth.mfaUseTotp') : t('auth.mfaUseRecovery')}
          </button>

          {error && <p className="text-sm text-destructive">{error}</p>}

          <DialogFooter className="flex-col-reverse sm:flex-row gap-2">
            <Button type="button" variant="ghost" onClick={close}>
              {t('settings.mfaCancel')}
            </Button>
            <Button type="submit" variant="destructive" disabled={disable.isPending}>
              {disable.isPending && <CircleNotch size={14} className=".5 animate-spin" />}
              {t('settings.mfaDisable')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
