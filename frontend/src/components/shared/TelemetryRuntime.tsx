import { useEffect, useState } from 'react'
import { useMatches } from 'react-router-dom'
import { useTelemetryConfig } from '@/features/telemetry/hooks'
import { useAppStore } from '@/stores/app-store'
import { initTelemetry, shutdownTelemetry, trackPageView } from '@/lib/telemetry'
import { templatePath } from '@/lib/telemetry-scrub'

/**
 * Route template of the current page (`/accounts/:id`), built from the router's matched params —
 * the real path never leaves this function. The catch-all route is reported as `not-found` since
 * its remainder is arbitrary user input.
 */
function useRouteTemplate(): string {
  const matches = useMatches()
  const last = matches[matches.length - 1]
  if (!last) return '/'
  if ('*' in (last.params ?? {})) return 'not-found'
  return templatePath(last.pathname, last.params)
}

/**
 * Bridges the instance's telemetry consent to the SDK: starts it when the server reports
 * `enabled` (consent given AND a DSN configured), shuts it down the moment that stops being true,
 * and reports one page view per route change. Renders nothing. Inert in demo mode.
 */
export function TelemetryRuntime() {
  const demoMode = useAppStore((s) => s.demoMode)
  const { data: config } = useTelemetryConfig(!demoMode)
  const template = useRouteTemplate()
  // A fresh object per completed init, so the page-view effect re-fires once the SDK is up.
  const [initDone, setInitDone] = useState<object | null>(null)

  const enabled = !demoMode && !!config?.enabled && !!config.dsn
  const dsn = config?.dsn ?? null
  const environment = config?.environment ?? ''
  const release = config?.release ?? ''

  useEffect(() => {
    if (!enabled) {
      shutdownTelemetry()
      return
    }
    let cancelled = false
    void initTelemetry({ enabled, dsn, environment, release }).then(() => {
      if (!cancelled) setInitDone({})
    })
    return () => {
      cancelled = true
    }
  }, [enabled, dsn, environment, release])

  useEffect(() => {
    // `trackPageView` is a no-op while the SDK is not running, so a stale `initDone` is harmless.
    if (enabled && initDone) trackPageView(template)
  }, [enabled, initDone, template])

  return null
}
