import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
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
  useTranslation: () => ({ t: (key: string, vars?: { count?: number }) => vars?.count != null ? `${key}:${vars.count}` : key }),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const { SimplefinTab } = await import('./SimplefinTab')

const DISCONNECTED = {
  data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null },
}

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<SimplefinTab />, { wrapper: Wrapper })
}

describe('SimplefinTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
  })

  it('claims the trimmed token and syncs immediately', async () => {
    apiGet.mockResolvedValue(DISCONNECTED)
    apiPost.mockImplementation((url: string) => {
      if (url === '/simplefin/connect') {
        apiGet.mockResolvedValue({
          data: { connected: true, connectionId: 1, status: 'CONNECTED', lastSyncedAt: null, maskedToken: '••••1234' },
        })
        return Promise.resolve({ data: null })
      }
      return Promise.resolve({ data: [{ id: 1 }, { id: 2 }] })
    })

    renderTab()

    const token = await screen.findByLabelText('sync.simplefin.token')
    expect(token).toHaveAttribute('type', 'password')
    fireEvent.change(token, { target: { value: '  tok-123  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.simplefin.connect' }))

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/simplefin/connect', { token: 'tok-123' }),
    )
    await vi.waitFor(() => expect(apiPost).toHaveBeenCalledWith('/simplefin/sync'))
  })

  it('shows sync and disconnect once connected', async () => {
    apiGet.mockResolvedValue({
      data: { connected: true, connectionId: 1, status: 'CONNECTED', lastSyncedAt: null, maskedToken: '••••1234' },
    })
    apiPost.mockResolvedValue({ data: [] })

    renderTab()

    expect(await screen.findByText('••••1234')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.simplefin.token')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.simplefin.sync' }))
    await vi.waitFor(() => expect(apiPost).toHaveBeenCalledWith('/simplefin/sync'))
  })

  it('surfaces a sync failure', async () => {
    apiGet.mockResolvedValue({
      data: { connected: true, connectionId: 1, status: 'ERROR', lastSyncedAt: null, maskedToken: '••••1234' },
    })
    apiPost.mockRejectedValue({
      response: { status: 422, data: { detail: 'SimpleFIN refused the stored access. It may have been revoked.' } },
    })

    renderTab()

    fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))
    expect(await screen.findByText('SimpleFIN refused the stored access. It may have been revoked.')).toBeInTheDocument()
  })

  it('falls back to the token form when the status request fails', async () => {
    apiGet.mockRejectedValue(new Error('Network Error'))

    renderTab()

    expect(await screen.findByLabelText('sync.simplefin.token')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'sync.simplefin.sync' })).not.toBeInTheDocument()
  })

  it('shows the token link and never calls the backend before the user submits', async () => {
    apiGet.mockResolvedValue(DISCONNECTED)

    renderTab()

    const link = await screen.findByRole('link', { name: 'sync.simplefin.createToken' })
    expect(link).toHaveAttribute('href', 'https://bridge.simplefin.org/simplefin/create')
    expect(apiGet).toHaveBeenCalledWith('/simplefin/status')
    expect(apiPost).not.toHaveBeenCalled()
    expect(apiDelete).not.toHaveBeenCalled()
  })

  it('does not claim the token when the field only holds whitespace', async () => {
    apiGet.mockResolvedValue(DISCONNECTED)

    renderTab()

    fireEvent.change(await screen.findByLabelText('sync.simplefin.token'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.simplefin.connect' }))

    expect(apiPost).not.toHaveBeenCalled()
  })
})
