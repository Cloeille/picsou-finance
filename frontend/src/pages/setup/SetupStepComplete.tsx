import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { CheckCircle, CircleNotch } from '@phosphor-icons/react'
import { Button } from '@/components/ui/button'
import { Confetti } from './components/Confetti'
import { INTEGRATION_LOGOS } from './components/IntegrationLogos'
import { useCompleteSetup, useSetupStatus } from '@/features/setup/hooks'
import { useSetupFlowStore, type IntegrationKey } from '@/stores/setup-flow-store'
import { consumeSetupCredentials } from '@/stores/setup-credentials'
import { authApi } from '@/features/auth/api'
import { useAuthStore } from '@/stores/auth-store'

type AutoLoginState = 'idle' | 'attempting' | 'success' | 'failed'

/**
 * Final screen. On mount:
 *   1. POST /api/setup/complete to flip setup.state to COMPLETE
 *   2. Immediately attempt auto-login with the in-memory credentials
 *      stashed by SetupStepAdmin (cleared here — one-shot consumption),
 *      or, without them, through the session the SSO callback opened
 *   3. Show the confetti + "Open my dashboard" CTA
 *
 * Auto-login is best-effort: if the /login call or the session probe
 * rejects, the CTA falls back to routing the user to /login manually.
 * setup.state is COMPLETE either way, so the app behaves normally from
 * here.
 */
export function SetupStepComplete() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const complete = useCompleteSetup()
  const status = useSetupStatus()
  const { refetch: refetchStatus } = status
  const loginIntoStore = useAuthStore((s) => s.login)

  const displayName = useSetupFlowStore((s) => s.adminDisplayName)
  const completed = useSetupFlowStore((s) => s.completedIntegrations)
  const resetFlow = useSetupFlowStore((s) => s.reset)

  const [autoLogin, setAutoLogin] = useState<AutoLoginState>('idle')
  const ranOnce = useRef(false)

  useEffect(() => {
    if (ranOnce.current) return
    ranOnce.current = true

    const run = async () => {
      try {
        await complete.mutateAsync()
      } catch (err) {
        // If /complete failed because setup was already complete (410), the
        // user probably finished in another tab — proceed to auto-login
        // anyway. Any other failure is surfaced as an inline error below.
        const statusCode = (err as { response?: { status?: number } })?.response?.status
        if (statusCode !== 410) {
          setAutoLogin('failed')
          return
        }
      }

      // No stashed password (SSO admin, or a reload mid-wizard): the session the SSO callback
      // opened may still be there. Probing it before /complete resolves would answer 503.
      const creds = consumeSetupCredentials()
      setAutoLogin('attempting')
      try {
        const user = creds ? await authApi.login(creds.username, creds.password) : await authApi.refresh()
        loginIntoStore(user)
        // Make sure RequireSetup sees fresh status on dashboard nav.
        await refetchStatus()
        setAutoLogin('success')
      } catch {
        setAutoLogin('failed')
      }
    }

    void run()
  }, [complete, loginIntoStore, refetchStatus])

  /**
   * On leaving this screen we clear the in-memory wizard state so a second
   * visit to /setup (e.g. an admin wiping the DB and re-running setup)
   * starts from a blank slate, not a half-filled session.
   */
  const goToDashboard = () => {
    resetFlow()
    navigate('/')
  }

  const goToLogin = () => {
    resetFlow()
    navigate('/login')
  }

  const integrationList: IntegrationKey[] = completed

  return (
    <div className="relative">
      {/* The confetti container is absolutely positioned so it never pushes
          content around. The parent is relative so pieces anchor correctly. */}
      <Confetti />

      <div className="relative mx-auto w-full max-w-lg space-y-6">
        <div className="space-y-2 text-center">
          <div className="flex justify-center">
            <span className="inline-flex size-12 items-center justify-center rounded-xl bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
              <CheckCircle className="size-6" />
            </span>
          </div>
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            {t('setup.complete.title')}
          </h1>
          {displayName && (
            <p className="mx-auto max-w-md text-sm text-muted-foreground">
              {t('setup.complete.subtitle', { name: displayName })}
            </p>
          )}
        </div>

        {integrationList.length > 0 ? (
          <div className="rounded-xl border border-border/60 bg-card p-4 sm:p-5">
            <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              {t('setup.complete.summaryTitle')}
            </p>
            <ul className="space-y-2.5">
              {integrationList.map((key) => {
                const BrandLogo = INTEGRATION_LOGOS[key]
                return (
                  <li key={key} className="flex items-center gap-3 text-sm font-medium">
                    <span className="inline-flex size-7 shrink-0 items-center justify-center text-foreground/80">
                      {BrandLogo ? (
                        <BrandLogo className="size-4" />
                      ) : (
                        <CheckCircle className="size-4" />
                      )}
                    </span>
                    <span className="min-w-0 flex-1 truncate">
                      {t(`setup.integrations.cards.${key}.title`)}
                    </span>
                    <CheckCircle className="size-4 shrink-0 text-emerald-500" aria-hidden="true" />
                  </li>
                )
              })}
            </ul>
          </div>
        ) : (
          <div className="rounded-xl border border-border/60 bg-card p-5 text-center text-sm leading-relaxed text-muted-foreground">
            {t('setup.complete.noIntegrations')}
          </div>
        )}

        {complete.isPending && (
          <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <CircleNotch className="size-4 animate-spin" />
            {t('setup.complete.completing')}
          </div>
        )}

        {autoLogin === 'attempting' && (
          <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <CircleNotch className="size-4 animate-spin" />
            {t('setup.complete.loggingIn')}
          </div>
        )}

        <div className="flex flex-col gap-2.5">
          {autoLogin === 'success' ? (
            <Button
              onClick={goToDashboard}
              className="w-full rounded-lg"
            >
              {t('setup.complete.cta')}
            </Button>
          ) : autoLogin === 'failed' ? (
            <Button
              onClick={goToLogin}
              className="w-full rounded-lg"
            >
              {t('setup.complete.loginFallback')}
            </Button>
          ) : null}
        </div>
      </div>
    </div>
  )
}
