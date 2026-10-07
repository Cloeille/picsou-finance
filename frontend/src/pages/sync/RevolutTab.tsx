import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Checkbox } from '@/components/ui/checkbox'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { RefreshCw, LogOut, Smartphone, Lock, Loader2, AlertTriangle, CheckCircle2 } from 'lucide-react'
import {
  useRevolutStatus,
  useForgetRevolut,
  useStartRevolutSync,
  useSyncProgress,
  useConfirmRevolutSync,
} from '@/features/sync/hooks'
import { revolutPhaseLabel } from '@/features/sync/revolut-phase'
import { formatApiError } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

export function RevolutTab() {
  const { t } = useTranslation()
  const [phoneNumber, setPhoneNumber] = useState('')
  const [passcode, setPasscode] = useState('')
  const [remember, setRemember] = useState(false)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  // Whether a discovery job is currently running (from the moment the 202 comes back
  // to the running → done transition detected below) — gates both the poll and the UI.
  const [isSyncing, setIsSyncing] = useState(false)
  // Brief success card once the auto-confirm below has persisted the discovery;
  // cleared as soon as the next sync starts.
  const [justSynced, setJustSynced] = useState(false)

  const { data: status, isLoading: statusLoading } = useRevolutStatus()
  const startSync = useStartRevolutSync()
  const progress = useSyncProgress('revolut', isSyncing)
  const confirmSync = useConfirmRevolutSync()
  const forgetMutation = useForgetRevolut()

  const remembered = status?.remembered ?? false
  // Covers the brief round-trip before the 202 response flips isSyncing to true, plus
  // the auto-confirm that follows discovery.
  const busy = isSyncing || startSync.isPending || confirmSync.isPending

  // Detect the running → done transition (mirrors CategorizeTab's AI-job pattern) to stop
  // polling and surface a discovery error. This tab is pure auto-sync — no selection step —
  // so a successful discovery immediately persists everything found: refreshes accounts
  // already imported and auto-adds any new pocket/vault.
  const prevRunningRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const running = progress.data?.running
    if (prevRunningRef.current === true && running === false) {
      setIsSyncing(false)
      const data = progress.data
      if (data?.error) {
        // Reacting to the running → done transition detected above, not deriving render state.
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setErrorMsg(data.error)
      } else if (data && data.discovered.length > 0) {
        confirmSync.mutate(
          { selectedExternalIds: data.discovered.map((d) => d.externalId), remember, voluntary: false },
          {
            onSuccess: () => {
              setPhoneNumber('')
              setPasscode('')
              setJustSynced(true)
            },
            onError: (err) => setErrorMsg(formatApiError(err, t)),
          },
        )
      } else {
        setJustSynced(true)
      }
    }
    prevRunningRef.current = running
    // progress.data/confirmSync/remember/t are read through the running dep on purpose
    // (see CategorizeTab) — confirmSync in particular is a fresh object every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress.data?.running])

  function startDiscovery(body: { phoneNumber?: string; passcode?: string }) {
    setErrorMsg(null)
    setJustSynced(false)
    startSync.mutate(body, {
      onSuccess: () => setIsSyncing(true),
      onError: (err) => setErrorMsg(formatApiError(err, t)),
    })
  }

  function handleQuickSync() {
    startDiscovery({})
  }

  function handleFormSync(e: React.FormEvent) {
    e.preventDefault()
    startDiscovery({ phoneNumber, passcode })
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
                {t('sync.revolut.lastSync')}: {formatDateTime(status.lastSyncedAt)}
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
        <form onSubmit={handleFormSync} className="space-y-4">
          <Card size="sm">
            <CardContent className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="revolut-phone">
                  <Smartphone className="size-4 inline-block mr-1" />
                  {t('sync.revolut.phone')}
                </Label>
                <Input
                  id="revolut-phone"
                  type="tel"
                  value={phoneNumber}
                  onChange={(e) => setPhoneNumber(e.target.value)}
                  required
                  disabled={busy}
                  placeholder="+33..."
                />
              </div>

              <div className="space-y-2">
                <Label htmlFor="revolut-passcode">
                  <Lock className="size-4 inline-block mr-1" />
                  {t('sync.revolut.passcode')}
                </Label>
                <Input
                  id="revolut-passcode"
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  value={passcode}
                  onChange={(e) => setPasscode(e.target.value)}
                  required
                  disabled={busy}
                />
              </div>

              <label className="flex items-center gap-2 cursor-pointer">
                <Checkbox
                  checked={remember}
                  onCheckedChange={(checked) => setRemember(checked === true)}
                  disabled={busy}
                />
                <span className="text-sm text-muted-foreground">{t('sync.revolut.remember')}</span>
              </label>

              <Button type="submit" disabled={busy} className="w-full">
                {busy && <Loader2 className="size-4 animate-spin" />}
                {busy ? t('sync.revolut.syncing') : t('sync.revolut.sync')}
              </Button>
            </CardContent>
          </Card>
        </form>
      )}
    </div>
  )
}
