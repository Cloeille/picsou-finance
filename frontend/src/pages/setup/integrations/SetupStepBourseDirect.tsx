import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { BriefcaseBusiness } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { nextIntegrationRoute } from './navigation'

export function SetupStepBourseDirect() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const ack = useAcknowledgeIntegration()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const proceed = async () => {
    try {
      await ack.mutateAsync('boursedirect')
      markDone('boursedirect')
    } catch {
      /* swallow — ack is best-effort; user can re-enable from Settings. */
    }
    navigate(nextIntegrationRoute('boursedirect', selected))
  }

  const skip = () => navigate(nextIntegrationRoute('boursedirect', selected))

  return (
    <div className="space-y-8">
      <div className="text-center space-y-2">
        <div className="flex justify-center">
          <span className="rounded-xl bg-primary/10 p-3 text-primary">
            <BriefcaseBusiness className="h-6 w-6" />
          </span>
        </div>
        <h1 className="text-2xl sm:text-3xl font-semibold tracking-tight">
          {t('setup.bourseDirect.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm text-muted-foreground">
          {t('setup.bourseDirect.body')}
        </p>
      </div>
      <div className="rounded-2xl border border-border/60 bg-muted/30 p-4 text-center text-xs text-muted-foreground">
        {t('setup.bourseDirect.tip')}
      </div>
      <div className="mx-auto flex w-full max-w-lg flex-col gap-3">
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          className="w-full rounded-full"
        >
          {t('setup.bourseDirect.skip')}
        </Button>
        <Button
          onClick={proceed}
          disabled={ack.isPending}
          className="w-full rounded-full"
        >
          {t('setup.bourseDirect.cta')}
        </Button>
      </div>
    </div>
  )
}
