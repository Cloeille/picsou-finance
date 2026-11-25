/**
 * True inside the Picsou Desktop window. Tauri defines `window.isTauri` on every page its
 * WebView loads; a regular browser pointed at the local address does not have it.
 */
export function isInsideDesktopShell(): boolean {
  return typeof window !== 'undefined' && (window as Window & { isTauri?: boolean }).isTauri === true
}
