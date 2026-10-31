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

const { CmesPanel } = await import('./CmesPanel')

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
  return render(<CmesPanel />, { wrapper: Wrapper })
}

async function reachSmsCode() {
  fireEvent.change(await screen.findByLabelText('sync.cmes.login'), { target: { value: 'saver' } })
  fireEvent.change(screen.getByLabelText('sync.cmes.password'), { target: { value: 'pw' } })
  fireEvent.click(screen.getByRole('button', { name: 'sync.cmes.connect' }))
  return screen.findByLabelText('sync.cmes.otpCode')
}

describe('CmesPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockResolvedValue({ data: NO_SESSION })
  })

  /** The sidecar keeps the attempt after a wrong SMS code: the saver types it again. */
  it('stays on the code form with the same attempt after a wrong SMS code', async () => {
    apiPost.mockImplementation((url: string, body: { code?: string }) => {
      if (url === '/cmes/auth/initiate') {
        return Promise.resolve({ data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' } })
      }
      if (url === '/cmes/auth/complete' && body.code === '000000') return Promise.reject(apiError('INVALID_OTP'))
      return Promise.resolve({ data: { ...NO_SESSION, isActive: true, syncStatus: 'QUEUED' } })
    })
    renderPanel()

    fireEvent.change(await reachSmsCode(), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.cmes.validate' }))

    expect(await screen.findByText('sync.cmes.errors.invalidCode')).toBeInTheDocument()
    const otp = screen.getByLabelText('sync.cmes.otpCode')
    expect(otp).toHaveValue('')

    fireEvent.change(otp, { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.cmes.validate' }))
    await waitFor(() =>
      expect(apiPost).toHaveBeenLastCalledWith('/cmes/auth/complete', { processId: 'p1', code: '123456' }))
  })

  it('drops the attempt on any other second-factor error', async () => {
    apiPost.mockImplementation((url: string) =>
      url === '/cmes/auth/initiate'
        ? Promise.resolve({ data: { processId: 'p1', mfaRequired: true, mfaType: 'SMS' } })
        : Promise.reject(apiError('AUTH_ATTEMPT_EXPIRED')))
    renderPanel()

    fireEvent.change(await reachSmsCode(), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'sync.cmes.validate' }))

    expect(await screen.findByText('sync.cmes.errors.authAttemptExpired')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.cmes.otpCode')).not.toBeInTheDocument()
  })
})
