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

type TelemetryInitConfig = Pick<TelemetryConfig, 'enabled' | 'dsn' | 'environment' | 'release'>
type SentryModule = typeof import('@sentry/react')

let sentry: SentryModule | null = null
let initializing: Promise<void> | null = null
let closing: Promise<void> | null = null
// Bumped on every shutdown so an `import()` still in flight can tell it was cancelled.
let epoch = 0
const CLOSE_TIMEOUT_MS = 1_000

type TelemetrySession = {
  active: boolean
  controllers: Set<AbortController>
  client?: ReturnType<SentryModule['getClient']>
}

let session: TelemetrySession | null = null

export function isTelemetryActive(): boolean {
  return sentry !== null
}

/** Starts the SDK when (and only when) telemetry is enabled with a DSN. Never throws. */
export function initTelemetry(config: TelemetryInitConfig): Promise<void> {
  if (!config.enabled || !config.dsn) return Promise.resolve()
  if (sentry) return Promise.resolve()
  if (initializing) return initializing

  const startedAt = epoch
  const dsn = config.dsn
  const nextSession: TelemetrySession = { active: true, controllers: new Set() }
  const run: Promise<void> = (async () => {
    try {
      // Sentry owns process-global handlers; don't let a previous client's async close race a new init.
      await closing
      if (startedAt !== epoch) return
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
        // Without this the SDK attaches a synthetic stack to every captureMessage, so a page view
        // is stored as "Error: page_view" with React internals as frames. Real errors keep theirs.
        attachStacktrace: false,
        maxBreadcrumbs: 0,
        beforeBreadcrumb: () => null,
        beforeSend: (event) => nextSession.active ? scrubEvent(event) : null,
        sendClientReports: false,
        transport: (options) => mod.makeFetchTransport(options, async (input, init) => {
          if (!nextSession.active) return new Response(null, { status: 204 })

          const controller = new AbortController()
          nextSession.controllers.add(controller)
          try {
            // Keep Sentry's same-origin request options, auth headers and cookies intact; only
            // add a signal so consent revocation can cancel requests already in flight.
            if (!nextSession.active) return new Response(null, { status: 204 })
            return await fetch(input, { ...init, signal: controller.signal })
          } finally {
            nextSession.controllers.delete(controller)
          }
        }),
      })
      nextSession.client = mod.getClient()
      sentry = mod
      session = nextSession
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
  const endingSession = session
  sentry = null
  session = null
  initializing = null
  if (endingSession) {
    endingSession.active = false
    for (const controller of endingSession.controllers) controller.abort()
  }
  if (mod && endingSession?.client) {
    const client = endingSession.client
    client.getOptions().enabled = false
    const scope = mod.getCurrentScope()
    if (scope.getClient() === client) scope.setClient(undefined)

    const close = new Promise<void>((resolve) => {
      let settled = false
      const finish = () => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        resolve()
      }
      const timer = setTimeout(finish, CLOSE_TIMEOUT_MS)
      try {
        Promise.resolve(client.close(CLOSE_TIMEOUT_MS)).then(finish, finish)
      } catch {
        finish()
      }
    })
    closing = close
    void close.finally(() => {
      if (closing === close) closing = null
    })
  }
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
