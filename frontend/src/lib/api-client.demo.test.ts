import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'

// zustand's `persist` (profile-store) needs a working localStorage; jsdom here doesn't provide one.
function memoryStorage(): Storage {
  const m = new Map<string, string>()
  return {
    getItem: (k) => m.get(k) ?? null,
    setItem: (k, v) => void m.set(k, String(v)),
    removeItem: (k) => void m.delete(k),
    clear: () => m.clear(),
    key: (i) => [...m.keys()][i] ?? null,
    get length() {
      return m.size
    },
  } as Storage
}
vi.stubGlobal('localStorage', memoryStorage())
vi.stubGlobal('sessionStorage', memoryStorage())

const demo = vi.hoisted(() => ({
  /** The demo chunk "arrives" when this resolves. */
  arrival: Promise.resolve(),
  /** Makes the chunk fail to load, as a dropped connection would. */
  failing: false,
  imports: 0,
  adaptersCreated: 0,
  adapter: undefined as unknown as ReturnType<typeof vi.fn>,
}))

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

// A fresh module graph and mock per test: api-client keeps the loaded adapter in module state.
async function loadApi() {
  vi.resetModules()
  vi.doMock('@/demo', async () => {
    demo.imports++
    await demo.arrival
    if (demo.failing) throw new Error('Failed to fetch dynamically imported module')
    return {
      createDemoAdapter: () => {
        demo.adaptersCreated++
        return demo.adapter
      },
    }
  })
  return (await import('@/lib/api-client')).api
}

describe('api client in demo mode', () => {
  let xhrOpen: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.stubEnv('VITE_DEMO_MODE', 'true')
    demo.arrival = Promise.resolve()
    demo.failing = false
    demo.imports = 0
    demo.adaptersCreated = 0
    demo.adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({
      data: { demo: true },
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
    }) as AxiosResponse)
    xhrOpen = vi.spyOn(XMLHttpRequest.prototype, 'open')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('starts fetching the chunk at boot, before any request', async () => {
    await loadApi()

    await vi.waitFor(() => expect(demo.imports).toBe(1))
  })

  it('holds a request issued before the demo chunk arrives, then answers it from the demo adapter', async () => {
    const chunk = deferred()
    demo.arrival = chunk.promise
    const api = await loadApi()

    const pending = api.get('/accounts')
    await new Promise((r) => setTimeout(r, 20))
    expect(demo.adapter).not.toHaveBeenCalled()
    expect(xhrOpen).not.toHaveBeenCalled()

    chunk.resolve()
    const res = await pending

    expect(res.data).toEqual({ demo: true })
    expect(demo.adapter).toHaveBeenCalledTimes(1)
    expect(xhrOpen).not.toHaveBeenCalled()
  })

  it('rejects the request when the chunk fails to load, and fetches it again on the next request', async () => {
    demo.failing = true
    const api = await loadApi()

    await expect(api.get('/accounts')).rejects.toThrow()
    expect(demo.adapter).not.toHaveBeenCalled()

    demo.failing = false
    const res = await api.get('/accounts')

    expect(res.data).toEqual({ demo: true })
    expect(demo.adapter).toHaveBeenCalledTimes(1)
    expect(xhrOpen).not.toHaveBeenCalled()
  })

  it('builds the demo adapter once for concurrent requests', async () => {
    const api = await loadApi()

    await Promise.all([api.get('/a'), api.get('/b')])

    expect(demo.adaptersCreated).toBe(1)
    expect(demo.adapter).toHaveBeenCalledTimes(2)
  })
})
