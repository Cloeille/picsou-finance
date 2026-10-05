import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const sentry = vi.hoisted(() => ({
  init: vi.fn(),
  close: vi.fn(() => Promise.resolve(true)),
  captureException: vi.fn(),
  captureMessage: vi.fn(),
  globalHandlersIntegration: vi.fn(() => ({ name: 'GlobalHandlers' })),
  linkedErrorsIntegration: vi.fn(() => ({ name: 'LinkedErrors' })),
}))
const factory = vi.hoisted(() => vi.fn())

vi.mock('@sentry/react', () => {
  factory()
  return sentry
})

const CONFIG = { enabled: true, dsn: 'https://key@glitchtip.example/1', environment: 'production', release: '1.1.0' }

async function load() {
  return import('./telemetry')
}

describe('telemetry', () => {
  let fetchSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
  })
  afterEach(() => vi.unstubAllGlobals())

  it('never loads or inits the SDK when disabled', async () => {
    const t = await load()
    await t.initTelemetry({ ...CONFIG, enabled: false, dsn: null })
    await t.initTelemetry({ ...CONFIG, enabled: false })
    t.trackPageView('/accounts/:id')
    t.trackFeature('sync_triggered')
    t.captureException(new Error('x'))
    expect(factory).not.toHaveBeenCalled()
    expect(sentry.init).not.toHaveBeenCalled()
    expect(sentry.captureMessage).not.toHaveBeenCalled()
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(t.isTelemetryActive()).toBe(false)
  })

  it('never loads the SDK when the DSN is empty', async () => {
    const t = await load()
    await t.initTelemetry({ ...CONFIG, dsn: '' })
    await t.initTelemetry({ ...CONFIG, dsn: null })
    expect(factory).not.toHaveBeenCalled()
    expect(sentry.init).not.toHaveBeenCalled()
  })

  it('inits with the tunnel, no breadcrumbs and no default integrations once consented', async () => {
    const t = await load()
    await t.initTelemetry(CONFIG)
    await t.initTelemetry(CONFIG) // idempotent
    expect(sentry.init).toHaveBeenCalledTimes(1)
    const opts = sentry.init.mock.calls[0][0]
    expect(opts).toMatchObject({
      dsn: CONFIG.dsn,
      tunnel: '/api/telemetry/tunnel',
      defaultIntegrations: false,
      maxBreadcrumbs: 0,
      environment: 'production',
      release: '1.1.0',
    })
    expect(opts.dataCollection).toMatchObject({ userInfo: false, cookies: false, httpHeaders: false })
    expect(opts.beforeBreadcrumb()).toBeNull()
    const scrubbed = opts.beforeSend({ user: { id: 1 }, message: 'a@b.fr', request: {} })
    expect(scrubbed).toEqual({ message: '[email]' })
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('sends page views and features as tagged messages, nothing else', async () => {
    const t = await load()
    await t.initTelemetry(CONFIG)
    t.trackPageView('/accounts/:id')
    t.trackFeature('goal_created')
    expect(sentry.captureMessage).toHaveBeenNthCalledWith(1, 'page_view', {
      level: 'info',
      tags: { route: '/accounts/:id' },
      fingerprint: ['page_view', '/accounts/:id'],
    })
    expect(sentry.captureMessage).toHaveBeenNthCalledWith(2, 'feature_used', {
      level: 'info',
      tags: { feature: 'goal_created' },
      fingerprint: ['feature', 'goal_created'],
    })
  })

  it('shutdown closes the SDK and turns captures into no-ops', async () => {
    const t = await load()
    await t.initTelemetry(CONFIG)
    t.shutdownTelemetry()
    expect(sentry.close).toHaveBeenCalledTimes(1)
    t.trackPageView('/')
    t.captureException(new Error('x'))
    expect(sentry.captureMessage).not.toHaveBeenCalled()
    expect(sentry.captureException).not.toHaveBeenCalled()
    expect(t.isTelemetryActive()).toBe(false)
  })

  it('shutdown while the SDK is still loading cancels the init', async () => {
    const t = await load()
    const pending = t.initTelemetry(CONFIG)
    t.shutdownTelemetry()
    await pending
    expect(sentry.init).not.toHaveBeenCalled()
    expect(t.isTelemetryActive()).toBe(false)
  })

  it('swallows SDK init failures', async () => {
    sentry.init.mockImplementationOnce(() => { throw new Error('boom') })
    const t = await load()
    await expect(t.initTelemetry(CONFIG)).resolves.toBeUndefined()
    expect(t.isTelemetryActive()).toBe(false)
  })
})
