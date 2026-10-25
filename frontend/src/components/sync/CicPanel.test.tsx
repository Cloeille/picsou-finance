import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

const { CicPanel } = await import('./CicPanel')

const status = (isActive: boolean) => ({
  data: { isActive, syncStatus: isActive ? 'SUCCESS' : 'IDLE', lastSyncCompletedAt: null, lastSyncError: null },
})
// What axios rejects with when the connection drops before any response.
const NETWORK_ERROR = Object.assign(new Error('Network Error'), { code: 'ERR_NETWORK' })

function renderPanel(onConnected = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<CicPanel onConnected={onConnected} />, { wrapper: Wrapper })
  return onConnected
}

async function startMobileConfirmation() {
  fireEvent.change(await screen.findByLabelText('sync.cic.login'), { target: { value: '12345678' } })
  fireEvent.change(screen.getByLabelText('sync.cic.password'), { target: { value: 'secret' } })
  fireEvent.click(screen.getByRole('button', { name: 'sync.cic.connect' }))
  await screen.findByText('sync.cic.appValidationPrompt')
}

describe('CicPanel mobile confirmation', () => {
  let active: boolean

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    active = false
    apiGet.mockReset().mockImplementation(async () => status(active))
    apiPost.mockReset().mockImplementation(async (url: string) => {
      if (url === '/cic/auth/initiate') return { data: { processId: 'p1', mfaRequired: true, mfaType: 'APP_PUSH' } }
      throw NETWORK_ERROR
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('keeps waiting when the completion request is lost, then shows the session it opened', async () => {
    const onConnected = renderPanel()
    await startMobileConfirmation()

    expect(screen.queryByText('sync.cic.errors.serverError')).not.toBeInTheDocument()
    active = true
    await act(() => vi.advanceTimersByTimeAsync(3_000))

    expect(await screen.findByText('sync.cic.sessionActive')).toBeInTheDocument()
    expect(onConnected).toHaveBeenCalledOnce()
  })

  it('gives up once the confirmation window is over', async () => {
    renderPanel()
    await startMobileConfirmation()

    await act(() => vi.advanceTimersByTimeAsync(153_000))

    expect(await screen.findByText('sync.cic.errors.appValidationTimeout')).toBeInTheDocument()
  })

  it('still reports an answer from the backend as such', async () => {
    apiPost.mockImplementation(async (url: string) => {
      if (url === '/cic/auth/initiate') return { data: { processId: 'p1', mfaRequired: true, mfaType: 'APP_PUSH' } }
      throw { response: { status: 401, data: { code: 'APP_VALIDATION_CANCELLED' } } }
    })
    renderPanel()
    await startMobileConfirmation().catch(() => undefined)

    expect(await screen.findByText('sync.cic.errors.appValidationCancelled')).toBeInTheDocument()
  })
})
