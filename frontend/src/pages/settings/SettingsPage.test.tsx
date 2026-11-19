import '@testing-library/jest-dom'
import { render, screen } from '@testing-library/react'
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
vi.mock('@/stores/app-store', () => ({
  useAppStore: () => ({
    dateFormat: 'locale',
    setDateFormat: vi.fn(),
    sidebarStyle: 'current',
    setSidebarStyle: vi.fn(),
    hideAmounts: false,
    setHideAmounts: vi.fn(),
  }),
}))
vi.mock('@/features/auth/hooks', () => ({
  useLogout: () => ({ mutate: vi.fn() }),
}))
vi.mock('@/components/shared/PageHeader', () => ({
  PageHeader: ({ title }: { title: string }) => <h1>{title}</h1>,
}))
vi.mock('@/components/ui/card', () => {
  const Part = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  return {
    Card: Part,
    CardContent: Part,
    CardDescription: Part,
    CardHeader: Part,
    CardTitle: Part,
  }
})
vi.mock('@/components/ui/button', () => ({
  Button: ({ children }: { children?: React.ReactNode }) => <button>{children}</button>,
}))
vi.mock('@/components/ui/input', () => ({
  Input: () => <input />,
}))
vi.mock('@/components/ui/label', () => ({
  Label: ({ children }: { children?: React.ReactNode }) => <label>{children}</label>,
}))
vi.mock('./security/SecuritySection', () => ({ SecuritySection: () => null }))
vi.mock('./sections/AccessKeysSection', () => ({ AccessKeysSection: () => null }))
vi.mock('./sections/ProfileSection', () => ({ ProfileSection: () => null }))
vi.mock('./sections/ConnectedAppsSection', () => ({ ConnectedAppsSection: () => null }))

const { SettingsPage } = await import('./SettingsPage')

describe('SettingsPage', () => {
  it('links to the canonical GitHub repository', () => {
    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>,
    )

    const link = screen.getByRole('link', {
      name: 'github.com/Cloeille/picsou-finance',
    })
    expect(link).toHaveAttribute(
      'href',
      'https://github.com/Cloeille/picsou-finance',
    )
  })
})
