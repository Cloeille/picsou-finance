import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet } = vi.hoisted(() => ({ apiGet: vi.fn() }))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: vi.fn(), delete: vi.fn() },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

const { SimplefinTab } = await import('./SimplefinTab')

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
  })

  it('renders the SimpleFIN panel', async () => {
    apiGet.mockResolvedValue({
      data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null },
    })

    renderTab()

    expect(await screen.findByLabelText('sync.simplefin.token')).toBeInTheDocument()
    expect(apiGet).toHaveBeenCalledWith('/simplefin/status')
  })
})
