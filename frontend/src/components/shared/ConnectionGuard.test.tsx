import '@testing-library/jest-dom/vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

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

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { ConnectionGuard } = await import('./ConnectionGuard')
const { useAppStore } = await import('@/stores/app-store')
const { useConnectivityStore } = await import('@/stores/connectivity-store')

describe('ConnectionGuard', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    delete (window as Window & { isTauri?: boolean }).isTauri
    useAppStore.setState({ demoMode: false, desktopMode: false })
    useConnectivityStore.setState({ isConnected: true, isChecking: false })
  })

  afterEach(() => {
    delete (window as Window & { isTauri?: boolean }).isTauri
    globalThis.fetch = originalFetch
    vi.restoreAllMocks()
  })

  it('uses desktop wording inside the Tauri shell even before desktopMode is populated', async () => {
    Object.defineProperty(window, 'isTauri', { value: true, configurable: true })

    let rejectHealth!: (reason?: unknown) => void
    globalThis.fetch = vi.fn(
      () => new Promise<Response>((_, reject) => {
        rejectHealth = reject
      }),
    )

    render(
      <ConnectionGuard>
        <div>app-content</div>
      </ConnectionGuard>,
    )

    expect(screen.getByText('error.connectingDesktop')).toBeInTheDocument()
    expect(screen.queryByText('error.connecting')).not.toBeInTheDocument()

    await act(async () => {
      rejectHealth(new Error('network'))
    })

    expect(await screen.findByText('error.noConnectionDescDesktop')).toBeInTheDocument()
    expect(screen.queryByText('error.noConnectionDesc')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'error.noConnectionDesktop' })).toBeInTheDocument()
    expect(screen.queryByText('error.noConnection')).not.toBeInTheDocument()
  })

  it('uses server wording outside the Tauri shell when desktopMode is false', async () => {
    let rejectHealth!: (reason?: unknown) => void
    globalThis.fetch = vi.fn(
      () => new Promise<Response>((_, reject) => {
        rejectHealth = reject
      }),
    )

    render(
      <ConnectionGuard>
        <div>app-content</div>
      </ConnectionGuard>,
    )

    expect(screen.getByText('error.connecting')).toBeInTheDocument()
    expect(screen.queryByText('error.connectingDesktop')).not.toBeInTheDocument()

    await act(async () => {
      rejectHealth(new Error('network'))
    })

    expect(await screen.findByText('error.noConnectionDesc')).toBeInTheDocument()
    expect(screen.queryByText('error.noConnectionDescDesktop')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'error.noConnection' })).toBeInTheDocument()
    expect(screen.queryByText('error.noConnectionDesktop')).not.toBeInTheDocument()
  })

  it('renders children once the health probe succeeds', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))

    render(
      <ConnectionGuard>
        <div>app-content</div>
      </ConnectionGuard>,
    )

    await waitFor(() => {
      expect(screen.getByText('app-content')).toBeInTheDocument()
    })
  })
})
