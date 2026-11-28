import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import fr from '@/i18n/locales/fr.json'
import en from '@/i18n/locales/en.json'
import es from '@/i18n/locales/es.json'
import de from '@/i18n/locales/de.json'

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
vi.stubGlobal('matchMedia', () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: vi.fn() },
  }),
}))

const { getStatus, desktopQuickStart } = vi.hoisted(() => ({
  getStatus: vi.fn(),
  desktopQuickStart: vi.fn(),
}))

vi.mock('@/features/setup/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/setup/api')>()
  return {
    ...actual,
    setupApi: {
      ...actual.setupApi,
      getStatus,
      desktopQuickStart,
    },
  }
})

const { SetupStepIntro } = await import('./SetupStepIntro')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

function renderIntro() {
  const qc = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/setup']}>
        <Routes>
          <Route path="/setup" element={<SetupStepIntro />} />
          <Route path="/" element={<div>dashboard-landed</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('Desktop 1-step welcome onboarding', () => {
  beforeEach(() => {
    getStatus.mockReset()
    desktopQuickStart.mockReset()
    useAuthStore.setState({ user: null, isAuthenticated: false })
    useAppStore.setState({
      desktopMode: false,
      desktopLockEnabled: false,
      legacyPostgresDetected: false,
      advancedMode: false,
    })
  })

  it('renders the 1-step desktop welcome and signs straight into the dashboard', async () => {
    getStatus.mockResolvedValue({
      state: 'PENDING_ADMIN',
      needsSetup: true,
      integrations: {},
      desktopMode: true,
      desktopLockEnabled: false,
    })
    desktopQuickStart.mockResolvedValue({
      username: 'camille',
      role: 'ADMIN',
      memberId: 1,
      displayName: 'Camille',
    })

    renderIntro()

    expect(await screen.findByText('desktop.onboarding.title')).toBeInTheDocument()
    expect(screen.queryByText('desktop.onboarding.legacyPostgresTitle')).not.toBeInTheDocument()
    const input = screen.getByLabelText('desktop.onboarding.nameLabel')
    fireEvent.change(input, { target: { value: '  Camille  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'desktop.onboarding.cta' }))

    await waitFor(() => expect(screen.getByText('dashboard-landed')).toBeInTheDocument())
    expect(desktopQuickStart).toHaveBeenCalledWith({ displayName: 'Camille' })
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
    expect(useAuthStore.getState().user?.username).toBe('camille')
    expect(useAppStore.getState().desktopMode).toBe(true)
    expect(useAppStore.getState().desktopLockEnabled).toBe(false)
  })

  it('omits displayName when left blank so the backend picks the localized default', async () => {
    getStatus.mockResolvedValue({
      state: 'PENDING_ADMIN',
      needsSetup: true,
      integrations: {},
      desktopMode: true,
      desktopLockEnabled: false,
    })
    desktopQuickStart.mockResolvedValue({
      username: 'mon-espace',
      role: 'ADMIN',
      memberId: 1,
      displayName: 'Mon espace',
    })

    renderIntro()

    await screen.findByText('desktop.onboarding.title')
    fireEvent.click(screen.getByRole('button', { name: 'desktop.onboarding.cta' }))

    await waitFor(() => expect(screen.getByText('dashboard-landed')).toBeInTheDocument())
    expect(desktopQuickStart).toHaveBeenCalledWith({ displayName: undefined })
  })

  it('shows a calm informational notice when a legacy postgres/ folder is detected', async () => {
    getStatus.mockResolvedValue({
      state: 'PENDING_ADMIN',
      needsSetup: true,
      integrations: {},
      desktopMode: true,
      desktopLockEnabled: false,
      legacyPostgresDetected: true,
    })

    renderIntro()

    expect(await screen.findByText('desktop.onboarding.legacyPostgresTitle')).toBeInTheDocument()
    expect(screen.getByText('desktop.onboarding.legacyPostgresBody')).toBeInTheDocument()
    expect(useAppStore.getState().legacyPostgresDetected).toBe(true)
  })

  it('offers to import an archive while the instance is on its first screen', async () => {
    getStatus.mockResolvedValue({
      state: 'PENDING_ADMIN',
      needsSetup: true,
      integrations: {},
      desktopMode: true,
      desktopLockEnabled: false,
    })

    renderIntro()

    const entry = await screen.findByRole('button', { name: 'setup.archive.entry' })
    expect(entry).toBeInTheDocument()
    // Below the primary action, never instead of it.
    const primary = screen.getByRole('button', { name: 'desktop.onboarding.cta' })
    expect(primary.compareDocumentPosition(entry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('hides the archive import once setup is past its first step', async () => {
    getStatus.mockResolvedValue({
      state: 'IN_PROGRESS',
      needsSetup: true,
      integrations: {},
      desktopMode: true,
      desktopLockEnabled: false,
    })

    renderIntro()

    await screen.findByText('desktop.onboarding.title')
    expect(screen.queryByRole('button', { name: 'setup.archive.entry' })).not.toBeInTheDocument()
  })

  it('never mentions the missing picsou export-sqlite CLI in any locale legacy Postgres notice', () => {
    for (const [lang, messages] of Object.entries({ fr, en, es, de })) {
      const body = messages.desktop.onboarding.legacyPostgresBody
      expect(body, `${lang} must not reference export-sqlite`).not.toContain('export-sqlite')
      expect(body, `${lang} must mention postgres/`).toContain('postgres/')
      expect(body, `${lang} must mention SQLite`).toContain('SQLite')
    }
  })
})
