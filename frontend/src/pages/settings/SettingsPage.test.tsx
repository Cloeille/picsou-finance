import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

vi.stubGlobal('localStorage', {
  getItem: () => null,
  setItem: () => undefined,
  removeItem: () => undefined,
  clear: () => undefined,
})
vi.stubGlobal('matchMedia', () => ({
  matches: false,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'en', changeLanguage: vi.fn() },
  }),
}))
vi.mock('@/stores/auth-store', () => ({
  useAuthStore: (selector: (state: { user: null; setUsername: () => void }) => unknown) =>
    selector({ user: null, setUsername: vi.fn() }),
}))
vi.mock('@/stores/app-store', () => {
  const state = {
    demoMode: false,
    dateFormat: 'locale',
    setDateFormat: vi.fn(),
    hideAmounts: false,
    setHideAmounts: vi.fn(),
    profilePhotos: {},
    setProfilePhoto: vi.fn(),
  }
  return { useAppStore: Object.assign(() => state, { getState: () => state }) }
})
vi.mock('@/hooks/use-money', () => ({
  useMoney: () => ({ format: (value: number) => String(value) }),
}))
vi.mock('@/features/auth/hooks', () => ({
  useLogout: () => ({ mutate: vi.fn() }),
}))
vi.mock('@/components/shared/WhatsNewDialog', () => ({ WhatsNewDialog: () => null }))
vi.mock('./security/SecuritySection', () => ({ SecuritySection: () => null }))
vi.mock('./sections/AccessKeysSection', () => ({ AccessKeysSection: () => null }))
vi.mock('./sections/ProfileSection', () => ({ ProfileSection: () => null }))
vi.mock('./sections/ConnectedAppsSection', () => ({ ConnectedAppsSection: () => null }))

const { SettingsPage } = await import('./SettingsPage')

describe('SettingsPage', () => {
  it('links the About section to the canonical GitHub repository', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={['/settings?tab=system']}>
          <SettingsPage />
        </MemoryRouter>
      </QueryClientProvider>,
    )

    const repository = 'https://github.com/Cloeille/picsou-finance'
    expect(screen.getByRole('link', { name: /Cloeille\/picsou-finance/ })).toHaveAttribute(
      'href',
      repository,
    )
    expect(screen.getByRole('link', { name: 'whatsNew.starGithub' })).toHaveAttribute(
      'href',
      repository,
    )
  })
})
