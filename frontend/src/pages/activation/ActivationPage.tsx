import { useState } from 'react'
import { useParams, useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Key } from '@phosphor-icons/react'
import { authApi } from '@/features/auth/api'
import { ssoErrorMessage } from '@/features/sso/errors'
import { useSsoInfo } from '@/features/sso/hooks'
import { submitSsoStart } from '@/features/sso/start'
import { formatApiError, getErrorStatus } from '@/lib/errors'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'

export function ActivationPage() {
  const { t } = useTranslation()
  const { token } = useParams<{ token: string }>()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const ssoInfo = useSsoInfo()
  const sso = ssoInfo.data?.enabled ? ssoInfo.data : null
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const [error, setError] = useState<string | null>(() => {
    const ssoError = searchParams.get('ssoError')
    return ssoError ? ssoErrorMessage(t, ssoError) : null
  })
  const [loading, setLoading] = useState(false)
  const [success, setSuccess] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)

    if (!acknowledged) {
      setError(t('activation.errors.acknowledgeRequired'))
      return
    }
    if (password.length < 8) {
      setError(t('activation.errors.passwordTooShort'))
      return
    }
    if (password !== confirmPassword) {
      setError(t('activation.errors.passwordMismatch'))
      return
    }
    if (!token) {
      setError(t('activation.errors.invalidLink'))
      return
    }

    setLoading(true)
    try {
      await authApi.activate(token, password, true)
      setSuccess(true)
      setTimeout(() => navigate('/login'), 2000)
    } catch (err: unknown) {
      // An unknown token answers 401 and an expired one 400: a dead link, not an expired session.
      const status = getErrorStatus(err)
      setError(
        status === 400 || status === 401
          ? t('activation.errors.failed')
          : formatApiError(err, t, 'activation.errors.failed'),
      )
    } finally {
      setLoading(false)
    }
  }

  if (success) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
        <Card className="w-full max-w-md rounded-xl border border-border/60 py-7 ring-0 sm:py-8">
          <CardHeader className="items-center gap-1.5 px-6 text-center sm:px-8">
            <CardTitle className="text-2xl font-semibold tracking-tight">{t('activation.successTitle')}</CardTitle>
            <CardDescription className="text-sm leading-relaxed">{t('activation.successRedirect')}</CardDescription>
          </CardHeader>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <Card className="w-full max-w-md rounded-xl border border-border/60 py-7 ring-0 sm:py-8">
        <CardHeader className="items-center gap-1.5 px-6 text-center sm:px-8">
          <CardTitle className="text-2xl font-semibold tracking-tight">{t('activation.title')}</CardTitle>
          <CardDescription className="text-sm leading-relaxed">
            {sso ? t('activation.subtitleSso', { provider: sso.providerName }) : t('activation.subtitle')}
          </CardDescription>
        </CardHeader>
        <CardContent className="px-6 sm:px-8">
          {/* Warning */}
          <div className="mb-5 rounded-xl border border-border bg-muted/40 p-4 text-sm leading-relaxed text-muted-foreground">
            <p className="mb-1 text-sm font-semibold">{t('activation.noticeTitle')}</p>
            <p>{t('activation.noticeBody')}</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="flex items-start gap-2.5">
              <input
                type="checkbox"
                id="acknowledge"
                checked={acknowledged}
                onChange={(e) => setAcknowledged(e.target.checked)}
                className="mt-0.5 size-4 shrink-0 rounded accent-primary"
              />
              <Label htmlFor="acknowledge" className="cursor-pointer text-sm leading-snug">
                {t('activation.acknowledge')}
              </Label>
            </div>

            {ssoInfo.isError && (
              <AlertBanner
                variant="roomy"
                role="alert"
                action={
                  <Button type="button" variant="outline" size="sm" onClick={() => void ssoInfo.refetch()} className="rounded-lg">
                    {t('common.retry')}
                  </Button>
                }
              >
                {t('auth.sso.infoError')}
              </AlertBanner>
            )}

            {sso && (
              <>
                <Button
                  type="button"
                  variant="outline"
                  disabled={!acknowledged}
                  onClick={() => submitSsoStart({ intent: 'activate', token, ack: true })}
                  className="h-auto min-h-10 w-full whitespace-normal rounded-lg px-4 py-2.5 text-sm font-medium"
                >
                  <Key className="size-4" aria-hidden="true" />
                  {t('activation.ssoButton', { provider: sso.providerName })}
                </Button>
                <div className="flex items-center gap-3">
                  <Separator className="h-px flex-1" />
                  <span className="text-xs text-muted-foreground">{t('auth.sso.or')}</span>
                  <Separator className="h-px flex-1" />
                </div>
              </>
            )}

            <div className="space-y-2">
              <Label htmlFor="password" className="text-sm font-semibold">{t('activation.password')}</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('activation.passwordPlaceholder')}
                minLength={8}
                required
                className="h-10 rounded-lg px-3.5 text-sm"
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="confirmPassword" className="text-sm font-semibold">{t('activation.confirmPassword')}</Label>
              <Input
                id="confirmPassword"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder={t('activation.confirmPlaceholder')}
                required
                className="h-10 rounded-lg px-3.5 text-sm"
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-destructive">{error}</p>
            )}

            <Button type="submit" className="h-10 w-full rounded-lg text-sm font-medium" disabled={!acknowledged || loading}>
              {loading ? t('activation.submitting') : t('activation.submit')}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
