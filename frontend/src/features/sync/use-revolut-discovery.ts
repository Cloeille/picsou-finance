import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { revolutApi } from '@/features/sync/api'
import { useStartRevolutSync, useSyncProgress } from '@/features/sync/hooks'
import { syncKeys } from '@/features/sync/keys'
import { revolutErrorLabel } from '@/features/sync/revolut-phase'
import { formatApiError, getErrorCode } from '@/lib/errors'
import type { SyncProgress } from '@/types/api'

const WEBVIEW_POLL_MS = 1_500

interface RevolutDiscoveryOptions {
  /** Runs when a new discovery is requested, before the job starts. */
  onStart: () => void
  /** Runs when a discovery finishes without error; `data` is the final progress snapshot. */
  onFinished: (data: SyncProgress | undefined) => void
}

/**
 * Drives a Revolut discovery job: start it, poll its progress, and detect the
 * running → done transition. What to do with a successful result is the caller's call.
 */
export function useRevolutDiscovery({ onStart, onFinished }: RevolutDiscoveryOptions) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  // Whether a discovery job is currently running (from the moment the 202 comes back
  // to the running → done transition detected below) — gates both the poll and the UI.
  const [isSyncing, setIsSyncing] = useState(false)

  const startSync = useStartRevolutSync()
  const progress = useSyncProgress('revolut', isSyncing)

  // Detect the running → done transition (mirrors CategorizeTab's AI-job pattern) to stop
  // polling and surface a discovery error.
  const prevRunningRef = useRef<boolean | undefined>(undefined)
  useEffect(() => {
    const running = progress.data?.running
    if (prevRunningRef.current === true && running === false) {
      setIsSyncing(false)
      if (progress.data?.error) {
        // Reacting to the running → done transition detected above, not deriving render state.
        // eslint-disable-next-line react-hooks/set-state-in-effect
        setErrorMsg(revolutErrorLabel(t, progress.data))
      } else {
        onFinished(progress.data)
      }
    }
    if (running !== undefined) {
      prevRunningRef.current = running
    }
    // progress.data/onFinished/t are read through the running dep on purpose
    // (see CategorizeTab) — callers hand in a fresh closure every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress.data?.running, isSyncing])

  function startDiscovery(body: { phoneNumber?: string; passcode?: string }) {
    setErrorMsg(null)
    onStart()
    startSync.mutate(body, {
      onSuccess: () => setIsSyncing(true),
      onError: (err: unknown) => setErrorMsg(formatApiError(err, t)),
    })
  }

  /** Attaches to a discovery job already started by the backend (e.g. Desktop WebView login). */
  function attachDiscovery() {
    setErrorMsg(null)
    onStart()
    queryClient.removeQueries({ queryKey: ['sync', 'revolut', 'progress'] })
    prevRunningRef.current = true
    setIsSyncing(true)
  }

  return {
    errorMsg,
    setErrorMsg,
    isSyncing,
    progress,
    startPending: startSync.isPending,
    startDiscovery,
    attachDiscovery,
  }
}

export type RevolutWebviewState = 'IDLE' | 'STARTING' | 'WAITING' | 'FETCHING'

interface RevolutWebviewOptions {
  onStart?: () => void
  onSyncing: () => void
  onError: (message: string) => void
}

function webviewErrorForCode(t: (key: string) => string, errorCode?: string): string | null {
  switch (errorCode) {
    case 'AUTH_ATTEMPT_EXPIRED':
      return t('sync.revolut.errors.authAttemptExpired')
    case 'SESSION_EXPIRED':
      return t('sync.revolut.errors.sessionExpired')
    case 'UPSTREAM_FORMAT_CHANGED':
      return t('sync.revolut.errors.upstreamFormatChanged')
    case 'HUMAN_VERIFICATION_REQUIRED':
      return t('sync.revolut.errors.humanVerificationRequired')
    case 'UPSTREAM_UNAVAILABLE':
      return t('sync.revolut.errors.serverError')
    default:
      return null
  }
}

function webviewErrorMessage(t: (key: string) => string, errorCode?: string): string {
  return webviewErrorForCode(t, errorCode) ?? t('sync.revolut.errors.serverError')
}

function formatWebviewError(t: (key: string) => string, err: unknown): string {
  return webviewErrorForCode(t, getErrorCode(err)) ?? formatApiError(err, t)
}

/**
 * Drives the Picsou Desktop native OS WebView login flow for Revolut:
 * opens the OS window on app.revolut.com, polls status until DONE/SYNCING, and hands off
 * smoothly to the standard Revolut discovery flow.
 */
export function useRevolutWebview({ onStart, onSyncing, onError }: RevolutWebviewOptions) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [webviewState, setWebviewState] = useState<RevolutWebviewState>('IDLE')
  const [processId, setProcessId] = useState<string | null>(null)
  const startingRef = useRef(false)

  useEffect(() => {
    if (!processId) return
    if (webviewState !== 'WAITING' && webviewState !== 'FETCHING') return
    let cancelled = false
    let inFlight = false
    const timer = setInterval(() => {
      if (inFlight) return
      inFlight = true
      void revolutApi
        .getWebviewStatus(processId)
        .then((res) => {
          if (cancelled) return
          if (res.status === 'WAITING_LOGIN') {
            setWebviewState('WAITING')
          } else if (res.status === 'FETCHING') {
            setWebviewState('FETCHING')
          } else if (res.status === 'SYNCING' || res.status === 'DONE') {
            cancelled = true
            clearInterval(timer)
            setProcessId(null)
            setWebviewState('IDLE')
            void queryClient.invalidateQueries({ queryKey: syncKeys.revolut() })
            onSyncing()
          } else if (res.status === 'CANCELLED') {
            cancelled = true
            clearInterval(timer)
            setProcessId(null)
            setWebviewState('IDLE')
          } else if (res.status === 'FAILED') {
            cancelled = true
            clearInterval(timer)
            setProcessId(null)
            setWebviewState('IDLE')
            void queryClient.invalidateQueries({ queryKey: syncKeys.revolut() })
            onError(webviewErrorMessage(t, res.errorCode))
          }
        })
        .catch((err) => {
          if (cancelled) return
          cancelled = true
          clearInterval(timer)
          setProcessId(null)
          setWebviewState('IDLE')
          void queryClient.invalidateQueries({ queryKey: syncKeys.revolut() })
          onError(formatWebviewError(t, err))
        })
        .finally(() => {
          inFlight = false
        })
    }, WEBVIEW_POLL_MS)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
    // onSyncing / onError are caller closures invoked once on terminal transition.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [processId, webviewState, queryClient, t])

  async function startWebview() {
    if (startingRef.current) return
    startingRef.current = true
    onStart?.()
    setWebviewState('STARTING')
    try {
      const result = await revolutApi.startWebview()
      if (!result.processId) {
        setWebviewState('IDLE')
        void queryClient.invalidateQueries({ queryKey: syncKeys.revolut() })
        onError(t('sync.revolut.errors.serverError'))
        return
      }
      setProcessId(result.processId)
      setWebviewState('WAITING')
    } catch (err) {
      setWebviewState('IDLE')
      void queryClient.invalidateQueries({ queryKey: syncKeys.revolut() })
      onError(formatWebviewError(t, err))
    } finally {
      startingRef.current = false
    }
  }

  function cancelWebview() {
    const pid = processId
    setProcessId(null)
    setWebviewState('IDLE')
    if (pid) {
      void revolutApi.cancelWebview(pid).catch(() => {
        // Best effort: window may already be closed.
      })
    }
  }

  return {
    webviewState,
    webviewInProgress: webviewState === 'WAITING' || webviewState === 'FETCHING',
    webviewStarting: webviewState === 'STARTING',
    startWebview,
    cancelWebview,
  }
}
