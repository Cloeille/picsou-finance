import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import type { FamilyMemberItem } from '@/features/family/api'

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

const { listMembers } = vi.hoisted(() => ({ listMembers: vi.fn() }))

vi.mock('@/features/family/api', () => ({
  familyApi: {
    listMembers,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'fr', changeLanguage: () => {} },
  }),
}))

const { AppSidebar } = await import('./AppSidebar')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')
const { useProfileStore } = await import('@/stores/profile-store')

function member(overrides: Partial<FamilyMemberItem>): FamilyMemberItem {
  return {
    id: 2,
    displayName: 'Lou',
    avatarColor: '#2563eb',
    managed: true,
    hasLogin: false,
    activated: false,
    loginName: null,
    mfaEnabled: false,
    ...overrides,
  }
}

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderSidebar(queryClient = makeClient()) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{children}</MemoryRouter>
      </QueryClientProvider>
    )
  }

  render(<AppSidebar />, { wrapper: Wrapper })
  return queryClient
}

function openAccountMenu(name = 'nav.account') {
  fireEvent.pointerDown(screen.getByRole('button', { name }), {
    button: 0,
    ctrlKey: false,
  })
}

describe('AppSidebar profile switcher', () => {
  beforeEach(() => {
    listMembers.mockReset()
    listMembers.mockResolvedValue([])
    useAuthStore.getState().logout()
    useAppStore.getState().setDemoMode(false)
    useAppStore.getState().setHideAmounts(false)
    useProfileStore.getState().reset()
  })

  it('does not load family members for non-admin users, and hides profile switching from their account menu', async () => {
    useAuthStore.getState().login({ username: 'robin', role: 'MEMBER', memberId: 7, displayName: 'Robin' })

    renderSidebar()

    await screen.findByText('Robin')
    expect(screen.getByRole('link', { name: 'nav.settings' })).toHaveAttribute('href', '/settings')

    openAccountMenu()
    expect(await screen.findByRole('menuitem', { name: 'settings.logout' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitemradio')).not.toBeInTheDocument()
    expect(listMembers).not.toHaveBeenCalled()
  })

  it('lets an admin switch to a managed profile and back to their own account', async () => {
    listMembers.mockResolvedValue([
      member({ id: 2, displayName: 'Lou', managed: true, activated: false }),
      member({ id: 3, displayName: 'Maya', managed: true, hasLogin: true, activated: true }),
    ])
    useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloe' })
    const queryClient = renderSidebar()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await screen.findByText('Chloe')
    expect(listMembers).toHaveBeenCalledTimes(1)

    openAccountMenu()
    expect(await screen.findByRole('menuitemradio', { name: /Lou/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitemradio', { name: /Maya/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('menuitemradio', { name: /Lou/ }))

    await waitFor(() => expect(useProfileStore.getState().activeMemberId).toBe(2))
    expect(invalidateSpy).toHaveBeenCalled()
    expect(await screen.findByText('Lou')).toBeInTheDocument()

    openAccountMenu()
    fireEvent.click(await screen.findByRole('menuitemradio', { name: /Chloe/ }))

    await waitFor(() => expect(useProfileStore.getState().activeMemberId).toBeNull())
    expect(invalidateSpy).toHaveBeenCalledTimes(2)
  })
})

describe('AppSidebar classic style', () => {
  beforeEach(() => {
    listMembers.mockReset()
    listMembers.mockResolvedValue([])
    useAuthStore.getState().logout()
    useAppStore.getState().setDemoMode(false)
    useAppStore.getState().setSidebarStyle('classic')
    useAppStore.getState().setHideAmounts(false)
    useProfileStore.getState().reset()
  })

  it('labels the account trigger as the account (not profile switcher) for a non-admin, and hides profile switching', async () => {
    useAuthStore.getState().login({ username: 'robin', role: 'MEMBER', memberId: 7, displayName: 'Robin' })

    renderSidebar()

    await screen.findByText('Robin')
    expect(screen.getByRole('button', { name: 'nav.account' })).toBeInTheDocument()

    openAccountMenu('nav.account')
    expect(await screen.findByRole('menuitem', { name: 'settings.logout' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitemradio')).not.toBeInTheDocument()
    expect(listMembers).not.toHaveBeenCalled()
  })

  it('labels the account trigger as the profile switcher for an admin, and exposes profile choices as radio items', async () => {
    listMembers.mockResolvedValue([
      member({ id: 2, displayName: 'Lou', managed: true, activated: false }),
    ])
    useAuthStore.getState().login({ username: 'chloe', role: 'ADMIN', memberId: 1, displayName: 'Chloe' })
    renderSidebar()

    await screen.findByText('Chloe')
    expect(screen.getByRole('button', { name: 'nav.switchProfile' })).toBeInTheDocument()

    openAccountMenu('nav.switchProfile')
    expect(await screen.findByRole('menuitemradio', { name: /Lou/ })).toBeInTheDocument()
  })
})

describe('AppSidebar privacy toggle', () => {
  beforeEach(() => {
    listMembers.mockReset()
    listMembers.mockResolvedValue([])
    useAuthStore.getState().logout()
    useAppStore.getState().setDemoMode(false)
    useAppStore.getState().setSidebarStyle('current')
    useAppStore.getState().setHideAmounts(false)
    useProfileStore.getState().reset()
  })

  it('hides and reveals every amount in the app, and says which action it offers', () => {
    renderSidebar()

    // The label names the action, not the state -- the icon already carries the state.
    const button = screen.getByRole('button', { name: 'nav.hideAmounts' })
    expect(button).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(button)

    expect(useAppStore.getState().hideAmounts).toBe(true)
    const pressed = screen.getByRole('button', { name: 'nav.showAmounts' })
    expect(pressed).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(pressed)
    expect(useAppStore.getState().hideAmounts).toBe(false)
  })

  it('persists the choice, because a refresh mid-demo must not reveal anything', () => {
    renderSidebar()
    fireEvent.click(screen.getByRole('button', { name: 'nav.hideAmounts' }))

    // Written deliberately: `partialize` in app-store is an allowlist, so leaving the field out
    // fails silently -- no type error, no runtime error, the setting simply does not survive a
    // reload. Nothing else in the suite would notice.
    const stored = JSON.parse(localStorage.getItem('picsou-app') ?? '{}')
    expect(stored.state.hideAmounts).toBe(true)
  })
})
