import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { PiggyBank } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { formatApiError } from '@/lib/errors'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

export function SetupStepFortuneo() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const ack = useAcknowledgeIntegration()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const proceed = async () => {
    try {
      await ack.mutateAsync('fortuneo')
      markDone('fortuneo')
      navigate(nextIntegrationRoute('fortuneo', selected))
    } catch {
      // Keep the step mounted so the mutation error is visible and retryable.
    }
  }

  const skip = () => navigate(nextIntegrationRoute('fortuneo', selected))

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <PiggyBank className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.fortuneo.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.fortuneo.body')}
        </p>
      </div>
      <div className="rounded-2xl border border-border/60 bg-muted/30 p-4 text-center text-xs text-muted-foreground">
        {t('setup.fortuneo.tip')}
      </div>
      <div className="mx-auto flex w-full max-w-lg flex-col gap-3">
        {ack.isError && (
          <p role="alert" className="text-sm text-destructive">
            {formatApiError(ack.error, t)}
          </p>
        )}
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          disabled={ack.isPending}
          className="w-full rounded-full"
        >
          {t('setup.fortuneo.skip')}
        </Button>
        <Button
          onClick={proceed}
          disabled={ack.isPending}
          className="w-full rounded-full"
        >
          {t('setup.fortuneo.cta')}
        </Button>
      </div>
    </div>
  )
}
