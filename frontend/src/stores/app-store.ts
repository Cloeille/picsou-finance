import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type DateFormat = 'locale' | 'iso'

interface AppState {
  sidebarCollapsed: boolean
  demoMode: boolean
  dateFormat: DateFormat
  dismissedInstanceStatusFingerprint: string | null
  /** The telemetry consent dialog was closed unanswered. Page-load only, never persisted. */
  telemetryConsentDismissed: boolean
  /**
   * Privacy mode: every amount on screen is replaced by a fixed-length mask, chart scales
   * included, so Picsou can be demoed without disclosing what it is worth.
   */
  hideAmounts: boolean
  /** Local custom profile photos keyed by memberId (or "self"), stored as compact 192px data URLs. */
  profilePhotos: Record<string, string>
  /** Latest What's New release version already opened by the user. */
  lastSeenWhatsNewVersion: string | null
  toggleSidebar: () => void
  setDemoMode: (enabled: boolean) => void
  setDateFormat: (format: DateFormat) => void
  setDismissedInstanceStatusFingerprint: (fingerprint: string | null) => void
  dismissTelemetryConsent: () => void
  toggleHideAmounts: () => void
  setHideAmounts: (hidden: boolean) => void
  setProfilePhoto: (key: string, dataUrl: string | null) => void
  markWhatsNewSeen: (version: string) => void
}

export const useAppStore = create<AppState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      demoMode: import.meta.env.VITE_DEMO_MODE === 'true',
      dateFormat: 'locale',
      dismissedInstanceStatusFingerprint: null,
      telemetryConsentDismissed: false,
      hideAmounts: false,
      profilePhotos: {},
      lastSeenWhatsNewVersion: null,
      toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),
      setDemoMode: (enabled) => set({ demoMode: enabled }),
      setDateFormat: (format) => set({ dateFormat: format }),
      setDismissedInstanceStatusFingerprint: (fingerprint) =>
        set({ dismissedInstanceStatusFingerprint: fingerprint }),
      dismissTelemetryConsent: () => set({ telemetryConsentDismissed: true }),
      toggleHideAmounts: () => set((s) => ({ hideAmounts: !s.hideAmounts })),
      setHideAmounts: (hidden) => set({ hideAmounts: hidden }),
      setProfilePhoto: (key, dataUrl) =>
        set((s) => {
          const next = { ...s.profilePhotos }
          if (dataUrl) next[key] = dataUrl
          else delete next[key]
          return { profilePhotos: next }
        }),
      markWhatsNewSeen: (version) => set({ lastSeenWhatsNewVersion: version }),
    }),
    {
      name: 'picsou-app',
      partialize: (s) => ({
        sidebarCollapsed: s.sidebarCollapsed,
        dateFormat: s.dateFormat,
        dismissedInstanceStatusFingerprint: s.dismissedInstanceStatusFingerprint,
        // An allowlist: a field left out here is silently not persisted -- no type error, no
        // runtime error, the setting simply does not survive a reload. Mid-demo, that would be
        // a refresh away from showing everything.
        hideAmounts: s.hideAmounts,
        profilePhotos: s.profilePhotos,
        lastSeenWhatsNewVersion: s.lastSeenWhatsNewVersion,
      }),
    }
  )
)
