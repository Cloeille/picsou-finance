import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { RefreshCw, LogOut, KeyRound, AlertTriangle, Loader2 } from 'lucide-react'
import {
  useSimplefinStatus,
  useConnectSimplefin,
  useSyncSimplefin,
  useDisconnectSimplefin,
} from '@/features/sync/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

const CREATE_TOKEN_URL = 'https://bridge.simplefin.org/simplefin/create'

interface SimplefinPanelProps {
  onConnected?: () => void
}

export function SimplefinPanel({ onConnected }: SimplefinPanelProps = {}) {
  const { t } = useTranslation()

  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showDisconnectConfirm, setShowDisconnectConfirm] = useState(false)

  const { data: status, isLoading: statusLoading } = useSimplefinStatus()

  const isConnected = status?.connected ?? false
  const isError = status?.status === 'ERROR'

  const connectMutation = useConnectSimplefin()
  const syncMutation = useSyncSimplefin()
  const disconnectMutation = useDisconnectSimplefin()

  async function handleConnect(e: React.FormEvent) {
    e.preventDefault()
    if (!token.trim()) return
    setError(null)
    try {
      await connectMutation.mutateAsync(token.trim())
    } catch (err: unknown) {
      setError(extractErrorMessage(err, t('sync.simplefin.errors.connectFailed')))
      return
    }
    setToken('')
    try {
      const accounts = await syncMutation.mutateAsync()
      toast.success(t('sync.simplefin.syncedToast', { count: accounts.length }))
      onConnected?.()
    } catch (err: unknown) {
      // The token is already spent. Stay on the connected card so Sync can be retried.
      setError(extractErrorMessage(err, t('sync.simplefin.errors.syncFailed')))
    }
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {isConnected && status && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex flex-wrap items-center gap-3">
              {isError ? (
                <Badge className="bg-destructive/10 text-destructive">{t('sync.simplefin.statusError')}</Badge>
              ) : (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.simplefin.connected')}
                </Badge>
              )}
              {status.maskedToken && (
                <span className="text-sm text-muted-foreground">{status.maskedToken}</span>
              )}
              {status.lastSyncedAt ? (
                <span className="text-xs text-muted-foreground">
                  {t('sync.simplefin.lastSync')}: {formatDateTime(status.lastSyncedAt)}
                </span>
              ) : (
                <span className="text-xs text-muted-foreground">{t('sync.simplefin.neverSynced')}</span>
              )}
            </div>
          </CardContent>
        </Card>
      )}

      {error && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">{error}</p>
          </CardContent>
        </Card>
      )}

      {isConnected ? (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => syncMutation.mutate(undefined, {
              onSuccess: (accounts) => {
                setError(null)
                toast.success(t('sync.simplefin.syncedToast', { count: accounts.length }))
              },
              onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.simplefin.errors.syncFailed'))),
            })}
            disabled={syncMutation.isPending}
          >
            {syncMutation.isPending ? (
              <>
                <Loader2 className="size-4 animate-spin" />
                {t('sync.simplefin.syncing')}
              </>
            ) : (
              <>
                <RefreshCw className="size-4" />
                {t('sync.simplefin.sync')}
              </>
            )}
          </Button>
          <Button
            variant="destructive"
            onClick={() => setShowDisconnectConfirm(true)}
            disabled={disconnectMutation.isPending}
          >
            <LogOut className="size-4" />
            {t('sync.simplefin.disconnect')}
          </Button>
        </div>
      ) : (
        <form onSubmit={handleConnect} className="mx-auto max-w-lg space-y-4">
          <p className="text-sm text-muted-foreground">
            {t('sync.simplefin.setupHint')}{' '}
            <a
              href={CREATE_TOKEN_URL}
              target="_blank"
              rel="noreferrer"
              className="underline"
            >
              {t('sync.simplefin.createToken')}
            </a>
          </p>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="simplefin-token">
                  <KeyRound className="mr-1 inline-block size-4" />
                  {t('sync.simplefin.token')}
                </Label>
                <Input
                  id="simplefin-token"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder={t('sync.simplefin.tokenPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={connectMutation.isPending || syncMutation.isPending || !token.trim()}
                className="w-full"
              >
                {(connectMutation.isPending || syncMutation.isPending) && (
                  <Loader2 className="size-4 animate-spin" />
                )}
                {t('sync.simplefin.connect')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}

      <ConfirmDialog
        open={showDisconnectConfirm}
        onOpenChange={setShowDisconnectConfirm}
        title={t('sync.simplefin.disconnect')}
        description={t('sync.simplefin.disconnectConfirm')}
        onConfirm={() => disconnectMutation.mutate(undefined, {
          onSuccess: () => {
            setShowDisconnectConfirm(false)
            setError(null)
          },
          onError: (err: unknown) => {
            setShowDisconnectConfirm(false)
            setError(extractErrorMessage(err, t('sync.simplefin.errors.disconnectFailed')))
          },
        })}
        loading={disconnectMutation.isPending}
      />
    </div>
  )
}
