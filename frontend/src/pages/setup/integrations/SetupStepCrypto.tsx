import { useCallback, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Bitcoin, CheckCircle2, CircleAlert, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useGenerateCryptoKey } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

type Phase = 'idle' | 'running' | 'existed' | 'created' | 'error'

/**
 * The happy path in Docker-land is "key already exists" — the entrypoint
 * wrote it before Spring even booted, so the backend responds with
 * `existed: true` and we immediately mark the integration done. The UI
 * still shows the idle-then-action flow because bare-metal installs land
 * in the `created: true` branch.
 */
export function SetupStepCrypto() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const gen = useGenerateCryptoKey()
  // Stable mutate fn for the check callback's deps — see the matching note in
  // SetupStepBoursoBank: depending on `gen.mutate` makes exhaustive-deps ask
  // for the whole unstable mutation object.
  const { mutate: generateKey } = gen
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  // Start in 'running' so the mount probe doesn't need a synchronous
  // setState in its effect — the loader shows immediately either way.
  const [phase, setPhase] = useState<Phase>('running')
  const [keyPath, setKeyPath] = useState<string | null>(null)

  // Fire the idempotent key-generation endpoint. State only changes inside
  // the async mutation callbacks (allowed in effects).
  const check = useCallback(() => {
    generateKey(undefined, {
      onSuccess: (result) => {
        setKeyPath(result.path)
        setPhase(result.existed ? 'existed' : 'created')
        markDone('crypto')
      },
      onError: () => setPhase('error'),
    })
  }, [generateKey, markDone])

  // Retry button: reset the visible state, then re-run.
  const run = () => {
    setPhase('running')
    check()
  }

  /**
   * Auto-fire on mount: most self-hosters land here with the key already
   * created by the Docker entrypoint, so eagerly triggering the idempotent
   * endpoint gives them a single-click "Continue" experience. Bare-metal
   * users will still see the regular success state after ~100ms.
   */
  useEffect(() => {
    check()
  }, [check])

  const proceed = () => navigate(nextIntegrationRoute('crypto', selected))
  const skip = () => navigate(nextIntegrationRoute('crypto', selected))

  const successCopy =
    phase === 'existed'
      ? t('setup.crypto.existed')
      : phase === 'created'
        ? t('setup.crypto.created')
        : ''

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <Bitcoin className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.crypto.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.crypto.body')}
        </p>
      </div>

      {(phase === 'idle' || phase === 'running') && (
        <div className="flex flex-col items-center gap-2 py-6 text-muted-foreground">
          <Loader2 className="h-6 w-6 animate-spin" />
          <p className="text-sm">{t('setup.crypto.generating')}</p>
        </div>
      )}

      {(phase === 'existed' || phase === 'created') && (
        <div className="space-y-3 rounded-2xl border border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
          <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
          <p className="text-lg font-medium">{successCopy}</p>
          {keyPath && (
            <p className="text-xs text-muted-foreground">
              {t('setup.crypto.pathLabel')}{' '}
              <span className="font-mono">{keyPath}</span>
            </p>
          )}
        </div>
      )}

      {phase === 'error' && (
        <div
          role="alert"
          className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-6"
        >
          <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
          <p className="text-sm text-destructive">{t('setup.crypto.error')}</p>
        </div>
      )}

      <div className="mx-auto flex w-full max-w-lg flex-col gap-3">
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          className="w-full rounded-full"
        >
          {t('setup.crypto.skip')}
        </Button>
        {phase === 'error' ? (
          <Button
            onClick={run}
            disabled={gen.isPending}
            className="w-full rounded-full"
          >
            {t('setup.crypto.generateCta')}
          </Button>
        ) : (
          <Button
            onClick={proceed}
            disabled={phase === 'running'}
            className="w-full rounded-full"
          >
            {t('setup.crypto.continue')}
          </Button>
        )}
      </div>
    </div>
  )
}
