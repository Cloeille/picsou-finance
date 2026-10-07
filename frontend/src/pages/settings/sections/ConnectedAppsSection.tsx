import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Trash2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { formatApiError } from '@/lib/errors'
import { formatDate, formatTimeAgo } from '@/lib/utils'
import { useConnectedApps, useRevokeConnectedApp } from '@/features/connectedApps/hooks'

export function ConnectedAppsSection() {
  const { t } = useTranslation()
  const { data: apps, isLoading } = useConnectedApps()
  const revokeApp = useRevokeConnectedApp()

  const [revokingId, setRevokingId] = useState<string | null>(null)

  function handleRevoke() {
    if (revokingId == null) return
    revokeApp.mutate(revokingId, { onSuccess: () => setRevokingId(null) })
  }

  return (
    <div className="space-y-4">
      {isLoading ? (
        <p className="text-sm text-muted-foreground">{t('connectedApps.loading')}</p>
      ) : !Array.isArray(apps) || apps.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t('connectedApps.empty')}</p>
      ) : (
        <ul className="divide-y rounded-lg border">
          {apps.map((a) => (
            <li
              key={a.id}
              className="flex flex-col gap-3 p-3 sm:flex-row sm:items-start sm:justify-between"
            >
              <div className="min-w-0 space-y-1.5">
                <span className="font-medium truncate">{a.clientName}</span>
                <div className="flex flex-wrap gap-1">
                  {a.scopes.map((s) => (
                    <Badge key={s} variant="secondary" className="font-mono text-[10px]">
                      {s}
                    </Badge>
                  ))}
                </div>
                <p className="text-xs text-muted-foreground">
                  {t('connectedApps.issued', { value: formatDate(a.issuedAt) })}
                  <span className="mx-2">·</span>
                  {a.lastUsedAt
                    ? t('connectedApps.lastUsed', { value: formatTimeAgo(a.lastUsedAt) })
                    : t('connectedApps.lastUsedNever')}
                </p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                className="shrink-0 text-destructive hover:text-destructive"
                onClick={() => setRevokingId(a.id)}
              >
                <Trash2 className="size-3.5" />
                {t('connectedApps.revoke')}
              </Button>
            </li>
          ))}
        </ul>
      )}

      <ConfirmDialog
        open={revokingId !== null}
        onOpenChange={(o) => { if (!o) { setRevokingId(null); revokeApp.reset() } }}
        title={t('connectedApps.revokeTitle')}
        description={t('connectedApps.revokeDescription')}
        confirmLabel={t('connectedApps.revoke')}
        onConfirm={handleRevoke}
        loading={revokeApp.isPending}
        error={revokeApp.isError ? formatApiError(revokeApp.error, t) : undefined}
        variant="destructive"
      />
    </div>
  )
}
