import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useVerifyMfa } from '@/features/mfa/hooks'
import { safeRedirect, isOAuthAuthorizeRedirect } from '@/lib/utils'
import { formatApiError, getErrorStatus } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Loader2 } from 'lucide-react'

/**
 * Second step of the MFA login flow. Reached only after `/auth/login`
 * returned `mfaRequired:true`; relies on the mfa_challenge HttpOnly cookie
 * the server set on that response. No `from`-state validation here — the
 * server enforces the challenge cookie's existence and freshness.
 */
export function MfaChallengePage() {
  const { t } = useTranslation()
  const verify = useVerifyMfa()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))
  const rememberMeFromUrl = searchParams.get('rememberMe') === '1'

  const [code, setCode] = useState('')
  const [isRecoveryCode, setIsRecoveryCode] = useState(false)
  // Trust-device checkbox: pre-checked iff the user ticked "Remember Me" on the
  // login form. Asking again here would be redundant, but they can opt out
  // before submitting the second factor.
  const [trustDevice, setTrustDevice] = useState(rememberMeFromUrl)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await verify.mutateAsync({ code, isRecoveryCode, trustDevice })
      // A /oauth2/ target (native app login) is server-handled — full-navigate to resume the
      // OAuth flow; any other target is an in-app SPA route.
      if (isOAuthAuthorizeRedirect(redirect)) window.location.assign(redirect)
      else navigate(redirect, { replace: true })
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      const ax = err as { response?: unknown; message?: string }
      if (!ax.response) {
        setError(t('auth.networkError'))
      } else if (status === 401) {
        // Most likely the mfa_challenge cookie expired (default 5 minutes)
        // or the user lingered on this page. Send them back to /login.
        setError(t('auth.mfaSessionExpired'))
        setTimeout(() => navigate('/login', { replace: true }), 1500)
      } else if (status === 429) {
        setError(t('auth.mfaTooManyAttempts'))
      } else if (status === 400) {
        setError(t('auth.mfaInvalidCode'))
      } else {
        setError(formatApiError(err, t, 'auth.mfaGenericError'))
      }
    }
  }

  const codeInputId = 'mfa-code'
  const codePattern = isRecoveryCode ? undefined : '\\d{6}'
  const codeMaxLength = isRecoveryCode ? 16 : 6
  const codeInputMode = isRecoveryCode ? 'text' : 'numeric'

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
        <Card className="py-8 sm:py-10">
          <CardHeader className="items-center gap-2 px-6 text-center sm:px-10">
            <CardTitle className="text-3xl font-semibold sm:text-4xl">{t('auth.mfaTitle')}</CardTitle>
            <CardDescription className="mt-1 max-w-lg text-base leading-6">
              {isRecoveryCode ? t('auth.mfaRecoveryLabel') : t('auth.mfaDesc')}
            </CardDescription>
          </CardHeader>

          <CardContent className="px-6 sm:px-10">
            <form onSubmit={handleSubmit} className="space-y-5">
              <div className="space-y-2">
                <Label htmlFor={codeInputId} className="text-base font-semibold">
                  {isRecoveryCode ? t('auth.mfaRecoveryLabel') : t('auth.mfaCodeLabel')}
                </Label>
                <Input
                  id={codeInputId}
                  type="text"
                  inputMode={codeInputMode}
                  pattern={codePattern}
                  maxLength={codeMaxLength}
                  value={code}
                  onChange={e => setCode(e.target.value)}
                  autoComplete="one-time-code"
                  autoFocus
                  required
                  placeholder={isRecoveryCode ? '12345678' : '123456'}
                  className="h-12 rounded-xl px-4 text-center font-mono text-lg md:text-lg"
                />
              </div>

              <button
                type="button"
                onClick={() => {
                  setIsRecoveryCode(v => !v)
                  setCode('')
                  setError(null)
                }}
                className="text-sm font-medium text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
              >
                {isRecoveryCode ? t('auth.mfaUseTotp') : t('auth.mfaUseRecovery')}
              </button>

              <div className="flex items-start gap-3 rounded-xl py-1">
                <input
                  id="trustDevice"
                  type="checkbox"
                  checked={trustDevice}
                  onChange={e => setTrustDevice(e.target.checked)}
                  className="mt-1 h-5 w-5 shrink-0 rounded-md accent-primary"
                />
                <div className="flex flex-col">
                  <Label htmlFor="trustDevice" className="cursor-pointer text-base font-semibold">
                    {t('auth.mfaTrustDevice')}
                  </Label>
                  <p className="text-sm text-muted-foreground">{t('auth.mfaTrustDeviceDesc')}</p>
                </div>
              </div>

              {error && (
                <p role="alert" className="rounded-xl bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive">
                  {error}
                </p>
              )}

              <Button
                type="submit"
                disabled={verify.isPending || code.length === 0}
                className="mt-2 h-12 w-full rounded-full px-8 text-base transition-transform active:scale-[0.96]"
              >
                {verify.isPending && <Loader2 className="size-5 animate-spin" />}
                {verify.isPending ? t('auth.mfaVerifying') : t('auth.mfaVerifyButton')}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
