import { useEffect, useEffectEvent, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { useLoginWithRememberMe } from '@/features/mfa/hooks'
import { useSetupStatus } from '@/features/setup/hooks'
import { ssoErrorMessage } from '@/features/sso/errors'
import { useSsoInfo } from '@/features/sso/hooks'
import { submitSsoStart } from '@/features/sso/start'
import { useAppStore } from '@/stores/app-store'
import { safeRedirect, isOAuthAuthorizeRedirect } from '@/lib/utils'
import { formatApiError, getErrorStatus } from '@/lib/errors'
import { consumeSsoRevalidate, rememberSsoPreference, ssoRememberPreference } from '@/lib/sso-revalidate'
import { Button } from '@/components/ui/button'
import { AlertBanner } from '@/components/shared/AlertBanner'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Eye, EyeSlash, Key, CircleNotch } from '@phosphor-icons/react'
import { SsoProgressScreen } from './SsoProgressScreen'

export function LoginPage() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(() => {
    const ssoError = searchParams.get('ssoError')
    return ssoError ? ssoErrorMessage(t, ssoError) : null
  })
  const [ssoRedirecting, setSsoRedirecting] = useState(false)
  const loginMutation = useLoginWithRememberMe()
  const demoMode = useAppStore(s => s.demoMode)
  const storeDesktopMode = useAppStore(s => s.desktopMode)
  const storeDesktopLockEnabled = useAppStore(s => s.desktopLockEnabled)
  const setupStatus = useSetupStatus(!demoMode)
  const desktopMode =
    setupStatus.data !== undefined ? Boolean(setupStatus.data.desktopMode) : storeDesktopMode
  const desktopLockEnabled =
    setupStatus.data !== undefined
      ? Boolean(setupStatus.data.desktopLockEnabled)
      : storeDesktopLockEnabled
  const showDesktopLockNotice = !demoMode && desktopMode && !desktopLockEnabled
  const navigate = useNavigate()
  const redirect = safeRedirect(searchParams.get('redirect'))
  const ssoInfo = useSsoInfo(!demoMode)
  const sso = !demoMode && ssoInfo.data?.enabled ? ssoInfo.data : null
  const ssoSettled = ssoInfo.isSuccess || ssoInfo.isError
  const revalidationHandled = useRef(false)

  const startSso = (remember: boolean) => {
    setSsoRedirecting(true)
    submitSsoStart({ intent: 'login', redirect, rememberMe: remember })
  }

  const handleSsoClick = () => {
    rememberSsoPreference(rememberMe)
    startSso(rememberMe)
  }

  // After an SSO_REVALIDATE refusal, restart the IdP round trip without a click. The one-shot
  // flag is read only once the SSO info has answered; read earlier, it would be spent while
  // the provider is still unknown.
  const restartRevalidation = useEffectEvent(() => {
    if (consumeSsoRevalidate() && sso) startSso(ssoRememberPreference())
  })
  useEffect(() => {
    if (!ssoSettled || revalidationHandled.current) return
    revalidationHandled.current = true
    restartRevalidation()
  }, [ssoSettled])

  // Back from the IdP can restore this page from the back/forward cache, spinner included.
  useEffect(() => {
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) setSsoRedirecting(false)
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [])

  // A /oauth2/ target is the backend authorize endpoint (native app login) — hand off with a
  // full-page navigation so the server resumes the flow; everything else is an in-app SPA route.
  const finishLogin = (target: string) => {
    if (isOAuthAuthorizeRedirect(target)) window.location.assign(target)
    else navigate(target, { replace: true })
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const result = await loginMutation.mutateAsync({ username, password, rememberMe })
      if (result.mfaRequired) {
        // Branch off to /login/mfa — the mfa_challenge cookie is now set.
        // Forward both the post-MFA redirect target and the rememberMe flag
        // so the user's preference survives the second hop. (Server-side, the
        // remember_me claim was already encoded into the mfa_challenge JWT —
        // the URL param is purely UI state for the trust-device checkbox.)
        const params = new URLSearchParams()
        if (redirect && redirect !== '/') params.set('redirect', redirect)
        if (rememberMe) params.set('rememberMe', '1')
        const qs = params.toString()
        navigate(`/login/mfa${qs ? `?${qs}` : ''}`)
        return
      }
      finishLogin(redirect)
    } catch (err: unknown) {
      const status = getErrorStatus(err)
      const ax = err as { response?: unknown; message?: string }
      if (!ax.response) {
        setError(desktopMode ? t('auth.networkErrorDesktop') : t('auth.networkError'))
      } else if (status === 401) {
        setError(t('auth.error'))
      } else {
        setError(formatApiError(err, t, 'auth.loginGenericError'))
      }
    }
  }

  if (ssoRedirecting) return <SsoProgressScreen message={t('auth.sso.redirecting')} />

  return (
    <div className="flex min-h-dvh items-center justify-center bg-background px-4 py-10">
      <div className="mx-auto flex w-full max-w-md flex-col gap-4">
        {showDesktopLockNotice && (
          <Card
            role="status"
            className="rounded-xl border-0 bg-muted/50 py-0 ring-1 ring-foreground/10"
          >
            <CardContent className="space-y-1 px-6 py-4 sm:px-8">
              <p className="text-sm font-semibold text-foreground">
                {t('desktop.oauthLogin.title')}
              </p>
              <p className="text-sm leading-relaxed text-muted-foreground">
                {t('desktop.oauthLogin.description')}
              </p>
            </CardContent>
          </Card>
        )}

        <Card className="rounded-xl border border-border/60 py-7 ring-0 sm:py-8">
          <CardHeader className="items-center gap-1.5 px-6 text-center sm:px-8">
            <CardTitle className="text-2xl font-semibold tracking-tight sm:text-3xl">
              {t('auth.login')}
            </CardTitle>
            <CardDescription className="text-sm leading-relaxed">
              {t('auth.loginTagline')}
            </CardDescription>
          </CardHeader>

          <CardContent className="px-6 sm:px-8">
            {!demoMode && ssoInfo.isError && (
              <AlertBanner
                variant="roomy"
                role="alert"
                className="mb-5"
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
                  onClick={handleSsoClick}
                  className="h-auto min-h-10 w-full whitespace-normal rounded-lg px-4 py-2.5 text-sm font-medium"
                >
                  <Key className="size-4" aria-hidden="true" />
                  {t('auth.sso.button', { provider: sso.providerName })}
                </Button>
                <div className="my-5 flex items-center gap-3">
                  <Separator className="h-px flex-1" />
                  <span className="text-xs text-muted-foreground">{t('auth.sso.or')}</span>
                  <Separator className="h-px flex-1" />
                </div>
              </>
            )}

            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="username" className="text-sm font-semibold">
                  {t('auth.username')}
                </Label>
                <Input
                  id="username"
                  type="text"
                  value={username}
                  onChange={e => setUsername(e.target.value)}
                  autoComplete="username"
                  required
                  placeholder="admin"
                  className="h-10 rounded-lg px-3.5 text-sm"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="password" className="text-sm font-semibold">
                  {t('auth.password')}
                </Label>
                <div className="relative">
                  <Input
                    id="password"
                    type={showPw ? 'text' : 'password'}
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    autoComplete="current-password"
                    required
                    placeholder="••••••••"
                    className="h-10 rounded-lg px-3.5 pr-11 text-sm"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPw(v => !v)}
                    className="absolute right-1 top-1/2 inline-flex size-8 -translate-y-1/2 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    aria-label={showPw ? t('auth.hidePassword') : t('auth.showPassword')}
                    aria-pressed={showPw}
                  >
                    {showPw ? (
                      <EyeSlash className="size-4" aria-hidden="true" />
                    ) : (
                      <Eye className="size-4" aria-hidden="true" />
                    )}
                  </button>
                </div>
              </div>

              <div className="flex items-start gap-2.5 pt-0.5">
                <input
                  id="rememberMe"
                  type="checkbox"
                  checked={rememberMe}
                  onChange={e => setRememberMe(e.target.checked)}
                  className="mt-0.5 size-4 shrink-0 rounded accent-primary"
                />
                <div className="flex flex-col gap-0.5">
                  <Label htmlFor="rememberMe" className="cursor-pointer text-sm font-medium leading-snug">
                    {t('auth.rememberMe')}
                  </Label>
                  <p className="text-sm text-muted-foreground">{t('auth.rememberMeDesc')}</p>
                </div>
              </div>

              {error && (
                <AlertBanner variant="prominent" role="alert">{error}</AlertBanner>
              )}

              <Button
                type="submit"
                disabled={loginMutation.isPending}
                className="mt-1 h-10 w-full rounded-lg text-sm font-medium"
              >
                {loginMutation.isPending && (
                  <CircleNotch className="size-4 animate-spin" />
                )}
                {loginMutation.isPending ? t('auth.loggingIn') : t('auth.loginButton')}
              </Button>
            </form>
          </CardContent>
        </Card>

        {demoMode && (
          <Card className="rounded-xl border border-border/60 bg-muted/40 py-0 ring-0">
            <CardContent className="px-6 py-4 sm:px-8">
              <p className="text-sm font-semibold text-foreground">{t('auth.demoMode')}</p>
              <p className="mt-0.5 text-sm text-muted-foreground">{t('auth.demoModeDesc')}</p>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
