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

export const useDesktopUpdateStore = create<DesktopUpdateState>()((set, get) => ({
  ...INITIAL_STATE,
  applyStatus: (status) =>
    set({
      initialized: true,
      enabled: status.enabled,
      currentVersion: status.currentVersion,
      availableVersion: status.enabled ? status.availableVersion : null,
      checked: status.enabled ? status.checked : false,
      installing: status.enabled ? status.installing : false,
      error: status.enabled ? status.error : null,
    }),
  syncInitialStatus: async () => {
    if (!hasDesktopUpdaterBridge()) {
      set({ ...INITIAL_STATE, initialized: true })
      return
    }
    if (initialSyncInFlight) return initialSyncInFlight
    initialSyncInFlight = (async () => {
      try {
        const status = await getDesktopUpdateStatus()
        if (status) {
          get().applyStatus(status)
        } else {
          set({ ...INITIAL_STATE, initialized: true })
        }
      } catch {
        set({ ...INITIAL_STATE, initialized: true })
      } finally {
        initialSyncInFlight = null
      }
    })()
    return initialSyncInFlight
  },
  checkForUpdates: async () => {
    if (!hasDesktopUpdaterBridge() || get().checking) return
    set({ checking: true, error: null })
    try {
      const status = await checkDesktopUpdate()
      if (status) {
        get().applyStatus(status)
      }
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err)
      set({ error: message || 'check_failed' })
    } finally {
      set({ checking: false })
    }
  },
  installUpdate: async () => {
    if (!hasDesktopUpdaterBridge() || get().installing) return
    set({ installing: true, error: null })
    try {
      await installDesktopUpdate()
    } catch {
      set({ installing: false })
    }
  },
  reset: () => {
    initialSyncInFlight = null
    set({ ...INITIAL_STATE })
  },
}))

/**
 * Hydrates desktop updater status from the Tauri shell and listens for live update events
 * emitted by the background watcher. No-op outside the desktop WebView.
 */
export function useDesktopUpdateBridge(): void {
  const applyStatus = useDesktopUpdateStore((s) => s.applyStatus)
  const syncInitialStatus = useDesktopUpdateStore((s) => s.syncInitialStatus)

  useEffect(() => {
    if (!hasDesktopUpdaterBridge()) return
    let active = true
    let unlisten: (() => void) | null = null

    if (!useDesktopUpdateStore.getState().initialized) {
      void syncInitialStatus()
    }

    void listenDesktopUpdateStatus((status) => {
      if (active) applyStatus(status)
    })
      .then((fn) => {
        if (active) unlisten = fn
        else fn()
      })
      .catch(() => undefined)

    return () => {
      active = false
      unlisten?.()
    }
  }, [applyStatus, syncInitialStatus])
}
