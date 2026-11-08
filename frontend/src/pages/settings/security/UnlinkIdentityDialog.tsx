import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, Unlink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import type { LinkedIdentity } from '@/features/sso/api'
import { formatSsoApiError } from '@/features/sso/errors'
import { useUnlinkIdentity } from '@/features/sso/hooks'
import { getErrorCode } from '@/lib/errors'
import { ReauthPanel } from './ReauthPanel'
import { IdentityLabel } from './SignInMethodsCard'

interface UnlinkIdentityDialogProps {
  /** The identity to unlink; the dialog is open while it is set. */
  identity: LinkedIdentity | null
  onOpenChange: (open: boolean) => void
  /** Back from the IdP re-authentication: open on the final confirmation. */
  resumed: boolean
  providerName: string
}

export function UnlinkIdentityDialog({ identity, onOpenChange, resumed, providerName }: UnlinkIdentityDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={identity !== null} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.signInMethods.unlinkTitle')}</DialogTitle>
          <DialogDescription>{t('settings.signInMethods.unlinkDesc', { provider: providerName })}</DialogDescription>
        </DialogHeader>
        {identity && (
          <>
            <p className="break-words text-sm font-medium">
              <IdentityLabel identity={identity} providerName={providerName} />
            </p>
            <UnlinkSteps
              key={identity.id}
              identityId={identity.id}
              resumed={resumed}
              onDone={() => onOpenChange(false)}
            />
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}

function UnlinkSteps({ identityId, resumed, onDone }: { identityId: number; resumed: boolean; onDone: () => void }) {
  const { t } = useTranslation()
  const unlink = useUnlinkIdentity()
  const [confirmed, setConfirmed] = useState(resumed)
  const [expired, setExpired] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!confirmed) {
    return <ReauthPanel action={`unlink-${identityId}`} expired={expired} onConfirmed={() => setConfirmed(true)} />
  }

  const handleConfirm = async () => {
    setError(null)
    try {
      await unlink.mutateAsync(identityId)
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
        <Button type="button" variant="destructive" onClick={() => void handleConfirm()} disabled={unlink.isPending}>
          {unlink.isPending ? <Loader2 className="animate-spin" /> : <Unlink />}
          {t('settings.signInMethods.unlink')}
        </Button>
      </DialogFooter>
    </>
  )
}
