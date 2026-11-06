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

// Key-echo translator: assertions target i18n keys, locale content is covered by locales-parity.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { NatixisPanel } = await import('./NatixisPanel')

const NO_SESSION = {
  isActive: false, syncStatus: 'IDLE', lastSyncError: null,
  lastSyncStartedAt: null, lastSyncCompletedAt: null,
}

function apiError(code: string) {
  return Object.assign(new Error(code), { response: { status: 400, data: { code } } })
}

function renderPanel() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  return render(<NatixisPanel />, { wrapper: Wrapper })
}

async function reachSmsCode() {
  fireEvent.change(await screen.findByLabelText('sync.natixis.login'), { target: { value: 'saver' } })
  fireEvent.change(screen.getByLabelText('sync.natixis.password'), { target: { value: 'pw' } })
  fireEvent.click(screen.getByRole('button', { name: 'sync.natixis.connect' }))
  return screen.findByLabelText('sync.natixis.otpCode')
}

describe('NatixisPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockResolvedValue({ data: NO_SESSION })
  })

  /** The sidecar keeps the attempt after a wrong SMS code: the saver types it again. */
  it('stays on the code form with the same attempt after a wrong SMS code', async () => {
    apiPost.mockImplementation((url: string, body: { code?: string }) => {
      if (url === '/natixis/auth/initiate') {
        return Promise.resolve({ data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' } })
      }
      if (url === '/natixis/auth/complete' && body.code === '000000') return Promise.reject(apiError('INVALID_OTP'))
      return Promise.resolve({ data: { ...NO_SESSION, isActive: true, syncStatus: 'QUEUED' } })
    })
    renderPanel()

    fireEvent.change(await reachSmsCode(), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.natixis.validate' }))

    expect(await screen.findByText('sync.natixis.errors.invalidCode')).toBeInTheDocument()
    const otp = screen.getByLabelText('sync.natixis.otpCode')
    expect(otp).toHaveValue('')

    fireEvent.change(otp, { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.natixis.validate' }))
    await waitFor(() =>
      expect(apiPost).toHaveBeenLastCalledWith('/natixis/auth/complete', { processId: 'p1', code: '123456' }))
  })

  it('shows the sync as running until the queued import finishes', async () => {
    const active = { ...NO_SESSION, isActive: true, syncStatus: 'SUCCESS', lastSyncCompletedAt: '2026-11-06T10:00:00Z' }
    let status = active
    apiGet.mockImplementation(() => Promise.resolve({ data: status }))
    apiPost.mockImplementation((url: string) => {
      if (url === '/natixis/sync') {
        status = { ...active, syncStatus: 'RUNNING' }
        return Promise.resolve({ data: { ...active, syncStatus: 'QUEUED' } })
      }
      return Promise.reject(new Error(url))
    })
    renderPanel()

    fireEvent.click(await screen.findByRole('button', { name: 'sync.natixis.sync' }))

    const running = await screen.findByRole('button', { name: 'sync.natixis.syncing' })
    expect(running).toBeDisabled()
  })

  it('drops the attempt on any other second-factor error', async () => {
    apiPost.mockImplementation((url: string) =>
      url === '/natixis/auth/initiate'
        ? Promise.resolve({ data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' } })
        : Promise.reject(apiError('AUTH_ATTEMPT_EXPIRED')))
    renderPanel()

    fireEvent.change(await reachSmsCode(), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.natixis.validate' }))

    expect(await screen.findByText('sync.natixis.errors.authAttemptExpired')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.natixis.otpCode')).not.toBeInTheDocument()
  })
})
