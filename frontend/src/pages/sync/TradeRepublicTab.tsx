import { useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import {
  AlertTriangle,
  RefreshCw,
  Upload,
  LogOut,
} from 'lucide-react'
import {
  useClearTrSession,
  useImportTrCsv,
  useSyncTradeRepublic,
  useTrSessionStatus,
} from '@/features/sync/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { formatTrAuthError, trErrorMessage } from '@/lib/errors'
import { TradeRepublicLogin } from '@/components/sync/TradeRepublicLogin'

export function TradeRepublicTab() {
  const { t } = useTranslation()
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [error, setError] = useState<string | null>(null)

  const { data: sessionStatus, isLoading: statusLoading } = useTrSessionStatus()
  const { data: accounts } = useAccounts()
  const syncMutation = useSyncTradeRepublic()
  const importCsvMutation = useImportTrCsv()
  const logoutMutation = useClearTrSession()

  const isConnected = sessionStatus?.isActive ?? false
  const syncStatus = sessionStatus?.syncStatus ?? 'IDLE'
  const isSyncing = syncStatus === 'QUEUED' || syncStatus === 'RUNNING'
  const requestingSync = syncMutation.isPending || isSyncing
  const backgroundError = syncStatus === 'FAILED'
    ? (trErrorMessage(t, sessionStatus?.lastSyncError) ?? t('sync.tr.errors.serverError'))
    : null
  const visibleError = error ?? backgroundError
  // Accounts a past session imported stop updating once it is gone (an expiry, a disconnect, or
  // the upgrade that dropped the old sessions): say so, the daily sync skips them silently.
  // CSV imports (tr_csv_*) never had a session, so they say nothing.
  const staleAccounts = !isConnected && (accounts ?? []).some(
    account => account.provider === 'Trade Republic' && !account.isManual
      && !account.externalAccountId?.startsWith('tr_csv_'),
  )

  function handleCsvChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return
    setError(null)
    importCsvMutation.mutate(file, {
      onSuccess: () => {
        if (fileInputRef.current) fileInputRef.current.value = ''
      },
      onError: (err: unknown) => setError(formatTrAuthError(err, t)),
    })
  }

  function handleSync() {
    setError(null)
    syncMutation.mutate(undefined, { onError: (err: unknown) => setError(formatTrAuthError(err, t)) })
  }

  function handleClear() {
    setError(null)
    logoutMutation.mutate(undefined, { onError: (err: unknown) => setError(formatTrAuthError(err, t)) })
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {isConnected ? (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.tr.sessionActive')}
                </Badge>
              ) : (
                <Badge variant="outline">
                  {t('sync.tr.noSession')}
                </Badge>
              )}
            </div>
          </div>
          {isSyncing && (
            <p className="mt-2 text-sm text-muted-foreground">
              {syncStatus === 'QUEUED' ? t('sync.tr.queued') : t('sync.tr.syncing')}
            </p>
          )}
          {syncStatus === 'SUCCESS' && sessionStatus?.lastSyncCompletedAt && (
            <p className="mt-2 text-sm text-emerald-600 dark:text-emerald-400">
              {t('sync.tr.syncSuccess')}
            </p>
          )}
        </CardContent>
      </Card>

      {visibleError && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{visibleError}</p>
            {error && (
              <Button size="sm" variant="outline" onClick={() => setError(null)}>
                {t('common.retry')}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {/* Connected controls */}
      {isConnected && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={handleSync} disabled={requestingSync}>
            <RefreshCw className={requestingSync ? 'animate-spin' : undefined} />
            {requestingSync ? t('sync.tr.syncing') : t('sync.tr.sync')}
          </Button>

          <Button variant="outline" onClick={() => fileInputRef.current?.click()} disabled={importCsvMutation.isPending}>
            <Upload />
            {t('sync.tr.importCsv')}
          </Button>

          <Button variant="destructive" onClick={handleClear} disabled={logoutMutation.isPending}>
            <LogOut />
            {t('sync.tr.clearSession')}
          </Button>

          <input
            ref={fileInputRef}
            type="file"
            accept=".csv"
            className="hidden"
            onChange={handleCsvChange}
          />
        </div>
      )}

      {staleAccounts && !visibleError && (
        <p className="text-sm text-muted-foreground">{t('sync.tr.staleAccounts')}</p>
      )}

      {!isConnected && <TradeRepublicLogin variant="page" maskedPhone={sessionStatus?.maskedPhone ?? null} />}
    </div>
  )
}
