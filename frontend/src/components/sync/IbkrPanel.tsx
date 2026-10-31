import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { RefreshCw, LogOut, KeyRound, Hash, AlertTriangle, Loader2 } from 'lucide-react'
import { useIbkrStatus, useConnectIbkr, useSyncIbkr, useDisconnectIbkr } from '@/features/sync/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

interface IbkrPanelProps {
  onConnected?: () => void
}

export function IbkrPanel({ onConnected }: IbkrPanelProps = {}) {
  const { t } = useTranslation()

  const [token, setToken] = useState('')
  const [queryId, setQueryId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showDisconnectConfirm, setShowDisconnectConfirm] = useState(false)

  const { data: status, isLoading: statusLoading } = useIbkrStatus()

  const isConnected = status?.connected ?? false
  const isError = status?.status === 'ERROR'

  const connectMutation = useConnectIbkr()
  const syncMutation = useSyncIbkr()
  const disconnectMutation = useDisconnectIbkr()

  function handleConnect(e: React.FormEvent) {
    e.preventDefault()
    if (!token.trim() || !queryId.trim()) return
    connectMutation.mutate(
      { token: token.trim(), queryId: queryId.trim() },
      {
        onSuccess: () => {
          setToken('')
          setQueryId('')
          setError(null)
          onConnected?.()
        },
        onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.ibkr.errors.connectFailed'))),
      },
    )
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Connection status card */}
      {isConnected && status && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex flex-wrap items-center gap-3">
              {isError ? (
                <Badge className="bg-destructive/10 text-destructive">{t('sync.ibkr.statusError')}</Badge>
              ) : (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.ibkr.connected')}
                </Badge>
              )}
              {status.maskedToken && (
                <span className="text-sm text-muted-foreground">{status.maskedToken}</span>
              )}
              {status.lastSyncedAt ? (
                <span className="text-xs text-muted-foreground">
                  {t('sync.ibkr.lastSync')}: {formatDateTime(status.lastSyncedAt)}
                </span>
              ) : (
                <span className="text-xs text-muted-foreground">{t('sync.ibkr.neverSynced')}</span>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {/* Error banner */}
      {error && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{error}</p>
          </CardContent>
        </Card>
      )}

      {isConnected ? (
        /* Connected: sync + disconnect */
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => syncMutation.mutate(undefined, {
              onSuccess: (accounts) => {
                setError(null)
                toast.success(t('sync.ibkr.syncedToast', { count: accounts.length }))
              },
              onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.ibkr.errors.syncFailed'))),
            })}
            disabled={syncMutation.isPending}
          >
            {syncMutation.isPending ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                {t('sync.ibkr.syncing')}
              </>
            ) : (
              <>
                <RefreshCw className="size-4" />
                {t('sync.ibkr.sync')}
              </>
            )}
          </Button>
          <Button
            variant="destructive"
            onClick={() => setShowDisconnectConfirm(true)}
            disabled={disconnectMutation.isPending}
          >
            <LogOut className="size-4" />
            {t('sync.ibkr.disconnect')}
          </Button>
        </div>
      ) : (
        /* Not connected: token + query id form */
        <form onSubmit={handleConnect} className="mx-auto max-w-lg space-y-4">
          <p className="text-sm text-muted-foreground">{t('sync.ibkr.setupHint')}</p>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="ibkr-token">
                  <KeyRound className="mr-1 inline-block size-4" />
                  {t('sync.ibkr.token')}
                </Label>
                <Input
                  id="ibkr-token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder={t('sync.ibkr.tokenPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <div className="space-y-2">
                <Label htmlFor="ibkr-query">
                  <Hash className="mr-1 inline-block size-4" />
                  {t('sync.ibkr.queryId')}
                </Label>
                <Input
                  id="ibkr-query"
                  value={queryId}
                  onChange={(e) => setQueryId(e.target.value)}
                  placeholder={t('sync.ibkr.queryIdPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={connectMutation.isPending || !token.trim() || !queryId.trim()}
                className="w-full"
              >
                {connectMutation.isPending && <Loader2 className="size-4 animate-spin" />}
                {t('sync.ibkr.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      <ConfirmDialog
        open={showDisconnectConfirm}
        onOpenChange={setShowDisconnectConfirm}
        title={t('sync.ibkr.disconnect')}
        description={t('sync.ibkr.disconnectConfirm')}
        onConfirm={() => disconnectMutation.mutate(undefined, {
          onSuccess: () => {
            setShowDisconnectConfirm(false)
            setError(null)
          },
          onError: (err: unknown) => {
            setShowDisconnectConfirm(false)
            setError(extractErrorMessage(err, t('sync.ibkr.errors.disconnectFailed')))
          },
        })}
        loading={disconnectMutation.isPending}
      />
    </div>
  )
}
