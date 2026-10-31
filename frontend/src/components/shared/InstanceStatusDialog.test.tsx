import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { instanceStatusFingerprint } from './instance-status'

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

const api = vi.hoisted(() => ({ getConfig: vi.fn(), getInstanceStatus: vi.fn() }))
vi.mock('@/features/telemetry/api', () => ({ telemetryApi: { getConfig: api.getConfig } }))
vi.mock('@/features/admin/api', () => ({ adminApi: { getInstanceStatus: api.getInstanceStatus } }))

const { InstanceStatusDialog } = await import('./InstanceStatusDialog')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const ADMIN = { username: 'a', role: 'ADMIN' as const, memberId: 1, displayName: 'A' }
const MEMBER = { ...ADMIN, role: 'MEMBER' as const }

const ISSUE = {
  code: 'SIDECAR_UNREACHABLE' as const,
  sidecar: 'corum-auth',
  expectedBase: 'sidecar-chromium',
}
const RELEASE = 'https://github.com/Cloeille/picsou-finance/releases/tag/1.2.0'

function renderDialog() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <InstanceStatusDialog />
    </QueryClientProvider>,
  )
}

function telemetry(available: boolean, consent: 'ENABLED' | 'DISABLED' | 'UNSET') {
  api.getConfig.mockResolvedValue({
    available,
    consent,
    enabled: consent === 'ENABLED',
    dsn: null,
    environment: 'test',
    release: 'test',
  })
}

function instanceStatus(body: {
  currentVersion?: string
  update: { latestVersion: string; releaseUrl: string } | null
  issues: { code: 'SIDECAR_UNREACHABLE' | 'SIDECAR_OUTDATED'; sidecar: string; expectedBase: string | null }[]
}) {
  api.getInstanceStatus.mockResolvedValue({
    currentVersion: body.currentVersion ?? '1.1.0',
    update: body.update,
    issues: body.issues,
  })
}

describe('instanceStatusFingerprint', () => {
  it('sorts code:sidecar and appends the latest version', () => {
    expect(instanceStatusFingerprint({
      issues: [
        { code: 'SIDECAR_UNREACHABLE', sidecar: 'corum-auth', expectedBase: 'sidecar-chromium' },
        { code: 'SIDECAR_OUTDATED', sidecar: 'bourso-auth', expectedBase: 'sidecar-python' },
      ],
      update: { latestVersion: '1.2.0', releaseUrl: RELEASE },
    })).toBe('SIDECAR_OUTDATED:bourso-auth,SIDECAR_UNREACHABLE:corum-auth|1.2.0')
  })

  it('omits the version when there is no update, and ignores the expected base', () => {
    const issues = [{ code: 'SIDECAR_UNREACHABLE' as const, sidecar: 'corum-auth', expectedBase: 'sidecar-chromium' }]
    expect(instanceStatusFingerprint({ issues, update: null })).toBe('SIDECAR_UNREACHABLE:corum-auth')
    expect(instanceStatusFingerprint({
      issues: [{ ...issues[0], expectedBase: null }],
      update: null,
    })).toBe('SIDECAR_UNREACHABLE:corum-auth')
  })

  it('is just the version when nothing else is wrong', () => {
    expect(instanceStatusFingerprint({
      issues: [],
      update: { latestVersion: '1.2.0', releaseUrl: RELEASE },
    })).toBe('1.2.0')
  })
})

describe('InstanceStatusDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    telemetry(true, 'ENABLED')
    instanceStatus({ update: null, issues: [] })
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({
      demoMode: false,
      hasSeenSidebarStylePrompt: true,
      dismissedInstanceStatusFingerprint: null,
    })
  })

  it('never shows (nor even fetches) for a non-admin', async () => {
    useAuthStore.setState({ user: MEMBER })
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.getInstanceStatus).not.toHaveBeenCalled()
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('stays hidden in demo mode', async () => {
    useAppStore.setState({ demoMode: true })
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(api.getInstanceStatus).not.toHaveBeenCalled()
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('stays closed when there are no issues and no update', async () => {
    renderDialog()
    await waitFor(() => expect(api.getInstanceStatus).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('opens on issues and shows the sidecar name and its expected base', async () => {
    instanceStatus({
      update: null,
      issues: [ISSUE, { code: 'SIDECAR_UNREACHABLE', sidecar: 'amex-auth', expectedBase: null }],
    })
    renderDialog()
    expect(await screen.findByText('admin.instanceStatus.title')).toBeInTheDocument()
    expect(screen.getByText('corum-auth')).toBeInTheDocument()
    expect(screen.getByText('amex-auth')).toBeInTheDocument()
    expect(screen.getByText('sidecar-chromium')).toBeInTheDocument()
    expect(screen.getByText('admin.instanceStatus.unreachableWithBase')).toBeInTheDocument()
    expect(screen.getByText('admin.instanceStatus.unreachable')).toBeInTheDocument()
    expect(screen.getByText('docker compose pull')).toBeInTheDocument()
    expect(screen.getByText('docker compose up -d')).toBeInTheDocument()
    expect(screen.getByText('docker compose up -d --build')).toBeInTheDocument()
  })

  it('opens on an update alone, with the versions and the release link', async () => {
    instanceStatus({
      currentVersion: '1.1.0',
      update: { latestVersion: '1.2.0', releaseUrl: RELEASE },
      issues: [],
    })
    renderDialog()
    expect(await screen.findByText('1.1.0')).toBeInTheDocument()
    expect(screen.getByText('1.2.0')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'admin.instanceStatus.releaseNotes' })
    expect(link).toHaveAttribute('href', RELEASE)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })

  it('stays closed when this fingerprint was already dismissed', async () => {
    instanceStatus({ update: null, issues: [ISSUE] })
    useAppStore.setState({ dismissedInstanceStatusFingerprint: 'SIDECAR_UNREACHABLE:corum-auth' })
    renderDialog()
    await waitFor(() => expect(api.getInstanceStatus).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('opens again when a new release arrives after dismissal', async () => {
    instanceStatus({
      update: { latestVersion: '1.3.0', releaseUrl: RELEASE },
      issues: [ISSUE],
    })
    useAppStore.setState({
      dismissedInstanceStatusFingerprint: 'SIDECAR_UNREACHABLE:corum-auth|1.2.0',
    })
    renderDialog()
    expect(await screen.findByText('1.3.0')).toBeInTheDocument()
  })

  it('stays closed while telemetry consent is still pending', async () => {
    telemetry(true, 'UNSET')
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    await waitFor(() => expect(api.getInstanceStatus).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('waits for the sidebar prompt so two modals never stack', async () => {
    useAppStore.setState({ hasSeenSidebarStylePrompt: false })
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('forgets the dismissal once the status is clean, so a recurring problem alerts again', async () => {
    useAppStore.setState({ dismissedInstanceStatusFingerprint: 'SIDECAR_UNREACHABLE:corum-auth' })
    renderDialog()
    await waitFor(() => expect(useAppStore.getState().dismissedInstanceStatusFingerprint).toBeNull())
  })

  it('keeps the dismissal while the same problem is still there', async () => {
    useAppStore.setState({ dismissedInstanceStatusFingerprint: 'SIDECAR_UNREACHABLE:corum-auth' })
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    await waitFor(() => expect(api.getInstanceStatus).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 20))
    expect(useAppStore.getState().dismissedInstanceStatusFingerprint).toBe('SIDECAR_UNREACHABLE:corum-auth')
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
  })

  it('stores the fingerprint on Later, including across a reload', async () => {
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    fireEvent.click(await screen.findByText('admin.instanceStatus.later'))
    expect(useAppStore.getState().dismissedInstanceStatusFingerprint).toBe('SIDECAR_UNREACHABLE:corum-auth')
    expect(screen.queryByText('admin.instanceStatus.title')).not.toBeInTheDocument()
    const stored = JSON.parse(localStorage.getItem('picsou-app') ?? '{}')
    expect(stored.state.dismissedInstanceStatusFingerprint).toBe('SIDECAR_UNREACHABLE:corum-auth')
  })

  it('stores the same fingerprint when Escape closes the dialog', async () => {
    instanceStatus({ update: null, issues: [ISSUE] })
    renderDialog()
    fireEvent.keyDown(await screen.findByRole('dialog'), { key: 'Escape' })
    await waitFor(() =>
      expect(useAppStore.getState().dismissedInstanceStatusFingerprint).toBe('SIDECAR_UNREACHABLE:corum-auth'),
    )
  })
})
