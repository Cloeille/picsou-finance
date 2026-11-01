import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { RouterProvider } from 'react-router'
import type { AxiosAdapter } from 'axios'
import { expect, it, vi } from 'vitest'

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
    i18n: { language: 'fr', changeLanguage: () => {} },
  }),
}))

// jsdom cannot load another document; it reports each attempt as a jsdomError instead.
declare const jsdom: {
  virtualConsole: { on(event: 'jsdomError', listener: (error: Error) => void): void }
}

const { router } = await import('./routes')
const { api } = await import('@/lib/api-client')
const { useAuthStore } = await import('@/stores/auth-store')
const { useAppStore } = await import('@/stores/app-store')

const USER = { username: 'alice', role: 'ADMIN' as const, memberId: 1, displayName: 'Alice' }

it('stays on the error page when a query of the real app shell fails there', async () => {
  const navigations: string[] = []
  jsdom.virtualConsole.on('jsdomError', (error) => {
    if (error.message.startsWith('Not implemented: navigation')) navigations.push(error.message)
  })
  const requested: string[] = []
  const failingBackend: AxiosAdapter = async (config) => {
    requested.push(config.url ?? '')
    return Promise.reject({ config, response: { status: 500, data: null } })
  }
  api.defaults.adapter = failingBackend
  useAppStore.setState({ demoMode: false, hasSeenSidebarStylePrompt: true })
  useAuthStore.getState().login(USER)

  await act(() => router.navigate('/error/500'))
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )

  const heading = await screen.findByRole('heading', { name: '500' })
  expect(within(screen.getByRole('main')).getByRole('heading', { name: '500' })).toBe(heading)
  await waitFor(() => expect(requested).toContain('/family/members'))
  await act(() => new Promise((resolve) => setTimeout(resolve, 50)))

  expect(navigations).toEqual([])
})
