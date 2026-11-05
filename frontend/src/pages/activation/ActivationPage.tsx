import { useState } from 'react'
import { useParams, useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { KeyRound } from 'lucide-react'
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
      <div className="flex min-h-screen items-center justify-center bg-background p-4">
        <Card className="w-full max-w-md">
          <CardHeader>
            <CardTitle>{t('activation.successTitle')}</CardTitle>
            <CardDescription>{t('activation.successRedirect')}</CardDescription>
          </CardHeader>
        </Card>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>{t('activation.title')}</CardTitle>
          <CardDescription>
            {sso ? t('activation.subtitleSso', { provider: sso.providerName }) : t('activation.subtitle')}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {/* Warning */}
          <div className="mb-6 rounded-lg border border-yellow-500/30 bg-yellow-500/10 p-4 text-sm text-yellow-900 dark:text-yellow-200">
            <p className="font-semibold mb-1">{t('activation.noticeTitle')}</p>
            <p>{t('activation.noticeBody')}</p>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="flex items-start gap-2">
              <input
                type="checkbox"
                id="acknowledge"
                checked={acknowledged}
                onChange={(e) => setAcknowledged(e.target.checked)}
                className="mt-1"
              />
              <Label htmlFor="acknowledge" className="text-sm">
                {t('activation.acknowledge')}
              </Label>
            </div>

            {ssoInfo.isError && (
              <AlertBanner
                variant="roomy"
                action={
                  <Button type="button" variant="outline" size="sm" onClick={() => void ssoInfo.refetch()}>
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
                  className="h-auto min-h-10 w-full whitespace-normal py-2"
                >
                  <KeyRound aria-hidden="true" />
                  {t('activation.ssoButton', { provider: sso.providerName })}
                </Button>
                <div className="flex items-center gap-3">
                  <Separator className="h-px flex-1" />
                  <span className="text-sm text-muted-foreground">{t('auth.sso.or')}</span>
                  <Separator className="h-px flex-1" />
                </div>
              </>
            )}

            <div className="space-y-2">
              <Label htmlFor="password">{t('activation.password')}</Label>
              <Input
                id="password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t('activation.passwordPlaceholder')}
                minLength={8}
                required
              />
            </div>

            <div className="space-y-2">
              <Label htmlFor="confirmPassword">{t('activation.confirmPassword')}</Label>
              <Input
                id="confirmPassword"
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder={t('activation.confirmPlaceholder')}
                required
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-destructive">{error}</p>
            )}

            <Button type="submit" className="w-full" disabled={!acknowledged || loading}>
              {loading ? t('activation.submitting') : t('activation.submit')}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
