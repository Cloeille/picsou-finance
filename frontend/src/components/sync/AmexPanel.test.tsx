import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

// The Radix checkbox measures itself; jsdom has no ResizeObserver.
vi.stubGlobal('ResizeObserver', class {
  observe() {}
  unobserve() {}
  disconnect() {}
})

// Key-echo translator: assertions target i18n keys, locale content is covered by locales-parity.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { AmexPanel } = await import('./AmexPanel')

const idle = {
  isActive: false, syncStatus: 'IDLE', lastSyncStartedAt: null, lastSyncCompletedAt: null,
  lastSyncError: null, credentialsRemembered: false,
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<AmexPanel />, { wrapper: Wrapper })
}

async function fillLogin() {
  fireEvent.change(await screen.findByLabelText('sync.amex.login'), { target: { value: 'synthetic-login' } })
  fireEvent.change(screen.getByLabelText('sync.amex.password'), { target: { value: 'synthetic-secret' } })
}

describe('AmexPanel remembered credentials', () => {
  beforeEach(() => {
    apiGet.mockReset().mockResolvedValue({ data: idle })
    apiPost.mockReset().mockResolvedValue({ data: { processId: 'p1', mfaRequired: true, mfaType: 'OTP' } })
    apiDelete.mockReset()
  })

  it('offers the remember checkbox, unticked by default', async () => {
    renderPanel()
    const checkbox = await screen.findByRole('checkbox', { name: 'sync.amex.remember' })
    expect(checkbox).not.toBeChecked()
  })

  it('sends remember=false when the box is left unticked', async () => {
    renderPanel()
    await fillLogin()
    fireEvent.click(screen.getByRole('button', { name: 'sync.amex.connect' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amex/auth/initiate', {
      login: 'synthetic-login', password: 'synthetic-secret', method: 'sms', remember: false,
    }))
  })

  it('sends remember=true when the box is ticked', async () => {
    renderPanel()
    await fillLogin()
    fireEvent.click(screen.getByRole('checkbox', { name: 'sync.amex.remember' }))
    fireEvent.click(screen.getByRole('button', { name: 'sync.amex.connect' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/amex/auth/initiate', {
      login: 'synthetic-login', password: 'synthetic-secret', method: 'sms', remember: true,
    }))
  })

  it('says in the status card that the credentials are remembered', async () => {
    apiGet.mockResolvedValue({ data: { ...idle, isActive: true, syncStatus: 'SUCCESS', credentialsRemembered: true } })
    renderPanel()
    expect(await screen.findByText('sync.amex.credentialsRemembered')).toBeInTheDocument()
  })
})
