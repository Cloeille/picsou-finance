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
  useWiseStatus,
  useConnectWise,
  useSyncWise,
  useDisconnectWise,
} from '@/features/sync/hooks'
import { extractErrorMessage } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

const CREATE_TOKEN_URL = 'https://wise.com/your-account/integrations-and-tools/api-tokens'

interface WisePanelProps {
  onConnected?: () => void
}

export function WisePanel({ onConnected }: WisePanelProps = {}) {
  const { t } = useTranslation()

  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showDisconnectConfirm, setShowDisconnectConfirm] = useState(false)

  const { data: status, isLoading: statusLoading } = useWiseStatus()

  const isConnected = status?.connected ?? false
  const isError = status?.status === 'ERROR'

  const connectMutation = useConnectWise()
  const syncMutation = useSyncWise()
  const disconnectMutation = useDisconnectWise()

  async function handleConnect(e: React.FormEvent) {
    e.preventDefault()
    if (!token.trim()) return
    setError(null)
    try {
      const jars = await connectMutation.mutateAsync(token.trim())
      setToken('')
      toast.success(t('sync.wise.syncedToast', { count: jars.length }))
      onConnected?.()
    } catch (err: unknown) {
      setError(extractErrorMessage(err, t('sync.wise.errors.connectFailed')))
    }
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      <p className="mx-auto max-w-lg text-sm text-muted-foreground">{t('sync.wise.intro')}</p>

      {isConnected && status && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex flex-wrap items-center gap-3">
              {isError ? (
                <Badge className="bg-destructive/10 text-destructive">{t('sync.wise.statusError')}</Badge>
              ) : (
                <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                  {t('sync.wise.connected')}
                </Badge>
              )}
              {status.maskedToken && (
                <span className="text-sm text-muted-foreground">{status.maskedToken}</span>
              )}
              {status.lastSyncedAt ? (
                <span className="text-xs text-muted-foreground">
                  {t('common.labelColon', { label: t('sync.wise.lastSync') })} {formatDateTime(status.lastSyncedAt)}
                </span>
              ) : (
                <span className="text-xs text-muted-foreground">{t('sync.wise.neverSynced')}</span>
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
        <div className="space-y-4">
          <div className="flex flex-wrap gap-3">
            <Button
              onClick={() => syncMutation.mutate(undefined, {
                onSuccess: (jars) => {
                  setError(null)
                  toast.success(t('sync.wise.syncedToast', { count: jars.length }))
                },
                onError: (err: unknown) => setError(extractErrorMessage(err, t('sync.wise.errors.syncFailed'))),
              })}
              disabled={syncMutation.isPending}
            >
              {syncMutation.isPending ? (
                <>
                  <Loader2 className="size-4 animate-spin" />
                  {t('sync.wise.syncing')}
                </>
              ) : (
                <>
                  <RefreshCw className="size-4" />
                  {t('sync.wise.sync')}
                </>
              )}
            </Button>
            <Button
              variant="destructive"
              onClick={() => setShowDisconnectConfirm(true)}
              disabled={disconnectMutation.isPending}
            >
              <LogOut className="size-4" />
              {t('sync.wise.disconnect')}
            </Button>
          </div>
          <p className="text-xs text-muted-foreground">{t('sync.wise.scopeNote')}</p>
        </div>
      ) : (
        <form onSubmit={handleConnect} className="mx-auto max-w-lg space-y-4">
          <p className="text-sm text-muted-foreground">
            {t('sync.wise.setupHint')}{' '}
            <a
              href={CREATE_TOKEN_URL}
              target="_blank"
              rel="noreferrer"
              className="underline"
            >
              {t('sync.wise.createToken')}
            </a>
          </p>
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="wise-token">
                  <KeyRound className="mr-1 inline-block size-4" />
                  {t('sync.wise.token')}
                </Label>
                <Input
                  id="wise-token"
                  type="password"
                  value={token}
                  onChange={(e) => setToken(e.target.value)}
                  placeholder={t('sync.wise.tokenPlaceholder')}
                  autoComplete="off"
                  required
                />
              </div>
              <Button
                type="submit"
                disabled={connectMutation.isPending || !token.trim()}
                className="w-full"
              >
                {connectMutation.isPending && <Loader2 className="size-4 animate-spin" />}
                {t('sync.wise.connect')}
              </Button>
            </CardContent>
          </Card>
          <p className="text-xs text-muted-foreground">{t('sync.wise.scopeNote')}</p>
        </form>
      )}

      <ConfirmDialog
        open={showDisconnectConfirm}
        onOpenChange={setShowDisconnectConfirm}
        title={t('sync.wise.disconnect')}
        description={t('sync.wise.disconnectConfirm')}
        onConfirm={() => disconnectMutation.mutate(undefined, {
          onSuccess: () => {
            setShowDisconnectConfirm(false)
            setError(null)
          },
          onError: (err: unknown) => {
            setShowDisconnectConfirm(false)
            setError(extractErrorMessage(err, t('sync.wise.errors.disconnectFailed')))
          },
        })}
        loading={disconnectMutation.isPending}
      />
    </div>
  )
}
