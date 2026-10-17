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
    t: (key: string, vars?: { count?: number }) => (vars?.count != null ? `${key}:${vars.count}` : key),
  }),
}))

vi.mock('sonner', () => ({
  toast: { success: toastSuccess, error: vi.fn() },
}))

const { SimplefinPanel } = await import('./SimplefinPanel')

const DISCONNECTED = {
  data: { connected: false, connectionId: null, status: null, lastSyncedAt: null, maskedToken: null },
}

function connected(overrides: Record<string, unknown> = {}) {
  return {
    data: {
      connected: true,
      connectionId: 1,
      status: 'CONNECTED',
      lastSyncedAt: '2026-07-07T08:30:00Z',
      maskedToken: '••••1234',
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
  return render(<SimplefinPanel {...props} />, { wrapper: Wrapper })
}

const connectButton = () => screen.getByRole('button', { name: 'sync.simplefin.connect' })

async function submitToken(value: string) {
  const input = await screen.findByLabelText('sync.simplefin.token')
  fireEvent.change(input, { target: { value } })
  fireEvent.click(connectButton())
  return input
}

describe('SimplefinPanel', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiPost.mockReset()
    apiDelete.mockReset()
    toastSuccess.mockReset()
  })

  describe('disconnected', () => {
    it('links to the SimpleFIN Bridge token page', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const link = await screen.findByRole('link', { name: 'sync.simplefin.createToken' })
      expect(link).toHaveAttribute('href', 'https://bridge.simplefin.org/simplefin/create')
    })

    it('keeps Connect disabled for an empty or whitespace-only token', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const input = await screen.findByLabelText('sync.simplefin.token')
      expect(connectButton()).toBeDisabled()

      fireEvent.change(input, { target: { value: '   ' } })
      expect(connectButton()).toBeDisabled()
      // Enter-key submission bypasses the disabled button; the handler must still refuse.
      fireEvent.submit(input.closest('form')!)
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('claims the trimmed token, syncs, toasts the account count and notifies onConnected', async () => {
      const onConnected = vi.fn()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockImplementation((url: string) => {
        if (url === '/simplefin/connect') {
          apiGet.mockResolvedValue(connected({ lastSyncedAt: null }))
          return Promise.resolve({ data: null })
        }
        return Promise.resolve({ data: [{ id: 1 }, { id: 2 }, { id: 3 }] })
      })

      renderPanel({ onConnected })
      await submitToken('  tok-xyz \n')

      await waitFor(() => expect(onConnected).toHaveBeenCalledTimes(1))
      expect(apiPost).toHaveBeenNthCalledWith(1, '/simplefin/connect', { token: 'tok-xyz' })
      expect(apiPost).toHaveBeenNthCalledWith(2, '/simplefin/sync')
      expect(toastSuccess).toHaveBeenCalledWith('sync.simplefin.syncedToast:3')
    })

    it.each([
      ['shows the server detail', { response: { status: 422, data: { detail: 'Token already claimed.' } } }, 'Token already claimed.'],
      ['hides an internal-looking detail', { response: { status: 500, data: { detail: 'java.lang.NullPointerException at com.picsou.Foo' } } }, 'sync.simplefin.errors.connectFailed'],
      ['falls back on a network failure', new Error('Network Error'), 'sync.simplefin.errors.connectFailed'],
    ])('on a failed connect, %s and keeps the form usable', async (_name, rejection, message) => {
      const onConnected = vi.fn()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockRejectedValue(rejection)

      renderPanel({ onConnected })
      const input = await submitToken('tok-used')

      expect(await screen.findByText(message)).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(1) // no sync after a failed claim
      expect(onConnected).not.toHaveBeenCalled()
      expect(input).toHaveValue('tok-used')
      expect(connectButton()).toBeEnabled()
    })

    it('lands on the connected card with a retryable Sync when the claim works but the first sync fails', async () => {
      const onConnected = vi.fn()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockImplementation((url: string) => {
        if (url === '/simplefin/connect') {
          apiGet.mockResolvedValue(connected({ lastSyncedAt: null }))
          return Promise.resolve({ data: null })
        }
        return Promise.reject({ response: { status: 502, data: { detail: 'SimpleFIN Bridge is unreachable.' } } })
      })

      renderPanel({ onConnected })
      await submitToken('tok')

      expect(await screen.findByText('SimpleFIN Bridge is unreachable.')).toBeInTheDocument()
      expect(await screen.findByRole('button', { name: 'sync.simplefin.sync' })).toBeEnabled()
      expect(screen.queryByLabelText('sync.simplefin.token')).not.toBeInTheDocument()
      expect(onConnected).not.toHaveBeenCalled()
    })
  })

  describe('connected', () => {
    it('shows the masked token, last sync date and sync button instead of the token form', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()

      expect(await screen.findByText('sync.simplefin.connected')).toBeInTheDocument()
      expect(screen.getByText('••••1234')).toBeInTheDocument()
      expect(screen.getByText(/^sync\.simplefin\.lastSync: .*2026/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'sync.simplefin.sync' })).toBeEnabled()
      expect(screen.queryByLabelText('sync.simplefin.token')).not.toBeInTheDocument()
    })

    it('says "never synced" when there is no last sync date', async () => {
      apiGet.mockResolvedValue(connected({ lastSyncedAt: null }))

      renderPanel()

      expect(await screen.findByText('sync.simplefin.neverSynced')).toBeInTheDocument()
    })

    it('shows the failure badge instead of "connected" when the last sync errored', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))

      renderPanel()

      expect(await screen.findByText('sync.simplefin.statusError')).toBeInTheDocument()
      expect(screen.queryByText('sync.simplefin.connected')).not.toBeInTheDocument()
    })

    it('syncs and toasts the account count', async () => {
      apiGet.mockResolvedValue(connected())
      apiPost.mockResolvedValue({ data: [{ id: 1 }] })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))

      await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('sync.simplefin.syncedToast:1'))
      expect(apiPost).toHaveBeenCalledWith('/simplefin/sync')
    })

    it('shows the sync error, then clears it after a later successful sync', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))
      apiPost
        .mockRejectedValueOnce({ response: { status: 422, data: { detail: 'SimpleFIN refused the stored access.' } } })
        .mockResolvedValueOnce({ data: [] })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))
      expect(await screen.findByText('SimpleFIN refused the stored access.')).toBeInTheDocument()

      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))
      await waitFor(() =>
        expect(screen.queryByText('SimpleFIN refused the stored access.')).not.toBeInTheDocument(),
      )
    })
  })

  describe('disconnect', () => {
    async function openDisconnectDialog() {
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.disconnect' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText('sync.simplefin.disconnectConfirm')).toBeInTheDocument()
      return dialog
    }

    it('keeps the connection when the confirmation is cancelled', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()
      const dialog = await openDisconnectDialog()
      fireEvent.click(within(dialog).getByRole('button', { name: 'common.cancel' }))

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(apiDelete).not.toHaveBeenCalled()
      expect(screen.getByText('••••1234')).toBeInTheDocument()
    })

    it('deletes the connection on confirm and shows the token form again', async () => {
      apiGet.mockResolvedValue(connected())
      apiDelete.mockImplementation(() => {
        apiGet.mockResolvedValue(DISCONNECTED)
        return Promise.resolve({ data: null })
      })

      renderPanel()
      const dialog = await openDisconnectDialog()
      fireEvent.click(within(dialog).getByRole('button', { name: 'common.delete' }))

      await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/simplefin/connection'))
      expect(await screen.findByLabelText('sync.simplefin.token')).toHaveValue('')
      expect(screen.queryByText('••••1234')).not.toBeInTheDocument()
    })

    it('closes the dialog and shows an error when the disconnect fails', async () => {
      apiGet.mockResolvedValue(connected())
      apiDelete.mockRejectedValue({
        response: { status: 500, data: { detail: 'Could not delete the stored connection.' } },
      })

      renderPanel()
      const dialog = await openDisconnectDialog()
      fireEvent.click(within(dialog).getByRole('button', { name: 'common.delete' }))

      expect(await screen.findByText('Could not delete the stored connection.')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(screen.getByRole('button', { name: 'sync.simplefin.disconnect' })).toBeEnabled()
    })
  })
})
