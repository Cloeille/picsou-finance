import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { CreditCard } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

export function SetupStepRevolut() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const ack = useAcknowledgeIntegration()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const proceed = async () => {
    try {
      await ack.mutateAsync('revolut')
      markDone('revolut')
    } catch {
      /* swallow — ack is best-effort; user can re-enable from Settings. */
    }
    navigate(nextIntegrationRoute('revolut', selected))
  }

  const skip = () => navigate(nextIntegrationRoute('revolut', selected))

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <p className="text-xs font-semibold tracking-[0.2em] text-muted-foreground">
          {t('setup.revolut.surtitle')}
        </p>
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <CreditCard className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.revolut.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.revolut.body')}
        </p>
      </div>

      <div className="rounded-2xl border border-border/60 bg-muted/30 p-4 text-center text-xs text-muted-foreground">
        {t('setup.revolut.tip')}
      </div>

      <div className="flex flex-col gap-2 sm:flex-row sm:justify-between">
        <Button
          type="button"
          variant="ghost"
          onClick={skip}
          className="w-full sm:w-auto"
        >
          {t('setup.revolut.skip')}
        </Button>
        <Button
          size="lg"
          onClick={proceed}
          disabled={ack.isPending}
          className="w-full rounded-full transition-transform hover:scale-[1.01] sm:w-auto"
        >
          {t('setup.revolut.cta')}
        </Button>
      </div>
    </div>
  )
}
