import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmDialog } from '@/components/shared/ConfirmDialog'
import { EmptyState } from '@/components/shared/EmptyState'
import { InstitutionSearch } from '@/components/sync/InstitutionSearch'
import { ReportConnectorIssueButton } from '@/components/sync/ReportConnectorIssueButton'
import {
  ArrowsClockwise,
  Trash,
  Bank,
  CircleNotch,
  CheckCircle,
  Warning,
  ArrowSquareOut,
  PiggyBank,
} from '@phosphor-icons/react'
import { extractTypedErrorCode } from '@/lib/connector-report'
import { extractErrorMessage, formatApiError } from '@/lib/errors'
import {
  useBankSyncStatus,
  useCompleteBankSync,
  useDeleteBankConnection,
  useInitiateBankSync,
  useReconnectBankSync,
  useRetryBankSync,
  useWiseStatus,
} from '@/features/sync/hooks'
import { useConnectorError } from '@/features/sync/use-connector-error'
import {
  buildEnableBankingCallbackPath,
  openDesktopEnableBankingAuth,
  shouldUseDesktopEnableBankingWindow,
} from '@/features/desktop/enable-banking'
import { useAppStore } from '@/stores/app-store'

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

// FAILED keeps a custom tint: the destructive variant measures about 3:1 in dark mode.
function statusClasses(status: string): string {
  switch (status) {
    case 'EXPIRED': return 'bg-muted text-muted-foreground'
    case 'FAILED': return 'bg-red-500/10 text-red-600 dark:text-red-400'
    default: return ''
  }
}

const WISE_INSTITUTION = /\bwise\b/i

/**
 * Enable Banking only returns Wise Jars when they are selected in its Control Panel, so a Wise
 * connection points members to the Wise Jars connector as an alternative, not a second source
 * for the same Jars, which would count them twice.
 * Only rendered under a Wise row, which keeps the status request off every other member's page.
 */
function WiseJarsHint({ onOpenWiseJars }: { onOpenWiseJars?: () => void }) {
  const { t } = useTranslation()
  const { data: wiseStatus } = useWiseStatus()

  if (wiseStatus?.connected && wiseStatus.status === 'CONNECTED') {
    return (
      <p className="flex items-center gap-2 px-1 text-sm text-muted-foreground">
        <PiggyBank className="size-4 shrink-0" />
        {t('sync.banks.wiseJarsConnected')}
      </p>
    )
  }
  const failed = wiseStatus?.connected ?? false
  return (
    <div className="flex flex-wrap items-center gap-2 px-1 text-xs text-muted-foreground">
      <PiggyBank className="size-4 shrink-0" />
      <span>{t(failed ? 'sync.banks.wiseJarsFailed' : 'sync.banks.wiseJarsMissing')}</span>
      {onOpenWiseJars && (
        <Button variant="outline" size="xs" onClick={onOpenWiseJars}>
          {t(failed ? 'sync.banks.wiseJarsOpen' : 'sync.banks.wiseJarsConnect')}
        </Button>
      )}
    </div>
  )
}

interface BankSyncTabProps {
  onOpenWiseJars?: () => void
}

export function BankSyncTab({ onOpenWiseJars }: BankSyncTabProps = {}) {
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const desktopMode = useAppStore((s) => s.desktopMode)

  const [deleteId, setDeleteId] = useState<number | null>(null)
  const [callbackStatus, setCallbackStatus] = useState<CallbackStatus | null>(null)
  const callbackErr = useConnectorError()
  const initiateErr = useConnectorError()
  const retryErr = useConnectorError()
  const [retryingIds, setRetryingIds] = useState<Set<number>>(() => new Set())
  const [authWindowOpen, setAuthWindowOpen] = useState(false)
  const [reconnectingWindowId, setReconnectingWindowId] = useState<number | null>(null)
  const handledCode = useRef<string | null>(null)
  const handledError = useRef<string | null>(null)

  const { mutate: completeSync } = useCompleteBankSync()
  const { setError: setCallbackError } = callbackErr

  const cleanBankCallbackUrl = useCallback(() => {
    if (location.pathname.endsWith('/sync/callback')) {
      navigate('/sync?tab=banks', { replace: true })
    } else if (window.location.pathname.endsWith('/sync/callback')) {
      window.history.replaceState(window.history.state, '', '/sync?tab=banks')
    }
  }, [location.pathname, navigate])

  const completeCallback = useCallback((code: string, state: string | null) => {
    completeSync({ code, state }, {
      onSuccess: () => {
        setCallbackStatus('done')
        setCallbackError(null)
        cleanBankCallbackUrl()
      },
      onError: (err: unknown) => {
        setCallbackStatus('error')
        setCallbackError(extractErrorMessage(err), extractTypedErrorCode(err))
      },
    })
  }, [completeSync, setCallbackError, cleanBankCallbackUrl])

  useEffect(() => {
    const code = searchParams.get('code')
    if (code && code !== handledCode.current) {
      handledCode.current = code
      setCallbackStatus('completing')
      completeCallback(code, searchParams.get('state'))
      return
    }
    const oauthError = searchParams.get('error')
    if (!oauthError) {
      handledError.current = null
      return
    }
    if (oauthError !== handledError.current) {
      handledError.current = oauthError
      const detail = searchParams.get('error_description') || oauthError
      setCallbackStatus('error')
      setCallbackError(detail, null)
      cleanBankCallbackUrl()
    }
  }, [searchParams, completeCallback, setCallbackError, cleanBankCallbackUrl])

  const { data: connections, isLoading: connectionsLoading } = useBankSyncStatus()
  const initiateMutation = useInitiateBankSync()
  const retryMutation = useRetryBankSync()

  async function openAuthLink(
    authLink: string,
    onError: (err: unknown) => void,
    reconnectId?: number,
  ) {
    if (!shouldUseDesktopEnableBankingWindow(desktopMode)) {
      window.location.href = authLink
      return
    }
    setAuthWindowOpen(true)
    if (reconnectId !== undefined) setReconnectingWindowId(reconnectId)
    try {
      const outcome = await openDesktopEnableBankingAuth(authLink)
      if (outcome.status === 'callback') {
        navigate(buildEnableBankingCallbackPath(outcome.query), { replace: true })
      }
    } catch (err: unknown) {
      onError(err)
    } finally {
      setAuthWindowOpen(false)
      setReconnectingWindowId(null)
    }
  }

  function handleInitiate(institutionId: string, institutionName: string) {
    initiateMutation.mutate({ institutionId, institutionName }, {
      onSuccess: (data) => {
        initiateErr.setError(null)
        void openAuthLink(data.authLink, (err) => {
          initiateErr.setError(extractErrorMessage(err, t('sync.banks.initiateError')), extractTypedErrorCode(err))
        })
      },
      onError: (err: unknown) => {
        initiateErr.setError(extractErrorMessage(err, t('sync.banks.initiateError')), extractTypedErrorCode(err))
      },
    })
  }

  async function handleRetry(id: number) {
    setRetryingIds((previous) => new Set(previous).add(id))
    try {
      await retryMutation.mutateAsync(id, {
        onSuccess: () => retryErr.setError(null),
        onError: (err: unknown) => {
          retryErr.setError(formatApiError(err, t, 'sync.banks.callbackError'), extractTypedErrorCode(err))
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
        retryErr.setError(null)
        if (data.authLink) {
          void openAuthLink(
            data.authLink,
            (err) => {
              retryErr.setError(formatApiError(err, t, 'sync.banks.initiateError'), extractTypedErrorCode(err))
            },
            id,
          )
        }
      },
      onError: (err: unknown) => {
        retryErr.setError(formatApiError(err, t, 'sync.banks.initiateError'), extractTypedErrorCode(err))
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
          callbackStatus === 'done' ? 'border-emerald-500/30 bg-emerald-500/5' :
          callbackStatus === 'error' ? 'border-destructive/30 bg-destructive/5' :
          'border-border/60 bg-muted/40'
        }>
          <CardContent className="flex flex-wrap items-center gap-3">
            {callbackStatus === 'completing' && (
              <CircleNotch className="size-4 animate-spin text-primary" />
            )}
            {callbackStatus === 'done' && (
              <CheckCircle className="size-4 text-emerald-600 dark:text-emerald-400" />
            )}
            {callbackStatus === 'error' && (
              <Warning className="size-4 text-destructive" />
            )}
            <span className="min-w-0 flex-1 text-sm font-medium">
              {callbackStatus === 'completing' && t('sync.banks.callbackCompleting')}
              {callbackStatus === 'done' && t('sync.banks.callbackDone')}
              {callbackStatus === 'error' && `${t('sync.banks.callbackError')}${callbackErr.error ? `: ${callbackErr.error}` : ''}`}
            </span>
            {callbackStatus === 'error' && (
              <ReportConnectorIssueButton connectorId="enable-banking" errorCode={callbackErr.errorCode} />
            )}
          </CardContent>
        </Card>
      )}

      {/* Initiate error */}
      {initiateErr.error && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="flex flex-wrap items-center gap-3">
            <Warning className="size-4 shrink-0 text-destructive" />
            <span className="min-w-0 flex-1 text-sm font-medium text-destructive">{initiateErr.error}</span>
            <ReportConnectorIssueButton connectorId="enable-banking" errorCode={initiateErr.errorCode} />
            <Button variant="outline" size="sm" onClick={() => initiateErr.setError(null)}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}

      {/* Retry error */}
      {retryErr.error && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="flex flex-wrap items-center gap-3">
            <Warning className="size-4 shrink-0 text-destructive" />
            <span className="min-w-0 flex-1 text-sm font-medium text-destructive">{retryErr.error}</span>
            <ReportConnectorIssueButton connectorId="enable-banking" errorCode={retryErr.errorCode} />
            <Button variant="outline" size="sm" onClick={() => retryErr.setError(null)}>
              {t('common.close')}
            </Button>
          </CardContent>
        </Card>
      )}

      <InstitutionSearch
        layout="page"
        onConnect={handleInitiate}
        connecting={initiateMutation.isPending || authWindowOpen}
      />

      {/* Active connections */}
      <div className="space-y-3">
        <h3 className="text-sm font-medium">{t('sync.banks.connected')}</h3>

        {connectionsLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}

        {!connectionsLoading && (!connections || connections.length === 0) && (
          <Card>
            <CardContent>
              <EmptyState
                className="py-8"
                title={t('sync.banks.noConnections')}
                icon={<Bank className="size-10" />}
              />
            </CardContent>
          </Card>
        )}

        {!connectionsLoading && connections && connections.length > 0 && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              {t('sync.banks.psd2ScopeNote')}
            </p>
            {connections.map(conn => {
              const isRetrying = retryingIds.has(conn.id)
              const isReconnecting =
                (reconnectMutation.isPending && reconnectMutation.variables === conn.id) ||
                reconnectingWindowId === conn.id

              return <div key={conn.id} className="space-y-2">
                <Card size="sm">
                  <CardContent className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                      <span className="text-sm font-medium">{conn.institutionName}</span>
                      <Badge
                        variant={statusVariant(conn.status)}
                        className={statusClasses(conn.status)}
                      >
                        {t(`sync.connectionStatus.${conn.status}`, { defaultValue: conn.status })}
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
                              ? <CircleNotch className="size-4 animate-spin" />
                              : <ArrowsClockwise className="size-4" />}
                          </Button>
                          <Button
                            size="icon-sm"
                            variant="ghost"
                            title={t('sync.banks.reconnect')}
                            onClick={() => handleReconnect(conn.id)}
                            disabled={isRetrying || isReconnecting}
                          >
                            {isReconnecting
                              ? <CircleNotch className="size-4 animate-spin" />
                              : <ArrowSquareOut className="size-4" />}
                          </Button>
                          <ReportConnectorIssueButton
                            connectorId="enable-banking"
                            errorCode="SYNC_FAILED"
                            iconOnly
                          />
                        </>
                      )}
                      <Button
                        size="icon-sm"
                        variant="ghost"
                        onClick={() => setDeleteId(conn.id)}
                      >
                        <Trash className="size-4 text-destructive" />
                      </Button>
                    </div>
                  </CardContent>
                </Card>
                {WISE_INSTITUTION.test(conn.institutionName) && <WiseJarsHint onOpenWiseJars={onOpenWiseJars} />}
              </div>
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
