import { afterEach, describe, expect, it, vi } from 'vitest'
import * as Sentry from '@sentry/react'
import { initTelemetry, shutdownTelemetry, trackPageView } from './telemetry'

const CONFIG = {
  enabled: true,
  dsn: 'https://key@glitchtip.example/1',
  environment: 'test',
  release: '1.1.0',
}

describe('telemetry SDK lifecycle', () => {
  afterEach(async () => {
    shutdownTelemetry()
    await Sentry.close(100)
    Sentry.getCurrentScope().setClient(undefined)
    vi.unstubAllGlobals()
  })

  it('aborts active sends and prevents queued sends after consent is revoked', async () => {
    const requests: Array<{ signal: AbortSignal; url: string }> = []
    let releaseFetch!: () => void
    const blocked = new Promise<void>((resolve) => { releaseFetch = resolve })
    const fetchSpy = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      requests.push({ signal: init?.signal as AbortSignal, url: String(url) })
      await blocked
      return new Response(null, { status: 200 })
    })
    vi.stubGlobal('fetch', fetchSpy)

    await initTelemetry(CONFIG)
    const oldClient = Sentry.getClient()
    expect(oldClient).toBeDefined()
    trackPageView('/first')
    await vi.waitFor(() => expect(requests.length).toBeGreaterThan(0))

    trackPageView('/queued')
    shutdownTelemetry()

    expect(oldClient?.getOptions().enabled).toBe(false)
    expect(Sentry.getClient()).toBeUndefined()
    expect(requests[0].signal.aborted).toBe(true)
    const sentAtShutdown = fetchSpy.mock.calls.length
    await new Promise((resolve) => setTimeout(resolve, 25))
    expect(fetchSpy).toHaveBeenCalledTimes(sentAtShutdown)
    expect(requests[0].url).toContain('/api/telemetry/tunnel')
    expect(fetchSpy.mock.calls[0][1]).toMatchObject({ method: 'POST' })

    // Reinitialization must not wait for an SDK close that outlives its deadline.
    const reinitStartedAt = Date.now()
    const reinit = initTelemetry(CONFIG)
    await expect(reinit).resolves.toBeUndefined()
    expect(Date.now() - reinitStartedAt).toBeLessThan(2_500)
    expect(Sentry.getClient()).toBeDefined()

    releaseFetch()
  })
})
