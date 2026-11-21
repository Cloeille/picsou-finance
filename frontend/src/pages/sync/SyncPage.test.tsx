import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/components/shared/PageHeader', () => ({
  PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}))

vi.mock('./LouveTab', () => ({ LouveTab: () => <div>louve-panel</div> }))
vi.mock('./BankSyncTab', () => ({ BankSyncTab: () => <div>banks-panel</div> }))

vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

const { SyncPage } = await import('./SyncPage')

function renderAt(entry: string) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/sync" element={<SyncPage />} />
          <Route path="/sync/louve/callback" element={<SyncPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SyncPage Louve Invest entry points', () => {
  it('opens the Louve Invest panel on its OAuth callback path', () => {
    renderAt('/sync/louve/callback?code=c&state=s&iss=i')
    expect(screen.getByText('louve-panel')).toBeInTheDocument()
    expect(screen.queryByText('banks-panel')).not.toBeInTheDocument()
  })

  it('opens the Louve Invest panel from ?tab=louve, as the sync-all reconnect link does', () => {
    renderAt('/sync?tab=louve')
    expect(screen.getByText('louve-panel')).toBeInTheDocument()
  })

  it('lists Louve Invest among the connectors', () => {
    renderAt('/sync')
    expect(screen.getAllByRole('tab', { name: 'sync.louve.title' }).length).toBeGreaterThan(0)
    expect(screen.getByText('banks-panel')).toBeInTheDocument()
  })
})
