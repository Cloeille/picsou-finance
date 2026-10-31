import { useQuery } from '@tanstack/react-query'
import { QUERY_STALE_TIMES } from '@/lib/constants'
import { useAppStore } from '@/stores/app-store'
import { useAuthStore } from '@/stores/auth-store'
import { telemetryApi } from './api'

export const telemetryKeys = {
  all: ['telemetry'] as const,
  config: () => [...telemetryKeys.all, 'config'] as const,
}

export function useTelemetryConfig(enabled = true) {
  return useQuery({
    queryKey: telemetryKeys.config(),
    queryFn: telemetryApi.getConfig,
    enabled,
    staleTime: QUERY_STALE_TIMES.dashboard,
    retry: false,
  })
}

/**
 * Whether the telemetry consent dialog is on screen. The single source for that dialog and for
 * every modal that must wait for it: a consent closed unanswered stays UNSET, so `consent` alone
 * cannot tell a pending prompt from a dismissed one.
 */
export function useTelemetryConsentPrompt() {
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN')
  const demoMode = useAppStore((s) => s.demoMode)
  const sidebarPromptSeen = useAppStore((s) => s.hasSeenSidebarStylePrompt)
  const dismissed = useAppStore((s) => s.telemetryConsentDismissed)
  const config = useTelemetryConfig(isAdmin && !demoMode)
  const open =
    isAdmin &&
    !demoMode &&
    sidebarPromptSeen &&
    !dismissed &&
    config.data?.available === true &&
    config.data.consent === 'UNSET'
  return { open, isLoading: config.isLoading }
}
