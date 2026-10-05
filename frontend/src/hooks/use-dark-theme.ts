import { useSyncExternalStore } from 'react'

/**
 * Whether the dark palette is showing, re-rendering when it changes.
 *
 * Reads the `.dark` class on `<html>` rather than the stored preference, because that class is
 * the single thing `lib/theme.ts` and `public/theme-init.js` write (including the "system" case
 * following the OS), so this cannot disagree with what the CSS is painting.
 */
export function useDarkTheme(): boolean {
  return useSyncExternalStore(subscribe, isDark, () => false)
}

function isDark(): boolean {
  return document.documentElement.classList.contains('dark')
}

function subscribe(onChange: () => void): () => void {
  const observer = new MutationObserver(onChange)
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => observer.disconnect()
}
