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
import { extractErrorMessage, getErrorCode } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

const CREATE_TOKEN_URL = 'https://wise.com/your-account/integrations-and-tools/api-tokens'
const STATUS_ERROR = 'ERROR'
const STATUS_REAUTH_REQUIRED = 'REAUTH_REQUIRED'

/** Codes the Wise endpoints attach to the failures a member can act on. */
const ERROR_KEYS: Record<string, string> = {
  SESSION_EXPIRED: 'sync.wise.errors.tokenRefused',
  INVALID_TOKEN: 'sync.wise.errors.invalidToken',
  RATE_LIMITED: 'sync.wise.errors.rateLimited',
}

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
  const tokenRefused = status?.status === STATUS_REAUTH_REQUIRED
  const lastSyncFailed = status?.status === STATUS_ERROR

  const connectMutation = useConnectWise()
  const syncMutation = useSyncWise()
  const disconnectMutation = useDisconnectWise()

  function errorMessage(err: unknown, fallbackKey: string) {
    const key = ERROR_KEYS[getErrorCode(err) ?? '']
    return key ? t(key) : extractErrorMessage(err, t(fallbackKey))
  }

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
      setError(errorMessage(err, 'sync.wise.errors.connectFailed'))
    }
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  const tokenForm = (
    <form onSubmit={handleConnect} className="mx-auto max-w-lg space-y-4">
      <p className="text-sm text-muted-foreground">
        {t(tokenRefused ? 'sync.wise.replaceTokenHint' : 'sync.wise.setupHint')}{' '}
        <a href={CREATE_TOKEN_URL} target="_blank" rel="noreferrer" className="underline">
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
          <Button type="submit" disabled={connectMutation.isPending || !token.trim()} className="w-full">
            {connectMutation.isPending && <Loader2 className="size-4 animate-spin" />}
            {t(tokenRefused ? 'sync.wise.replaceToken' : 'sync.wise.connect')}
          </Button>
        </CardContent>
      </Card>
    </form>
  )

  const disconnectButton = (
    <Button
      variant="destructive"
      onClick={() => setShowDisconnectConfirm(true)}
      disabled={disconnectMutation.isPending}
    >
      <LogOut className="size-4" />
      {t('sync.wise.disconnect')}
    </Button>
  )

  return (
    <div className="space-y-6">
      <p className="mx-auto max-w-lg text-sm text-muted-foreground">{t('sync.wise.intro')}</p>

      {isConnected && status && (
        <Card size="sm">
          <CardContent className="flex items-center justify-between py-4">
            <div className="flex flex-wrap items-center gap-3">
              {tokenRefused ? (
                <Badge className="bg-destructive/10 text-destructive">{t('sync.wise.statusTokenRefused')}</Badge>
              ) : lastSyncFailed ? (
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

      {isConnected && !tokenRefused && (
        <div className="flex flex-wrap gap-3">
          <Button
            onClick={() => syncMutation.mutate(undefined, {
              onSuccess: (jars) => {
                setError(null)
                toast.success(t('sync.wise.syncedToast', { count: jars.length }))
              },
              onError: (err: unknown) => setError(errorMessage(err, 'sync.wise.errors.syncFailed')),
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
          {disconnectButton}
        </div>
      )}

      {(!isConnected || tokenRefused) && tokenForm}

      {isConnected && tokenRefused && <div className="mx-auto flex max-w-lg">{disconnectButton}</div>}

      <p className="mx-auto max-w-lg text-xs text-muted-foreground">{t('sync.wise.scopeNote')}</p>

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
