import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

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

const { AmexPanel } = await import('./AmexPanel')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}
const ACTIVE = { ...DISCONNECTED, isActive: true }

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  }
  return render(<AmexPanel />, { wrapper: Wrapper })
}

describe('AmexPanel', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  it('shows the classic credentials form in Web/Docker mode', async () => {
    renderPanel()
    expect(await screen.findByLabelText(/sync\.amex\.login/)).toBeInTheDocument()
    expect(screen.getByLabelText(/sync\.amex\.password/)).toBeInTheDocument()
    expect(screen.queryByText('sync.amex.webview.connect')).toBeNull()
  })

  it('uses the 1-click native WebView flow on Desktop and cancels cleanly', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, webviewLoginSupported: true } })
    apiPost.mockImplementation((url: string) => {
      if (url === '/amex/webview/start') return Promise.resolve({ data: { processId: 'wv-amex-1', status: 'WAITING_LOGIN' } })
      if (url === '/amex/webview/cancel/wv-amex-1') return Promise.resolve({ data: { cancelled: true } })
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    renderPanel()

    const connectBtn = await screen.findByText('sync.amex.webview.connect')
    expect(screen.queryByLabelText(/sync\.amex\.login/)).toBeNull()
    expect(screen.queryByLabelText(/sync\.amex\.password/)).toBeNull()

    fireEvent.click(connectBtn)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amex/webview/start'))
    expect(await screen.findByText('sync.amex.webview.waiting')).toBeInTheDocument()

    fireEvent.click(screen.getByText('common.cancel'))
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amex/webview/cancel/wv-amex-1'))
    expect(await screen.findByText('sync.amex.webview.connect')).toBeInTheDocument()
  })

  it('polls WebView status on Desktop and transitions smoothly into syncing', async () => {
    let statusCalls = 0
    apiGet.mockImplementation((url: string) => {
      if (url === '/amex/status') {
        statusCalls += 1
        return Promise.resolve({
          data:
            statusCalls === 1
              ? { ...DISCONNECTED, webviewLoginSupported: true }
              : { ...ACTIVE, syncStatus: 'RUNNING', webviewLoginSupported: true },
        })
      }
      if (url === '/amex/webview/status/wv-amex-2') {
        return Promise.resolve({ data: { status: 'SYNCING' } })
      }
      return Promise.reject(new Error(`unexpected ${url}`))
    })
    apiPost.mockResolvedValue({ data: { processId: 'wv-amex-2', status: 'WAITING_LOGIN' } })
    renderPanel()

    fireEvent.click(await screen.findByText('sync.amex.webview.connect'))
    expect(await screen.findByText('sync.amex.webview.waiting')).toBeInTheDocument()

    await waitFor(
      () => expect(apiGet).toHaveBeenCalledWith('/amex/webview/status/wv-amex-2', expect.anything()),
      { timeout: 4000 },
    )
    expect(await screen.findByText('sync.amex.sessionActive')).toBeInTheDocument()
    expect(apiPost.mock.calls.filter(([url]) => url === '/amex/auth/initiate')).toHaveLength(0)
  })
})
