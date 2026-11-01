import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { CheckCircle2, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Confetti } from './components/Confetti'
import { useCompleteSetup, useSetupStatus } from '@/features/setup/hooks'
import { useSetupFlowStore, type IntegrationKey } from '@/stores/setup-flow-store'
import { consumeSetupCredentials } from '@/stores/setup-credentials'
import { authApi } from '@/features/auth/api'
import { useAuthStore } from '@/stores/auth-store'
import { useAppStore } from '@/stores/app-store'
import { SidebarStylePromptModal } from '@/components/layout/SidebarStylePromptModal'

type AutoLoginState = 'idle' | 'attempting' | 'success' | 'failed'

/**
 * Final screen. On mount:
 *   1. POST /api/setup/complete to flip setup.state to COMPLETE
 *   2. Immediately attempt auto-login with the in-memory credentials
 *      stashed by SetupStepAdmin (cleared here — one-shot consumption)
 *   3. Show the confetti + "Open my dashboard" CTA
 *
 * Auto-login is best-effort: if credentials were cleared by a refresh or
 * the /login call itself rejects, the CTA falls back to routing the user
 * to /login manually. setup.state is COMPLETE either way, so the app
 * behaves normally from here.
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

  const hasSeenSidebarStylePrompt = useAppStore((s) => s.hasSeenSidebarStylePrompt)
  const [showSidebarStylePrompt, setShowSidebarStylePrompt] = useState(!hasSeenSidebarStylePrompt)

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

      const creds = consumeSetupCredentials()
      if (!creds) {
        setAutoLogin('failed')
        return
      }

      setAutoLogin('attempting')
      try {
        const user = await authApi.login(creds.username, creds.password)
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
      <SidebarStylePromptModal open={showSidebarStylePrompt} onOpenChange={setShowSidebarStylePrompt} />

      {/* The confetti container is absolutely positioned so it never pushes
          content around. The parent is relative so pieces anchor correctly. */}
      <Confetti />

      <div className="relative space-y-8">
        <div className="text-center space-y-2">
          <div className="flex justify-center">
            <span className="rounded-2xl bg-emerald-500/10 p-3 text-emerald-500">
              <CheckCircle2 className="h-8 w-8" />
            </span>
          </div>
          <h1 className="text-3xl sm:text-4xl font-semibold tracking-tight">
            {t('setup.complete.title')}
          </h1>
          {displayName && (
            <p className="mx-auto max-w-md text-sm text-muted-foreground">
              {t('setup.complete.subtitle', { name: displayName })}
            </p>
          )}
        </div>

        {integrationList.length > 0 ? (
          <div className="rounded-2xl border border-border/60 bg-card p-4 sm:p-5">
            <p className="mb-3 text-xs font-semibold tracking-[0.18em] text-muted-foreground">
              {t('setup.complete.summaryTitle')}
            </p>
            <ul className="space-y-2">
              {integrationList.map((key) => (
                <li key={key} className="flex items-center gap-2 text-sm">
                  <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                  {t(`setup.integrations.cards.${key}.title`)}
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <div className="rounded-2xl border border-border/60 bg-muted/30 p-4 text-center text-xs text-muted-foreground">
            {t('setup.complete.noIntegrations')}
          </div>
        )}

        {complete.isPending && (
          <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t('setup.complete.completing')}
          </div>
        )}

        {autoLogin === 'attempting' && (
          <div className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t('setup.complete.loggingIn')}
          </div>
        )}

        <div className="flex flex-col gap-2 sm:flex-row sm:justify-center">
          {autoLogin === 'success' ? (
            <Button
              onClick={goToDashboard}
              className="w-full rounded-full"
            >
              {t('setup.complete.cta')}
            </Button>
          ) : autoLogin === 'failed' ? (
            <Button
              onClick={goToLogin}
              className="w-full rounded-full"
            >
              {t('setup.complete.loginFallback')}
            </Button>
          ) : null}
        </div>
      </div>
    </div>
  )
}
