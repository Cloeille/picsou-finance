import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { SETUP_STATUS_KEY } from '@/features/setup/hooks'
import { useAppStore } from '@/stores/app-store'
import { desktopApi, type UpdateDesktopLockRequest } from './api'

export const DESKTOP_LOCK_KEY = ['desktop', 'lock'] as const

interface ActuatorInfo {
  app?: { desktop?: boolean | string; 'desktop-unavailable'?: string }
}

/** `caisseEpargne` or `caisse-epargne` -> `caisse-epargne-auth`, the sidecar's service name. */
export function sidecarService(key: string): string {
  return `${key.replace(/[A-Z]/g, c => `-${c.toLowerCase()}`)}-auth`
}

/** The sidecars the desktop app does not ship; empty outside the desktop app. */
export function parseUnavailable(info: ActuatorInfo | undefined): Set<string> {
  const app = info?.app
  if (!app || String(app.desktop) !== 'true') return new Set()
  return new Set((app['desktop-unavailable'] ?? '').split(',').map(s => s.trim()).filter(Boolean))
}

export function useDesktopUnavailable(): (key: string) => boolean {
  const { data } = useQuery({
    queryKey: ['actuator', 'info'],
    queryFn: async (): Promise<ActuatorInfo> => {
      const res = await fetch('/actuator/info')
      return res.ok ? res.json() : {}
    },
    staleTime: Infinity,
  })
  const unavailable = parseUnavailable(data)
  return (key: string) => unavailable.has(sidecarService(key))
}

export function useDesktopLockStatus(enabled: boolean) {
  return useQuery({
    queryKey: DESKTOP_LOCK_KEY,
    queryFn: async () => {
      const status = await desktopApi.getLockStatus()
      useAppStore.getState().setDesktopLockEnabled(status.enabled)
      return status
    },
    enabled,
  })
}

export function useUpdateDesktopLock() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateDesktopLockRequest) => desktopApi.updateLock(body),
    onSuccess: (status) => {
      useAppStore.getState().setDesktopLockEnabled(status.enabled)
      queryClient.setQueryData(DESKTOP_LOCK_KEY, status)
      void queryClient.invalidateQueries({ queryKey: SETUP_STATUS_KEY })
    },
  })
}
