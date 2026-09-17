import { useState, useCallback, useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useQueryClient } from '@tanstack/react-query'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { EmptyState } from '@/components/shared/EmptyState'
import { Skeleton } from '@/components/ui/skeleton'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import {
  AlertTriangle,
  Loader2,
  RefreshCw,
  ExternalLink,
  Landmark,
  Coins,
  Wallet,
  Building2,
  LineChart,
  CreditCard,
  Info,
  Smartphone,
  Lock,
  PiggyBank,
  ShieldCheck,
} from 'lucide-react'
import {
  useBankSyncStatus,
  useCryptoExchangeStatuses,
  useCryptoWallets,
  useTrSessionStatus,
  useBoursoSessionStatus,
  useRevolutStatus,
  useFinaryConnectionStatus,
  useRetryBankSync,
  useReconnectBankSync,
  useSyncCryptoExchange,
  useSyncCryptoWallet,
  useSyncTradeRepublic,
  useInitiateTrAuth,
  useCompleteTrAuth,
  useSyncBourso,
  useAmundiStatus,
  useSyncAmundi,
  useBourseDirectStatus,
  useSyncBourseDirect,
  useDegiroSessionStatus,
  useSyncDegiro,
  useIbkrStatus,
  useSyncIbkr,
} from '@/features/sync/hooks'
import { useAccounts } from '@/features/accounts/hooks'
import { formatTimeAgo } from '@/lib/utils'
import { formatApiError, formatTrAuthError, isTrSessionDeadError } from '@/lib/errors'
import { syncKeys } from '@/features/sync/hooks'
import { TR_VERIFICATION_CODE_LENGTH } from '@/lib/constants'

type SyncConnection = {
  id: string
  providerType: 'bank' | 'exchange' | 'wallet' | 'tr' | 'finary' | 'bourso' | 'revolut'
    | 'amundi' | 'bourse-direct' | 'degiro' | 'ibkr'
  name: string
  status: string
  lastSyncedAt: string | null
  syncId?: number
  /** Session-based providers open their tab to re-authenticate instead of firing a doomed sync. */
  needsReauth?: boolean
}


const ProviderIcon: Record<SyncConnection['providerType'], React.ComponentType<{ className?: string }>> = {
  bank: Landmark,
  exchange: Coins,
  wallet: Wallet,
  tr: Building2,
  finary: LineChart,
  bourso: Building2,
  revolut: CreditCard,
  amundi: PiggyBank,
  'bourse-direct': LineChart,
  degiro: LineChart,
  ibkr: LineChart,
}

/** Which Sync-page tab each provider re-authenticates on. */
const REAUTH_TAB: Partial<Record<SyncConnection['providerType'], string>> = {
  amundi: 'amundi',
  bourso: 'bourso',
  'bourse-direct': 'bourse-direct',
  degiro: 'degiro',
  ibkr: 'ibkr',
  finary: 'finary',
}

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'LINKED':
    case 'CONNECTED':
    case 'active':
      return 'default'
    case 'CREATED':
      return 'secondary'
    case 'SESSION_EXPIRED':
    case 'EXPIRED':
      return 'outline'
    case 'FAILED':
    case 'ERROR':
      return 'destructive'
    default:
      return 'outline'
  }
}

interface SyncAllModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function SyncAllModal({ open, onOpenChange }: SyncAllModalProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  // Queries
  const { data: banks, isLoading: banksLoading } = useBankSyncStatus()
  const { data: exchanges, isLoading: exchangesLoading } = useCryptoExchangeStatuses()
  const { data: wallets, isLoading: walletsLoading } = useCryptoWallets()
  const { data: trStatus } = useTrSessionStatus()
  const { data: boursoStatus } = useBoursoSessionStatus()
  const { data: revolutStatus } = useRevolutStatus()
  const { data: finaryStatus } = useFinaryConnectionStatus()
  const { data: amundiStatus } = useAmundiStatus()
  const { data: bourseDirectStatus } = useBourseDirectStatus()
  const { data: degiroStatus } = useDegiroSessionStatus()
  const { data: ibkrStatus } = useIbkrStatus()
  const { data: accounts } = useAccounts()

  // Show TR in modal if: there are active TR accounts, the session is active, or the session
  // is expired but was previously created (expiresAt != null). This keeps TR visible even when
  // accounts were soft-deleted — the session still exists and the user can reconnect from here.
  // isActive=false + expiresAt=null means no session has ever been created → hide TR.
  const hasTrSession = trStatus?.isActive === true || trStatus?.expiresAt != null
  const hasTrAccount = (accounts?.some(a => a.provider === 'Trade Republic') ?? false) || hasTrSession
  // Same "keep visible across soft-delete" rule as TR — see comment above.
  // Remembered credentials play the role expiresAt played for TR: they mean
  // a Revolut connection exists even if every synced account was soft-deleted.
  const hasRevolutSession = revolutStatus?.connected === true || revolutStatus?.remembered === true
  const hasRevolutAccount = (accounts?.some(a => a.provider === 'Revolut') ?? false) || hasRevolutSession

  // Mutations
  /**
   * The connectors that hold one session per member. Each is listed when its session is live
   * *or* the user still has accounts from it — a dead session must stay visible and
   * re-authenticatable rather than disappear from the list, the same rule Trade Republic
   * already followed. `provider` is the exact string the connector stamps on the accounts it
   * creates (see each service's PROVIDER constant).
   */
  const sessionProviders = useMemo(() => [
    {
      type: 'amundi' as const, name: 'Amundi', provider: 'Amundi Épargne Salariale',
      active: amundiStatus?.isActive ?? false, lastSyncedAt: amundiStatus?.lastSyncCompletedAt ?? null,
      failed: amundiStatus?.syncStatus === 'FAILED',
    },
    {
      type: 'bourso' as const, name: 'BoursoBank', provider: 'BoursoBank',
      active: boursoStatus?.isActive ?? false, lastSyncedAt: boursoStatus?.lastSyncCompletedAt ?? null,
      failed: boursoStatus?.syncStatus === 'FAILED',
    },
    {
      type: 'bourse-direct' as const, name: 'Bourse Direct', provider: 'Bourse Direct',
      active: bourseDirectStatus?.isActive ?? false, lastSyncedAt: bourseDirectStatus?.lastSyncCompletedAt ?? null,
      failed: bourseDirectStatus?.syncStatus === 'FAILED',
    },
    {
      type: 'degiro' as const, name: 'DEGIRO', provider: 'DEGIRO',
      active: degiroStatus?.isActive ?? false, lastSyncedAt: degiroStatus?.lastSyncedAt ?? null,
      failed: degiroStatus?.status === 'FAILED',
      // DEGIRO says so explicitly; the others only ever report an inactive session.
      reauth: degiroStatus?.status === 'REAUTH_REQUIRED',
    },
    {
      // `connected` stays true through a failed sync -- the Flex connection exists, it is the
      // last run that broke -- so without `failed` an errored IBKR row would read as healthy.
      type: 'ibkr' as const, name: 'Interactive Brokers', provider: 'Interactive Brokers',
      active: ibkrStatus?.connected ?? false, lastSyncedAt: ibkrStatus?.lastSyncedAt ?? null,
      failed: ibkrStatus?.status === 'ERROR',
    },
  ], [amundiStatus, boursoStatus, bourseDirectStatus, degiroStatus, ibkrStatus])

  const retryBankMutation     = useRetryBankSync()
  const reconnectBankMutation = useReconnectBankSync()
  const syncExchangeMutation = useSyncCryptoExchange()
  const syncWalletMutation   = useSyncCryptoWallet()
  const syncTrMutation       = useSyncTradeRepublic()
  const initiateTrMutation   = useInitiateTrAuth()
  const completeTrMutation   = useCompleteTrAuth()
  const syncBoursoMutation   = useSyncBourso()
  const syncAmundiMutation       = useSyncAmundi()
  const syncBourseDirectMutation = useSyncBourseDirect()
  const syncDegiroMutation       = useSyncDegiro()
  const syncIbkrMutation         = useSyncIbkr()

  // Track syncing state per connection
  const [syncingIds, setSyncingIds] = useState<Set<string>>(new Set())

  // TR inline auth state
  const [trAuthStep, setTrAuthStep] = useState<'idle' | 'phone' | 'tan'>('idle')
  const [trPhone, setTrPhone] = useState('')
  const [trPin, setTrPin] = useState('')
  const [trTan, setTrTan] = useState('')
  const [trProcessId, setTrProcessId] = useState<string | null>(null)
  const [trAuthError, setTrAuthError] = useState<string | null>(null)

  // Per-connection sync/retry errors, keyed by connection id
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({})

  const isLoading = banksLoading || exchangesLoading || walletsLoading

  // Build unified connections list. Memoized so its identity is stable
  // across renders — otherwise it would invalidate the useCallback hooks
  // below (and the compiler's exhaustive-deps check) on every render.
  const connections = useMemo<SyncConnection[]>(() => {
    const list: SyncConnection[] = []
    if (banks) {
      banks
        .filter(b => b.status !== 'CREATED')
        .forEach(b => {
          list.push({
            id: `bank-${b.id}`,
            providerType: 'bank',
            name: b.institutionName,
            status: b.status,
            lastSyncedAt: b.lastSyncedAt,
            syncId: b.id,
          })
        })
    }
    if (exchanges) {
      exchanges.forEach(e => {
        list.push({
          id: `exchange-${e.id}`,
          providerType: 'exchange',
          name: e.exchangeType,
          status: e.status,
          lastSyncedAt: e.lastSyncedAt,
          syncId: e.id,
        })
      })
    }
    if (wallets) {
      wallets.forEach(w => {
        list.push({
          id: `wallet-${w.id}`,
          providerType: 'wallet',
          name: w.label || `${w.chain} - ${w.address.slice(0, 8)}...`,
          status: 'CONNECTED',
          lastSyncedAt: w.lastSyncedAt,
          syncId: w.id,
        })
      })
    }
    // Show TR when user has a TR account, regardless of session status
    if (hasTrAccount) {
      const trAccount = accounts?.find(a => a.provider === 'Trade Republic')
      list.push({
        id: 'tr',
        providerType: 'tr',
        name: 'Trade Republic',
        status: trStatus?.isActive ? 'active' : 'SESSION_EXPIRED',
        lastSyncedAt: trAccount?.lastSyncedAt ?? null,
      })
    }
    for (const provider of sessionProviders) {
      const hasAccount = accounts?.some(a => a.provider === provider.provider) ?? false
      if (!provider.active && !hasAccount) continue
      list.push({
        id: provider.type,
        providerType: provider.type,
        name: provider.name,
        // A failed run is reported as such rather than as "active": the session may well still
        // be live, but a row that reads healthy while its last sync errored is the state a
        // user never thinks to look into.
        status: provider.failed ? 'FAILED' : provider.active ? 'active' : 'SESSION_EXPIRED',
        lastSyncedAt: provider.lastSyncedAt,
        // A failure is not automatically a credential problem -- a Flex outage or a rate limit
        // clears on its own -- so a failed-but-live session keeps its retry button. Only a
        // session that is gone, or one the provider explicitly flags, goes to its tab.
        needsReauth: !provider.active || provider.reauth === true,
      })
    }
    if (hasRevolutAccount) {
      const revolutAccount = accounts?.find(a => a.provider === 'Revolut')
      list.push({
        id: 'revolut',
        providerType: 'revolut',
        name: 'Revolut',
        status: revolutStatus?.remembered ? 'active' : 'SESSION_EXPIRED',
        lastSyncedAt: revolutAccount?.lastSyncedAt ?? revolutStatus?.lastSyncedAt ?? null,
      })
    }
    if (finaryStatus?.connected) {
      list.push({
        id: 'finary',
        providerType: 'finary',
        name: 'Finary',
        status: finaryStatus.status || 'CONNECTED',
        lastSyncedAt: finaryStatus.lastSyncedAt,
      })
    }
    return list
  }, [banks, exchanges, wallets, hasTrAccount, accounts, trStatus?.isActive, hasRevolutAccount, revolutStatus?.remembered, revolutStatus?.lastSyncedAt, finaryStatus, sessionProviders])

  const handleSync = useCallback((connection: SyncConnection) => {
    // TR without active session: open inline auth instead of syncing
    if (connection.providerType === 'tr' && !trStatus?.isActive) {
      setTrAuthError(null)
      setTrAuthStep('phone')
      return
    }
    // The remaining session providers each re-authenticate through their own multi-step form
    // (credentials, MFA, a Flex token). Rather than duplicate four flows in this modal, send
    // the user to the tab that owns them -- firing the sync would only return a 401.
    const reauthTab = connection.needsReauth ? REAUTH_TAB[connection.providerType] : undefined
    if (reauthTab) {
      navigate(`/sync?tab=${reauthTab}`)
      onOpenChange(false)
      return
    }
    // Revolut without remembered credentials: the phone+passcode form needs
    // real screen space (and the sync blocks on a mobile approval) — send the
    // user to the full tab, same affordance as Finary.
    if (connection.providerType === 'revolut' && !revolutStatus?.remembered) {
      navigate('/sync?tab=revolut')
      onOpenChange(false)
      return
    }

    setSyncingIds(prev => new Set(prev).add(connection.id))

    const clearSyncing = () => setSyncingIds(prev => {
      const next = new Set(prev)
      next.delete(connection.id)
      return next
    })
    const clearRowError = () => setRowErrors(prev => {
      if (!(connection.id in prev)) return prev
      const next = { ...prev }
      delete next[connection.id]
      return next
    })
    /** Row-scoped mutation callbacks: clear the spinner, and show the row's error on failure. */
    const rowCallbacks = (formatError: (err: unknown) => string) => ({
      onSettled: clearSyncing,
      onSuccess: clearRowError,
      onError: (err: unknown) => setRowErrors(prev => ({ ...prev, [connection.id]: formatError(err) })),
    })
    const formatGeneric = (err: unknown) => formatApiError(err, t, 'common.errors.serverError')

    switch (connection.providerType) {
      case 'bank':
        if (connection.syncId !== undefined) retryBankMutation.mutate(connection.syncId, rowCallbacks(formatGeneric))
        break
      case 'exchange':
        if (connection.syncId !== undefined) syncExchangeMutation.mutate(connection.syncId, rowCallbacks(formatGeneric))
        break
      case 'wallet':
        if (connection.syncId !== undefined) syncWalletMutation.mutate(connection.syncId, rowCallbacks(formatGeneric))
        break
      case 'tr':
        syncTrMutation.mutate(undefined, {
          onSettled: clearSyncing,
          onSuccess: clearRowError,
          onError: (err: unknown) => {
            setRowErrors(prev => ({ ...prev, [connection.id]: formatTrAuthError(err, t) }))
            // Session truly dead (refresh rejected or session cleared): refetch
            // the now-inactive status and fall back to the inline phone/PIN form.
            if (isTrSessionDeadError(err)) {
              queryClient.invalidateQueries({ queryKey: syncKeys.tr() })
              setTrAuthStep('phone')
            }
          },
        })
        break
      case 'bourso':
        syncBoursoMutation.mutate(undefined, rowCallbacks(formatGeneric))
        break
      case 'amundi':
        syncAmundiMutation.mutate(undefined, rowCallbacks(formatGeneric))
        break
      case 'bourse-direct':
        syncBourseDirectMutation.mutate(undefined, rowCallbacks(formatGeneric))
        break
      case 'degiro':
        syncDegiroMutation.mutate(undefined, rowCallbacks(formatGeneric))
        break
      case 'ibkr':
        syncIbkrMutation.mutate(undefined, rowCallbacks(formatGeneric))
        break
      case 'revolut':
        // Revolut's on-demand flow is discover → pick accounts → confirm, which lives in the
        // dedicated tab; SyncAll routes there rather than blind-importing everything.
        navigate('/sync?tab=revolut')
        onOpenChange(false)
        setSyncingIds(prev => {
          const next = new Set(prev)
          next.delete(connection.id)
          return next
        })
        break
      case 'finary':
        navigate('/sync?tab=finary')
        onOpenChange(false)
        clearSyncing()
        break
    }
  }, [
    trStatus?.isActive,
    revolutStatus?.remembered,
    retryBankMutation,
    syncExchangeMutation,
    syncWalletMutation,
    syncTrMutation,
    syncBoursoMutation,
    syncAmundiMutation,
    syncBourseDirectMutation,
    syncDegiroMutation,
    syncIbkrMutation,
    navigate,
    onOpenChange,
    queryClient,
    t,
  ])

  // "Sync all" only fires what a single click can actually complete: Finary is a manual
  // two-phase import, and any session needing re-authentication would just fail. Those rows
  // keep their own button, which opens the right form instead.
  const isBatchSyncable = useCallback((c: SyncConnection) =>
    c.providerType !== 'finary' &&
    !c.needsReauth &&
    !(c.providerType === 'tr' && !trStatus?.isActive) &&
    !(c.providerType === 'revolut' && !revolutStatus?.remembered)
  , [trStatus?.isActive, revolutStatus?.remembered])

  const handleSyncAll = useCallback(() => {
    connections
      .filter(isBatchSyncable)
      .forEach(connection => {
        if (!syncingIds.has(connection.id)) {
          handleSync(connection)
        }
      })
  }, [connections, syncingIds, handleSync, isBatchSyncable])

  // With only Finary rows and expired sessions in the list there is nothing for "Sync all" to
  // do, and an enabled button that quietly does nothing reads as a broken one.
  const hasBatchSyncable = connections.some(isBatchSyncable)

  const isSyncAll = syncingIds.size > 0 && connections
    .filter(isBatchSyncable)
    .every(c => syncingIds.has(c.id))

  // --- TR inline auth ---
  // Error-state semantics (see docs/features/trade-republic.md): an initiate
  // failure has no valid processId, so stay on the phone/PIN step and clear
  // pending state; a complete failure keeps the processId so the user can
  // retry the code without re-entering phone/PIN.
  function handleTrInitiate(e: React.FormEvent) {
    e.preventDefault()
    initiateTrMutation.mutate(
      { phoneNumber: trPhone, pin: trPin },
      {
        onSuccess: (data) => {
          setTrProcessId(data.processId)
          setTrAuthStep('tan')
          setTrAuthError(null)
        },
        onError: (err: unknown) => {
          setTrAuthError(formatTrAuthError(err, t))
          setTrProcessId(null)
          setTrTan('')
          setTrAuthStep('phone')
        },
      },
    )
  }

  function handleTrComplete(e: React.FormEvent) {
    e.preventDefault()
    if (!trProcessId || trTan.length !== TR_VERIFICATION_CODE_LENGTH) return
    completeTrMutation.mutate(
      { processId: trProcessId, tan: trTan },
      {
        onSuccess: () => {
          setTrAuthStep('idle')
          setTrPhone('')
          setTrPin('')
          setTrTan('')
          setTrProcessId(null)
          setTrAuthError(null)
          // Query invalidation (status, accounts, dashboard) is handled by
          // useCompleteTrAuth itself; the background sync results arrive via
          // the existing refetch intervals.
        },
        onError: (err: unknown) => {
          setTrAuthError(formatTrAuthError(err, t))
          setTrTan('')
        },
      },
    )
  }

  function resetTrAuth() {
    setTrAuthStep('idle')
    setTrPhone('')
    setTrPin('')
    setTrTan('')
    setTrProcessId(null)
    setTrAuthError(null)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="flex max-h-[calc(100dvh-2rem)] flex-col overflow-hidden sm:max-w-lg">
        <DialogHeader className="shrink-0">
          <DialogTitle>{t('sync.all.title')}</DialogTitle>
          <DialogDescription>
            {connections.length > 0
              ? t('sync.all.lastSync')
              : t('sync.all.noConnections')}
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="-mx-1 min-h-0 flex-1 space-y-3 overflow-y-auto px-1">
            {Array.from({ length: 3 }).map((_, i) => (
              <Card key={i} size="sm">
                <CardContent className="flex items-center justify-between py-3">
                  <div className="space-y-2">
                    <Skeleton className="h-4 w-32" />
                    <Skeleton className="h-3 w-24" />
                  </div>
                  <Skeleton className="size-8" />
                </CardContent>
              </Card>
            ))}
          </div>
        ) : connections.length === 0 ? (
          <EmptyState
            title={t('sync.all.noConnections')}
            icon={<RefreshCw className="size-12" />}
          />
        ) : (
          <div className="-mx-1 min-h-0 flex-1 space-y-2 overflow-y-auto px-1">
            {connections.map(connection => {
              const Icon = ProviderIcon[connection.providerType]
              const isSyncing = syncingIds.has(connection.id)
              const isFinary = connection.providerType === 'finary'
              const isTr = connection.providerType === 'tr'
              const isRevolut = connection.providerType === 'revolut'
              const revolutNeedsEnrolment = isRevolut && !revolutStatus?.remembered
              // Sends the user to the tab owning that provider's auth form rather than syncing.
              const opensTab = connection.needsReauth && REAUTH_TAB[connection.providerType] !== undefined

              return (
                <Card key={connection.id} size="sm">
                  <CardContent className="flex flex-col gap-0 py-3">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-3">
                        <Icon className="size-5 text-muted-foreground" />
                        <div className="space-y-1">
                          <div className="flex items-center gap-2">
                            <span className="text-sm font-medium">{connection.name}</span>
                            <Badge variant={statusVariant(connection.status)} className="text-xs">
                              {connection.status === 'SESSION_EXPIRED'
                                ? isTr
                                  ? t('sync.tr.noSession')
                                  : isRevolut
                                    ? t('sync.revolut.notConnected')
                                    : t('sync.all.sessionExpired')
                                : connection.status}
                            </Badge>
                            {isTr && (
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <Info className="size-3.5 text-muted-foreground cursor-help" />
                                </TooltipTrigger>
                                <TooltipContent side="top" className="max-w-xs text-xs">
                                  {t('sync.all.trManualInfo')}
                                </TooltipContent>
                              </Tooltip>
                            )}
                            {revolutNeedsEnrolment && (
                              <Tooltip>
                                <TooltipTrigger asChild>
                                  <Info className="size-3.5 text-muted-foreground cursor-help" />
                                </TooltipTrigger>
                                <TooltipContent side="top" className="max-w-xs text-xs">
                                  {t('sync.all.revolutManualInfo')}
                                </TooltipContent>
                              </Tooltip>
                            )}
                          </div>
                          <p className="text-xs text-muted-foreground">
                            {t('sync.all.lastSync')}: {formatTimeAgo(connection.lastSyncedAt)}
                          </p>
                          {rowErrors[connection.id] && (
                            <p className="flex items-center gap-1.5 text-xs text-destructive">
                              <AlertTriangle className="size-3 shrink-0" />
                              {rowErrors[connection.id]}
                            </p>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-1">
                        {connection.providerType === 'bank' && connection.status === 'FAILED' && connection.syncId !== undefined && (() => {
                          // Pending state scoped to THIS row — the mutation is shared,
                          // so isPending alone would spin every bank's button at once.
                          const isReconnecting =
                            reconnectBankMutation.isPending && reconnectBankMutation.variables === connection.syncId
                          return (
                            <Button
                              size="icon-sm"
                              variant="ghost"
                              title={t('sync.banks.reconnect')}
                              disabled={isReconnecting}
                              onClick={() => reconnectBankMutation.mutate(connection.syncId!, {
                                onSuccess: (data) => {
                                  if (data.authLink) window.location.href = data.authLink
                                },
                                onError: (err: unknown) => setRowErrors(prev => ({
                                  ...prev,
                                  [connection.id]: formatApiError(err, t, 'sync.banks.initiateError'),
                                })),
                              })}
                            >
                              {isReconnecting
                                ? <Loader2 className="size-4 animate-spin" />
                                : <ExternalLink className="size-4" />}
                            </Button>
                          )
                        })()}
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          disabled={isSyncing}
                          onClick={() => handleSync(connection)}
                          title={isFinary ? t('sync.all.openFinary') : revolutNeedsEnrolment ? t('sync.all.openRevolut') : opensTab ? t('sync.all.reconnect') : undefined}
                        >
                          {isSyncing ? (
                            <Loader2 className="size-4 animate-spin" />
                          ) : isFinary || revolutNeedsEnrolment || opensTab ? (
                            <ExternalLink className="size-4" />
                          ) : (
                            <RefreshCw className="size-4" />
                          )}
                        </Button>
                      </div>
                    </div>

                    {/* TR inline auth form */}
                    {isTr && trAuthStep !== 'idle' && !trStatus?.isActive && (
                      <div className="mt-3 border-t pt-3">
                        <p className="mb-3 text-xs text-muted-foreground">
                          {t('sync.all.trSlowWarning')}
                        </p>
                        {trAuthError && (
                          <p className="mb-3 flex items-center gap-2 text-xs text-destructive">
                            <AlertTriangle className="size-3.5 shrink-0" />
                            {trAuthError}
                          </p>
                        )}
                        {trAuthStep === 'phone' && (
                          <form onSubmit={handleTrInitiate} className="space-y-3">
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-phone">
                                <Smartphone className="size-3 inline-block mr-1" />
                                {t('sync.tr.phone')}
                              </Label>
                              <Input
                                id="tr-modal-phone"
                                type="tel"
                                value={trPhone}
                                onChange={e => setTrPhone(e.target.value)}
                                placeholder="+49..."
                                required
                              />
                            </div>
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-pin">
                                <Lock className="size-3 inline-block mr-1" />
                                {t('sync.tr.pin')}
                              </Label>
                              <Input
                                id="tr-modal-pin"
                                type="password"
                                value={trPin}
                                onChange={e => setTrPin(e.target.value)}
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={initiateTrMutation.isPending}>
                                {initiateTrMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.tr.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetTrAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                        {trAuthStep === 'tan' && (
                          <form onSubmit={handleTrComplete} className="space-y-3">
                            <div className="space-y-1">
                              <Label htmlFor="tr-modal-tan">
                                <ShieldCheck className="size-3 inline-block mr-1" />
                                {t('sync.tr.tan')}
                              </Label>
                              <Input
                                id="tr-modal-tan"
                                value={trTan}
                                onChange={e => setTrTan(e.target.value.replace(/\D/g, '').slice(0, TR_VERIFICATION_CODE_LENGTH))}
                                inputMode="numeric"
                                autoComplete="one-time-code"
                                maxLength={TR_VERIFICATION_CODE_LENGTH}
                                autoFocus
                                required
                              />
                            </div>
                            <div className="flex gap-2">
                              <Button type="submit" size="sm" disabled={completeTrMutation.isPending || trTan.length !== TR_VERIFICATION_CODE_LENGTH}>
                                {completeTrMutation.isPending && <Loader2 className="size-3 animate-spin" />}
                                {t('sync.tr.connect')}
                              </Button>
                              <Button type="button" size="sm" variant="outline" onClick={resetTrAuth}>
                                {t('common.cancel')}
                              </Button>
                            </div>
                          </form>
                        )}
                      </div>
                    )}
                  </CardContent>
                </Card>
              )
            })}
          </div>
        )}

        {connections.length > 0 && (
          <DialogFooter className="shrink-0">
            <Button
              onClick={handleSyncAll}
              disabled={isSyncAll || isLoading || !hasBatchSyncable}
            >
              {isSyncAll ? (
                <>
                  <Loader2 className="mr-2 size-4 animate-spin" />
                  {t('sync.all.syncing')}
                </>
              ) : (
                <>
                  <RefreshCw className="mr-2 size-4" />
                  {t('sync.all.syncAll')}
                </>
              )}
            </Button>
          </DialogFooter>
        )}
      </DialogContent>
    </Dialog>
  )
}
