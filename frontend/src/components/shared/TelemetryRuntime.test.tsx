import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { createMemoryRouter, RouterProvider } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TelemetryConfig } from '@/types/api'

function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (key) => m.get(key) ?? null,
    setItem: (key, value) => void m.set(key, String(value)),
    removeItem: (key) => void m.delete(key),
    clear: () => m.clear(),
    key: (index) => [...m.keys()][index] ?? null,
    get length() { return m.size },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const api = vi.hoisted(() => ({ getConfig: vi.fn() }))
vi.mock('@/features/telemetry/api', () => ({ telemetryApi: api }))
const tel = vi.hoisted(() => ({
  initTelemetry: vi.fn(() => Promise.resolve()),
  shutdownTelemetry: vi.fn(),
  trackPageView: vi.fn(),
}))
vi.mock('@/lib/telemetry', () => tel)

const { TelemetryRuntime } = await import('./TelemetryRuntime')
const { useAppStore } = await import('@/stores/app-store')

const ON: TelemetryConfig = { available: true, consent: 'ENABLED', enabled: true, dsn: 'https://k@g.example/1', environment: 'production', release: '1.1.0' }
const SDK_ON = { enabled: true, dsn: ON.dsn, environment: ON.environment, release: ON.release }

function renderAt(path: string, strict = false) {
  const router = createMemoryRouter(
    [
      { path: '/accounts/:id', element: <TelemetryRuntime /> },
      { path: '*', element: <TelemetryRuntime /> },
    ],
    { initialEntries: [path] },
  )
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const app = (
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  )
  return { view: render(strict ? <StrictMode>{app}</StrictMode> : app), qc }
}

describe('TelemetryRuntime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    tel.initTelemetry.mockReset().mockResolvedValue()
    useAppStore.setState({ demoMode: false })
  })

  it('inits and reports the route TEMPLATE, never the real path', async () => {
    api.getConfig.mockResolvedValue(ON)
    renderAt('/accounts/123?x=1')
    await waitFor(() => expect(tel.initTelemetry).toHaveBeenCalledWith(SDK_ON))
    await waitFor(() => expect(tel.trackPageView).toHaveBeenCalledWith('/accounts/:id'))
    expect(JSON.stringify(tel.trackPageView.mock.calls)).not.toContain('123')
  })

  it('reports the catch-all route as not-found, not its raw remainder', async () => {
    api.getConfig.mockResolvedValue(ON)
    renderAt('/some/secret-path')
    await waitFor(() => expect(tel.trackPageView).toHaveBeenCalledWith('not-found'))
  })

  it('does not init (and shuts down) when the server says disabled', async () => {
    api.getConfig.mockResolvedValue({ ...ON, enabled: false, dsn: null })
    renderAt('/accounts/1')
    await waitFor(() => expect(api.getConfig).toHaveBeenCalled())
    await waitFor(() => expect(tel.shutdownTelemetry).toHaveBeenCalled())
    expect(tel.initTelemetry).not.toHaveBeenCalled()
    expect(tel.trackPageView).not.toHaveBeenCalled()
  })

  it('does nothing in demo mode, not even fetching the config', async () => {
    useAppStore.setState({ demoMode: true })
    api.getConfig.mockResolvedValue(ON)
    renderAt('/accounts/1')
    await new Promise((r) => setTimeout(r, 20))
    expect(api.getConfig).not.toHaveBeenCalled()
    expect(tel.initTelemetry).not.toHaveBeenCalled()
  })

  it('shuts down the SDK when the runtime unmounts on logout', async () => {
    api.getConfig.mockResolvedValue(ON)
    const { view } = renderAt('/accounts/1')
    await waitFor(() => expect(tel.initTelemetry).toHaveBeenCalledWith(SDK_ON))
    tel.shutdownTelemetry.mockClear()

    view.unmount()

    expect(tel.shutdownTelemetry).toHaveBeenCalled()
  })

  it('cancels a pending initialization when the runtime unmounts', async () => {
    let completeInit!: () => void
    tel.initTelemetry.mockImplementation(() => new Promise<void>((resolve) => { completeInit = resolve }))
    api.getConfig.mockResolvedValue(ON)
    const { view } = renderAt('/accounts/1')
    await waitFor(() => expect(tel.initTelemetry).toHaveBeenCalledWith(SDK_ON))
    tel.shutdownTelemetry.mockClear()

    view.unmount()
    completeInit()
    await Promise.resolve()

    expect(tel.shutdownTelemetry).toHaveBeenCalled()
    expect(tel.trackPageView).not.toHaveBeenCalled()
  })

  it('survives StrictMode effect replay and can re-enable after consent changes', async () => {
    api.getConfig.mockResolvedValue(ON)
    const { view, qc } = renderAt('/accounts/1', true)
    await waitFor(() => expect(tel.trackPageView).toHaveBeenCalledWith('/accounts/:id'))
    const initCount = tel.initTelemetry.mock.calls.length
    expect(initCount).toBeGreaterThanOrEqual(1)

    qc.setQueryData(['telemetry', 'config'], { ...ON, enabled: false, dsn: null, consent: 'DISABLED' })
    await waitFor(() => expect(tel.shutdownTelemetry).toHaveBeenCalled())
    const viewCount = tel.trackPageView.mock.calls.length
    qc.setQueryData(['telemetry', 'config'], ON)
    await waitFor(() => expect(tel.initTelemetry.mock.calls.length).toBeGreaterThan(initCount))
    await waitFor(() => expect(tel.trackPageView.mock.calls.length).toBeGreaterThan(viewCount))

    view.unmount()
  })
})
