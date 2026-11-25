import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PICSOU_UPDATE_COMMAND } from '@/lib/whats-new'

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
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', resolvedLanguage: 'en' },
  }),
}))

const { getInstanceStatus } = vi.hoisted(() => ({
  getInstanceStatus: vi.fn(),
}))

vi.mock('@/features/admin/api', () => ({
  adminApi: {
    getInstanceStatus,
  },
}))

const { WhatsNewDialog } = await import('./WhatsNewDialog')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const ADMIN = {
  username: 'admin',
  role: 'ADMIN' as const,
  memberId: 1,
  displayName: 'Admin',
}

function renderDialog() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={qc}>
      <WhatsNewDialog open onOpenChange={() => undefined} />
    </QueryClientProvider>,
  )
}

describe('WhatsNewDialog', () => {
  beforeEach(() => {
    getInstanceStatus.mockReset()
    getInstanceStatus.mockResolvedValue({
      update: {
        currentVersion: '1.2.0',
        latestVersion: '1.3.0',
        releaseUrl: 'https://github.com/Cloeille/picsou-finance/releases/tag/v1.3.0',
      },
      warnings: [],
    })
    useAuthStore.setState({ user: ADMIN, isAuthenticated: true })
    useAppStore.setState({ demoMode: false, desktopMode: false })
  })

  it('shows Docker update instructions and command outside Desktop Mode', async () => {
    useAppStore.setState({ desktopMode: false })
    renderDialog()

    expect(await screen.findByText('whatsNew.updateAvailable')).toBeInTheDocument()
    expect(screen.getByText('whatsNew.updateInstructions')).toBeInTheDocument()
    expect(screen.getByText(PICSOU_UPDATE_COMMAND)).toBeInTheDocument()
  })

  it('hides Docker update instructions and command in Desktop Mode', async () => {
    useAppStore.setState({ desktopMode: true })
    renderDialog()

    expect(await screen.findByText('whatsNew.updateAvailable')).toBeInTheDocument()
    expect(screen.queryByText('whatsNew.updateInstructions')).not.toBeInTheDocument()
    expect(screen.queryByText(PICSOU_UPDATE_COMMAND)).not.toBeInTheDocument()
  })
})
