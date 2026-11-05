import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { KeyRound, Loader2, ShieldCheck } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { DialogFooter } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { authApi } from '@/features/auth/api'
import { formatSsoApiError, ssoErrorMessage } from '@/features/sso/errors'
import { useReauth, useSignInMethods } from '@/features/sso/hooks'
import { submitSsoStart } from '@/features/sso/start'
import { useMounted } from '@/hooks/use-mounted'
import { getErrorCode } from '@/lib/errors'
import { restartSsoRevalidation } from '@/lib/sso-revalidate'

/** The action a confirmation unlocks. It is also the `resume` value an IdP round trip returns with. */
export type ReauthAction = 'export' | 'remove-password' | 'set-password' | 'link' | `unlink-${number}`

interface ReauthPanelProps {
  action: ReauthAction
  onConfirmed: () => void
  /** The final action answered REAUTH_FAILED: the proof expired before it was used. */
  expired?: boolean
}

/**
 * Confirms the user's identity before a credential change or an export. With a password, the
 * password (plus the TOTP code when 2FA is on: the server requires both) mints the proof cookie
 * here. Without one, the proof comes from a forced IdP sign-in, and Settings resumes `action`
 * from the URL on the way back.
 */
export function ReauthPanel({ action, onConfirmed, expired = false }: ReauthPanelProps) {
  const { t } = useTranslation()
  const methods = useSignInMethods()

  if (methods.isPending) {
    return (
      <div className="flex justify-center py-4">
        <Loader2 className="size-5 animate-spin text-muted-foreground" />
      </div>
    )
  }
  if (!methods.data) return <PanelAlert message={formatSsoApiError(methods.error, t)} />

  const notice = expired ? t('reauth.expired') : null
  if (methods.data.hasPassword) {
    return <PasswordConfirmation mfaEnabled={methods.data.mfaEnabled} notice={notice} onConfirmed={onConfirmed} />
  }
  if (!methods.data.ssoEnabled) return <PanelAlert message={ssoErrorMessage(t, 'SSO_DISABLED')} />
  return <ProviderConfirmation action={action} providerName={methods.data.providerName} notice={notice} />
}

function PanelAlert({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {message}
    </p>
  )
}

function PasswordConfirmation({
  mfaEnabled,
  notice,
  onConfirmed,
}: {
  mfaEnabled: boolean
  notice: string | null
  onConfirmed: () => void
}) {
  const { t } = useTranslation()
  const reauth = useReauth()
  const passwordId = useId()
  const codeId = useId()
  const [password, setPassword] = useState('')
  const [totpCode, setTotpCode] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await reauth.mutateAsync(mfaEnabled ? { password, totpCode } : { password })
      onConfirmed()
    } catch (err: unknown) {
      setError(formatSsoApiError(err, t))
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={passwordId}>{t('settings.currentPassword')}</Label>
        <Input
          id={passwordId}
          type="password"
          value={password}
          onChange={e => setPassword(e.target.value)}
          autoComplete="current-password"
          required
          autoFocus
        />
      </div>
      {mfaEnabled && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={codeId}>{t('auth.mfaCodeLabel')}</Label>
          <Input
            id={codeId}
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
      )}
      <PanelAlert message={error ?? notice} />
      <DialogFooter>
        <Button type="submit" disabled={reauth.isPending}>
          {reauth.isPending ? <Loader2 className="animate-spin" /> : <ShieldCheck />}
          {t('reauth.confirm')}
        </Button>
      </DialogFooter>
    </form>
  )
}

function ProviderConfirmation({
  action,
  providerName,
  notice,
}: {
  action: ReauthAction
  providerName: string
  notice: string | null
}) {
  const { t } = useTranslation()
  const [redirecting, setRedirecting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const mounted = useMounted()

  // Back from the IdP can restore this page from the back/forward cache, spinner included.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setRedirecting(false)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  const confirm = async () => {
    setRedirecting(true)
    setError(null)
    try {
      // A fresh access token keeps the start guard and the IdP callback authenticated.
      await authApi.refresh()
      if (mounted.current) submitSsoStart({ intent: 'reauth', redirect: `/settings?resume=${action}` })
    } catch (err: unknown) {
      if (!mounted.current) return
      if (getErrorCode(err) === 'SSO_REVALIDATE') {
        restartSsoRevalidation('/settings')
        return
      }
      setRedirecting(false)
      setError(formatSsoApiError(err, t))
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-muted-foreground">{t('reauth.ssoHint', { provider: providerName })}</p>
      <PanelAlert message={error ?? notice} />
      <DialogFooter>
        <Button type="button" onClick={() => void confirm()} disabled={redirecting}>
          {redirecting ? <Loader2 className="animate-spin" /> : <KeyRound />}
          {t('reauth.confirmWith', { provider: providerName })}
        </Button>
      </DialogFooter>
    </div>
  )
}
