import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { CheckCircle2, CircleAlert, Info, Loader2, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EBSubstepShell } from './EBSubstepShell'
import { useTestEnableBanking } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from '../navigation'
import { formatApiError } from '@/lib/errors'

interface Props {
  onBack: () => void
}

type TestState =
  | { phase: 'idle' | 'running' | 'ok' }
  | { phase: 'ko'; code: string }
  | { phase: 'ko'; error: unknown }

const KNOWN_CODES = new Set([
  'invalid_application_id',
  'invalid_key_id',
  'public_key_not_uploaded',
  'redirect_uri_mismatch',
  'network',
])

export function EBStep5Test({ onBack }: Props) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const test = useTestEnableBanking()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)
  const updateEbDraft = useSetupFlowStore((s) => s.updateEbDraft)

  const [state, setState] = useState<TestState>({ phase: 'idle' })
  const { phase } = state

  const run = () => {
    setState({ phase: 'running' })
    test.mutate(undefined, {
      onSuccess: (result) => {
        if (result.ok) {
          setState({ phase: 'ok' })
          updateEbDraft({ tested: true })
          markDone('enablebanking')
        } else {
          setState({ phase: 'ko', code: result.code })
        }
      },
      onError: (err) => {
        setState({ phase: 'ko', error: err })
      },
    })
  }

  const proceed = () => navigate(nextIntegrationRoute('enablebanking', selected))

  const failureMessage = state.phase !== 'ko'
    ? null
    : 'error' in state
      ? formatApiError(state.error, t)
      : t(KNOWN_CODES.has(state.code)
        ? `setup.enablebanking.test.hints.${state.code}`
        : 'setup.enablebanking.test.hints.unknown')

  return (
    <EBSubstepShell current={5} total={5}>
      <div className="space-y-6">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <Zap className="h-6 w-6" />
          </span>
        </div>
        <div className="text-center space-y-2">
          <h2 className="text-2xl sm:text-3xl font-semibold tracking-tight">
            {t('setup.enablebanking.test.title')}
          </h2>
          <p className="mx-auto max-w-md text-sm text-muted-foreground">
            {t('setup.enablebanking.test.body')}
          </p>
        </div>

        {phase === 'idle' && (
          <>
            <div
              className="flex items-start gap-3 rounded-2xl border border-amber-500/40 bg-amber-500/5 p-4 text-sm text-amber-900 dark:text-amber-200"
              role="note"
            >
              <Info className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
              <p>{t('setup.enablebanking.test.activationNotice')}</p>
            </div>
            <div className="flex justify-center">
              <Button
                onClick={run}
                className="w-full rounded-full"
              >
                {t('setup.enablebanking.test.run')}
              </Button>
            </div>
          </>
        )}

        {phase === 'running' && (
          <div className="flex flex-col items-center gap-2 py-6 text-muted-foreground">
            <Loader2 className="h-6 w-6 animate-spin" />
            <p className="text-sm">{t('setup.enablebanking.test.running')}</p>
          </div>
        )}

        {phase === 'ok' && (
          <div role="status" className="space-y-4 rounded-2xl border border-emerald-500/40 bg-emerald-500/5 p-6 text-center">
            <CheckCircle2 className="mx-auto h-10 w-10 text-emerald-500" />
            <p className="text-lg font-medium">{t('setup.enablebanking.test.success')}</p>
            <p className="text-sm text-muted-foreground">{t('setup.enablebanking.test.successBody')}</p>
            <Button
              onClick={proceed}
              className="w-full rounded-full"
            >
              {t('setup.enablebanking.continue')}
            </Button>
          </div>
        )}

        {phase === 'ko' && (
          <div
            role="alert"
            className="space-y-4 rounded-2xl border border-destructive/40 bg-destructive/5 p-6"
          >
            <div className="flex items-start gap-3">
              <CircleAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
              <p className="text-sm text-destructive">{failureMessage}</p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
              <Button
                type="button"
                variant="outline"
                onClick={onBack}
                className="w-full sm:w-auto"
              >
                {t('setup.enablebanking.test.backToPrevious')}
              </Button>
              <Button
                type="button"
                onClick={run}
                className="w-full rounded-full sm:w-auto"
              >
                {t('setup.enablebanking.test.retry')}
              </Button>
            </div>
          </div>
        )}

        {phase !== 'ok' && (
          <div className="flex flex-col gap-2 sm:flex-row sm:justify-start">
            <Button
              type="button"
              variant="ghost"
              onClick={onBack}
              className="w-full sm:w-auto"
            >
              {t('setup.enablebanking.back')}
            </Button>
          </div>
        )}
      </div>
    </EBSubstepShell>
  )
}
