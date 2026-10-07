import '@testing-library/jest-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const { apiGet, apiPost, apiDelete, toastSuccess, toastError } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
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
  toast: { success: toastSuccess, error: toastError },
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

/** Promise whose settlement the test controls, to observe in-flight UI states. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
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

/** Types into the token field and submits the form. */
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
    toastError.mockReset()
  })

  afterEach(() => {
    cleanup()
  })

  describe('token form', () => {
    it('shows a loading message while the status is being fetched', () => {
      apiGet.mockReturnValue(new Promise(() => {}))

      renderPanel()

      expect(screen.getByText('common.loading')).toBeInTheDocument()
      expect(screen.queryByLabelText('sync.simplefin.token')).not.toBeInTheDocument()
    })

    it('renders a masked, non-autofilled token field', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const input = await screen.findByLabelText('sync.simplefin.token')
      expect(input).toHaveAttribute('type', 'password')
      expect(input).toHaveAttribute('autocomplete', 'off')
      expect(input).toBeRequired()
    })

    it('keeps Connect disabled for an empty or whitespace-only token and never calls the API', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const input = await screen.findByLabelText('sync.simplefin.token')
      expect(connectButton()).toBeDisabled()

      fireEvent.change(input, { target: { value: '   \t ' } })
      expect(connectButton()).toBeDisabled()

      // Enter-key submission bypasses the disabled button; the handler must still refuse.
      fireEvent.submit(input.closest('form')!)
      expect(apiPost).not.toHaveBeenCalled()

      fireEvent.change(input, { target: { value: 'abc' } })
      expect(connectButton()).toBeEnabled()
    })

    it('links to the SimpleFIN Bridge token page and opens it safely', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)

      renderPanel()

      const link = await screen.findByRole('link', { name: 'sync.simplefin.createToken' })
      expect(link).toHaveAttribute('href', 'https://bridge.simplefin.org/simplefin/create')
      expect(link).toHaveAttribute('target', '_blank')
      // `noreferrer` implies `noopener` in every supported browser; accept either spelling.
      expect(link.getAttribute('rel')).toMatch(/noopener|noreferrer/)
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

    it('disables Connect while the token is being claimed', async () => {
      const claim = deferred<{ data: null }>()
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockReturnValue(claim.promise)

      renderPanel()
      const input = await submitToken('tok')

      await waitFor(() => expect(connectButton()).toBeDisabled())
      // A second click must not claim the (single-use) token twice.
      fireEvent.click(connectButton())
      fireEvent.submit(input.closest('form')!)
      expect(apiPost.mock.calls.filter(([url]) => url === '/simplefin/connect')).toHaveLength(1)

      claim.reject({ response: { status: 422, data: { detail: 'nope' } } })
      await screen.findByText('nope')
    })

    it('shows the server error from a failed connect and keeps the form usable', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockRejectedValue({
        response: { status: 422, data: { detail: 'That setup token has already been claimed.' } },
      })
      const onConnected = vi.fn()

      renderPanel({ onConnected })
      const input = await submitToken('tok-used')

      expect(await screen.findByText('That setup token has already been claimed.')).toBeInTheDocument()
      expect(apiPost).toHaveBeenCalledTimes(1) // no sync after a failed claim
      expect(onConnected).not.toHaveBeenCalled()
      expect(toastSuccess).not.toHaveBeenCalled()
      // The form is still there so the user can fix the token and retry.
      expect(input).toBeInTheDocument()
      expect(input).toHaveValue('tok-used')
      expect(connectButton()).toBeEnabled()
    })

    it('replaces an internal-looking connect error with the friendly translation', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockRejectedValue({
        response: { status: 500, data: { detail: 'java.lang.NullPointerException at com.picsou.Foo' } },
      })

      renderPanel()
      await submitToken('tok')

      expect(await screen.findByText('sync.simplefin.errors.connectFailed')).toBeInTheDocument()
      expect(screen.queryByText(/NullPointerException/)).not.toBeInTheDocument()
    })

    it('falls back to the friendly translation on a network failure', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)
      apiPost.mockRejectedValue(new Error('Network Error'))

      renderPanel()
      await submitToken('tok')

      expect(await screen.findByText('sync.simplefin.errors.connectFailed')).toBeInTheDocument()
    })

    it('clears the previous error when the user submits again', async () => {
      apiGet.mockResolvedValue(DISCONNECTED)
      const retry = deferred<{ data: null }>()
      apiPost
        .mockRejectedValueOnce({ response: { status: 422, data: { detail: 'first failure' } } })
        .mockReturnValueOnce(retry.promise)

      renderPanel()
      await submitToken('tok')
      expect(await screen.findByText('first failure')).toBeInTheDocument()

      fireEvent.click(connectButton())
      await waitFor(() => expect(screen.queryByText('first failure')).not.toBeInTheDocument())

      retry.reject({ response: { status: 422, data: { detail: 'second failure' } } })
      expect(await screen.findByText('second failure')).toBeInTheDocument()
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
      expect(toastSuccess).not.toHaveBeenCalled()
    })
  })

  describe('connected state', () => {
    it('shows the connected badge, masked token and last sync date', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()

      expect(await screen.findByText('sync.simplefin.connected')).toBeInTheDocument()
      expect(screen.getByText('••••1234')).toBeInTheDocument()
      expect(screen.getByText(/^sync\.simplefin\.lastSync: .*2026/)).toBeInTheDocument()
      expect(screen.queryByText('sync.simplefin.neverSynced')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('sync.simplefin.token')).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'sync.simplefin.createToken' })).not.toBeInTheDocument()
    })

    it('says "never synced" when there is no last sync date', async () => {
      apiGet.mockResolvedValue(connected({ lastSyncedAt: null }))

      renderPanel()

      expect(await screen.findByText('sync.simplefin.neverSynced')).toBeInTheDocument()
      expect(screen.queryByText(/sync\.simplefin\.lastSync/)).not.toBeInTheDocument()
    })

    it('omits the token chip when the backend returns no masked token', async () => {
      apiGet.mockResolvedValue(connected({ maskedToken: null }))

      renderPanel()

      expect(await screen.findByText('sync.simplefin.connected')).toBeInTheDocument()
      expect(screen.queryByText(/••••/)).not.toBeInTheDocument()
    })

    it('shows the failure badge instead of "connected" when the last sync errored', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))

      renderPanel()

      expect(await screen.findByText('sync.simplefin.statusError')).toBeInTheDocument()
      expect(screen.queryByText('sync.simplefin.connected')).not.toBeInTheDocument()
      // Sync stays available so the user can retry.
      expect(screen.getByRole('button', { name: 'sync.simplefin.sync' })).toBeEnabled()
    })

    it('never renders the token in plain text, only the backend-provided mask', async () => {
      apiGet.mockResolvedValue(connected())

      const { container } = renderPanel()

      await screen.findByText('••••1234')
      expect(container.querySelector('input')).toBeNull()
    })
  })

  describe('sync', () => {
    it('disables Sync and shows the syncing label while the request is in flight', async () => {
      const sync = deferred<{ data: unknown[] }>()
      apiGet.mockResolvedValue(connected())
      apiPost.mockReturnValue(sync.promise)

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))

      const syncing = await screen.findByRole('button', { name: 'sync.simplefin.syncing' })
      expect(syncing).toBeDisabled()
      fireEvent.click(syncing)
      expect(apiPost).toHaveBeenCalledTimes(1)

      sync.resolve({ data: [{ id: 1 }] })

      const idle = await screen.findByRole('button', { name: 'sync.simplefin.sync' })
      expect(idle).toBeEnabled()
      expect(toastSuccess).toHaveBeenCalledWith('sync.simplefin.syncedToast:1')
    })

    it('shows the sync error, then clears it after a later successful sync', async () => {
      apiGet.mockResolvedValue(connected({ status: 'ERROR' }))
      apiPost
        .mockRejectedValueOnce({
          response: { status: 422, data: { detail: 'SimpleFIN refused the stored access.' } },
        })
        .mockResolvedValueOnce({ data: [] })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))
      expect(await screen.findByText('SimpleFIN refused the stored access.')).toBeInTheDocument()
      expect(toastSuccess).not.toHaveBeenCalled()

      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))
      await waitFor(() =>
        expect(screen.queryByText('SimpleFIN refused the stored access.')).not.toBeInTheDocument(),
      )
      expect(toastSuccess).toHaveBeenCalledWith('sync.simplefin.syncedToast:0')
    })

    it('uses the friendly fallback when the sync error carries no safe message', async () => {
      apiGet.mockResolvedValue(connected())
      apiPost.mockRejectedValue(new Error('Request failed with status code 500'))

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.sync' }))

      expect(await screen.findByText('sync.simplefin.errors.syncFailed')).toBeInTheDocument()
      expect(screen.queryByText(/status code 500/)).not.toBeInTheDocument()
    })
  })

  describe('disconnect', () => {
    function confirmButton(dialog: HTMLElement) {
      // The confirm label comes from ConfirmDialog's default; select it structurally
      // (the one button that is neither Cancel nor the dialog's close icon).
      const buttons = within(dialog)
        .getAllByRole('button')
        .filter((b) => b.textContent !== 'common.cancel' && !/close/i.test(b.textContent ?? ''))
      expect(buttons).toHaveLength(1)
      return buttons[0]
    }

    it('asks for confirmation and does not delete anything until confirmed', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.disconnect' }))

      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText('sync.simplefin.disconnectConfirm')).toBeInTheDocument()
      expect(apiDelete).not.toHaveBeenCalled()
    })

    it('keeps the connection when the confirmation is cancelled', async () => {
      apiGet.mockResolvedValue(connected())

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.disconnect' }))
      const dialog = await screen.findByRole('dialog')
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
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.disconnect' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(confirmButton(dialog))

      await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/simplefin/connection'))
      expect(await screen.findByLabelText('sync.simplefin.token')).toHaveValue('')
      expect(screen.queryByRole('button', { name: 'sync.simplefin.sync' })).not.toBeInTheDocument()
      expect(screen.queryByText('••••1234')).not.toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })

    it('closes the dialog and shows an error when the disconnect fails', async () => {
      apiGet.mockResolvedValue(connected())
      apiDelete.mockRejectedValue({
        response: { status: 500, data: { detail: 'Could not delete the stored connection.' } },
      })

      renderPanel()
      fireEvent.click(await screen.findByRole('button', { name: 'sync.simplefin.disconnect' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(confirmButton(dialog))

      expect(await screen.findByText('Could not delete the stored connection.')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      // Still connected: the user can retry.
      expect(screen.getByRole('button', { name: 'sync.simplefin.disconnect' })).toBeEnabled()
    })
  })
})
