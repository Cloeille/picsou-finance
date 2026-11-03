import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete, toastSuccess } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  toastSuccess: vi.fn(),
}))

vi.mock('@/lib/api-client', () => ({
  api: { get: apiGet, post: apiPost, delete: apiDelete },
}))

// Key-echo translator: assertions target i18n keys, locale content is covered by locales-parity.
vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, vars?: { count?: number; label?: string }) =>
      vars?.label ? `${vars.label}:` : vars?.count != null ? `${key}:${vars.count}` : key,
  }),
}))

vi.mock('sonner', () => ({
  toast: { success: toastSuccess, error: vi.fn() },
}))

const { WisePanel } = await import('./WisePanel')

const DISCONNECTED = {
  data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null },
}

function connected(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      connected: true,
      connectionId: 1,
      status: 'CONNECTED',
      lastSyncedAt: '2026-11-03T08:30:00Z',
      maskedToken: '••••e1f0',
      ...overrides,
    },
  }
}

function renderPanel(props: { onConnected?: () => void } = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
  return render(<WisePanel {...props} />, { wrapper: Wrapper })
}

const connectButton = () => screen.getByRole('button', { name: 'sync.wise.connect' })

async function submitToken(value: string) {
  const input = await screen.findByLabelText('sync.wise.token')
  fireEvent.change(input, { target: { value } })
  fireEvent.click(connectButton())
  return input
}

describe('WisePanel', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    toastSuccess.mockReset()
  })

  describe('disconnected', () => {
    it('links to the Wise API token page and says what is imported', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const link = await screen.findByRole('link', { name: 'sync.wise.createToken' })
      expect(link).toHaveAttribute('href', 'https://wise.com/your-account/integrations-and-tools/api-tokens')
      expect(screen.getByText('sync.wise.intro')).toBeInTheDocument()
      expect(screen.getByText('sync.wise.scopeNote')).toBeInTheDocument()
    })

    it('keeps Connect disabled for an empty or whitespace-only token', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const input = await screen.findByLabelText('sync.wise.token')
      expect(connectButton()).toBeDisabled()

      fireEvent.change(input, { target: { value: '   ' } })
      expect(connectButton()).toBeDisabled()
      fireEvent.submit(input.closest('form')!)
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('connects with the trimmed token in one call, toasts the jar count and notifies onConnected', async () => {
      const onConnected = vi.fn()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockImplementation(() => {
        apiGet.mockResolvedValue(connected())
        return Promise.resolve({ data: [{ id: 1 }, { id: 2 }] })
      })

      renderPanel({ onConnected })
      await submitToken('  tok-xyz \n')

      await waitFor(() => expect(onConnected).toHaveBeenCalledTimes(1))
      expect(apiPost).toHaveBeenCalledTimes(1)
      expect(apiPost).toHaveBeenCalledWith('/wise/connect', { token: 'tok-xyz' })
      expect(toastSuccess).toHaveBeenCalledWith('sync.wise.syncedToast:2')
    })

    it.each([
      ['translates a refused token', { response: { status: 422, data: { detail: 'Wise refused the API token.', code: 'SESSION_EXPIRED' } } }, 'sync.wise.errors.tokenRefused'],
      ['translates a malformed token', { response: { status: 422, data: { detail: 'This does not look like a Wise API token.', code: 'INVALID_TOKEN' } } }, 'sync.wise.errors.invalidToken'],
      ['translates the Wise rate limit', { response: { status: 422, data: { detail: 'Wise is limiting requests right now.', code: 'RATE_LIMITED' } } }, 'sync.wise.errors.rateLimited'],
      ['shows an uncoded server detail', { response: { status: 422, data: { detail: 'Could not reach Wise. Try again in a moment.' } } }, 'Could not reach Wise. Try again in a moment.'],
      ['falls back on a network failure', new Error('Network Error'), 'sync.wise.errors.connectFailed'],
    ])('on a refused token, %s and keeps the form usable', async (_name, rejection, message) => {
      const onConnected = vi.fn()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockRejectedValue(rejection)

      renderPanel({ onConnected })
      const input = await submitToken('tok-bad')

      expect(await screen.findByText(message)).toBeInTheDocument()
      expect(onConnected).not.toHaveBeenCalled()
      expect(input).toHaveValue('tok-bad')
      expect(connectButton()).toBeEnabled()
    })
  })

  describe('connected', () => {
    it('shows the masked token, last sync date and sync button instead of the token form', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()

      expect(await screen.findByText('sync.wise.connected')).toBeInTheDocument()
      expect(screen.getByText('••••e1f0')).toBeInTheDocument()
      expect(screen.getByText(/^sync\.wise\.lastSync: .*2026/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'sync.wise.sync' })).toBeEnabled()
      expect(screen.queryByLabelText('sync.wise.token')).not.toBeInTheDocument()
    })

    it('shows the failure badge instead of "connected" when the last sync errored', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))

      renderPanel()

      expect(await screen.findByText('sync.wise.statusError')).toBeInTheDocument()
      expect(screen.queryByText('sync.wise.connected')).not.toBeInTheDocument()
    })

    it('syncs and toasts the jar count', async () => {
      apiGet.mockResolvedValue(connected())
      apiPost.mockResolvedValue({ data: [{ id: 1 }, { id: 2 }, { id: 3 }] })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.sync' }))

      await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('sync.wise.syncedToast:3'))
      expect(apiPost).toHaveBeenCalledWith('/wise/sync')
    })

    it('turns the badge to the failure state when a sync of a healthy connection fails', async () => {
      apiGet.mockResolvedValue(connected())
      apiPost.mockImplementation(() => {
        apiGet.mockResolvedValue(connected({ status: 'ERROR' }))
        return Promise.reject({ response: { status: 422, data: { detail: 'Wise refused the API token.' } } })
      })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.sync' }))

      expect(await screen.findByText('sync.wise.statusError')).toBeInTheDocument()
      expect(screen.queryByText('sync.wise.connected')).not.toBeInTheDocument()
      expect(screen.getByText('Wise refused the API token.')).toBeInTheDocument()
    })

    it('shows the sync error, then clears it after a later successful sync', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))
      apiPost
        .mockRejectedValueOnce({ response: { status: 422, data: { detail: 'Wise refused the API token.' } } })
        .mockResolvedValueOnce({ data: [] })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.sync' }))
      expect(await screen.findByText('Wise refused the API token.')).toBeInTheDocument()

      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.sync' }))
      await waitFor(() => expect(screen.queryByText('Wise refused the API token.')).not.toBeInTheDocument())
    })
  })

  describe('refused token', () => {
    it('shows why, hides Sync, and replaces the token through connect', async () => {
      apiGet.mockResolvedValue(connected({ status: 'REAUTH_REQUIRED' }))
      apiPost.mockImplementation(() => {
        apiGet.mockResolvedValue(connected())
        return Promise.resolve({ data: [{ id: 1 }] })
      })

      renderPanel()

      expect(await screen.findByText('sync.wise.statusTokenRefused')).toBeInTheDocument()
      expect(screen.getByText('sync.wise.replaceTokenHint')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'sync.wise.sync' })).not.toBeInTheDocument()
      fireEvent.change(screen.getByLabelText('sync.wise.token'), { target: { value: 'tok-new' } })
      fireEvent.click(screen.getByRole('button', { name: 'sync.wise.replaceToken' }))

      await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/wise/connect', { token: 'tok-new' }))
      expect(await screen.findByRole('button', { name: 'sync.wise.sync' })).toBeEnabled()
    })

    it('turns a sync that Wise refuses into the replace-token form with a translated message', async () => {
      apiGet.mockResolvedValue(connected())
      apiPost.mockImplementation(() => {
        apiGet.mockResolvedValue(connected({ status: 'REAUTH_REQUIRED' }))
        return Promise.reject({
          response: { status: 422, data: { detail: 'Wise refused the API token.', code: 'SESSION_EXPIRED' } },
        })
      })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.sync' }))

      expect(await screen.findByText('sync.wise.errors.tokenRefused')).toBeInTheDocument()
      expect(screen.queryByText('Wise refused the API token.')).not.toBeInTheDocument()
      expect(await screen.findByRole('button', { name: 'sync.wise.replaceToken' })).toBeInTheDocument()
    })
  })

  describe('disconnect', () => {
    it('deletes the connection on confirm and shows the token form again', async () => {
      apiGet.mockResolvedValue(connected())
      apiDelete.mockImplementation(() => {
        apiGet.mockResolvedValue(DISCONNECTED)
        return Promise.resolve({ data: null })
      })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.wise.disconnect' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText('sync.wise.disconnectConfirm')).toBeInTheDocument()
      fireEvent.click(within(dialog).getByRole('button', { name: 'common.delete' }))

      await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/wise/connection'))
      expect(await screen.findByLabelText('sync.wise.token')).toHaveValue('')
    })
  })
})
