import { useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { BankCountrySelect, DEFAULT_BANK_COUNTRY } from '@/components/shared/BankCountrySelect'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import {
  Search,
  RefreshCw,
  Trash2,
  Landmark,
  Loader2,
  CheckCircle,
  AlertTriangle,
  ExternalLink,
} from 'lucide-react'
import { extractErrorMessage, formatApiError } from '@/lib/errors'
import {
  useBankSyncStatus,
  useCompleteBankSync,
  useDeleteBankConnection,
  useInitiateBankSync,
  useReconnectBankSync,
  useRetryBankSync,
  useSearchInstitutions,
} from '@/features/sync/hooks'

type CallbackStatus = 'completing' | 'done' | 'error'

function statusVariant(status: string): 'default' | 'secondary' | 'destructive' | 'outline' {
  switch (status) {
    case 'LINKED': return 'default'
    case 'CREATED': return 'secondary'
    case 'EXPIRED': return 'outline'
    case 'FAILED': return 'destructive'
    default: return 'outline'
  }
}

function statusClasses(status: string): string {
  switch (status) {
    case 'LINKED': return 'bg-green-500/10 text-green-600 dark:text-green-400'
    case 'CREATED': return 'bg-amber-500/10 text-amber-600 dark:text-amber-400'
    case 'EXPIRED': return 'bg-muted text-muted-foreground'
    case 'FAILED': return 'bg-red-500/10 text-red-600 dark:text-red-400'
    default: return ''
  }
}

function cleanBankCallbackUrl() {
  if (window.location.pathname.endsWith('/sync/callback')) {
    window.history.replaceState(window.history.state, '', '/sync?tab=banks')
  }
}

export function BankSyncTab() {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()

  const [searchQuery, setSearchQuery] = useState('')
  const [country, setCountry] = useState(DEFAULT_BANK_COUNTRY)
  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [callbackStatus, setCallbackStatus] = useState<CallbackStatus | null>(null)
  const [callbackError, setCallbackError] = useState<string | null>(null)
  const [initiateError, setInitiateError] = useState<string | null>(null)
  const [retryError, setRetryError] = useState<string | null>(null)
  const [retryingIds, setRetryingIds] = useState<Set<number>>(() => new Set())
  const handledCode = useRef<string | null>(null)

  const { mutate: completeSync } = useCompleteBankSync()

  const completeCallback = useCallback((code: string, state: string | null) => {
    completeSync({ code, state }, {
      onSuccess: () => {
        setCallbackStatus('done')
        cleanBankCallbackUrl()
      },
      onError: (err: unknown) => {
        setCallbackStatus('error')
        setCallbackError(extractErrorMessage(err))
      },
    })
  }, [completeSync])

  useEffect(() => {
    const code = searchParams.get('code')
    if (code && code !== handledCode.current) {
      handledCode.current = code
      setCallbackStatus('completing')
      completeCallback(code, searchParams.get('state'))
    }
  }, [searchParams, completeCallback])

  const searchEnabled = searchQuery.trim().length >= 2

  const {
    data: institutions,
    isError: searchFailed,
    isLoading: searchLoading,
    error: searchError,
  } = useSearchInstitutions(searchQuery.trim(), country)
  const { data: connections, isLoading: connectionsLoading } = useBankSyncStatus()
  const initiateMutation = useInitiateBankSync()
  const retryMutation = useRetryBankSync()

  function handleInitiate(institutionId: string, institutionName: string) {
    initiateMutation.mutate({ institutionId, institutionName }, {
      onSuccess: (data) => {
        setInitiateError(null)
        window.location.href = data.authLink
      },
      onError: (err: unknown) => {
        setInitiateError(extractErrorMessage(err, t('sync.banks.initiateError')))
      },
    })
  }

  async function handleRetry(id: number) {
    setRetryingIds((previous) => new Set(previous).add(id))
    try {
      await retryMutation.mutateAsync(id, {
        onSuccess: () => setRetryError(null),
        onError: (err: unknown) => {
          setRetryError(formatApiError(err, t, 'sync.banks.callbackError'))
        },
      })
    } catch {
      // The mutation's onError handler renders the translated failure banner.
    } finally {
      setRetryingIds((previous) => {
        const next = new Set(previous)
        next.delete(id)
        return next
      })
    }
  }

  // Re-initiates the OAuth flow for a dead requisition (failed code exchange,
  // expired PSD2 consent) — a plain retry can never fix those.
  const reconnectMutation = useReconnectBankSync()

  function handleReconnect(id: number) {
    reconnectMutation.mutate(id, {
      onSuccess: (data) => {
        setRetryError(null)
        if (data.authLink) window.location.href = data.authLink
      },
      onError: (err: unknown) => {
        setRetryError(formatApiError(err, t, 'sync.banks.initiateError'))
      },
    })
  }

  const deleteMutation = useDeleteBankConnection()

  function handleDelete() {
    if (deleteId !== null) {
      deleteMutation.mutate(deleteId, { onSuccess: () => setDeleteId(null) })
    }
  }

  const connectionToBeDeleted = connections?.find(c => c.id === deleteId)

  return (
    <div className="space-y-6">
      {/* OAuth callback status */}
      {callbackStatus && (
        <Card className={
          callbackStatus === 'done' ? 'border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-950' :
          callbackStatus === 'error' ? 'border-destructive/30 bg-destructive/5' :
          'border-blue-200 bg-blue-50 dark:border-blue-800 dark:bg-blue-950'
        }>
          <CardContent className="flex items-center gap-3 py-3">
            {callbackStatus === 'completing' && (
              <Loader2 className="size-4 animate-spin text-blue-600 dark:text-blue-400" />
            )}
            {callbackStatus === 'done' && (
              <CheckCircle className="size-4 text-green-600 dark:text-green-400" />
            )}
            {callbackStatus === 'error' && (
              <AlertTriangle className="size-4 text-destructive" />
            )}
            <span className="text-sm font-medium">
              {callbackStatus === 'completing' && t('sync.banks.callbackCompleting')}
              {callbackStatus === 'done' && t('sync.banks.callbackDone')}
              {callbackStatus === 'error' && `${t('sync.banks.callbackError')}${callbackError ? `: ${callbackError}` : ''}`}
            </span>
          </CardContent>
        </Card>
      )}

      {/* Initiate error */}
      {initiateError && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="flex items-center gap-3 py-3">
            <AlertTriangle className="size-4 shrink-0 text-destructive" />
            <span className="flex-1 text-sm font-medium text-destructive">{initiateError}</span>
            <Button variant="ghost" size="sm" onClick={() => setInitiateError(null)}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Retry error */}
      {retryError && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="flex items-center gap-3 py-3">
            <AlertTriangle className="size-4 shrink-0 text-destructive" />
            <span className="flex-1 text-sm font-medium text-destructive">{retryError}</span>
            <Button variant="ghost" size="sm" onClick={() => setRetryError(null)}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Search section */}
      <div className="space-y-3">
        <label className="text-sm font-medium">{t('sync.banks.search')}</label>
        <div className="flex items-start gap-2">
          <div className="relative flex-1">
            <Search
              className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground"
            />
            <Input
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder={t('sync.banks.searchPlaceholder')}
              className="pl-10"
            />
          </div>
          <BankCountrySelect value={country} onChange={setCountry} />
        </div>

        {/* Search results */}
        {searchLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

        {searchEnabled && searchFailed && !searchLoading && (
          <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <span className="flex-1">{formatApiError(searchError, t, 'sync.banks.searchError')}</span>
          </div>
        )}

        {searchEnabled && !searchFailed && institutions && institutions.length > 0 && (
          <div className="space-y-2">
            {institutions.map(inst => (
              <Card key={inst.id} size="sm">
                <CardContent className="grid grid-cols-[minmax(0,1fr)_2.5rem_auto] items-center gap-4 py-2">
                  <div className="flex min-w-0 items-center gap-3">
                    <Landmark className="size-5 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 text-sm font-medium leading-5">{inst.name}</span>
                    {inst.psuType === 'business' && (
                      <Badge variant="outline" title={t('sync.banks.proBadgeTitle')}>
                        {t('sync.banks.proBadge')}
                      </Badge>
                    )}
                  </div>
                  <span className="justify-self-center text-xs text-muted-foreground">{inst.country}</span>
                  <Button
                    size="sm"
                    className="justify-self-end"
                    onClick={() =>
                      handleInitiate(inst.id, inst.name)
                    }
                    disabled={initiateMutation.isPending}
                  >
                    {t('sync.banks.connect')}
                  </Button>
                </CardContent>
              </Card>
            ))}
          </div>
        )}

        {searchEnabled && !searchFailed && institutions && institutions.length === 0 && !searchLoading && (
          <p className="text-sm text-muted-foreground">{t('sync.banks.noConnections')}</p>
        )}
      </div>

      {/* Active connections */}
      <div className="space-y-3">
        <h3 className="text-sm font-medium">{t('sync.banks.connected')}</h3>

        {connectionsLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

        {!connectionsLoading && (!connections || connections.length === 0) && (
          <EmptyState
            title={t('sync.banks.noConnections')}
            icon={<Landmark className="size-12" />}
          />
        )}

        {!connectionsLoading && connections && connections.length > 0 && (
          <div className="space-y-3">
            <p className="text-xs text-muted-foreground">
              {t('sync.banks.psd2ScopeNote')}
            </p>
            {connections.map(conn => {
              const isRetrying = retryingIds.has(conn.id)
              const isReconnecting =
                reconnectMutation.isPending && reconnectMutation.variables === conn.id

              return <Card key={conn.id} size="sm">
                <CardContent className="flex items-center justify-between py-2">
                  <div className="flex items-center gap-3">
                    <span className="text-sm font-medium">{conn.institutionName}</span>
                    <Badge
                      variant={statusVariant(conn.status)}
                      className={statusClasses(conn.status)}
                    >
                      {conn.status}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-2">
                    {conn.status === 'FAILED' && (
                      <>
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          title={t('sync.banks.retry')}
                          onClick={() => void handleRetry(conn.id)}
                          disabled={isRetrying || isReconnecting}
                        >
                          {isRetrying
                            ? <Loader2 className="size-4 animate-spin" />
                            : <RefreshCw className="size-4" />}
                        </Button>
                        <Button
                          size="icon-sm"
                          variant="ghost"
                          title={t('sync.banks.reconnect')}
                          onClick={() => handleReconnect(conn.id)}
                          disabled={isRetrying || isReconnecting}
                        >
                          {isReconnecting
                            ? <Loader2 className="size-4 animate-spin" />
                            : <ExternalLink className="size-4" />}
                        </Button>
                      </>
                    )}
                    <Button
                      size="icon-sm"
                      variant="ghost"
                      onClick={() => setDeleteId(conn.id)}
                    >
                      <Trash2 className="size-4 text-destructive" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            })}
          </div>
        )}
      </div>

      {/* Delete confirmation */}
      <ConfirmDialog
        open={deleteId !== null}
        onOpenChange={(open) => !open && setDeleteId(null)}
        title={t('sync.banks.delete')}
        description={connectionToBeDeleted ? t('sync.banks.deleteConfirm') : ''}
        onConfirm={handleDelete}
        loading={deleteMutation.isPending}
      />
    </div>
  )
}
