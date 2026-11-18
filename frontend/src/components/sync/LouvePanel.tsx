import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { AlertTriangle, Building2, Loader2, LogOut, RefreshCw } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { ScpiSyncSummary } from '@/components/scpi/ScpiSyncSummary'
import {
  useCompleteLouveConnection,
  useDisconnectLouve,
  useLouveStatus,
  useStartLouveConnection,
  useSyncLouve,
} from '@/features/sync/hooks'
import { extractErrorMessage, getErrorCode } from '@/lib/errors'
import { formatDateTime } from '@/lib/utils'

/** Where Louve sends the browser back; also the redirect URI the backend registers. */
export const LOUVE_CALLBACK_PATH = '/sync/louve/callback'

/** Codes the Louve endpoints attach to a failure, and their wording. */
const ERROR_KEYS: Record<string, string> = {
  AUTH_ATTEMPT_EXPIRED: 'sync.louve.errors.authAttemptExpired',
  SESSION_EXPIRED: 'sync.louve.errors.sessionExpired',
  INVALID_SESSION_STATE: 'sync.louve.errors.sessionExpired',
  RATE_LIMITED: 'sync.louve.errors.rateLimited',
  PORTFOLIO_INCOMPLETE: 'sync.louve.errors.portfolioIncomplete',
  UPSTREAM_FORMAT_CHANGED: 'sync.louve.errors.formatChanged',
  INVALID_DATA: 'sync.louve.errors.invalidData',
  UPSTREAM_UNAVAILABLE: 'sync.louve.errors.serverError',
  INTERNAL_ERROR: 'sync.louve.errors.serverError',
  ORIGIN_NOT_ALLOWED: 'sync.louve.errors.originNotAllowed',
  ORIGIN_NOT_SECURE: 'sync.louve.errors.originNotSecure',
}

interface LouvePanelProps {
  onConnected?: () => void
}

/**
 * Louve Invest through its official MCP server: no identifier or password is typed here. "Connect"
 * sends the browser to Louve's consent page ("Lire votre portefeuille", read-only); Louve sends it
 * back to {@link LOUVE_CALLBACK_PATH}, where this panel posts the code once and cleans the URL.
 */
export function LouvePanel({ onConnected }: LouvePanelProps = {}) {
  const { t } = useTranslation()
  const location = useLocation()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // A callback that failed hands its message over through the navigation state (see `leave`).
  const [error, setError] = useState<string | null>(
    () => (location.state as { louveError?: string } | null)?.louveError ?? null,
  )
  const [confirmDisconnect, setConfirmDisconnect] = useState(false)
  const handledCallback = useRef(false)
  // Where the panel stands now, for a callback answer that arrives late: by then the member may
  // have gone to another page, whose history entry must not be replaced.
  const mounted = useRef(false)
  const here = useRef(location)
  useEffect(() => {
    here.current = location
  }, [location])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  const status = useLouveStatus()
  const start = useStartLouveConnection()
  const complete = useCompleteLouveConnection()
  const sync = useSyncLouve()
  const clear = useDisconnectLouve()

  const messageOf = (err: unknown, fallbackKey: string) => {
    const key = ERROR_KEYS[getErrorCode(err) ?? '']
    return key ? t(key) : extractErrorMessage(err, t(fallbackKey))
  }

  // The callback: Louve appended code, state and iss (or an error). Posted once, then the URL is
  // replaced so a reload or the back button never replays a single-use code.
  const onCallback = location.pathname === LOUVE_CALLBACK_PATH
  const completeAsync = complete.mutateAsync
  useEffect(() => {
    if (!onCallback || handledCallback.current) return
    handledCallback.current = true
    const code = searchParams.get('code')
    const state = searchParams.get('state')
    const iss = searchParams.get('iss')
    // The single-use code leaves the address bar and the history at once, before the call, so
    // neither a reload nor the back button can replay it, whatever the call does. A message is set
    // here and carried in the navigation state: whether the router keeps this panel mounted
    // across the URL change or mounts a new one, it is shown once.
    const leave = (louveError?: string) => {
      if (louveError) setError(louveError)
      navigate('/sync?tab=louve', { replace: true, state: louveError ? { louveError } : null })
    }
    if (searchParams.get('error')) {
      leave(t(searchParams.get('error') === 'access_denied'
        ? 'sync.louve.errors.consentDenied'
        : 'sync.louve.errors.callbackInvalid'))
      return
    }
    if (!code || !state || !iss) {
      leave(t('sync.louve.errors.callbackInvalid'))
      return
    }
    leave()
    // The promise, not mutate's per-call callbacks: those are dropped when the observer that made
    // the call unsubscribes (StrictMode's effect replay, the route change above), which is what
    // left the callback URL in place. The history entry was replaced above, once: the answer never
    // navigates again, so a member who went elsewhere meanwhile stays where they are.
    completeAsync({ code, state, iss })
      .then(() => {
        toast.success(t('sync.louve.connectedToast'))
        onConnected?.()
      })
      .catch((err: unknown) => {
        const message = messageOf(err, 'sync.louve.errors.connectFailed')
        if (mounted.current && here.current.pathname !== LOUVE_CALLBACK_PATH
            && here.current.search.includes('tab=louve')) {
          setError(message)
        } else {
          toast.error(message)
        }
      })
    // Runs once per callback visit; the ref guards StrictMode's double effect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onCallback])

  function handleConnect() {
    setError(null)
    start.mutate(undefined, {
      onSuccess: ({ authorizeUrl }) => window.location.assign(authorizeUrl),
      onError: (err: unknown) => setError(messageOf(err, 'sync.louve.errors.connectFailed')),
    })
  }

  if (status.isLoading) {
    return <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
  }

  const current = status.data
  const connected = current?.connected ?? false
  // Louve refused the grant: the tokens stay stored (a disconnect still revokes them), but only a
  // new consent can sync again.
  const needsReconnect = connected && current?.status === 'REAUTH_REQUIRED'
  const isActive = connected && !needsReconnect
  const lastFailed = isActive && current?.status === 'ERROR'
  const running = sync.isPending
  const completing = complete.isPending

  return (
    <div className="space-y-6">
      <p className="mx-auto max-w-lg text-sm text-muted-foreground">{t('sync.louve.intro')}</p>

      <Card size="sm">
        <CardContent className="flex flex-wrap items-center gap-3 py-4">
          <Building2 className="size-5 text-muted-foreground" />
          {isActive ? (
            <Badge className="bg-green-500/10 text-green-600 dark:text-green-400">{t('sync.louve.connected')}</Badge>
          ) : needsReconnect ? (
            <Badge className="bg-destructive/10 text-destructive">{t('sync.louve.needsReconnect')}</Badge>
          ) : (
            <Badge variant="outline">{t('sync.louve.notConnected')}</Badge>
          )}
          {isActive && (
            <span className="text-xs text-muted-foreground">
              {current?.lastSyncedAt
                ? `${t('common.labelColon', { label: t('sync.louve.lastSync') })} ${formatDateTime(current.lastSyncedAt)}`
                : t('sync.louve.neverSynced')}
            </span>
          )}
        </CardContent>
      </Card>

      {isActive && !lastFailed && current?.lastSyncedAt && (
        <ScpiSyncSummary provider="LOUVE" completedAt={current.lastSyncedAt} />
      )}

      {(error || needsReconnect || lastFailed) && (
        <Card size="sm" className="border-destructive/30">
          <CardContent className="flex items-center gap-3 py-4">
            <AlertTriangle className="size-5 shrink-0 text-destructive" />
            <p className="flex-1 text-sm text-destructive">
              {error ?? t(needsReconnect ? 'sync.louve.errors.sessionExpired' : 'sync.louve.errors.lastSyncFailed')}
            </p>
          </CardContent>
        </Card>
      )}

      <div className="flex flex-wrap gap-3">
        {isActive ? (
          <>
            <Button
              onClick={() => sync.mutate(undefined, {
                onSuccess: () => setError(null),
                onError: (err: unknown) => setError(messageOf(err, 'sync.louve.errors.syncFailed')),
              })}
              disabled={running}
            >
              {running ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
              {running ? t('sync.louve.syncing') : t('sync.louve.sync')}
            </Button>
            <Button variant="destructive" onClick={() => setConfirmDisconnect(true)} disabled={clear.isPending}>
              <LogOut className="size-4" />
              {t('sync.louve.disconnect')}
            </Button>
          </>
        ) : (
          <>
            <Button onClick={handleConnect} disabled={start.isPending || completing}>
              {(start.isPending || completing) && <Loader2 className="size-4 animate-spin" />}
              {completing
                ? t('sync.louve.completing')
                : needsReconnect ? t('sync.louve.reconnect') : t('sync.louve.connect')}
            </Button>
            {needsReconnect && (
              <Button variant="destructive" onClick={() => setConfirmDisconnect(true)} disabled={clear.isPending}>
                <LogOut className="size-4" />
                {t('sync.louve.disconnect')}
              </Button>
            )}
          </>
        )}
      </div>

      <p className="mx-auto max-w-lg text-xs text-muted-foreground">{t('sync.louve.scope')}</p>

      <ConfirmDialog
        open={confirmDisconnect}
        onOpenChange={setConfirmDisconnect}
        title={t('sync.louve.disconnect')}
        description={t('sync.louve.disconnectConfirm')}
        onConfirm={() => clear.mutate(undefined, {
          onSuccess: () => {
            setConfirmDisconnect(false)
            setError(null)
          },
          onError: (err: unknown) => {
            setConfirmDisconnect(false)
            setError(extractErrorMessage(err, t('sync.louve.errors.disconnectFailed')))
          },
        })}
        loading={clear.isPending}
      />
    </div>
  )
}
