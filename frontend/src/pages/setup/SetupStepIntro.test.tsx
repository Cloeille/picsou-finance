import '@testing-library/jest-dom/vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
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
vi.stubGlobal('matchMedia', () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
}))
vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: vi.fn() },
  }),
}))

// The greeting animation has nothing to do with the entry point: show the content directly.
vi.mock('./HelloGreeting', () => ({
  HelloGreeting: ({ onFinish }: { onFinish: () => void }) => (
    <button type="button" onClick={onFinish}>greeting-finish</button>
  ),
}))

const { getStatus } = vi.hoisted(() => ({ getStatus: vi.fn() }))
vi.mock('@/features/setup/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/setup/api')>()
  return { ...actual, setupApi: { ...actual.setupApi, getStatus } }
})

const { SetupStepIntro } = await import('./SetupStepIntro')
const { useAppStore } = await import('@/stores/app-store')

async function renderIntro() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/setup']}>
        <Routes>
          <Route path="/setup" element={<SetupStepIntro />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  fireEvent.click(await screen.findByText('greeting-finish'))
}

const statusOf = (over: Record<string, unknown> = {}) => ({
  state: 'PENDING_ADMIN',
  needsSetup: true,
  integrations: {},
  ...over,
})

describe('Docker wizard intro', () => {
  beforeEach(() => {
    getStatus.mockReset()
    useAppStore.setState({ demoMode: false, desktopMode: false })
  })

  it('offers the archive import under the primary action on a fresh instance', async () => {
    getStatus.mockResolvedValue(statusOf())

    await renderIntro()

    const entry = await screen.findByRole('button', { name: 'setup.archive.entry' })
    const primary = screen.getByRole('button', { name: 'setup.intro.cta' })
    expect(primary.compareDocumentPosition(entry) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('opens the import dialog from the entry point', async () => {
    getStatus.mockResolvedValue(statusOf())

    await renderIntro()
    fireEvent.click(await screen.findByRole('button', { name: 'setup.archive.entry' }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('setup.archive.title')).toBeInTheDocument()
  })

  it('hides the entry point once the wizard has started', async () => {
    getStatus.mockResolvedValue(statusOf({ state: 'IN_PROGRESS' }))

    await renderIntro()

    await screen.findByRole('button', { name: 'setup.intro.cta' })
    expect(screen.queryByRole('button', { name: 'setup.archive.entry' })).not.toBeInTheDocument()
  })

  it('hides the entry point in demo mode', async () => {
    useAppStore.setState({ demoMode: true })
    getStatus.mockResolvedValue(statusOf())

    await renderIntro()

    await screen.findByRole('button', { name: 'setup.intro.cta' })
    expect(screen.queryByRole('button', { name: 'setup.archive.entry' })).not.toBeInTheDocument()
  })
})
