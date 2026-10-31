import '@testing-library/jest-dom'
import { act, cleanup, render, screen, within } from '@testing-library/react'
import { Outlet, RouterProvider } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/components/layout/AppLayout', () => ({
  AppLayout: () => (
    <main data-testid="app-shell">
      <Outlet />
    </main>
  ),
}))

const { router } = await import('./routes')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const USER = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

async function renderAt(path: string) {
  await act(() => router.navigate(path))
  render(<RouterProvider router={router} />)
}

describe('error routes', () => {
  beforeEach(() => {
    useAppStore.setState({ demoMode: false })
    useAuthStore.setState({ user: null, isAuthenticated: false })
  })
  afterEach(cleanup)

  it.each([
    ['/does-not-exist', '404'],
    ['/error/403', '403'],
    ['/error/500', '500'],
  ])('renders %s inside the app shell for a signed-in user', async (path, code) => {
    useAuthStore.setState({ user: USER, isAuthenticated: true })

    await renderAt(path)

    const heading = await screen.findByRole('heading', { name: code })
    expect(within(screen.getByTestId('app-shell')).getByRole('heading', { name: code })).toBe(heading)
  })

  it('renders the 404 without the app shell for a signed-out visitor', async () => {
    await renderAt('/does-not-exist')

    expect(await screen.findByRole('heading', { name: '404' })).toBeInTheDocument()
    expect(screen.queryByTestId('app-shell')).not.toBeInTheDocument()
  })

  it.each(['/does-not-exist', '/error/403', '/error/500'])(
    '%s offers back to the dashboard and back',
    async (path) => {
      await renderAt(path)

      expect(await screen.findByRole('link', { name: 'error.backToDashboard' })).toHaveAttribute('href', '/')
      expect(screen.getByRole('button', { name: 'common.back' })).toBeInTheDocument()
    },
  )
})
