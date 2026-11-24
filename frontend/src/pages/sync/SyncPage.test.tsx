import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '@/stores/app-store'

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

describe('SyncPage inside the desktop app', () => {
  const info = { app: { desktop: 'true', 'desktop-unavailable': 'louve-auth' } }
  const answerInfo = () =>
    vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(new Response(JSON.stringify(info), { status: 200 }))

  afterEach(() => {
    useAppStore.setState({ desktopMode: false })
    vi.restoreAllMocks()
  })

  it('says a bank the app does not ship is unavailable instead of offering its login', async () => {
    useAppStore.setState({ desktopMode: true })
    answerInfo()

    renderAt('/sync?tab=louve')

    expect(await screen.findByText('desktop.unavailableDetail')).toBeInTheDocument()
    expect(screen.queryByText('louve-panel')).not.toBeInTheDocument()
  })

  it('does not ask which banks are missing outside the desktop app', () => {
    const fetchSpy = answerInfo()

    renderAt('/sync?tab=louve')

    expect(screen.getByText('louve-panel')).toBeInTheDocument()
    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
