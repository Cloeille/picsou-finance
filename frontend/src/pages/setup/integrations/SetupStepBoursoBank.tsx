import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Boxes, CheckCircle2, CircleAlert, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useBoursoBankHealth } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

type Phase = 'checking' | 'ok' | 'ko'

/**
 * Auto-pings the sidecar on mount. The backend flips the enabled flag on
 * success, so we only need to mark the integration done in the local store
 * and route forward. On failure we stay on this screen so the user can
 * start the container and retry without losing wizard position.
 */
export function SetupStepBoursoBank() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const health = useBoursoBankHealth()
  // Pull out the referentially-stable mutate fn so the check callback can
  // depend on it directly — depending on `health.mutate` makes exhaustive-deps
  // ask for the whole (unstable) mutation object instead.
  const { mutate: probeHealth } = health
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const [phase, setPhase] = useState<Phase>('checking')
  const [detail, setDetail] = useState<string | null>(null)

  // Fire the health probe. State only changes inside the async mutation
  // callbacks (allowed in effects) — not synchronously in the effect body.
  const check = useCallback(() => {
    probeHealth(undefined, {
      onSuccess: (result) => {
        if (result.ok) {
          setPhase('ok')
          markDone('boursobank')
        } else {
          setPhase('ko')
          setDetail(result.hint)
        }
      },
      onError: () => {
        setPhase('ko')
        setDetail(null)
      },
    })
  }, [probeHealth, markDone])

  // Retry button: reset the visible state, then re-probe.
  const run = () => {
    setPhase('checking')
    setDetail(null)
    check()
  }

  useEffect(() => {
    check()
  }, [check])

  const proceed = () => navigate(nextIntegrationRoute('boursobank', selected))

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <Boxes className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.bourso.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.bourso.body')}
        </p>
      </div>

      {phase === 'checking' && (
        <div className="flex flex-col items-center gap-2 py-6 text-muted-foreground">
          <Loader2 className="h-6 w-6 animate-spin" />
          <p className="text-sm">{t('setup.bourso.pinging')}</p>
        </div>
      )}

      {phase === 'ok' && (
        <div className="flex flex-col items-center gap-3 rounded-2xl border border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
          <CheckCircle2 className="h-10 w-10 text-emerald-500" />
          <p className="text-lg font-medium">{t('setup.bourso.ok')}</p>
        </div>
      )}

      {phase === 'ko' && (
        <div
          role="alert"
          className="space-y-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-6"
        >
          <div className="flex items-start gap-3">
            <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
            <div className="space-y-1">
              <p className="font-medium text-destructive">{t('setup.bourso.downTitle')}</p>
              <p className="text-sm text-muted-foreground">
                {detail ?? t('setup.bourso.downHint')}
              </p>
            </div>
          </div>
        </div>
      )}

      <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
        <Button
          type="button"
          variant="ghost"
          onClick={proceed}
          className="w-full sm:w-auto"
        >
          {t('setup.bourso.skip')}
        </Button>
        {phase === 'ko' ? (
          <Button
            onClick={run}
            disabled={health.isPending}
            className="w-full rounded-full"
          >
            {t('setup.bourso.retry')}
          </Button>
        ) : (
          <Button
            onClick={proceed}
            disabled={phase === 'checking'}
            className="w-full rounded-full"
          >
            {t('setup.bourso.continue')}
          </Button>
        )}
      </div>
    </div>
  )
}
