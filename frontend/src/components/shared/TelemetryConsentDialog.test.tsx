import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

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
vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

const api = vi.hoisted(() => ({ getConfig: vi.fn(), updateTelemetry: vi.fn() }))
vi.mock('@/features/telemetry/api', () => ({ telemetryApi: api }))
vi.mock('@/features/admin/api', () => ({ adminApi: { updateTelemetry: api.updateTelemetry } }))

const { TelemetryConsentDialog } = await import('./TelemetryConsentDialog')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const ADMIN = { username: 'a', role: 'ADMIN' as const, memberId: 1, displayName: 'A' }
const MEMBER = { ...ADMIN, role: 'MEMBER' as const }

function renderDialog() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <TelemetryConsentDialog />
    </QueryClientProvider>,
  )
}

function settings(available: boolean, consent: 'ENABLED' | 'DISABLED' | 'UNSET') {
  api.getConfig.mockResolvedValue({ available, consent, enabled: consent === 'ENABLED', dsn: null, environment: 'test', release: 'test' })
}

describe('TelemetryConsentDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.updateTelemetry.mockResolvedValue(undefined)
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({ demoMode: false, hasSeenSidebarStylePrompt: true })
  })

  it('shows for an admin when telemetry is available and consent is UNSET', async () => {
    settings(true, 'UNSET')
    renderDialog()
    expect(await screen.findByText('telemetry.consent.title')).toBeInTheDocument()
    expect(screen.getByText('telemetry.consent.never1')).toBeInTheDocument()
  })

  it.each([
    ['no DSN configured', true, 'ADMIN', false, 'UNSET'],
    ['already enabled', true, 'ADMIN', true, 'ENABLED'],
    ['already declined', true, 'ADMIN', true, 'DISABLED'],
  ] as const)('stays hidden: %s', async (_name, _admin, _role, available, consent) => {
    settings(available, consent)
    renderDialog()
    await waitFor(() => expect(api.getConfig).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('telemetry.consent.title')).not.toBeInTheDocument()
  })

  it('never shows (nor even fetches settings) for a non-admin member', async () => {
    useAuthStore.setState({ user: MEMBER })
    settings(true, 'UNSET')
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.getConfig).not.toHaveBeenCalled()
    expect(screen.queryByText('telemetry.consent.title')).not.toBeInTheDocument()
  })

  it('waits for the sidebar prompt so two modals never stack', async () => {
    useAppStore.setState({ hasSeenSidebarStylePrompt: false })
    settings(true, 'UNSET')
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('telemetry.consent.title')).not.toBeInTheDocument()
  })

  it('stays hidden in demo mode', async () => {
    useAppStore.setState({ demoMode: true })
    settings(true, 'UNSET')
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.getConfig).not.toHaveBeenCalled()
    expect(screen.queryByText('telemetry.consent.title')).not.toBeInTheDocument()
  })

  it('"Enable" PUTs enabled=true', async () => {
    settings(true, 'UNSET')
    renderDialog()
    fireEvent.click(await screen.findByText('telemetry.consent.enable'))
    await waitFor(() => expect(api.updateTelemetry).toHaveBeenCalledWith(true))
  })

  it('"No thanks" PUTs enabled=false', async () => {
    settings(true, 'UNSET')
    renderDialog()
    fireEvent.click(await screen.findByText('telemetry.consent.decline'))
    await waitFor(() => expect(api.updateTelemetry).toHaveBeenCalledWith(false))
  })

  it('refetches admin settings after the choice', async () => {
    settings(true, 'UNSET')
    renderDialog()
    fireEvent.click(await screen.findByText('telemetry.consent.decline'))
    settings(true, 'DISABLED')
    await waitFor(() => expect(api.getConfig).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByText('telemetry.consent.title')).not.toBeInTheDocument())
  })
})
