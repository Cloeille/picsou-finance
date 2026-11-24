import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

beforeAll(() => {
  if (typeof globalThis.ResizeObserver === 'undefined') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
})

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { RevolutTab } = await import('./RevolutTab')

const DISCONNECTED = {
  connected: false,
  remembered: false,
  lastSyncedAt: null,
}

function renderTab() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return render(<RevolutTab />, { wrapper: Wrapper })
}

describe('RevolutTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  it('shows the phone and passcode form in Web/Docker mode', async () => {
    renderTab()
    expect(await screen.findByLabelText(/sync\.revolut\.phone/)).toBeInTheDocument()
    expect(screen.getByLabelText(/sync\.revolut\.passcode/)).toBeInTheDocument()
    expect(screen.queryByText('sync.revolut.webview.connect')).toBeNull()
  })

  it('uses the 1-click native WebView flow on Desktop and cancels cleanly', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, webviewLoginSupported: true } })
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/webview/start') return Promise.resolve({ data: { processId: 'wv-revo-1', status: 'WAITING_LOGIN' } })
      if (url === '/revolut/webview/cancel/wv-revo-1') return Promise.resolve({ data: { cancelled: true } })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderTab()

    const connectBtn = await screen.findByText('sync.revolut.webview.connect')
    expect(screen.queryByLabelText(/sync\.revolut\.phone/)).toBeNull()
    expect(screen.queryByLabelText(/sync\.revolut\.passcode/)).toBeNull()

    fireEvent.click(connectBtn)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/webview/start'))
    expect(await screen.findByText('sync.revolut.webview.waiting')).toBeInTheDocument()

    fireEvent.click(screen.getByText('common.cancel'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/revolut/webview/cancel/wv-revo-1'))
    expect(await screen.findByText('sync.revolut.webview.connect')).toBeInTheDocument()
  })

  it('polls WebView status on Desktop and auto-confirms discovered accounts', async () => {
    let statusCalls = 0
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        statusCalls += 1
        return Promise.resolve({
          data:
            statusCalls === 1
              ? { ...DISCONNECTED, webviewLoginSupported: true }
              : { connected: true, remembered: false, lastSyncedAt: '2026-11-21T12:00:00Z', webviewLoginSupported: true },
        })
      }
      if (url === '/revolut/webview/status/wv-revo-2') {
        return Promise.resolve({ data: { status: 'SYNCING' } })
      }
      if (url === '/revolut/sync/progress') {
        return Promise.resolve({
          data: {
            running: false,
            phase: 'HARVESTING',
            elapsedSeconds: 1,
            remainingSeconds: null,
            accountsFound: 1,
            done: true,
            error: null,
            errorCode: null,
            discovered: [
              {
                externalId: 'w-1',
                name: 'Revolut',
                type: 'CHECKING',
                currency: 'EUR',
                balance: 100,
                parentExternalId: null,
                alreadyImported: false,
                transactionCount: 2,
              },
            ],
          },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/webview/start') return Promise.resolve({ data: { processId: 'wv-revo-2', status: 'WAITING_LOGIN' } })
      if (url === '/revolut/sync/confirm') return Promise.resolve({ data: undefined })
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    fireEvent.click(await screen.findByText('sync.revolut.webview.connect'))
    expect(await screen.findByText('sync.revolut.webview.waiting')).toBeInTheDocument()

    await waitFor(
      () =>
        expect(apiPost).toHaveBeenCalledWith('/revolut/sync/confirm', {
          selectedExternalIds: ['w-1'],
          remember: false,
          voluntary: false,
        }),
      { timeout: 5000 },
    )
    expect(await screen.findByText('sync.revolut.syncSuccess')).toBeInTheDocument()
  })

  it('returns to the WebView connect card after forgetting a connected Desktop session', async () => {
    let connected = true
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        return Promise.resolve({
          data: connected
            ? { connected: true, remembered: false, lastSyncedAt: '2026-11-21T12:00:00Z', webviewLoginSupported: true }
            : { connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiDelete.mockImplementation((url: string) => {
      if (url === '/revolut/session') {
        connected = false
        return Promise.resolve({ data: undefined })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    expect(await screen.findByText('sync.revolut.connected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.revolut.sync' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.revolut.webview.reconnect' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.revolut.forget' }))

    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/revolut/session'))
    expect(await screen.findByText('sync.revolut.notConnected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.revolut.webview.connect' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'sync.revolut.sync' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'sync.revolut.webview.reconnect' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'sync.revolut.forget' })).toBeNull()
  })

  it('translates coded WebView poll errors and refreshes Revolut status after a failed reconnect', async () => {
    let connected = true
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        return Promise.resolve({
          data: connected
            ? { connected: true, remembered: false, lastSyncedAt: '2026-11-21T12:00:00Z', webviewLoginSupported: true }
            : { connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true },
        })
      }
      if (url === '/revolut/webview/status/wv-revo-exp') {
        connected = false
        return Promise.reject({
          response: {
            status: 422,
            data: { code: 'AUTH_ATTEMPT_EXPIRED', detail: 'Revolut sign-in attempt expired' },
          },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/webview/start') {
        return Promise.resolve({ data: { processId: 'wv-revo-exp', status: 'WAITING_LOGIN' } })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'sync.revolut.webview.reconnect' }))
    expect(await screen.findByText('sync.revolut.webview.waiting')).toBeInTheDocument()

    expect(
      await screen.findByText('sync.revolut.errors.authAttemptExpired', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(screen.queryByText('Revolut sign-in attempt expired')).toBeNull()
    expect(await screen.findByText('sync.revolut.notConnected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'sync.revolut.webview.connect' })).toBeInTheDocument()
  })

  it('translates coded startWebview errors, keeps formatApiError for uncoded ones, and refreshes status', async () => {
    let connected = true
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        return Promise.resolve({
          data: connected
            ? { connected: true, remembered: false, lastSyncedAt: '2026-11-21T12:00:00Z', webviewLoginSupported: true }
            : { connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost
      .mockImplementationOnce((url: string) => {
        if (url === '/revolut/webview/start') {
          connected = false
          return Promise.reject({
            response: {
              status: 422,
              data: { code: 'AUTH_ATTEMPT_EXPIRED', detail: 'Revolut sign-in attempt expired' },
            },
          })
        }
        return Promise.reject(new Error(`unexpected ${url}`))
      })
      .mockImplementationOnce((url: string) => {
        if (url === '/revolut/webview/start') {
          return Promise.reject({
            response: {
              status: 422,
              data: { detail: 'WebView bridge unavailable' },
            },
          })
        }
        return Promise.reject(new Error(`unexpected ${url}`))
      })

    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'sync.revolut.webview.reconnect' }))

    expect(await screen.findByText('sync.revolut.errors.authAttemptExpired')).toBeInTheDocument()
    expect(screen.queryByText('Revolut sign-in attempt expired')).toBeNull()
    expect(await screen.findByText('sync.revolut.notConnected')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.revolut.webview.connect' }))
    expect(await screen.findByText('WebView bridge unavailable')).toBeInTheDocument()
  })

  it('translates an unreachable WebView bridge instead of showing the backend sentence', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        return Promise.resolve({
          data: { connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost.mockImplementationOnce((url: string) => {
      if (url === '/revolut/webview/start') {
        return Promise.reject({
          response: {
            status: 422,
            data: { code: 'UPSTREAM_UNAVAILABLE', detail: 'No response from the Revolut service. Please try again later.' },
          },
        })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'sync.revolut.webview.connect' }))

    expect(await screen.findByText('sync.revolut.errors.serverError')).toBeInTheDocument()
    expect(screen.queryByText('No response from the Revolut service. Please try again later.')).toBeNull()
  })

  it('refreshes Revolut status when WebView polling ends with FAILED', async () => {
    let connected = true
    apiGet.mockImplementation((url: string) => {
      if (url === '/revolut/status') {
        return Promise.resolve({
          data: connected
            ? { connected: true, remembered: false, lastSyncedAt: '2026-11-21T12:00:00Z', webviewLoginSupported: true }
            : { connected: false, remembered: false, lastSyncedAt: null, webviewLoginSupported: true },
        })
      }
      if (url === '/revolut/webview/status/wv-revo-fail') {
        connected = false
        return Promise.resolve({ data: { status: 'FAILED', errorCode: 'SESSION_EXPIRED' } })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost.mockImplementation((url: string) => {
      if (url === '/revolut/webview/start') {
        return Promise.resolve({ data: { processId: 'wv-revo-fail', status: 'WAITING_LOGIN' } })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })

    renderTab()
    fireEvent.click(await screen.findByRole('button', { name: 'sync.revolut.webview.reconnect' }))

    expect(
      await screen.findByText('sync.revolut.errors.sessionExpired', {}, { timeout: 5000 }),
    ).toBeInTheDocument()
    expect(await screen.findByText('sync.revolut.notConnected')).toBeInTheDocument()
  })
})
