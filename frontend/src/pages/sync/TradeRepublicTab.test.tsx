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
  api: {
    get: apiGet,
    post: apiPost,
    delete: apiDelete,
  },
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}))

const { TradeRepublicTab } = await import('./TradeRepublicTab')

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderTab() {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={makeClient()}>
        {children}
      </QueryClientProvider>
    )
  }

  render(<TradeRepublicTab />, { wrapper: Wrapper })
}

async function fillCredentials() {
  fireEvent.change(await screen.findByLabelText('sync.tr.phone'), {
    target: { value: '+33612345678' },
  })
  fireEvent.change(screen.getByLabelText('sync.tr.pin'), {
    target: { value: '1234' },
  })
}

describe('TradeRepublicTab authentication flow', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    apiGet.mockResolvedValue({ data: { isActive: false } })
  })

  it('keeps the phone and PIN form visible when initiation fails', async () => {
    apiPost.mockRejectedValue({
      response: { status: 500, data: { detail: 'PIN_INVALID' } },
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidPin')).toBeInTheDocument()
    expect(screen.getByLabelText('sync.tr.phone')).toBeInTheDocument()
    expect(screen.queryByLabelText('sync.tr.tan')).not.toBeInTheDocument()
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/initiate', {
      phoneNumber: '+33612345678',
      pin: '1234',
    })
  })

  it('moves focus to the verification-code input once the PIN is accepted', async () => {
    apiPost.mockResolvedValue({ data: { processId: 'process-123' } })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    const tanInput = await screen.findByLabelText('sync.tr.tan')
    await waitFor(() => expect(tanInput).toHaveFocus())
  })

  it('keeps the verification-code step visible when TAN completion fails', async () => {
    apiPost.mockImplementation((url: string) => {
      if (url === '/tr/auth/initiate') {
        return Promise.resolve({ data: { processId: 'process-123' } })
      }
      if (url === '/tr/auth/complete') {
        return Promise.reject({
          response: { status: 500, data: { detail: 'VALIDATION_CODE_INVALID' } },
        })
      }
      return Promise.reject(new Error(`Unexpected POST ${url}`))
    })

    renderTab()
    await fillCredentials()
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    fireEvent.change(await screen.findByLabelText('sync.tr.tan'), {
      target: { value: '9876' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'sync.tr.connect' }))

    expect(await screen.findByText('sync.tr.errors.invalidTan')).toBeInTheDocument()
    const tanInput = screen.getByLabelText('sync.tr.tan')
    expect(tanInput).toBeInTheDocument()
    await waitFor(() => expect(tanInput).toHaveValue(''))
    expect(apiPost).toHaveBeenCalledWith('/tr/auth/complete', {
      processId: 'process-123',
      tan: '9876',
    })
  })
})
