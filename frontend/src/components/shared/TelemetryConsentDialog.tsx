import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useUpdateTelemetry } from '@/features/admin/hooks'
import { useTelemetryConsentPrompt } from '@/features/telemetry/hooks'
import { useAppStore } from '@/stores/app-store'

const COLLECTED = ['collected1', 'collected2', 'collected3'] as const
const NEVER = ['never1', 'never2', 'never3'] as const

/**
 * One-time opt-in prompt, for ADMIN users only (consent is instance-wide). Shown when the server
 * has a DSN configured and nobody has answered yet — on first launch, and on the update that ships
 * the feature. Dismissing without answering only hides it for this page load; it stays UNSET
 * (= off) and asks again next time.
 */
export function TelemetryConsentDialog() {
  const { t } = useTranslation()
  // Waits for the one-time sidebar prompt so two modals never stack on first launch.
  const { open } = useTelemetryConsentPrompt()
  const dismiss = useAppStore((s) => s.dismissTelemetryConsent)
  const update = useUpdateTelemetry()

  function choose(enabled: boolean) {
    update.mutate(enabled, {
      onError: () => toast.error(t('telemetry.consent.error')),
    })
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!next) dismiss() }}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('telemetry.consent.title')}</DialogTitle>
          <DialogDescription>{t('telemetry.consent.intro')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4 text-sm">
          <section>
            <h3 className="mb-1 font-medium">{t('telemetry.consent.collectedTitle')}</h3>
            <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
              {COLLECTED.map((k) => <li key={k}>{t(`telemetry.consent.${k}`)}</li>)}
            </ul>
          </section>
          <section>
            <h3 className="mb-1 font-medium">{t('telemetry.consent.neverTitle')}</h3>
            <ul className="list-disc space-y-1 pl-5 text-muted-foreground">
              {NEVER.map((k) => <li key={k}>{t(`telemetry.consent.${k}`)}</li>)}
            </ul>
          </section>
          <p className="text-xs text-muted-foreground">{t('telemetry.consent.tunnel')}</p>
        </div>

        <DialogFooter>
          <Button variant="outline" disabled={update.isPending} onClick={() => choose(false)}>
            {t('telemetry.consent.decline')}
          </Button>
          <Button disabled={update.isPending} onClick={() => choose(true)}>
            {t('telemetry.consent.enable')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
