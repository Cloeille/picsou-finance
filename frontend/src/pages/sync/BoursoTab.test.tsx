import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
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

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

const { BoursoTab } = await import('./BoursoTab')

const DISCONNECTED = {
  isActive: false,
  syncStatus: 'IDLE',
  lastSyncStartedAt: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
}

function renderTab() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  render(<BoursoTab />, { wrapper: Wrapper })
}

async function signIn(customerId = '12345678', password = '123456') {
  fireEvent.change(await screen.findByLabelText('sync.bourso.customerId'), {
    target: { value: customerId },
  })
  fireEvent.change(await screen.findByLabelText('sync.bourso.password'), {
    target: { value: password },
  })
  fireEvent.click(screen.getByText('sync.bourso.connect'))
}

describe('BoursoTab', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: DISCONNECTED })
  })

  /**
   * The app push has nothing to type: the completion call goes out on its own
   * and stays open while the user approves on their phone.
   */
  it('waits on the app without asking for a code', async () => {
    apiPost
      .mockResolvedValueOnce({ data: { processId: 'p1', mfaRequired: true, mfaType: 'APP_PUSH' } })
      .mockReturnValueOnce(new Promise(() => {}))

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.bourso.appValidationPrompt')).toBeInTheDocument()
    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bourso/auth/complete', { processId: 'p1' }),
    )
  })

  it('signs in directly when BoursoBank asks for no second factor', async () => {
    apiPost.mockResolvedValueOnce({
      data: { processId: null, mfaRequired: false, mfaType: null },
    })

    renderTab()
    await signIn()

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bourso/auth/initiate', {
        customerId: '12345678',
        password: '123456',
      }),
    )
    expect(screen.queryByText('sync.bourso.appValidationPrompt')).not.toBeInTheDocument()
  })

  /**
   * BoursoBank's virtual keyboard can only encode digits, and a stray character
   * would spend a login attempt on a password the user never typed.
   */
  it('keeps the credentials numeric', async () => {
    apiPost.mockResolvedValueOnce({
      data: { processId: null, mfaRequired: false, mfaType: null },
    })

    renderTab()
    await signIn('12ab34', '56cd78')

    await vi.waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/bourso/auth/initiate', {
        customerId: '1234',
        password: '5678',
      }),
    )
  })

  it('translates an unsupported second factor instead of leaking its message', async () => {
    apiPost.mockRejectedValueOnce({
      response: {
        status: 422,
        data: { code: 'MFA_TYPE_UNSUPPORTED', detail: 'raw upstream detail' },
      },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.bourso.errors.mfaTypeUnsupported')).toBeInTheDocument()
    expect(screen.queryByText('raw upstream detail')).not.toBeInTheDocument()
  })

  it('tells the user to acknowledge the fraud notice instead of blaming the password', async () => {
    apiPost.mockRejectedValueOnce({
      response: {
        status: 422,
        data: { code: 'FRAUD_ACK_REQUIRED', detail: 'raw upstream detail' },
      },
    })

    renderTab()
    await signIn()

    expect(await screen.findByText('sync.bourso.errors.fraudAckRequired')).toBeInTheDocument()
    expect(screen.queryByText('raw upstream detail')).not.toBeInTheDocument()
  })

  it('reports a failed background sync from the polled status alone', async () => {
    apiGet.mockResolvedValue({
      data: {
        ...DISCONNECTED,
        isActive: true,
        syncStatus: 'FAILED',
        lastSyncError: 'PORTFOLIO_INCOMPLETE',
      },
    })

    renderTab()

    expect(
      await screen.findByText('sync.bourso.errors.portfolioIncomplete'),
    ).toBeInTheDocument()
  })

  it('offers sync and disconnect once a session is active', async () => {
    apiGet.mockResolvedValue({ data: { ...DISCONNECTED, isActive: true } })
    apiDelete.mockResolvedValue({ data: null })

    renderTab()

    fireEvent.click(await screen.findByText('sync.bourso.clearSession'))

    await vi.waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/bourso/session'))
  })
})
