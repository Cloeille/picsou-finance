import { useTranslation } from 'react-i18next'
import { Switch } from '@/components/ui/switch'
import { Plug } from 'lucide-react'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import { useToggleIntegration } from '@/features/admin/hooks'

const INTEGRATION_KEYS = ['enablebanking', 'boursobank', 'boursedirect', 'fortuneo', 'traderepublic', 'finary', 'crypto'] as const

export function IntegrationsSection({ integrations }: { integrations: Record<string, boolean> }) {
  const { t } = useTranslation()
  const toggle = useToggleIntegration()

  return (
    <Card className="rounded-4xl bg-card">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-lg">
          <Plug className="size-5 text-muted-foreground" />
          {t('admin.integrations.title')}
        </CardTitle>
        <CardDescription>{t('admin.integrations.description')}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {INTEGRATION_KEYS.map((key) => (
          <div key={key} className="flex items-center justify-between">
            <span className="text-sm font-medium">{t(`admin.integrations.${key}`)}</span>
            <Switch
              checked={integrations[key] ?? false}
              disabled={toggle.isPending}
              onCheckedChange={(enabled) => toggle.mutate({ key, enabled })}
              aria-label={t(`admin.integrations.${key}`)}
            />
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
