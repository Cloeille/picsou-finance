export interface DesktopUpdateStatus {
  enabled: boolean
  currentVersion: string
  availableVersion: string | null
  checked: boolean
  installing: boolean
  error: string | null
}

export const DESKTOP_UPDATE_STATUS_EVENT = 'desktop-update-status'

interface TauriGlobal {
  core?: {
    invoke?: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>
  }
  event?: {
    listen?: <T>(
      event: string,
      handler: (event: { payload: T }) => void,
    ) => Promise<() => void>
  }
}

function getTauriGlobal(): TauriGlobal | undefined {
  if (typeof window === 'undefined') return undefined
  return (window as Window & { __TAURI__?: TauriGlobal }).__TAURI__
}

export function hasDesktopUpdaterBridge(): boolean {
  return typeof getTauriGlobal()?.core?.invoke === 'function'
}

export async function getDesktopUpdateStatus(): Promise<DesktopUpdateStatus | null> {
  const invoke = getTauriGlobal()?.core?.invoke
  if (!invoke) return null
  return invoke<DesktopUpdateStatus>('get_desktop_update_status')
}

export async function checkDesktopUpdate(): Promise<DesktopUpdateStatus | null> {
  const invoke = getTauriGlobal()?.core?.invoke
  if (!invoke) return null
  return invoke<DesktopUpdateStatus>('check_desktop_update')
}

export async function installDesktopUpdate(): Promise<void> {
  const invoke = getTauriGlobal()?.core?.invoke
  if (!invoke) {
    throw new Error('Desktop updater bridge is unavailable.')
  }
  await invoke<void>('install_desktop_update')
}

export async function listenDesktopUpdateStatus(
  handler: (status: DesktopUpdateStatus) => void,
): Promise<() => void> {
  const listen = getTauriGlobal()?.event?.listen
  if (!listen) return () => undefined
  return listen<DesktopUpdateStatus>(DESKTOP_UPDATE_STATUS_EVENT, (event) => {
    handler(event.payload)
  })
}
