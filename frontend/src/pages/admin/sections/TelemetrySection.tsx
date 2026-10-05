import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Activity } from 'lucide-react'
import { Switch } from '@/components/ui/switch'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useUpdateTelemetry } from '@/features/admin/hooks'
import { shutdownTelemetry } from '@/lib/telemetry'
import type { TelemetrySettings } from '@/types/api'

export function TelemetrySection({ settings }: { settings: TelemetrySettings }) {
  const { t } = useTranslation()
  const update = useUpdateTelemetry()

  function onToggle(enabled: boolean) {
    update.mutate(enabled, {
      // Stop reporting right now rather than waiting for the config refetch.
      onSuccess: () => { if (!enabled) shutdownTelemetry() },
      onError: () => toast.error(t('telemetry.section.error')),
    })
  }

  return (
    <Card className="rounded-4xl bg-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Activity className="size-5 text-muted-foreground" />
          {t('telemetry.section.title')}
        </CardTitle>
        <CardDescription>{t('telemetry.section.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium">{t('telemetry.section.toggle')}</span>
          <Switch
            checked={settings.available && settings.consent === 'ENABLED'}
            disabled={!settings.available || update.isPending}
            onCheckedChange={onToggle}
            aria-label={t('telemetry.section.toggle')}
          />
        </div>
        {!settings.available && (
          <p className="text-sm text-muted-foreground">{t('telemetry.section.unavailable')}</p>
        )}
        <p className="text-xs text-muted-foreground">{t('telemetry.section.privacy')}</p>
      </CardContent>
    </Card>
  )
}
