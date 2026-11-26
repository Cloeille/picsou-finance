import { useEffect } from 'react'
import { create } from 'zustand'
import {
  checkDesktopUpdate,
  getDesktopUpdateStatus,
  hasDesktopUpdaterBridge,
  installDesktopUpdate,
  listenDesktopUpdateStatus,
  type DesktopUpdateStatus,
} from '@/features/desktop/updater'

interface DesktopUpdateState {
  initialized: boolean
  enabled: boolean
  currentVersion: string | null
  availableVersion: string | null
  checked: boolean
  checking: boolean
  installing: boolean
  error: string | null
  applyStatus: (status: DesktopUpdateStatus) => void
  syncInitialStatus: () => Promise<void>
  checkForUpdates: () => Promise<void>
  installUpdate: () => Promise<void>
  reset: () => void
}

const INITIAL_STATE = {
  initialized: false,
  enabled: false,
  currentVersion: null as string | null,
  availableVersion: null as string | null,
  checked: false,
  checking: false,
  installing: false,
  error: null as string | null,
}

let initialSyncInFlight: Promise<void> | null = null
let statusGeneration = 0
let resetEpoch = 0

let bridgeRefCount = 0
let bridgeSessionId = 0
let bridgeUnlisten: (() => unknown) | null = null

function safeUnlisten(fn: (() => unknown) | null): void {
  if (!fn) return
  try {
    void Promise.resolve(fn()).catch(() => undefined)
  } catch {
    // Ignore cleanup errors from an already torn-down listener.
  }
}

export const useDesktopUpdateStore = create<DesktopUpdateState>()((set, get) => ({
  ...INITIAL_STATE,
  applyStatus: (status) => {
    statusGeneration += 1
    set({
      initialized: true,
      enabled: status.enabled,
      currentVersion: status.currentVersion,
      availableVersion: status.enabled ? status.availableVersion : null,
      checked: status.enabled ? status.checked : false,
      installing: status.enabled ? status.installing : false,
      error: status.enabled ? status.error : null,
    })
  },
  syncInitialStatus: async () => {
    if (!hasDesktopUpdaterBridge()) {
      statusGeneration += 1
      set({ ...INITIAL_STATE, initialized: true })
      return
    }
    if (initialSyncInFlight) return initialSyncInFlight
    const startGeneration = statusGeneration
    let promise: Promise<void> | null = null
    promise = (async () => {
      try {
        const status = await getDesktopUpdateStatus()
        if (statusGeneration !== startGeneration) return
        if (status) {
          get().applyStatus(status)
        } else {
          statusGeneration += 1
          set({ ...INITIAL_STATE, initialized: true })
        }
      } catch {
        if (statusGeneration !== startGeneration) return
        statusGeneration += 1
        set({ ...INITIAL_STATE, initialized: true })
      } finally {
        if (initialSyncInFlight === promise) {
          initialSyncInFlight = null
        }
      }
    })()
    initialSyncInFlight = promise
    return promise
  },
  checkForUpdates: async () => {
    if (!hasDesktopUpdaterBridge() || get().checking || get().installing) return
    const epoch = resetEpoch
    const startGeneration = statusGeneration
    set({ checking: true, error: null })
    try {
      const status = await checkDesktopUpdate()
      if (statusGeneration !== startGeneration) return
      if (status) {
        get().applyStatus(status)
      }
    } catch (err: unknown) {
      if (statusGeneration !== startGeneration) return
      const message = err instanceof Error ? err.message : String(err)
      set({ error: message || 'check_failed' })
    } finally {
      if (epoch === resetEpoch) {
        set({ checking: false })
      }
    }
  },
  installUpdate: async () => {
    if (!hasDesktopUpdaterBridge() || get().installing || get().checking) return
    statusGeneration += 1
    const installGeneration = statusGeneration
    set({ installing: true, error: null })
    try {
      await installDesktopUpdate()
    } catch {
      if (statusGeneration === installGeneration) {
        set({ installing: false })
      }
    }
  },
  reset: () => {
    initialSyncInFlight = null
    statusGeneration += 1
    resetEpoch += 1
    bridgeSessionId += 1
    bridgeRefCount = 0
    const unlisten = bridgeUnlisten
    bridgeUnlisten = null
    safeUnlisten(unlisten)
    set({ ...INITIAL_STATE })
  },
}))

function isBridgeSessionActive(sessionId: number): boolean {
  return sessionId === bridgeSessionId && bridgeRefCount > 0
}

function acquireDesktopUpdateBridge(): () => void {
  bridgeRefCount += 1
  if (bridgeRefCount === 1) {
    const sessionId = ++bridgeSessionId
    let receivedLiveEventInSession = false

    void (async () => {
      try {
        const unlisten = await listenDesktopUpdateStatus((status) => {
          if (!isBridgeSessionActive(sessionId)) return
          receivedLiveEventInSession = true
          useDesktopUpdateStore.getState().applyStatus(status)
        })
        if (!isBridgeSessionActive(sessionId)) {
          safeUnlisten(unlisten)
          return
        }
        bridgeUnlisten = unlisten
      } catch {
        if (!isBridgeSessionActive(sessionId)) return
      }

      if (isBridgeSessionActive(sessionId) && !receivedLiveEventInSession) {
        await useDesktopUpdateStore.getState().syncInitialStatus()
      }
    })()
  }

  let released = false
  return () => {
    if (released) return
    released = true
    if (bridgeRefCount > 0) {
      bridgeRefCount -= 1
    }
    if (bridgeRefCount === 0) {
      bridgeSessionId += 1
      statusGeneration += 1
      initialSyncInFlight = null
      const unlisten = bridgeUnlisten
      bridgeUnlisten = null
      safeUnlisten(unlisten)
    }
  }
}

/**
 * Hydrates desktop updater status from the Tauri shell and listens for live update events
 * emitted by the background watcher. Uses a single ref-counted module-level subscription
 * shared across callers. No-op outside the desktop WebView.
 */
export function useDesktopUpdateBridge(): void {
  useEffect(() => {
    if (!hasDesktopUpdaterBridge()) return
    return acquireDesktopUpdateBridge()
  }, [])
}
