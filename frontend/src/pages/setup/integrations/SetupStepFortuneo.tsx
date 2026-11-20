import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { useAcknowledgeIntegration } from '@/features/setup/hooks'
import { formatApiError } from '@/lib/errors'
import { useSetupFlowStore } from '@/stores/setup-flow-store'
import { FortuneoLogo } from '../components/IntegrationLogos'
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
      // Error surfaced via ack.error below so the user can retry.
    }
  }

  const skip = () => navigate(nextIntegrationRoute('fortuneo', selected))

  return (
    <div className="mx-auto w-full max-w-lg space-y-6">
      <div className="space-y-2 text-center">
        <div className="flex justify-center">
          <span className="inline-flex size-12 items-center justify-center text-foreground">
            <FortuneoLogo className="size-6" />
          </span>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight sm:text-3xl">
          {t('setup.fortuneo.title')}
        </h1>
        <p className="mx-auto max-w-md text-sm leading-relaxed text-muted-foreground">
          {t('setup.fortuneo.body')}
        </p>
      </div>

      <div className="rounded-xl border border-border/60 bg-muted/30 p-4 text-center text-sm leading-relaxed text-muted-foreground">
        {t('setup.fortuneo.tip')}
      </div>

      {ack.isError && (
        <p role="alert" className="text-center text-sm text-destructive">
          {formatApiError(ack.error, t)}
        </p>
      )}

      <div className="flex flex-col gap-2.5">
        <Button
          onClick={proceed}
          disabled={ack.isPending}
          className="w-full rounded-lg"
        >
          {t('setup.fortuneo.cta')}
        </Button>
        <Button
          type="button"
          variant="outline"
          onClick={skip}
          disabled={ack.isPending}
          className="w-full rounded-lg"
        >
          {t('setup.fortuneo.skip')}
        </Button>
      </div>
    </div>
  )
}
