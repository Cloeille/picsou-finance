import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useLoginWithRememberMe } from '@/features/mfa/hooks'
import { useAppStore } from '@/stores/app-store'
import { safeRedirect, isOAuthAuthorizeRedirect } from '@/lib/utils'
import { formatApiError, getErrorStatus } from '@/lib/errors'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '@/components/ui/card'
import { Eye, EyeOff, Loader2 } from 'lucide-react'

export function LoginPage() {
  const { t } = useTranslation()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [rememberMe, setRememberMe] = useState(false)
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const loginMutation = useLoginWithRememberMe()
  const demoMode = useAppStore(s => s.demoMode)
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirect = safeRedirect(searchParams.get('redirect'))

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
        setError(t('auth.networkError'))
      } else if (status === 401) {
        setError(t('auth.error'))
      } else {
        setError(formatApiError(err, t, 'auth.loginGenericError'))
      }
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4 py-10">
      <div className="mx-auto flex w-full max-w-2xl flex-col gap-4">
        <Card className="py-8 sm:py-10">
          <CardHeader className="items-center gap-2 px-6 text-center sm:px-10">
            <CardTitle className="text-3xl font-semibold sm:text-4xl">{t('auth.login')}</CardTitle>
            <CardDescription className="mt-1 text-base leading-6">
              {t('auth.loginTagline')}
            </CardDescription>
          </CardHeader>

          <CardContent className="px-6 sm:px-10">
            <form onSubmit={handleSubmit} className="space-y-5">
              <div className="space-y-2">
                <Label htmlFor="username" className="text-base font-semibold">
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
                  className="h-12 rounded-xl px-4 text-base md:text-base"
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="password" className="text-base font-semibold">
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
                    className="h-12 rounded-xl px-4 pr-14 text-base md:text-base"
                  />
                  <button
                    type="button"
                    onClick={() => setShowPw(v => !v)}
                    className="absolute right-2 top-1/2 inline-flex h-10 w-10 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                    aria-label={showPw ? t('auth.hidePassword') : t('auth.showPassword')}
                    aria-pressed={showPw}
                  >
                    {showPw ? (
                      <EyeOff className="size-5" aria-hidden="true" />
                    ) : (
                      <Eye className="size-5" aria-hidden="true" />
                    )}
                  </button>
                </div>
              </div>

              <div className="flex items-start gap-3 rounded-xl py-1">
                <input
                  id="rememberMe"
                  type="checkbox"
                  checked={rememberMe}
                  onChange={e => setRememberMe(e.target.checked)}
                  className="mt-1 h-5 w-5 shrink-0 rounded-md accent-primary"
                />
                <div className="flex flex-col">
                  <Label htmlFor="rememberMe" className="cursor-pointer text-base font-semibold">
                    {t('auth.rememberMe')}
                  </Label>
                  <p className="text-sm text-muted-foreground">{t('auth.rememberMeDesc')}</p>
                </div>
              </div>

              {error && (
                <p role="alert" className="rounded-xl bg-destructive/10 px-4 py-3 text-sm font-medium text-destructive">
                  {error}
                </p>
              )}

              <Button
                type="submit"
                disabled={loginMutation.isPending}
                className="mt-2 h-12 w-full rounded-full px-8 text-base transition-transform active:scale-[0.96]"
              >
                {loginMutation.isPending && (
                  <Loader2 className="size-5 animate-spin" />
                )}
                {loginMutation.isPending ? t('auth.loggingIn') : t('auth.loginButton')}
              </Button>
            </form>
          </CardContent>
        </Card>

        {demoMode && (
          <Card className="border-muted bg-muted/40">
            <CardContent className="px-6 py-5 sm:px-10">
              <p className="text-base font-semibold text-foreground">{t('auth.demoMode')}</p>
              <p className="mt-1 text-sm text-muted-foreground">{t('auth.demoModeDesc')}</p>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
