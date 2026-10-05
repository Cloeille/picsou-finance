import type { TelemetryConfig } from '@/types/api'
import { scrubEvent } from './telemetry-scrub'

/**
 * Opt-in anonymous telemetry (issue #200).
 *
 * The Sentry SDK is loaded with a dynamic `import()` ONLY once the instance has consented and a
 * DSN is configured, so without consent the SDK is not even downloaded and no request is made.
 * Events never go to the DSN host directly: they are tunneled through the Picsou backend, which
 * scrubs them again and forwards them (the user's IP never reaches the telemetry server).
 */

/** Closed set of usage events — no payload, the name is the whole signal. */
export type FeatureEvent =
  | 'sync_triggered'
  | 'export_downloaded'
  | 'goal_created'
  | 'budget_viewed'

export const TELEMETRY_TUNNEL_URL = '/api/telemetry/tunnel'

type SentryModule = typeof import('@sentry/react')

let sentry: SentryModule | null = null
let initializing: Promise<void> | null = null
// Bumped on every shutdown so an `import()` still in flight can tell it was cancelled.
let epoch = 0

export function isTelemetryActive(): boolean {
  return sentry !== null
}

/** Starts the SDK when (and only when) telemetry is enabled with a DSN. Never throws. */
export function initTelemetry(config: TelemetryConfig): Promise<void> {
  if (!config.enabled || !config.dsn) return Promise.resolve()
  if (sentry) return Promise.resolve()
  if (initializing) return initializing

  const startedAt = epoch
  const dsn = config.dsn
  const run: Promise<void> = (async () => {
    try {
      const mod = await import('@sentry/react')
      if (startedAt !== epoch) return // shut down while the SDK was loading
      mod.init({
        dsn,
        environment: config.environment,
        release: config.release,
        tunnel: TELEMETRY_TUNNEL_URL,
        // v11 replaced `sendDefaultPii` by `dataCollection`: switch every channel off.
        dataCollection: {
          userInfo: false,
          cookies: false,
          httpHeaders: false,
          httpBodies: [],
          urlQueryParams: false,
          stackFrameVariables: false,
          frameContextLines: 0,
        },
        // No default integrations: breadcrumbs, browser tracing, replay, http context and session
        // tracking all collect more than the allowlist permits. Only error capture is kept.
        defaultIntegrations: false,
        integrations: [mod.globalHandlersIntegration(), mod.linkedErrorsIntegration()],
        maxBreadcrumbs: 0,
        beforeBreadcrumb: () => null,
        beforeSend: (event) => scrubEvent(event),
        sendClientReports: false,
      })
      sentry = mod
    } catch {
      // Telemetry must never break the app.
    } finally {
      if (startedAt === epoch) initializing = null
    }
  })()
  initializing = run
  return run
}

/** Closes the SDK; every later capture is a no-op until `initTelemetry` runs again. */
export function shutdownTelemetry(): void {
  epoch += 1
  const mod = sentry
  sentry = null
  initializing = null
  if (mod) void mod.close(2000).catch(() => undefined)
}

export function captureException(error: unknown): void {
  sentry?.captureException(error, { mechanism: { type: 'react.errorboundary', handled: false } })
}

/** `template` is a route pattern such as `/accounts/:id` — never a real path. */
export function trackPageView(template: string): void {
  sentry?.captureMessage('page_view', {
    level: 'info',
    tags: { route: template },
    fingerprint: ['page_view', template],
  })
}

export function trackFeature(name: FeatureEvent): void {
  sentry?.captureMessage('feature_used', {
    level: 'info',
    tags: { feature: name },
    fingerprint: ['feature', name],
  })
}
