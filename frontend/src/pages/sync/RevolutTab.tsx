import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ArrowsClockwise, Bank, SignOut, CircleNotch, Warning, CheckCircle } from '@phosphor-icons/react'
import {
  useRevolutStatus,
  useForgetRevolut,
  useConfirmRevolutSync,
} from '@/features/sync/hooks'
import { revolutPhaseLabel } from '@/features/sync/revolut-phase'
import { useRevolutDiscovery, useRevolutWebview } from '@/features/sync/use-revolut-discovery'
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
  const connected = status?.connected ?? false
  const webviewSupported = status?.webviewLoginSupported === true

  // This tab is pure auto-sync — no selection step — so a successful discovery immediately
  // persists everything found: refreshes accounts already imported and auto-adds any new
  // pocket/vault.
  const { errorMsg, setErrorMsg, isSyncing, progress, startPending, startDiscovery, attachDiscovery } =
    useRevolutDiscovery({
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

  const { webviewState, webviewInProgress, webviewStarting, startWebview, cancelWebview } =
    useRevolutWebview({
      onStart: () => {
        setErrorMsg(null)
        setJustSynced(false)
      },
      onSyncing: () => attachDiscovery(),
      onError: (msg) => setErrorMsg(msg),
    })

  // Covers the brief round-trip before the 202 response flips isSyncing to true, plus
  // the auto-confirm that follows discovery.
  const busy = isSyncing || startPending || confirmSync.isPending || webviewStarting || webviewInProgress
  const isConnectedDesktop = webviewSupported && connected
  const showConnectedBadge = remembered || isConnectedDesktop

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
        <CardContent>
          <div className="flex flex-wrap items-center gap-3">
            {showConnectedBadge ? (
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
          <CardContent>
            <div className="flex items-center gap-3">
              <Warning className="size-5 text-destructive shrink-0" />
              <p role="alert" className="text-sm text-destructive flex-1">{errorMsg}</p>
              <Button size="sm" variant="outline" onClick={() => setErrorMsg(null)}>
                {t('sync.banks.retry')}
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Desktop WebView interactive login waiting / fetching state */}
      {webviewInProgress && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3">
            <ArrowsClockwise className="size-5 shrink-0 animate-spin text-muted-foreground" />
            <div className="flex-1 space-y-1">
              <p className="text-sm">
                {webviewState === 'FETCHING'
                  ? t('sync.revolut.webview.fetching')
                  : t('sync.revolut.webview.waiting')}
              </p>
              {webviewState === 'WAITING' && (
                <p className="text-sm text-muted-foreground">{t('sync.revolut.webview.waitingHint')}</p>
              )}
            </div>
            {webviewState === 'WAITING' && (
              <Button type="button" variant="outline" size="sm" onClick={cancelWebview}>
                {t('common.cancel')}
              </Button>
            )}
          </CardContent>
        </Card>
      )}

      {/* Live phase — the discovery job runs in the background; poll and render its
          current phase (checking session → logging in → mobile approval countdown →
          harvesting accounts) instead of a single static spinner. */}
      {isSyncing && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3">
            <CircleNotch className="size-5 animate-spin text-muted-foreground shrink-0" />
            <p className="text-sm text-muted-foreground">{revolutPhaseLabel(t, progress.data)}</p>
          </CardContent>
        </Card>
      )}

      {/* Discovery done: auto-persisting the refresh + any new pocket, no selection step here. */}
      {confirmSync.isPending && (
        <Card size="sm">
          <CardContent className="flex items-center gap-3">
            <CircleNotch className="size-5 animate-spin text-muted-foreground shrink-0" />
            <p className="text-sm text-muted-foreground">{t('sync.revolut.selection.importing')}</p>
          </CardContent>
        </Card>
      )}

      {justSynced && !busy && !errorMsg && (
        <Card size="sm" className="border-green-500/30">
          <CardContent className="flex items-center gap-3">
            <CheckCircle className="size-5 text-green-500 shrink-0" />
            <p className="text-sm text-muted-foreground">{t('sync.revolut.syncSuccess')}</p>
          </CardContent>
        </Card>
      )}

      {webviewSupported && !webviewInProgress ? (
        isConnectedDesktop ? (
          <div className="flex flex-wrap gap-3">
            <Button onClick={handleQuickSync} disabled={busy}>
              {busy ? <CircleNotch className="animate-spin" /> : <ArrowsClockwise />}
              {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
            </Button>

            <Button type="button" variant="outline" onClick={() => void startWebview()} disabled={busy}>
              <Bank />
              {t('sync.revolut.webview.reconnect')}
            </Button>

            <Button variant="destructive" onClick={handleForget} disabled={busy || forgetMutation.isPending}>
              <SignOut />
              {t('sync.revolut.forget')}
            </Button>
          </div>
        ) : (
          <Card size="sm">
            <CardContent className="space-y-4">
              <p className="text-sm text-muted-foreground">{t('sync.revolut.webview.hint')}</p>
              <Button type="button" disabled={busy} onClick={() => void startWebview()}>
                {webviewStarting ? <ArrowsClockwise className="animate-spin" /> : <Bank />}
                {webviewStarting ? t('sync.revolut.syncing') : t('sync.revolut.webview.connect')}
              </Button>
            </CardContent>
          </Card>
        )
      ) : !webviewSupported ? (
        remembered ? (
          <div className="flex flex-wrap gap-3">
            <Button onClick={handleQuickSync} disabled={busy}>
              {busy ? <CircleNotch className="animate-spin" /> : <ArrowsClockwise />}
              {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
            </Button>

            <Button variant="destructive" onClick={handleForget} disabled={busy || forgetMutation.isPending}>
              <SignOut />
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
        )
      ) : null}
    </div>
  )
}
