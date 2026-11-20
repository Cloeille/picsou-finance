import { useQuery } from '@tanstack/react-query'

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
