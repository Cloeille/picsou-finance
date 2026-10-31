import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type DateFormat = 'locale' | 'iso'
export type SidebarStyle = 'current' | 'classic'

interface AppState {
  sidebarCollapsed: boolean
  demoMode: boolean
  dateFormat: DateFormat
  sidebarStyle: SidebarStyle
  hasSeenSidebarStylePrompt: boolean
  dismissedInstanceStatusFingerprint: string | null
  /** The telemetry consent dialog was closed unanswered. Page-load only, never persisted. */
  telemetryConsentDismissed: boolean
  /**
   * Privacy mode: every amount on screen is replaced by a fixed-length mask, chart scales
   * included, so Picsou can be demoed without disclosing what it is worth.
   */
  hideAmounts: boolean
  toggleSidebar: () => void
  setDemoMode: (enabled: boolean) => void
  setDateFormat: (format: DateFormat) => void
  setSidebarStyle: (style: SidebarStyle) => void
  setHasSeenSidebarStylePrompt: (seen: boolean) => void
  setDismissedInstanceStatusFingerprint: (fingerprint: string | null) => void
  dismissTelemetryConsent: () => void
  toggleHideAmounts: () => void
  setHideAmounts: (hidden: boolean) => void
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      demoMode: import.meta.env.VITE_DEMO_MODE === 'true',
      dateFormat: 'locale',
      sidebarStyle: 'current',
      hasSeenSidebarStylePrompt: false,
      dismissedInstanceStatusFingerprint: null,
      telemetryConsentDismissed: false,
      hideAmounts: false,
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setDemoMode: (enabled) => set({ demoMode: enabled }),
      setDateFormat: (format) => set({ dateFormat: format }),
      setSidebarStyle: (style) => set({ sidebarStyle: style }),
      setHasSeenSidebarStylePrompt: (seen) => set({ hasSeenSidebarStylePrompt: seen }),
      setDismissedInstanceStatusFingerprint: (fingerprint) =>
        set({ dismissedInstanceStatusFingerprint: fingerprint }),
      dismissTelemetryConsent: () => set({ telemetryConsentDismissed: true }),
      toggleHideAmounts: () => set((s) => ({ hideAmounts: !s.hideAmounts })),
      setHideAmounts: (hidden) => set({ hideAmounts: hidden }),
    }),
    {
      name: 'picsou-app',
      partialize: (s) => ({
        sidebarCollapsed: s.sidebarCollapsed,
        dateFormat: s.dateFormat,
        sidebarStyle: s.sidebarStyle,
        hasSeenSidebarStylePrompt: s.hasSeenSidebarStylePrompt,
        dismissedInstanceStatusFingerprint: s.dismissedInstanceStatusFingerprint,
        // An allowlist: a field left out here is silently not persisted -- no type error, no
        // runtime error, the setting simply does not survive a reload. Mid-demo, that would be
        // a refresh away from showing everything.
        hideAmounts: s.hideAmounts,
      }),
    }
  )
)
