import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { FinaryLogo } from '../components/IntegrationLogos'
import { nextIntegrationRoute } from './navigation'

export function SetupStepFinary() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const ack = useAcknowledgeIntegration()
  const selected = useSetupFlowStore((s) => s.selectedIntegrations)
  const markDone = useSetupFlowStore((s) => s.markIntegrationDone)

  const proceed = async () => {
    try {
      await ack.mutateAsync('finary')
    } catch {
      /* best-effort acknowledgement; user can also connect from Accounts */
    }
    markDone('finary')
    navigate(nextIntegrationRoute('finary', selected))
  }

  const skip = () => navigate(nextIntegrationRoute('finary', selected))

  return (
    <div className="mx-auto w-full max-w-lg space-y-6">
      <div className="space-y-2 text-center">
        <div className="flex justify-center">
          <span className="inline-flex size-12 items-center justify-center text-foreground">
            <FinaryLogo className="size-6" />
          </span>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          {t('setup.finary.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm leading-relaxed text-muted-foreground">
          {t('setup.finary.body')}
        </p>
      </div>

      <div className="rounded-xl border border-border/60 bg-muted/30 p-4 text-center text-sm leading-relaxed text-muted-foreground">
        {t('setup.finary.tip')}
      </div>

      <div className="flex flex-col gap-2.5">
        <Button
          onClick={proceed}
          disabled={ack.isPending}
          className="w-full rounded-lg"
        >
          {t('setup.finary.cta')}
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          disabled={ack.isPending}
          className="w-full rounded-lg"
        >
          {t('setup.finary.skip')}
        </Button>
      </div>
    </div>
  )
}
