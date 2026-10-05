import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
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

const ON: TelemetryConfig = { enabled: true, dsn: 'https://k@g.example/1', environment: 'production', release: '1.1.0' }

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: '/accounts/:id', element: <TelemetryRuntime /> },
      { path: '*', element: <TelemetryRuntime /> },
    ],
    { initialEntries: [path] },
  )
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

describe('TelemetryRuntime', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAppStore.setState({ demoMode: false })
  })

  it('inits and reports the route TEMPLATE, never the real path', async () => {
    api.getConfig.mockResolvedValue(ON)
    renderAt('/accounts/123?x=1')
    await waitFor(() => expect(tel.initTelemetry).toHaveBeenCalledWith(ON))
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
})
