import { useState } from 'react'
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
import { formatSsoApiError } from '@/features/sso/errors'
import { useRemovePassword } from '@/features/sso/hooks'
import { getErrorCode } from '@/lib/errors'
import { ReauthPanel } from './ReauthPanel'

interface RemovePasswordDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Back from the IdP re-authentication: open on the final confirmation. */
  resumed: boolean
  providerName: string
}

export function RemovePasswordDialog({ open, onOpenChange, resumed, providerName }: RemovePasswordDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.signInMethods.removePasswordTitle')}</DialogTitle>
          <DialogDescription>
            {t('settings.signInMethods.removePasswordDesc', { provider: providerName })}
          </DialogDescription>
        </DialogHeader>
        <RemovePasswordSteps resumed={resumed} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function RemovePasswordSteps({ resumed, onDone }: { resumed: boolean; onDone: () => void }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const removePassword = useRemovePassword()
  const [confirmed, setConfirmed] = useState(resumed)
  const [expired, setExpired] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!confirmed) {
    return <ReauthPanel action="remove-password" expired={expired} onConfirmed={() => setConfirmed(true)} />
  }

  const handleConfirm = async () => {
    setError(null)
    try {
      await removePassword.mutateAsync(undefined)
      // The server also turned 2FA off and revoked every Remember-me session.
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
    <>
      {error && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onDone}>
          {t('common.cancel')}
        </Button>
        <Button
          type="button"
          variant="destructive"
          onClick={() => void handleConfirm()}
          disabled={removePassword.isPending}
        >
          {removePassword.isPending ? <Loader2 className="animate-spin" /> : <KeyRound />}
          {t('settings.signInMethods.turnOff')}
        </Button>
      </DialogFooter>
    </>
  )
}
