import '@testing-library/jest-dom'
import { act, cleanup, render, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'sonner'
import { Toaster } from '@/components/ui/sonner'
import { applyTheme } from '@/lib/theme'

function setOsDarkMode(dark: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: dark && query === '(prefers-color-scheme: dark)',
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: () => false,
  }))
}

async function renderToaster() {
  const { container } = render(<Toaster />)
  act(() => { toast('Theme notification', { duration: Infinity }) })
  await waitFor(() => {
    expect(container.querySelector('[data-sonner-toaster]')).toBeInTheDocument()
  })
  return container.querySelector('[data-sonner-toaster]')
}

beforeEach(() => {
  document.documentElement.classList.remove('dark')
  vi.stubGlobal('localStorage', { setItem: vi.fn() })
  setOsDarkMode(false)
})

afterEach(() => {
  cleanup()
  toast.dismiss()
  document.documentElement.classList.remove('dark')
  vi.unstubAllGlobals()
})

describe('Toaster theme', () => {
  it.each([
    { theme: 'dark' as const, osDark: false },
    { theme: 'light' as const, osDark: true },
  ])('uses the app $theme palette when OS dark mode is $osDark', async ({ theme, osDark }) => {
    setOsDarkMode(osDark)
    applyTheme(theme)

    const toaster = await renderToaster()

    await waitFor(() => {
      expect(toaster).toHaveAttribute('data-sonner-theme', theme)
    })
  })

  it('follows live theme changes, including a return to the system palette', async () => {
    setOsDarkMode(true)
    applyTheme('system')
    const toaster = await renderToaster()

    await waitFor(() => expect(toaster).toHaveAttribute('data-sonner-theme', 'dark'))

    await act(async () => applyTheme('light'))
    await waitFor(() => expect(toaster).toHaveAttribute('data-sonner-theme', 'light'))

    await act(async () => applyTheme('dark'))
    await waitFor(() => expect(toaster).toHaveAttribute('data-sonner-theme', 'dark'))

    await act(async () => applyTheme('light'))
    await waitFor(() => expect(toaster).toHaveAttribute('data-sonner-theme', 'light'))

    await act(async () => applyTheme('system'))
    await waitFor(() => expect(toaster).toHaveAttribute('data-sonner-theme', 'dark'))
  })
})
