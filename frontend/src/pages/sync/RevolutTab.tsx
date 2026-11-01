import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, Loader2, AlertTriangle, CheckCircle2 } from 'lucide-react'
import {
  useRevolutStatus,
  useForgetRevolut,
  useConfirmRevolutSync,
} from '@/features/sync/hooks'
import { revolutPhaseLabel } from '@/features/sync/revolut-phase'
import { useRevolutDiscovery } from '@/features/sync/use-revolut-discovery'
import { RevolutCredentialsForm, type RevolutCredentials } from '@/components/sync/RevolutCredentialsForm'
import { formatApiError } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

const EMPTY_CREDENTIALS: RevolutCredentials = { phoneNumber: '', passcode: '', remember: false }

export function RevolutTab() {
  const { t } = useTranslation()
  const [credentials, setCredentials] = useState<RevolutCredentials>(EMPTY_CREDENTIALS)
  // Brief success card once the auto-confirm below has persisted the discovery;
  // cleared as soon as the next sync starts.
  const [justSynced, setJustSynced] = useState(false)

  const { data: status, isLoading: statusLoading } = useRevolutStatus()
  const confirmSync = useConfirmRevolutSync()
  const forgetMutation = useForgetRevolut()

  const remembered = status?.remembered ?? false

  // This tab is pure auto-sync — no selection step — so a successful discovery immediately
  // persists everything found: refreshes accounts already imported and auto-adds any new
  // pocket/vault.
  const { errorMsg, setErrorMsg, isSyncing, progress, startPending, startDiscovery } = useRevolutDiscovery({
    onStart: () => setJustSynced(false),
    onFinished: (data) => {
      if (data && data.discovered.length > 0) {
        confirmSync.mutate(
          { selectedExternalIds: data.discovered.map((d) => d.externalId), remember: credentials.remember, voluntary: false },
          {
            onSuccess: () => {
              setCredentials((c) => ({ ...c, phoneNumber: '', passcode: '' }))
              setJustSynced(true)
            },
            onError: (err) => setErrorMsg(formatApiError(err, t)),
          },
        )
      } else {
        setJustSynced(true)
      }
    },
  })

  // Covers the brief round-trip before the 202 response flips isSyncing to true, plus
  // the auto-confirm that follows discovery.
  const busy = isSyncing || startPending || confirmSync.isPending

  function handleQuickSync() {
    startDiscovery({})
  }

  function handleFormSync(e: React.FormEvent) {
    e.preventDefault()
    startDiscovery({ phoneNumber: credentials.phoneNumber, passcode: credentials.passcode })
  }

  function handleForget() {
    forgetMutation.mutate()
  }

  if (statusLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  return (
    <div className="space-y-6">
      {/* Session status card */}
      <Card size="sm">
        <CardContent className="py-4">
          <div className="flex flex-wrap items-center gap-3">
            {remembered ? (
              <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">
                {t('sync.revolut.connected')}
              </Badge>
            ) : (
              <Badge variant="outline">{t('sync.revolut.notConnected')}</Badge>
            )}
            {status?.lastSyncedAt && (
              <span className="text-sm text-muted-foreground">
                {t('common.labelColon', { label: t('sync.revolut.lastSync') })} {formatDateTime(status.lastSyncedAt)}
              </span>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Error state */}
      {errorMsg && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="py-4">
            <div className="flex items-center gap-3">
              <AlertTriangle className="size-5 text-destructive shrink-0" />
              <p className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={() => setErrorMsg(null)}>
                {t('sync.banks.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Live phase — the discovery job runs in the background; poll and render its
          current phase (checking session → logging in → mobile approval countdown →
          harvesting accounts) instead of a single static spinner. */}
      {isSyncing && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3 py-4">
            <Loader2 className="size-5 animate-spin text-muted-foreground shrink-0" />
            <p className="text-sm text-muted-foreground">{revolutPhaseLabel(t, progress.data)}</p>
          </CardContent>
        </Card>
      )}

      {/* Discovery done: auto-persisting the refresh + any new pocket, no selection step here. */}
      {confirmSync.isPending && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3 py-4">
            <Loader2 className="size-5 animate-spin text-muted-foreground shrink-0" />
            <p className="text-sm text-muted-foreground">{t('sync.revolut.selection.importing')}</p>
          </CardContent>
        </Card>
      )}

      {justSynced && !busy && !errorMsg && (
        <Card size="sm" className="border-green-500/30">
          <CardContent className="flex items-center gap-3 py-4">
            <CheckCircle2 className="size-5 text-green-500 shrink-0" />
            <p className="text-sm text-muted-foreground">{t('sync.revolut.syncSuccess')}</p>
          </CardContent>
        </Card>
      )}

      {remembered ? (
        <div className="flex flex-wrap gap-3">
          <Button onClick={handleQuickSync} disabled={busy}>
            {busy ? <Loader2 className="animate-spin" /> : <RefreshCw />}
            {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
          </Button>

          <Button variant="destructive" onClick={handleForget} disabled={busy || forgetMutation.isPending}>
            <LogOut />
            {t('sync.revolut.forget')}
          </Button>
        </div>
      ) : (
        <RevolutCredentialsForm
          variant="page"
          value={credentials}
          onChange={setCredentials}
          onSubmit={handleFormSync}
          busy={busy}
        />
      )}
    </div>
  )
}
