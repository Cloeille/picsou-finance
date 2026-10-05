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
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const { IbkrTab } = await import('./IbkrTab')

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<IbkrTab />, { wrapper: Wrapper })
}

describe('IbkrTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
  })

  it('stores the trimmed token + query id when connecting', async () => {
    apiGet.mockResolvedValue({ data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null } })
    apiPost.mockResolvedValue({ data: null })

    renderTab()

    fireEvent.change(await screen.findByLabelText('sync.ibkr.token'), { target: { value: '  tok-123  ' } })
    fireEvent.change(screen.getByLabelText('sync.ibkr.queryId'), { target: { value: ' 987654 ' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.ibkr.connect' }))

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/ibkr/connect', { token: 'tok-123', queryId: '987654' }),
    )
  })

  it('shows the sync + disconnect controls and syncs when connected', async () => {
    apiGet.mockResolvedValue({
      data: { connected: true, connectionId: 1, status: 'CONNECTED', lastSyncedAt: null, maskedToken: '••••3F29' },
    })
    apiPost.mockResolvedValue({ data: [] })

    renderTab()

    // Masked token surfaces; the connect form is gone.
    expect(await screen.findByText('••••3F29')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.ibkr.token')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'sync.ibkr.sync' }))
    await vi.waitFor(() => expect(apiPost).toHaveBeenCalledWith('/ibkr/sync'))
  })

  it('surfaces an error when the sync fails', async () => {
    apiGet.mockResolvedValue({
      data: { connected: true, connectionId: 1, status: 'CONNECTED', lastSyncedAt: null, maskedToken: '••••3F29' },
    })
    apiPost.mockRejectedValue({ response: { status: 500, data: { detail: 'Interactive Brokers: token has expired (code 1015)' } } })

    renderTab()

    fireEvent.click(await screen.findByRole('button', { name: 'sync.ibkr.sync' }))
    expect(await screen.findByText('Interactive Brokers: token has expired (code 1015)')).toBeInTheDocument()
  })
})
