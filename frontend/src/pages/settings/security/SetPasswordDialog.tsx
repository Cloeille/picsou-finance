import { useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import { KeyRound, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { formatSsoApiError } from '@/features/sso/errors'
import { useSetPassword } from '@/features/sso/hooks'
import { getErrorCode } from '@/lib/errors'
import { ReauthPanel } from './ReauthPanel'

const MIN_PASSWORD_LENGTH = 8
const MAX_PASSWORD_LENGTH = 128

interface SetPasswordDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Back from the IdP re-authentication: open on the password form. */
  resumed: boolean
  providerName: string
}

export function SetPasswordDialog({ open, onOpenChange, resumed, providerName }: SetPasswordDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.signInMethods.setPassword')}</DialogTitle>
          <DialogDescription>
            {t('settings.signInMethods.setPasswordDesc', { provider: providerName })}
          </DialogDescription>
        </DialogHeader>
        <SetPasswordSteps resumed={resumed} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function SetPasswordSteps({ resumed, onDone }: { resumed: boolean; onDone: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const save = useSetPassword()
  const passwordId = useId()
  const confirmationId = useId()
  const [confirmed, setConfirmed] = useState(resumed)
  const [expired, setExpired] = useState(false)
  const [password, setPassword] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const [error, setError] = useState<string | null>(null)

  if (!confirmed) {
    return <ReauthPanel action="set-password" expired={expired} onConfirmed={() => setConfirmed(true)} />
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(t('auth.validation.passwordTooShort'))
      return
    }
    if (password !== confirmation) {
      setError(t('settings.signInMethods.passwordMismatch'))
      return
    }
    setError(null)
    try {
      await save.mutateAsync(password)
      // A new password signs every other device out.
      void queryClient.invalidateQueries({ queryKey: ['mfa'] })
      onDone()
    } catch (err: unknown) {
      if (getErrorCode(err) === 'REAUTH_FAILED') {
        setExpired(true)
        setConfirmed(false)
      } else {
        setError(formatSsoApiError(err, t))
      }
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={passwordId}>{t('settings.newPassword')}</Label>
        <Input
          id={passwordId}
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          autoComplete="new-password"
          maxLength={MAX_PASSWORD_LENGTH}
          required
          autoFocus
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={confirmationId}>{t('settings.confirmPassword')}</Label>
        <Input
          id={confirmationId}
          type="password"
          value={confirmation}
          onChange={e => setConfirmation(e.target.value)}
          autoComplete="new-password"
          maxLength={MAX_PASSWORD_LENGTH}
          required
        />
      </div>
      {error && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? <Loader2 className="animate-spin" /> : <KeyRound />}
          {t('settings.signInMethods.setPassword')}
        </Button>
      </DialogFooter>
    </form>
  )
}
