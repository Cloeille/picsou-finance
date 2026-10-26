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

const api = vi.hoisted(() => ({ getConfig: vi.fn(), getInstanceStatus: vi.fn(), updateTelemetry: vi.fn() }))
vi.mock('@/features/telemetry/api', () => ({ telemetryApi: { getConfig: api.getConfig } }))
vi.mock('@/features/admin/api', () => ({
  adminApi: { getInstanceStatus: api.getInstanceStatus, updateTelemetry: api.updateTelemetry },
}))

const { TelemetryConsentDialog } = await import('./TelemetryConsentDialog')
const { InstanceStatusDialog } = await import('./InstanceStatusDialog')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')
const { resetClientState } = await import('@/lib/reset-client-state')

const ADMIN = { username: 'a', role: 'ADMIN' as const, memberId: 1, displayName: 'A' }
const ISSUE = { code: 'SIDECAR_UNREACHABLE' as const, sidecar: 'corum-auth', expectedBase: 'sidecar-chromium' }
const FINGERPRINT = 'SIDECAR_UNREACHABLE:corum-auth'

let consent: 'ENABLED' | 'DISABLED' | 'UNSET'

function renderBoth() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <TelemetryConsentDialog />
      <InstanceStatusDialog />
    </QueryClientProvider>,
  )
}

const consentTitle = () => screen.queryByText('telemetry.consent.title')
const alertTitle = () => screen.queryByText('admin.instanceStatus.title')

async function consentOnScreenAlone() {
  expect(await screen.findByText('telemetry.consent.title')).toBeInTheDocument()
  await waitFor(() => expect(api.getInstanceStatus).toHaveBeenCalled())
  await new Promise((r) => setTimeout(r, 20))
  expect(alertTitle()).not.toBeInTheDocument()
}

describe('TelemetryConsentDialog and InstanceStatusDialog together', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    consent = 'UNSET'
    api.getConfig.mockImplementation(async () => ({
      available: true,
      consent,
      enabled: consent === 'ENABLED',
      dsn: null,
      environment: 'test',
      release: 'test',
    }))
    api.updateTelemetry.mockImplementation(async (enabled: boolean) => {
      consent = enabled ? 'ENABLED' : 'DISABLED'
    })
    api.getInstanceStatus.mockResolvedValue({ currentVersion: '1.1.0', update: null, issues: [ISSUE] })
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({
      demoMode: false,
      hasSeenSidebarStylePrompt: true,
      dismissedInstanceStatusFingerprint: null,
      telemetryConsentDismissed: false,
    })
  })

  it('shows the instance alert once the consent dialog is closed with X, and consent stays UNSET', async () => {
    renderBoth()
    await consentOnScreenAlone()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(await screen.findByText('admin.instanceStatus.title')).toBeInTheDocument()
    expect(consentTitle()).not.toBeInTheDocument()
    expect(api.updateTelemetry).not.toHaveBeenCalled()
    expect(consent).toBe('UNSET')
  })

  it('shows the instance alert once the consent dialog is closed with Escape, and consent stays UNSET', async () => {
    renderBoth()
    await consentOnScreenAlone()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(await screen.findByText('admin.instanceStatus.title')).toBeInTheDocument()
    expect(consentTitle()).not.toBeInTheDocument()
    expect(api.updateTelemetry).not.toHaveBeenCalled()
    expect(consent).toBe('UNSET')
  })

  it.each([
    ['Yes', 'telemetry.consent.enable', true],
    ['No', 'telemetry.consent.decline', false],
  ] as const)('shows the instance alert once the admin answers %s', async (_name, button, enabled) => {
    renderBoth()
    await consentOnScreenAlone()
    fireEvent.click(screen.getByText(button))
    expect(await screen.findByText('admin.instanceStatus.title')).toBeInTheDocument()
    expect(api.updateTelemetry).toHaveBeenCalledWith(enabled)
    expect(consentTitle()).not.toBeInTheDocument()
  })

  it('keeps an already dismissed alert closed after the consent dialog goes away', async () => {
    useAppStore.setState({ dismissedInstanceStatusFingerprint: FINGERPRINT })
    renderBoth()
    await consentOnScreenAlone()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(consentTitle()).not.toBeInTheDocument())
    await new Promise((r) => setTimeout(r, 20))
    expect(alertTitle()).not.toBeInTheDocument()
    expect(useAppStore.getState().dismissedInstanceStatusFingerprint).toBe(FINGERPRINT)
  })

  it('asks for consent again after an auth-boundary reset', async () => {
    useAppStore.setState({ telemetryConsentDismissed: true })
    resetClientState(new QueryClient())
    renderBoth()
    await consentOnScreenAlone()
  })
})
