import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useStartRevolutSync, useSyncProgress } from '@/features/sync/hooks'
import { revolutErrorLabel } from '@/features/sync/revolut-phase'
import { formatApiError } from '@/lib/errors'
import type { SyncProgress } from '@/types/api'

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
    prevRunningRef.current = running
    // progress.data/onFinished/t are read through the running dep on purpose
    // (see CategorizeTab) — callers hand in a fresh closure every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [progress.data?.running])

  function startDiscovery(body: { phoneNumber?: string; passcode?: string }) {
    setErrorMsg(null)
    onStart()
    startSync.mutate(body, {
      onSuccess: () => setIsSyncing(true),
      onError: (err: unknown) => setErrorMsg(formatApiError(err, t)),
    })
  }

  return {
    errorMsg,
    setErrorMsg,
    isSyncing,
    progress,
    startPending: startSync.isPending,
    startDiscovery,
  }
}
