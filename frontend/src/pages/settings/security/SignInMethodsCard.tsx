import { useEffect, useEffectEvent, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link2, Loader2, Unlink } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { authApi } from '@/features/auth/api'
import type { LinkedIdentity } from '@/features/sso/api'
import { formatSsoApiError } from '@/features/sso/errors'
import { useSignInMethods } from '@/features/sso/hooks'
import { submitSsoStart } from '@/features/sso/start'
import { useMounted } from '@/hooks/use-mounted'
import { getErrorCode } from '@/lib/errors'
import { restartSsoRevalidation } from '@/lib/sso-revalidate'
import { formatDateTime } from '@/lib/utils'
import { ReauthPanel, type ReauthAction } from './ReauthPanel'

const ROW = 'flex flex-col gap-3 p-3 @xl:flex-row @xl:items-center @xl:justify-between'
const ROW_ACTION = 'w-full @xl:w-auto'

export function SignInMethodsCard({ onAction }: { onAction: (action: ReauthAction) => void }) {
  const { t } = useTranslation()
  const { data: methods, isPending, error } = useSignInMethods()
  const hintId = useId()

  if (isPending) {
    return (
      <div className="flex justify-center py-4">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    )
  }
  if (!methods) return <p role="alert" className="text-sm text-destructive">{formatSsoApiError(error, t)}</p>
  // Without SSO there is no SSO UI, and a password-only account has nothing to manage here.
  if (!methods.ssoEnabled && methods.hasPassword && methods.identities.length === 0) return null

  const provider = methods.providerName
  const canTurnOff = methods.ssoEnabled && methods.identities.some(i => i.currentIssuer && Boolean(i.lastLoginAt))
  const currentIssuerCount = methods.identities.filter(i => i.currentIssuer).length
  // Same rule as the server: an account without a password keeps one identity of the current issuer.
  const isLastMethod = (identity: LinkedIdentity) =>
    !methods.hasPassword && currentIssuerCount - (identity.currentIssuer ? 1 : 0) === 0
  const passwordHintId = `${hintId}-password`

  return (
    <div className="space-y-3">
      <p className="text-sm font-medium">{t('settings.signInMethods.title')}</p>
      <ul className="divide-y rounded-lg border">
        <li className={ROW}>
          <div className="min-w-0 space-y-0.5">
            <p className="text-sm font-medium">{t('settings.signInMethods.password')}</p>
            <p className="text-xs text-muted-foreground">
              {methods.hasPassword
                ? t('settings.signInMethods.passwordOn')
                : t('settings.signInMethods.passwordOff', { provider })}
            </p>
            {methods.hasPassword && !canTurnOff && (
              <p id={passwordHintId} className="text-xs text-muted-foreground">
                {t('settings.signInMethods.turnOffHint', { provider })}
              </p>
            )}
          </div>
          {methods.hasPassword ? (
            <Button
              variant="outline"
              size="sm"
              className={ROW_ACTION}
              disabled={!canTurnOff}
              aria-describedby={canTurnOff ? undefined : passwordHintId}
              onClick={() => onAction('remove-password')}
            >
              {t('settings.signInMethods.turnOff')}
            </Button>
          ) : (
            <Button variant="outline" size="sm" className={ROW_ACTION} onClick={() => onAction('set-password')}>
              {t('settings.signInMethods.setPassword')}
            </Button>
          )}
        </li>
        {methods.identities.map(identity => {
          const locked = isLastMethod(identity)
          const lockedHintId = `${hintId}-${identity.id}`
          return (
            <li key={identity.id} className={ROW}>
              <div className="min-w-0 space-y-0.5">
                <p className="break-words text-sm font-medium">
                  <IdentityLabel identity={identity} providerName={provider} />
                </p>
                <p className="text-xs text-muted-foreground">
                  {identity.lastLoginAt
                    ? `${t('common.labelColon', { label: t('settings.signInMethods.lastSignIn') })} ${formatDateTime(identity.lastLoginAt)}`
                    : t('settings.signInMethods.neverUsed')}
                </p>
                {locked && (
                  <p id={lockedHintId} className="text-xs text-muted-foreground">
                    {t('settings.signInMethods.unlinkHint')}
                  </p>
                )}
              </div>
              <Button
                variant="outline"
                size="sm"
                className={ROW_ACTION}
                disabled={locked}
                aria-describedby={locked ? lockedHintId : undefined}
                onClick={() => onAction(`unlink-${identity.id}`)}
              >
                <Unlink />
                {t('settings.signInMethods.unlink')}
              </Button>
            </li>
          )
        })}
      </ul>
      {(methods.adminGroupManaged || methods.ssoEnabled) && (
        <div className="flex flex-col gap-3 @xl:flex-row @xl:items-center @xl:justify-between">
          {methods.adminGroupManaged && (
            <p className="text-xs text-muted-foreground">
              {t('settings.signInMethods.adminGroupNote', { provider })}
            </p>
          )}
          {methods.ssoEnabled && (
            <Button
              variant="outline"
              size="sm"
              className={`${ROW_ACTION} @xl:ml-auto`}
              onClick={() => onAction('link')}
            >
              <Link2 />
              {t('settings.signInMethods.link', { provider })}
            </Button>
          )}
        </div>
      )}
    </div>
  )
}

export function IdentityLabel({ identity, providerName }: { identity: LinkedIdentity; providerName: string }) {
  const { t } = useTranslation()
  return identity.email ?? identity.displayName ?? t('settings.signInMethods.identityFallback', { provider: providerName })
}

interface LinkIdentityDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** Back from the IdP re-authentication of a password-less account: link right away. */
  resumed: boolean
  providerName: string
}

export function LinkIdentityDialog({ open, onOpenChange, resumed, providerName }: LinkIdentityDialogProps) {
  const { t } = useTranslation()
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t('settings.signInMethods.linkTitle', { provider: providerName })}</DialogTitle>
          <DialogDescription>{t('settings.signInMethods.linkDesc', { provider: providerName })}</DialogDescription>
        </DialogHeader>
        <LinkSteps resumed={resumed} />
      </DialogContent>
    </Dialog>
  )
}

function LinkSteps({ resumed }: { resumed: boolean }) {
  const { t } = useTranslation()
  const [redirecting, setRedirecting] = useState(resumed)
  const [error, setError] = useState<string | null>(null)
  const resumeHandled = useRef(false)
  const mounted = useMounted()

  const startLink = async () => {
    setRedirecting(true)
    setError(null)
    try {
      // A fresh access token keeps the start guard and the IdP callback authenticated.
      await authApi.refresh()
      if (mounted.current) submitSsoStart({ intent: 'link', redirect: '/settings' })
    } catch (err: unknown) {
      if (getErrorCode(err) === 'SSO_REVALIDATE') {
        restartSsoRevalidation('/settings')
        return
      }
      setRedirecting(false)
      setError(formatSsoApiError(err, t))
    }
  }

  const startResumedLink = useEffectEvent(() => void startLink())
  useEffect(() => {
    if (!resumed || resumeHandled.current) return
    resumeHandled.current = true
    startResumedLink()
  }, [resumed])

  // Back from the IdP can restore this page from the back/forward cache, spinner included.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setRedirecting(false)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  if (redirecting) {
    return (
      <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        {t('auth.sso.redirecting')}
      </p>
    )
  }
  return (
    <div className="space-y-4">
      {error && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
          {error}
        </p>
      )}
      <ReauthPanel action="link" onConfirmed={() => void startLink()} />
    </div>
  )
}
