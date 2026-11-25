import { lazy, type ComponentType } from 'react'

const RELOAD_KEY = 'picsou:chunk-reload-attempted'

/**
 * Matches browser-specific dynamic ES module import errors raised after a SPA rebuild replaces
 * hashed chunk filenames on disk (WebKit/Safari, Chromium, Firefox, Vite preload).
 */
export function isChunkLoadError(err: unknown): boolean {
  const msg =
    err instanceof Error
      ? err.message
      : typeof err === 'string'
        ? err
        : ''
  const lower = msg.toLowerCase()
  return (
    lower.includes('importing a module script failed') ||
    lower.includes('failed to fetch dynamically imported module') ||
    lower.includes('error loading dynamically imported module') ||
    lower.includes('loading chunk')
  )
}

/**
 * Reloads the page at most once per current URL when a stale lazy chunk fails to load, preventing
 * an infinite reload loop if the server is genuinely unreachable.
 */
export function reloadOnceOnChunkError(
  reload: () => void = () => window.location.reload(),
): boolean {
  try {
    const marker = window.location.pathname + window.location.search
    if (window.sessionStorage.getItem(RELOAD_KEY) === marker) {
      return false
    }
    window.sessionStorage.setItem(RELOAD_KEY, marker)
  } catch {
    // sessionStorage unavailable (private mode / restricted WebView): still attempt reload once.
  }
  reload()
  return true
}

/**
 * Drop-in `React.lazy` wrapper that triggers a clean single reload when a lazy route chunk vanished
 * after an app/SPA update instead of crashing into a blank "Unexpected Application Error!" screen.
 */
// Matches React.lazy's own generic signature (`ComponentType<any>`).
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function lazyWithRetry<T extends ComponentType<any>>(
  factory: () => Promise<{ default: T }>,
) {
  return lazy(async () => {
    try {
      const mod = await factory()
      try {
        window.sessionStorage.removeItem(RELOAD_KEY)
      } catch {
        // ignore
      }
      return mod
    } catch (err) {
      if (isChunkLoadError(err) && reloadOnceOnChunkError()) {
        // Keep Suspense pending while the browser navigates to the fresh index.html.
        return new Promise<{ default: T }>(() => {})
      }
      throw err
    }
  })
}

/** Registers Vite's `vite:preloadError` listener so preloaded asset mismatches also recover. */
export function installChunkReloadListener(): void {
  if (typeof window === 'undefined') return
  window.addEventListener('vite:preloadError', (event) => {
    if (reloadOnceOnChunkError()) {
      event.preventDefault()
    }
  })
}
