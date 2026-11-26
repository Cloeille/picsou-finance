import { isInsideDesktopShell } from './shell'

/**
 * Fixed HTTPS callback URL suggested on Picsou Desktop (#590).
 * Enable Banking Production applications require HTTPS redirect URLs; Picsou Desktop never loads
 * this URL over the network because its dedicated Enable Banking WebView intercepts any
 * `.../sync/callback` navigation before a connection is made.
 */
export const DESKTOP_ENABLE_BANKING_REDIRECT_URI = 'https://localhost/sync/callback'

export type DesktopEnableBankingAuthResult =
  | { status: 'callback'; query: string }
  | { status: 'cancelled' }

interface TauriCoreInvoke {
  invoke?: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>
}

function getTauriInvoke(): TauriCoreInvoke['invoke'] {
  if (typeof window === 'undefined') return undefined
  return (window as Window & { __TAURI__?: { core?: TauriCoreInvoke } }).__TAURI__?.core?.invoke
}

export function isDesktopEnableBankingMode(desktopMode: boolean): boolean {
  return desktopMode || isInsideDesktopShell()
}

export function defaultEnableBankingRedirectUri(desktopMode: boolean): string {
  if (isDesktopEnableBankingMode(desktopMode)) {
    return DESKTOP_ENABLE_BANKING_REDIRECT_URI
  }
  return typeof window !== 'undefined' ? `${window.location.origin}/sync/callback` : ''
}

export function hasDesktopEnableBankingBridge(): boolean {
  return typeof getTauriInvoke() === 'function'
}

export function shouldUseDesktopEnableBankingWindow(desktopMode: boolean): boolean {
  return isDesktopEnableBankingMode(desktopMode) && hasDesktopEnableBankingBridge()
}

export function buildEnableBankingCallbackPath(query: string): string {
  const trimmed = query.replace(/^\?/, '')
  return trimmed ? `/sync/callback?${trimmed}` : '/sync/callback'
}

export async function openDesktopEnableBankingAuth(
  authLink: string,
): Promise<DesktopEnableBankingAuthResult> {
  const invoke = getTauriInvoke()
  if (!invoke) {
    throw new Error('Desktop Enable Banking bridge is unavailable.')
  }
  return invoke<DesktopEnableBankingAuthResult>('open_enable_banking_auth', { authLink })
}
